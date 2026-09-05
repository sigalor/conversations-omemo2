package eu.siacs.conversations.xmpp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xml.Namespace;

import org.junit.Test;

/**
 * The invariant this fork exists for: nothing carrying user content leaves the device unless it is
 * inside the PQ-OMEMO2 envelope.
 */
public class ContentGuardTest {

    private static Element message() {
        return new Element("message", Namespace.JABBER_CLIENT);
    }

    private static Element body(final String text) {
        final Element body = new Element("body", Namespace.JABBER_CLIENT);
        body.setContent(text);
        return body;
    }

    private static Element omemo2Envelope() {
        return new Element(ContentGuard.OMEMO2_ENVELOPE, Namespace.OMEMO2);
    }

    // --- content that must never go out in the clear -------------------------------------------

    @Test
    public void plaintextBodyIsContent() {
        final Element packet = message();
        packet.addChild(body("hello"));
        assertEquals("body", ContentGuard.findCleartextContent(packet));
    }

    @Test
    public void namespacelessBodyIsContent() {
        // MessageGenerator builds some bodies as `new Element("body")`, with no xmlns at all.
        final Element packet = message();
        final Element namespaceless = new Element("body");
        namespaceless.setContent("hello");
        packet.addChild(namespaceless);
        assertEquals("body", ContentGuard.findCleartextContent(packet));
    }

    @Test
    public void plaintextReactionsAreContent() {
        final Element packet = message();
        packet.addChild("reactions", Namespace.REACTIONS).setAttribute("id", "1");
        assertEquals("reactions", ContentGuard.findCleartextContent(packet));
    }

    @Test
    public void plaintextReplyIsContent() {
        final Element packet = message();
        packet.addChild("reply", "urn:xmpp:reply:0").setAttribute("id", "1");
        assertEquals("reply", ContentGuard.findCleartextContent(packet));
    }

    @Test
    public void plaintextMessageRetractionIsContent() {
        final Element packet = message();
        packet.addChild("retract", "urn:xmpp:message-retract:1").setAttribute("id", "1");
        assertEquals("retract", ContentGuard.findCleartextContent(packet));
    }

    @Test
    public void plaintextCorrectionIsContent() {
        final Element packet = message();
        packet.addChild("replace", Namespace.LAST_MESSAGE_CORRECTION).setAttribute("id", "1");
        assertEquals("replace", ContentGuard.findCleartextContent(packet));
    }

    @Test
    public void outOfBandFileUrlIsContent() {
        final Element packet = message();
        packet.addChild("x", Namespace.OOB).addChild("url").setContent("https://example.invalid/f");
        assertEquals("x", ContentGuard.findCleartextContent(packet));
    }

    @Test
    public void statelessFileSharingIsContent() {
        final Element packet = message();
        packet.addChild("file-sharing", Namespace.SFS);
        assertEquals("file-sharing", ContentGuard.findCleartextContent(packet));
    }

    @Test
    public void liveLocationElementsAreContent() {
        for (final String name :
                new String[] {"live-location", "live-location-update", "live-location-stop"}) {
            final Element packet = message();
            packet.addChild(name, Namespace.LIVE_LOCATION);
            assertEquals(name, ContentGuard.findCleartextContent(packet));
        }
    }

    // --- the OMEMO2 envelope makes its cleartext siblings legitimate ----------------------------

    @Test
    public void omemo2EnvelopeAllowsFallbackBody() {
        // What generateOmemo2Chat actually produces: the encrypted payload plus a cleartext
        // fallback body telling non-OMEMO2 clients why they cannot read it.
        final Element packet = message();
        packet.addChild(omemo2Envelope());
        packet.addChild(body("this message is encrypted"));
        packet.addChild("store", "urn:xmpp:hints");
        assertNull(ContentGuard.findCleartextContent(packet));
    }

    @Test
    public void omemo2EnvelopeAllowsEncryptedReactionStanza() {
        final Element packet = message();
        packet.addChild(omemo2Envelope());
        assertNull(ContentGuard.findCleartextContent(packet));
    }

