package eu.siacs.conversations.xmpp.jingle;

import android.util.Log;

import com.google.common.collect.ImmutableSet;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.MucOptions;
import eu.siacs.conversations.entities.Presence;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xmpp.Jid;
import im.conversations.android.xmpp.model.stanza.Iq;

/**
 * XEP-0272 Muji - coordinates group calls on top of the existing 1:1 {@link JingleRtpConnection}
 * machinery. A conference is a MUC: we announce our {@code <muji>} presence, watch other
 * occupants' {@code <muji>} presence, run a deterministic glare tie-break, and establish one
 * per-pair Jingle RTP session (full mesh) with each ready participant.
 *
 * <p>Wire-compatible with the monocles desktop client. Audio-only for now; the per-pair sessions
 * carry {@code <muji room>} and skip JMI (see {@link JingleConnectionManager#initializeMujiSession}
 * / {@link JingleConnectionManager#createMujiResponder}). The standard ongoing-call notification /
 * {@code RtpSessionActivity} surfaces the call (a multi-party participant grid is a follow-up).
 */
public class MujiConferenceManager {

    private final JingleConnectionManager jingleManager;
    private final XmppConnectionService service;

    /** Active conferences, keyed by {@link #key(Account, String)}. */
    private final Map<String, Conference> conferences = new ConcurrentHashMap<>();
    /** Latest observed Muji presence per occupant, keyed by conference key -> occupant JID. Tracked
     * independently of {@link #conferences} so a call joined after peers announced still sees who
     * is already ready. */
    private final Map<String, Map<String, PeerState>> mujiSeen = new ConcurrentHashMap<>();
    /** Conference keys for which we've shown a "join group call" invite (we're not in the call). */
    private final Set<String> invited = ConcurrentHashMap.newKeySet();

    /** A peer's observed Muji presence: readiness + their real full JID for direct addressing. */
    private static final class PeerState {
        private final Muji.State state;
        private final String realJid; // user@host/resource, or the occupant JID as a fallback

        private PeerState(final Muji.State state, final String realJid) {
            this.state = state;
            this.realJid = realJid;
        }
    }

    MujiConferenceManager(final JingleConnectionManager jingleManager) {
        this.jingleManager = jingleManager;
        this.service = jingleManager.getXmppConnectionService();
    }

    private static String key(final Account account, final String room) {
        return account.getJid().asBareJid().toString() + " " + room;
    }

    private Set<Media> mediaFor(final boolean video) {
        return video ? ImmutableSet.of(Media.AUDIO, Media.VIDEO) : ImmutableSet.of(Media.AUDIO);
    }

    // --- entry points -----------------------------------------------------------------------

