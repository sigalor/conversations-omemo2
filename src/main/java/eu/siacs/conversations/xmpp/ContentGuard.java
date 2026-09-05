package eu.siacs.conversations.xmpp;

import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xml.Namespace;

/**
 * Decides whether an outgoing message stanza carries user content in the clear.
 *
 * <p>This fork exists to make PQ-OMEMO2 the only encryption mode: content is encrypted or it is
 * not sent. Enforcing that per feature has failed repeatedly -- the same fail-open shape (an
 * OR-clause, or an un-gated direct {@code sendMessagePacket()} call) was found five separate times
 * over this branch, most recently in reactions and in live-location sharing. Rather than keep
 * re-auditing every feature, {@link XmppConnection#sendMessagePacket} consults this class on the
 * way out, so a stanza that carries content without the OMEMO2 envelope is dropped no matter which
 * code path built it -- including code not written yet.
 *
 * <p>Pure functions over {@link Element}, deliberately with no XMPP-connection or account state, so
 * the policy is unit-testable on its own (see {@code ContentGuardTest}).
 */
public final class ContentGuard {

    private ContentGuard() {}

    /** The PQ-OMEMO2 envelope. Its presence is what makes cleartext siblings legitimate. */
    public static final String OMEMO2_ENVELOPE = "encrypted";

    /**
     * @return the name of the first direct child of {@code packet} that carries user content in
     *     the clear, or null if the stanza is safe to send.
     */
    public static String findCleartextContent(final Element packet) {
        if (packet == null) {
            return null;
        }
        if (packet.hasChild(OMEMO2_ENVELOPE, Namespace.OMEMO2)) {
            // A real OMEMO2 stanza. Its cleartext parts -- the <body> fallback, EME, hints -- are
            // the envelope; the content itself is inside <encrypted/>.
            return null;
        }
        for (final Element child : packet.getChildren()) {
            if (child != null && isCleartextContent(child)) {
                return child.getName();
            }
        }
        return null;
    }

    /**
     * Whether a direct child of a message stanza carries user content.
     *
     * <p>Only direct children are considered, so the {@code <body/>} markers nested inside
     * {@code <fallback/>} elements are not matched.
     *
     * <p>Deliberately NOT content, and therefore still sendable in the clear (the final
     * whole-branch review scoped these out as protocol/metadata): chat states, delivery receipts
     * and read markers, ephemeral-timer negotiation, Jingle call signalling, MUC invites and MUC
     * admin. That last one includes {@code <subject/>}: a room subject is room configuration,
     * which the server itself stores and replays to every joiner, so it cannot be end-to-end
     * encrypted at all.
     */
    private static boolean isCleartextContent(final Element child) {
        final String name = child.getName();
        final String namespace = child.getNamespace();
        if (name == null) {
            return false;
        }
        switch (name) {
            case "body":
                // The message body itself. Matched namespace-less too: not every code path sets
                // an explicit xmlns on it.
                return namespace == null || Namespace.JABBER_CLIENT.equals(namespace);
            case "reactions":
                return Namespace.REACTIONS.equals(namespace);
            case "reply":
                return NAMESPACE_REPLY.equals(namespace);
            case "retract":
                // XEP-0424 message retraction ONLY. Jingle Message Initiation has a <retract/> of
                // its own (urn:xmpp:jingle-message:0) which is call signalling, not content --
                // hence matching on the namespace and never on the element name alone.
                return namespace != null && namespace.startsWith(NAMESPACE_MESSAGE_RETRACT_PREFIX);
            case "replace":
                return Namespace.LAST_MESSAGE_CORRECTION.equals(namespace);
            case "apply-to":
                return NAMESPACE_FASTEN.equals(namespace);
            case "x":
                // Out-of-band data: a file URL. Other <x/> payloads (MUC invites, muc#user,
                // jabber:x:data forms) are not content and must stay sendable.
                return Namespace.OOB.equals(namespace);
            case "file-sharing":
            case "attach-to":
                return Namespace.SFS.equals(namespace);
            case "live-location":
            case "live-location-update":
            case "live-location-stop":
                return Namespace.LIVE_LOCATION.equals(namespace);
            default:
                return false;
        }
    }

    private static final String NAMESPACE_REPLY = "urn:xmpp:reply:0";
    private static final String NAMESPACE_FASTEN = "urn:xmpp:fasten:0";
    private static final String NAMESPACE_MESSAGE_RETRACT_PREFIX = "urn:xmpp:message-retract:";
}
