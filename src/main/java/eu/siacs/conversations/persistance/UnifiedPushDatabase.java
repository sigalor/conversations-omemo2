package eu.siacs.conversations.persistance;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.DatabaseUtils;
import net.zetetic.database.sqlcipher.SQLiteDatabase;
import net.zetetic.database.sqlcipher.SQLiteOpenHelper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;

import java.io.File;
import java.util.List;

import eu.siacs.conversations.AppSettings;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.services.UnifiedPushBroker;
import eu.siacs.conversations.utils.FileHelper;

public class UnifiedPushDatabase extends SQLiteOpenHelper {

    private static final String DATABASE_NAME = "unified-push-distributor";
    private static final int DATABASE_VERSION = 1;

    private static UnifiedPushDatabase instance;

    public static synchronized void closeInstance() {
        if (instance != null) {
            instance.close();
            instance = null;
        }
    }

    public static synchronized void migrate(Context context, char[] oldPassword, char[] newPassword) throws Exception {
        closeInstance();
        System.loadLibrary("sqlcipher");
        final AppSettings settings = new AppSettings(context);
        File dbFile = context.getDatabasePath(DATABASE_NAME);

        final byte[] newSalt = (newPassword != null)
                ? eu.siacs.conversations.Argon2KeyDerivation.INSTANCE.generateSalt()
                : null;
        final byte[] newRawKey = (newPassword != null)
                ? eu.siacs.conversations.Argon2KeyDerivation.INSTANCE.deriveRawKeyBytes(newPassword, newSalt)
                : null;

        if (!dbFile.exists()) {
            persistNewKeyState(settings, newPassword, newSalt, newRawKey);
            if (newRawKey != null) java.util.Arrays.fill(newRawKey, (byte) 0);
            return;
        }

        File tempFile = context.getDatabasePath(DATABASE_NAME + ".tmp");
        if (tempFile.exists() && !tempFile.delete()) {
            if (newRawKey != null) java.util.Arrays.fill(newRawKey, (byte) 0);
            throw new java.io.IOException("Failed to delete existing temporary database file");
        }
        if (tempFile.getParentFile() != null && !tempFile.getParentFile().exists() && !tempFile.getParentFile().mkdirs()) {
            if (newRawKey != null) java.util.Arrays.fill(newRawKey, (byte) 0);
            throw new java.io.IOException("Failed to create database directory");
        }
        if (!tempFile.createNewFile()) {
            if (newRawKey != null) java.util.Arrays.fill(newRawKey, (byte) 0);
            throw new java.io.IOException("Failed to create temporary database file");
        }

        // Open source DB with the old key.  Use the UPDB-specific salt (not the main DB's)
        // so that a prior main-DB migration cannot corrupt this derivation.
        final byte[] oldKeyBytes;
        final byte[] oldUpdbSalt = settings.getArgon2SaltForUpdb();
        if (oldUpdbSalt != null) {
            // UPDB was already migrated to Argon2id — derive its key with the UPDB salt.
            oldKeyBytes = (oldPassword != null)
                    ? eu.siacs.conversations.Argon2KeyDerivation.INSTANCE.deriveRawKeyBytes(oldPassword, oldUpdbSalt)
                    : null;
        } else {
            // UPDB not yet on Argon2id (or no encryption) — use PBKDF2 passphrase bytes.
            oldKeyBytes = oldPassword != null ? DatabaseBackend.charsToUtf8Bytes(oldPassword) : null;
        }

        SQLiteDatabase db;
        try {
            db = SQLiteDatabase.openDatabase(
                    dbFile.getAbsolutePath(), oldKeyBytes, null,
                    SQLiteDatabase.OPEN_READWRITE | SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING,
                    DatabaseBackend.hookForKey(oldKeyBytes));
        } finally {
            if (oldKeyBytes != null) java.util.Arrays.fill(oldKeyBytes, (byte) 0);
        }

        int version = db.getVersion();

        final boolean isEmpty;
        try (final Cursor c = db.rawQuery("SELECT count(*) FROM sqlite_master WHERE type='table'", null)) {
            isEmpty = version == 0 && c != null && c.moveToFirst() && c.getInt(0) == 0;
        }
        if (isEmpty) {
            db.close();
            tempFile.delete();
            FileHelper.secureDelete(dbFile);
            FileHelper.secureDelete(new File(dbFile.getAbsolutePath() + "-wal"));
            FileHelper.secureDelete(new File(dbFile.getAbsolutePath() + "-shm"));
            persistNewKeyState(settings, newPassword, newSalt, newRawKey);
            if (newRawKey != null) java.util.Arrays.fill(newRawKey, (byte) 0);
            return;
        }

        try {
            db.rawExecSQL("PRAGMA cipher_default_use_hmac = ON;");
            db.rawExecSQL("PRAGMA cipher_default_memory_security = ON;");

            // Wrap key in SQL string quotes — see DatabaseBackend.migrate() for the explanation
            // of why a SQL blob literal (x'...') would silently use PBKDF2 instead of raw mode.
            final String attachKeySql;
            if (newRawKey != null) {
                String keyStr = new String(newRawKey, java.nio.charset.StandardCharsets.UTF_8);
                attachKeySql = "'" + keyStr.replace("'", "''") + "'";
            } else {
                attachKeySql = "''";
            }
            db.rawExecSQL("ATTACH DATABASE " + DatabaseUtils.sqlEscapeString(tempFile.getAbsolutePath())
                    + " AS encrypted KEY " + attachKeySql);
            db.rawExecSQL("SELECT sqlcipher_export('encrypted');");
            db.rawExecSQL("PRAGMA encrypted.user_version = " + version);
            db.rawExecSQL("DETACH DATABASE encrypted;");
        } finally {
            db.close();
            if (newRawKey != null) java.util.Arrays.fill(newRawKey, (byte) 0);
        }

        final File backupFile = context.getDatabasePath(DATABASE_NAME + ".bak");
        if (backupFile.exists() && !backupFile.delete()) {
            throw new java.io.IOException("Failed to delete existing backup file");
        }

        PreferenceManager.getDefaultSharedPreferences(context)
                .edit().putBoolean("rekey_migration_updb_in_progress", true).commit();

        boolean prefsUpdated = false;
        try {
            if (!dbFile.renameTo(backupFile)) {
                throw new java.io.IOException("Failed to backup old database file");
            }
            if (!tempFile.renameTo(dbFile)) {
                if (!backupFile.renameTo(dbFile)) {
                    Log.e(Config.LOGTAG, "updb rekey: CRITICAL — failed to rollback after temp rename failure");
                }
                throw new java.io.IOException("Failed to rename temporary database file");
            }
            persistNewKeyState(settings, newPassword, newSalt, null);
            prefsUpdated = true;
            PreferenceManager.getDefaultSharedPreferences(context)
                    .edit().remove("rekey_migration_updb_in_progress").commit();
            FileHelper.secureDelete(backupFile);
            FileHelper.secureDelete(new File(backupFile.getAbsolutePath() + "-wal"));
            FileHelper.secureDelete(new File(backupFile.getAbsolutePath() + "-shm"));
            FileHelper.secureDelete(new File(dbFile.getAbsolutePath() + "-wal"));
            FileHelper.secureDelete(new File(dbFile.getAbsolutePath() + "-shm"));
        } catch (Exception e) {
            if (!prefsUpdated) {
                PreferenceManager.getDefaultSharedPreferences(context)
                        .edit().remove("rekey_migration_updb_in_progress").apply();
            }
            throw e;
        }
    }

