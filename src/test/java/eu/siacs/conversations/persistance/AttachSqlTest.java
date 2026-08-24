package eu.siacs.conversations.persistance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Random;

/**
 * Pins {@link DatabaseBackend#buildAttachSql} to the statement the previous string-concatenating
 * code produced, byte for byte.
 *
 * <p>This helper exists only to keep unzeroable copies of the live database key off the JVM heap;
 * it must not change a single byte of what SQLCipher receives. It very nearly did: the key is
 * formatted as {@code x'<hex>'}, which itself contains single quotes, and the original code
 * doubled them (<code>keyStr.replace("'", "''")</code>) to survive the surrounding SQL string
 * literal. A first cut of the helper dropped that escaping, which would have handed SQLCipher a
 * different key and produced a database nothing could open — silently, until the next launch.
 */
public class AttachSqlTest {

    /** Reproduces Argon2KeyDerivation.formatAsRawSqlCipherKey without the Android dependency. */
    private static byte[] formatAsRawSqlCipherKey(final byte[] key) {
        final String hex = "0123456789abcdef";
        final byte[] out = new byte[2 + key.length * 2 + 1];
        out[0] = 'x';
        out[1] = '\'';
        for (int i = 0; i < key.length; i++) {
            final int b = key[i] & 0xFF;
            out[2 + i * 2] = (byte) hex.charAt(b >>> 4);
            out[2 + i * 2 + 1] = (byte) hex.charAt(b & 0x0F);
        }
        out[out.length - 1] = '\'';
        return out;
    }

    /** The exact expression the pre-fix code used, kept here as the reference implementation. */
    private static String legacyAttachSql(final String escapedPath, final byte[] rawKey) {
        final String keyStr = new String(rawKey, StandardCharsets.UTF_8);
        final String attachKeySql = "'" + keyStr.replace("'", "''") + "'";
        return "ATTACH DATABASE " + escapedPath + " AS encrypted KEY " + attachKeySql;
    }

    /**
     * DatabaseUtils.sqlEscapeString is an Android stub under plain unit tests, so compare only the
     * part after the path: the prefix is byte-identical by construction in both implementations.
     */
    private static String keyClauseOf(final String sql) {
        final int i = sql.indexOf(" AS encrypted KEY ");
        assertTrue("malformed statement: " + sql, i >= 0);
        return sql.substring(i);
    }

    @Test
    public void matchesTheLegacyStatementForRandomKeys() {
        final Random random = new Random(0xDBBEEFL);
        for (int i = 0; i < 2000; i++) {
            final byte[] key = new byte[32];
            random.nextBytes(key);
            final byte[] raw = formatAsRawSqlCipherKey(key);
            final String expected = keyClauseOf(legacyAttachSql("<path>", raw));
            final String actual = keyClauseOf(new String(
                    DatabaseBackend.buildAttachSql("<path>", raw)).replace("'<path>'", "<path>"));
            assertEquals(expected, actual);
        }
    }

    /** The quotes around the hex must arrive doubled, or SQLCipher sees a different key. */
    @Test
    public void escapesTheQuotesAroundTheHexPayload() {
        final byte[] raw = formatAsRawSqlCipherKey(new byte[32]);
        final String sql = new String(DatabaseBackend.buildAttachSql("<path>", raw));
        final String keyClause = keyClauseOf(sql);
        assertTrue("key literal must open with a doubled quote: " + keyClause,
                keyClause.contains("KEY 'x''"));
        assertTrue("key literal must close with doubled + closing quote: " + keyClause,
                keyClause.endsWith("'''"));
        // the payload itself is covered by carriesTheFullKeyPayload
    }

    /** Every byte of the formatted key must survive into the statement. */
    @Test
    public void carriesTheFullKeyPayload() {
        final byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) (i * 7 + 1);
        }
        final byte[] raw = formatAsRawSqlCipherKey(key);
        final String sql = new String(DatabaseBackend.buildAttachSql("<path>", raw));
        final String hex = new String(raw, StandardCharsets.UTF_8)
                .replace("x'", "").replace("'", "");
        assertEquals(64, hex.length());
        assertTrue("statement must contain the whole hex payload", sql.contains(hex));
    }
}
