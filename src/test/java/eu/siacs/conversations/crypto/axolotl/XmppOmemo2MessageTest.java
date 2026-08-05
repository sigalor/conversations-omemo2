package eu.siacs.conversations.crypto.axolotl;

import eu.siacs.conversations.xmpp.Jid;
import org.junit.Assert;
import org.junit.Test;

public class XmppOmemo2MessageTest {

    private static String hex(final byte[] bytes) {
        final StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (final byte b : bytes) {
            builder.append(Character.forDigit((b >> 4) & 0xF, 16));
            builder.append(Character.forDigit(b & 0xF, 16));
        }
        return builder.toString();
    }

    private static byte[] range(final int from, final int toInclusive) {
        final byte[] bytes = new byte[toInclusive - from + 1];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (from + i);
        }
        return bytes;
    }

    private static byte[] binding() {
        return XmppOmemo2Message.computeContextBinding(
                Jid.of("alice@example.com"), Jid.of("bob@example.com"), 42);
    }

    private static byte[] messageKey() {
        final byte[] key = new byte[32];
        java.util.Arrays.fill(key, (byte) 0x07);
        return key;
    }

    /**
     * NIST SP 800-185 KMAC256 sample vectors #4, #5 and #6, run through the same code path the
     * protocol uses.
     *
     * <p>The desktop client builds KMAC by hand over cSHAKE256 rather than using a library, so the
     * two implementations are independent and must agree byte-for-byte or no cross-client message
     * decrypts. Pinning both to NIST's own vectors rather than to each other means a shared
     * misreading of the spec cannot cancel out.
     */
    @Test
    public void kmac256NistSp800_185Vectors() {
        final byte[] key = range(0x40, 0x5F);
        final byte[] tagged = "My Tagged Application".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        final byte[] data = range(0x00, 0xC7);

        Assert.assertEquals(
                "SP 800-185 KMAC256 sample #4",
                "20c570c31346f703c9ac36c61c03cb64c3970d0cfc787e9b79599d273a68d2f7"
                        + "f69d4cc3de9d104a351689f27cf6f5951f0103f33f4f24871024d9c27773a8dd",
                hex(XmppOmemo2Message.kmac256(key, range(0x00, 0x03), tagged, 64)));
        Assert.assertEquals(
                "SP 800-185 KMAC256 sample #5",
                "75358cf39e41494e949707927cee0af20a3ff553904c86b08f21cc414bcfd691"
                        + "589d27cf5e15369cbbff8b9a4c2eb17800855d0235ff635da82533ec6b759b69",
                hex(XmppOmemo2Message.kmac256(key, data, new byte[0], 64)));
        Assert.assertEquals(
                "SP 800-185 KMAC256 sample #6",
                "b58618f71f92e1d56c1b8c55ddd7cd188b97b4ca4d99831eb2699a837da2e4d9"
                        + "70fbacfde50033aea585f1a2708510c32d07880801bd182898fe476876fc8965",
                hex(XmppOmemo2Message.kmac256(key, data, tagged, 64)));
    }

    /**
     * Known-answer test locking the §5.5 key commitment to the desktop client's
     * {@code omemo2_key_commitment} and to the vector documented in the proto-XEP. Both clients
     * publish this value in {@code <commit>} and each rejects a payload whose commitment does not
     * match, so a divergence here does not degrade gracefully — every cross-client message fails.
     */
    @Test
    public void keyCommitmentKnownAnswer() {
        Assert.assertEquals(
                "e04e685382db88563a43d2a5d55218bf917b5b57989b377636d88cf7f479bfc5"
                        + "31b5a1a87a4eeef2909d8510a27f0b83e9f183361686ad5a3b00194794bde224",
                hex(XmppOmemo2Message.keyCommitment(messageKey(), binding())));
    }

    /**
     * The payload key/IV must match the desktop byte-for-byte too — it is the other KMAC256
     * customization string, and a mismatch means every message fails its GCM tag.
     */
    @Test
    public void payloadKeysKnownAnswer() {
        final byte[] derived = XmppOmemo2Message.derivePayloadKeys(messageKey(), binding());
        Assert.assertEquals(44, derived.length);
        Assert.assertEquals(
                "91d133b399016d8ed75e9e585ecdcd7ffb1c95b9b364e188784ccbab610d97e7",
                hex(java.util.Arrays.copyOfRange(derived, 0, 32)));
        Assert.assertEquals("058b5c01dd3d1eac0e564269", hex(java.util.Arrays.copyOfRange(derived, 32, 44)));
    }

    /** The commitment must be 64 bytes and must change with the message key it commits to. */
    @Test
    public void keyCommitmentBindsTheMessageKey() {
        final byte[] one = XmppOmemo2Message.keyCommitment(new byte[32], binding());
        final byte[] other = new byte[32];
        other[0] = 1;
        Assert.assertEquals(64, one.length);
        Assert.assertNotEquals(hex(one), hex(XmppOmemo2Message.keyCommitment(other, binding())));
    }

    /**
     * The commitment and the payload key are the same primitive under the same key, separated only
     * by the customization string — so it is worth asserting explicitly that they do not collide.
     */
    @Test
    public void commitmentAndPayloadKeyAreSeparated() {
        final byte[] commit = XmppOmemo2Message.keyCommitment(messageKey(), binding());
        final byte[] derived = XmppOmemo2Message.derivePayloadKeys(messageKey(), binding());
        Assert.assertNotEquals(
                hex(java.util.Arrays.copyOfRange(commit, 0, 44)), hex(derived));
    }
}
