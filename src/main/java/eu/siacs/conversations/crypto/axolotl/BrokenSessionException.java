package eu.siacs.conversations.crypto.axolotl;

import org.signal.libsignal.protocol.SignalProtocolAddress;

public class BrokenSessionException extends CryptoFailedException {

    private final SignalProtocolAddress signalProtocolAddress;

    public BrokenSessionException(SignalProtocolAddress address, Exception e) {
        super(e);
        this.signalProtocolAddress = address;

    }

    public SignalProtocolAddress getSignalProtocolAddress() {
        return signalProtocolAddress;
    }
}
