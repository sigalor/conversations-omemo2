package eu.siacs.conversations.crypto.axolotl.legacy;

import android.util.Log;

import org.whispersystems.libsignal.IdentityKey;
import org.whispersystems.libsignal.IdentityKeyPair;
import org.whispersystems.libsignal.InvalidKeyException;
import org.whispersystems.libsignal.InvalidKeyIdException;
import org.whispersystems.libsignal.InvalidMessageException;
import org.whispersystems.libsignal.InvalidVersionException;
import org.whispersystems.libsignal.LegacyMessageException;
import org.whispersystems.libsignal.NoSessionException;
import org.whispersystems.libsignal.SessionBuilder;
import org.whispersystems.libsignal.SessionCipher;
import org.whispersystems.libsignal.SignalProtocolAddress;
import org.whispersystems.libsignal.UntrustedIdentityException;
import org.whispersystems.libsignal.ecc.ECPublicKey;
import org.whispersystems.libsignal.ecc.Curve;
import org.whispersystems.libsignal.protocol.PreKeySignalMessage;
import org.whispersystems.libsignal.protocol.SignalMessage;
import org.whispersystems.libsignal.state.PreKeyBundle;
import org.whispersystems.libsignal.state.PreKeyRecord;
import org.whispersystems.libsignal.state.SignedPreKeyRecord;
import org.whispersystems.libsignal.util.KeyHelper;

import java.util.ArrayList;
import java.util.List;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.crypto.axolotl.AxolotlService;
import eu.siacs.conversations.crypto.axolotl.SQLiteAxolotlStore;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.services.XmppConnectionService;

/**
 * High-level wrapper around the old-libsignal stack for optional XEP-0384 v0.3
 * (pre-PQ OMEMO) interop. All entry points are single-device: callers handle
 * fan-out across recipient devices the same way they do for the primary OMEMO2
 * stack.
 *
 * <p>The backend is created lazily per account when the user enables legacy
 * OMEMO. It is otherwise dormant and consumes nothing.
 */
public class LegacyAxolotlBackend {

    /**
     * Result of decrypting one wrapped key on the legacy stack. The bytes are
     * the inner per-message key that the outer OMEMO2-style payload AEAD uses;
     * decrypted exactly the same way as in the primary stack.
     */
    public static class Decrypted {
        public final byte[] key;
        public final boolean wasPreKeyMessage;

        Decrypted(final byte[] key, final boolean wasPreKeyMessage) {
            this.key = key;
            this.wasPreKeyMessage = wasPreKeyMessage;
        }
    }

    /** What the outer encrypt() returns per recipient device. */
    public static class Wrapped {
        public final byte[] serialized;
        public final boolean isPreKeyMessage;

        Wrapped(final byte[] serialized, final boolean isPreKeyMessage) {
            this.serialized = serialized;
            this.isPreKeyMessage = isPreKeyMessage;
        }
    }

    private final Account account;
    private final XmppConnectionService service;
    private final LegacySignalProtocolStore store;

    public LegacyAxolotlBackend(final Account account, final XmppConnectionService service,
                                final SQLiteAxolotlStore primary) {
        this.account = account;
        this.service = service;
        this.store = new LegacySignalProtocolStore(account, service, primary);
    }

    public LegacySignalProtocolStore getStore() {
        return store;
    }

    // ------------------------------------------------------------------
    // Key generation / bundle publication helpers
    // ------------------------------------------------------------------

    /**
     * Generate a fresh batch of one-time legacy prekeys, persist them, and
     * return them so the caller can publish them to the legacy PEP bundle node.
     */
    public List<PreKeyRecord> generatePreKeyBatch(final int startId, final int count) {
        final List<PreKeyRecord> out = new ArrayList<>(count);
        final List<PreKeyRecord> generated = KeyHelper.generatePreKeys(startId, count);
        for (final PreKeyRecord r : generated) {
            store.storePreKey(r.getId(), r);
            out.add(r);
        }
        return out;
    }

