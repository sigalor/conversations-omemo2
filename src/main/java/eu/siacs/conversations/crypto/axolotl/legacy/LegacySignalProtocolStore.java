package eu.siacs.conversations.crypto.axolotl.legacy;

import android.util.Log;

import java.util.ArrayList;
import java.util.List;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.crypto.axolotl.AxolotlService;
import eu.siacs.conversations.crypto.axolotl.SQLiteAxolotlStore;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.services.XmppConnectionService;

import org.whispersystems.libsignal.IdentityKey;
import org.whispersystems.libsignal.IdentityKeyPair;
import org.whispersystems.libsignal.InvalidKeyException;
import org.whispersystems.libsignal.InvalidKeyIdException;
import org.whispersystems.libsignal.SignalProtocolAddress;
import org.whispersystems.libsignal.state.IdentityKeyStore;
import org.whispersystems.libsignal.state.PreKeyRecord;
import org.whispersystems.libsignal.state.SessionRecord;
import org.whispersystems.libsignal.state.SignalProtocolStore;
import org.whispersystems.libsignal.state.SignedPreKeyRecord;

/**
 * {@link SignalProtocolStore} implementation backed by the {@code legacy_*}
 * SQLite tables, for optional XEP-0384 v0.3 (pre-PQ OMEMO) interop.
 *
 * <p>The legacy stack is intentionally kept entirely separate from the primary
 * (post-quantum) {@link SQLiteAxolotlStore}: different SQLite tables, different
 * libsignal package roots ({@code org.whispersystems.libsignal.*} vs
 * {@code org.signal.libsignal.protocol.*}). The two never share session state.
 *
 * <p>Identity keys ARE shared: the bare 32-byte Curve25519 public key is the
 * same on both stacks, and the trust state is anchored at that fingerprint. So
 * this store re-uses the primary store's {@link IdentityKeyPair} (re-serialised
 * via raw bytes through the old library's types) and delegates identity
 * lookup/save/trust to the existing identities table.
 *
 * <p>Identity-key methods that return {@link Boolean} follow the old-libsignal
 * contract (true = identity was newly stored or unchanged, false = mismatch).
 */
public class LegacySignalProtocolStore implements SignalProtocolStore {

    private final Account account;
    private final XmppConnectionService service;
    private final SQLiteAxolotlStore primary;

    public LegacySignalProtocolStore(final Account account,
                                     final XmppConnectionService service,
                                     final SQLiteAxolotlStore primary) {
        this.account = account;
        this.service = service;
        this.primary = primary;
        service.databaseBackend.ensureLegacyOmemoTablesExist();
    }

    // ---- IdentityKeyStore ----

    @Override
    public IdentityKeyPair getIdentityKeyPair() {
        try {
            // Shared bytes: the Curve25519 keypair is wire-compatible between
            // the new and the old library. Re-serialise through the old type.
            final byte[] bytes = primary.getIdentityKeyPair().serialize();
            return new IdentityKeyPair(bytes);
        } catch (final InvalidKeyException e) {
            throw new AssertionError("primary identity key incompatible with legacy lib", e);
        }
    }

    @Override
    public int getLocalRegistrationId() {
        return primary.getLocalRegistrationId();
    }

    @Override
    public boolean saveIdentity(final SignalProtocolAddress address, final IdentityKey identityKey) {
        // Bridge to the primary store via the IK's raw bytes.
        try {
            final org.signal.libsignal.protocol.IdentityKey primaryIk =
                    new org.signal.libsignal.protocol.IdentityKey(identityKey.serialize());
            final org.signal.libsignal.protocol.SignalProtocolAddress primaryAddr =
                    new org.signal.libsignal.protocol.SignalProtocolAddress(
                            address.getName(), address.getDeviceId());
            primary.saveIdentity(primaryAddr, primaryIk);
            return true;
        } catch (final org.signal.libsignal.protocol.InvalidKeyException e) {
            Log.w(Config.LOGTAG, AxolotlService.getLogprefix(account)
                    + "legacy saveIdentity: invalid key bytes — " + e.getMessage());
            return false;
        }
    }

    @Override
    public boolean isTrustedIdentity(final SignalProtocolAddress address,
                                     final IdentityKey identityKey,
                                     final IdentityKeyStore.Direction direction) {
        // Trust is enforced at the application layer in this project (see
        // FingerprintStatus / BTBV). Mirror the primary store's behaviour.
        return true;
    }

