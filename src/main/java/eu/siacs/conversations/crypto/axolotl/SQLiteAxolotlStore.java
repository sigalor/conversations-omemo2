package eu.siacs.conversations.crypto.axolotl;

import android.util.Log;
import android.util.LruCache;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.utils.CryptoHelper;

import org.signal.libsignal.protocol.IdentityKey;
import org.signal.libsignal.protocol.IdentityKeyPair;
import org.signal.libsignal.protocol.InvalidKeyIdException;
import org.signal.libsignal.protocol.NoSessionException;
import org.signal.libsignal.protocol.ReusedBaseKeyException;
import org.signal.libsignal.protocol.ecc.ECPublicKey;
import org.signal.libsignal.protocol.SignalProtocolAddress;
import org.signal.libsignal.protocol.ecc.ECKeyPair;
import org.signal.libsignal.protocol.groups.state.SenderKeyRecord;
import org.signal.libsignal.protocol.kem.KEMKeyPair;
import org.signal.libsignal.protocol.kem.KEMKeyType;
import org.signal.libsignal.protocol.state.IdentityKeyStore;
import org.signal.libsignal.protocol.state.KyberPreKeyRecord;
import org.signal.libsignal.protocol.state.PreKeyRecord;
import org.signal.libsignal.protocol.state.SessionRecord;
import org.signal.libsignal.protocol.state.SignalProtocolStore;
import org.signal.libsignal.protocol.state.SignedPreKeyRecord;

import java.security.cert.X509Certificate;

public class SQLiteAxolotlStore implements SignalProtocolStore {

    // PQ OMEMO2 (org.signal.libsignal) gets its own tables so it never inherits
    // the pre-existing org.whispersystems OMEMO state from before the PQ upgrade.
    // That old state stays in the original sessions/prekeys/signed_prekeys tables
    // and is used by the optional legacy stack instead. Identities/trust are
    // shared (see IDENTITIES_TABLENAME) — same Curve25519 fingerprints for both.
    public static final String PREKEY_TABLENAME = "omemo2_prekeys";
    public static final String SIGNED_PREKEY_TABLENAME = "omemo2_signed_prekeys";
    public static final String SESSION_TABLENAME = "omemo2_sessions";
    public static final String IDENTITIES_TABLENAME = "identities";
    public static final String KYBER_PREKEY_TABLENAME = "kyber_prekeys";
    public static final String KYBER_LAST_RESORT_SESSIONS_TABLENAME = "kyber_last_resort_sessions";
    public static final String KYBER_IS_LAST_RESORT = "is_last_resort";
    public static final String KEM_PREKEY_ID = "kem_prekey_id";
    public static final String SPK_ID = "signed_prekey_id";
    public static final String BASE_KEY = "base_key";
    public static final String ACCOUNT = "account";
    public static final String DEVICE_ID = "device_id";
    public static final String ID = "id";
    public static final String KEY = "key";
    public static final String FINGERPRINT = "fingerprint";
    public static final String NAME = "name";
    public static final String TRUSTED = "trusted"; // no longer used
    public static final String TRUST = "trust";
    public static final String ACTIVE = "active";
    public static final String LAST_ACTIVATION = "last_activation";
    public static final String OWN = "ownkey";
    public static final String CERTIFICATE = "certificate";

    public static final String JSONKEY_REGISTRATION_ID = "axolotl_reg_id";
    public static final String JSONKEY_CURRENT_PREKEY_ID = "axolotl_cur_prekey_id";
    public static final String JSONKEY_CURRENT_KEM_PREKEY_ID = "axolotl_cur_kem_prekey_id";
    public static final String JSONKEY_CURRENT_LEGACY_PREKEY_ID = "axolotl_cur_legacy_prekey_id";
    /**
     * Set once the legacy XEP-0384 v0.3 bundle has been accepted by PEP. Legacy
     * OMEMO is available by default, but an account whose OMEMO2 bundle is
     * already current never runs a bundle publish, so without this marker such
     * a device would silently stay unreachable over legacy.
     */
    public static final String JSONKEY_LEGACY_BUNDLE_PUBLISHED = "axolotl_legacy_bundle_published";

    private static final int NUM_TRUSTS_TO_CACHE = 100;