    private static void persistNewKeyState(
            AppSettings settings, char[] newPassword, byte[] newSalt, byte[] ignoredRawKey) {
        if (newPassword != null) {
            // Only update the UPDB-specific salt; the shared password and KDF flag are
            // managed by DatabaseBackend.migrate() which always runs before this.
            settings.setUpdbPasswordAndSalt(newPassword, newSalt);
            settings.setArgon2idKdf(); // idempotent if already set by DatabaseBackend
        } else {
            // Disabling encryption — clear the UPDB salt and remaining Argon2 state.
            // (Password and main DB salt are already cleared by DatabaseBackend.migrate().)
            settings.setDatabasePassword(null); // idempotent
            settings.clearArgon2State();        // clears UPDB salt + KDF flag
        }
    }

    /**
     * Derives the key bytes for the UnifiedPush distributor database.
     * Uses the UPDB-specific Argon2id salt when available; falls back to the PBKDF2
     * passphrase path when the UPDB has not yet been migrated or has no encryption.
     */
    private static byte[] getKeyBytesForUpdb(Context context) {
        final AppSettings appSettings = new AppSettings(context);
        final byte[] updbSalt = appSettings.getArgon2SaltForUpdb();
        if (updbSalt != null) {
            final char[] password = appSettings.getDatabasePasswordChars();
            if (password == null) return null;
            try {
                return eu.siacs.conversations.Argon2KeyDerivation.INSTANCE
                        .deriveRawKeyBytes(password, updbSalt);
            } finally {
                java.util.Arrays.fill(password, '\0');
            }
        }
        // UPDB not yet migrated to Argon2id, or no encryption.
        final char[] chars = appSettings.getDatabasePasswordChars();
        if (chars == null) return null;
        final byte[] bytes = DatabaseBackend.charsToUtf8Bytes(chars);
        java.util.Arrays.fill(chars, '\0');
        return bytes;
    }

