package eu.siacs.conversations.crypto.axolotl;

import static eu.siacs.conversations.utils.Random.SECURE_RANDOM;

import android.os.Bundle;
import android.security.KeyChain;
import android.util.Base64;
import android.util.Log;
import android.util.Pair;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.pqc.jcajce.provider.BouncyCastlePQCProvider;
import org.signal.libsignal.protocol.IdentityKey;
import org.signal.libsignal.protocol.pqid.PqBundle;
import org.signal.libsignal.protocol.pqid.PqIdentityKey;
import org.signal.libsignal.protocol.pqid.PqIdentityKeyPair;
import org.signal.libsignal.protocol.IdentityKeyPair;
import org.signal.libsignal.protocol.InvalidKeyException;
import org.signal.libsignal.protocol.InvalidKeyIdException;
import org.signal.libsignal.protocol.SessionBuilder;
import org.signal.libsignal.protocol.SignalProtocolAddress;
import org.signal.libsignal.protocol.UntrustedIdentityException;
import org.signal.libsignal.protocol.ecc.ECKeyPair;
import org.signal.libsignal.protocol.ecc.ECPublicKey;
import org.signal.libsignal.protocol.kem.KEMKeyPair;
import org.signal.libsignal.protocol.kem.KEMKeyType;
import org.signal.libsignal.protocol.state.KyberPreKeyRecord;
import org.signal.libsignal.protocol.state.PreKeyBundle;
import org.signal.libsignal.protocol.state.PreKeyRecord;
import org.signal.libsignal.protocol.state.SessionRecord;
import org.signal.libsignal.protocol.state.SignedPreKeyRecord;

import java.security.PrivateKey;
import java.security.Security;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Contact;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.parser.IqParser;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.utils.CryptoHelper;
import eu.siacs.conversations.utils.SerialSingleThreadExecutor;
import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xml.Namespace;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.OnAdvancedStreamFeaturesLoaded;
import eu.siacs.conversations.xmpp.XmppConnection;
import eu.siacs.conversations.xmpp.jingle.DescriptionTransport;
import eu.siacs.conversations.xmpp.jingle.OmemoVerification;
import eu.siacs.conversations.xmpp.jingle.OmemoVerifiedRtpContentMap;
import eu.siacs.conversations.xmpp.jingle.RtpContentMap;
import eu.siacs.conversations.xmpp.jingle.stanzas.IceUdpTransportInfo;
import eu.siacs.conversations.xmpp.jingle.stanzas.OmemoVerifiedIceUdpTransportInfo;
import eu.siacs.conversations.xmpp.jingle.stanzas.RtpDescription;
import eu.siacs.conversations.xmpp.pep.PublishOptions;
import im.conversations.android.xmpp.model.stanza.Iq;

public class AxolotlService implements OnAdvancedStreamFeaturesLoaded {

    public static final String PEP_PREFIX = "eu.siacs.conversations.axolotl";
    public static final String PEP_DEVICE_LIST = PEP_PREFIX + ".devicelist";
    public static final String PEP_DEVICE_LIST_NOTIFY = PEP_DEVICE_LIST + "+notify";
    public static final String PEP_BUNDLES = PEP_PREFIX + ".bundles";
    public static final String PEP_VERIFICATION = PEP_PREFIX + ".verification";
    public static final String PEP_OMEMO_WHITELISTED = PEP_PREFIX + ".whitelisted";

    public static final String LOGPREFIX = "AxolotlService";

    private static final int NUM_KEYS_TO_PUBLISH = 100;

    /**
     * Most devices we accept from one JID's published OMEMO device list, either stack.
     *
     * <p>Every accepted id costs an outbound bundle fetch (see {@code registerDevices} and
     * {@code findDevicesWithoutSession}) plus a cached session and fetch-status entry, and
     * nothing else bounds the list: the XML reader limits nesting depth, not child count, so
     * a hostile PEP node could turn a single stanza into tens of thousands of IQ round-trips.
     * Far above any plausible real account — Conversations' own trust UI becomes unusable
     * long before this.
     */
    public static final int MAX_DEVICES_PER_JID = 128;
    private static final int publishTriesThreshold = 3;
    // XEP-0384: the first message received for a given ratchet key whose Double Ratchet
    // counter reaches this value MUST be answered with a heartbeat (an empty OMEMO
    // message), forcing a DH-ratchet step so the peer's next chain restarts at 0.
    private static final int HEARTBEAT_COUNTER_THRESHOLD = 53;
    // Hard timeout (seconds) for the PEP requests that feed the trust screen
    // (device lists and bundles). Without it a request that never gets an answer
    // — most commonly one written while the stream is not bound, which is dropped
    // silently — leaves the fetch marked PENDING forever: the trust screen then
    // shows "Fetching keys…" with a disabled button, reopens on every send, and
    // never retries because the pending entry suppresses new requests.
    private static final long FETCH_TIMEOUT = 30;
    // Rotate the EC signed prekey after this age, mirroring KEM_SPK_ROTATION_MS.
    private static final long SIGNED_PREKEY_ROTATION_MS = 30L * 24 * 60 * 60 * 1000;

    public static final String PEP_OMEMO2_DEVICE_LIST = Namespace.OMEMO2_DEVICES;
    public static final String PEP_OMEMO2_DEVICE_LIST_NOTIFY = PEP_OMEMO2_DEVICE_LIST + "+notify";
    public static final String PEP_OMEMO2_BUNDLES = Namespace.OMEMO2_BUNDLES;

    final Account account;
    public final XmppConnectionService mXmppConnectionService;
    private final SQLiteAxolotlStore axolotlStore;
    private final SessionMap sessions;
    // Legacy XEP-0384 v0.3 device IDs (published at PEP_DEVICE_LIST). Kept
    // strictly separate from the OMEMO2 device IDs below: the two device lists
    // live at different PEP nodes and routinely differ (a contact may run a new
    // PQ device and an old legacy-only one at the same time, or be legacy-only).
    // They MUST NOT share one map — a single shared map let whichever device-list
    // notification arrived last overwrite the other, wiping legacy device IDs
    // (breaking legacy sending) and making the OMEMO2 trust screen flap on every
    // reconnect. Each stack reads its own map.
    private final Map<Jid, Set<Integer>> deviceIds;
    private final Map<Jid, Set<Integer>> omemo2DeviceIds = new HashMap<>();
    private final Map<String, XmppAxolotlMessage> messageCache;
    // Prepared-but-not-yet-sent OMEMO2 messages, keyed by message UUID. The
    // message key inside each entry is already wiped (buildOmemo2Header zeroes it
    // after the last per-device wrap), so entries hold only ciphertext and wrapped
    // keys — the LRU cap is memory hygiene for messages whose resend never happens.
    private final Map<String, XmppOmemo2Message> omemo2MessageCache =
            java.util.Collections.synchronizedMap(
                    new java.util.LinkedHashMap<String, XmppOmemo2Message>(16, 0.75f, true) {
                        @Override
                        protected boolean removeEldestEntry(
                                final Map.Entry<String, XmppOmemo2Message> eldest) {
                            return size() > 64;
                        }
                    });
    // Set when a server-side check finds our OMEMO2 bundle node missing/empty so
    // the next publishBundlesIfNeeded() forces a republish even if the local KEM
    // store is non-empty (e.g. a previous publish IQ failed). See Fix 1.
    private volatile boolean forceOmemo2BundleRepublish = false;
    private final FetchStatusMap fetchStatusMap;
    // Outcome of the last device-list fetch per JID, tracked separately per stack
    // for the same reason the device-id maps above are separate: a contact very
    // often has a list on one node and none on the other (legacy-only, or
    // PQ-only). With one shared map the later fetch overwrote the earlier one's
    // outcome, so the "this contact has no keys on this stack" signal was lost
    // and the trust screen kept reopening on every send attempt instead of
    // failing closed with a toast. Value true = list fetched and non-empty,
    // false = fetch failed or the list is empty, absent = unknown (never tried,
    // or the request timed out and should be retried).
    // Synchronized: written from iq-response callbacks (connection thread) and
    // from fetch timeouts (scheduler thread), read from the UI thread.
    private final Map<Jid, Boolean> fetchDeviceListStatus =
            Collections.synchronizedMap(new HashMap<>());
    private final Map<Jid, Boolean> omemo2FetchDeviceListStatus =
            Collections.synchronizedMap(new HashMap<>());
    private final HashMap<Jid, List<OnDeviceIdsFetched>> fetchDeviceIdsMap = new HashMap<>();
    private final SerialSingleThreadExecutor executor;
    private final Set<SignalProtocolAddress> healingAttempts = new HashSet<>();
    // XEP-0384 heartbeat de-duplication: the sender ratchet key we last heartbeated
    // for, per peer device, so we send at most one heartbeat per receiving chain.
    private final Map<SignalProtocolAddress, byte[]> heartbeatRatchetKeys = new HashMap<>();
    // Devices we already attempted a background pq_ik pin reconciliation for this
    // app run (see reconcileOmemo2PqPinIfMissing) — at most one bundle fetch per
    // device per run, whether or not it succeeds.
    private final Set<SignalProtocolAddress> pqPinReconcileAttempts =
            Collections.synchronizedSet(new HashSet<>());
    private final HashSet<Integer> cleanedOwnDeviceIds = new HashSet<>();
    private final Set<Integer> PREVIOUSLY_REMOVED_FROM_ANNOUNCEMENT = new HashSet<>();
    private int numPublishTriesOnEmptyPep = 0;
    private boolean pepBroken = false;
    // Own device-list de-duplication hashes, tracked separately per stack: the
    // legacy (XEP-0384 v0.3) and OMEMO2 device lists are independent PEP nodes
    // and may carry different device-id sets. Sharing one hash could let one
    // stack's notification suppress the other's, skipping proactive own-device
    // session building for that stack.
    private int lastDeviceListNotificationHash = 0;
    private int lastOmemo2DeviceListNotificationHash = 0;
    // Sessions stored here receive "complete session" treatment after MAM
    // catch-up. The Boolean records the stack the prekey message arrived on
    // (true = PQ OMEMO2, false = legacy XEP-0384 v0.3) so completion happens on
    // the SAME stack — never building a legacy key-transport from a PQ session
    // or vice versa (strict OMEMO2/legacy separation).
    private final Map<XmppAxolotlSession, Boolean> postponedSessions = new HashMap<>();
    // Addresses needing a healing notification after MAM catch-up. The value is
    // whether the broken session was an OMEMO2 (PQ) session, so healing rebuilds
    // it via the correct stack instead of always falling back to the legacy one.
    private final Map<SignalProtocolAddress, Boolean> postponedHealing = new HashMap<>();
    private final AtomicBoolean changeAccessMode = new AtomicBoolean(false);

    public AxolotlService(Account account, XmppConnectionService connectionService) {
        if (account == null || connectionService == null) {
            throw new IllegalArgumentException("account and service cannot be null");
        }
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
        this.mXmppConnectionService = connectionService;
        this.account = account;
        this.axolotlStore = new SQLiteAxolotlStore(this.account, this.mXmppConnectionService);
        migrateToSeparateOmemo2IdentityIfNeeded();
        this.deviceIds = new HashMap<>();
        this.messageCache = new HashMap<>();
        this.sessions = new SessionMap(mXmppConnectionService, axolotlStore, account);
        this.fetchStatusMap = new FetchStatusMap();
        this.executor = new SerialSingleThreadExecutor("Axolotl");
    }

    /**
     * Ensure the PQ OMEMO2 stack has its OWN identity key, distinct from the
     * legacy OMEMO key, so the two never share a fingerprint and trust never
     * bleeds across stacks (proto-XEP §1.2 strict separation / never downgrade).
     *
     * <p>Runs once: when no separate OMEMO2 identity exists yet — a fresh install,
     * or the first run after updating from a build that shared one key between the
     * stacks. The existing own-key row (name = bareJid) stays as the LEGACY key, so
     * legacy peers that already verified this device keep recognising it; the OMEMO2
     * stack mints a brand-new key on its next {@code getIdentityKeyPair()} call
     * ({@link SQLiteAxolotlStore#loadIdentityKeyPair}).
     *
     * <p>Because the new identity key must re-sign all published key material
     * (peers MUST abort on a stale signature, proto-XEP §4.4.1/§6.2), we drop the
     * old OMEMO2 sessions/prekeys/KEM material here — scoped to the OMEMO2 stack
     * only, so verified contact fingerprints and the legacy stack are preserved —
     * and force the next publish to regenerate and republish the bundle.
     */
    private void migrateToSeparateOmemo2IdentityIfNeeded() {
        if (mXmppConnectionService.databaseBackend.loadOwnOmemo2IdentityKeyPair(account) != null) {
            return; // already separated
        }
        Log.i(Config.LOGTAG, getLogprefix(account)
                + "no separate OMEMO2 identity yet — re-keying (legacy keeps the original key)");
        mXmppConnectionService.databaseBackend.wipeOmemo2OwnKeyMaterial(account);
        resetOwnPqIdentity(); // the wipe above also dropped the own ML-DSA-87 row
        forceOmemo2BundleRepublish = true;
    }

    public static String getLogprefix(Account account) {
        return LOGPREFIX + " (" + account.getJid().asBareJid().toString() + "): ";
    }