    private final Account account;
    private final XmppConnectionService mXmppConnectionService;

    private IdentityKeyPair identityKeyPair;
    private int localRegistrationId;
    private int currentPreKeyId = 0;
    private int currentKemPreKeyId = 0;

    private final HashSet<Integer> preKeysMarkedForRemoval = new HashSet<>();

    private final LruCache<String, FingerprintStatus> trustCache =
            new LruCache<String, FingerprintStatus>(NUM_TRUSTS_TO_CACHE) {
                @Override
                protected FingerprintStatus create(String fingerprint) {
                    return mXmppConnectionService.databaseBackend.getFingerprintStatus(
                            account, fingerprint);
                }
            };

    private static IdentityKeyPair generateIdentityKeyPair() {
        Log.i(Config.LOGTAG, AxolotlService.LOGPREFIX + " : " + "Generating axolotl IdentityKeyPair...");
        return IdentityKeyPair.generate();
    }

    private static int generateRegistrationId() {
        Log.i(Config.LOGTAG, AxolotlService.LOGPREFIX + " : " + "Generating axolotl registration ID...");
        return new SecureRandom().nextInt(16380) + 1;
    }

    public SQLiteAxolotlStore(Account account, XmppConnectionService service) {
        this.account = account;
        this.mXmppConnectionService = service;
        this.localRegistrationId = loadRegistrationId();
        this.currentPreKeyId = loadCurrentPreKeyId();
        this.currentKemPreKeyId = loadCurrentKemPreKeyId();
    }

    public int getCurrentPreKeyId() {
        return currentPreKeyId;
    }

    public int getCurrentKemPreKeyId() {
        return currentKemPreKeyId;
    }

    // --------------------------------------
    // IdentityKeyStore
    // --------------------------------------

    private IdentityKeyPair loadIdentityKeyPair() {
        synchronized (mXmppConnectionService) {
            // PQ OMEMO2 has its OWN identity key, stored separately from the legacy
            // OMEMO key, so the two stacks never share a fingerprint and trust never
            // bleeds across them. On a fresh install — or the first run after the
            // shared-key → separate-key migration — this returns null and we generate
            // a brand new OMEMO2 identity (the legacy stack keeps the original key).
            IdentityKeyPair ownKey = mXmppConnectionService.databaseBackend.loadOwnOmemo2IdentityKeyPair(account);
            if (ownKey != null) {
                return ownKey;
            } else {
                Log.i(Config.LOGTAG, AxolotlService.getLogprefix(account) + "Could not retrieve own OMEMO2 IdentityKeyPair, generating a fresh one");
                ownKey = generateIdentityKeyPair();
                mXmppConnectionService.databaseBackend.storeOwnOmemo2IdentityKeyPair(account, ownKey);
            }
            return ownKey;
        }
    }

    private int loadRegistrationId() {
        return loadRegistrationId(false);
    }

    private int loadRegistrationId(boolean regenerate) {
        String regIdString = this.account.getKey(JSONKEY_REGISTRATION_ID);
        int reg_id;
        if (!regenerate && regIdString != null) {
            reg_id = Integer.valueOf(regIdString);
        } else {
            Log.i(Config.LOGTAG, AxolotlService.getLogprefix(account)
                    + "Could not retrieve axolotl registration id for account " + account.getJid());
            reg_id = generateRegistrationId();
            boolean success = this.account.setKey(JSONKEY_REGISTRATION_ID, Integer.toString(reg_id));
            if (success) {
                mXmppConnectionService.databaseBackend.updateAccount(account);
            } else {
                Log.e(Config.LOGTAG, AxolotlService.getLogprefix(account) + "Failed to write new key to the database!");
            }
        }
        return reg_id;
    }

    private int loadCurrentPreKeyId() {
        String prekeyIdString = this.account.getKey(JSONKEY_CURRENT_PREKEY_ID);
        if (prekeyIdString != null) {
            return Integer.valueOf(prekeyIdString);
        }
        Log.w(Config.LOGTAG, AxolotlService.getLogprefix(account)
                + "Could not retrieve current prekey id for account " + account.getJid());
        return 0;
    }

    private int loadCurrentKemPreKeyId() {
        String idString = this.account.getKey(JSONKEY_CURRENT_KEM_PREKEY_ID);
        if (idString != null) {
            return Integer.valueOf(idString);
        }
        return 0;
    }