    @Test
    public void legacyOmemo1EnvelopeDoesNotAllowCleartextBody() {
        // A legacy OMEMO1 <encrypted/> is a different namespace and must not launder content.
        final Element packet = message();
        packet.addChild("encrypted", "eu.siacs.conversations.axolotl");
        packet.addChild(body("hello"));
        assertEquals("body", ContentGuard.findCleartextContent(packet));
    }

    // --- protocol/metadata stanzas must stay sendable -------------------------------------------

    @Test
    public void chatStateIsNotContent() {
        final Element packet = message();
        packet.addChild("composing", "http://jabber.org/protocol/chatstates");
        assertNull(ContentGuard.findCleartextContent(packet));
    }

    @Test
    public void deliveryReceiptAndReadMarkerAreNotContent() {
        final Element received = message();
        received.addChild("received", "urn:xmpp:receipts").setAttribute("id", "1");
        assertNull(ContentGuard.findCleartextContent(received));

        final Element displayed = message();
        displayed.addChild("displayed", "urn:xmpp:chat-markers:0").setAttribute("id", "1");
        assertNull(ContentGuard.findCleartextContent(displayed));
    }

    @Test
    public void jingleMessageRetractIsNotContent() {
        // JMI call signalling has a <retract/> of its own. Matching retraction by element name
        // alone would silently break outgoing calls.
        final Element packet = message();
        packet.addChild("retract", Namespace.JINGLE_MESSAGE).setAttribute("id", "session");
        assertNull(ContentGuard.findCleartextContent(packet));
    }

    @Test
    public void jingleMessageProposeIsNotContent() {
        final Element packet = message();
        packet.addChild("propose", Namespace.JINGLE_MESSAGE).setAttribute("id", "session");
        assertNull(ContentGuard.findCleartextContent(packet));
    }

    @Test
    public void mucInviteIsNotContent() {
        final Element packet = message();
        packet.addChild("x", "http://jabber.org/protocol/muc#user").addChild("invite");
        assertNull(ContentGuard.findCleartextContent(packet));

        final Element direct = message();
        direct.addChild("x", "jabber:x:conference").setAttribute("jid", "room@example.invalid");
        assertNull(ContentGuard.findCleartextContent(direct));
    }

    @Test
    public void mucSubjectIsNotContent() {
        // Room configuration: the server stores and replays it, so it cannot be E2E encrypted.
        final Element packet = message();
        final Element subject = new Element("subject", Namespace.JABBER_CLIENT);
        subject.setContent("topic");
        packet.addChild(subject);
        assertNull(ContentGuard.findCleartextContent(packet));
    }

    @Test
    public void dataFormIsNotContent() {
        final Element packet = message();
        packet.addChild("x", "jabber:x:data");
        assertNull(ContentGuard.findCleartextContent(packet));
    }

    @Test
    public void ephemeralNegotiationIsNotContent() {
        final Element packet = message();
        packet.addChild("ephemeral", Namespace.EPHEMERAL).setAttribute("timer", "60");
        assertNull(ContentGuard.findCleartextContent(packet));
    }

    @Test
    public void bodyNestedInFallbackIsNotMatched() {
        // <fallback><body/></fallback> is a range marker, not a body. Only direct children count.
        final Element packet = message();
        packet.addChild(omemo2Envelope());
        packet.addChild("fallback", "urn:xmpp:fallback:0").addChild("body", "urn:xmpp:fallback:0");
        assertNull(ContentGuard.findCleartextContent(packet));

        final Element withoutEnvelope = message();
        withoutEnvelope
                .addChild("fallback", "urn:xmpp:fallback:0")
                .addChild("body", "urn:xmpp:fallback:0");
        assertNull(ContentGuard.findCleartextContent(withoutEnvelope));
    }

    @Test
    public void emptyStanzaIsSafe() {
        assertNull(ContentGuard.findCleartextContent(message()));
        assertNull(ContentGuard.findCleartextContent(null));
    }
}
