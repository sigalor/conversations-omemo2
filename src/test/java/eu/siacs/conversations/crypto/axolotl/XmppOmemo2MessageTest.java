package eu.siacs.conversations.crypto.axolotl;

import eu.siacs.conversations.xml.Element;
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

    // ---- parse-time bounds on an attacker-supplied header ----

    private static final Jid SENDER = Jid.of("mallory@example.com");
    private static final Jid RECIPIENT = Jid.of("alice@example.com");

    /**
     * {@code <encrypted><header sid='1'><keys jid=…>} with {@code keyCount} {@code <key>}
     * children, plus a {@code <payload/>}. The key contents are irrelevant — the unit-test
     * runtime stubs {@code Base64.decode} — so this exercises the parser's bookkeeping, which is
     * exactly what the cap governs.
     */
    private static Element headerWithKeys(final Jid jid, final int keyCount) {
        final Element keys = new Element("keys", AxolotlService.PEP_PREFIX);
        keys.setAttribute("jid", jid.toString());
        for (int i = 0; i < keyCount; i++) {
            final Element key = new Element("key", AxolotlService.PEP_PREFIX);
            key.setAttribute("rid", String.valueOf(i + 1));
            key.setContent("AAAA");
            keys.addChild(key);
        }
        final Element header = new Element("header", AxolotlService.PEP_PREFIX);
        header.setAttribute("sid", "1");
        header.addChild(keys);
        final Element payload = new Element("payload", AxolotlService.PEP_PREFIX);
        payload.setContent("AAAA");
        final Element encrypted = new Element("encrypted", AxolotlService.PEP_PREFIX);
        encrypted.addChild(header);
        encrypted.addChild(payload);
        return encrypted;
    }

    /**
     * A real account's block is kept whole: it holds one key per device, and the device list is
     * already refused past {@link AxolotlService#MAX_DEVICES_PER_JID}, so the cap cannot cost a
     * legitimate recipient a key.
     */
    @Test
    public void legitimateKeysBlockIsKeptWhole() {
        for (final int n : new int[] {1, 5, AxolotlService.MAX_DEVICES_PER_JID}) {
            final XmppOmemo2Message message =
                    XmppOmemo2Message.fromElement(headerWithKeys(RECIPIENT, n), SENDER);
            Assert.assertNotNull("header with " + n + " keys must parse", message);
            Assert.assertEquals(n, message.keyCountFor(RECIPIENT));
        }
    }

    /**
     * Parsing decodes every key of every block up front, including blocks the receiving path
     * never reads. Without a bound, a hostile header buys the sender a decoded copy of as much
     * data as it cares to send, held for the life of the message object, before anything checks
     * who the message is even addressed to.
     */
    @Test
    public void oversizedKeysBlockIsCapped() {
        final XmppOmemo2Message message =
                XmppOmemo2Message.fromElement(
                        headerWithKeys(RECIPIENT, AxolotlService.MAX_DEVICES_PER_JID * 50),
                        SENDER);
        Assert.assertNotNull("an oversized header is truncated, not rejected", message);
        Assert.assertEquals(AxolotlService.MAX_DEVICES_PER_JID, message.keyCountFor(RECIPIENT));
    }

    /**
     * The bound is per block, so a third party's oversized block cannot crowd ours out — each is
     * bounded on its own.
     */
    @Test
    public void capAppliesPerBlockNotAcrossThem() {
        final Jid other = Jid.of("bob@example.com");
        final Element encrypted =
                headerWithKeys(other, AxolotlService.MAX_DEVICES_PER_JID * 10);
        final Element ours = new Element("keys", AxolotlService.PEP_PREFIX);
        ours.setAttribute("jid", RECIPIENT.toString());
        final Element key = new Element("key", AxolotlService.PEP_PREFIX);
        key.setAttribute("rid", "42");
        key.setContent("AAAA");
        ours.addChild(key);
        encrypted.findChild("header").addChild(ours);

        final XmppOmemo2Message message = XmppOmemo2Message.fromElement(encrypted, SENDER);
        Assert.assertNotNull(message);
        Assert.assertEquals(AxolotlService.MAX_DEVICES_PER_JID, message.keyCountFor(other));
        Assert.assertEquals("our own block is unaffected", 1, message.keyCountFor(RECIPIENT));
    }
}
