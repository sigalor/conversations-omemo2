package eu.siacs.conversations.xmpp.jingle;

import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xml.Namespace;

/**
 * XEP-0272 Muji - multiparty Jingle (group calls).
 *
 * <p>A Muji conference <em>is</em> a MUC. Participants coordinate through their MUC presence: each
 * announces a {@code <muji>} payload (first {@code <preparing/>}, then the media {@code <content>}
 * codec advertisement) and then establishes a <strong>full mesh</strong> of ordinary 1:1 Jingle
 * RTP sessions - one per other participant - each tagged with {@code <muji room=.../>}. This class
 * holds only the Muji <em>stanza</em> helpers + the glare tie-break; the per-pair sessions reuse
 * the existing {@link JingleRtpConnection} machinery.
 *
 * <p>Wire-compatible with the monocles desktop client (same namespace, payload shape, the
 * {@code <muji room>} placement on the Jingle element, and the tie-break rule).
 */
public final class Muji {

    private Muji() {}

    /** A peer occupant's advertised Muji state, parsed from their MUC presence. */
    public enum State {
        /** {@code <muji><preparing/></muji>} - allocating streams, not yet callable. */
        PREPARING,
        /** {@code <muji>} with {@code <content>} and no {@code <preparing/>} - ready to be called. */
        READY
    }

    /** A participant's Muji advertisement: their readiness state + OMEMO device ID (optional). */
    public static class Advertisement {
        public final State state;
        public final Integer deviceId;

        public Advertisement(final State state, final Integer deviceId) {
            this.state = state;
            this.deviceId = deviceId;
        }
    }

    /**
     * Build the {@code <muji>} presence payload. While {@code prepared} is false it advertises only
     * {@code <preparing/>}; once true it advertises the media {@code <content>} codec set (Opus
     * audio, plus VP8 video when {@code video}) and drops {@code <preparing/>}. The payload-types
     * are advisory (XEP-0272 codec coordination) - our codec set is fixed, and the real per-pair
     * sessions still run a full SDP offer/answer.
     */
    public static Element payload(
            final boolean prepared, final boolean video, final Integer deviceId) {
        final Element muji = new Element("muji", Namespace.JINGLE_MUJI);
        if (deviceId != null) {
            muji.setAttribute("device", deviceId);
        }
        if (!prepared) {
            muji.addChild("preparing", Namespace.JINGLE_MUJI);
            return muji;
        }
        final Element audio = muji.addChild("content", Namespace.JINGLE_MUJI);
        audio.setAttribute("creator", "initiator");
        audio.setAttribute("name", "voice");
        final Element audioDesc = audio.addChild("description", Namespace.JINGLE_APPS_RTP);
        audioDesc.setAttribute("media", "audio");
        final Element opus = audioDesc.addChild("payload-type", Namespace.JINGLE_APPS_RTP);
        opus.setAttribute("id", "111");
        opus.setAttribute("name", "opus");
        opus.setAttribute("clockrate", "48000");
        opus.setAttribute("channels", "2");
        if (video) {
            final Element webcam = muji.addChild("content", Namespace.JINGLE_MUJI);
            webcam.setAttribute("creator", "initiator");
            webcam.setAttribute("name", "webcam");
            final Element videoDesc = webcam.addChild("description", Namespace.JINGLE_APPS_RTP);
            videoDesc.setAttribute("media", "video");
            final Element vp8 = videoDesc.addChild("payload-type", Namespace.JINGLE_APPS_RTP);
            vp8.setAttribute("id", "96");
            vp8.setAttribute("name", "VP8");
            vp8.setAttribute("clockrate", "90000");
        }
        return muji;
    }

    /**
     * Inspect an occupant's presence for a {@code <muji>} element and classify it. Returns
     * {@code null} when there is no {@code <muji>} (the occupant is not participating, or has left
     * the conference).
     */
    public static Advertisement parse(final Element presence) {
        final Element muji = presence.findChild("muji", Namespace.JINGLE_MUJI);
        if (muji == null) {
            return null;
        }
        final Integer deviceId;
        final String deviceAttr = muji.getAttribute("device");
        if (deviceAttr != null) {
            Integer d;
            try {
                d = Integer.parseInt(deviceAttr);
            } catch (final NumberFormatException e) {
                d = null;
            }
            deviceId = d;
        } else {
            deviceId = null;
        }
        if (muji.findChild("preparing", Namespace.JINGLE_MUJI) != null) {
            return new Advertisement(State.PREPARING, deviceId);
        }
        for (final Element child : muji.getChildren()) {
            if ("content".equals(child.getName())) {
                return new Advertisement(State.READY, deviceId);
            }
        }
        // A bare <muji/> with neither preparing nor content - treat as preparing.
        return new Advertisement(State.PREPARING, deviceId);
    }

    /** The {@code room} attribute of a {@code <muji>} child of a {@code <jingle>}, or null. */
    public static String room(final Element jingle) {
        final Element muji = jingle.findChild("muji", Namespace.JINGLE_MUJI);
        return muji == null ? null : muji.getAttribute("room");
    }

    /**
     * Glare tie-break (XEP-0272): when two ready participants could each initiate a Jingle session
     * with the other, only one must. Deterministically pick the participant whose occupant JID
     * ({@code room/nick}) is lexicographically greater as the initiator. Both sides see the same
     * pair of occupant JIDs, so exactly one returns {@code true}.
     */
    public static boolean shouldInitiate(final String myOccupantJid, final String theirOccupantJid) {
        return myOccupantJid.compareTo(theirOccupantJid) > 0;
    }
}