    private UnifiedPushDatabase(@Nullable Context context) {
        this(context, getKeyBytesForUpdb(context));
    }

    private UnifiedPushDatabase(@Nullable Context context, byte[] keyBytes) {
        super(context, DATABASE_NAME, keyBytes, null, DATABASE_VERSION, 0, null,
                DatabaseBackend.hookForKey(keyBytes), true);
    }

    public static UnifiedPushDatabase getInstance(final Context context) {
        synchronized (UnifiedPushDatabase.class) {
            if (instance == null) {
                System.loadLibrary("sqlcipher");
                instance = new UnifiedPushDatabase(context.getApplicationContext());
            }
            return instance;
        }
    }


    @Override
    public void onCreate(final SQLiteDatabase sqLiteDatabase) {
        sqLiteDatabase.execSQL(
                "CREATE TABLE if not exists push (account TEXT, transport TEXT, application TEXT NOT NULL, instance TEXT NOT NULL UNIQUE, endpoint TEXT, expiration NUMBER DEFAULT 0)");
    }

    public boolean register(final String application, final String instance) {
        final SQLiteDatabase sqLiteDatabase = getWritableDatabase();
        sqLiteDatabase.beginTransaction();
        final Optional<String> existingApplication;
        try (final Cursor cursor =
                sqLiteDatabase.query(
                        "push",
                        new String[] {"application"},
                        "instance=?",
                        new String[] {instance},
                        null,
                        null,
                        null)) {
            if (cursor != null && cursor.moveToFirst()) {
                existingApplication = Optional.of(cursor.getString(0));
            } else {
                existingApplication = Optional.absent();
            }
        }
        if (existingApplication.isPresent()) {
            sqLiteDatabase.setTransactionSuccessful();
            sqLiteDatabase.endTransaction();
            return application.equals(existingApplication.get());
        }
        final ContentValues contentValues = new ContentValues();
        contentValues.put("application", application);
        contentValues.put("instance", instance);
        contentValues.put("expiration", 0);
        final long inserted = sqLiteDatabase.insert("push", null, contentValues);
        if (inserted > 0) {
            Log.d(Config.LOGTAG, "inserted new application/instance tuple into unified push db");
        }
        sqLiteDatabase.setTransactionSuccessful();
        sqLiteDatabase.endTransaction();
        return true;
    }

    public List<PushTarget> getRenewals(final String account, final String transport) {
        final ImmutableList.Builder<PushTarget> renewalBuilder = ImmutableList.builder();
        final long expiration = System.currentTimeMillis() + UnifiedPushBroker.TIME_TO_RENEW;
        final SQLiteDatabase sqLiteDatabase = getReadableDatabase();
        try (final Cursor cursor =
                sqLiteDatabase.query(
                        "push",
                        new String[] {"application", "instance"},
                        "account <> ? OR transport <> ? OR expiration < " + expiration,
                        new String[] {account, transport},
                        null,
                        null,
                        null)) {
            while (cursor != null && cursor.moveToNext()) {
                renewalBuilder.add(
                        new PushTarget(
                                cursor.getString(cursor.getColumnIndexOrThrow("application")),
                                cursor.getString(cursor.getColumnIndexOrThrow("instance"))));
            }
        }
        return renewalBuilder.build();
    }

    public ApplicationEndpoint getEndpoint(
            final String account, final String transport, final String instance) {
        final long expiration = System.currentTimeMillis() + UnifiedPushBroker.TIME_TO_RENEW;
        final SQLiteDatabase sqLiteDatabase = getReadableDatabase();
        try (final Cursor cursor =
                sqLiteDatabase.query(
                        "push",
                        new String[] {"application", "endpoint"},
                        "account = ? AND transport = ? AND instance = ? AND endpoint IS NOT NULL AND expiration >= "
                                + expiration,
                        new String[] {account, transport, instance},
                        null,
                        null,
                        null)) {
            if (cursor != null && cursor.moveToFirst()) {
                return new ApplicationEndpoint(
                        cursor.getString(cursor.getColumnIndexOrThrow("application")),
                        cursor.getString(cursor.getColumnIndexOrThrow("endpoint")));
            }
        }
        return null;
    }

