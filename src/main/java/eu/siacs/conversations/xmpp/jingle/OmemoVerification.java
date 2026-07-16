package eu.siacs.conversations.xmpp.jingle;

import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;

import java.util.concurrent.atomic.AtomicBoolean;

import eu.siacs.conversations.crypto.axolotl.AxolotlService;

public class OmemoVerification {

    private final AtomicBoolean deviceIdWritten = new AtomicBoolean(false);
    private final AtomicBoolean sessionFingerprintWritten = new AtomicBoolean(false);
    private Integer deviceId;
    private String sessionFingerprint;
    private volatile boolean legacy = false;

    public void setDeviceId(final Integer id) {
        if (deviceIdWritten.compareAndSet(false, true)) {
            this.deviceId = id;
            return;
        }
        throw new IllegalStateException("Device Id has already been set");
    }

    public int getDeviceId() {
        Preconditions.checkNotNull(this.deviceId, "Device ID is null");
        return this.deviceId;
    }

    public boolean hasDeviceId() {
        return this.deviceId != null;
    }

    public void setSessionFingerprint(final String fingerprint) {
        Preconditions.checkNotNull(fingerprint, "Session fingerprint must not be null");
        if (sessionFingerprintWritten.compareAndSet(false, true)) {
            this.sessionFingerprint = fingerprint;
            return;
        }
        throw new IllegalStateException("Session fingerprint has already been set");
    }

    public String getFingerprint() {
        return this.sessionFingerprint;
    }

    // Which OMEMO stack authenticated the DTLS fingerprint. Sticky: once any leg of the
    // call was verified via the legacy stack, the whole call is displayed as legacy.
    public void setLegacy(final boolean legacy) {
        if (legacy) {
            this.legacy = true;
        }
    }

    public boolean isLegacy() {
        return this.legacy;
    }

    public void setOrEnsureEqual(AxolotlService.OmemoVerifiedPayload<?> omemoVerifiedPayload) {
        setOrEnsureEqual(omemoVerifiedPayload.getDeviceId(), omemoVerifiedPayload.getFingerprint());
        setLegacy(omemoVerifiedPayload.isLegacy());
    }

    public void setOrEnsureEqual(final int deviceId, final String sessionFingerprint) {
        Preconditions.checkNotNull(sessionFingerprint, "Session fingerprint must not be null");
        // Whether this is the first verification is decided by the fingerprint, not the device id:
        // a Muji responder leg pre-sets the device id at construction (so it knows which device to
        // encrypt its session-accept to) while the fingerprint is only learned when the verified
        // payload is first decrypted. Gating on the device id would mis-route that first call into
        // the "ensure equal" branch and throw "No session fingerprint has been previously provided".
        if (this.sessionFingerprintWritten.get()) {
            if (!sessionFingerprint.equals(this.sessionFingerprint)) {
                throw new SecurityException("Session Fingerprints did not match");
            }
            if (this.deviceId == null) {
                throw new IllegalStateException("No Device Id has been previously provided");
            }
            if (this.deviceId != deviceId) {
                throw new IllegalStateException("Device Ids did not match");
            }
        } else {
            this.setSessionFingerprint(sessionFingerprint);
            if (this.deviceIdWritten.get()) {
                // Device id was pre-set (Muji): confirm the verified payload came from exactly the
                // device the MUC advertised, then keep it.
                if (this.deviceId == null || this.deviceId != deviceId) {
                    throw new IllegalStateException("Device Ids did not match");
                }
            } else {
                this.setDeviceId(deviceId);
            }
        }
    }

    public boolean hasFingerprint() {
        return this.sessionFingerprint != null;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("deviceId", deviceId)
                .add("fingerprint", sessionFingerprint)
                .add("legacy", legacy)
                .toString();
    }
}
