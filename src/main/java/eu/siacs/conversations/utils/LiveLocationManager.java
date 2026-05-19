package eu.siacs.conversations.utils;

import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

public class LiveLocationManager {

    private static final LiveLocationManager INSTANCE = new LiveLocationManager();

    public static LiveLocationManager getInstance() {
        return INSTANCE;
    }

    private LiveLocationManager() {}

    public interface PositionListener {
        void onPositionUpdate(String sessionId, double latitude, double longitude);
        void onSessionExpired(String sessionId);
    }

    public static class IncomingSession {
        public final String sessionId;
        public final String messageUuid;
        public volatile double latitude;
        public volatile double longitude;
        public final long expiresAt;

        IncomingSession(String sessionId, String messageUuid, double lat, double lon, long expiresAt) {
            this.sessionId = sessionId;
            this.messageUuid = messageUuid;
            this.latitude = lat;
            this.longitude = lon;
            this.expiresAt = expiresAt;
        }

        public boolean isExpired() {
            return System.currentTimeMillis() > expiresAt;
        }
    }

    public static class OutgoingSession {
        public final String sessionId;
        public final String conversationUuid;
        public final String messageUuid;
        public final long expiresAt;

        OutgoingSession(String sessionId, String conversationUuid, String messageUuid, long expiresAt) {
            this.sessionId = sessionId;
            this.conversationUuid = conversationUuid;
            this.messageUuid = messageUuid;
            this.expiresAt = expiresAt;
        }

        public boolean isExpired() {
            return System.currentTimeMillis() > expiresAt;
        }
    }

    private final ConcurrentHashMap<String, IncomingSession> incoming = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> messageToSession = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, OutgoingSession> outgoingByConversation = new ConcurrentHashMap<>();
    private final CopyOnWriteArraySet<PositionListener> listeners = new CopyOnWriteArraySet<>();
    private final ConcurrentHashMap<String, Drawable> sessionAvatars = new ConcurrentHashMap<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public void registerIncomingSession(String sessionId, String messageUuid, double lat, double lon, long expiresAt) {
        IncomingSession s = new IncomingSession(sessionId, messageUuid, lat, lon, expiresAt);
        incoming.put(sessionId, s);
        messageToSession.put(messageUuid, sessionId);
        scheduleExpiry(sessionId, expiresAt);
    }

    public void updateIncomingPosition(String sessionId, double lat, double lon) {
        IncomingSession s = incoming.get(sessionId);
        if (s != null && !s.isExpired()) {
            s.latitude = lat;
            s.longitude = lon;
        }
        for (PositionListener l : listeners) {
            l.onPositionUpdate(sessionId, lat, lon);
        }
    }

    public void notifyOutgoingPositionUpdate(String sessionId, double lat, double lon) {
        for (PositionListener l : listeners) {
            l.onPositionUpdate(sessionId, lat, lon);
        }
    }

    public void setSessionAvatar(String sessionId, Drawable drawable) {
        if (drawable != null) sessionAvatars.put(sessionId, drawable);
    }

    public Drawable getSessionAvatar(String sessionId) {
        return sessionId != null ? sessionAvatars.get(sessionId) : null;
    }

    public boolean isActiveLiveLocationMessage(String messageUuid) {
        String sessionId = messageToSession.get(messageUuid);
        if (sessionId != null) {
            IncomingSession s = incoming.get(sessionId);
            if (s != null && !s.isExpired()) return true;
        }
        for (OutgoingSession os : outgoingByConversation.values()) {
            if (messageUuid.equals(os.messageUuid) && !os.isExpired()) return true;
        }
        return false;
    }

    public IncomingSession getSessionForMessage(String messageUuid) {
        String sessionId = messageToSession.get(messageUuid);
        if (sessionId == null) return null;
        IncomingSession s = incoming.get(sessionId);
        return s != null && !s.isExpired() ? s : null;
    }

    public IncomingSession getSession(String sessionId) {
        return incoming.get(sessionId);
    }

    public String getSessionIdForMessage(String messageUuid) {
        String sessionId = messageToSession.get(messageUuid);
        if (sessionId != null) return sessionId;
        for (OutgoingSession os : outgoingByConversation.values()) {
            if (messageUuid.equals(os.messageUuid) && !os.isExpired()) return os.sessionId;
        }
        return null;
    }

    public void addListener(PositionListener l) {
        listeners.add(l);
    }

    public void removeListener(PositionListener l) {
        listeners.remove(l);
    }

    public void registerOutgoingSession(String conversationUuid, String sessionId, String messageUuid, long expiresAt) {
        OutgoingSession os = new OutgoingSession(sessionId, conversationUuid, messageUuid, expiresAt);
        outgoingByConversation.put(conversationUuid, os);
    }

    public OutgoingSession getOutgoingSession(String conversationUuid) {
        OutgoingSession os = outgoingByConversation.get(conversationUuid);
        return os != null && !os.isExpired() ? os : null;
    }

    public void clearOutgoingSession(String conversationUuid) {
        outgoingByConversation.remove(conversationUuid);
    }

    private void scheduleExpiry(String sessionId, long expiresAt) {
        long delay = expiresAt - System.currentTimeMillis();
        if (delay <= 0) {
            expireSession(sessionId);
            return;
        }
        mainHandler.postDelayed(() -> expireSession(sessionId), delay);
    }

    private void expireSession(String sessionId) {
        IncomingSession s = incoming.remove(sessionId);
        if (s != null) {
            messageToSession.remove(s.messageUuid);
            for (PositionListener l : listeners) {
                l.onSessionExpired(sessionId);
            }
        }
    }
}