    public void regenerate() {
        mXmppConnectionService.databaseBackend.wipeAxolotlDb(account);
        trustCache.evictAll();
        account.setKey(JSONKEY_CURRENT_PREKEY_ID, Integer.toString(0));
        account.setKey(JSONKEY_CURRENT_KEM_PREKEY_ID, Integer.toString(0));
        identityKeyPair = loadIdentityKeyPair();
        localRegistrationId = loadRegistrationId(true);
        currentPreKeyId = 0;
        currentKemPreKeyId = 0;
        mXmppConnectionService.updateAccountUi();
    }

    @Override
    public IdentityKeyPair getIdentityKeyPair() {
        if (identityKeyPair == null) {
            identityKeyPair = loadIdentityKeyPair();
        }
        return identityKeyPair;
    }

    @Override
    public int getLocalRegistrationId() {
        return localRegistrationId;
    }

    @Override
    public IdentityKeyStore.IdentityChange saveIdentity(final SignalProtocolAddress address, final IdentityKey identityKey) {
        if (!mXmppConnectionService.databaseBackend.loadIdentityKeys(account, address.getName()).contains(identityKey)) {
            String fingerprint = CryptoHelper.bytesToHex(identityKey.getPublicKey().serialize());
            FingerprintStatus status = getFingerprintStatus(fingerprint);
            if (status == null) {
                if (mXmppConnectionService.getAppSettings().isBTBVEnabled()
                        && !account.getAxolotlService().hasVerifiedKeys(address.getName())) {
                    Log.d(Config.LOGTAG, account.getJid().asBareJid()
                            + ": blindly trusted " + fingerprint + " of " + address.getName());
                    status = FingerprintStatus.createActiveTrusted();
                } else {
                    status = FingerprintStatus.createActiveUndecided();
                }
            } else {
                status = status.toActive();
            }
            mXmppConnectionService.databaseBackend.storeIdentityKey(account, address.getName(), identityKey, status);
            trustCache.remove(fingerprint);
            return IdentityKeyStore.IdentityChange.REPLACED_EXISTING;
        }
        return IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED;
    }

    @Override
    public boolean isTrustedIdentity(SignalProtocolAddress address, IdentityKey identityKey, IdentityKeyStore.Direction direction) {
        return true;
    }

    @Override
    public IdentityKey getIdentity(SignalProtocolAddress address) {
        Set<IdentityKey> keys = mXmppConnectionService.databaseBackend.loadIdentityKeys(account, address.getName());
        return keys.isEmpty() ? null : keys.iterator().next();
    }

    public FingerprintStatus getFingerprintStatus(String fingerprint) {
        return (fingerprint == null) ? null : trustCache.get(fingerprint);
    }

    public void setFingerprintStatus(String fingerprint, FingerprintStatus status) {
        mXmppConnectionService.databaseBackend.setIdentityKeyTrust(account, fingerprint, status);
        trustCache.remove(fingerprint);
    }

    public void setFingerprintCertificate(String fingerprint, X509Certificate x509Certificate) {
        mXmppConnectionService.databaseBackend.setIdentityKeyCertificate(account, fingerprint, x509Certificate);
    }

    public X509Certificate getFingerprintCertificate(String fingerprint) {
        return mXmppConnectionService.databaseBackend.getIdentityKeyCertifcate(account, fingerprint);
    }

    public Set<IdentityKey> getContactKeysWithTrust(String bareJid, FingerprintStatus status) {
        return mXmppConnectionService.databaseBackend.loadIdentityKeys(account, bareJid, status);
    }

    public long getContactNumTrustedKeys(String bareJid) {
        return mXmppConnectionService.databaseBackend.numTrustedKeys(account, bareJid);
    }

    // --------------------------------------
    // SessionStore
    // --------------------------------------

    @Override
    public SessionRecord loadSession(SignalProtocolAddress address) {
        SessionRecord session = mXmppConnectionService.databaseBackend.loadSession(this.account, address);
        return (session != null) ? session : new SessionRecord();
    }