    /**
     * Generate a fresh legacy signed prekey, signed by the identity key.
     * Caller persists it and republishes the bundle.
     */
    public SignedPreKeyRecord generateSignedPreKey(final int id) {
        try {
            final IdentityKeyPair ikp = store.getIdentityKeyPair();
            final SignedPreKeyRecord r = KeyHelper.generateSignedPreKey(ikp, id);
            store.storeSignedPreKey(id, r);
            return r;
        } catch (final InvalidKeyException e) {
            throw new AssertionError("local identity key invalid for signing", e);
        }
    }

    // ------------------------------------------------------------------
    // Session build / encrypt / decrypt
    // ------------------------------------------------------------------

    /**
     * Build a legacy session from a peer's published XEP-0384 v0.3 bundle.
     * Caller has already parsed the bundle XML and assembled the components.
     *
     * @throws InvalidKeyException        bad bundle keys / signature
     * @throws UntrustedIdentityException identity key mismatch (rare; we usually
     *                                    delegate trust to the application layer)
     */
    public void buildSession(final SignalProtocolAddress address,
                             final int registrationId,
                             final int preKeyId,
                             final ECPublicKey preKeyPublic,
                             final int signedPreKeyId,
                             final ECPublicKey signedPreKeyPublic,
                             final byte[] signedPreKeySignature,
                             final IdentityKey identityKey)
            throws InvalidKeyException, UntrustedIdentityException {
        // Verify <spks> signature before we hand the bundle to libsignal — the
        // old library also checks it, but we surface the failure here with a
        // clearer log line.
        if (!Curve.verifySignature(
                identityKey.getPublicKey(),
                signedPreKeyPublic.serialize(),
                signedPreKeySignature)) {
            throw new InvalidKeyException("legacy spk signature invalid for " + address);
        }
        final PreKeyBundle bundle = new PreKeyBundle(
                registrationId, address.getDeviceId(),
                preKeyId, preKeyPublic,
                signedPreKeyId, signedPreKeyPublic, signedPreKeySignature,
                identityKey);
        new SessionBuilder(store, address).process(bundle);
    }

    public boolean hasSession(final SignalProtocolAddress address) {
        return store.containsSession(address);
    }

    /**
     * Wrap a per-message key for a single recipient device. The caller manages
     * fan-out (one Wrapped per recipient device, all sharing the same outer
     * AES-GCM payload).
     */
    public Wrapped encryptKey(final SignalProtocolAddress address, final byte[] perMessageKey) {
        final SessionCipher cipher = new SessionCipher(store, address);
        try {
            final var ciphertext = cipher.encrypt(perMessageKey);
            final boolean isPreKey = ciphertext.getType()
                    == org.whispersystems.libsignal.protocol.CiphertextMessage.PREKEY_TYPE;
            return new Wrapped(ciphertext.serialize(), isPreKey);
        } catch (final UntrustedIdentityException e) {
            Log.w(Config.LOGTAG, AxolotlService.getLogprefix(account)
                    + "legacy encrypt: untrusted identity for " + address);
            return null;
        }
    }

    /**
     * Unwrap a per-message key from a single wrapped entry. If the wrapped
     * entry is a PreKeySignalMessage, the corresponding one-time prekey is
     * deleted as a side effect (libsignal does this internally).
     */
    public Decrypted decryptKey(final SignalProtocolAddress senderAddress,
                                final byte[] wrappedBytes, final boolean isPreKey)
            throws InvalidMessageException, InvalidVersionException, InvalidKeyException,
                    InvalidKeyIdException, NoSessionException, LegacyMessageException,
                    UntrustedIdentityException, org.whispersystems.libsignal.DuplicateMessageException {
        final SessionCipher cipher = new SessionCipher(store, senderAddress);
        final byte[] plaintext;
        if (isPreKey) {
            final PreKeySignalMessage msg = new PreKeySignalMessage(wrappedBytes);
            plaintext = cipher.decrypt(msg);
        } else {
            final SignalMessage msg = new SignalMessage(wrappedBytes);
            plaintext = cipher.decrypt(msg);
        }
        return new Decrypted(plaintext, isPreKey);
    }
}
