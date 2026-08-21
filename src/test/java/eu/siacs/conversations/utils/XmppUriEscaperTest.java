package eu.siacs.conversations.utils;

import eu.siacs.conversations.xmpp.Jid;
import org.junit.Assert;
import org.junit.Test;

/**
 * Covers the percent encoding applied to the JID in an {@code xmpp:} URI.
 *
 * <p>The wire formats this feeds - the Atom {@code <author><uri/>}, the {@code rel='replies'} href
 * and the story reference carried on a message - are shared with the desktop client and with
 * payloads already published by older builds. So the two properties that matter are that a JID
 * survives a round trip, and that an unescaped legacy value still reads back correctly.
 */
public class XmppUriEscaperTest {

    /** A biboumi IRC gateway address: the realistic JID that carries both '#' and '%'. */
    private static final String IRC = "#fdroid%irc.oftc.net@irc.example.org";

    // ---- escaping ----

    @Test
    public void ordinaryAddressIsUntouched() {
        Assert.assertEquals("alice@example.com", XmppUriEscaper.escape("alice@example.com"));
    }

    @Test
    public void resourceSeparatorIsKept() {
        Assert.assertEquals(
                "alice@example.com/phone", XmppUriEscaper.escape("alice@example.com/phone"));
    }

    @Test
    public void ircGatewayAddressIsEscaped() {
        Assert.assertEquals(
                "%23fdroid%25irc.oftc.net@irc.example.org", XmppUriEscaper.escape(IRC));
    }

    /** '?' is legal in a localpart and would otherwise start the query string. */
    @Test
    public void questionMarkIsEscaped() {
        Assert.assertEquals("who%3F@example.com", XmppUriEscaper.escape("who?@example.com"));
    }

    /** A resourcepart may contain a space. */
    @Test
    public void spaceIsEscaped() {
        Assert.assertEquals(
                "alice@example.com/my%20phone", XmppUriEscaper.escape("alice@example.com/my phone"));
    }

    @Test
    public void nonAsciiIsEncodedAsUtf8() {
        // U+00E4 is two bytes in UTF-8
        Assert.assertEquals("h%C3%A4ns@example.com", XmppUriEscaper.escape("häns@example.com"));
    }

    // ---- round trip ----

    @Test
    public void escapingRoundTrips() {
        for (final String address :
                new String[] {
                    "alice@example.com",
                    IRC,
                    "who?@example.com",
                    "häns@example.com",
                    "alice@example.com/my phone",
                    "a%b#c?d@example.com/x y"
                }) {
            Assert.assertEquals(
                    address, XmppUriEscaper.unescape(XmppUriEscaper.escape(address)));
        }
    }

    // ---- unescaping ----

    @Test
    public void valueWithoutEscapesIsReturnedAsIs() {
        Assert.assertEquals("alice@example.com", XmppUriEscaper.unescape("alice@example.com"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void invalidEscapeIsRejected() {
        // '%ir' is what a legacy unescaped IRC address looks like
        XmppUriEscaper.unescape(IRC);
    }

    @Test(expected = IllegalArgumentException.class)
    public void truncatedEscapeIsRejected() {
        XmppUriEscaper.unescape("alice@example.com%2");
    }

    @Test
    public void lowerCaseHexIsAccepted() {
        Assert.assertEquals("#a@example.com", XmppUriEscaper.unescape("%23a@example.com"));
        Assert.assertEquals("#a@example.com", XmppUriEscaper.unescape("%23a@example.com"));
    }

    // ---- lenient parsing: the compatibility contract ----

    @Test
    public void parsesTheEscapedFormWeNowWrite() {
        Assert.assertEquals(Jid.of(IRC), XmppUriEscaper.parseJidOrNull(XmppUriEscaper.escape(IRC)));
    }

    /** Payloads already published by older builds, and by other clients, are unescaped. */
    @Test
    public void parsesTheLegacyUnescapedForm() {
        Assert.assertEquals(Jid.of(IRC), XmppUriEscaper.parseJidOrNull(IRC));
    }

    @Test
    public void parsesAnOrdinaryAddressEitherWay() {
        final Jid expected = Jid.of("alice@example.com");
        Assert.assertEquals(expected, XmppUriEscaper.parseJidOrNull("alice@example.com"));
        Assert.assertEquals(
                expected, XmppUriEscaper.parseJidOrNull(XmppUriEscaper.escape("alice@example.com")));
    }

    @Test
    public void unparsableValueYieldsNull() {
        Assert.assertNull(XmppUriEscaper.parseJidOrNull("@@@"));
        Assert.assertNull(XmppUriEscaper.parseJidOrNull(""));
        Assert.assertNull(XmppUriEscaper.parseJidOrNull(null));
    }

    /**
     * Pins the known ambiguity rather than pretending it is absent: a legacy address whose literal
     * '%' happens to be followed by two hex digits is read as an escape. The escaped reading wins
     * because that is the form XEP-0147 prescribes.
     */
    @Test
    public void literalPercentFollowedByHexIsReadAsAnEscape() {
        Assert.assertEquals(
                Jid.of("#ab@example.com"), XmppUriEscaper.parseJidOrNull("%23ab@example.com"));
    }
}
