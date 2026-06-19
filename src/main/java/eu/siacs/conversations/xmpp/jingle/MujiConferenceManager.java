package eu.siacs.conversations.xmpp.jingle;

import android.util.Log;

import com.google.common.collect.ImmutableSet;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Message;
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

    /** A leg connecting longer than this without reaching CONNECTED is retired + retried. */
    private static final long STUCK_LEG_MILLIS = 12_000L;

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
        private final Integer deviceId;

        private PeerState(final Muji.State state, final String realJid, final Integer deviceId) {
            this.state = state;
            this.realJid = realJid;
            this.deviceId = deviceId;
        }
    }

    MujiConferenceManager(final JingleConnectionManager jingleManager) {
        this.jingleManager = jingleManager;
        this.service = jingleManager.getXmppConnectionService();
    }

    private static String key(final Account account, final String room) {
        return account.getJid().asBareJid().toString() + " " + room;
    }

    public boolean isParticipating(final Account account, final String room) {
        return conferences.containsKey(key(account, room));
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
        // The group call is PQ OMEMO2-verified only when the user placed it with the conversation's
        // encryption lock set to OMEMO2; an unencrypted lock yields an unverified call.
        final boolean verified =
                muc.getNextEncryption() == Message.ENCRYPTION_AXOLOTL_OMEMO2;
        final Conference conference =
                new Conference(
                        account, room, ourOccupant.toString(), video, verified, mediaFor(video));
        conferences.put(k, conference);
        // We're joining now -> clear any pending "join" invite notification for this room.
        if (invited.remove(k)) {
            service.getNotificationService().cancelGroupCallInvite(muc);
        }
        Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": muji placeGroupCall room=" + room);
        // XEP-0272: announce <preparing/> then the ready content. Our codec set is fixed, so we
        // advertise readiness immediately. Include our OMEMO device ID if encryption is active.
        final Integer deviceId = account.getAxolotlService().getOwnDeviceId();
        announce(conference, Muji.payload(false, video, deviceId));
        announce(conference, Muji.payload(true, video, deviceId));
        // Mesh with anyone already ready.
        final Map<String, PeerState> seen = mujiSeen.get(k);
        if (seen != null) {
            for (final Map.Entry<String, PeerState> e : seen.entrySet()) {
                if (e.getValue().state == Muji.State.READY) {
                    maybeInitiate(conference, e.getKey(), e.getValue().realJid, e.getValue().deviceId);
                }
            }
        }
        // Periodically heal the mesh: drop legs that never connected (stuck / one-sided) or died
        // and re-initiate with ready peers we have no live leg to.
        conference.remeshFuture =
                JingleConnectionManager.SCHEDULED_EXECUTOR_SERVICE.scheduleWithFixedDelay(
                        () -> {
                            try {
                                remesh(account, room);
                            } catch (final Exception e) {
                                // Never let a throw cancel future ticks (scheduleWithFixedDelay).
                                Log.w(Config.LOGTAG, "muji remesh tick failed", e);
                            }
                        },
                        8,
                        8,
                        TimeUnit.SECONDS);
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
        cancelRemesh(conference);
        announce(conference, null); // drop our <muji> presence (XEP-0272 ordering)
        for (final JingleRtpConnection connection : conference.members.values()) {
            if (connection != self) {
                endSession(connection);
            }
        }
        conference.members.clear();
        // Keep mujiSeen: see leaveGroupCall.
    }

    /** Leave a Muji group call: drop our {@code <muji>} presence, then end every per-pair session. */
    public synchronized void leaveGroupCall(final Account account, final String room) {
        final String k = key(account, room);
        final Conference conference = conferences.remove(k);
        if (conference == null) {
            return;
        }
        cancelRemesh(conference);
        // Drop <muji> from our presence first (XEP-0272 ordering - reduces join/leave races).
        announce(conference, null);
        for (final JingleRtpConnection connection : conference.members.values()) {
            endSession(connection);
        }
        conference.members.clear();
        // Do NOT clear mujiSeen here. We're leaving the *call*, not the MUC — the other
        // participants are still in the call and still advertising <muji>, but won't re-send that
        // presence just because we left. mujiSeen is kept current by MUC presence (a peer is
        // removed when it actually drops <muji>), so preserving it is what lets placeGroupCall
        // re-mesh with the still-running call when the user rejoins via the call button. Clearing
        // it made rejoin initiate to nobody (only the legs peers started came up).
    }

    public synchronized void setMicrophoneEnabled(
            final Account account, final String room, final boolean enabled) {
        final Conference conference = conferences.get(key(account, room));
        if (conference != null) {
            conference.microphoneEnabled = enabled;
        }
    }

    public synchronized void setVideoEnabled(
            final Account account, final String room, final boolean enabled) {
        final Conference conference = conferences.get(key(account, room));
        if (conference != null) {
            conference.videoEnabled = enabled;
        }
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
            final Muji.Advertisement advertisement,
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
        final Integer deviceId;
        if (advertisement == null) {
            // On departure the unavailable presence has no <item jid>; recover the last-known
            // real JID we stored, so we can drop the right member (keyed by real JID).
            final PeerState prev = seen.remove(occupant);
            addr = prev != null ? prev.realJid : occupant;
            deviceId = prev != null ? prev.deviceId : null;
        } else {
            addr = (realJid == null || realJid.isEmpty()) ? occupant : realJid;
            deviceId = advertisement.deviceId;
            seen.put(occupant, new PeerState(advertisement.state, addr, deviceId));
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
        if (advertisement != null && advertisement.state == Muji.State.READY) {
            maybeInitiate(conference, occupant, addr, deviceId);
        } else if (advertisement == null) {
            // members are keyed by real JID; `addr` was recovered from the departing peer's state
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
        // SECURITY: only accept a Muji leg from someone who is actually a participant of this room
        // — an occupant who announced <muji> presence whose real JID the (non-anonymous) MUC
        // vouched for. Without this, any JID that learns we're in the call could send a
        // <muji room=…> session-initiate and inject unsolicited media (it auto-accepts, no ring).
        // Per XEP-0272 the initiate arrives from the peer's real full JID, so match `from` against
        // the real JID we recorded from its <muji> presence (the seen map is keyed by occupant).
        final Map<String, PeerState> seen = mujiSeen.get(key(account, room));
        PeerState peerState = null;
        if (seen != null) {
            for (final PeerState candidate : seen.values()) {
                if (from.toString().equals(candidate.realJid)) {
                    peerState = candidate;
                    break;
                }
            }
        }
        if (peerState == null || peerState.state != Muji.State.READY) {
            Log.w(
                    Config.LOGTAG,
                    account.getJid().asBareJid()
                            + ": rejecting muji session-initiate from non-participant "
                            + from);
            jingleManager.sendSessionTerminateMuji(account, packet, id);
            return;
        }
        final JingleRtpConnection rtpConnection =
                jingleManager.createMujiResponder(
                        id, from, room, conference.verified, peerState.realJid, peerState.deviceId);
        rtpConnection.setProposedMedia(conference.media);
        rtpConnection.setMicrophoneEnabled(conference.microphoneEnabled);
        rtpConnection.setVideoEnabled(conference.videoEnabled);
        conference.members.put(from.toString(), rtpConnection);
        conference.legStartedAt.put(from.toString(), System.currentTimeMillis());
        // receiveSessionInitiate auto-accepts (no ring) because the connection's mujiRoom is set.
        rtpConnection.deliverPacket(packet);
    }

    /**
     * A per-pair session ended (peer left, hang-up, or error). Drop the member; once the last
     * member is gone, retract our {@code <muji>} presence and forget the conference (we've left).
     * Called from {@link JingleRtpConnection#finish()}.
     */
    public synchronized void onSessionEnded(
            final Account account, final String room, final String peerAddr) {
        final String k = key(account, room);
        final Conference conference = conferences.get(k);
        if (conference == null) {
            return;
        }
        // `peerAddr` is the leg's id.with — the peer's real full JID — i.e. the members-map key.
        conference.members.remove(peerAddr);
        conference.legStartedAt.remove(peerAddr);
        // Only LEAVE if nobody is left in the room at all. If any peer is still present (its <muji>
        // hasn't gone), keep the conference: the re-mesh re-establishes a leg whose ICE failed or
        // never came up. (The user explicitly leaving goes through leaveGroupCall, not here.)
        final Map<String, PeerState> seen = mujiSeen.get(k);
        final boolean anyPeerPresent = seen != null && !seen.isEmpty();
        if (conference.members.isEmpty() && !anyPeerPresent) {
            cancelRemesh(conference);
            conferences.remove(k);
            announce(conference, null); // drop <muji> - we're no longer in the call
            mujiSeen.remove(k);
        }
    }

    /**
     * Periodic mesh reconciliation (every few seconds while in the call). Drops legs that died or
     * never reached {@code CONNECTED} (stuck / one-sided), then (re)initiates with every ready peer
     * we have no live leg to. This heals an incomplete mesh and re-establishes a leg after a
     * mid-call ICE failure — so one participant leaving (or a flaky leg) no longer drops the call
     * for the others.
     */
    public synchronized void remesh(final Account account, final String room) {
        final String k = key(account, room);
        final Conference conference = conferences.get(k);
        if (conference == null) {
            return;
        }
        final long now = System.currentTimeMillis();
        // 1. Retire legs that ended or have been connecting too long (never answered / no ICE).
        final List<String> drop = new ArrayList<>();
        for (final Map.Entry<String, JingleRtpConnection> e : conference.members.entrySet()) {
            final RtpEndUserState st = e.getValue().getEndUserState();
            final boolean up =
                    st == RtpEndUserState.CONNECTED || st == RtpEndUserState.RECONNECTING;
            final Long since = conference.legStartedAt.get(e.getKey());
            final boolean stuck = !up && since != null && (now - since) > STUCK_LEG_MILLIS;
            if (st == RtpEndUserState.ENDED || st == RtpEndUserState.CONNECTIVITY_ERROR || stuck) {
                drop.add(e.getKey());
            }
        }
        for (final String memberKey : drop) {
            final JingleRtpConnection connection = conference.members.remove(memberKey);
            conference.legStartedAt.remove(memberKey);
            if (connection != null) {
                endSession(connection);
            }
            Log.d(Config.LOGTAG, "muji remesh: dropped stuck/ended leg " + memberKey + ", will retry");
        }
        // 2. (Re)initiate with every ready peer we now have no member for (maybeInitiate dedups +
        //    applies the JID tie-break, so it only initiates where we should).
        final Map<String, PeerState> seen = mujiSeen.get(k);
        if (seen != null) {
            for (final Map.Entry<String, PeerState> e : seen.entrySet()) {
                if (e.getValue().state == Muji.State.READY) {
                    maybeInitiate(conference, e.getKey(), e.getValue().realJid, e.getValue().deviceId);
                }
            }
        }
    }

    private void cancelRemesh(final Conference conference) {
        if (conference.remeshFuture != null) {
            conference.remeshFuture.cancel(false);
            conference.remeshFuture = null;
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
     * Per XEP-0272 the Jingle session is addressed to the peer's <em>real full JID</em>
     * (`peerAddr`) — IQ routing to a MUC occupant JID is not guaranteed — so that is also the
     * members-map key (the value `id.with` takes on both ends). The occupant JID (`peerOccupant`)
     * is used only for the initiate/respond tie-break.
     */
    private void maybeInitiate(
            final Conference conference, final String peerOccupant, final String peerAddr, final Integer deviceId) {
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
                            conference.verified,
                            conference.media,
                            peerAddr,
                            deviceId);
            rtpConnection.setMicrophoneEnabled(conference.microphoneEnabled);
            rtpConnection.setVideoEnabled(conference.videoEnabled);
            conference.members.put(peerAddr, rtpConnection);
            conference.legStartedAt.put(peerAddr, System.currentTimeMillis());
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
     * is the peer's real full JID string (the members map key). */
    private void removeMember(final Conference conference, final String memberKey) {
        final JingleRtpConnection connection = conference.members.remove(memberKey);
        conference.legStartedAt.remove(memberKey);
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
        /** Whether this group call must be PQ OMEMO2-verified (placed with OMEMO2 lock active). */
        private final boolean verified;
        private final Set<Media> media;
        /** Remote participant (occupant-JID key) -> its per-pair Jingle session. */
        private final Map<String, JingleRtpConnection> members = new ConcurrentHashMap<>();
        /** Member key -> when its current leg (re)started connecting (millis), for the re-mesh. */
        private final Map<String, Long> legStartedAt = new ConcurrentHashMap<>();
        /** The periodic re-mesh task, cancelled when the conference ends. */
        private ScheduledFuture<?> remeshFuture;

        private boolean microphoneEnabled = true;
        private boolean videoEnabled = true;

        private Conference(
                final Account account,
                final String room,
                final String ourOccupant,
                final boolean video,
                final boolean verified,
                final Set<Media> media) {
            this.account = account;
            this.room = room;
            this.ourOccupant = ourOccupant;
            this.video = video;
            this.verified = verified;
            this.media = media;
        }
    }
}