    @Override
    public List<SessionRecord> loadExistingSessions(List<SignalProtocolAddress> addresses) throws NoSessionException {
        List<SessionRecord> records = new ArrayList<>();
        for (SignalProtocolAddress address : addresses) {
            SessionRecord record = mXmppConnectionService.databaseBackend.loadSession(this.account, address);
            if (record == null) {
                throw new NoSessionException("No session for " + address);
            }
            records.add(record);
        }
        return records;
    }

    @Override
    public List<Integer> getSubDeviceSessions(String name) {
        return mXmppConnectionService.databaseBackend.getSubDeviceSessions(
                account, new SignalProtocolAddress(name, 1));
    }

    public List<String> getKnownAddresses() {
        return mXmppConnectionService.databaseBackend.getKnownSignalAddresses(account);
    }

    @Override
    public void storeSession(SignalProtocolAddress address, SessionRecord record) {
        mXmppConnectionService.databaseBackend.storeSession(account, address, record);
    }

    @Override
    public boolean containsSession(SignalProtocolAddress address) {
        return mXmppConnectionService.databaseBackend.containsSession(account, address);
    }

    @Override
    public void deleteSession(SignalProtocolAddress address) {
        mXmppConnectionService.databaseBackend.deleteSession(account, address);
    }

    @Override
    public void deleteAllSessions(String name) {
        SignalProtocolAddress address = new SignalProtocolAddress(name, 1);
        mXmppConnectionService.databaseBackend.deleteAllSessions(account, address);
    }

    // --------------------------------------
    // PreKeyStore
    // --------------------------------------

    @Override
    public PreKeyRecord loadPreKey(int preKeyId) throws InvalidKeyIdException {
        PreKeyRecord record = mXmppConnectionService.databaseBackend.loadPreKey(account, preKeyId);
        if (record == null) {
            throw new InvalidKeyIdException("No such PreKeyRecord: " + preKeyId);
        }
        return record;
    }

    @Override
    public void storePreKey(int preKeyId, PreKeyRecord record) {
        mXmppConnectionService.databaseBackend.storePreKey(account, record);
        currentPreKeyId = preKeyId;
        boolean success = this.account.setKey(JSONKEY_CURRENT_PREKEY_ID, Integer.toString(preKeyId));
        if (success) {
            mXmppConnectionService.databaseBackend.updateAccount(account);
        } else {
            Log.e(Config.LOGTAG, AxolotlService.getLogprefix(account) + "Failed to write new prekey id to the database!");
        }
    }

    @Override
    public boolean containsPreKey(int preKeyId) {
        return mXmppConnectionService.databaseBackend.containsPreKey(account, preKeyId);
    }

    @Override
    public void removePreKey(int preKeyId) {
        Log.d(Config.LOGTAG, "mark prekey for removal " + preKeyId);
        synchronized (preKeysMarkedForRemoval) {
            preKeysMarkedForRemoval.add(preKeyId);
        }
    }

    public boolean flushPreKeys() {
        Log.d(Config.LOGTAG, "flushing pre keys");
        int count = 0;
        synchronized (preKeysMarkedForRemoval) {
            for (Integer preKeyId : preKeysMarkedForRemoval) {
                count += mXmppConnectionService.databaseBackend.deletePreKey(account, preKeyId);
            }
            preKeysMarkedForRemoval.clear();
        }
        return count > 0;
    }

    // --------------------------------------
    // SignedPreKeyStore
    // --------------------------------------

    @Override
    public SignedPreKeyRecord loadSignedPreKey(int signedPreKeyId) throws InvalidKeyIdException {
        SignedPreKeyRecord record = mXmppConnectionService.databaseBackend.loadSignedPreKey(account, signedPreKeyId);
        if (record == null) {
            throw new InvalidKeyIdException("No such SignedPreKeyRecord: " + signedPreKeyId);
        }
        return record;
    }

    @Override
    public List<SignedPreKeyRecord> loadSignedPreKeys() {
        return mXmppConnectionService.databaseBackend.loadSignedPreKeys(account);
    }

    public int getSignedPreKeysCount() {
        return mXmppConnectionService.databaseBackend.getSignedPreKeysCount(account);
    }

    @Override
    public void storeSignedPreKey(int signedPreKeyId, SignedPreKeyRecord record) {
        mXmppConnectionService.databaseBackend.storeSignedPreKey(account, record);
    }