    // ---- PreKeyStore ----

    @Override
    public PreKeyRecord loadPreKey(final int preKeyId) throws InvalidKeyIdException {
        final byte[] bytes = service.databaseBackend.loadLegacyPreKeyBytes(account, preKeyId);
        if (bytes == null) throw new InvalidKeyIdException("no legacy prekey " + preKeyId);
        try {
            return new PreKeyRecord(bytes);
        } catch (final java.io.IOException e) {
            throw new InvalidKeyIdException("legacy prekey " + preKeyId + " corrupt: " + e);
        }
    }

    @Override
    public void storePreKey(final int preKeyId, final PreKeyRecord record) {
        service.databaseBackend.storeLegacyPreKeyBytes(account, preKeyId, record.serialize());
    }

    @Override
    public boolean containsPreKey(final int preKeyId) {
        return service.databaseBackend.containsLegacyPreKey(account, preKeyId);
    }

    @Override
    public void removePreKey(final int preKeyId) {
        service.databaseBackend.deleteLegacyPreKey(account, preKeyId);
    }

    // ---- SignedPreKeyStore ----

    @Override
    public SignedPreKeyRecord loadSignedPreKey(final int signedPreKeyId) throws InvalidKeyIdException {
        final byte[] bytes = service.databaseBackend.loadLegacySignedPreKeyBytes(account, signedPreKeyId);
        if (bytes == null) throw new InvalidKeyIdException("no legacy signed prekey " + signedPreKeyId);
        try {
            return new SignedPreKeyRecord(bytes);
        } catch (final java.io.IOException e) {
            throw new InvalidKeyIdException("legacy signed prekey " + signedPreKeyId + " corrupt: " + e);
        }
    }

    @Override
    public List<SignedPreKeyRecord> loadSignedPreKeys() {
        final List<SignedPreKeyRecord> out = new ArrayList<>();
        for (final byte[] bytes : service.databaseBackend.loadAllLegacySignedPreKeyBytes(account)) {
            try {
                out.add(new SignedPreKeyRecord(bytes));
            } catch (final java.io.IOException e) {
                Log.w(Config.LOGTAG, AxolotlService.getLogprefix(account)
                        + "skipping corrupt legacy signed prekey: " + e);
            }
        }
        return out;
    }

    @Override
    public void storeSignedPreKey(final int signedPreKeyId, final SignedPreKeyRecord record) {
        service.databaseBackend.storeLegacySignedPreKeyBytes(account, signedPreKeyId, record.serialize());
    }

    @Override
    public boolean containsSignedPreKey(final int signedPreKeyId) {
        return service.databaseBackend.containsLegacySignedPreKey(account, signedPreKeyId);
    }

    @Override
    public void removeSignedPreKey(final int signedPreKeyId) {
        service.databaseBackend.deleteLegacySignedPreKey(account, signedPreKeyId);
    }

    // ---- SessionStore ----

    @Override
    public SessionRecord loadSession(final SignalProtocolAddress address) {
        final byte[] bytes = service.databaseBackend.loadLegacySessionBytes(
                account, address.getName(), address.getDeviceId());
        if (bytes == null) return new SessionRecord();
        try {
            return new SessionRecord(bytes);
        } catch (final java.io.IOException e) {
            Log.w(Config.LOGTAG, AxolotlService.getLogprefix(account)
                    + "legacy session for " + address + " corrupt, returning fresh: " + e);
            return new SessionRecord();
        }
    }

    @Override
    public List<Integer> getSubDeviceSessions(final String name) {
        return service.databaseBackend.getLegacySubDeviceSessions(account, name);
    }

    @Override
    public void storeSession(final SignalProtocolAddress address, final SessionRecord record) {
        service.databaseBackend.storeLegacySessionBytes(
                account, address.getName(), address.getDeviceId(), record.serialize());
    }

    @Override
    public boolean containsSession(final SignalProtocolAddress address) {
        return service.databaseBackend.containsLegacySession(
                account, address.getName(), address.getDeviceId());
    }

    @Override
    public void deleteSession(final SignalProtocolAddress address) {
        service.databaseBackend.deleteLegacySession(
                account, address.getName(), address.getDeviceId());
    }

    @Override
    public void deleteAllSessions(final String name) {
        service.databaseBackend.deleteAllLegacySessions(account, name);
    }
}