    public List<PushTarget> deletePushTargets() {
        final SQLiteDatabase sqLiteDatabase = getReadableDatabase();
        final ImmutableList.Builder<PushTarget> builder = new ImmutableList.Builder<>();
        try (final Cursor cursor = sqLiteDatabase.query("push",new String[]{"application","instance"},null,null,null,null,null)) {
            if (cursor != null && cursor.moveToFirst()) {
                builder.add(new PushTarget(
                        cursor.getString(cursor.getColumnIndexOrThrow("application")),
                        cursor.getString(cursor.getColumnIndexOrThrow("instance"))));
            }
        } catch (final Exception e) {
            Log.d(Config.LOGTAG,"unable to retrieve push targets",e);
            return builder.build();
        }
        sqLiteDatabase.delete("push",null,null);
        return builder.build();
    }

    public boolean hasEndpoints(final UnifiedPushBroker.Transport transport) {
        final SQLiteDatabase sqLiteDatabase = getReadableDatabase();
        try (final Cursor cursor =
                sqLiteDatabase.rawQuery(
                        "SELECT EXISTS(SELECT endpoint FROM push WHERE account = ? AND transport = ?)",
                        new String[] {
                            transport.account.getUuid(), transport.transport.toString()
                        })) {
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getInt(0) > 0;
            }
        }
        return false;
    }

    @Override
    public void onUpgrade(
            final SQLiteDatabase sqLiteDatabase, final int oldVersion, final int newVersion) {}

    public boolean updateEndpoint(
            final String instance,
            final String account,
            final String transport,
            final String endpoint,
            final long expiration) {
        final SQLiteDatabase sqLiteDatabase = getWritableDatabase();
        sqLiteDatabase.beginTransaction();
        final String existingEndpoint;
        try (final Cursor cursor =
                sqLiteDatabase.query(
                        "push",
                        new String[] {"endpoint"},
                        "instance=?",
                        new String[] {instance},
                        null,
                        null,
                        null)) {
            if (cursor != null && cursor.moveToFirst()) {
                existingEndpoint = cursor.getString(0);
            } else {
                existingEndpoint = null;
            }
        }
        final ContentValues contentValues = new ContentValues();
        contentValues.put("account", account);
        contentValues.put("transport", transport);
        contentValues.put("endpoint", endpoint);
        contentValues.put("expiration", expiration);
        sqLiteDatabase.update("push", contentValues, "instance=?", new String[] {instance});
        sqLiteDatabase.setTransactionSuccessful();
        sqLiteDatabase.endTransaction();
        return !endpoint.equals(existingEndpoint);
    }

    public List<PushTarget> getPushTargets(final String account, final String transport) {
        final ImmutableList.Builder<PushTarget> renewalBuilder = ImmutableList.builder();
        final SQLiteDatabase sqLiteDatabase = getReadableDatabase();
        try (final Cursor cursor =
                sqLiteDatabase.query(
                        "push",
                        new String[] {"application", "instance"},
                        "account = ?",
                        new String[] {account},
                        null,
                        null,
                        null)) {
            while (cursor != null && cursor.moveToNext()) {
                renewalBuilder.add(
                        new PushTarget(
                                cursor.getString(cursor.getColumnIndexOrThrow("application")),
                                cursor.getString(cursor.getColumnIndexOrThrow("instance"))));
            }
        }
        return renewalBuilder.build();
    }

    public boolean deleteInstance(final String instance) {
        final SQLiteDatabase sqLiteDatabase = getReadableDatabase();
        final int rows = sqLiteDatabase.delete("push", "instance=?", new String[] {instance});
        return rows >= 1;
    }

    public boolean deleteApplication(final String application) {
        final SQLiteDatabase sqLiteDatabase = getReadableDatabase();
        final int rows = sqLiteDatabase.delete("push", "application=?", new String[] {application});
        return rows >= 1;
    }

    public static class ApplicationEndpoint {
        public final String application;
        public final String endpoint;

        public ApplicationEndpoint(String application, String endpoint) {
            this.application = application;
            this.endpoint = endpoint;
        }
    }

    public static class PushTarget {
        public final String application;
        public final String instance;

        public PushTarget(final String application, final String instance) {
            this.application = application;
            this.instance = instance;
        }

        @NonNull
        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                    .add("application", application)
                    .add("instance", instance)
                    .toString();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            PushTarget that = (PushTarget) o;
            return Objects.equal(application, that.application)
                    && Objects.equal(instance, that.instance);
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(application, instance);
        }
    }
}
