
package eu.siacs.conversations.entities;

import eu.siacs.conversations.xmpp.Jid;

public class Call {

    private final String contact;
    private final Jid jid;
    private final long startTime;
    private final int status;
    private final boolean isVideoCall;
    private final boolean successful;

    public Call(String contact, Jid jid, long startTime, int status, boolean isVideoCall, boolean successful) {
        this.contact = contact;
        this.jid = jid;
        this.startTime = startTime;
        this.status = status;
        this.isVideoCall = isVideoCall;
        this.successful = successful;
    }

    public String getContact() {
        return contact;
    }

    public Jid getJid() {
        return jid;
    }

    public long getStartTime() {
        return startTime;
    }

    public int getStatus() {
        return status;
    }

    public boolean isVideoCall() {
        return isVideoCall;
    }

    public boolean isSuccessful() {
        return successful;
    }
}