    @Override
    public boolean containsSignedPreKey(int signedPreKeyId) {
        return mXmppConnectionService.databaseBackend.containsSignedPreKey(account, signedPreKeyId);
    }

    @Override
    public void removeSignedPreKey(int signedPreKeyId) {
        mXmppConnectionService.databaseBackend.deleteSignedPreKey(account, signedPreKeyId);
    }

    // --------------------------------------
    // KyberPreKeyStore (PQXDH)
    // --------------------------------------

    @Override
    public KyberPreKeyRecord loadKyberPreKey(int kyberPreKeyId) throws InvalidKeyIdException {
        KyberPreKeyRecord record = mXmppConnectionService.databaseBackend.loadKyberPreKey(account, kyberPreKeyId);
        if (record == null) {
            throw new InvalidKeyIdException("No such KyberPreKeyRecord: " + kyberPreKeyId);
        }
        return record;
    }

    @Override
    public List<KyberPreKeyRecord> loadKyberPreKeys() {
        return mXmppConnectionService.databaseBackend.loadKyberPreKeys(account);
    }

    @Override
    public void storeKyberPreKey(int kyberPreKeyId, KyberPreKeyRecord record) {
        mXmppConnectionService.databaseBackend.storeKyberPreKey(account, record, false);
        currentKemPreKeyId = kyberPreKeyId;
        boolean success = this.account.setKey(JSONKEY_CURRENT_KEM_PREKEY_ID, Integer.toString(kyberPreKeyId));
        if (success) {
            mXmppConnectionService.databaseBackend.updateAccount(account);
        }
    }

    public void storeKyberLastResortPreKey(int kyberPreKeyId, KyberPreKeyRecord record) {
        mXmppConnectionService.databaseBackend.storeKyberPreKey(account, record, true);
        currentKemPreKeyId = kyberPreKeyId;
        boolean success = this.account.setKey(JSONKEY_CURRENT_KEM_PREKEY_ID, Integer.toString(kyberPreKeyId));
        if (success) {
            mXmppConnectionService.databaseBackend.updateAccount(account);
        }
    }

    @Override
    public boolean containsKyberPreKey(int kyberPreKeyId) {
        return mXmppConnectionService.databaseBackend.containsKyberPreKey(account, kyberPreKeyId);
    }

    @Override
    public void markKyberPreKeyUsed(int kyberPreKeyId, int signedPreKeyId, ECPublicKey baseKey)
            throws ReusedBaseKeyException {
        if (mXmppConnectionService.databaseBackend.isKyberPreKeyLastResort(account, kyberPreKeyId)) {
            // Last-resort key: protect against replay by tracking the
            // (kemId, spkId, baseKey) tuple. Throw if seen before; do NOT delete.
            if (mXmppConnectionService.databaseBackend.kyberLastResortSessionExists(
                    account, kyberPreKeyId, signedPreKeyId, baseKey.serialize())) {
                throw new ReusedBaseKeyException(
                        "Kyber last-resort prekey " + kyberPreKeyId + " replayed");
            }
            mXmppConnectionService.databaseBackend.recordKyberLastResortSession(
                    account, kyberPreKeyId, signedPreKeyId, baseKey.serialize());
        } else {
            // One-time key: delete after single use.
            mXmppConnectionService.databaseBackend.deleteKyberPreKey(account, kyberPreKeyId);
        }
    }

    public int getKyberOneTimePreKeyCount() {
        return mXmppConnectionService.databaseBackend.countKyberOneTimePreKeys(account);
    }

    // --------------------------------------
    // SenderKeyStore (group sessions – stubbed, not used in XMPP)
    // --------------------------------------

    @Override
    public void storeSenderKey(SignalProtocolAddress sender, UUID distributionId, SenderKeyRecord record) {
        // not used
    }

    @Override
    public SenderKeyRecord loadSenderKey(SignalProtocolAddress sender, UUID distributionId) {
        return null;
    }

    // --------------------------------------
    // Fingerprint helpers (used by UI)
    // --------------------------------------

    public void preVerifyFingerprint(Account account, String name, String fingerprint) {
        mXmppConnectionService.databaseBackend.storePreVerification(
                account, name, fingerprint, FingerprintStatus.createInactiveVerified());
    }
}