    @Override
    public void onAdvancedStreamFeaturesAvailable(Account account) {
        if (Config.supportOmemo()
                && account.getXmppConnection() != null
                && account.getXmppConnection().getFeatures().pep()) {
            publishBundlesIfNeeded(true, false);
            verifyOmemo2BundlePublished();
        } else {
            Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": skipping OMEMO initialization");
        }
    }

    /**
     * Independently confirm that our OMEMO2 bundle on the server (PEP node
     * {@code Namespace.OMEMO2_BUNDLES}) actually carries KEM material. {@link
     * #publishBundlesIfNeeded(boolean, boolean)} reconciles against the same
     * node, but {@link IqParser#omemo2Bundle} only compares the EC portion of
     * the bundle (ik, spk/spks, one-time prekeys) and the KEM checks in {@link
     * #publishOmemo2BundlesIfNeeded} inspect the local store, not the server.
     * A node that kept a valid EC bundle but lost its KEM elements — a publish
     * IQ that failed after local key generation, or server-side node damage —
     * would therefore pass reconciliation, and peers fetching it could not
     * establish a post-quantum session. If the node is absent, carries no
     * bundle, or carries no KEM material, force a republish (independent of
     * the local KEM-prekey count).
     */
    private void verifyOmemo2BundlePublished() {
        if (pepBroken) return;
        final Iq fetch = mXmppConnectionService.getIqGenerator()
                .retrieveOmemo2BundlesForDevice(account.getJid().asBareJid(), getOwnDeviceId());
        mXmppConnectionService.sendIqPacket(account, fetch, response -> {
            if (response.getType() == Iq.Type.TIMEOUT) {
                return; // transient; try again on next connect
            }
            boolean needsRepublish = false;
            if (response.getType() != Iq.Type.RESULT) {
                // item-not-found (or other error): the node is not usable.
                needsRepublish = true;
            } else {
                final Element item = IqParser.getItem(response);
                final Element bundle = item == null ? null : item.findChild("bundle", Namespace.OMEMO2);
                if (bundle == null) {
                    needsRepublish = true;
                } else {
                    final boolean hasSignedKem = bundle.findChild("kem-spk") != null
                            && bundle.findChildContent("kem-spks") != null;
                    boolean hasOneTimeKem = false;
                    final Element kemPrekeys = bundle.findChild("kem-prekeys");
                    if (kemPrekeys != null) {
                        for (final Element c : kemPrekeys.getChildren()) {
                            if ("kem-pk".equals(c.getName())) {
                                hasOneTimeKem = true;
                                break;
                            }
                        }
                    }
                    needsRepublish = !hasSignedKem && !hasOneTimeKem;
                }
            }
            if (needsRepublish) {
                Log.w(Config.LOGTAG, getLogprefix(account)
                        + "OMEMO2 bundle node missing/empty on server — forcing republish.");
                forceOmemo2BundleRepublish = true;
                publishBundlesIfNeeded(false, false);
            }
        });
    }

    /**
     * Sends one of the PEP fetches the trust screen waits on, with a hard
     * timeout so the callback always runs exactly once — with a real response or
     * with {@link Iq.Type#TIMEOUT} — and the fetch never stays pending forever.
     */
    private void sendFetchIq(final Iq packet, final java.util.function.Consumer<Iq> callback) {
        mXmppConnectionService.sendIqPacket(account, packet, callback, FETCH_TIMEOUT);
    }

    /** The device-list fetch outcomes of a single stack (see the field docs). */
    private Map<Jid, Boolean> deviceListStatus(final boolean omemo2Stack) {
        return omemo2Stack ? this.omemo2FetchDeviceListStatus : this.fetchDeviceListStatus;
    }

    private static boolean stackIsOmemo2(final int encryption) {
        return encryption == Message.ENCRYPTION_AXOLOTL_OMEMO2;
    }

    private boolean hasErrorFetchingDeviceList(final Jid jid, final boolean omemo2Stack) {
        Boolean status = deviceListStatus(omemo2Stack).get(jid);
        return status != null && !status;
    }

    public boolean hasErrorFetchingDeviceList(final List<Jid> jids, final int encryption) {
        for (Jid jid : jids) {
            if (hasErrorFetchingDeviceList(jid, stackIsOmemo2(encryption))) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when a bundle fetch for one of {@code jids} failed permanently on the
     * given stack. Only that stack's device IDs are inspected: a broken legacy
     * device says nothing about the peer's OMEMO2 devices (and vice versa).
     */
    public boolean fetchMapHasErrors(final List<Jid> jids, final int encryption) {
        final boolean omemo2Stack = stackIsOmemo2(encryption);
        for (Jid jid : jids) {
            final Set<Integer> ids = getDeviceIdsForStack(jid, omemo2Stack);
            if (ids != null) {
                for (Integer foreignId : ids) {
                    SignalProtocolAddress address = new SignalProtocolAddress(jid.toString(), foreignId);
                    if (fetchStatusMap.get(address) == FetchStatus.ERROR) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public void preVerifyFingerprint(Contact contact, String fingerprint) {
        axolotlStore.preVerifyFingerprint(contact.getAccount(), contact.getJid().asBareJid().toString(), fingerprint);
    }

    public void preVerifyFingerprint(Account account, String fingerprint) {
        axolotlStore.preVerifyFingerprint(account, account.getJid().asBareJid().toString(), fingerprint);
    }

    /**
     * Whether any key of {@code name} (a bare JID) has been verified. This is the
     * switch that ends blind trust before verification for that contact, so it
     * deliberately spans BOTH stacks: verifying is something the user does to a
     * *contact*, out of band, and a QR code or a fingerprint comparison covers
     * whichever identity key the other side happens to show. Once one of their
     * keys is verified, a newly appearing key — legacy or OMEMO2 — must be
     * decided on explicitly instead of being trusted blindly. (The keys themselves
     * stay strictly separated; only this trust decision looks at both.)
     */
    public boolean hasVerifiedKeys(String name) {
        for (XmppAxolotlSession session : this.sessions.getAll(name).values()) {
            if (session.getTrust().isVerified()) {
                return true;
            }
        }
        // The legacy OMEMO1 crypto backend has been removed: no legacy session
        // can exist any more, so there is nothing left to check there.
        return false;
    }

    public String getOwnFingerprint() {
        return CryptoHelper.bytesToHex(axolotlStore.getIdentityKeyPair().getPublicKey().serialize());
    }

    // Lazily generated/persisted ML-DSA-87 post-quantum half of this device's
    // hybrid identity. Created alongside (and re-keyed with) the classical OMEMO2
    // identity key; see migrateToSeparateOmemo2IdentityIfNeeded / wipeOmemo2OwnKeyMaterial.
    private volatile PqIdentityKeyPair ownPqIdentityKeyPair = null;

    public synchronized PqIdentityKeyPair getOwnPqIdentityKeyPair() {
        if (ownPqIdentityKeyPair == null) {
            final byte[] stored = mXmppConnectionService.databaseBackend.loadOwnOmemo2PqKeyPair(account);
            if (stored != null) {
                ownPqIdentityKeyPair = PqIdentityKeyPair.fromSerialized(stored);
            } else {
                Log.i(Config.LOGTAG, getLogprefix(account)
                        + "generating fresh ML-DSA-87 post-quantum identity key");
                ownPqIdentityKeyPair = PqIdentityKeyPair.generate();
                mXmppConnectionService.databaseBackend.storeOwnOmemo2PqKeyPair(
                        account, ownPqIdentityKeyPair.serialize());
            }
        }
        return ownPqIdentityKeyPair;
    }

    /**
     * The user-verifiable fingerprint of this device's hybrid identity. It commits
     * to BOTH the classical identity key and the post-quantum (ML-DSA-87) identity
     * key, so verifying it out-of-band authenticates the post-quantum key too —
     * without which a quantum adversary able to forge Ed25519 could swap in their
     * own pq_ik. See {@link CryptoHelper#hybridOmemo2Fingerprint(byte[], byte[])}.
     */
    public String getOwnHybridFingerprint() {
        final byte[] ik = axolotlStore.getIdentityKeyPair().getPublicKey().serialize();
        final byte[] pqIk = getOwnPqIdentityKeyPair().getPublicKey().serialize();
        return CryptoHelper.hybridOmemo2Fingerprint(ik, pqIk);
    }

    /**
     * The hybrid (classical-IK + ML-DSA-87) fingerprint to DISPLAY for a peer
     * OMEMO2 device identified by its classical fingerprint
     * ({@code bytesToHex(identityKey.serialize())}), or null when no post-quantum
     * key is pinned for it yet (the caller then shows the classical fingerprint).
     * Internal trust and the QR/URI stay keyed on the classical fingerprint; this
     * is purely the human-verifiable string, which we make commit to the
     * post-quantum key so manual verification authenticates it too.
     *
     * <p>{@code name} (the bare JID that owns the classical key) is required for the same
     * reason it is on the trust lookups: the pin table is keyed on the triple, because a
     * classical identity key is public and any peer can republish someone else's.
     */
    public String hybridFingerprintFor(final String name, final String classicalFingerprint) {
        if (name == null || classicalFingerprint == null) return null;
        final byte[] pqIk = mXmppConnectionService.databaseBackend
                .getPinnedOmemo2PqIdentity(account, name, classicalFingerprint);
        if (pqIk == null) return null;
        try {
            return CryptoHelper.hybridOmemo2Fingerprint(
                    CryptoHelper.hexToBytes(classicalFingerprint), pqIk);
        } catch (final RuntimeException e) {
            return null;
        }
    }

    public Set<IdentityKey> getKeysWithTrust(FingerprintStatus status, int encryption) {
        return filterByStack(axolotlStore.getContactKeysWithTrust(account.getJid().asBareJid().toString(), status), account.getJid().asBareJid(), encryption);
    }

    public Set<IdentityKey> getKeysWithTrust(FingerprintStatus status, Jid jid, int encryption) {
        return filterByStack(axolotlStore.getContactKeysWithTrust(jid.asBareJid().toString(), status), jid, encryption);
    }

    public Set<IdentityKey> getKeysWithTrust(FingerprintStatus status, List<Jid> jids, int encryption) {
        Set<IdentityKey> keys = new HashSet<>();
        for (Jid jid : jids) {
            keys.addAll(filterByStack(axolotlStore.getContactKeysWithTrust(jid.toString(), status), jid, encryption));
        }
        return keys;
    }

    private Set<IdentityKey> filterByStack(Set<IdentityKey> keys, Jid jid, int encryption) {
        final Set<String> stackFingerprints = getFingerprintsForStack(jid, encryption);
        final Set<IdentityKey> filtered = new HashSet<>();
        for (IdentityKey key : keys) {
            if (stackFingerprints.contains(CryptoHelper.bytesToHex(key.getPublicKey().serialize()))) {
                filtered.add(key);
            }
        }
        return filtered;
    }

    public Set<Jid> findCounterpartsBySourceId(int sid) {
        return sessions.findCounterpartsForSourceId(sid);
    }

    public Set<String> getFingerprintsForStack(Jid jid, int encryptionType) {
        if (encryptionType != Message.ENCRYPTION_AXOLOTL_OMEMO2) {
            // The legacy OMEMO1 crypto backend has been removed; no other stack has any
            // fingerprints to report.
            return Collections.emptySet();
        }
        final String bareJid = jid.asBareJid().toString();
        final List<Integer> deviceIds =
                mXmppConnectionService.databaseBackend.getOmemo2SubDeviceSessions(account, bareJid);
        final Set<String> fingerprints = new HashSet<>();
        for (Integer deviceId : deviceIds) {
            final var session = sessions.get(new SignalProtocolAddress(bareJid, deviceId));
            final String fingerprint = session != null ? session.getFingerprint() : null;
            if (fingerprint != null) {
                fingerprints.add(fingerprint);
            }
        }
        return fingerprints;
    }

    public long getNumTrustedKeys(Jid jid, int encryption) {
        final Set<String> stackFingerprints = getFingerprintsForStack(jid, encryption);
        final String bareJid = jid.asBareJid().toString();
        int count = 0;
        for (String fingerprint : stackFingerprints) {
            if (getFingerprintTrust(bareJid, fingerprint).isTrustedAndActive()) {
                count++;
            }
        }
        return count;
    }

    public boolean anyTargetHasNoTrustedKeys(List<Jid> jids, int encryption) {
        for (Jid jid : jids) {
            if (getNumTrustedKeys(jid, encryption) == 0) {
                return true;
            }
        }
        return false;
    }

    private SignalProtocolAddress getAddressForJid(Jid jid) {
        return new SignalProtocolAddress(jid.toString(), 1);
    }

    public Collection<XmppAxolotlSession> findOwnSessions() {
        SignalProtocolAddress ownAddress = getAddressForJid(account.getJid().asBareJid());
        ArrayList<XmppAxolotlSession> s = new ArrayList<>(this.sessions.getAll(ownAddress.getName()).values());
        Collections.sort(s);
        return s;
    }

    public Collection<XmppAxolotlSession> findSessionsForContact(Contact contact) {
        SignalProtocolAddress contactAddress = getAddressForJid(contact.getJid());
        ArrayList<XmppAxolotlSession> s = new ArrayList<>(this.sessions.getAll(contactAddress.getName()).values());
        Collections.sort(s);
        return s;
    }

    public static class LegacySessionInfo {
        public final String fingerprint;
        public final FingerprintStatus status;
        // Device id the legacy session belongs to. Legacy and OMEMO2 share this
        // device's registration id (the legacy bundle is published for
        // getOwnDeviceId() as well), so it is only unique together with the stack.
        public final int deviceId;

        public LegacySessionInfo(String fingerprint, FingerprintStatus status, int deviceId) {
            this.fingerprint = fingerprint;
            this.status = status;
            this.deviceId = deviceId;
        }
    }

    /**
     * The legacy OMEMO1 crypto backend has been removed. Always returns an empty
     * list; kept (rather than deleted) because it is still called from UI code
     * that has not yet been cleaned up of legacy-OMEMO display elements.
     */
    public List<LegacySessionInfo> findLegacySessionsForContact(Contact contact) {
        return Collections.emptyList();
    }

    /**
     * The legacy OMEMO1 crypto backend has been removed, so there is no longer a
     * separate legacy identity key; this always returns null. Kept (rather than
     * deleted) because it is still called from UI code that has not yet been
     * cleaned up of legacy-OMEMO display elements.
     */
    @Nullable
    public String getOwnLegacyFingerprint() {
        return null;
    }

    /**
     * The legacy OMEMO1 crypto backend has been removed. Always returns an empty
     * list; kept (rather than deleted) because it is still called from UI code
     * that has not yet been cleaned up of legacy-OMEMO display elements.
     */
    public List<LegacySessionInfo> findOwnLegacySessions() {
        return Collections.emptyList();
    }

    private Set<XmppAxolotlSession> findSessionsForConversation(Conversation conversation) {
        if (conversation.getContact().isSelf()) {
            //will be added in findOwnSessions()
            return Collections.emptySet();
        }
        HashSet<XmppAxolotlSession> sessions = new HashSet<>();
        for (Jid jid : conversation.getAcceptedCryptoTargets()) {
            sessions.addAll(this.sessions.getAll(getAddressForJid(jid).getName()).values());
        }
        return sessions;
    }

    private boolean hasAny(Jid jid) {
        return sessions.hasAny(getAddressForJid(jid));
    }

    public boolean isPepBroken() {
        return this.pepBroken;
    }

    public void resetBrokenness() {
        this.pepBroken = false;
        this.numPublishTriesOnEmptyPep = 0;
        this.lastDeviceListNotificationHash = 0;
        this.lastOmemo2DeviceListNotificationHash = 0;
        this.healingAttempts.clear();
    }

    public void clearErrorsInFetchStatusMap(Jid jid) {
        fetchStatusMap.clearErrorFor(jid);
        fetchDeviceListStatus.remove(jid);
        omemo2FetchDeviceListStatus.remove(jid);
    }

    public void regenerateKeys(boolean wipeOther) {
        axolotlStore.regenerate();
        // The store wipe above deleted our ML-DSA-87 key pair row; drop the
        // in-memory copy too, BEFORE republishing. Otherwise the bundle publish
        // below would still sign with the old (possibly compromised) post-quantum
        // identity, and the next app start would generate a fresh one anyway —
        // leaving peers pinned to a pq_ik that immediately changes.
        resetOwnPqIdentity();
        sessions.clear();
        fetchStatusMap.clear();
        fetchDeviceIdsMap.clear();
        fetchDeviceListStatus.clear();
        omemo2FetchDeviceListStatus.clear();
        publishBundlesIfNeeded(true, wipeOther);
    }

    /** Forget the cached own ML-DSA-87 key pair; the next {@link #getOwnPqIdentityKeyPair()} reloads or regenerates it. */
    private synchronized void resetOwnPqIdentity() {
        ownPqIdentityKeyPair = null;
    }

    public void destroy() {
        Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": destroying old axolotl service. no longer in use");
        mXmppConnectionService.databaseBackend.wipeAxolotlDb(account);
    }

    public AxolotlService makeNew() {
        Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": make new axolotl service");
        return new AxolotlService(this.account, this.mXmppConnectionService);
    }

    public int getOwnDeviceId() {
        return axolotlStore.getLocalRegistrationId();
    }

    // In libsignal 0.94.1, getRemoteIdentityKey() throws IllegalStateException on empty sessions
    // rather than returning null. Guard every call site with this helper.
    private static IdentityKey getRemoteIdentityKeySafe(final SessionRecord session) {
        try {
            return session.getRemoteIdentityKey();
        } catch (final IllegalStateException ignored) {
            return null;
        }
    }

    public SignalProtocolAddress getOwnAxolotlAddress() {
        return new SignalProtocolAddress(account.getJid().asBareJid().toString(), getOwnDeviceId());
    }

    public Set<Integer> getOwnDeviceIds() {
        return getDeviceIds(account.getJid().asBareJid());
    }

    /**
     * The device IDs known for {@code jid} on a single stack: the OMEMO2 map
     * when {@code omemo2Stack}, otherwise the legacy map. May be null.
     */
    private Set<Integer> getDeviceIdsForStack(final Jid jid, final boolean omemo2Stack) {
        return (omemo2Stack ? this.omemo2DeviceIds : this.deviceIds).get(jid);
    }

    /**
     * The union of legacy and OMEMO2 device IDs known for {@code jid}. Returns
     * null only when neither stack knows any device for the JID, preserving the
     * nullable contract callers relied on with the old single map.
     */
    public Set<Integer> getDeviceIds(final Jid jid) {
        final Set<Integer> legacy = this.deviceIds.get(jid);
        final Set<Integer> omemo2 = this.omemo2DeviceIds.get(jid);
        if (legacy == null && omemo2 == null) {
            return null;
        }
        final Set<Integer> union = new HashSet<>();
        if (legacy != null) {
            union.addAll(legacy);
        }
        if (omemo2 != null) {
            union.addAll(omemo2);
        }
        return union;
    }

    public void registerDevices(final Jid jid, @NonNull final Set<Integer> deviceIds) {
        registerDevices(jid, deviceIds, false);
    }

    public void registerDevices(final Jid jid, @NonNull final Set<Integer> deviceIds, final boolean omemo2Stack) {
        // A non-empty list clears a previously recorded "no devices on this
        // stack" (set by the device-id fetches when the list came back empty or
        // the request failed), so a contact who starts publishing devices — or
        // migrates between stacks — recovers without a restart. Only the stack
        // the list belongs to is touched; the other one keeps its own outcome.
        // registerOmemo2Devices() does the same before delegating here, for the
        // OMEMO2 PEP notification path.
        if (!omemo2Stack && !deviceIds.isEmpty()) {
            fetchDeviceListStatus.remove(jid);
        }
        final int hash = deviceIds.hashCode();
        final boolean me = jid.asBareJid().equals(account.getJid().asBareJid());
        if (me) {
            final int lastHash = omemo2Stack
                    ? this.lastOmemo2DeviceListNotificationHash
                    : this.lastDeviceListNotificationHash;
            if (hash != 0 && hash == lastHash) {
                Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": ignoring duplicate own device id list");
                return;
            }
            if (omemo2Stack) {
                this.lastOmemo2DeviceListNotificationHash = hash;
            } else {
                this.lastDeviceListNotificationHash = hash;
            }
        }
        boolean needsPublishing = me && !deviceIds.contains(getOwnDeviceId());
        if (me) {
            deviceIds.remove(getOwnDeviceId());
        }
        // Active/inactive session-trust bookkeeping concerns the OMEMO2 session
        // cache and store only. The legacy OMEMO1 crypto backend has been
        // removed, so there is no equivalent legacy trust-active bookkeeping to
        // run any more.
        if (omemo2Stack) {
            final Set<Integer> expiredDevices = new HashSet<>(axolotlStore.getSubDeviceSessions(jid.asBareJid().toString()));
            expiredDevices.removeAll(deviceIds);
            for (Integer deviceId : expiredDevices) {
                SignalProtocolAddress address = new SignalProtocolAddress(jid.asBareJid().toString(), deviceId);
                XmppAxolotlSession session = sessions.get(address);
                if (session != null && session.getFingerprint() != null) {
                    if (session.getTrust().isActive()) {
                        session.setTrust(session.getTrust().toInactive());
                    }
                }
            }
            final Set<Integer> newDevices = ImmutableSet.copyOf(deviceIds);
            for (final Integer deviceId : newDevices) {
                SignalProtocolAddress address = new SignalProtocolAddress(jid.asBareJid().toString(), deviceId);
                XmppAxolotlSession session = sessions.get(address);
                if (session != null && session.getFingerprint() != null) {
                    if (!session.getTrust().isActive()) {
                        Log.d(Config.LOGTAG, "reactivating device with fingerprint " + session.getFingerprint());
                        session.setTrust(session.getTrust().toActive());
                    }
                }
            }
        }
        if (me) {
            // Auto-expiry inspects OMEMO2 own sessions; only meaningful for the
            // OMEMO2 device list.
            boolean prunedExpiredDevices = false;
            if (omemo2Stack && mXmppConnectionService.getOmemoAutoExpiry() != 0) {
                prunedExpiredDevices = deviceIds.removeAll(getExpiredDevices());
                needsPublishing |= prunedExpiredDevices;
            }
            needsPublishing |= this.changeAccessMode.get();
            for (final Integer deviceId : deviceIds) {
                SignalProtocolAddress ownDeviceAddress = new SignalProtocolAddress(jid.asBareJid().toString(), deviceId);
                if (omemo2Stack) {
                    if (sessions.get(ownDeviceAddress) == null) {
                        FetchStatus status = fetchStatusMap.get(ownDeviceAddress);
                        if (status == null || status == FetchStatus.TIMEOUT) {
                            fetchStatusMap.put(ownDeviceAddress, FetchStatus.PENDING);
                            this.buildSessionFromOmemo2PEP(ownDeviceAddress, null, SettableFuture.create());
                        }
                    }
                } else {
                    if (sessions.get(ownDeviceAddress) == null) {
                        FetchStatus status = fetchStatusMap.get(ownDeviceAddress);
                        if (status == null || status == FetchStatus.TIMEOUT) {
                            fetchStatusMap.put(ownDeviceAddress, FetchStatus.PENDING);
                            this.buildSessionFromPEP(ownDeviceAddress);
                        }
                    }
                }
            }
            if (needsPublishing) {
                // do not run next device list update notification through de-duplication (might get
                // skipped by CSI). Republish to the SAME stack's node — mixing
                // them up would announce OMEMO2 devices on the legacy node.
                if (omemo2Stack) {
                    this.lastOmemo2DeviceListNotificationHash = 0;
                    if (prunedExpiredDevices) {
                        // Publish the set we just pruned, NOT a freshly fetched one:
                        // publishOmemo2DeviceId() re-reads the node and would put the
                        // expired ids straight back, which is why auto-expiry never
                        // actually removed anything from the OMEMO2 list.
                        publishOmemo2DeviceIds(deviceIds);
                    } else {
                        // Nothing was deliberately removed, so take the re-fetching path:
                        // publishing a locally-held list here would let a TRANSIENT empty
                        // or partial device list drop a LIVE device from the announcement
                        // and lose messages to it.
                        publishOmemo2DeviceId();
                    }
                } else {
                    this.lastDeviceListNotificationHash = 0;
                    publishOwnDeviceId(deviceIds);
                }
            }
        }
        final Map<Jid, Set<Integer>> target = omemo2Stack ? this.omemo2DeviceIds : this.deviceIds;
        final Set<Integer> oldSet = target.get(jid);
        final boolean changed = oldSet == null || oldSet.hashCode() != hash;
        target.put(jid, deviceIds);
        if (omemo2Stack && !deviceIds.isEmpty()) {
            upgradeLegacyConversationsToOmemo2(jid.asBareJid());
        }
        if (changed) {
            mXmppConnectionService.updateConversationUi(); //update the lock icon
            mXmppConnectionService.keyStatusUpdated(null);
            if (me) {
                mXmppConnectionService.updateAccountUi();
            }
        } else {
            Log.d(Config.LOGTAG, "skipped device list update because it hasn't changed");
        }
    }

    /**
     * Bulk half of {@link #upgradeConversationToOmemo2IfPossible(Conversation)}, called
     * whenever a non-empty OMEMO2 device list is registered for {@code bare}.
     */
    private void upgradeLegacyConversationsToOmemo2(final Jid bare) {
        // Our own JID is not filtered out here: it is a crypto target of the
        // note-to-self chat, and there our other devices ARE the participants.
        // For every other chat the target check below skips it.
        for (final Conversation conversation : mXmppConnectionService.getConversations()) {
            // Cheap checks first; this runs on every device list we register.
            if (conversation.getAccount() != account
                    || conversation.getBooleanAttribute(
                            Conversation.ATTRIBUTE_ALLOW_LEGACY_OMEMO, false)
                    || conversation.getNextEncryption() != Message.ENCRYPTION_AXOLOTL) {
                continue;
            }
            if (!getCryptoTargets(conversation).contains(bare)) {
                continue;
            }
            upgradeConversationToOmemo2IfPossible(conversation);
        }
    }

    /**
     * Moves a chat off legacy OMEMO once every participant announces OMEMO2 devices.
     * Public so the chat UI can re-evaluate when a conversation is opened: the
     * OMEMO2 device list may have been registered long before this chat existed
     * or was last looked at, in which case there is no device-list event left
     * to react to.
     *
     * <p>The legacy OMEMO1 crypto backend has been removed, so {@link
     * Conversation#getNextEncryption()} never reports {@code ENCRYPTION_AXOLOTL} any more —
     * it always converts a stored legacy value to OMEMO2 itself. That makes this method
     * unreachable in practice (the check below always returns early); it is kept as a
     * safety net for that invariant rather than as a live rollout mechanism.
     */
    public void upgradeConversationToOmemo2IfPossible(final Conversation conversation) {
        if (conversation.getBooleanAttribute(Conversation.ATTRIBUTE_ALLOW_LEGACY_OMEMO, false)) {
            // The user picked legacy for this chat. Their choice wins.
            return;
        }
        if (conversation.getNextEncryption() != Message.ENCRYPTION_AXOLOTL) {
            return;
        }
        final List<Jid> targets = getCryptoTargets(conversation);
        if (targets.isEmpty()) {
            return;
        }
        for (final Jid target : targets) {
            final Jid bare = target.asBareJid();
            final Set<Integer> omemo2 = this.omemo2DeviceIds.get(bare);
            if (omemo2 != null && !omemo2.isEmpty()) {
                continue;
            }
            if (bare.equals(account.getJid().asBareJid())) {
                // Note to self: the only "participant" is us. Both maps exclude
                // this device (see registerDevices), so an empty OMEMO2 list
                // just means our OTHER devices are legacy-only — unless there
                // are no other devices at all, in which case nobody is left
                // behind by the upgrade.
                final Set<Integer> legacy = this.deviceIds.get(bare);
                if (legacy == null || legacy.isEmpty()) {
                    continue;
                }
            }
            // Not (yet) known to do OMEMO2 — upgrading now would make this
            // chat unsendable for them. Try again on their next device list.
            return;
        }
        if (conversation.setNextEncryption(Message.ENCRYPTION_AXOLOTL_OMEMO2)) {
            Log.d(Config.LOGTAG, getLogprefix(account)
                    + "all participants of " + conversation.getJid().asBareJid()
                    + " announce OMEMO2 devices — upgrading chat from legacy OMEMO");
            mXmppConnectionService.updateConversation(conversation);
            mXmppConnectionService.updateConversationUi();
        }
    }

    public void wipeOtherPepDevices() {
        if (pepBroken) {
            Log.d(Config.LOGTAG, getLogprefix(account) + "wipeOtherPepDevices called, but PEP is broken. Ignoring... ");
            return;
        }
        Set<Integer> deviceIds = new HashSet<>();
        deviceIds.add(getOwnDeviceId());
        // Both stacks announce on their OWN PEP node. Clearing only the legacy one left
        // every PQ OMEMO2 device announced, so "clear devices" did nothing for them.
        // Two independent publishes: a failure on one must not skip the other.
        publishDeviceIdsAndRefineAccessModel(deviceIds);
        publishOmemo2DeviceIds(deviceIds);
    }

    /**
     * Permanently removes one of our OWN other devices: its session, its identity row and
     * (for OMEMO2) its pinned ML-DSA-87 key, then re-announces the device list of that
     * stack without it. Until now nothing ever deleted these rows, so a device that had
     * gone away stayed in "Other devices" forever with a dead, greyed-out trust switch.
     *
     * <p>Strictly single-stack: an OMEMO2 purge never touches the legacy session table or
     * the legacy PEP node, and vice versa.
     *
     * @return false when the purge was refused — this device itself, or a key the user has
     *         manually VERIFIED. Deleting a verified row would silently discard that
     *         verification, so it has to be distrusted first.
     */
    public boolean purgeOwnDevice(final int deviceId, final String fingerprint, final boolean legacy) {
        if (fingerprint == null || deviceId == getOwnDeviceId()) {
            return false;
        }
        if (getFingerprintTrust(account.getJid().asBareJid().toString(), fingerprint).isVerified()) {
            Log.d(Config.LOGTAG, account.getJid().asBareJid()
                    + ": refusing to purge verified device " + deviceId);
            return false;
        }
        final Jid bare = account.getJid().asBareJid();
        final String bareJid = bare.toString();
        final SignalProtocolAddress address = new SignalProtocolAddress(bareJid, deviceId);
        final Map<Jid, Set<Integer>> announced = legacy ? this.deviceIds : this.omemo2DeviceIds;
        // Re-announce only when this device is actually IN our cached list. If it is not,
        // the device is not announced anyway and publishing a locally-held list could drop
        // a LIVE device from the announcement whenever that cache is stale or was never
        // filled this session.
        final boolean wasAnnounced =
                announced.get(bare) != null && announced.get(bare).contains(deviceId);
        final Set<Integer> remaining = withoutDevice(announced.get(bare), deviceId);
        announced.put(bare, remaining);
        if (legacy) {
            mXmppConnectionService.databaseBackend.deleteLegacySession(account, bareJid, deviceId);
            if (wasAnnounced) {
                this.lastDeviceListNotificationHash = 0;
                // Publish directly rather than through publishOwnDeviceId(): purging the
                // last other device legitimately leaves an empty set, which that method
                // treats as a symptom of broken PEP and would eventually latch pepBroken on.
                final Set<Integer> toPublish = new HashSet<>(remaining);
                toPublish.add(getOwnDeviceId());
                publishDeviceIdsAndRefineAccessModel(toPublish);
            }
        } else {
            sessions.remove(address);
            fetchStatusMap.remove(address);
            mXmppConnectionService.databaseBackend.deleteSession(account, address);
            mXmppConnectionService.databaseBackend.unpinOmemo2PqIdentity(
                    account, bareJid, fingerprint);
            if (wasAnnounced) {
                this.lastOmemo2DeviceListNotificationHash = 0;
                publishOmemo2DeviceIds(remaining);
            }
        }
        // The identities table is shared by both stacks and keyed on the fingerprint
        // alone, so only drop the row once no session in EITHER stack still pins this key.
        if (!stillReferencedByAnySession(fingerprint)) {
            axolotlStore.deleteFingerprint(bareJid, fingerprint);
        }
        Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": purged own "
                + (legacy ? "legacy" : "OMEMO2") + " device " + deviceId);
        mXmppConnectionService.updateAccountUi();
        return true;
    }

    private static Set<Integer> withoutDevice(final Set<Integer> ids, final int deviceId) {
        final Set<Integer> remaining = ids == null ? new HashSet<>() : new HashSet<>(ids);
        remaining.remove(deviceId);
        return remaining;
    }

    /**
     * Whether any remaining OMEMO2 session of ours still pins {@code fingerprint}.
     * Guards the deletion of the shared identity row in
     * {@link #purgeOwnDevice(int, String, boolean)}. The legacy OMEMO1 crypto
     * backend has been removed, so there is no separate legacy session store left
     * to check any more.
     */
    private boolean stillReferencedByAnySession(final String fingerprint) {
        for (final XmppAxolotlSession session : findOwnSessions()) {
            if (fingerprint.equals(session.getFingerprint())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Marks the key {@code name} holds under {@code fingerprint} untrusted.
     *
     * <p>Null-safe: {@code getFingerprintStatus} genuinely returns null for a key with no row
     * — the row may have been removed by {@link #purgeOwnDevice} or by a manual identity
     * re-exchange while the list on screen went stale — and dereferencing it crashed the very
     * screen the user was using to revoke trust.
     *
     * @return false when there was nothing to distrust.
     */
    public boolean distrustFingerprint(final String name, final String fingerprint) {
        if (name == null || fingerprint == null) {
            return false;
        }
        final String fp = fingerprint.replaceAll("\\s", "");
        final FingerprintStatus fingerprintStatus = axolotlStore.getFingerprintStatus(name, fp);
        if (fingerprintStatus == null) {
            Log.d(Config.LOGTAG, getLogprefix(account)
                    + "nothing to distrust: no identity row for " + fp + " of " + name);
            return false;
        }
        return axolotlStore.setFingerprintStatus(name, fp, fingerprintStatus.toUntrusted());
    }

    private void publishOwnDeviceIdIfNeeded() {
        if (pepBroken) {
            Log.d(Config.LOGTAG, getLogprefix(account) + "publishOwnDeviceIdIfNeeded called, but PEP is broken. Ignoring... ");
            return;
        }
        Iq packet = mXmppConnectionService.getIqGenerator().retrieveDeviceIds(account.getJid().asBareJid());
        mXmppConnectionService.sendIqPacket(account, packet, response -> {
            if (response.getType() == Iq.Type.TIMEOUT) {
                Log.d(Config.LOGTAG, getLogprefix(account) + "Timeout received while retrieving own Device Ids.");
            } else {
                //TODO consider calling registerDevices only after item-not-found to account for broken PEPs
                final Element item = IqParser.getItem(response);
                final Set<Integer> deviceIds = IqParser.deviceIds(item);
                Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": retrieved own device list: " + deviceIds);
                registerDevices(account.getJid().asBareJid(), deviceIds);
            }

        });
    }

    private Set<Integer> getExpiredDevices() {
        Set<Integer> devices = new HashSet<>();
        for (XmppAxolotlSession session : findOwnSessions()) {
            if (session.getTrust().isActive()) {
                long diff = System.currentTimeMillis() - session.getTrust().getLastActivation();
                if (diff > mXmppConnectionService.getOmemoAutoExpiry()) {
                    long lastMessageDiff = System.currentTimeMillis() - mXmppConnectionService.databaseBackend.getLastTimeFingerprintUsed(account, session.getFingerprint());
                    long hours = Math.round(lastMessageDiff / (1000 * 60.0 * 60.0));
                    if (lastMessageDiff > mXmppConnectionService.getOmemoAutoExpiry()) {
                        devices.add(session.getRemoteAddress().getDeviceId());
                        session.setTrust(session.getTrust().toInactive());
                        Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": added own device " + session.getFingerprint() + " to list of expired devices. Last message received " + hours + " hours ago");
                    } else {
                        Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": own device " + session.getFingerprint() + " was active " + hours + " hours ago");
                    }
                } //TODO print last activation diff
            }
        }
        return devices;
    }

    private void publishOwnDeviceId(final Set<Integer> deviceIds) {
        final Set<Integer> deviceIdsCopy = new HashSet<>(deviceIds);
        Log.d(Config.LOGTAG, AxolotlService.getLogprefix(account) + "publishing own device ids");
        if (deviceIdsCopy.isEmpty()) {
            if (numPublishTriesOnEmptyPep >= publishTriesThreshold) {
                Log.w(Config.LOGTAG, getLogprefix(account) + "Own device publish attempt threshold exceeded, aborting...");
                pepBroken = true;
                return;
            } else {
                numPublishTriesOnEmptyPep++;
                Log.w(Config.LOGTAG, getLogprefix(account) + "Own device list empty, attempting to publish (try " + numPublishTriesOnEmptyPep + ")");
            }
        } else {
            numPublishTriesOnEmptyPep = 0;
        }
        deviceIdsCopy.add(getOwnDeviceId());
        publishDeviceIdsAndRefineAccessModel(deviceIdsCopy);
    }

    private void publishDeviceIdsAndRefineAccessModel(Set<Integer> ids) {
        publishDeviceIdsAndRefineAccessModel(ids, true);
    }

    private void publishDeviceIdsAndRefineAccessModel(final Set<Integer> ids, final boolean firstAttempt) {
        final XmppConnection connection = account.getXmppConnection();
        if (connection == null) {
            return;
        }
        final Bundle publishOptions = connection.getFeatures().pepPublishOptions() ? PublishOptions.openAccess() : null;
        final var publish = mXmppConnectionService.getIqGenerator().publishDeviceIds(ids, publishOptions);
        mXmppConnectionService.sendIqPacket(account, publish, response -> {
            final Element error = response.getType() == Iq.Type.ERROR ? response.findChild("error") : null;
            final boolean preConditionNotMet = PublishOptions.preconditionNotMet(response);
            if (firstAttempt && preConditionNotMet) {
                Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": precondition wasn't met for device list. pushing node configuration");
                mXmppConnectionService.pushNodeConfiguration(account, AxolotlService.PEP_DEVICE_LIST, publishOptions, new XmppConnectionService.OnConfigurationPushed() {
                    @Override
                    public void onPushSucceeded() {
                        publishDeviceIdsAndRefineAccessModel(ids, false);
                    }

                    @Override
                    public void onPushFailed() {
                        publishDeviceIdsAndRefineAccessModel(ids, false);
                    }
                });
            } else {
                if (AxolotlService.this.changeAccessMode.compareAndSet(true, false)) {
                    Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": done changing access mode");
                    account.setOption(Account.OPTION_REQUIRES_ACCESS_MODE_CHANGE, false);
                    mXmppConnectionService.databaseBackend.updateAccount(account);
                }
                if (response.getType() == Iq.Type.ERROR) {
                    if (preConditionNotMet) {
                        Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": device list pre condition still not met on second attempt");
                    } else if (error != null) {
                        pepBroken = true;
                        Log.d(Config.LOGTAG, getLogprefix(account) + "Error received while publishing own device id" + response.findChild("error"));
                    }

                }
            }
        });
    }

    public void publishDeviceVerificationAndBundle(final SignedPreKeyRecord signedPreKeyRecord,
                                                   final Set<PreKeyRecord> preKeyRecords,
                                                   final boolean announceAfter,
                                                   final boolean wipe) {
        try {
            IdentityKey axolotlPublicKey = axolotlStore.getIdentityKeyPair().getPublicKey();
            PrivateKey x509PrivateKey = KeyChain.getPrivateKey(mXmppConnectionService, account.getPrivateKeyAlias());
            X509Certificate[] chain = KeyChain.getCertificateChain(mXmppConnectionService, account.getPrivateKeyAlias());
            Signature verifier = Signature.getInstance("sha256WithRSA");
            verifier.initSign(x509PrivateKey, SECURE_RANDOM);
            verifier.update(axolotlPublicKey.serialize());
            byte[] signature = verifier.sign();
            final Iq packet = mXmppConnectionService.getIqGenerator().publishVerification(signature, chain, getOwnDeviceId());
            Log.d(Config.LOGTAG, AxolotlService.getLogprefix(account) + ": publish verification for device " + getOwnDeviceId());
            mXmppConnectionService.sendIqPacket(account, packet, response -> {
                String node = AxolotlService.PEP_VERIFICATION + ":" + getOwnDeviceId();
                mXmppConnectionService.pushNodeConfiguration(account, node, PublishOptions.openAccess(), new XmppConnectionService.OnConfigurationPushed() {
                    @Override
                    public void onPushSucceeded() {
                        Log.d(Config.LOGTAG, getLogprefix(account) + "configured verification node to be world readable");
                        publishDeviceBundle(signedPreKeyRecord, preKeyRecords, announceAfter, wipe);
                    }

                    @Override
                    public void onPushFailed() {
                        Log.d(Config.LOGTAG, getLogprefix(account) + "unable to set access model on verification node");
                        publishDeviceBundle(signedPreKeyRecord, preKeyRecords, announceAfter, wipe);
                    }
                });
            });
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Whether the published EC signed prekey has aged past its rotation window.
     * Same 30-day schedule as the signed ("last-resort") KEM prekey, so both
     * halves of the PQXDH handshake get comparable forward secrecy.
     */
    private static boolean isSignedPreKeyDueForRotation(final SignedPreKeyRecord record) {
        final long age = System.currentTimeMillis() - record.getTimestamp();
        return age < 0 || age > SIGNED_PREKEY_ROTATION_MS;
    }

    private static SignedPreKeyRecord generateSignedPreKey(final IdentityKeyPair identityKeyPair, final int id) throws InvalidKeyException {
        final ECKeyPair spkPair = ECKeyPair.generate();
        final byte[] sig = identityKeyPair.getPrivateKey().calculateSignature(spkPair.getPublicKey().serialize());
        return new SignedPreKeyRecord(id, System.currentTimeMillis(), spkPair, sig);
    }

    public void publishBundlesIfNeeded(final boolean announce, final boolean wipe) {
        if (pepBroken) {
            Log.d(Config.LOGTAG, getLogprefix(account) + "publishBundlesIfNeeded called, but PEP is broken. Ignoring... ");
            return;
        }
        final XmppConnection connection = account.getXmppConnection();
        if (connection == null) {
            return;
        }

        if (connection.getFeatures().pepPublishOptions()) {
            this.changeAccessMode.set(account.isOptionSet(Account.OPTION_REQUIRES_ACCESS_MODE_CHANGE));
        } else {
            if (account.setOption(Account.OPTION_REQUIRES_ACCESS_MODE_CHANGE, true)) {
                Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": server doesn’t support publish-options. setting for later access mode change");
                mXmppConnectionService.databaseBackend.updateAccount(account);
            }
        }
        if (this.changeAccessMode.get()) {
            Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": server gained publish-options capabilities. changing access model");
        }
        // Reconcile against the OMEMO2 bundle node — the node this method actually
        // publishes to. (This used to fetch the legacy v0.3 node, which no longer
        // carries the primary stack's keys after the identity separation; the
        // comparison could then never match, so every connect regenerated and
        // republished the full bundle — 100 EC + 101 KEM keys each time.)
        final Iq packet = mXmppConnectionService.getIqGenerator().retrieveOmemo2BundlesForDevice(account.getJid().asBareJid(), getOwnDeviceId());
        mXmppConnectionService.sendIqPacket(account, packet, response -> {

            if (response.getType() == Iq.Type.TIMEOUT) {
                return; //ignore timeout. do nothing
            }

            if (response.getType() == Iq.Type.ERROR) {
                Element error = response.findChild("error");
                if (error == null || !error.hasChild("item-not-found")) {
                    pepBroken = true;
                    Log.w(Config.LOGTAG, AxolotlService.getLogprefix(account) + "request for device bundles came back with something other than item-not-found" + response);
                    return;
                }
            }

            PreKeyBundle bundle = IqParser.omemo2Bundle(response);
            final Map<Integer, ECPublicKey> keys = IqParser.omemo2PreKeyPublics(response);
            boolean flush = false;
            if (bundle == null) {
                Log.w(Config.LOGTAG, AxolotlService.getLogprefix(account) + "No valid OMEMO2 bundle published yet:" + response);
                flush = true;
            }
            try {
                boolean changed = false;
                // Validate IdentityKey
                IdentityKeyPair identityKeyPair = axolotlStore.getIdentityKeyPair();
                if (flush || !identityKeyPair.getPublicKey().equals(bundle.getIdentityKey())) {
                    Log.i(Config.LOGTAG, AxolotlService.getLogprefix(account) + "Adding own IdentityKey " + identityKeyPair.getPublicKey() + " to PEP.");
                    changed = true;
                }

                // Validate signedPreKeyRecord + ID
                SignedPreKeyRecord signedPreKeyRecord;
                int numSignedPreKeys = axolotlStore.getSignedPreKeysCount();
                try {
                    if (flush) throw new InvalidKeyIdException("bundle invalid, regenerating");
                    signedPreKeyRecord = axolotlStore.loadSignedPreKey(bundle.getSignedPreKeyId());
                    if (!bundle.getSignedPreKey().equals(signedPreKeyRecord.getKeyPair().getPublicKey())
                            || !Arrays.equals(bundle.getSignedPreKeySignature(), signedPreKeyRecord.getSignature())) {
                        Log.i(Config.LOGTAG, AxolotlService.getLogprefix(account) + "Adding new signedPreKey with ID " + (numSignedPreKeys + 1) + " to PEP.");
                        signedPreKeyRecord = generateSignedPreKey(identityKeyPair, numSignedPreKeys + 1);
                        axolotlStore.storeSignedPreKey(signedPreKeyRecord.getId(), signedPreKeyRecord);
                        changed = true;
                    } else if (isSignedPreKeyDueForRotation(signedPreKeyRecord)) {
                        // Age-based rotation, matching what the KEM signed prekey
                        // already does: a signed prekey that is published for years
                        // keeps widening the window in which its compromise unlocks
                        // every session started against it. The superseded private
                        // key stays in the store (nothing deletes signed prekeys),
                        // so handshakes already in flight against the old bundle
                        // still complete.
                        Log.i(Config.LOGTAG, AxolotlService.getLogprefix(account)
                                + "signed prekey " + signedPreKeyRecord.getId()
                                + " is due for rotation — publishing ID " + (numSignedPreKeys + 1));
                        signedPreKeyRecord = generateSignedPreKey(identityKeyPair, numSignedPreKeys + 1);
                        axolotlStore.storeSignedPreKey(signedPreKeyRecord.getId(), signedPreKeyRecord);
                        changed = true;
                    }
                } catch (InvalidKeyIdException e) {
                    Log.i(Config.LOGTAG, AxolotlService.getLogprefix(account) + "Adding new signedPreKey with ID " + (numSignedPreKeys + 1) + " to PEP.");
                    signedPreKeyRecord = generateSignedPreKey(identityKeyPair, numSignedPreKeys + 1);
                    axolotlStore.storeSignedPreKey(signedPreKeyRecord.getId(), signedPreKeyRecord);
                    changed = true;
                }

                // Validate PreKeys: keep published one-time EC prekeys we still
                // hold, generate only the shortfall (consumed keys were deleted
                // locally when their PreKeySignalMessage arrived).
                Set<PreKeyRecord> preKeyRecords = new HashSet<>();
                for (Integer id : keys.keySet()) {
                    try {
                        PreKeyRecord preKeyRecord = axolotlStore.loadPreKey(id);
                        if (preKeyRecord.getKeyPair().getPublicKey().equals(keys.get(id))) {
                            preKeyRecords.add(preKeyRecord);
                        }
                    } catch (InvalidKeyIdException ignored) {
                    }
                }
                int newKeys = NUM_KEYS_TO_PUBLISH - preKeyRecords.size();
                if (newKeys > 0) {
                    final int startId = axolotlStore.getCurrentPreKeyId() + 1;
                    final List<PreKeyRecord> newRecords = new ArrayList<>();
                    for (int i = 0; i < newKeys; i++) {
                        newRecords.add(new PreKeyRecord(startId + i, ECKeyPair.generate()));
                    }
                    preKeyRecords.addAll(newRecords);
                    for (PreKeyRecord record : newRecords) {
                        axolotlStore.storePreKey(record.getId(), record);
                    }
                    changed = true;
                    Log.i(Config.LOGTAG, AxolotlService.getLogprefix(account) + "Adding " + newKeys + " new preKeys to PEP.");
                }

                // Validate KEM material. The published kem-spk must be a
                // last-resort key we still hold and that has not aged past the
                // rotation window; and at least MIN_KEM_PREKEYS of the published
                // one-time KEM prekeys must still be unconsumed locally.
                mXmppConnectionService.databaseBackend.ensureKyberTablesExist();
                // Drop any retained Round-3 Kyber keys before deciding whether a republish is
                // due; otherwise their presence makes the checks below conclude that nothing
                // needs doing (see purgeNonMlKemKyberPreKeys).
                mXmppConnectionService.databaseBackend.purgeNonMlKemKyberPreKeys(account);
                final KyberPreKeyRecord currentKemSpk = getCurrentKemSignedPreKey();
                if (currentKemSpk == null) {
                    Log.i(Config.LOGTAG, AxolotlService.getLogprefix(account)
                            + "KEM signed prekey missing or due for rotation — republishing OMEMO2 bundle.");
                    changed = true;
                } else if (bundle == null
                        || bundle.getKyberPreKeySignature() == null
                        || bundle.getKyberPreKeySignature().length == 0
                        || bundle.getKyberPreKeyId() != currentKemSpk.getId()) {
                    // placeholder (no kem-spk published) or a stale kem-spk id
                    changed = true;
                }
                int liveKemPreKeys = 0;
                for (final IqParser.KemBundleKey kem : IqParser.omemo2KemPreKeys(response)) {
                    if (axolotlStore.containsKyberPreKey(kem.id)
                            && !mXmppConnectionService.databaseBackend.isKyberPreKeyLastResort(account, kem.id)) {
                        liveKemPreKeys++;
                    }
                }
                if (liveKemPreKeys < MIN_KEM_PREKEYS) {
                    Log.i(Config.LOGTAG, AxolotlService.getLogprefix(account)
                            + "published one-time KEM prekey stock low (" + liveKemPreKeys
                            + ") — republishing OMEMO2 bundle.");
                    changed = true;
                }

                // Post-migration safeguard: no local KEM prekeys at all, or a
                // server-side check found the OMEMO2 node missing/empty (see
                // verifyOmemo2BundlePublished) — force a publish.
                if (axolotlStore.getKyberOneTimePreKeyCount() == 0 || forceOmemo2BundleRepublish) {
                    Log.i(Config.LOGTAG, AxolotlService.getLogprefix(account)
                            + "OMEMO2 bundle needs (re)publishing (no local KEM prekeys"
                            + " or server node missing) — forcing OMEMO2 bundle publish.");
                    changed = true;
                    forceOmemo2BundleRepublish = false;
                }

                if (changed || changeAccessMode.get()) {
                    if (account.getPrivateKeyAlias() != null && Config.X509_VERIFICATION) {
                        mXmppConnectionService.publishDisplayName(account);
                        publishDeviceVerificationAndBundle(signedPreKeyRecord, preKeyRecords, announce, wipe);
                    } else {
                        publishDeviceBundle(signedPreKeyRecord, preKeyRecords, announce, wipe);
                    }
                } else {
                    Log.d(Config.LOGTAG, getLogprefix(account) + "Bundle " + getOwnDeviceId() + " in PEP was current");
                    if (wipe) {
                        wipeOtherPepDevices();
                    } else if (announce) {
                        Log.d(Config.LOGTAG, getLogprefix(account) + "Announcing device " + getOwnDeviceId());
                        publishOwnDeviceIdIfNeeded();
                    }
                }
            } catch (InvalidKeyException e) {
                Log.e(Config.LOGTAG, AxolotlService.getLogprefix(account) + "Failed to publish bundle " + getOwnDeviceId() + ", reason: " + e.getMessage());
            }
        });
    }

    private void publishDeviceBundle(SignedPreKeyRecord signedPreKeyRecord,
                                     Set<PreKeyRecord> preKeyRecords,
                                     final boolean announceAfter,
                                     final boolean wipe) {
        publishDeviceBundle(signedPreKeyRecord, preKeyRecords, announceAfter, wipe, true);
    }

    private void publishDeviceBundle(final SignedPreKeyRecord signedPreKeyRecord,
                                     final Set<PreKeyRecord> preKeyRecords,
                                     final boolean announceAfter,
                                     final boolean wipe,
                                     final boolean firstAttempt) {
        // The legacy OMEMO1 crypto backend has been removed: we only ever
        // publish the PQ OMEMO2 bundle now.
        Log.d(Config.LOGTAG, AxolotlService.getLogprefix(account)
                + ": Publishing OMEMO2 bundle for " + getOwnDeviceId());
        publishOmemo2BundlesIfNeeded(signedPreKeyRecord, preKeyRecords);
        if (wipe) {
            wipeOtherPepDevices();
        } else if (announceAfter) {
            publishOwnDeviceIdIfNeeded();
        }
    }

    /**
     * The legacy OMEMO1 crypto backend has been removed, so there is no legacy
     * bundle left to publish; this is now a no-op. Kept (rather than deleted)
     * because it is still called from settings UI code that has not yet been
     * cleaned up of legacy-OMEMO preferences (see Task 7).
     */
    public void publishLegacyBundleNow() {
        Log.d(Config.LOGTAG, getLogprefix(account)
                + "legacy OMEMO1 support has been removed — nothing to publish");
    }

    public void deleteOmemoIdentity() {
        mXmppConnectionService.deletePepNode(
                account, AxolotlService.PEP_BUNDLES + ":" + getOwnDeviceId());
        final Set<Integer> ownDeviceIds = getOwnDeviceIds();
        publishDeviceIdsAndRefineAccessModel(
                ownDeviceIds == null ? Collections.emptySet() : ownDeviceIds);
    }

    public List<Jid> getCryptoTargets(Conversation conversation) {
        final List<Jid> jids;
        if (conversation.getMode() == Conversation.MODE_SINGLE) {
            jids = new ArrayList<>();
            jids.add(conversation.getJid().asBareJid());
        } else {
            jids = conversation.getMucOptions().getMembers(false);
        }
        return jids;
    }

    /**
     * The trust the user has recorded for the key {@code name} (a bare JID) holds under
     * {@code fingerprint}, substituting UNDECIDED when there is no row.
     *
     * <p>{@code name} is REQUIRED. The {@code identities} table is shared by the legacy and
     * the OMEMO2 stack and by every contact on the account, and an identity public key is
     * published in PEP for anyone to copy — so a fingerprint on its own does not identify a
     * trust decision. Asking without the JID used to let a peer republishing someone else's
     * identity key read (and inherit) the trust its real owner had been given.
     */
    public FingerprintStatus getFingerprintTrust(final String name, final String fingerprint) {
        final FingerprintStatus status = axolotlStore.getFingerprintStatus(name, fingerprint);
        return status != null ? status : FingerprintStatus.createActiveUndecided();
    }

    /**
     * The stored trust for {@code fingerprint}, or {@code null} when no identity row exists for
     * it yet.
     *
     * <p>Deliberately NOT the same as {@link #getFingerprintTrust(String)}, which substitutes
     * UNDECIDED for a missing row — most callers want that default, but anything that has to
     * distinguish "the user has not decided yet" from "we have never seen this key" needs the
     * raw answer. {@code verifyFingerprints} does: a scanned fingerprint we know nothing about
     * has to be recorded with {@link #preVerifyFingerprint}, because
     * {@code setFingerprintTrust} is an UPDATE that would match no rows and silently drop the
     * verification. Do not fold these two methods back together.
     */
    @Nullable
    public FingerprintStatus getFingerprintStatusOrNull(final String name, final String fingerprint) {
        return axolotlStore.getFingerprintStatus(name, fingerprint);
    }

    public X509Certificate getFingerprintCertificate(String name, String fingerprint) {
        return axolotlStore.getFingerprintCertificate(name, fingerprint);
    }

    /**
     * Records a trust decision for the key {@code name} holds under {@code fingerprint}.
     *
     * @return false when no such row existed, so nothing was recorded. Callers that are
     *     acting on an explicit user decision should surface that rather than reporting
     *     success — an UPDATE matching no rows is exactly how verification of a
     *     never-before-seen key came to be a silent no-op.
     */
    public boolean setFingerprintTrust(
            final String name, final String fingerprint, final FingerprintStatus status) {
        final boolean recorded = axolotlStore.setFingerprintStatus(name, fingerprint, status);
        if (!recorded) {
            Log.w(Config.LOGTAG, getLogprefix(account) + "trust for " + fingerprint
                    + " of " + name + " was not recorded — no matching identity row");
        }
        // TODO we decided to call this after a fingerprint gets toggled to update the 'your contact
        //  is using unverified devices text'; however this means the entire screen gets redrawn
        //  after a toggle which might be annoying or cause other weird UI glitches
        mXmppConnectionService.updateAccountUi();
        return recorded;
    }

    private ListenableFuture<XmppAxolotlSession> verifySessionWithPEP(final XmppAxolotlSession session) {
        Log.d(Config.LOGTAG, "trying to verify fresh session (" + session.getRemoteAddress().getName() + ") with pep");
        final SignalProtocolAddress address = session.getRemoteAddress();
        final IdentityKey identityKey = session.getIdentityKey();
        final Jid jid;
        try {
            jid = Jid.of(address.getName());
        } catch (final IllegalArgumentException e) {
            fetchStatusMap.put(address, FetchStatus.SUCCESS);
            finishBuildingSessionsFromPEP(address);
            return Futures.immediateFuture(session);
        }
        final SettableFuture<XmppAxolotlSession> future = SettableFuture.create();
        final Iq packet = mXmppConnectionService.getIqGenerator().retrieveVerificationForDevice(jid, address.getDeviceId());
        mXmppConnectionService.sendIqPacket(account, packet, response -> {
            Pair<X509Certificate[], byte[]> verification = IqParser.verification(response);
            if (verification != null) {
                try {
                    Signature verifier = Signature.getInstance("sha256WithRSA");
                    verifier.initVerify(verification.first[0]);
                    verifier.update(identityKey.serialize());
                    if (verifier.verify(verification.second)) {
                        try {
                            mXmppConnectionService.getMemorizingTrustManager().getNonInteractive().checkClientTrusted(verification.first, "RSA");
                            String fingerprint = session.getFingerprint();
                            Log.d(Config.LOGTAG, "verified session with x.509 signature. fingerprint was: " + fingerprint);
                            setFingerprintTrust(address.getName(), fingerprint,
                                    FingerprintStatus.createActiveVerified(true));
                            axolotlStore.setFingerprintCertificate(
                                    address.getName(), fingerprint, verification.first[0]);
                            fetchStatusMap.put(address, FetchStatus.SUCCESS_VERIFIED);
                            Bundle information = CryptoHelper.extractCertificateInformation(verification.first[0]);
                            try {
                                final String cn = information.getString("subject_cn");
                                final Jid jid1 = Jid.of(address.getName());
                                Log.d(Config.LOGTAG, "setting common name for " + jid1 + " to " + cn);
                                account.getRoster().getContact(jid1).setCommonName(cn);
                            } catch (final IllegalArgumentException ignored) {
                                //ignored
                            }
                            finishBuildingSessionsFromPEP(address);
                            future.set(session);
                            return;
                        } catch (Exception e) {
                            Log.d(Config.LOGTAG, "could not verify certificate");
                        }
                    }
                } catch (Exception e) {
                    Log.d(Config.LOGTAG, "error during verification " + e.getMessage());
                }
            } else {
                Log.d(Config.LOGTAG, "no verification found");
            }
            fetchStatusMap.put(address, FetchStatus.SUCCESS);
            finishBuildingSessionsFromPEP(address);
            future.set(session);
        });
        return future;
    }

    private void finishBuildingSessionsFromPEP(final SignalProtocolAddress address) {
        SignalProtocolAddress ownAddress = new SignalProtocolAddress(account.getJid().asBareJid().toString(), 1);
        Map<Integer, FetchStatus> own = fetchStatusMap.getAll(ownAddress.getName());
        Map<Integer, FetchStatus> remote = fetchStatusMap.getAll(address.getName());
        if (!own.containsValue(FetchStatus.PENDING) && !remote.containsValue(FetchStatus.PENDING)) {
            FetchStatus report = null;
            if (own.containsValue(FetchStatus.SUCCESS) || remote.containsValue(FetchStatus.SUCCESS)) {
                report = FetchStatus.SUCCESS;
            } else if (own.containsValue(FetchStatus.SUCCESS_VERIFIED) || remote.containsValue(FetchStatus.SUCCESS_VERIFIED)) {
                report = FetchStatus.SUCCESS_VERIFIED;
            } else if (own.containsValue(FetchStatus.SUCCESS_TRUSTED) || remote.containsValue(FetchStatus.SUCCESS_TRUSTED)) {
                report = FetchStatus.SUCCESS_TRUSTED;
            } else if (own.containsValue(FetchStatus.ERROR) || remote.containsValue(FetchStatus.ERROR)) {
                report = FetchStatus.ERROR;
            }
            mXmppConnectionService.keyStatusUpdated(report);
        }
        if (Config.REMOVE_BROKEN_DEVICES) {
            Set<Integer> ownDeviceIds = new HashSet<>(getOwnDeviceIds());
            boolean publish = false;
            for (Map.Entry<Integer, FetchStatus> entry : own.entrySet()) {
                int id = entry.getKey();
                if (entry.getValue() == FetchStatus.ERROR && PREVIOUSLY_REMOVED_FROM_ANNOUNCEMENT.add(id) && ownDeviceIds.remove(id)) {
                    publish = true;
                    Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": error fetching own device with id " + id + ". removing from announcement");
                }
            }
            if (publish) {
                publishOwnDeviceId(ownDeviceIds);
            }
        }
    }

    public boolean hasEmptyDeviceList(Jid jid) {
        final Set<Integer> ids = getDeviceIds(jid);
        return !hasAny(jid) && (ids == null || ids.isEmpty());
    }

    /**
     * Stack-specific variant used by the session-creation paths: a JID has an
     * "empty device list" for the given stack when that stack's map holds no
     * IDs for it (OMEMO2 additionally requires no live session in the cache).
     */
    private boolean hasEmptyDeviceList(final Jid jid, final boolean omemo2Stack) {
        final Set<Integer> ids = getDeviceIdsForStack(jid, omemo2Stack);
        final boolean noIds = ids == null || ids.isEmpty();
        return omemo2Stack ? (!hasAny(jid) && noIds) : noIds;
    }

    public void fetchDeviceIds(final Jid jid) {
        fetchDeviceIds(jid, null);
    }

    private void fetchDeviceIds(final Jid jid, OnDeviceIdsFetched callback) {
        final Iq packet;
        synchronized (this.fetchDeviceIdsMap) {
            List<OnDeviceIdsFetched> callbacks = this.fetchDeviceIdsMap.get(jid);
            if (callbacks != null) {
                if (callback != null) {
                    callbacks.add(callback);
                }
                Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": fetching device ids for " + jid + " already running. adding callback");
                packet = null;
            } else {
                callbacks = new ArrayList<>();
                if (callback != null) {
                    callbacks.add(callback);
                }
                this.fetchDeviceIdsMap.put(jid, callbacks);
                Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": fetching device ids for " + jid);
                packet = mXmppConnectionService.getIqGenerator().retrieveDeviceIds(jid);
            }
        }
        if (packet != null) {
            sendFetchIq(packet, response -> {
                if (response.getType() == Iq.Type.RESULT) {
                    final Element item = IqParser.getItem(response);
                    final Set<Integer> deviceIds = IqParser.deviceIds(item);
                    // An EMPTY list means the contact publishes no legacy OMEMO
                    // devices (they may be OMEMO2-only, or have removed all their
                    // devices). Record that like a failed fetch — same as
                    // fetchOmemo2DeviceIds does — so the legacy trust guard fails
                    // closed with a toast instead of reopening TrustKeysActivity
                    // on every send, forever, with nothing to show. Recovery is
                    // automatic: registerDevices() drops the status again as soon
                    // as a non-empty list arrives.
                    fetchDeviceListStatus.put(jid, !deviceIds.isEmpty());
                    registerDevices(jid, deviceIds);
                    final List<OnDeviceIdsFetched> callbacks;
                    synchronized (fetchDeviceIdsMap) {
                        callbacks = fetchDeviceIdsMap.remove(jid);
                    }
                    if (callbacks != null) {
                        for (OnDeviceIdsFetched c : callbacks) {
                            c.fetched(jid, deviceIds);
                        }
                    }
                } else {
                    if (response.getType() == Iq.Type.TIMEOUT) {
                        // Unanswered: leave the outcome unknown so the next
                        // attempt retries rather than recording a permanent
                        // "this contact has no legacy devices".
                        fetchDeviceListStatus.remove(jid);
                    } else {
                        fetchDeviceListStatus.put(jid, false);
                    }
                    final List<OnDeviceIdsFetched> callbacks;
                    synchronized (fetchDeviceIdsMap) {
                        callbacks = fetchDeviceIdsMap.remove(jid);
                    }
                    if (callbacks != null) {
                        for (OnDeviceIdsFetched c : callbacks) {
                            c.fetched(jid, null);
                        }
                    }
                    // The fetch is no longer pending; tell the UI so a trust
                    // screen waiting on it leaves the "Fetching keys…" state
                    // instead of sitting there with a disabled button.
                    mXmppConnectionService.keyStatusUpdated(null);
                }
            });
        }
    }

    private void fetchDeviceIds(List<Jid> jids, final OnMultipleDeviceIdFetched callback) {
        final ArrayList<Jid> unfinishedJids = new ArrayList<>(jids);
        synchronized (unfinishedJids) {
            // Copy: the callback may run synchronously (no connection) and removes
            // from unfinishedJids while we are iterating it.
            for (Jid jid : new ArrayList<>(unfinishedJids)) {
                fetchDeviceIds(jid, (j, deviceIds) -> {
                    synchronized (unfinishedJids) {
                        unfinishedJids.remove(j);
                        if (unfinishedJids.size() == 0 && callback != null) {
                            callback.fetched();
                        }
                    }
                });
            }
        }
    }

    private ListenableFuture<XmppAxolotlSession> buildSessionFromPEP(final SignalProtocolAddress address) {
        return buildSessionFromPEP(address, null);
    }

    private ListenableFuture<XmppAxolotlSession> buildSessionFromPEP(final SignalProtocolAddress address, OnSessionBuildFromPep callback) {
        // Legacy XEP-0384 v0.3 session build. PQXDH-capable peers go through
        // buildSessionFromOmemo2PEP instead. The legacy OMEMO1 crypto backend
        // has been removed, so a legacy session can never be built any more —
        // this always fails closed.
        final SettableFuture<XmppAxolotlSession> sessionSettableFuture = SettableFuture.create();
        Log.w(Config.LOGTAG, getLogprefix(account)
                + "legacy OMEMO1 support has been removed — cannot build session for " + address);
        fetchStatusMap.put(address, FetchStatus.ERROR);
        finishBuildingSessionsFromPEP(address);
        if (callback != null) {
            callback.onSessionBuildFailed();
        }
        sessionSettableFuture.setException(new CryptoFailedException(
                "legacy OMEMO1 support has been removed"));
        return sessionSettableFuture;
    }

    private void buildSessionFromOmemo2PEP(final SignalProtocolAddress address,
            final OnSessionBuildFromPep callback,
            final SettableFuture<XmppAxolotlSession> future) {
        final Jid jid = Jid.of(address.getName());
        Log.d(Config.LOGTAG, getLogprefix(account) + "Building session from OMEMO2 bundle for " + address);
        final Iq omemo2Packet = mXmppConnectionService.getIqGenerator().retrieveOmemo2BundlesForDevice(jid, address.getDeviceId());
        sendFetchIq(omemo2Packet, response -> {
            if (response.getType() == Iq.Type.RESULT) {
                final Map<Integer, ECPublicKey> preKeyPublics = IqParser.omemo2PreKeyPublics(response);
                final List<IqParser.KemBundleKey> kemPreKeys = IqParser.omemo2KemPreKeys(response);
                final PreKeyBundle bundle = IqParser.omemo2Bundle(response);
                // The peer's one-time EC prekeys may be exhausted. PQXDH/X3DH
                // permits omitting the one-time EC prekey (the DH4 term), but
                // doing so weakens EC forward secrecy for the handshake step
                // (the post-quantum KEM contribution and SPQR are unaffected).
                // This is gated behind a preference that is OFF by default, so by
                // default we fail closed rather than silently reduce FS.
                final boolean allowNoOneTimePrekey = mXmppConnectionService.getAppSettings()
                        .isOmemo2SessionWithoutOnetimePrekeyAllowed();
                if (bundle != null && (!preKeyPublics.isEmpty() || allowNoOneTimePrekey)) {
                    final int chosenPkId;
                    final ECPublicKey chosenPk;
                    if (!preKeyPublics.isEmpty()) {
                        final List<Integer> pkIds = new ArrayList<>(preKeyPublics.keySet());
                        chosenPkId = pkIds.get(SECURE_RANDOM.nextInt(pkIds.size()));
                        chosenPk = preKeyPublics.get(chosenPkId);
                    } else {
                        // No one-time EC prekey available; the user has opted into
                        // the signed-prekey-only fallback. libsignal treats
                        // preKeyId == -1 / a null public key as "no one-time prekey".
                        Log.w(Config.LOGTAG, getLogprefix(account)
                                + "peer " + address + " has no one-time EC prekeys left; "
                                + "building OMEMO2 session without a one-time prekey (enabled by preference)");
                        chosenPkId = -1;
                        chosenPk = null;
                    }
                    final int kemPreKeyId;
                    final org.signal.libsignal.protocol.kem.KEMPublicKey kemPreKeyPublic;
                    final byte[] kemPreKeySig;
                    if (!kemPreKeys.isEmpty()) {
                        // Prefer a one-time KEM prekey for forward secrecy
                        final IqParser.KemBundleKey chosenKem = kemPreKeys.get(SECURE_RANDOM.nextInt(kemPreKeys.size()));
                        kemPreKeyId = chosenKem.id;
                        kemPreKeyPublic = chosenKem.publicKey;
                        kemPreKeySig = chosenKem.signature;
                    } else {
                        // Fall back to the signed KEM prekey (last-resort).
                        // If the peer published no <kem-spk> either, the bundle will have a
                        // placeholder with an invalid signature and process() will reject it.
                        kemPreKeyId = bundle.getKyberPreKeyId();
                        kemPreKeyPublic = bundle.getKyberPreKey();
                        kemPreKeySig = bundle.getKyberPreKeySignature();
                    }
                    final PreKeyBundle plainPreKeyBundle = new PreKeyBundle(0, address.getDeviceId(),
                            chosenPkId, chosenPk,
                            bundle.getSignedPreKeyId(), bundle.getSignedPreKey(),
                            bundle.getSignedPreKeySignature(), bundle.getIdentityKey(),
                            kemPreKeyId, kemPreKeyPublic, kemPreKeySig);
                    // monocles PQ-OMEMO2 hybrid identity is MANDATORY: a bundle with
                    // no post-quantum identity, or whose pinned pq_ik changed, is
                    // refused — we never downgrade a post-quantum conversation to a
                    // classical-only one. The ML-DSA-87 signature itself is verified
                    // inside process() (it binds ik+pq_ik+spk); here we additionally
                    // pin pq_ik to the peer's classical identity (TOFU) so it cannot
                    // be silently swapped on a later bundle.
                    final IqParser.PqIdentity peerPq = IqParser.omemo2PqIdentity(response);
                    final String ikFingerprint = CryptoHelper.bytesToHex(
                            bundle.getIdentityKey().getPublicKey().serialize());
                    final PreKeyBundle preKeyBundle;
                    if (peerPq == null) {
                        Log.w(Config.LOGTAG, getLogprefix(account) + "peer " + address
                                + " published no PQ identity (pq-ik/pq-sig) — refusing OMEMO2 session (never downgrade)");
                        preKeyBundle = null;
                    } else {
                        final byte[] pinned = mXmppConnectionService.databaseBackend
                                .getPinnedOmemo2PqIdentity(
                                        account, address.getName(), ikFingerprint);
                        final boolean pqChanged = pinned != null
                                && !Arrays.equals(pinned, peerPq.identityKey);
                        if (pqChanged) {
                            // A PQ-identity change for an already-pinned classical identity is
                            // ALWAYS refused, regardless of classical-fingerprint trust status.
                            // The entire point of the hybrid layer is to stay secure even when
                            // classical (Ed25519) crypto is broken -- an attacker who has
                            // recovered a peer's classical private key can forge a fully valid
                            // replacement bundle with their OWN new PQ identity, so trusting a
                            // stale classical "verified" flag here would silently downgrade the
                            // hybrid identity to classical-only trust. See
                            // docs/superpowers/specs/2026-09-05-pq-omemo2-security-review.md,
                            // Finding 3, for the full attack chain. A genuine re-key (e.g. a
                            // contact reinstalling and losing their identity) needs an explicit,
                            // deliberate re-verification UX -- not a silent policy exception.
                            Log.e(Config.LOGTAG, getLogprefix(account) + "PQ identity for "
                                    + ikFingerprint + " CHANGED — refusing OMEMO2 session (requires"
                                    + " explicit re-verification, never a silent accept)");
                            preKeyBundle = null;
                        } else {
                            // Recompute the KEM binding from the fetched bundle so
                            // process() can verify the v2 transcript: if any ML-KEM
                            // pre-key was substituted (the harvest-and-forge vector),
                            // the digest won't match the ML-DSA-87 signature.
                            final byte[] kemBinding =
                                    computeOmemo2KemBindingFromWire(bundle, kemPreKeys);
                            preKeyBundle = plainPreKeyBundle.withPqIdentity(
                                    peerPq.identityKey, peerPq.signature, kemBinding);
                        }
                    }
                    try {
                        if (preKeyBundle == null) {
                            throw new CryptoFailedException("missing or changed PQ identity for " + address);
                        }
                        final SignalProtocolAddress localAddress = getOwnAxolotlAddress();
                        new SessionBuilder(axolotlStore, address, localAddress).process(preKeyBundle);
                        // process() verified the ML-DSA-87 signature over the bundle
                        // transcript; pin pq_ik to this peer's classical identity
                        // (idempotent — we already rejected a changed pq_ik above).
                        mXmppConnectionService.databaseBackend.pinOmemo2PqIdentity(
                                account, address.getName(), ikFingerprint, peerPq.identityKey);
                        final XmppAxolotlSession session = new XmppAxolotlSession(account, axolotlStore, localAddress, address, bundle.getIdentityKey());
                        sessions.put(address, session);
                        final FingerprintStatus fpStatus = getFingerprintTrust(address.getName(),
                                CryptoHelper.bytesToHex(bundle.getIdentityKey().getPublicKey().serialize()));
                        final FetchStatus fetchStatus;
                        if (fpStatus != null && fpStatus.isVerified()) {
                            fetchStatus = FetchStatus.SUCCESS_VERIFIED;
                        } else if (fpStatus != null && fpStatus.isTrusted()) {
                            fetchStatus = FetchStatus.SUCCESS_TRUSTED;
                        } else {
                            fetchStatus = FetchStatus.SUCCESS;
                        }
                        fetchStatusMap.put(address, fetchStatus);
                        finishBuildingSessionsFromPEP(address);
                        if (callback != null) callback.onSessionBuildSuccessful();
                        future.set(session);
                        return;
                    } catch (UntrustedIdentityException | InvalidKeyException
                             | CryptoFailedException | RuntimeException e) {
                        // RuntimeException: everything here is built from a peer-supplied
                        // bundle, and this lambda runs on the connection thread where an
                        // escaping unchecked exception would take the process down. Fall
                        // through to the FetchStatus.ERROR path below like any other failure.
                        Log.e(Config.LOGTAG, getLogprefix(account) + "OMEMO2 session build error for " + address + ": " + e.getMessage());
                    }
                } else if (bundle != null) {
                    // bundle is valid but the peer has no one-time EC prekeys and
                    // the no-one-time-prekey fallback is disabled by preference:
                    // fail closed to preserve handshake forward secrecy.
                    Log.w(Config.LOGTAG, getLogprefix(account)
                            + "peer " + address + " has no one-time EC prekeys and the "
                            + "signed-prekey-only fallback is disabled — not building session");
                } else {
                    Log.d(Config.LOGTAG, getLogprefix(account) + "OMEMO2 bundle empty or invalid for " + address);
                }
            } else {
                Log.d(Config.LOGTAG, getLogprefix(account) + "OMEMO2 bundle fetch failed for " + address);
            }
            // OMEMO2 failed. An unanswered request (we were not connected, or the
            // server never replied) is transient: record it as TIMEOUT, which the
            // next send retries. Only a real failure response is a permanent ERROR
            // — that one is what makes the trust guard fail closed instead of
            // reopening the trust screen for a device that will never build.
            fetchStatusMap.put(address,
                    response.getType() == Iq.Type.TIMEOUT ? FetchStatus.TIMEOUT : FetchStatus.ERROR);
            finishBuildingSessionsFromPEP(address);
            if (callback != null) callback.onSessionBuildFailed();
            future.setException(new CryptoFailedException("Unable to build session from OMEMO2 bundle for " + address));
        });
    }

    private void removeFromDeviceAnnouncement(Integer id) {
        HashSet<Integer> temp = new HashSet<>(getOwnDeviceIds());
        if (temp.remove(id)) {
            Log.d(Config.LOGTAG, account.getJid().asBareJid() + " remove own device id " + id + " from announcement. devices left:" + temp);
            publishOwnDeviceId(temp);
        }
    }

    public Set<SignalProtocolAddress> findDevicesWithoutSession(final Conversation conversation) {
        return findDevicesWithoutSession(conversation, false);
    }

    public Set<SignalProtocolAddress> findDevicesWithoutSession(final Conversation conversation, final boolean omemo2Stack) {
        // The legacy OMEMO1 crypto backend has been removed, so a legacy
        // session can never already exist any more — every announced legacy
        // device id is treated as "without session" (and will fail to build
        // one; see buildSessionFromPEP).
        Set<SignalProtocolAddress> addresses = new HashSet<>();
        for (Jid jid : getCryptoTargets(conversation)) {
            Log.d(Config.LOGTAG, AxolotlService.getLogprefix(account) + "Finding devices without session for " + jid);
            final Set<Integer> ids = getDeviceIdsForStack(jid, omemo2Stack);
            if (ids != null && !ids.isEmpty()) {
                for (Integer foreignId : ids) {
                    SignalProtocolAddress address = new SignalProtocolAddress(jid.toString(), foreignId);
                    if (sessions.get(address) == null) {
                        IdentityKey identityKey = getRemoteIdentityKeySafe(axolotlStore.loadSession(address));
                        if (identityKey != null) {
                            Log.d(Config.LOGTAG, AxolotlService.getLogprefix(account) + "Already have session for " + address.toString() + ", adding to cache...");
                            XmppAxolotlSession session = new XmppAxolotlSession(account, axolotlStore, getOwnAxolotlAddress(), address, identityKey);
                            sessions.put(address, session);
                        } else {
                            Log.d(Config.LOGTAG, AxolotlService.getLogprefix(account) + "Found device " + jid + ":" + foreignId);
                            if (fetchStatusMap.get(address) != FetchStatus.ERROR) {
                                addresses.add(address);
                            } else {
                                Log.d(Config.LOGTAG, getLogprefix(account) + "skipping over " + address + " because it's broken");
                            }
                        }
                    }
                }
            } else {
                mXmppConnectionService.keyStatusUpdated(FetchStatus.ERROR);
                Log.w(Config.LOGTAG, AxolotlService.getLogprefix(account) + "Have no target devices in PEP!");
            }
        }
        Set<Integer> ownIds = getDeviceIdsForStack(account.getJid().asBareJid(), omemo2Stack);
        for (Integer ownId : (ownIds != null ? ownIds : new HashSet<Integer>())) {
            SignalProtocolAddress address = new SignalProtocolAddress(account.getJid().asBareJid().toString(), ownId);
            if (sessions.get(address) == null) {
                IdentityKey identityKey = getRemoteIdentityKeySafe(axolotlStore.loadSession(address));
                if (identityKey != null) {
                    Log.d(Config.LOGTAG, AxolotlService.getLogprefix(account) + "Already have session for own " + address.toString() + ", adding to cache...");
                    XmppAxolotlSession session = new XmppAxolotlSession(account, axolotlStore, getOwnAxolotlAddress(), address, identityKey);
                    sessions.put(address, session);
                } else {
                    Log.d(Config.LOGTAG, AxolotlService.getLogprefix(account) + "Found device " + account.getJid().asBareJid() + ":" + ownId);
                    if (fetchStatusMap.get(address) != FetchStatus.ERROR) {
                        addresses.add(address);
                    } else {
                        Log.d(Config.LOGTAG, getLogprefix(account) + "skipping over " + address + " because it's broken");
                    }
                }
            }
        }

        return addresses;
    }

    public boolean createSessionsIfNeeded(final Conversation conversation) {
        final List<Jid> jidsWithEmptyDeviceList = getCryptoTargets(conversation);
        for (Iterator<Jid> iterator = jidsWithEmptyDeviceList.iterator(); iterator.hasNext(); ) {
            final Jid jid = iterator.next();
            if (!hasEmptyDeviceList(jid, false)) {
                iterator.remove();
            }
        }
        Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": createSessionsIfNeeded() - jids with empty device list: " + jidsWithEmptyDeviceList);
        if (jidsWithEmptyDeviceList.size() > 0) {
            fetchDeviceIds(jidsWithEmptyDeviceList, () -> createSessionsIfNeededActual(conversation));
            return true;
        } else {
            return createSessionsIfNeededActual(conversation);
        }
    }

    private boolean createSessionsIfNeededActual(final Conversation conversation) {
        Log.i(Config.LOGTAG, AxolotlService.getLogprefix(account) + "Creating axolotl sessions if needed...");
        boolean newSessions = false;
        Set<SignalProtocolAddress> addresses = findDevicesWithoutSession(conversation);
        for (SignalProtocolAddress address : addresses) {
            Log.d(Config.LOGTAG, AxolotlService.getLogprefix(account) + "Processing device: " + address.toString());
            FetchStatus status = fetchStatusMap.get(address);
            if (status == null || status == FetchStatus.TIMEOUT) {
                fetchStatusMap.put(address, FetchStatus.PENDING);
                this.buildSessionFromPEP(address);
                newSessions = true;
            } else if (status == FetchStatus.PENDING) {
                newSessions = true;
            } else {
                Log.d(Config.LOGTAG, AxolotlService.getLogprefix(account) + "Already fetching bundle for " + address.toString());
            }
        }

        return newSessions;
    }

    public boolean createOmemo2SessionsIfNeeded(final Conversation conversation) {
        final List<Jid> jidsWithEmptyDeviceList = getCryptoTargets(conversation);
        for (final Iterator<Jid> iterator = jidsWithEmptyDeviceList.iterator(); iterator.hasNext(); ) {
            if (!hasEmptyDeviceList(iterator.next(), true)) {
                iterator.remove();
            }
        }
        if (!jidsWithEmptyDeviceList.isEmpty()) {
            fetchOmemo2DeviceIds(jidsWithEmptyDeviceList, () -> createOmemo2SessionsIfNeededActual(conversation));
            return true;
        } else {
            return createOmemo2SessionsIfNeededActual(conversation);
        }
    }

    private boolean createOmemo2SessionsIfNeededActual(final Conversation conversation) {
        Log.i(Config.LOGTAG, getLogprefix(account) + "Creating OMEMO2 sessions if needed...");
        boolean newSessions = false;
        for (final SignalProtocolAddress address : findDevicesWithoutSession(conversation, true)) {
            final FetchStatus status = fetchStatusMap.get(address);
            if (status == FetchStatus.PENDING) {
                // already fetching; wait for it to resolve
                newSessions = true;
            } else {
                // Every address returned here lacks a usable OMEMO2 session and is
                // NOT in ERROR (those are filtered out by findDevicesWithoutSession).
                // (Re)fetch it even if a stale SUCCESS* status is recorded, so it
                // always progresses to a real session or to ERROR. Previously a
                // SUCCESS status with no session was skipped here, leaving the
                // device permanently "pending" — which made the Trust screen open
                // and instantly close in a loop.
                fetchStatusMap.put(address, FetchStatus.PENDING);
                buildSessionFromOmemo2PEP(address, null, SettableFuture.create());
                newSessions = true;
            }
        }
        return newSessions;
    }

    private void fetchOmemo2DeviceIds(final List<Jid> jids, final OnMultipleDeviceIdFetched callback) {
        final ArrayList<Jid> unfinished = new ArrayList<>(jids);
        synchronized (unfinished) {
            // Iterate a copy: when the account has no connection object the send
            // below invokes the callback synchronously, and that callback removes
            // from `unfinished` (ConcurrentModificationException on the live list).
            for (final Jid jid : new ArrayList<>(unfinished)) {
                final Iq packet = mXmppConnectionService.getIqGenerator().retrieveOmemo2DeviceIds(jid);
                sendFetchIq(packet, response -> {
                    if (response.getType() == Iq.Type.RESULT) {
                        final Element item = IqParser.getItem(response);
                        final Set<Integer> deviceIds = IqParser.omemo2DeviceIds(item);
                        // Record the fetch outcome so the OMEMO2 trust guard
                        // (ConversationFragment#trustOmemo2KeysIfNeeded) can fail
                        // closed instead of reopening TrustKeysActivity forever.
                        // Previously this method never populated
                        // omemo2FetchDeviceListStatus, so hasErrorFetchingDeviceList()
                        // was permanently false for OMEMO2. An EMPTY result means
                        // the peer published no PQ-OMEMO2 devices (e.g. a
                        // legacy-only client): treat it like an error here so the
                        // send fails closed rather than looping the trust dialog.
                        // Recovery is automatic — once the peer publishes an
                        // OMEMO2 device list, registerOmemo2Devices() clears this
                        // status again (see there).
                        omemo2FetchDeviceListStatus.put(jid, !deviceIds.isEmpty());
                        registerDevices(jid, deviceIds, true);
                    } else if (response.getType() == Iq.Type.TIMEOUT) {
                        // Unanswered (typically: we are not connected). Leave the
                        // outcome unknown so the next attempt retries instead of
                        // recording a permanent "this peer has no keys".
                        omemo2FetchDeviceListStatus.remove(jid);
                        mXmppConnectionService.keyStatusUpdated(null);
                    } else {
                        omemo2FetchDeviceListStatus.put(jid, false);
                        mXmppConnectionService.keyStatusUpdated(null);
                    }
                    synchronized (unfinished) {
                        unfinished.remove(jid);
                        if (unfinished.isEmpty() && callback != null) {
                            callback.fetched();
                        }
                    }
                });
            }
        }
    }

    public boolean trustedSessionVerified(final Conversation conversation) {
        final Set<XmppAxolotlSession> sessions = new HashSet<>();
        sessions.addAll(findSessionsForConversation(conversation));
        sessions.addAll(findOwnSessions());
        boolean verified = false;
        for (XmppAxolotlSession session : sessions) {
            if (session.getTrust().isTrustedAndActive()) {
                if (session.getTrust().getTrust() == FingerprintStatus.Trust.VERIFIED_X509) {
                    verified = true;
                } else {
                    return false;
                }
            }
        }
        return verified;
    }

    /**
     * Whether a key fetch that the given stack is waiting on is still running.
     * Per stack, because the two are independent: a pending legacy bundle fetch
     * must not make the OMEMO2 trust screen sit on "Fetching keys…" (and the
     * other way round).
     */
    public boolean hasPendingKeyFetches(final List<Jid> jids, final int encryption) {
        final boolean omemo2Stack = stackIsOmemo2(encryption);
        if (hasPendingBundleFetch(account.getJid().asBareJid(), omemo2Stack)) {
            return true;
        }
        synchronized (this.fetchDeviceIdsMap) {
            for (final Jid jid : jids) {
                // fetchDeviceIdsMap tracks legacy device-list fetches only; the
                // OMEMO2 one keeps no such registry.
                if (!omemo2Stack && this.fetchDeviceIdsMap.containsKey(jid)) {
                    return true;
                }
                if (hasPendingBundleFetch(jid, omemo2Stack)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** True when any device of {@code jid} ON THIS STACK has a bundle fetch in flight. */
    private boolean hasPendingBundleFetch(final Jid jid, final boolean omemo2Stack) {
        final Set<Integer> ids = getDeviceIdsForStack(jid.asBareJid(), omemo2Stack);
        if (ids == null) {
            return false;
        }
        final String name = jid.asBareJid().toString();
        for (final Integer id : ids) {
            if (fetchStatusMap.get(new SignalProtocolAddress(name, id)) == FetchStatus.PENDING) {
                return true;
            }
        }
        return false;
    }

    /**
     * The legacy OMEMO1 crypto backend has been removed: a legacy-format
     * ({@link XmppAxolotlMessage}) outgoing message can no longer be wrapped for
     * any recipient device, so this always fails. Kept (rather than deleted,
     * along with {@link #encrypt(Message)}/{@link #preparePayloadMessage}) because
     * it is still reachable from the message-sending pipeline for conversations
     * not yet migrated off legacy encryption (see Task 7 for that cleanup).
     */
    @Nullable
    public XmppAxolotlMessage encrypt(final String content, Jid counterpart) {
        Log.w(Config.LOGTAG, getLogprefix(account)
                + "legacy OMEMO1 support has been removed — cannot encrypt to " + counterpart);
        return null;
    }

    @Nullable
    public XmppAxolotlMessage encrypt(final String content, Conversation conversation) {
        Log.w(Config.LOGTAG, getLogprefix(account)
                + "legacy OMEMO1 support has been removed — cannot encrypt for "
                + conversation.getJid().asBareJid());
        return null;
    }

    @Nullable
    public XmppAxolotlMessage encrypt(Message message) {
        final String content;
        if (message.hasFileOnRemoteHost()) {
            content = message.getFileParams().url;
        } else {
            content = message.getRawBody();
        }

        if (message.isPrivateMessage()) {
            return encrypt(content, message.getTrueCounterpart());
        } else {
            return encrypt(content, (Conversation) message.getConversation());
        }
    }

    public void preparePayloadMessage(final Message message, final boolean delay) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                XmppAxolotlMessage axolotlMessage = encrypt(message);
                if (axolotlMessage == null) {
                    mXmppConnectionService.markMessage(message, Message.STATUS_SEND_FAILED);
                    //mXmppConnectionService.updateConversationUi();
                } else {
                    Log.d(Config.LOGTAG, AxolotlService.getLogprefix(account) + "Generated message, caching: " + message.getUuid());
                    messageCache.put(message.getUuid(), axolotlMessage);
                    mXmppConnectionService.resendMessage(message, delay, true);
                }
            }
        });
    }

    private OmemoVerifiedIceUdpTransportInfo encryptTransport(final IceUdpTransportInfo element,
            final SignalProtocolAddress address) throws CryptoFailedException {
        final OmemoVerifiedIceUdpTransportInfo transportInfo = new OmemoVerifiedIceUdpTransportInfo();
        transportInfo.setAttributes(element.getAttributes());
        final XmppAxolotlSession omemo2Session = sessions.get(address);
        for (final Element child : element.getChildren()) {
            if ("fingerprint".equals(child.getName()) && Namespace.JINGLE_APPS_DTLS.equals(child.getNamespace())) {
                final Element fingerprint = new Element("fingerprint", Namespace.OMEMO_DTLS_SRTP_VERIFICATION);
                fingerprint.setAttribute("setup", child.getAttribute("setup"));
                fingerprint.setAttribute("hash", child.getAttribute("hash"));
                if (omemo2Session != null) {
                    final XmppOmemo2Message omemo2Message =
                            new XmppOmemo2Message(account.getJid().asBareJid(), getOwnDeviceId());
                    final Element dtlsFingerprint = new Element("fingerprint", Namespace.JINGLE_APPS_DTLS);
                    dtlsFingerprint.setAttribute("setup", child.getAttribute("setup"));
                    dtlsFingerprint.setAttribute("hash", child.getAttribute("hash"));
                    dtlsFingerprint.setContent(child.getContent());
                    try {
                        omemo2Message.encrypt(
                                null,
                                java.util.Collections.singletonList(dtlsFingerprint),
                                Jid.of(address.getName()).asBareJid(),
                                false);
                    } catch (final Exception e) {
                        throw new CryptoFailedException(e);
                    }
                    omemo2Message.addDevice(omemo2Session, true);
                    omemo2Message.wipeMessageKey();
                    fingerprint.addChild(omemo2Message.toElement());
                } else {
                    throw new CryptoFailedException("no OMEMO2 session for RTP verification with " + address);
                }
                transportInfo.addChild(fingerprint);
            } else {
                transportInfo.addChild(child);
            }
        }
        return transportInfo;
    }


    public ListenableFuture<OmemoVerifiedPayload<OmemoVerifiedRtpContentMap>> encrypt(final RtpContentMap rtpContentMap, final Jid jid, final int deviceId) {
        final SignalProtocolAddress address = new SignalProtocolAddress(jid.asBareJid().toString(), deviceId);
        return Futures.transformAsync(
                prepareRtpSession(address),
                ignored -> {
                    try {
                        return Futures.immediateFuture(encryptRtpContentMap(rtpContentMap, address));
                    } catch (final CryptoFailedException e) {
                        return Futures.immediateFailedFuture(e);
                    }
                },
                MoreExecutors.directExecutor()
        );
    }

    private OmemoVerifiedPayload<OmemoVerifiedRtpContentMap> encryptRtpContentMap(
            final RtpContentMap rtpContentMap, final SignalProtocolAddress address) throws CryptoFailedException {
        final XmppAxolotlSession omemo2Session = sessions.get(address);
        final String fingerprint;
        if (omemo2Session == null) {
            throw new CryptoFailedException("no OMEMO2 session for RTP verification with " + address);
        }
        if (Config.REQUIRE_RTP_VERIFICATION) {
            requireVerification(omemo2Session);
        }
        fingerprint = omemo2Session.getFingerprint();
        final ImmutableMap.Builder<String, DescriptionTransport<RtpDescription,IceUdpTransportInfo>> descriptionTransportBuilder = new ImmutableMap.Builder<>();
        final OmemoVerification omemoVerification = new OmemoVerification();
        omemoVerification.setDeviceId(address.getDeviceId());
        omemoVerification.setSessionFingerprint(fingerprint);
        omemoVerification.setLegacy(false);
        for (final Map.Entry<String, DescriptionTransport<RtpDescription,IceUdpTransportInfo>> content : rtpContentMap.contents.entrySet()) {
            final DescriptionTransport<RtpDescription,IceUdpTransportInfo> descriptionTransport = content.getValue();
            final OmemoVerifiedIceUdpTransportInfo encryptedTransportInfo =
                    encryptTransport(descriptionTransport.transport, address);
            descriptionTransportBuilder.put(
                    content.getKey(),
                    new DescriptionTransport<>(descriptionTransport.senders, descriptionTransport.description, encryptedTransportInfo)
            );
        }
        return new OmemoVerifiedPayload<>(
                omemoVerification,
                new OmemoVerifiedRtpContentMap(rtpContentMap.group, descriptionTransportBuilder.build()));
    }

    /**
     * Build (or reuse) an OMEMO2 session to verify an outgoing call to a device.
     * The legacy OMEMO1 crypto backend has been removed, so OMEMO2 is now the
     * only stack that can ever verify a call; this fails when no OMEMO2 session
     * can be established.
     */
    private ListenableFuture<Void> prepareRtpSession(final SignalProtocolAddress address) {
        if (sessions.get(address) != null) {
            return Futures.immediateFuture(null);
        }
        final SettableFuture<Void> result = SettableFuture.create();
        buildSessionFromOmemo2PEP(address, new OnSessionBuildFromPep() {
            @Override
            public void onSessionBuildSuccessful() {
                result.set(null);
            }

            @Override
            public void onSessionBuildFailed() {
                result.setException(new CryptoFailedException(
                        "no OMEMO2 session for RTP verification with " + address));
            }
        }, SettableFuture.create());
        return result;
    }

    public ListenableFuture<OmemoVerifiedPayload<RtpContentMap>> decrypt(OmemoVerifiedRtpContentMap omemoVerifiedRtpContentMap, final Jid from) {
        final ImmutableMap.Builder<String, DescriptionTransport<RtpDescription,IceUdpTransportInfo>> descriptionTransportBuilder = new ImmutableMap.Builder<>();
        final OmemoVerification omemoVerification = new OmemoVerification();
        final ImmutableList.Builder<ListenableFuture<XmppAxolotlSession>> pepVerificationFutures = new ImmutableList.Builder<>();
        for (final Map.Entry<String, DescriptionTransport<RtpDescription,IceUdpTransportInfo>> content : omemoVerifiedRtpContentMap.contents.entrySet()) {
            final DescriptionTransport<RtpDescription,IceUdpTransportInfo> descriptionTransport = content.getValue();
            final OmemoVerifiedPayload<IceUdpTransportInfo> decryptedTransport;
            try {
                decryptedTransport = decrypt((OmemoVerifiedIceUdpTransportInfo) descriptionTransport.transport, from, pepVerificationFutures);
            } catch (CryptoFailedException e) {
                return Futures.immediateFailedFuture(e);
            }
            omemoVerification.setOrEnsureEqual(decryptedTransport);
            descriptionTransportBuilder.put(
                    content.getKey(),
                    new DescriptionTransport<>(descriptionTransport.senders, descriptionTransport.description, decryptedTransport.payload)
            );
        }
        processPostponed();
        final ImmutableList<ListenableFuture<XmppAxolotlSession>> sessionFutures = pepVerificationFutures.build();
        return Futures.transform(
                Futures.allAsList(sessionFutures),
                sessions -> {
                    if (Config.REQUIRE_RTP_VERIFICATION) {
                        for (XmppAxolotlSession session : sessions) {
                            requireVerification(session);
                        }
                    }
                    return new OmemoVerifiedPayload<>(
                            omemoVerification,
                            new RtpContentMap(omemoVerifiedRtpContentMap.group, descriptionTransportBuilder.build())
                    );

                },
                MoreExecutors.directExecutor()
        );
    }

    private OmemoVerifiedPayload<IceUdpTransportInfo> decrypt(final OmemoVerifiedIceUdpTransportInfo verifiedIceUdpTransportInfo, final Jid from, ImmutableList.Builder<ListenableFuture<XmppAxolotlSession>> pepVerificationFutures) throws CryptoFailedException {
        final IceUdpTransportInfo transportInfo = new IceUdpTransportInfo();
        transportInfo.setAttributes(verifiedIceUdpTransportInfo.getAttributes());
        final OmemoVerification omemoVerification = new OmemoVerification();
        for (final Element child : verifiedIceUdpTransportInfo.getChildren()) {
            if ("fingerprint".equals(child.getName()) && Namespace.OMEMO_DTLS_SRTP_VERIFICATION.equals(child.getNamespace())) {
                final Element fingerprint = new Element("fingerprint", Namespace.JINGLE_APPS_DTLS);
                fingerprint.setAttribute("setup", child.getAttribute("setup"));
                fingerprint.setAttribute("hash", child.getAttribute("hash"));
                String decryptedFingerprint;
                int verifiedDeviceId = 0;
                String verifiedFingerprint = null;
                final Element omemo2Encrypted = child.findChildEnsureSingle("encrypted", Namespace.OMEMO2);
                if (omemo2Encrypted != null) {
                    final XmppOmemo2Message omemo2Message =
                            XmppOmemo2Message.fromElement(omemo2Encrypted, from.asBareJid());
                    final SignalProtocolAddress senderAddress = new SignalProtocolAddress(
                            from.asBareJid().toString(), omemo2Message.getSenderDeviceId());
                    final XmppAxolotlSession session = getReceivingSession(senderAddress);
                    final XmppOmemo2Message.DecryptedSce sce;
                    try {
                        final Jid ownBare = account.getJid().asBareJid();
                        // Jingle transport-info arrives live; stanza sending time is now.
                        sce = omemo2Message.decrypt(session, getOwnDeviceId(), ownBare, ownBare,
                                System.currentTimeMillis());
                    } catch (final Exception e) {
                        throw new CryptoFailedException(e);
                    }
                    final Integer preKeyId = session.getPreKeyIdAndReset();
                    if (preKeyId != null) {
                        postponedSessions.put(session, true);
                    }
                    if (session.isFresh()) {
                        pepVerificationFutures.add(putFreshSession(session));
                    } else if (Config.REQUIRE_RTP_VERIFICATION) {
                        pepVerificationFutures.add(Futures.immediateFuture(session));
                    }
                    Element innerFingerprint = null;
                    for (final Element el : sce.elements) {
                        if ("fingerprint".equals(el.getName())) {
                            innerFingerprint = el;
                            break;
                        }
                    }
                    if (innerFingerprint == null || innerFingerprint.getContent() == null) {
                        throw new CryptoFailedException("OMEMO2 RTP verification: no DTLS fingerprint in SCE content");
                    }
                    decryptedFingerprint = innerFingerprint.getContent();
                    verifiedDeviceId = session.getRemoteAddress().getDeviceId();
                    verifiedFingerprint = sce.fingerprint;
                } else {
                    // Legacy (XEP-0384 v0.3) container. The legacy OMEMO1 crypto
                    // backend has been removed, so this can only be decrypted via
                    // the OMEMO2 session cipher — the fallback that used to cover
                    // pre-split builds that wrapped a legacy container with the
                    // primary session.
                    final Element encrypted = child.findChildEnsureSingle(XmppAxolotlMessage.CONTAINERTAG, AxolotlService.PEP_PREFIX);
                    final XmppAxolotlMessage xmppAxolotlMessage = XmppAxolotlMessage.fromElement(encrypted, from.asBareJid());
                    final XmppAxolotlSession session = getReceivingSession(xmppAxolotlMessage);
                    final XmppAxolotlMessage.XmppAxolotlPlaintextMessage plaintext =
                            xmppAxolotlMessage.decrypt(session, getOwnDeviceId());
                    if (plaintext == null) {
                        throw new CryptoFailedException("could not decrypt Jingle security element from " + from);
                    }
                    final Integer preKeyId = session.getPreKeyIdAndReset();
                    if (preKeyId != null) {
                        postponedSessions.put(session, true);
                    }
                    if (session.isFresh()) {
                        pepVerificationFutures.add(putFreshSession(session));
                    } else if (Config.REQUIRE_RTP_VERIFICATION) {
                        pepVerificationFutures.add(Futures.immediateFuture(session));
                    }
                    verifiedDeviceId = session.getRemoteAddress().getDeviceId();
                    verifiedFingerprint = plaintext.getFingerprint();
                    decryptedFingerprint = plaintext.getPlaintext();
                }
                fingerprint.setContent(decryptedFingerprint);
                omemoVerification.setDeviceId(verifiedDeviceId);
                omemoVerification.setSessionFingerprint(verifiedFingerprint);
                transportInfo.addChild(fingerprint);
            } else {
                transportInfo.addChild(child);
            }
        }
        return new OmemoVerifiedPayload<>(omemoVerification, transportInfo);
    }

    private static void requireVerification(final XmppAxolotlSession session) {
        if (session.getTrust().isVerified()) {
            return;
        }
        throw new NotVerifiedException(String.format(
                "session with %s was not verified",
                session.getFingerprint()
        ));
    }

    /**
     * The legacy OMEMO1 crypto backend has been removed, so a legacy key
     * transport message can no longer be wrapped for any device; this always
     * fails. Kept (rather than deleted) because it is still reachable from
     * the legacy Jingle file-transfer path (see JingleFileTransferConnection),
     * not yet cleaned up per this plan's Task 7.
     */
    public ListenableFuture<XmppAxolotlMessage> prepareKeyTransportMessage(final Conversation conversation) {
        return Futures.submit(() -> {
            throw new IllegalStateException("No session to decrypt to");
        }, executor);
    }

    public ListenableFuture<XmppOmemo2Message> prepareOmemo2KeyTransportMessage(
            final Conversation conversation, final byte[] key, final byte[] iv) {
        return Futures.submit(() -> {
            final Element securityElement =
                    new Element("jingle-transport-security", "urn:xmpp:jingle:transports:omemo:2");
            securityElement.addChild("key").setContent(Base64.encodeToString(key, Base64.NO_WRAP));
            securityElement.addChild("iv").setContent(Base64.encodeToString(iv, Base64.NO_WRAP));
            return encryptOmemo2ContentElements(
                    Collections.singletonList(securityElement), conversation);
        }, executor);
    }

    public XmppAxolotlMessage fetchAxolotlMessageFromCache(Message message) {
        XmppAxolotlMessage axolotlMessage = messageCache.get(message.getUuid());
        if (axolotlMessage != null) {
            Log.d(Config.LOGTAG, AxolotlService.getLogprefix(account) + "Cache hit: " + message.getUuid());
            messageCache.remove(message.getUuid());
        } else {
            Log.d(Config.LOGTAG, AxolotlService.getLogprefix(account) + "Cache miss: " + message.getUuid());
        }
        return axolotlMessage;
    }

    private XmppAxolotlSession recreateUncachedSession(SignalProtocolAddress address) {
        IdentityKey identityKey = getRemoteIdentityKeySafe(axolotlStore.loadSession(address));
        return (identityKey != null)
                ? new XmppAxolotlSession(account, axolotlStore, getOwnAxolotlAddress(), address, identityKey)
                : null;
    }

    private XmppAxolotlSession getReceivingSession(XmppAxolotlMessage message) {
        SignalProtocolAddress senderAddress = new SignalProtocolAddress(message.getFrom().toString(), message.getSenderDeviceId());
        return getReceivingSession(senderAddress);

    }

    private XmppAxolotlSession getReceivingSession(SignalProtocolAddress senderAddress) {
        XmppAxolotlSession session = sessions.get(senderAddress);
        if (session == null) {
            session = recreateUncachedSession(senderAddress);
            if (session == null) {
                session = new XmppAxolotlSession(account, axolotlStore, getOwnAxolotlAddress(), senderAddress);
            }
        }
        return session;
    }

    /**
     * The legacy OMEMO1 crypto backend has been removed, so a legacy (XEP-0384
     * v0.3, namespace eu.siacs.conversations.axolotl) payload message can never
     * be decrypted any more — this always fails closed rather than falling back
     * to the OMEMO2 stack (the two stacks use incompatible per-device key
     * wrapping, and mixing them would re-introduce cross-stack ambiguity).
     */
    public XmppAxolotlMessage.XmppAxolotlPlaintextMessage processReceivingPayloadMessage(XmppAxolotlMessage message, boolean postponePreKeyMessageHandling) throws NotEncryptedForThisDeviceException, BrokenSessionException, OutdatedSenderException {
        Log.w(Config.LOGTAG, getLogprefix(account)
                + "Received legacy OMEMO from " + message.getFrom()
                + " but legacy OMEMO1 support has been removed — dropping");
        return null;
    }

    public void reportBrokenSessionException(BrokenSessionException e, boolean postpone) {
        reportBrokenSessionException(e, postpone, false);
    }

    /**
     * @param omemo2Session whether the broken session was an OMEMO2 (PQ) session.
     *     The legacy OMEMO1 crypto backend has been removed, so this is now
     *     always {@code true} in practice — the {@code false} (legacy) path is
     *     kept only because {@link #reportBrokenSessionException(BrokenSessionException, boolean)}
     *     is still reachable from message-parsing code that has not yet been
     *     cleaned up of legacy-OMEMO handling (see Task 7), and does nothing
     *     since there is no longer a legacy session to heal.
     */
    public void reportBrokenSessionException(BrokenSessionException e, boolean postpone, final boolean omemo2Session) {
        Log.e(Config.LOGTAG, account.getJid().asBareJid() + ": broken session with " + e.getSignalProtocolAddress().toString() + " detected", e);
        if (!omemo2Session) {
            Log.d(Config.LOGTAG, account.getJid().asBareJid()
                    + ": ignoring broken legacy OMEMO1 session — legacy support has been removed");
            return;
        }
        if (postpone) {
            postponedHealing.put(e.getSignalProtocolAddress(), true);
        } else {
            notifyRequiresHealing(e.getSignalProtocolAddress());
        }
    }

    private void notifyRequiresHealing(final SignalProtocolAddress signalProtocolAddress) {
        if (healingAttempts.add(signalProtocolAddress)) {
            Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": attempt to heal " + signalProtocolAddress + " (OMEMO2)");
            final OnSessionBuildFromPep callback = new OnSessionBuildFromPep() {
                @Override
                public void onSessionBuildSuccessful() {
                    Log.d(Config.LOGTAG, "successfully build new session from pep after detecting broken session");
                    completeOmemo2Session(getReceivingSession(signalProtocolAddress));
                }

                @Override
                public void onSessionBuildFailed() {
                    Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": unable to build new session from pep after detecting broken session");
                }
            };
            buildSessionFromOmemo2PEP(signalProtocolAddress, callback, SettableFuture.create());
        } else {
            Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": do not attempt to heal " + signalProtocolAddress + " again");
        }
    }

    private void postPreKeyMessageHandling(final XmppAxolotlSession session, final boolean postpone) {
        if (postpone) {
            postponedSessions.put(session, true);
        } else {
            if (axolotlStore.flushPreKeys()) {
                publishBundlesIfNeeded(false, false);
            } else {
                Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": nothing to flush. Not republishing key");
            }
            replenishKyberPreKeysIfNeeded();
            if (trustedOrPreviouslyResponded(session) && Config.AUTOMATICALLY_COMPLETE_SESSIONS) {
                completeOmemo2Session(session);
            }
        }
    }

    public void processPostponed() {
        if (postponedSessions.size() > 0) {
            if (axolotlStore.flushPreKeys()) {
                publishBundlesIfNeeded(false, false);
            }
            replenishKyberPreKeysIfNeeded();
        }
        // Both maps' Boolean value used to record which stack the postponed
        // session/heal belonged to. The legacy OMEMO1 crypto backend has been
        // removed, so every entry is now an OMEMO2 one.
        final Iterator<Map.Entry<XmppAxolotlSession, Boolean>> iterator =
                postponedSessions.entrySet().iterator();
        while (iterator.hasNext()) {
            final Map.Entry<XmppAxolotlSession, Boolean> entry = iterator.next();
            final XmppAxolotlSession session = entry.getKey();
            if (trustedOrPreviouslyResponded(session) && Config.AUTOMATICALLY_COMPLETE_SESSIONS) {
                completeOmemo2Session(session);
            }
            iterator.remove();
        }
        final Iterator<Map.Entry<SignalProtocolAddress, Boolean>> postponedHealingAttemptsIterator =
                postponedHealing.entrySet().iterator();
        while (postponedHealingAttemptsIterator.hasNext()) {
            final Map.Entry<SignalProtocolAddress, Boolean> entry = postponedHealingAttemptsIterator.next();
            notifyRequiresHealing(entry.getKey());
            postponedHealingAttemptsIterator.remove();
        }
    }

    private boolean trustedOrPreviouslyResponded(XmppAxolotlSession session) {
        try {
            return trustedOrPreviouslyResponded(Jid.of(session.getRemoteAddress().getName()));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public boolean trustedOrPreviouslyResponded(Jid jid) {
        final Contact contact = account.getRoster().getContact(jid);
        if (contact.showInRoster() || contact.isSelf()) {
            return true;
        }
        final Conversation conversation = mXmppConnectionService.find(account, jid);
        return conversation != null && conversation.sentMessagesCount() > 0;
    }

    /**
     * After rebuilding a broken OMEMO2 session, send the peer a minimal OMEMO2
     * message carrying an empty SCE envelope (no body, no metadata). Decrypting
     * it on the peer side runs the normal OMEMO2 receive path, which
     * ratchets/rebuilds their session and produces no visible message — healing
     * the session bidirectionally. A no-payload "key transport" would not work
     * here because the receive path only dispatches OMEMO2 stanzas that carry a
     * {@code <payload>}.
     */
    private void completeOmemo2Session(final XmppAxolotlSession session) {
        if (session == null) return;
        final Jid jid;
        try {
            jid = Jid.of(session.getRemoteAddress().getName());
        } catch (final IllegalArgumentException e) {
            throw new Error("Remote addresses are created from jid and should convert back to jid", e);
        }
        final XmppOmemo2Message message = new XmppOmemo2Message(account.getJid().asBareJid(), getOwnDeviceId());
        try {
            // Empty SCE envelope: no body, no metadata elements.
            message.encrypt(null, null, jid, false);
        } catch (final CryptoFailedException e) {
            Log.w(Config.LOGTAG, getLogprefix(account)
                    + "could not build OMEMO2 heal message for " + jid + ": " + e.getMessage());
            return;
        }
        message.addDevice(session, true);
        message.wipeMessageKey();
        if (!message.hasPayload()) return;
        final var packet = mXmppConnectionService.getMessageGenerator()
                .generateOmemo2KeyTransportMessage(jid, message);
        mXmppConnectionService.sendMessagePacket(account, packet);
    }

    /**
     * XEP-0384 heartbeat (OMEMO2 stack): if the message we just decrypted from this
     * device reached the ratchet-counter threshold on a not-yet-heartbeated chain, reply
     * with an empty OMEMO2 message to force a DH-ratchet step — restoring break-in recovery
     * and bounding skipped-key storage in a long one-directional conversation.
     *
     * Security: same envelope a heal sends (no body, no metadata); only to a trusted &
     * active device; at most once per receiving ratchet key. It never downgrades or
     * re-pairs anything and stays entirely on the OMEMO2 stack.
     */
    private void maybeSendOmemo2Heartbeat(final XmppAxolotlSession session,
            final SignalProtocolAddress address) {
        if (session == null) return;
        final XmppAxolotlSession.WhisperRatchet ratchet = session.getLastWhisperRatchetAndReset();
        if (ratchet == null || ratchet.counter < HEARTBEAT_COUNTER_THRESHOLD) {
            return;
        }
        if (!heartbeatDue(address, ratchet.ratchetKey)) {
            return;
        }
        if (!session.getTrust().isTrustedAndActive()) {
            return;
        }
        Log.d(Config.LOGTAG, account.getJid().asBareJid()
                + ": sending XEP-0384 heartbeat to " + address + " (ratchet counter "
                + ratchet.counter + ")");
        completeOmemo2Session(session);
    }

    /** True (and records the ratchet key) only when we have not already heartbeated for
     *  this exact sender ratchet key — i.e. the first such message for a given chain. */
    private boolean heartbeatDue(final SignalProtocolAddress address, final byte[] ratchetKey) {
        final byte[] previous = heartbeatRatchetKeys.get(address);
        if (previous != null && Arrays.equals(previous, ratchetKey)) {
            return false;
        }
        heartbeatRatchetKeys.put(address, ratchetKey);
        return true;
    }

    /**
     * Background pq_ik pin reconciliation. The ML-DSA-87 identity of a peer device is
     * normally pinned when WE build the session from its fetched bundle
     * (buildSessionFromOmemo2PEP). When the PEER initiated the session, the inbound
     * PQXDH key-exchange message does not carry the initiator's pq_ik, so no pin is
     * ever written — the device then shows its classical instead of hybrid fingerprint
     * indefinitely. Called after every successful inbound OMEMO2 decrypt: if no pq_ik
     * is pinned for the sender's classical identity yet, fetch its bundle once (per
     * device, per app run), verify it, and pin.
     *
     * Security: this is strictly a TOFU pin-fill, never a re-pin — an existing pin is
     * never overwritten here (a changed pq_ik stays an error handled at session build).
     * Before pinning, the fetched bundle must (a) carry the SAME classical identity key
     * as our existing session — a malicious/compromised PEP node cannot poison the pin
     * for an identity we already have — and (b) carry a valid ML-DSA-87 signature over
     * the v2 transcript (ik, pq_ik, EC signed pre-key, KEM binding), proving possession
     * of the pq identity's signing key for exactly this classical identity.
     */
    private void reconcileOmemo2PqPinIfMissing(final SignalProtocolAddress address,
            final XmppAxolotlSession session) {
        try {
            final String ikFingerprint = session.getFingerprint();
            if (ikFingerprint == null) {
                return;
            }
            if (!pqPinReconcileAttempts.add(address)) {
                return; // already attempted this run
            }
            if (mXmppConnectionService.databaseBackend
                    .getPinnedOmemo2PqIdentity(account, address.getName(), ikFingerprint)
                    != null) {
                return; // already pinned
            }
            Log.d(Config.LOGTAG, getLogprefix(account) + "no pq_ik pinned for " + address
                    + " — fetching its OMEMO2 bundle to reconcile");
            final Jid jid = Jid.of(address.getName());
            final Iq packet = mXmppConnectionService.getIqGenerator()
                    .retrieveOmemo2BundlesForDevice(jid, address.getDeviceId());
            mXmppConnectionService.sendIqPacket(account, packet, response -> {
                if (response.getType() != Iq.Type.RESULT) {
                    Log.d(Config.LOGTAG, getLogprefix(account)
                            + "pq_ik reconciliation: bundle fetch for " + address + " failed");
                    return;
                }
                try {
                    reconcileOmemo2PqPinFromBundle(address, ikFingerprint, response);
                } catch (final Exception e) {
                    Log.w(Config.LOGTAG, getLogprefix(account)
                            + "pq_ik reconciliation for " + address + " failed: " + e.getMessage());
                }
            });
        } catch (final Exception e) {
            // never let background reconciliation interfere with message processing
            Log.w(Config.LOGTAG, getLogprefix(account)
                    + "pq_ik reconciliation for " + address + " failed: " + e.getMessage());
        }
    }

    private void reconcileOmemo2PqPinFromBundle(final SignalProtocolAddress address,
            final String ikFingerprint, final Iq response) {
        final PreKeyBundle bundle = IqParser.omemo2Bundle(response);
        final List<IqParser.KemBundleKey> kemPreKeys = IqParser.omemo2KemPreKeys(response);
        final IqParser.PqIdentity peerPq = IqParser.omemo2PqIdentity(response);
        if (bundle == null || peerPq == null) {
            Log.w(Config.LOGTAG, getLogprefix(account) + "pq_ik reconciliation: bundle for "
                    + address + " is empty or has no PQ identity — not pinning");
            return;
        }
        // (a) the bundle must belong to the classical identity we already know
        final String bundleIkFingerprint = CryptoHelper.bytesToHex(
                bundle.getIdentityKey().getPublicKey().serialize());
        if (!bundleIkFingerprint.equals(ikFingerprint)) {
            Log.e(Config.LOGTAG, getLogprefix(account) + "pq_ik reconciliation: bundle for "
                    + address + " carries a DIFFERENT classical identity key — not pinning");
            return;
        }
        // (b) the ML-DSA-87 signature over the v2 transcript must verify
        final byte[] kemBinding = computeOmemo2KemBindingFromWire(bundle, kemPreKeys);
        final byte[] transcript = PqBundle.transcript(
                bundle.getIdentityKey(),
                peerPq.identityKey,
                bundle.getSignedPreKeyId(),
                bundle.getSignedPreKey(),
                kemBinding);
        if (!new PqIdentityKey(peerPq.identityKey).verify(transcript, peerPq.signature)) {
            Log.e(Config.LOGTAG, getLogprefix(account) + "pq_ik reconciliation: invalid"
                    + " ML-DSA-87 bundle signature for " + address + " — not pinning");
            return;
        }
        // pin-fill only: re-check under the current state and never overwrite —
        // a concurrent session build may have pinned (possibly this same value) already
        final byte[] pinned = mXmppConnectionService.databaseBackend
                .getPinnedOmemo2PqIdentity(account, address.getName(), ikFingerprint);
        if (pinned != null) {
            if (!Arrays.equals(pinned, peerPq.identityKey)) {
                Log.e(Config.LOGTAG, getLogprefix(account) + "pq_ik reconciliation: a"
                        + " DIFFERENT pq_ik was pinned concurrently for " + address
                        + " — keeping the existing pin");
            }
            return;
        }
        mXmppConnectionService.databaseBackend.pinOmemo2PqIdentity(
                account, address.getName(), ikFingerprint, peerPq.identityKey);
        Log.d(Config.LOGTAG, getLogprefix(account)
                + "pq_ik reconciliation: pinned PQ identity for " + address);
        // hybrid fingerprint is now available — refresh key lists in the UI
        mXmppConnectionService.keyStatusUpdated(null);
    }

    /**
     * The legacy OMEMO1 crypto backend has been removed, so a legacy (XEP-0384
     * v0.3) key transport message can never be decrypted any more — this always
     * fails closed.
     */
    public XmppAxolotlMessage.XmppAxolotlKeyTransportMessage processReceivingKeyTransportMessage(XmppAxolotlMessage message, final boolean postponePreKeyMessageHandling) {
        Log.w(Config.LOGTAG, getLogprefix(account)
                + "received legacy OMEMO key transport from " + message.getFrom()
                + " but legacy OMEMO1 support has been removed — dropping");
        return null;
    }

    public XmppAxolotlMessage.XmppAxolotlKeyTransportMessage processReceivingOmemo2KeyTransportMessage(
            final XmppOmemo2Message message, final Jid expectedTo) {
        final XmppOmemo2Message.DecryptedSce decryptedSce;
        try {
            // Jingle security messages arrive live; the stanza sending time is now.
            decryptedSce = processReceivingOmemo2PayloadMessage(
                    message, false, expectedTo, System.currentTimeMillis());
        } catch (final Exception e) {
            Log.w(
                    Config.LOGTAG,
                    getLogprefix(account)
                            + "failed to decrypt OMEMO2 Jingle security message: "
                            + e.getMessage());
            return null;
        }
        if (decryptedSce == null) {
            return null;
        }
        for (final Element element : decryptedSce.elements) {
            if ("jingle-transport-security".equals(element.getName())
                    && "urn:xmpp:jingle:transports:omemo:2".equals(element.getNamespace())) {
                final String keyStr = element.findChildContent("key");
                final String ivStr = element.findChildContent("iv");
                if (keyStr != null && ivStr != null) {
                    try {
                        return new XmppAxolotlMessage.XmppAxolotlKeyTransportMessage(
                                decryptedSce.fingerprint,
                                Base64.decode(keyStr, Base64.DEFAULT),
                                Base64.decode(ivStr, Base64.DEFAULT));
                    } catch (final Exception e) {
                        Log.w(
                                Config.LOGTAG,
                                getLogprefix(account)
                                        + "failed to decode OMEMO2 Jingle security: "
                                        + e.getMessage());
                    }
                }
            }
        }
        return null;
    }

    private ListenableFuture<XmppAxolotlSession> putFreshSession(XmppAxolotlSession session) {
        sessions.put(session);
        if (Config.X509_VERIFICATION) {
            if (session.getIdentityKey() != null) {
                return verifySessionWithPEP(session);
            } else {
                Log.e(Config.LOGTAG, account.getJid().asBareJid() + ": identity key was empty after reloading for x509 verification");
            }
        }
        return Futures.immediateFuture(session);
    }

    public enum FetchStatus {
        PENDING,
        SUCCESS,
        SUCCESS_VERIFIED,
        TIMEOUT,
        SUCCESS_TRUSTED,
        ERROR
    }

    public interface OnDeviceIdsFetched {
        void fetched(Jid jid, Set<Integer> deviceIds);
    }


    public interface OnMultipleDeviceIdFetched {
        void fetched();
    }

    interface OnSessionBuildFromPep {
        void onSessionBuildSuccessful();

        void onSessionBuildFailed();
    }

    private static class AxolotlAddressMap<T> {
        protected final Object MAP_LOCK = new Object();
        protected Map<String, Map<Integer, T>> map;

        public AxolotlAddressMap() {
            this.map = new HashMap<>();
        }

        public void put(SignalProtocolAddress address, T value) {
            synchronized (MAP_LOCK) {
                Map<Integer, T> devices = map.get(address.getName());
                if (devices == null) {
                    devices = new HashMap<>();
                    map.put(address.getName(), devices);
                }
                devices.put(address.getDeviceId(), value);
            }
        }

        public T get(SignalProtocolAddress address) {
            synchronized (MAP_LOCK) {
                Map<Integer, T> devices = map.get(address.getName());
                if (devices == null) {
                    return null;
                }
                return devices.get(address.getDeviceId());
            }
        }

        public T remove(SignalProtocolAddress address) {
            synchronized (MAP_LOCK) {
                final Map<Integer, T> devices = map.get(address.getName());
                if (devices == null) {
                    return null;
                }
                final T removed = devices.remove(address.getDeviceId());
                if (devices.isEmpty()) {
                    map.remove(address.getName());
                }
                return removed;
            }
        }

        public Map<Integer, T> getAll(String name) {
            synchronized (MAP_LOCK) {
                Map<Integer, T> devices = map.get(name);
                if (devices == null) {
                    return new HashMap<>();
                }
                return devices;
            }
        }

        public boolean hasAny(SignalProtocolAddress address) {
            synchronized (MAP_LOCK) {
                Map<Integer, T> devices = map.get(address.getName());
                return devices != null && !devices.isEmpty();
            }
        }

        public void clear() {
            map.clear();
        }

    }

    private static class SessionMap extends AxolotlAddressMap<XmppAxolotlSession> {
        private final XmppConnectionService xmppConnectionService;
        private final Account account;

        public SessionMap(XmppConnectionService service, SQLiteAxolotlStore store, Account account) {
            super();
            this.xmppConnectionService = service;
            this.account = account;
            this.fillMap(store);
        }

        public Set<Jid> findCounterpartsForSourceId(Integer sid) {
            Set<Jid> candidates = new HashSet<>();
            synchronized (MAP_LOCK) {
                for (Map.Entry<String, Map<Integer, XmppAxolotlSession>> entry : map.entrySet()) {
                    String key = entry.getKey();
                    if (entry.getValue().containsKey(sid)) {
                        candidates.add(Jid.of(key));
                    }
                }
            }
            return candidates;
        }

        private void putDevicesForJid(String bareJid, List<Integer> deviceIds, SQLiteAxolotlStore store) {
            for (Integer deviceId : deviceIds) {
                if (deviceId <= 0) {
                    Log.w(Config.LOGTAG, "Skipping invalid device ID " + deviceId + " for " + bareJid);
                    continue;
                }
                SignalProtocolAddress axolotlAddress = new SignalProtocolAddress(bareJid, deviceId);
                IdentityKey identityKey = getRemoteIdentityKeySafe(store.loadSession(axolotlAddress));
                if (Config.X509_VERIFICATION && identityKey != null) {
                    X509Certificate certificate = store.getFingerprintCertificate(bareJid,
                            CryptoHelper.bytesToHex(identityKey.getPublicKey().serialize()));
                    if (certificate != null) {
                        Bundle information = CryptoHelper.extractCertificateInformation(certificate);
                        try {
                            final String cn = information.getString("subject_cn");
                            final Jid jid = Jid.of(bareJid);
                            Log.d(Config.LOGTAG, "setting common name for " + jid + " to " + cn);
                            account.getRoster().getContact(jid).setCommonName(cn);
                        } catch (final IllegalArgumentException ignored) {
                            //ignored
                        }
                    }
                }
                final SignalProtocolAddress localAddress = new SignalProtocolAddress(account.getJid().asBareJid().toString(), store.getLocalRegistrationId());
                this.put(axolotlAddress, new XmppAxolotlSession(account, store, localAddress, axolotlAddress, identityKey));
            }
        }

        private void fillMap(SQLiteAxolotlStore store) {
            List<Integer> deviceIds = store.getSubDeviceSessions(account.getJid().asBareJid().toString());
            putDevicesForJid(account.getJid().asBareJid().toString(), deviceIds, store);
            for (String address : store.getKnownAddresses()) {
                deviceIds = store.getSubDeviceSessions(address);
                putDevicesForJid(address, deviceIds, store);
            }
        }

        @Override
        public void put(SignalProtocolAddress address, XmppAxolotlSession value) {
            super.put(address, value);
            value.setNotFresh();
        }

        public void put(XmppAxolotlSession session) {
            this.put(session.getRemoteAddress(), session);
        }
    }

    private static class FetchStatusMap extends AxolotlAddressMap<FetchStatus> {

        public void clearErrorFor(Jid jid) {
            synchronized (MAP_LOCK) {
                Map<Integer, FetchStatus> devices = this.map.get(jid.asBareJid().toString());
                if (devices == null) {
                    return;
                }
                for (Map.Entry<Integer, FetchStatus> entry : devices.entrySet()) {
                    if (entry.getValue() == FetchStatus.ERROR) {
                        Log.d(Config.LOGTAG, "resetting error for " + jid.asBareJid() + "(" + entry.getKey() + ")");
                        entry.setValue(FetchStatus.TIMEOUT);
                    }
                }
            }
        }
    }

    public static class OmemoVerifiedPayload<T> {
        private final int deviceId;
        private final String fingerprint;
        private final boolean legacy;
        private final T payload;

        private OmemoVerifiedPayload(OmemoVerification omemoVerification, T payload) {
            this.deviceId = omemoVerification.getDeviceId();
            this.fingerprint = omemoVerification.getFingerprint();
            this.legacy = omemoVerification.isLegacy();
            this.payload = payload;
        }

        public int getDeviceId() {
            return deviceId;
        }

        public String getFingerprint() {
            return fingerprint;
        }

        public boolean isLegacy() {
            return legacy;
        }

        public T getPayload() {
            return payload;
        }
    }

    public static class NotVerifiedException extends SecurityException {

        public NotVerifiedException(String message) {
            super(message);
        }

    }

    // -------------------------------------------------------------------------
    // OMEMO2 (XEP-0384) support
    // -------------------------------------------------------------------------

    // Rotate the signed (last-resort) KEM prekey after this age. Proto-XEP §4.5.1
    // allows 7–90 days; 30 days keeps last-resort exposure short without churning
    // the bundle on every publish.
    private static final long KEM_SPK_ROTATION_MS = 30L * 24 * 60 * 60 * 1000;
    // Delete unpublished KEM prekeys older than this: any in-flight
    // PreKeySignalMessage still referencing them is long dead, and keeping them
    // only grows the at-rest secret-key store without bound.
    private static final long KEM_PREKEY_MAX_AGE_MS = 90L * 24 * 60 * 60 * 1000;

    /**
     * The current last-resort KEM prekey, or null when none exists or the newest
     * one has aged past {@link #KEM_SPK_ROTATION_MS} (i.e. rotation is due).
     */
    @Nullable
    private KyberPreKeyRecord getCurrentKemSignedPreKey() {
        final KyberPreKeyRecord latest =
                mXmppConnectionService.databaseBackend.loadLatestKyberLastResortPreKey(account);
        if (latest == null) return null;
        // Belt and braces alongside purgeNonMlKemKyberPreKeys: a retained Round-3 Kyber key is
        // treated as due for rotation, never reused, no matter how young it is (§5.1.1).
        try {
            if (!CryptoHelper.isMlKem1024PublicKey(
                    latest.getKeyPair().getPublicKey().serialize())) {
                Log.i(Config.LOGTAG, getLogprefix(account)
                        + "stored KEM signed prekey is not ML-KEM-1024 — rotating");
                return null;
            }
        } catch (final Exception e) {
            return null;
        }
        final long age = System.currentTimeMillis() - latest.getTimestamp();
        if (age < 0 || age > KEM_SPK_ROTATION_MS) return null;
        return latest;
    }

    /** Publish our device ID and bundle to the OMEMO2 PEP nodes. Called after legacy publish. */
    public void publishOmemo2BundlesIfNeeded(final SignedPreKeyRecord signedPreKeyRecord,
                                             final Set<PreKeyRecord> preKeyRecords) {
        // Guard against first-run race where onUpgrade transaction may not have committed yet.
        mXmppConnectionService.databaseBackend.ensureKyberTablesExist();
        // Upgrade path: discard retained Round-3 Kyber prekeys so the reuse logic below
        // regenerates them as ML-KEM-1024 instead of republishing keys peers now reject.
        mXmppConnectionService.databaseBackend.purgeNonMlKemKyberPreKeys(account);
        // Signed KEM prekey (last-resort): REUSED until it ages past the rotation
        // window. Regenerating it on every publish would defeat the §4.5.1
        // rotation schedule and grow the key store without bound; it stays
        // protected against replay by the last-resort tuple tracker either way.
        KyberPreKeyRecord kyberSignedPreKeyRecord = getCurrentKemSignedPreKey();
        if (kyberSignedPreKeyRecord == null) {
            kyberSignedPreKeyRecord = generateKyberSignedPreKey(
                    axolotlStore.getIdentityKeyPair(), axolotlStore.getCurrentKemPreKeyId() + 1);
            axolotlStore.storeKyberLastResortPreKey(
                    kyberSignedPreKeyRecord.getId(), kyberSignedPreKeyRecord);
        }

        // One-time KEM prekeys: keep the unconsumed ones (they are deleted from
        // the store when a PreKeySignalMessage consumes them), generate only the
        // shortfall up to the published batch size.
        final List<KyberPreKeyRecord> kyberPreKeyRecords =
                mXmppConnectionService.databaseBackend.loadKyberOneTimePreKeys(
                        account, NUM_KEYS_TO_PUBLISH);
        final int shortfall = NUM_KEYS_TO_PUBLISH - kyberPreKeyRecords.size();
        if (shortfall > 0) {
            final int startKemId = axolotlStore.getCurrentKemPreKeyId() + 1;
            for (int i = 0; i < shortfall; i++) {
                final KyberPreKeyRecord record = generateKyberSignedPreKey(
                        axolotlStore.getIdentityKeyPair(), startKemId + i);
                kyberPreKeyRecords.add(record);
                axolotlStore.storeKyberPreKey(record.getId(), record);
            }
            Log.i(Config.LOGTAG, getLogprefix(account)
                    + "generated " + shortfall + " new one-time KEM prekeys (retained "
                    + (kyberPreKeyRecords.size() - shortfall) + ")");
        }

        pruneStaleKyberPreKeys(kyberPreKeyRecords, kyberSignedPreKeyRecord);
        publishOmemo2Bundle(signedPreKeyRecord, preKeyRecords, kyberSignedPreKeyRecord, kyberPreKeyRecords, true);
    }

    /**
     * Delete KEM prekeys that are neither part of the bundle being published nor
     * the current last-resort key, once they are older than
     * {@link #KEM_PREKEY_MAX_AGE_MS}. Superseded keys are deliberately kept for
     * that grace period so in-flight session initiations against a previously
     * published bundle still decrypt.
     */
    private void pruneStaleKyberPreKeys(final List<KyberPreKeyRecord> published,
                                        final KyberPreKeyRecord currentLastResort) {
        final Set<Integer> keep = new HashSet<>();
        for (final KyberPreKeyRecord record : published) {
            keep.add(record.getId());
        }
        keep.add(currentLastResort.getId());
        final long cutoff = System.currentTimeMillis() - KEM_PREKEY_MAX_AGE_MS;
        int pruned = 0;
        for (final KyberPreKeyRecord record : axolotlStore.loadKyberPreKeys()) {
            if (keep.contains(record.getId())) continue;
            if (record.getTimestamp() < cutoff) {
                mXmppConnectionService.databaseBackend.deleteKyberPreKey(account, record.getId());
                pruned++;
            }
        }
        if (pruned > 0) {
            Log.i(Config.LOGTAG, getLogprefix(account)
                    + "pruned " + pruned + " KEM prekeys older than 90 days");
        }
        // Now that superseded keys are gone, drop the last-resort replay records that
        // referenced them: they can no longer be reached (the replay fails at key lookup),
        // and that table is otherwise append-only and remotely growable — one row per
        // handshake anyone on the network initiates against our last-resort key.
        mXmppConnectionService.databaseBackend.pruneOrphanedKyberLastResortSessions(account);
    }

    // Republish when fewer than half of the published one-time KEM prekeys remain.
    // Below this threshold new sessions fall back to the signed (last-resort) KEM
    // prekey, which gives weaker forward secrecy for the handshake itself.
    private static final int MIN_KEM_PREKEYS = NUM_KEYS_TO_PUBLISH / 2;

    /** Republish the OMEMO2 bundle with a fresh batch of one-time KEM prekeys if stock is low. */
    private void replenishKyberPreKeysIfNeeded() {
        if (axolotlStore.getKyberOneTimePreKeyCount() >= MIN_KEM_PREKEYS) return;
        Log.i(Config.LOGTAG, getLogprefix(account)
                + "KEM prekey stock low — republishing OMEMO2 bundle");
        publishBundlesIfNeeded(false, false);
    }

    private static KyberPreKeyRecord generateKyberSignedPreKey(final IdentityKeyPair identityKeyPair, final int id) {
        final KEMKeyPair kemPair = KEMKeyPair.generate(KEMKeyType.MLKEM1024);
        final byte[] sig = identityKeyPair.getPrivateKey().calculateSignature(kemPair.getPublicKey().serialize());
        return new KyberPreKeyRecord(id, System.currentTimeMillis(), kemPair, sig);
    }

    /**
     * Verifier-side counterpart of {@link #computeOmemo2KemBinding}: recompute the
     * KEM binding from a fetched peer bundle. {@code fetched}'s kyber fields carry
     * the {@code <kem-spk>} (last-resort) and {@code oneTime} the {@code <kem-pk>}
     * one-time keys in document order — the same serialized bytes the publisher
     * bound — so a matching digest proves none of the peer's ML-KEM pre-keys were
     * substituted.
     */
    private byte[] computeOmemo2KemBindingFromWire(final PreKeyBundle fetched,
                                                   final List<IqParser.KemBundleKey> oneTime) {
        int kemSpkId = 0;
        byte[] kemSpkPub = new byte[0];
        try {
            kemSpkId = fetched.getKyberPreKeyId();
            kemSpkPub = fetched.getKyberPreKey().serialize();
        } catch (final Exception e) {
            Log.w(Config.LOGTAG, getLogprefix(account)
                    + "could not read fetched kem-spk for KEM binding: " + e.getMessage());
        }
        final List<PqBundle.KemOneTimeKey> list = new ArrayList<>();
        if (oneTime != null) {
            for (final IqParser.KemBundleKey k : oneTime) {
                list.add(new PqBundle.KemOneTimeKey(k.id, k.publicKey.serialize()));
            }
        }
        return PqBundle.kemBinding(kemSpkId, kemSpkPub, list);
    }

    /**
     * Compute the PQ-OMEMO2 KEM binding digest ({@link PqBundle#kemBinding}) over
     * the KEM material we are about to publish: the signed ("last-resort")
     * kem-spk plus every one-time kem-pk, using the same {@code serialize()} bytes
     * {@link IqGenerator#publishOmemo2Bundles} writes to the wire. The verifier
     * recomputes the identical digest from the fetched bundle, so the ML-DSA-87
     * bundle signature authenticates all of our ML-KEM pre-keys.
     */
    private byte[] computeOmemo2KemBinding(final KyberPreKeyRecord kemSpk,
                                           final List<KyberPreKeyRecord> oneTimeRecords)
            throws InvalidKeyException {
        final int kemSpkId = kemSpk != null ? kemSpk.getId() : 0;
        final byte[] kemSpkPub = kemSpk != null
                ? kemSpk.getKeyPair().getPublicKey().serialize() : new byte[0];
        final List<PqBundle.KemOneTimeKey> oneTime = new ArrayList<>();
        if (oneTimeRecords != null) {
            for (final KyberPreKeyRecord r : oneTimeRecords) {
                oneTime.add(new PqBundle.KemOneTimeKey(
                        r.getId(), r.getKeyPair().getPublicKey().serialize()));
            }
        }
        return PqBundle.kemBinding(kemSpkId, kemSpkPub, oneTime);
    }

    private void publishOmemo2Bundle(final SignedPreKeyRecord signedPreKeyRecord,
                                     final Set<PreKeyRecord> preKeyRecords,
                                     final KyberPreKeyRecord kyberSignedPreKeyRecord,
                                     final List<KyberPreKeyRecord> kyberPreKeyRecords,
                                     final boolean firstAttempt) {
        final XmppConnection connection = account.getXmppConnection();
        if (connection == null) {
            return;
        }
        final Bundle publishOptions = connection.getFeatures().pepPublishOptions()
                ? PublishOptions.openAccess() : null;
        // monocles PQ-OMEMO2 hybrid identity: sign the bundle transcript with our
        // ML-DSA-87 key so peers can post-quantum-authenticate this bundle. The v2
        // transcript binds our classical identity key, our pq_ik, the EC signed
        // pre-key, and — via the KEM binding — every ML-KEM pre-key in this bundle
        // (kem-spk + all one-time kem-pk), computed over exactly the serialized
        // bytes IqGenerator publishes (see PqBundle / pq_bundle_transcript).
        final IdentityKey ownIdentityKey = axolotlStore.getIdentityKeyPair().getPublicKey();
        final PqIdentityKeyPair ownPq = getOwnPqIdentityKeyPair();
        final byte[] pqIdentityKey = ownPq.getPublicKey().serialize();
        final byte[] pqSignature;
        try {
            final byte[] kemBinding = computeOmemo2KemBinding(
                    kyberSignedPreKeyRecord, kyberPreKeyRecords);
            final byte[] pqTranscript = PqBundle.transcript(
                    ownIdentityKey,
                    pqIdentityKey,
                    signedPreKeyRecord.getId(),
                    signedPreKeyRecord.getKeyPair().getPublicKey(),
                    kemBinding);
            pqSignature = ownPq.sign(pqTranscript);
        } catch (final InvalidKeyException e) {
            Log.e(Config.LOGTAG, getLogprefix(account)
                    + "could not build/sign PQ bundle transcript: " + e.getMessage());
            return;
        }
        final Iq publish = mXmppConnectionService.getIqGenerator().publishOmemo2Bundles(
                signedPreKeyRecord, ownIdentityKey,
                preKeyRecords, kyberSignedPreKeyRecord, kyberPreKeyRecords,
                pqIdentityKey, pqSignature, getOwnDeviceId(), publishOptions);
        mXmppConnectionService.sendIqPacket(account, publish, response -> {
            final boolean preconditionNotMet = PublishOptions.preconditionNotMet(response);
            if (firstAttempt && preconditionNotMet) {
                mXmppConnectionService.pushNodeConfiguration(account,
                        PEP_OMEMO2_BUNDLES, publishOptions,
                        new XmppConnectionService.OnConfigurationPushed() {
                            @Override
                            public void onPushSucceeded() {
                                publishOmemo2Bundle(signedPreKeyRecord, preKeyRecords, kyberSignedPreKeyRecord, kyberPreKeyRecords, false);
                            }
                            @Override
                            public void onPushFailed() {
                                publishOmemo2Bundle(signedPreKeyRecord, preKeyRecords, kyberSignedPreKeyRecord, kyberPreKeyRecords, false);
                            }
                        });
            } else if (response.getType() == Iq.Type.RESULT) {
                Log.d(Config.LOGTAG, getLogprefix(account) + "Successfully published OMEMO2 bundle.");
                publishOmemo2DeviceId();
            } else if (response.getType() == Iq.Type.ERROR) {
                Log.d(Config.LOGTAG, getLogprefix(account) + "Error publishing OMEMO2 bundle: " + response);
            }
        });
    }

    private void publishOmemo2DeviceId() {
        final XmppConnection connection = account.getXmppConnection();
        if (connection == null) {
            return;
        }
        final Bundle publishOptions = connection.getFeatures().pepPublishOptions()
                ? PublishOptions.openAccess() : null;
        final Iq packet = mXmppConnectionService.getIqGenerator()
                .retrieveOmemo2DeviceIds(account.getJid().asBareJid());
        mXmppConnectionService.sendIqPacket(account, packet, response -> {
            final Set<Integer> deviceIds;
            if (response.getType() == Iq.Type.RESULT) {
                final Element item = IqParser.getItem(response);
                deviceIds = IqParser.omemo2DeviceIds(item);
            } else {
                deviceIds = new HashSet<>();
            }
            deviceIds.add(getOwnDeviceId());
            publishOmemo2DeviceIds(deviceIds);
        });
    }

    /**
     * Publishes an EXPLICIT OMEMO2 device list. Unlike {@link #publishOmemo2DeviceId()},
     * which re-reads the node first and therefore can only ever grow it, this keeps a
     * caller-computed set — required whenever devices are being REMOVED (auto-expiry,
     * manual purge, "clear devices"), where re-reading would resurrect exactly the ids we
     * are dropping. This device's own id is always kept.
     */
    private void publishOmemo2DeviceIds(final Set<Integer> ids) {
        final XmppConnection connection = account.getXmppConnection();
        if (connection == null) {
            return;
        }
        final Bundle publishOptions = connection.getFeatures().pepPublishOptions()
                ? PublishOptions.openAccess() : null;
        final Set<Integer> deviceIds = new HashSet<>(ids);
        deviceIds.add(getOwnDeviceId());
        final Iq publish = mXmppConnectionService.getIqGenerator()
                .publishOmemo2DeviceIds(deviceIds, publishOptions);
        mXmppConnectionService.sendIqPacket(account, publish, r -> {
            if (r.getType() == Iq.Type.RESULT) {
                Log.d(Config.LOGTAG, getLogprefix(account) + "Published OMEMO2 device IDs " + deviceIds);
            } else if (PublishOptions.preconditionNotMet(r)) {
                mXmppConnectionService.pushNodeConfiguration(account,
                        PEP_OMEMO2_DEVICE_LIST, publishOptions,
                        new XmppConnectionService.OnConfigurationPushed() {
                            @Override public void onPushSucceeded() {
                                final Iq retry = mXmppConnectionService.getIqGenerator()
                                        .publishOmemo2DeviceIds(deviceIds, publishOptions);
                                mXmppConnectionService.sendIqPacket(account, retry, null);
                            }
                            @Override public void onPushFailed() {}
                        });
            }
        });
    }

    /** Register OMEMO2 device IDs received via PEP notification. */
    public void registerOmemo2Devices(final Jid jid, final Set<Integer> ids) {
        // Only when the device list actually CHANGED (e.g. the peer just migrated
        // to PQ OMEMO2 and published their first bundle, or added/removed a
        // device) do we clear stale FetchStatus.ERROR so the next send retries the
        // bundle fetch. Clearing on EVERY notification would repeatedly resurrect
        // genuinely-unbuildable devices (e.g. an own legacy-only device) into the
        // "without session" set, which made the Trust screen reopen in a loop even
        // when both peers are on PQ OMEMO2 and have accepted each other's keys.
        final Set<Integer> known = this.omemo2DeviceIds.get(jid);
        if (known == null || !known.equals(ids)) {
            clearErrorsInFetchStatusMap(jid);
            // Also clear a stale device-list fetch error (set by
            // fetchOmemo2DeviceIds when a previous fetch returned empty/failed),
            // so a peer migrating to PQ OMEMO2 recovers automatically: the trust
            // guard stops failing closed once a non-empty list is known.
            if (!ids.isEmpty()) {
                omemo2FetchDeviceListStatus.remove(jid);
            }
        }
        // Store in the OMEMO2 device-id map so OMEMO2 sessions can be built for
        // these devices, strictly separate from the legacy device list.
        registerDevices(jid, ids, true);
    }

    // --- OMEMO2 encryption ---

    @Nullable
    public XmppOmemo2Message encryptOmemo2(final Message message) {
        final Conversation conversation = (Conversation) message.getConversation();
        final boolean isMuc = conversation.getMode() == Conversation.MODE_MULTI;
        final Jid toJid = isMuc ? conversation.getJid().asBareJid() : message.getCounterpart();

        final boolean isRetraction = message.isDeleted() && message.getRetractId() != null;
        // For a file message with a caption we emit, inside the encrypted SCE envelope,
        // the same body+OOB+fallback shape the plaintext path uses (MessageGenerator
        // generateChat): SCE <body> = caption + url, an <x xmlns='jabber:x:oob'><url>
        // element, and a <fallback for='oob'> marking the url span so the receiver strips
        // it for display. These two elements are collected here and added to extraContent
        // below. Without a caption we keep body = url (byte-identical to before).
        final String content;
        Element fileOob = null;
        final List<Element> fileFallbacks = new ArrayList<>();
        final List<Element> fileSharing = new ArrayList<>();
        if (isRetraction) {
            // A fallback body so the SCE envelope is a real content message (not a no-body
            // stanza that gets dropped); clients that don't grok <retract> still see this.
            content = "This message has been retracted by the sender.";
        } else if (message.hasFileOnRemoteHost()) {
            final String url = message.getFileParams().url;
            final String caption = message.getRawBody();
            // XEP-0447 description of every file of this message, carried inside the encrypted
            // envelope. The <url-data> source holds the aesgcm URL whose fragment is the file
            // key, and the metadata (name, size, hashes) describes the plaintext file — none of
            // it may appear on the outer stanza, so it is built here, not in MessageGenerator.
            fileSharing.addAll(
                    eu.siacs.conversations.generator.MessageGenerator.fileSharingElements(message));
            final boolean hasCaption =
                    caption != null && !caption.isEmpty() && !caption.equals(url);
            if (hasCaption || message.hasAttachments()) {
                // Emit body = caption + every file URL with an OOB <url> for the first file and
                // a <fallback> marking each URL span (XEP-0066/0428), all inside SCE, so that
                // peers without XEP-0447 still receive every file as a link.
                final StringBuilder body = new StringBuilder(hasCaption ? caption : "");
                for (final Message file : message.getFileMessages()) {
                    final Message.FileParams params = file.getFileParams();
                    if (params == null || params.url == null) continue;
                    final String separator =
                            body.length() == 0 || body.charAt(body.length() - 1) == ' ' ? "" : "\n";
                    final int start = body.codePointCount(0, body.length());
                    final String appended = separator + params.url;
                    body.append(appended);
                    fileFallbacks.addAll(
                            eu.siacs.conversations.generator.MessageGenerator.fileFallbacks(
                                    start, start + appended.codePointCount(0, appended.length())));
                }
                content = body.toString();
                fileOob = new Element("x", eu.siacs.conversations.xml.Namespace.OOB);
                fileOob.addChild("url").setContent(url);
            } else {
                // Single file, no caption: body = url, byte-identical to the previous wire
                // format (no OOB/fallback) so unchanged peers keep working exactly as before.
                content = url;
            }
        } else {
            content = message.getRawBody();
        }

        // Collect all SCE content elements per XEP-0420 / XEP-0384
        final List<Element> extraContent = new ArrayList<>();
        for (final Element payload : message.getPayloads()) {
            extraContent.add(payload);
        }
        extraContent.addAll(fileSharing);
        if (fileOob != null) {
            extraContent.add(fileOob);
        }
        extraContent.addAll(fileFallbacks);
        if (message.getSubject() != null && !message.getSubject().isEmpty()) {
            // Explicit jabber:client namespace (like <body>/<thread>) — without it
            // the serialized element would inherit the SCE envelope namespace.
            // Receivers match by local name, so both forms decode, but the wire
            // format should be unambiguous.
            final Element subject = new Element("subject", "jabber:client");
            subject.setContent(message.getSubject());
            extraContent.add(subject);
        }
        if (message.edited() && !message.isDeleted()) {
            final Element replace = new Element("replace", "urn:xmpp:message-correct:0");
            replace.setAttribute("id", message.getEditedIdWireFormat());
            extraContent.add(replace);
        }
        // XEP-0424 retraction inside the encrypted SCE content (mirrors the cleartext
        // outer-stanza form built in MessageGenerator for unencrypted chats).
        if (isRetraction) {
            final Element retract = new Element("retract", "urn:xmpp:message-retract:1");
            retract.setAttribute("id", message.getRetractId());
            extraContent.add(retract);
            final Element fallback = new Element("fallback", "urn:xmpp:fallback:0");
            fallback.setAttribute("for", "urn:xmpp:message-retract:1");
            extraContent.add(fallback);
        }
        if (message.getEphemeralTimer() > 0) {
            final Element ephemeral = new Element("ephemeral", eu.siacs.conversations.xml.Namespace.EPHEMERAL);
            ephemeral.setAttribute("timer", String.valueOf(message.getEphemeralTimer()));
            extraContent.add(ephemeral);
        }
        if (message.isEphemeralIWantOut()) {
            extraContent.add(new Element("i-want-out", eu.siacs.conversations.xml.Namespace.EPHEMERAL));
        }

        final XmppOmemo2Message omemo2Message = new XmppOmemo2Message(
                account.getJid().asBareJid(), getOwnDeviceId());
        try {
            omemo2Message.encrypt(content, extraContent.isEmpty() ? null : extraContent, toJid, isMuc);
        } catch (final CryptoFailedException e) {
            Log.w(Config.LOGTAG, getLogprefix(account) + "OMEMO2 encrypt failed: " + e.getMessage());
            return null;
        }

        if (message.isPrivateMessage()) {
            return buildOmemo2Header(omemo2Message, message.getTrueCounterpart()) ? omemo2Message : null;
        } else {
            return buildOmemo2Header(omemo2Message, conversation) ? omemo2Message : null;
        }
    }

    private boolean buildOmemo2Header(final XmppOmemo2Message message, final Conversation c) {
        final Set<XmppAxolotlSession> remoteSessions = findSessionsForConversation(c);
        final boolean acceptEmpty = (c.getMode() == Conversation.MODE_MULTI
                && c.getMucOptions().getUserCount() == 0) || c.getContact().isSelf();
        final Collection<XmppAxolotlSession> ownSessions = findOwnSessions();
        if (remoteSessions.isEmpty() && !acceptEmpty) return false;
        // Count what was actually wrapped, not what we tried to wrap: addDevice
        // silently skips sessions that are not trusted-and-active. Without this
        // check a conversation whose peer devices all became untrusted/inactive
        // between the trust gate and the send produced a message readable only
        // by our own devices — and reported it as sent, while the peer saw
        // "not encrypted for this device".
        boolean addedRemote = false;
        for (final XmppAxolotlSession session : remoteSessions) {
            addedRemote |= message.addDevice(session);
        }
        for (final XmppAxolotlSession session : ownSessions) {
            message.addDevice(session);
        }
        // All per-device wraps done — the raw message key is no longer needed and
        // must not linger in memory (the built message may sit in the resend cache).
        message.wipeMessageKey();
        if (!addedRemote && !acceptEmpty) {
            Log.w(Config.LOGTAG, getLogprefix(account)
                    + "no trusted and active OMEMO2 recipient device for " + c.getJid().asBareJid()
                    + " — refusing to send");
            return false;
        }
        return true;
    }

    private boolean buildOmemo2Header(final XmppOmemo2Message message, final Jid jid) {
        if (jid == null) return false;
        final Set<XmppAxolotlSession> sessions = new HashSet<>(
                this.sessions.getAll(getAddressForJid(jid).getName()).values());
        if (sessions.isEmpty()) return false;
        boolean addedRemote = false;
        for (final XmppAxolotlSession session : sessions) {
            addedRemote |= message.addDevice(session);
        }
        for (final XmppAxolotlSession session : findOwnSessions()) {
            message.addDevice(session);
        }
        message.wipeMessageKey();
        if (!addedRemote) {
            Log.w(Config.LOGTAG, getLogprefix(account)
                    + "no trusted and active OMEMO2 device for " + jid.asBareJid()
                    + " — refusing to send private message");
            return false;
        }
        return true;
    }

    public void prepareOmemo2PayloadMessage(final Message message, final boolean delay) {
        executor.execute(() -> {
            final XmppOmemo2Message omemo2Message = encryptOmemo2(message);
            if (omemo2Message == null) {
                mXmppConnectionService.markMessage(message, Message.STATUS_SEND_FAILED);
            } else {
                Log.d(Config.LOGTAG, getLogprefix(account) + "Generated OMEMO2 message, caching: " + message.getUuid());
                omemo2MessageCache.put(message.getUuid(), omemo2Message);
                mXmppConnectionService.resendMessage(message, delay, true);
            }
        });
    }

    @Nullable
    public XmppOmemo2Message fetchOmemo2MessageFromCache(final Message message) {
        final XmppOmemo2Message cached = omemo2MessageCache.get(message.getUuid());
        if (cached != null) {
            omemo2MessageCache.remove(message.getUuid());
        }
        return cached;
    }

    /**
     * Encrypt a set of SCE content elements (no body) for OMEMO2. Used for live location
     * updates, stop signals, and similar metadata-only stanzas.
     */
    @Nullable
    public XmppOmemo2Message encryptOmemo2ContentElements(
            final java.util.List<eu.siacs.conversations.xml.Element> contentElements,
            final Conversation conversation) {
        final boolean isMuc = conversation.getMode() == Conversation.MODE_MULTI;
        final Jid toJid = isMuc ? conversation.getJid().asBareJid() : conversation.getJid();
        final XmppOmemo2Message omemo2Message = new XmppOmemo2Message(
                account.getJid().asBareJid(), getOwnDeviceId());
        try {
            omemo2Message.encrypt(null, contentElements, toJid, isMuc);
        } catch (final CryptoFailedException e) {
            Log.w(Config.LOGTAG, getLogprefix(account) + "OMEMO2 content-elements encrypt failed: " + e.getMessage());
            return null;
        }
        return buildOmemo2Header(omemo2Message, conversation) ? omemo2Message : null;
    }

    /**
     * Encrypt {@code contentElements} into an OMEMO2 SCE payload and attach it to
     * {@code basePacket}, then send. Runs on the axolotl executor thread.
     */
    public void sendOmemo2Packet(
            final Conversation conversation,
            final im.conversations.android.xmpp.model.stanza.Message basePacket,
            final java.util.List<eu.siacs.conversations.xml.Element> contentElements) {
        executor.execute(() -> {
            final XmppOmemo2Message omemo2Message =
                    encryptOmemo2ContentElements(contentElements, conversation);
            if (omemo2Message == null) {
                Log.w(Config.LOGTAG, getLogprefix(account) + "Failed to encrypt OMEMO2 packet — dropping");
                return;
            }
            basePacket.setAxolotlMessage(omemo2Message.toElement());
            basePacket.addChild("encryption", "urn:xmpp:eme:0")
                    .setAttribute("name", "PQ-OMEMO2")
                    .setAttribute("namespace", eu.siacs.conversations.xml.Namespace.OMEMO2);
            mXmppConnectionService.sendMessagePacket(account, basePacket);
        });
    }

    // --- OMEMO2 decryption ---

    public XmppOmemo2Message.DecryptedSce processReceivingOmemo2PayloadMessage(
            final XmppOmemo2Message message, final boolean postponePreKeyMessageHandling,
            final Jid expectedTo, final Long stanzaTimestamp)
            throws CryptoFailedException {

        final SignalProtocolAddress senderAddress = new SignalProtocolAddress(
                message.getFrom().toString(), message.getSenderDeviceId());
        final XmppAxolotlSession session = getReceivingSession(senderAddress);
        final int ownDeviceId = getOwnDeviceId();

        XmppOmemo2Message.DecryptedSce decrypted = null;
        try {
            decrypted = message.decrypt(session, ownDeviceId, account.getJid().asBareJid(), expectedTo, stanzaTimestamp);
            final Integer preKeyId = session.getPreKeyIdAndReset();
            if (preKeyId != null) {
                // PQ OMEMO2 payload: complete on the OMEMO2 stack.
                postPreKeyMessageHandling(session, postponePreKeyMessageHandling);
            }
        } catch (final NotEncryptedForThisDeviceException e) {
            if (account.getJid().asBareJid().equals(message.getFrom().asBareJid())
                    && message.getSenderDeviceId() == ownDeviceId) {
                Log.w(Config.LOGTAG, getLogprefix(account) + "Reflected OMEMO2 message received");
            } else {
                throw e;
            }
        } catch (final CryptoFailedException e) {
            // GCM auth failure, SCE binding mismatch, malformed envelope, …
            // Propagate instead of swallowing: the caller decides whether this
            // was a content message that must surface a visible decryption-failed
            // placeholder — silently dropping would hide an active attack (or a
            // bug) from the user entirely.
            Log.w(Config.LOGTAG, getLogprefix(account) + "OMEMO2 decrypt failed from " + message.getFrom(), e);
            throw e;
        }

        if (decrypted != null) {
            maybeSendOmemo2Heartbeat(session, senderAddress);
            // Peer-initiated sessions never delivered the peer's pq_ik (it only
            // travels in the published bundle) — pin it now if it is missing, so
            // the hybrid fingerprint can be displayed.
            reconcileOmemo2PqPinIfMissing(senderAddress, session);
        }

        if (session.isFresh() && decrypted != null) {
            putFreshSession(session);
        }
        return decrypted;
    }
}