    /** Start / join a Muji group call in the given (already-joined) MUC conversation. */
    public synchronized void placeGroupCall(final Conversation muc, final boolean video) {
        final Account account = muc.getAccount();
        final String room = muc.getJid().asBareJid().toString();
        final MucOptions mucOptions = muc.getMucOptions();
        final Jid ourOccupant = mucOptions.getSelf().getFullJid();
        if (ourOccupant == null) {
            Log.w(Config.LOGTAG, "muji: cannot place group call - not joined to " + room);
            return;
        }
        final String k = key(account, room);
        final Conference conference =
                new Conference(account, room, ourOccupant.toString(), video, mediaFor(video));
        conferences.put(k, conference);
        // We're joining now -> clear any pending "join" invite notification for this room.
        if (invited.remove(k)) {
            service.getNotificationService().cancelGroupCallInvite(muc);
        }
        Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": muji placeGroupCall room=" + room);
        // XEP-0272: announce <preparing/> then the ready content. Our codec set is fixed, so we
        // advertise readiness immediately.
        announce(conference, Muji.payload(false, video));
        announce(conference, Muji.payload(true, video));
        // Mesh with anyone already ready.
        final Map<String, PeerState> seen = mujiSeen.get(k);
        if (seen != null) {
            for (final Map.Entry<String, PeerState> e : seen.entrySet()) {
                if (e.getValue().state == Muji.State.READY) {
                    maybeInitiate(conference, e.getKey(), e.getValue().realJid);
                }
            }
        }
    }

    /**
     * Leave the whole conference because the local user hung up one of its legs (the Android
     * call UI is per-leg). Drops our {@code <muji>} presence and ends every <em>other</em> leg;
     * the leg that triggered this (`self`) finishes on its own. Without this, hanging up one leg
     * would leave the conference's other legs — and the shared mic/factory — alive.
     */
    public synchronized void leaveConferenceExcept(
            final Account account, final String room, final JingleRtpConnection self) {
        final String k = key(account, room);
        final Conference conference = conferences.remove(k);
        if (conference == null) {
            return;
        }
        announce(conference, null); // drop our <muji> presence (XEP-0272 ordering)
        for (final JingleRtpConnection connection : conference.members.values()) {
            if (connection != self) {
                endSession(connection);
            }
        }
        conference.members.clear();
        mujiSeen.remove(k);
    }

    /** Leave a Muji group call: drop our {@code <muji>} presence, then end every per-pair session. */
    public synchronized void leaveGroupCall(final Account account, final String room) {
        final String k = key(account, room);
        final Conference conference = conferences.remove(k);
        if (conference == null) {
            return;
        }
        // Drop <muji> from our presence first (XEP-0272 ordering - reduces join/leave races).
        announce(conference, null);
        for (final JingleRtpConnection connection : conference.members.values()) {
            endSession(connection);
        }
        conference.members.clear();
        mujiSeen.remove(k);
    }

    // --- presence coordination (called from PresenceParser) ----------------------------------

    /**
     * Observe an occupant's MUC presence for a {@code <muji>} payload. Tracks who is ready and,
     * when we're in the conference, meshes (via the tie-break) or drops the participant.
     */
    public synchronized void onPresence(
            final Account account,
            final Jid occupantFull,
            final String realJid,
            final Muji.State state,
            final boolean isSelf) {
        if (occupantFull == null || occupantFull.getResource() == null || isSelf) {
            return; // occupant JIDs only; never coordinate against our own presence
        }
        final String room = occupantFull.asBareJid().toString();
        final String occupant = occupantFull.toString();
        final String k = key(account, room);
        // Address the peer's real full JID directly (occupant-JID routing via the MUC is
        // unreliable peer-to-peer); fall back to the occupant JID if the room hides it.
        final Map<String, PeerState> seen =
                mujiSeen.computeIfAbsent(k, x -> new ConcurrentHashMap<>());
        final String addr;
        if (state == null) {
            // On departure the unavailable presence has no <item jid>; recover the last-known
            // real JID we stored, so we can drop the right member (keyed by real JID).
            final PeerState prev = seen.remove(occupant);
            addr = prev != null ? prev.realJid : occupant;
        } else {
            addr = (realJid == null || realJid.isEmpty()) ? occupant : realJid;
            seen.put(occupant, new PeerState(state, addr));
        }
        final Conference conference = conferences.get(k);
        if (conference == null) {
            // We're not in this room's call. Surface / clear a one-tap "join" invite.
            final boolean anyParticipant = !seen.isEmpty();
            if (anyParticipant && invited.add(k)) {
                final Conversation conversation = service.find(account, occupantFull.asBareJid());
                if (conversation != null) {
                    service.getNotificationService()
                            .pushGroupCallInvite(conversation, occupantFull.getResource());
                }
            } else if (!anyParticipant && invited.remove(k)) {
                final Conversation conversation = service.find(account, occupantFull.asBareJid());
                if (conversation != null) {
                    service.getNotificationService().cancelGroupCallInvite(conversation);
                }
            }
            return;
        }
        if (state == Muji.State.READY) {
            maybeInitiate(conference, occupant, addr);
        } else if (state == null) {
            removeMember(conference, addr);
        }
    }

    // --- incoming session-initiate (called from JingleConnectionManager.deliverPacket) -------

    /**
     * Handle an incoming Muji session-initiate (tagged {@code <muji room>}): if we're in that
     * conference, spin up the responder side and auto-accept (the user already joined the call);
     * otherwise terminate it (no incoming group-call ring in this version).
     */
    public synchronized void handleIncomingSessionInitiate(
            final Account account,
            final Iq packet,
            final AbstractJingleConnection.Id id,
            final Jid from,
            final String room) {
        final Conference conference = conferences.get(key(account, room));
        if (conference == null) {
            Log.d(
                    Config.LOGTAG,
                    account.getJid().asBareJid()
                            + ": terminating muji session-initiate for a conference we're not in ("
                            + room
                            + ")");
            jingleManager.sendSessionTerminateMuji(account, packet, id);
            return;
        }
        final JingleRtpConnection rtpConnection =
                jingleManager.createMujiResponder(id, from, room);
        conference.members.put(from.toString(), rtpConnection);
        // receiveSessionInitiate auto-accepts (no ring) because the connection's mujiRoom is set.
        rtpConnection.deliverPacket(packet);
    }

    /**
     * A per-pair session ended (peer left, hang-up, or error). Drop the member; once the last
     * member is gone, retract our {@code <muji>} presence and forget the conference (we've left).
     * Called from {@link JingleRtpConnection#finish()}.
     */
    public synchronized void onSessionEnded(
            final Account account, final String room, final String peerOccupant) {
        final String k = key(account, room);
        final Conference conference = conferences.get(k);
        if (conference == null) {
            return;
        }
        conference.members.remove(peerOccupant);
        if (conference.members.isEmpty()) {
            conferences.remove(k);
            announce(conference, null); // drop <muji> - we're no longer in the call
            mujiSeen.remove(k);
        }
    }

    /**
     * All per-pair connections of the conference in `room` (for the participant-grid UI + to keep
     * the call alive when a leg ends). Reads the live connection registry rather than our
     * `members` bookkeeping (which a terminating leg mutates), so it's race-free.
     */
    public java.util.List<JingleRtpConnection> getConnections(
            final Account account, final String room) {
        return jingleManager.getMujiConnections(account, room);
    }

    // --- internals --------------------------------------------------------------------------

    /**
     * Tie-break, then either initiate a per-pair session with a ready peer or wait for theirs.
     * Members are keyed by the peer's real full JID (`peerAddr`) — the same value `id.with` takes
     * on both ends — so adding/removing a leg stays consistent. The tie-break uses the occupant.
     */
    private void maybeInitiate(
            final Conference conference, final String peerOccupant, final String peerAddr) {
        if (conference.members.containsKey(peerAddr)) {
            return; // already have a session with this peer
        }
        if (!Muji.shouldInitiate(conference.ourOccupant, peerOccupant)) {
            return; // the peer initiates; we wait for their session-initiate
        }
        try {
            final JingleRtpConnection rtpConnection =
                    jingleManager.initializeMujiSession(
                            conference.account,
                            Jid.of(peerAddr),
                            conference.room,
                            conference.media);
            conference.members.put(peerAddr, rtpConnection);
            Log.d(
                    Config.LOGTAG,
                    conference.account.getJid().asBareJid()
                            + ": muji initiated session with "
                            + peerOccupant
                            + " (addr=" + peerAddr + ")");
        } catch (final Exception e) {
            Log.w(Config.LOGTAG, "muji: failed to initiate session with " + peerOccupant, e);
        }
    }

    /** Drop a participant who left the conference: end their per-pair session if any. `memberKey`
     * is the peer's real full JID (the members map key). */
    private void removeMember(final Conference conference, final String memberKey) {
        final JingleRtpConnection connection = conference.members.remove(memberKey);
        if (connection != null) {
            endSession(connection);
        }
    }

    private void endSession(final JingleRtpConnection connection) {
        try {
            connection.endCall();
        } catch (final Exception e) {
            Log.w(Config.LOGTAG, "muji: failed to end session", e);
        }
    }

    /** Build + send a directed MUC presence to our occupant JID carrying an optional {@code <muji>}. */
    private void announce(final Conference conference, final Element muji) {
        final Account account = conference.account;
        final Jid ourOccupant = Jid.of(conference.ourOccupant);
        final String nick = ourOccupant.getResource();
        final var presence =
                service.getPresenceGenerator()
                        .selfPresence(account, Presence.Status.ONLINE, true, nick);
        presence.setTo(ourOccupant);
        if (muji != null) {
            presence.addChild(muji);
        }
        service.sendPresencePacket(account, presence);
    }

    private static final class Conference {
        private final Account account;
        private final String room; // bare room JID
        private final String ourOccupant; // room/nick
        private final boolean video;
        private final Set<Media> media;
        /** Remote participant occupant JID -> its per-pair Jingle session. */
        private final Map<String, JingleRtpConnection> members = new ConcurrentHashMap<>();

        private Conference(
                final Account account,
                final String room,
                final String ourOccupant,
                final boolean video,
                final Set<Media> media) {
            this.account = account;
            this.room = room;
            this.ourOccupant = ourOccupant;
            this.video = video;
            this.media = media;
        }
    }
}
