package eu.siacs.conversations.crypto.axolotl;

import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xmpp.Jid;
import org.junit.Assert;
import org.junit.Test;

/**
 * The legacy OMEMO container is parsed straight off an incoming stanza, before any trust decision.
 * Every malformation has to come back as {@link IllegalArgumentException} - the type this
 * constructor already uses for a missing header, a bad source id, a duplicate iv and so on -
 * because one of its call sites reaches it without a try: {@code Content.getSecurity()}, from an
 * inbound Jingle session-initiate, whose exceptions land on the connection thread.
 */
public class XmppAxolotlMessageParseTest {

    private static final Jid FROM = Jid.of("mallory@example.com");

    /** Builds {@code <encrypted><header sid=…>…</header>[<payload/>]</encrypted>}. */
    private static Element container(final Element header, final Element payload) {
        final var encrypted = new Element("encrypted", AxolotlService.PEP_PREFIX);
        if (header != null) {
            encrypted.addChild(header);
        }
        if (payload != null) {
            encrypted.addChild(payload);
        }
        return encrypted;
    }

    private static Element header(final String sid, final Element... children) {
        final var header = new Element("header", AxolotlService.PEP_PREFIX);
        if (sid != null) {
            header.setAttribute("sid", sid);
        }
        for (final Element child : children) {
            header.addChild(child);
        }
        return header;
    }

    private static Element key(final String rid, final String content) {
        final var key = new Element("key", AxolotlService.PEP_PREFIX);
        if (rid != null) {
            key.setAttribute("rid", rid);
        }
        if (content != null) {
            key.setContent(content);
        }
        return key;
    }

    private static Element iv(final String content) {
        final var iv = new Element("iv", AxolotlService.PEP_PREFIX);
        if (content != null) {
            iv.setContent(content);
        }
        return iv;
    }

    private static void assertRejected(final Element encrypted, final String what) {
        try {
            XmppAxolotlMessage.fromElement(encrypted, FROM);
            Assert.fail("expected " + what + " to be rejected");
        } catch (final IllegalArgumentException expected) {
            // the contract: malformed input is rejected, not thrown past the caller
        }
    }

    // ---- the regression: empty elements used to dereference null ----

    @Test
    public void keyWithoutContentIsRejected() {
        assertRejected(
                container(header("1", key("1", null), iv("AAAAAAAAAAAAAAAA")), null),
                "a <key/> with no content");
    }

    @Test
    public void ivWithoutContentIsRejected() {
        assertRejected(container(header("1", key("1", "AAAA"), iv(null)), null), "an <iv/> with no content");
    }

    @Test
    public void payloadWithoutContentIsRejected() {
        final var payload = new Element("payload", AxolotlService.PEP_PREFIX);
        assertRejected(
                container(header("1", key("1", "AAAA"), iv("AAAAAAAAAAAAAAAA")), payload),
                "a <payload/> with no content");
    }

    // ---- malformations the constructor already handled, pinned so they stay that way ----

    @Test
    public void missingHeaderIsRejected() {
        assertRejected(container(null, null), "a container with no header");
    }

    @Test
    public void missingSourceIdIsRejected() {
        assertRejected(container(header(null, key("1", "AAAA"), iv("AAAA")), null), "a header with no sid");
    }

    @Test
    public void nonNumericSourceIdIsRejected() {
        assertRejected(container(header("abc", key("1", "AAAA"), iv("AAAA")), null), "a non numeric sid");
    }

    @Test
    public void negativeSourceIdIsRejected() {
        assertRejected(container(header("-1", key("1", "AAAA"), iv("AAAA")), null), "a negative sid");
    }

    @Test
    public void headerWithoutKeysIsRejected() {
        assertRejected(container(header("1", iv("AAAA")), null), "a header carrying no <key/>");
    }

    @Test
    public void headerWithoutIvIsRejected() {
        assertRejected(container(header("1", key("1", "AAAA")), null), "a header carrying no <iv/>");
    }

    @Test
    public void duplicateIvIsRejected() {
        assertRejected(
                container(header("1", key("1", "AAAA"), iv("AAAA"), iv("BBBB")), null),
                "a header with two <iv/> entries");
    }

    @Test
    public void nonNumericRecipientIdIsRejected() {
        assertRejected(
                container(header("1", key("abc", "AAAA"), iv("AAAA")), null), "a non numeric rid");
    }

    /**
     * Nothing above should have made a well formed container unparseable.
     *
     * <p>It cannot be parsed to completion here: {@code android.util.Base64} is a stub on the JVM
     * and hands back null, so the constructor later reports a missing iv. What this does pin is
     * that a populated element gets past the content check - the rejection, when it comes, is the
     * downstream one and never "carries no content".
     */
    @Test
    public void wellFormedContainerPassesTheContentChecks() {
        try {
            XmppAxolotlMessage.fromElement(
                    container(header("42", key("7", "AAAA"), iv("AAAAAAAAAAAAAAAA")), null), FROM);
        } catch (final IllegalArgumentException e) {
            Assert.assertFalse(
                    "populated elements must not be treated as empty: " + e.getMessage(),
                    String.valueOf(e.getMessage()).contains("carries no content"));
        }
    }
}
