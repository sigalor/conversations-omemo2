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
    // Lazily created when the global legacy-OMEMO flag is enabled. Kept null
    // otherwise so the old-libsignal stack contributes nothing at runtime when
    // the user hasn't opted in.
    private volatile eu.siacs.conversations.crypto.axolotl.legacy.LegacyAxolotlBackend legacyBackend = null;
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
    private Map<Jid, Boolean> deviceListStatus(final boolean isOmemo2) {
        return isOmemo2 ? this.omemo2FetchDeviceListStatus : this.fetchDeviceListStatus;
    }

    private static boolean isOmemo2(final int encryption) {
        return encryption == Message.ENCRYPTION_AXOLOTL_OMEMO2;
    }

    private boolean hasErrorFetchingDeviceList(final Jid jid, final boolean isOmemo2) {
        Boolean status = deviceListStatus(isOmemo2).get(jid);
        return status != null && !status;
    }

    public boolean hasErrorFetchingDeviceList(final List<Jid> jids, final int encryption) {
        for (Jid jid : jids) {
            if (hasErrorFetchingDeviceList(jid, isOmemo2(encryption))) {
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
        final boolean isOmemo2 = isOmemo2(encryption);
        for (Jid jid : jids) {
            final Set<Integer> ids = getDeviceIdsForStack(jid, isOmemo2);
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
        return hasVerifiedLegacyKeys(name);
    }

    /**
     * Same question for the legacy (XEP-0384 v0.3) stack, whose sessions live in a
     * separate store and therefore never show up in {@link #sessions}. Not gated on
     * the "legacy OMEMO enabled" setting: a verification the user performed stays a
     * verification even while the stack it belongs to is switched off, and this is
     * the fail-closed direction.
     */
    private boolean hasVerifiedLegacyKeys(final String bareJid) {
        final List<Integer> deviceIds =
                mXmppConnectionService.databaseBackend.getLegacySubDeviceSessions(account, bareJid);
        for (final Integer deviceId : deviceIds) {
            final String fingerprint = legacyFingerprintFromSession(bareJid, deviceId);
            if (fingerprint != null && getFingerprintTrust(fingerprint).isVerified()) {
                return true;
            }
        }
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
     */
    public String hybridFingerprintFor(final String classicalFingerprint) {
        if (classicalFingerprint == null) return null;
        final byte[] pqIk = mXmppConnectionService.databaseBackend
                .getPinnedOmemo2PqIdentity(account, classicalFingerprint);
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
        final String bareJid = jid.asBareJid().toString();
        final List<Integer> deviceIds;
        if (encryptionType == Message.ENCRYPTION_AXOLOTL_OMEMO2) {
            deviceIds = mXmppConnectionService.databaseBackend.getOmemo2SubDeviceSessions(account, bareJid);
        } else if (encryptionType == Message.ENCRYPTION_AXOLOTL) {
            deviceIds = mXmppConnectionService.databaseBackend.getLegacySubDeviceSessions(account, bareJid);
        } else {
            return Collections.emptySet();
        }
        final Set<String> fingerprints = new HashSet<>();
        for (Integer deviceId : deviceIds) {
            final String fingerprint;
            if (encryptionType == Message.ENCRYPTION_AXOLOTL_OMEMO2) {
                final var session = sessions.get(new SignalProtocolAddress(bareJid, deviceId));
                fingerprint = session != null ? session.getFingerprint() : null;
            } else {
                fingerprint = getLegacyFingerprint(bareJid, deviceId);
            }
            if (fingerprint != null) {
                fingerprints.add(fingerprint);
            }
        }
        return fingerprints;
    }

    public long getNumTrustedKeys(Jid jid, int encryption) {
        final Set<String> stackFingerprints = getFingerprintsForStack(jid, encryption);
        int count = 0;
        for (String fingerprint : stackFingerprints) {
            if (getFingerprintTrust(fingerprint).isTrustedAndActive()) {
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

    @Nullable
    private String getLegacyFingerprint(String bareJid, int deviceId) {
        final var legacy = getLegacyBackend();
        if (legacy == null) return null;
        return legacyFingerprintFromSession(bareJid, deviceId);
    }

    /**
     * The peer identity key pinned in the stored legacy SessionRecord, without the
     * "is legacy OMEMO enabled" gate of {@link #getLegacyFingerprint(String, int)}:
     * reading what was verified in the past does not depend on the stack being in
     * use right now. Callers that surface legacy keys in the UI use the gated
     * variant instead.
     */
    @Nullable
    private String legacyFingerprintFromSession(String bareJid, int deviceId) {
        final var bytes = mXmppConnectionService.databaseBackend.loadLegacySessionBytes(account, bareJid, deviceId);
        if (bytes == null) return null;
        try {
            final var record = new org.whispersystems.libsignal.state.SessionRecord(bytes);
            final var identityKey = record.getSessionState().getRemoteIdentityKey();
            return identityKey == null ? null : CryptoHelper.bytesToHex(identityKey.getPublicKey().serialize());
        } catch (Exception e) {
            return null;
        }
    }

    public List<LegacySessionInfo> findLegacySessionsForContact(Contact contact) {
        // Strict stack separation: when legacy OMEMO is disabled (e.g. the user
        // chose "PQ OMEMO2 only"), never surface stale legacy fingerprints in the
        // trust UI. Mirrors getLegacyBackend(), which returns null when disabled.
        if (!mXmppConnectionService.getAppSettings().isLegacyOmemoEnabled()) {
            return Collections.emptyList();
        }
        final String bareJid = contact.getJid().asBareJid().toString();
        final List<Integer> deviceIds = mXmppConnectionService.databaseBackend.getLegacySubDeviceSessions(account, bareJid);
        final List<LegacySessionInfo> out = new ArrayList<>();
        for (Integer deviceId : deviceIds) {
            final String fingerprint = getLegacyFingerprint(bareJid, deviceId);
            if (fingerprint != null) {
                out.add(new LegacySessionInfo(fingerprint, getFingerprintTrust(fingerprint), deviceId));
            }
        }
        return out;
    }

    /**
     * This device's OWN legacy (XEP-0384 v0.3) identity-key fingerprint, in the
     * same full hex form as {@link #getOwnFingerprint()} (leading DJB "05" type
     * byte included). Returns {@code null} when legacy OMEMO is disabled — the
     * legacy stack keeps a SEPARATE identity key from PQ OMEMO2, so this differs
     * from the OMEMO2 fingerprint. Never merged with the OMEMO2 fingerprint;
     * shown alongside it so peers can verify the legacy key too.
     */
    @Nullable
    public String getOwnLegacyFingerprint() {
        final var legacy = getLegacyBackend();
        if (legacy == null) return null;
        try {
            return CryptoHelper.bytesToHex(
                    legacy.getStore().getIdentityKeyPair().getPublicKey().serialize());
        } catch (final RuntimeException e) {
            return null;
        }
    }

    public List<LegacySessionInfo> findOwnLegacySessions() {
        if (!mXmppConnectionService.getAppSettings().isLegacyOmemoEnabled()) {
            return Collections.emptyList();
        }
        final String bareJid = account.getJid().asBareJid().toString();
        final List<Integer> deviceIds = mXmppConnectionService.databaseBackend.getLegacySubDeviceSessions(account, bareJid);
        final List<LegacySessionInfo> out = new ArrayList<>();
        for (Integer deviceId : deviceIds) {
            if (deviceId == getOwnDeviceId()) continue;
            final String fingerprint = getLegacyFingerprint(bareJid, deviceId);
            if (fingerprint != null) {
                out.add(new LegacySessionInfo(fingerprint, getFingerprintTrust(fingerprint), deviceId));
            }
        }
        return out;
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
     * when {@code isOmemo2}, otherwise the legacy map. May be null.
     */
    private Set<Integer> getDeviceIdsForStack(final Jid jid, final boolean isOmemo2) {
        return (isOmemo2 ? this.omemo2DeviceIds : this.deviceIds).get(jid);
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

    public void registerDevices(final Jid jid, @NonNull final Set<Integer> deviceIds, final boolean isOmemo2) {
        // A non-empty list clears a previously recorded "no devices on this
        // stack" (set by the device-id fetches when the list came back empty or
        // the request failed), so a contact who starts publishing devices — or
        // migrates between stacks — recovers without a restart. Only the stack
        // the list belongs to is touched; the other one keeps its own outcome.
        // registerOmemo2Devices() does the same before delegating here, for the
        // OMEMO2 PEP notification path.
        if (!isOmemo2 && !deviceIds.isEmpty()) {
            fetchDeviceListStatus.remove(jid);
        }
        final int hash = deviceIds.hashCode();
        final boolean me = jid.asBareJid().equals(account.getJid().asBareJid());
        if (me) {
            final int lastHash = isOmemo2
                    ? this.lastOmemo2DeviceListNotificationHash
                    : this.lastDeviceListNotificationHash;
            if (hash != 0 && hash == lastHash) {
                Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": ignoring duplicate own device id list");
                return;
            }
            if (isOmemo2) {
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
        // cache and store only (the legacy stack keeps its sessions in separate
        // tables and tracks no such state here). Running it for a legacy device
        // list would wrongly deactivate OMEMO2 sessions whose device IDs happen
        // to be absent from the legacy list.
        if (isOmemo2) {
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
            if (isOmemo2 && mXmppConnectionService.getOmemoAutoExpiry() != 0) {
                needsPublishing |= deviceIds.removeAll(getExpiredDevices());
            }
            needsPublishing |= this.changeAccessMode.get();
            for (final Integer deviceId : deviceIds) {
                SignalProtocolAddress ownDeviceAddress = new SignalProtocolAddress(jid.asBareJid().toString(), deviceId);
                if (isOmemo2) {
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
                if (isOmemo2) {
                    this.lastOmemo2DeviceListNotificationHash = 0;
                    publishOmemo2DeviceId();
                } else {
                    this.lastDeviceListNotificationHash = 0;
                    publishOwnDeviceId(deviceIds);
                }
            }
        }
        final Map<Jid, Set<Integer>> target = isOmemo2 ? this.omemo2DeviceIds : this.deviceIds;
        final Set<Integer> oldSet = target.get(jid);
        final boolean changed = oldSet == null || oldSet.hashCode() != hash;
        target.put(jid, deviceIds);
        if (isOmemo2 && !deviceIds.isEmpty()) {
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
     * One-way rollout upgrade: move a chat off legacy OMEMO as soon as everyone
     * in it announces OMEMO2 devices. Only chats that are on legacy because of
     * the global default stack
     * ({@link eu.siacs.conversations.AppSettings#OMEMO_DEFAULT_LEGACY}) or
     * because they predate PQ OMEMO2 are touched — an explicit per-chat legacy
     * choice ({@link Conversation#ATTRIBUTE_ALLOW_LEGACY_OMEMO}) is never
     * overridden, and nothing here ever moves a chat back to legacy. Without
     * this, a legacy default would be sticky forever and chats would stay on
     * the pre-PQ stack long after both sides could do OMEMO2.
     *
     * <p>Called whenever a non-empty OMEMO2 device list is registered for
     * {@code bare} (and when a chat is opened), so the upgrade lands as soon as
     * the last participant becomes OMEMO2-capable.
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
     * Single-conversation half of {@link #upgradeLegacyConversationsToOmemo2}.
     * Public so the chat UI can re-evaluate when a conversation is opened: the
     * OMEMO2 device list may have been registered long before this chat existed
     * or was last looked at, in which case there is no device-list event left
     * to react to.
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
        publishDeviceIdsAndRefineAccessModel(deviceIds);
    }

    public void distrustFingerprint(final String fingerprint) {
        final String fp = fingerprint.replaceAll("\\s", "");
        final FingerprintStatus fingerprintStatus = axolotlStore.getFingerprintStatus(fp);
        axolotlStore.setFingerprintStatus(fp, fingerprintStatus.toUntrusted());
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
                    // The OMEMO2 bundle is current, so publishDeviceBundle() —
                    // which is what normally carries the legacy bundle along —
                    // does not run. Make sure legacy has been published at least
                    // once anyway.
                    publishLegacyBundleIfNeverPublished();
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
        // Historically we published our PQ-stack keys to the legacy
        // PEP_BUNDLES node. That was misleading: legacy v0.3 peers can't
        // actually open a session with us because new-libsignal cannot process
        // a v0.3 PreKeySignalMessage. We always publish the PQ OMEMO2 bundle
        // here, and separately publish a real legacy-stack bundle to
        // PEP_BUNDLES iff the user has opted in to legacy OMEMO globally.
        Log.d(Config.LOGTAG, AxolotlService.getLogprefix(account)
                + ": Publishing OMEMO2 bundle for " + getOwnDeviceId());
        publishOmemo2BundlesIfNeeded(signedPreKeyRecord, preKeyRecords);
        publishLegacyBundleIfNeeded(true);
        if (wipe) {
            wipeOtherPepDevices();
        } else if (announceAfter) {
            publishOwnDeviceIdIfNeeded();
        }
    }

    /**
     * Publish a legacy XEP-0384 v0.3 bundle to {@link #PEP_BUNDLES}. Generates a
     * fresh legacy-stack signed prekey and a batch of one-time prekeys (stored
     * separately from the PQ stack) on first call, then republishes whenever
     * triggered. No-op when the global legacy-OMEMO flag is disabled.
     */
    /**
     * Publish the legacy (XEP-0384 v0.3) bundle right away — e.g. immediately
     * after the user opts into legacy OMEMO from the first-run prompt — so peers
     * on older/other clients can reach this device without waiting for the next
     * reconnect. No-op when the global legacy-OMEMO flag is disabled.
     */
    public void publishLegacyBundleNow() {
        if (getLegacyBackend() == null) return;
        publishLegacyBundleIfNeeded(true);
    }

    /**
     * Publish the legacy bundle if this account has never had one accepted by
     * PEP. Legacy OMEMO is available by default, but the legacy bundle only
     * rides along with an OMEMO2 bundle publish — and an account whose OMEMO2
     * bundle is already current does not publish anything on connect. Without
     * this, an existing install would keep announcing OMEMO2-only forever and
     * legacy peers could never start a session with it.
     */
    private void publishLegacyBundleIfNeverPublished() {
        if (getLegacyBackend() == null) return;
        if (account.getKey(SQLiteAxolotlStore.JSONKEY_LEGACY_BUNDLE_PUBLISHED) != null) {
            return;
        }
        Log.d(Config.LOGTAG, getLogprefix(account)
                + "no legacy v0.3 bundle published yet — publishing one now");
        publishLegacyBundleIfNeeded(true);
    }

    private void publishLegacyBundleIfNeeded(final boolean firstAttempt) {
        final var legacy = getLegacyBackend();
        if (legacy == null) return; // feature disabled
        final XmppConnection connection = account.getXmppConnection();
        if (connection == null) {
            return;
        }
        mXmppConnectionService.databaseBackend.ensureLegacyOmemoTablesExist();
        final org.whispersystems.libsignal.state.SignedPreKeyRecord legacySpk;
        final java.util.List<org.whispersystems.libsignal.state.PreKeyRecord> legacyPreKeys;
        try {
            // Track the next free prekey ID across re-publishes so we never
            // overwrite a prekey a peer is currently using. The counter lives
            // in account JSON, mirroring the OMEMO2/kyber pattern.
            int curId = 0;
            try {
                curId = Integer.parseInt(
                        account.getKey(SQLiteAxolotlStore.JSONKEY_CURRENT_LEGACY_PREKEY_ID));
            } catch (final NumberFormatException ignored) {
            }
            if (curId == 0) {
                // First legacy publish on this account. On a pre-PQ -> PQ upgrade
                // the legacy stack reuses the ORIGINAL prekeys / signed_prekeys
                // tables, which already hold the user's pre-PQ legacy keys (IDs
                // 1..N, tracked by JSONKEY_CURRENT_PREKEY_ID before the upgrade).
                // Starting the legacy counter back at 1 would regenerate IDs that
                // collide with — and ON CONFLICT REPLACE overwrite — those still
                // in-use prekeys, breaking decryption of in-flight legacy
                // handshakes. Seed from the pre-PQ high-water mark so new legacy
                // keys get fresh, non-colliding IDs and the old ones survive.
                try {
                    curId = Integer.parseInt(
                            account.getKey(SQLiteAxolotlStore.JSONKEY_CURRENT_PREKEY_ID));
                } catch (final NumberFormatException ignored) {
                }
            }
            final int spkId = curId <= 0 ? 1 : curId + 1;
            legacySpk = legacy.generateSignedPreKey(spkId);
            legacyPreKeys = legacy.generatePreKeyBatch(spkId + 1, NUM_KEYS_TO_PUBLISH);
            account.setKey(SQLiteAxolotlStore.JSONKEY_CURRENT_LEGACY_PREKEY_ID,
                    Integer.toString(spkId + NUM_KEYS_TO_PUBLISH));
            mXmppConnectionService.databaseBackend.updateAccount(account);
        } catch (final RuntimeException e) {
            Log.w(Config.LOGTAG, getLogprefix(account)
                    + "could not generate legacy keys: " + e.getMessage());
            return;
        }
        final Bundle publishOptions = connection.getFeatures().pepPublishOptions()
                ? PublishOptions.openAccess() : null;
        final org.whispersystems.libsignal.IdentityKey legacyIk =
                legacy.getStore().getIdentityKeyPair().getPublicKey();
        final java.util.Set<org.whispersystems.libsignal.state.PreKeyRecord> set =
                new java.util.HashSet<>(legacyPreKeys);
        final Iq publish = mXmppConnectionService.getIqGenerator()
                .publishLegacyBundles(legacySpk, legacyIk, set, getOwnDeviceId(), publishOptions);
        Log.d(Config.LOGTAG, getLogprefix(account)
                + "publishing legacy v0.3 bundle for device " + getOwnDeviceId());
        mXmppConnectionService.sendIqPacket(account, publish, response -> {
            final boolean preconditionNotMet = PublishOptions.preconditionNotMet(response);
            if (firstAttempt && preconditionNotMet) {
                final String node = PEP_BUNDLES + ":" + getOwnDeviceId();
                mXmppConnectionService.pushNodeConfiguration(account, node, publishOptions,
                        new XmppConnectionService.OnConfigurationPushed() {
                            @Override public void onPushSucceeded() {
                                publishLegacyBundleIfNeeded(false);
                            }
                            @Override public void onPushFailed() {
                                publishLegacyBundleIfNeeded(false);
                            }
                        });
            } else if (response.getType() == Iq.Type.RESULT) {
                Log.d(Config.LOGTAG, getLogprefix(account) + "legacy bundle published");
                if (account.setKey(SQLiteAxolotlStore.JSONKEY_LEGACY_BUNDLE_PUBLISHED, "true")) {
                    mXmppConnectionService.databaseBackend.updateAccount(account);
                }
            } else {
                Log.w(Config.LOGTAG, getLogprefix(account)
                        + "legacy bundle publish failed: " + response);
            }
        });
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
     * Returns the legacy XEP-0384 v0.3 backend, creating it on demand. Returns
     * null when the global legacy-OMEMO setting is disabled; callers MUST
     * handle null and fall back to OMEMO2-only behaviour.
     */
    @Nullable
    public eu.siacs.conversations.crypto.axolotl.legacy.LegacyAxolotlBackend getLegacyBackend() {
        if (!mXmppConnectionService.getAppSettings().isLegacyOmemoEnabled()) {
            return null;
        }
        eu.siacs.conversations.crypto.axolotl.legacy.LegacyAxolotlBackend b = this.legacyBackend;
        if (b == null) {
            synchronized (this) {
                b = this.legacyBackend;
                if (b == null) {
                    b = new eu.siacs.conversations.crypto.axolotl.legacy.LegacyAxolotlBackend(
                            account, mXmppConnectionService, axolotlStore);
                    this.legacyBackend = b;
                }
            }
        }
        return b;
    }

    public FingerprintStatus getFingerprintTrust(String fingerprint) {
        final FingerprintStatus status = axolotlStore.getFingerprintStatus(fingerprint);
        return status != null ? status : FingerprintStatus.createActiveUndecided();
    }

    public X509Certificate getFingerprintCertificate(String fingerprint) {
        return axolotlStore.getFingerprintCertificate(fingerprint);
    }

    public void setFingerprintTrust(final String fingerprint, final FingerprintStatus status) {
        axolotlStore.setFingerprintStatus(fingerprint, status);
        // TODO we decided to call this after a fingerprint gets toggled to update the 'your contact
        //  is using unverified devices text'; however this means the entire screen gets redrawn
        //  after a toggle which might be annoying or cause other weird UI glitches
        mXmppConnectionService.updateAccountUi();
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
                            setFingerprintTrust(fingerprint, FingerprintStatus.createActiveVerified(true));
                            axolotlStore.setFingerprintCertificate(fingerprint, verification.first[0]);
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
    private boolean hasEmptyDeviceList(final Jid jid, final boolean isOmemo2) {
        final Set<Integer> ids = getDeviceIdsForStack(jid, isOmemo2);
        final boolean noIds = ids == null || ids.isEmpty();
        return isOmemo2 ? (!hasAny(jid) && noIds) : noIds;
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
        // buildSessionFromOmemo2PEP instead. Here we delegate to the legacy
        // backend (old-libsignal), which produces a session in legacy_sessions
        // — kept strictly separate from the primary (OMEMO2) session store.
        final SettableFuture<XmppAxolotlSession> sessionSettableFuture = SettableFuture.create();
        final var legacy = getLegacyBackend();
        if (legacy == null) {
            Log.w(Config.LOGTAG, getLogprefix(account)
                    + "legacy OMEMO disabled — cannot build session for " + address);
            fetchStatusMap.put(address, FetchStatus.ERROR);
            finishBuildingSessionsFromPEP(address);
            if (callback != null) {
                callback.onSessionBuildFailed();
            }
            sessionSettableFuture.setException(new CryptoFailedException(
                    "Legacy OMEMO is disabled in app settings"));
            return sessionSettableFuture;
        }
        buildLegacySessionFromPEP(address, callback, sessionSettableFuture);
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
                                .getPinnedOmemo2PqIdentity(account, ikFingerprint);
                        final boolean pqChanged = pinned != null
                                && !Arrays.equals(pinned, peerPq.identityKey);
                        // A changed pq_ik for a known classical identity is normally
                        // refused (it can't be swapped silently). Exception: when the
                        // classical fingerprint is already user-verified, the identity
                        // is authenticated out-of-band, so an attacker cannot MITM the
                        // session (they lack the classical private key) — accept the
                        // new pq_ik and re-pin it. This removes the first-contact
                        // pin-poisoning denial-of-service while keeping the strict TOFU
                        // lock for unverified contacts.
                        //
                        // getFingerprintTrust may return null (no trust row yet, or the
                        // identity was deleted during a manual re-exchange while the pq
                        // pin row lingered) — treat that as NOT verified so we fall into
                        // the strict refuse branch rather than NPEing here (a crash would
                        // deny session building entirely).
                        final FingerprintStatus classicalTrust = getFingerprintTrust(ikFingerprint);
                        final boolean classicalVerified =
                                classicalTrust != null && classicalTrust.isVerified();
                        if (pqChanged && !classicalVerified) {
                            Log.e(Config.LOGTAG, getLogprefix(account) + "PQ identity for "
                                    + ikFingerprint + " CHANGED — refusing OMEMO2 session (possible downgrade/MITM)");
                            preKeyBundle = null;
                        } else {
                            if (pqChanged) {
                                Log.w(Config.LOGTAG, getLogprefix(account) + "PQ identity for "
                                        + ikFingerprint + " changed, but the classical fingerprint is"
                                        + " verified — accepting and re-pinning the new pq_ik");
                            }
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
                                account, ikFingerprint, peerPq.identityKey);
                        final XmppAxolotlSession session = new XmppAxolotlSession(account, axolotlStore, localAddress, address, bundle.getIdentityKey());
                        sessions.put(address, session);
                        final FingerprintStatus fpStatus = getFingerprintTrust(CryptoHelper.bytesToHex(bundle.getIdentityKey().getPublicKey().serialize()));
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
                    } catch (UntrustedIdentityException | InvalidKeyException | CryptoFailedException e) {
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

    /**
     * Fetch the peer's legacy v0.3 bundle and build a session via the
     * old-libsignal stack. Stored entirely in the legacy session table; the
     * caller can later detect a legacy session by querying
     * {@code getLegacyBackend().hasSession(address)}.
     */
    private void buildLegacySessionFromPEP(final SignalProtocolAddress address,
            final OnSessionBuildFromPep callback,
            final SettableFuture<XmppAxolotlSession> future) {
        final var legacy = getLegacyBackend();
        if (legacy == null) {
            fetchStatusMap.put(address, FetchStatus.ERROR);
            finishBuildingSessionsFromPEP(address);
            if (callback != null) callback.onSessionBuildFailed();
            future.setException(new CryptoFailedException(
                    "legacy OMEMO disabled — cannot build session for " + address));
            return;
        }
        final Jid jid = Jid.of(address.getName());
        Log.d(Config.LOGTAG, getLogprefix(account)
                + "Falling back to legacy v0.3 bundle for " + address);
        final Iq legacyPacket = mXmppConnectionService.getIqGenerator()
                .retrieveBundlesForDevice(jid, address.getDeviceId());
        sendFetchIq(legacyPacket, response -> {
            if (response.getType() != Iq.Type.RESULT) {
                Log.d(Config.LOGTAG, getLogprefix(account)
                        + "legacy bundle fetch failed for " + address + ": " + response);
                // Unanswered (offline / no reply) is retryable, see the OMEMO2
                // counterpart in buildSessionFromOmemo2PEP.
                fetchStatusMap.put(address,
                        response.getType() == Iq.Type.TIMEOUT ? FetchStatus.TIMEOUT : FetchStatus.ERROR);
                finishBuildingSessionsFromPEP(address);
                if (callback != null) callback.onSessionBuildFailed();
                future.setException(new CryptoFailedException(
                        "legacy bundle fetch failed for " + address));
                return;
            }
            final org.whispersystems.libsignal.state.PreKeyBundle partial =
                    IqParser.legacyBundle(response);
            final Map<Integer, org.whispersystems.libsignal.ecc.ECPublicKey> preKeys =
                    IqParser.legacyPreKeyPublics(response);
            if (partial == null || preKeys.isEmpty()) {
                Log.d(Config.LOGTAG, getLogprefix(account)
                        + "legacy bundle invalid or empty for " + address);
                fetchStatusMap.put(address, FetchStatus.ERROR);
                finishBuildingSessionsFromPEP(address);
                if (callback != null) callback.onSessionBuildFailed();
                future.setException(new CryptoFailedException(
                        "legacy bundle invalid for " + address));
                return;
            }
            final List<Integer> ids = new ArrayList<>(preKeys.keySet());
            final int chosenPkId = ids.get(SECURE_RANDOM.nextInt(ids.size()));
            final org.whispersystems.libsignal.SignalProtocolAddress legacyAddr =
                    new org.whispersystems.libsignal.SignalProtocolAddress(
                            address.getName(), address.getDeviceId());
            try {
                legacy.buildSession(legacyAddr,
                        partial.getRegistrationId(),
                        chosenPkId, preKeys.get(chosenPkId),
                        partial.getSignedPreKeyId(),
                        partial.getSignedPreKey(),
                        partial.getSignedPreKeySignature(),
                        partial.getIdentityKey());
            } catch (final org.whispersystems.libsignal.InvalidKeyException
                            | org.whispersystems.libsignal.UntrustedIdentityException e) {
                Log.w(Config.LOGTAG, getLogprefix(account)
                        + "legacy session build failed for " + address + ": " + e);
                fetchStatusMap.put(address, FetchStatus.ERROR);
                finishBuildingSessionsFromPEP(address);
                if (callback != null) callback.onSessionBuildFailed();
                future.setException(new CryptoFailedException(
                        "legacy session build failed for " + address + ": " + e.getMessage()));
                return;
            }
            Log.d(Config.LOGTAG, getLogprefix(account)
                    + "legacy v0.3 session established for " + address);
            // The legacy session lives in legacy_sessions only; the primary
            // store is unaware of it. We do not insert a placeholder
            // XmppAxolotlSession here. Trust state is shared via the identities
            // table (fingerprint anchor). Send/receive routing is responsible
            // for picking the legacy backend when this address has a legacy
            // session (see future encrypt/decrypt wiring).
            final FingerprintStatus fpStatus = getFingerprintTrust(
                    CryptoHelper.bytesToHex(
                            partial.getIdentityKey().getPublicKey().serialize()));
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
            // Mark the future as completed without a session object — the
            // caller will check getLegacyBackend().hasSession(address) before
            // attempting to send.
            future.setException(new LegacySessionEstablishedException(address));
        });
    }

    /**
     * Sentinel exception used to communicate "session was established, but on
     * the legacy stack — use {@link #getLegacyBackend()} to access it". The
     * future API expects a primary {@link XmppAxolotlSession}, which a legacy
     * session does not produce.
     */
    public static class LegacySessionEstablishedException extends RuntimeException {
        public final SignalProtocolAddress address;
        public LegacySessionEstablishedException(final SignalProtocolAddress address) {
            super("legacy session established for " + address);
            this.address = address;
        }
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

    public Set<SignalProtocolAddress> findDevicesWithoutSession(final Conversation conversation, final boolean isOmemo2) {
        final var legacy = getLegacyBackend();
        final boolean allowLegacy = legacy != null && conversation.isLegacyOmemoAllowed();
        Set<SignalProtocolAddress> addresses = new HashSet<>();
        for (Jid jid : getCryptoTargets(conversation)) {
            Log.d(Config.LOGTAG, AxolotlService.getLogprefix(account) + "Finding devices without session for " + jid);
            final Set<Integer> ids = getDeviceIdsForStack(jid, isOmemo2);
            if (ids != null && !ids.isEmpty()) {
                for (Integer foreignId : ids) {
                    SignalProtocolAddress address = new SignalProtocolAddress(jid.toString(), foreignId);
                    if (sessions.get(address) == null) {
                        IdentityKey identityKey = getRemoteIdentityKeySafe(axolotlStore.loadSession(address));
                        if (identityKey != null) {
                            Log.d(Config.LOGTAG, AxolotlService.getLogprefix(account) + "Already have session for " + address.toString() + ", adding to cache...");
                            XmppAxolotlSession session = new XmppAxolotlSession(account, axolotlStore, getOwnAxolotlAddress(), address, identityKey);
                            sessions.put(address, session);
                        } else if (!isOmemo2 && allowLegacy && legacy.hasSession(
                                new org.whispersystems.libsignal.SignalProtocolAddress(
                                        jid.toString(), foreignId))) {
                            // A legacy session for this peer device already
                            // exists. Don't treat it as "without session" —
                            // sending will pick the legacy backend during the
                            // header build.
                            Log.d(Config.LOGTAG, getLogprefix(account)
                                    + "legacy session present for " + address + ", skipping fetch");
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
        Set<Integer> ownIds = getDeviceIdsForStack(account.getJid().asBareJid(), isOmemo2);
        for (Integer ownId : (ownIds != null ? ownIds : new HashSet<Integer>())) {
            SignalProtocolAddress address = new SignalProtocolAddress(account.getJid().asBareJid().toString(), ownId);
            if (sessions.get(address) == null) {
                IdentityKey identityKey = getRemoteIdentityKeySafe(axolotlStore.loadSession(address));
                if (identityKey != null) {
                    Log.d(Config.LOGTAG, AxolotlService.getLogprefix(account) + "Already have session for own " + address.toString() + ", adding to cache...");
                    XmppAxolotlSession session = new XmppAxolotlSession(account, axolotlStore, getOwnAxolotlAddress(), address, identityKey);
                    sessions.put(address, session);
                } else if (!isOmemo2 && allowLegacy && legacy.hasSession(
                        new org.whispersystems.libsignal.SignalProtocolAddress(
                                account.getJid().asBareJid().toString(), ownId))) {
                    // Own device with a legacy session — strict-legacy
                    // conversations don't need an OMEMO2 session.
                    Log.d(Config.LOGTAG, getLogprefix(account)
                            + "legacy session present for own " + address + ", skipping fetch");
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
        final boolean isOmemo2 = isOmemo2(encryption);
        if (hasPendingBundleFetch(account.getJid().asBareJid(), isOmemo2)) {
            return true;
        }
        synchronized (this.fetchDeviceIdsMap) {
            for (final Jid jid : jids) {
                // fetchDeviceIdsMap tracks legacy device-list fetches only; the
                // OMEMO2 one keeps no such registry.
                if (!isOmemo2 && this.fetchDeviceIdsMap.containsKey(jid)) {
                    return true;
                }
                if (hasPendingBundleFetch(jid, isOmemo2)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** True when any device of {@code jid} ON THIS STACK has a bundle fetch in flight. */
    private boolean hasPendingBundleFetch(final Jid jid, final boolean isOmemo2) {
        final Set<Integer> ids = getDeviceIdsForStack(jid.asBareJid(), isOmemo2);
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

    @Nullable
    private boolean buildHeader(XmppAxolotlMessage axolotlMessage, Conversation c) {
        // Legacy OMEMO (XEP-0384 v0.3) wire format. Strictly legacy: do NOT
        // mix in OMEMO2 (PQ) wrapped keys. The two stacks share a wire
        // namespace but use incompatible per-device key wrapping, and a
        // legacy-only peer cannot decrypt the OMEMO2 ciphertext bytes.
        final boolean acceptEmpty = (c.getMode() == Conversation.MODE_MULTI && c.getMucOptions().getUserCount() == 0) || c.getContact().isSelf();
        final boolean addedPeer = addLegacyDevicesForConversation(axolotlMessage, c);
        if (!addedPeer && !acceptEmpty) {
            return false;
        }
        // Our own other devices: only wrap for those with a legacy session.
        // Devices that are OMEMO2-only will not receive this message — the
        // user should switch the conversation to OMEMO2 for full coverage.
        addOwnLegacyDevices(axolotlMessage);
        return true;
    }

    /**
     * Candidate device IDs for the legacy send path.
     *
     * <p>Normally that is the announced device list, and it stays exactly that:
     * a device its owner removed from the list must stop receiving copies even
     * while a stale session for it lingers in the database — the announced list
     * is the only thing enforcing that on this stack.
     *
     * <p>The in-memory lists are volatile though: they are filled only by a
     * device-list fetch or a PEP notification, so until one arrives the legacy
     * list for a JID is unknown and enumerating it wrapped zero keys — the
     * message was marked failed even though perfectly usable sessions were
     * sitting in the database. (An upgraded install hits this routinely: the
     * peer's OMEMO2 list may well arrive while their legacy one never does.)
     * While that list is unknown there is no revocation information to honour,
     * so we fall back to the devices we actually hold a legacy session with.
     * {@code legacy.hasSession()} is the real gate on every caller, so the
     * fallback can only match a device we already established a session with.
     */
    private Set<Integer> legacyCandidateDeviceIds(final Jid jid) {
        final Jid bare = jid.asBareJid();
        final Set<Integer> announced = getDeviceIds(bare);
        if (this.deviceIds.get(bare) != null) {
            // Legacy list known — use it (plus the OMEMO2 IDs, as before).
            return announced == null ? Collections.emptySet() : announced;
        }
        final Set<Integer> ids =
                new HashSet<>(
                        mXmppConnectionService.databaseBackend.getLegacySubDeviceSessions(
                                account, bare.toString()));
        if (announced != null) {
            ids.addAll(announced);
        }
        return ids;
    }

    /**
     * Application-layer trust gate for the legacy send path.
     *
     * <p>The legacy store only pins a device's identity key per (jid, deviceId)
     * — TOFU, so a changed key is rejected — while the user's actual decision
     * lives in the shared identities table. That decision has to be enforced
     * here: OMEMO2 does the equivalent inside
     * {@link XmppAxolotlSession#processSending}, but the legacy path wraps keys
     * directly through the backend, so without this check a device whose
     * fingerprint the user untrusted (or never decided on) still received a copy
     * of every message. Rows are written by
     * {@link SQLiteAxolotlStore#saveIdentity} — reached from the legacy store's
     * saveIdentity bridge — so blind-trust-before-verification applies to legacy
     * devices exactly as it does to OMEMO2 ones, and undecided devices are the
     * ones the trust screen asks about.
     *
     * <p>Deliberately {@code isTrusted()} rather than {@code isTrustedAndActive()}:
     * since the two stacks were split, the "active" flag is only maintained for
     * OMEMO2 sessions, so a legacy row can carry a stale {@code active = 0} that
     * says nothing about what the user decided.
     */
    private boolean isLegacyDeviceTrusted(final Jid jid, final int deviceId) {
        final String fingerprint = getLegacyFingerprint(jid.asBareJid().toString(), deviceId);
        if (fingerprint == null) {
            return false;
        }
        final FingerprintStatus status = getFingerprintTrust(fingerprint);
        return status != null && status.isTrusted();
    }

    /**
     * Ask for a peer's legacy device list when this app run has never seen it
     * (absent, as opposed to a fetched and known-empty list). The send in flight
     * proceeds from the sessions already on disk — the answer only needs to
     * arrive before the NEXT send, which is what makes devices the peer added
     * while we were not running discoverable at all: the trust gate derives
     * "devices without session" from the same in-memory list, so while it is
     * empty nothing else would ever trigger the fetch. Already-running requests
     * are de-duplicated inside fetchDeviceIds().
     */
    private void refreshLegacyDeviceListIfUnknown(final Jid jid) {
        final Jid bare = jid.asBareJid();
        if (bare.equals(account.getJid().asBareJid())) {
            // Our own list is maintained by the login/publish path; re-fetching
            // it here would run the own-device-list bookkeeping (expiry checks,
            // republish) off a send.
            return;
        }
        if (this.deviceIds.get(bare) == null) {
            fetchDeviceIds(bare);
        }
    }

    /**
     * Wrap the message's inner AES-GCM key for each of the conversation's
     * peer devices that has a legacy XEP-0384 v0.3 session, and attach the
     * results to {@code axolotlMessage}. Returns true if at least one legacy
     * device was added.
     */
    private boolean addLegacyDevicesForConversation(final XmppAxolotlMessage axolotlMessage,
                                                    final Conversation c) {
        final var legacy = getLegacyBackend();
        if (legacy == null) return false;
        if (!c.isLegacyOmemoAllowed()) {
            // Per-conversation opt-in: the user must have picked legacy OMEMO for
            // this specific chat (from the encryption menu, or before the PQ
            // OMEMO2 update — see Conversation#isLegacyOmemoAllowed).
            return false;
        }
        boolean added = false;
        for (final Jid jid : getCryptoTargets(c)) {
            // Announced IDs of both stacks (legacy.hasSession() is the real gate,
            // so including the OMEMO2 list can only ever match a device that
            // truly has a legacy session, never add a wrong recipient), or the
            // persisted sessions when nothing is known yet — see
            // legacyCandidateDeviceIds.
            refreshLegacyDeviceListIfUnknown(jid);
            final Set<Integer> ids = legacyCandidateDeviceIds(jid);
            for (final Integer deviceId : ids) {
                final var address = new org.whispersystems.libsignal.SignalProtocolAddress(
                        jid.toString(), deviceId);
                if (!legacy.hasSession(address)) continue;
                if (!isLegacyDeviceTrusted(jid, deviceId)) {
                    Log.d(Config.LOGTAG, getLogprefix(account)
                            + "skipping untrusted legacy device " + address);
                    continue;
                }
                final var wrapped = legacy.encryptKey(address, axolotlMessage.getInnerKey());
                if (wrapped == null) continue;
                axolotlMessage.addLegacyWrappedKey(deviceId, wrapped.serialized, wrapped.isPreKeyMessage);
                added = true;
            }
        }
        return added;
    }

    /**
     * Wrap the message's inner AES-GCM key for each of the local account's
     * other devices using the legacy XEP-0384 v0.3 stack.
     */
    private void addOwnLegacyDevices(final XmppAxolotlMessage axolotlMessage) {
        final var legacy = getLegacyBackend();
        if (legacy == null) return;
        final Jid jid = account.getJid().asBareJid();
        // Includes our own devices with a persisted legacy session, so our other
        // devices still get a copy before the own device list has been received
        // in this app run.
        final Set<Integer> ids = legacyCandidateDeviceIds(jid);
        final int ownDeviceId = getOwnDeviceId();
        for (final Integer deviceId : ids) {
            if (deviceId == ownDeviceId) continue;
            final var address = new org.whispersystems.libsignal.SignalProtocolAddress(
                    jid.toString(), deviceId);
            if (!legacy.hasSession(address)) continue;
            if (!isLegacyDeviceTrusted(jid, deviceId)) {
                Log.d(Config.LOGTAG, getLogprefix(account)
                        + "skipping untrusted own legacy device " + address);
                continue;
            }
            final var wrapped = legacy.encryptKey(address, axolotlMessage.getInnerKey());
            if (wrapped == null) continue;
            axolotlMessage.addLegacyWrappedKey(deviceId, wrapped.serialized, wrapped.isPreKeyMessage);
        }
    }

    //this is being used for private muc messages only
    private boolean buildHeader(XmppAxolotlMessage axolotlMessage, Jid jid) {
        if (jid == null) {
            return false;
        }
        final var legacy = getLegacyBackend();
        if (legacy == null) return false;
        boolean added = false;
        refreshLegacyDeviceListIfUnknown(jid);
        for (final Integer deviceId : legacyCandidateDeviceIds(jid)) {
            final var address = new org.whispersystems.libsignal.SignalProtocolAddress(
                    jid.asBareJid().toString(), deviceId);
            if (!legacy.hasSession(address)) continue;
            if (!isLegacyDeviceTrusted(jid, deviceId)) {
                Log.d(Config.LOGTAG, getLogprefix(account)
                        + "skipping untrusted legacy device " + address);
                continue;
            }
            final var wrapped = legacy.encryptKey(address, axolotlMessage.getInnerKey());
            if (wrapped == null) continue;
            axolotlMessage.addLegacyWrappedKey(deviceId, wrapped.serialized, wrapped.isPreKeyMessage);
            added = true;
        }
        if (added) {
            addOwnLegacyDevices(axolotlMessage);
        }
        return added;
    }

    @Nullable
    public XmppAxolotlMessage encrypt(final String content, Jid counterpart) {
        final XmppAxolotlMessage axolotlMessage = new XmppAxolotlMessage(account.getJid().asBareJid(), getOwnDeviceId());
        try {
            axolotlMessage.encrypt(content);
        } catch (CryptoFailedException e) {
            Log.w(Config.LOGTAG, getLogprefix(account) + "Failed to encrypt message: " + e.getMessage());
            return null;
        }
        if (!buildHeader(axolotlMessage, counterpart)) return null;
        return axolotlMessage;
    }

    @Nullable
    public XmppAxolotlMessage encrypt(final String content, Conversation conversation) {
        final XmppAxolotlMessage axolotlMessage = new XmppAxolotlMessage(account.getJid().asBareJid(), getOwnDeviceId());
        try {
            axolotlMessage.encrypt(content);
        } catch (CryptoFailedException e) {
            Log.w(Config.LOGTAG, getLogprefix(account) + "Failed to encrypt message: " + e.getMessage());
            return null;
        }
        if (!buildHeader(axolotlMessage, conversation)) return null;
        return axolotlMessage;
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

    private static org.whispersystems.libsignal.SignalProtocolAddress legacyAddr(final SignalProtocolAddress address) {
        return new org.whispersystems.libsignal.SignalProtocolAddress(address.getName(), address.getDeviceId());
    }

    private OmemoVerifiedIceUdpTransportInfo encryptTransport(final IceUdpTransportInfo element,
            final SignalProtocolAddress address, final boolean useLegacy) throws CryptoFailedException {
        final OmemoVerifiedIceUdpTransportInfo transportInfo = new OmemoVerifiedIceUdpTransportInfo();
        transportInfo.setAttributes(element.getAttributes());
        final XmppAxolotlSession omemo2Session = useLegacy ? null : sessions.get(address);
        final var legacy = useLegacy ? getLegacyBackend() : null;
        final org.whispersystems.libsignal.SignalProtocolAddress legacyAddress = useLegacy ? legacyAddr(address) : null;
        for (final Element child : element.getChildren()) {
            if ("fingerprint".equals(child.getName()) && Namespace.JINGLE_APPS_DTLS.equals(child.getNamespace())) {
                final Element fingerprint = new Element("fingerprint", Namespace.OMEMO_DTLS_SRTP_VERIFICATION);
                fingerprint.setAttribute("setup", child.getAttribute("setup"));
                fingerprint.setAttribute("hash", child.getAttribute("hash"));
                if (useLegacy) {
                    final XmppAxolotlMessage axolotlMessage = new XmppAxolotlMessage(account.getJid().asBareJid(), getOwnDeviceId());
                    axolotlMessage.encrypt(child.getContent());
                    final var wrapped = legacy == null ? null : legacy.encryptKey(legacyAddress, axolotlMessage.getInnerKey());
                    if (wrapped == null) {
                        throw new CryptoFailedException("legacy RTP key wrap failed for " + address);
                    }
                    axolotlMessage.addLegacyWrappedKey(address.getDeviceId(), wrapped.serialized, wrapped.isPreKeyMessage);
                    fingerprint.addChild(axolotlMessage.toElement());
                } else if (omemo2Session != null) {
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
                useLegacy -> {
                    try {
                        return Futures.immediateFuture(encryptRtpContentMap(rtpContentMap, address, useLegacy));
                    } catch (final CryptoFailedException e) {
                        return Futures.immediateFailedFuture(e);
                    }
                },
                MoreExecutors.directExecutor()
        );
    }

    private OmemoVerifiedPayload<OmemoVerifiedRtpContentMap> encryptRtpContentMap(
            final RtpContentMap rtpContentMap, final SignalProtocolAddress address,
            final boolean useLegacy) throws CryptoFailedException {
        final XmppAxolotlSession omemo2Session = useLegacy ? null : sessions.get(address);
        final String fingerprint;
        if (useLegacy) {
            fingerprint = legacyFingerprintForAddress(legacyAddr(address));
            if (Config.REQUIRE_RTP_VERIFICATION) {
                final FingerprintStatus status = fingerprint == null ? null : getFingerprintTrust(fingerprint);
                if (status == null || !status.isVerified()) {
                    throw new NotVerifiedException("legacy session with " + fingerprint + " was not verified");
                }
            }
        } else {
            if (omemo2Session == null) {
                throw new CryptoFailedException("no OMEMO2 session for RTP verification with " + address);
            }
            if (Config.REQUIRE_RTP_VERIFICATION) {
                requireVerification(omemo2Session);
            }
            fingerprint = omemo2Session.getFingerprint();
        }
        final ImmutableMap.Builder<String, DescriptionTransport<RtpDescription,IceUdpTransportInfo>> descriptionTransportBuilder = new ImmutableMap.Builder<>();
        final OmemoVerification omemoVerification = new OmemoVerification();
        omemoVerification.setDeviceId(address.getDeviceId());
        omemoVerification.setSessionFingerprint(fingerprint);
        omemoVerification.setLegacy(useLegacy);
        for (final Map.Entry<String, DescriptionTransport<RtpDescription,IceUdpTransportInfo>> content : rtpContentMap.contents.entrySet()) {
            final DescriptionTransport<RtpDescription,IceUdpTransportInfo> descriptionTransport = content.getValue();
            final OmemoVerifiedIceUdpTransportInfo encryptedTransportInfo =
                    encryptTransport(descriptionTransport.transport, address, useLegacy);
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
     * Decide which stack verifies an outgoing call to a device.
     * {@code false} = OMEMO2 (post-quantum, preferred), {@code true} = legacy.
     * Legacy is only chosen when no OMEMO2 session can be established AND legacy
     * OMEMO is enabled with a usable legacy session — i.e. legacy is "in use".
     * Fails if neither stack can verify (verification never silently skipped).
     */
    private ListenableFuture<Boolean> prepareRtpSession(final SignalProtocolAddress address) {
        if (sessions.get(address) != null) {
            return Futures.immediateFuture(false);
        }
        final var legacy = getLegacyBackend();
        if (legacy != null && legacy.hasSession(legacyAddr(address))) {
            return Futures.immediateFuture(true);
        }
        final SettableFuture<Boolean> result = SettableFuture.create();
        buildSessionFromOmemo2PEP(address, new OnSessionBuildFromPep() {
            @Override
            public void onSessionBuildSuccessful() {
                result.set(false);
            }

            @Override
            public void onSessionBuildFailed() {
                if (legacy == null) {
                    result.setException(new CryptoFailedException(
                            "no OMEMO2 session for RTP verification with " + address));
                    return;
                }
                // OMEMO2 unavailable and legacy OMEMO is enabled: build a legacy
                // session and verify the call over legacy.
                buildLegacySessionFromPEP(address, new OnSessionBuildFromPep() {
                    @Override
                    public void onSessionBuildSuccessful() {
                        result.set(true);
                    }

                    @Override
                    public void onSessionBuildFailed() {
                        result.setException(new CryptoFailedException(
                                "no OMEMO session for RTP verification with " + address));
                    }
                }, SettableFuture.create());
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
                    final Element encrypted = child.findChildEnsureSingle(XmppAxolotlMessage.CONTAINERTAG, AxolotlService.PEP_PREFIX);
                    final XmppAxolotlMessage xmppAxolotlMessage = XmppAxolotlMessage.fromElement(encrypted, from.asBareJid());
                    XmppAxolotlMessage.XmppAxolotlPlaintextMessage plaintext = null;
                    // Legacy container ⇒ legacy stack first. Handing it to the
                    // OMEMO2 session cipher (which is what this did first) drives
                    // the OMEMO2 ratchet with a stanza that was never meant for
                    // it. The OMEMO2 attempt survives only as a fallback for the
                    // pre-split builds that wrapped a legacy container with the
                    // primary session.
                    final var legacy = getLegacyBackend();
                    final var legacyAddress = legacyAddr(
                            new SignalProtocolAddress(from.asBareJid().toString(), xmppAxolotlMessage.getSenderDeviceId()));
                    CryptoFailedException legacyFailure = null;
                    if (legacy != null) {
                        try {
                            plaintext = xmppAxolotlMessage.decryptLegacy(
                                    legacy, legacyAddress, getOwnDeviceId(),
                                    () -> legacyFingerprintForAddress(legacyAddress));
                        } catch (final CryptoFailedException e) {
                            legacyFailure = e;
                        }
                        if (plaintext != null) {
                            // Verify AFTER the unwrap: the fingerprint is
                            // device-scoped and read from the legacy session, which
                            // a first-contact PreKey message only just created.
                            if (Config.REQUIRE_RTP_VERIFICATION) {
                                final String fp = plaintext.getFingerprint();
                                final FingerprintStatus status =
                                        fp == null ? null : getFingerprintTrust(fp);
                                if (status == null || !status.isVerified()) {
                                    throw new NotVerifiedException(
                                            "legacy session with " + fp + " was not verified");
                                }
                            }
                            replenishLegacyPreKeysIfNeeded();
                            verifiedDeviceId = xmppAxolotlMessage.getSenderDeviceId();
                            verifiedFingerprint = plaintext.getFingerprint();
                            omemoVerification.setLegacy(true);
                        }
                    }
                    if (plaintext == null) {
                        final XmppAxolotlSession session = getReceivingSession(xmppAxolotlMessage);
                        try {
                            plaintext = xmppAxolotlMessage.decrypt(session, getOwnDeviceId());
                        } catch (final CryptoFailedException omemo2Failure) {
                            throw legacyFailure != null ? legacyFailure : omemo2Failure;
                        }
                        if (plaintext == null) {
                            throw legacyFailure != null ? legacyFailure
                                    : new CryptoFailedException("could not decrypt Jingle security element from " + from);
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
                    }
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

    public ListenableFuture<XmppAxolotlMessage> prepareKeyTransportMessage(final Conversation conversation) {
        return Futures.submit(()->{
            final XmppAxolotlMessage axolotlMessage = new XmppAxolotlMessage(account.getJid().asBareJid(), getOwnDeviceId());
            if (buildHeader(axolotlMessage, conversation)) {
                return axolotlMessage;
            } else {
                throw new IllegalStateException("No session to decrypt to");
            }
        },executor);
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

    public XmppAxolotlMessage.XmppAxolotlPlaintextMessage processReceivingPayloadMessage(XmppAxolotlMessage message, boolean postponePreKeyMessageHandling) throws NotEncryptedForThisDeviceException, BrokenSessionException, OutdatedSenderException {
        final int ownDeviceId = getOwnDeviceId();

        // Legacy XEP-0384 v0.3 wire format (namespace eu.siacs.conversations.axolotl).
        // Strictly legacy: do NOT fall back to the OMEMO2 (PQ) stack. The two
        // stacks use incompatible per-device key wrapping; mixing them would
        // re-introduce the cross-stack ambiguity the user has asked us to avoid.
        final var legacy = getLegacyBackend();
        if (legacy == null) {
            Log.w(Config.LOGTAG, getLogprefix(account)
                    + "Received legacy OMEMO from " + message.getFrom()
                    + " but legacy support is disabled — dropping");
            return null;
        }
        final var legacySender = new org.whispersystems.libsignal.SignalProtocolAddress(
                message.getFrom().toString(), message.getSenderDeviceId());
        try {
            final var pt = message.decryptLegacy(legacy, legacySender, ownDeviceId,
                    () -> legacyFingerprintForAddress(legacySender));
            if (pt != null) {
                // libsignal deleted one of our prekeys when consuming a
                // PreKeySignalMessage. Top up if we've dipped below the
                // replenishment threshold.
                replenishLegacyPreKeysIfNeeded();
            }
            return pt;
        } catch (final NotEncryptedForThisDeviceException e) {
            if (account.getJid().asBareJid().equals(message.getFrom().asBareJid())
                    && message.getSenderDeviceId() == ownDeviceId) {
                Log.w(Config.LOGTAG, getLogprefix(account)
                        + "Reflected legacy OMEMO message received — ignoring");
                return null;
            }
            throw e;
        } catch (final CryptoFailedException e) {
            Log.w(Config.LOGTAG, getLogprefix(account)
                    + "legacy decrypt failed for " + legacySender + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * The fingerprint of the LEGACY identity key of one specific peer device,
     * read from that device's legacy session record.
     *
     * <p>Must stay device- and stack-scoped: this value is what the UI renders
     * the per-message shield/lock from. It used to come from
     * {@code axolotlStore.getIdentity(address)}, which ignores the device id and
     * returns an arbitrary element of every identity stored under the peer's
     * JID — across both stacks. A legacy message from an unverified (or rogue)
     * device could then be displayed as verified because some other key of that
     * contact — typically their OMEMO2 key, verified by QR — happened to come
     * out of the set first.
     */
    @Nullable
    private String legacyFingerprintForAddress(
            final org.whispersystems.libsignal.SignalProtocolAddress address) {
        return getLegacyFingerprint(address.getName(), address.getDeviceId());
    }

    public void reportBrokenSessionException(BrokenSessionException e, boolean postpone) {
        reportBrokenSessionException(e, postpone, false);
    }

    public void reportBrokenSessionException(BrokenSessionException e, boolean postpone, final boolean isOmemo2) {
        Log.e(Config.LOGTAG, account.getJid().asBareJid() + ": broken session with " + e.getSignalProtocolAddress().toString() + " detected", e);
        if (postpone) {
            postponedHealing.put(e.getSignalProtocolAddress(), isOmemo2);
        } else {
            notifyRequiresHealing(e.getSignalProtocolAddress(), isOmemo2);
        }
    }

    private void notifyRequiresHealing(final SignalProtocolAddress signalProtocolAddress, final boolean isOmemo2) {
        if (healingAttempts.add(signalProtocolAddress)) {
            Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": attempt to heal " + signalProtocolAddress
                    + (isOmemo2 ? " (OMEMO2)" : " (legacy)"));
            final OnSessionBuildFromPep callback = new OnSessionBuildFromPep() {
                @Override
                public void onSessionBuildSuccessful() {
                    Log.d(Config.LOGTAG, "successfully build new session from pep after detecting broken session");
                    // Heal on the SAME stack the broken session came from. Routing an
                    // OMEMO2 break through the legacy builder would never repair the
                    // OMEMO2 session (the stacks use separate stores) and could create
                    // a stray legacy session.
                    if (isOmemo2) {
                        completeOmemo2Session(getReceivingSession(signalProtocolAddress));
                    } else {
                        completeSession(getReceivingSession(signalProtocolAddress));
                    }
                }

                @Override
                public void onSessionBuildFailed() {
                    Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": unable to build new session from pep after detecting broken session");
                }
            };
            if (isOmemo2) {
                buildSessionFromOmemo2PEP(signalProtocolAddress, callback, SettableFuture.create());
            } else {
                buildSessionFromPEP(signalProtocolAddress, callback);
            }
        } else {
            Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": do not attempt to heal " + signalProtocolAddress + " again");
        }
    }

    private void postPreKeyMessageHandling(final XmppAxolotlSession session, final boolean postpone,
            final boolean isOmemo2) {
        if (postpone) {
            postponedSessions.put(session, isOmemo2);
        } else {
            if (axolotlStore.flushPreKeys()) {
                publishBundlesIfNeeded(false, false);
            } else {
                Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": nothing to flush. Not republishing key");
            }
            replenishKyberPreKeysIfNeeded();
            if (trustedOrPreviouslyResponded(session) && Config.AUTOMATICALLY_COMPLETE_SESSIONS) {
                // Complete on the stack the prekey message arrived on. Routing a
                // PQ OMEMO2 session through the legacy completeSession() would
                // emit a v0.3-format key-transport derived from a PQ session,
                // mixing the two stacks.
                if (isOmemo2) {
                    completeOmemo2Session(session);
                } else {
                    completeSession(session);
                }
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
        final Iterator<Map.Entry<XmppAxolotlSession, Boolean>> iterator =
                postponedSessions.entrySet().iterator();
        while (iterator.hasNext()) {
            final Map.Entry<XmppAxolotlSession, Boolean> entry = iterator.next();
            final XmppAxolotlSession session = entry.getKey();
            if (trustedOrPreviouslyResponded(session) && Config.AUTOMATICALLY_COMPLETE_SESSIONS) {
                if (entry.getValue() != null && entry.getValue()) {
                    completeOmemo2Session(session);
                } else {
                    completeSession(session);
                }
            }
            iterator.remove();
        }
        final Iterator<Map.Entry<SignalProtocolAddress, Boolean>> postponedHealingAttemptsIterator =
                postponedHealing.entrySet().iterator();
        while (postponedHealingAttemptsIterator.hasNext()) {
            final Map.Entry<SignalProtocolAddress, Boolean> entry = postponedHealingAttemptsIterator.next();
            notifyRequiresHealing(entry.getKey(), entry.getValue() != null && entry.getValue());
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
     * Complete a session on the LEGACY stack. Delegates to
     * {@link #completeLegacySession}, which wraps with the legacy backend: the
     * key transport goes out in the legacy container, so wrapping it with the
     * OMEMO2 session cipher (as this used to do) produced a stanza no legacy
     * peer could open and mixed the two stacks.
     */
    private void completeSession(XmppAxolotlSession session) {
        final SignalProtocolAddress address = session.getRemoteAddress();
        completeLegacySession(new org.whispersystems.libsignal.SignalProtocolAddress(
                address.getName(), address.getDeviceId()));
    }

    /**
     * OMEMO2 (PQ) counterpart of {@link #completeSession}: after rebuilding a
     * broken OMEMO2 session, send the peer a minimal OMEMO2 message carrying an
     * empty SCE envelope (no body, no metadata). Decrypting it on the peer side
     * runs the normal OMEMO2 receive path, which ratchets/rebuilds their session
     * and produces no visible message — healing the session bidirectionally,
     * entirely on the OMEMO2 stack (never the legacy one). A no-payload
     * "key transport" would not work here because the receive path only dispatches
     * OMEMO2 stanzas that carry a {@code <payload>}.
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
                    .getPinnedOmemo2PqIdentity(account, ikFingerprint) != null) {
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
                .getPinnedOmemo2PqIdentity(account, ikFingerprint);
        if (pinned != null) {
            if (!Arrays.equals(pinned, peerPq.identityKey)) {
                Log.e(Config.LOGTAG, getLogprefix(account) + "pq_ik reconciliation: a"
                        + " DIFFERENT pq_ik was pinned concurrently for " + address
                        + " — keeping the existing pin");
            }
            return;
        }
        mXmppConnectionService.databaseBackend.pinOmemo2PqIdentity(
                account, ikFingerprint, peerPq.identityKey);
        Log.d(Config.LOGTAG, getLogprefix(account)
                + "pq_ik reconciliation: pinned PQ identity for " + address);
        // hybrid fingerprint is now available — refresh key lists in the UI
        mXmppConnectionService.keyStatusUpdated(null);
    }

    public XmppAxolotlMessage.XmppAxolotlKeyTransportMessage processReceivingKeyTransportMessage(XmppAxolotlMessage message, final boolean postponePreKeyMessageHandling) {
        // Legacy XEP-0384 v0.3 key transport (no <payload>): session completion,
        // healing, and the legacy Jingle security element. It MUST be unwrapped
        // with the legacy backend, exactly like a legacy payload message.
        //
        // This used to run through the OMEMO2 session cipher. That both made
        // every genuine legacy key transport fail, and — worse — let anyone able
        // to inject a stanza from the peer's JID (a malicious server) take a
        // captured OMEMO2 <key> blob, re-send it inside a legacy container with
        // no payload, and have it decrypted against the OMEMO2 ratchet:
        // advancing it, consuming a one-time prekey, and making the genuine
        // message arrive as a duplicate. It also completed such a "session" on
        // the wrong stack.
        final var legacy = getLegacyBackend();
        if (legacy == null) {
            Log.w(Config.LOGTAG, getLogprefix(account)
                    + "received legacy OMEMO key transport from " + message.getFrom()
                    + " but legacy support is disabled — dropping");
            return null;
        }
        final int ownDeviceId = getOwnDeviceId();
        final var legacySender = new org.whispersystems.libsignal.SignalProtocolAddress(
                message.getFrom().toString(), message.getSenderDeviceId());
        final boolean wasPreKey = message.isPreKeyFor(ownDeviceId);
        final XmppAxolotlMessage.XmppAxolotlKeyTransportMessage keyTransportMessage;
        try {
            keyTransportMessage = message.decryptLegacyKeyTransport(legacy, legacySender, ownDeviceId,
                    () -> legacyFingerprintForAddress(legacySender));
        } catch (final NotEncryptedForThisDeviceException e) {
            if (account.getJid().asBareJid().equals(message.getFrom().asBareJid())
                    && message.getSenderDeviceId() == ownDeviceId) {
                Log.w(Config.LOGTAG, getLogprefix(account)
                        + "Reflected legacy OMEMO key transport received — ignoring");
            } else {
                Log.d(Config.LOGTAG, getLogprefix(account)
                        + "legacy key transport not encrypted for this device");
            }
            return null;
        } catch (final CryptoFailedException e) {
            Log.d(Config.LOGTAG, "could not decrypt legacy keyTransport message " + e.getMessage());
            return null;
        }
        if (keyTransportMessage == null) {
            return null;
        }
        if (wasPreKey) {
            // The old libsignal deleted the consumed one-time prekey as a side
            // effect; top the published stock back up. Session completion is
            // skipped during MAM catch-up (postpone), where the peer has long
            // since moved on.
            replenishLegacyPreKeysIfNeeded();
            if (!postponePreKeyMessageHandling
                    && Config.AUTOMATICALLY_COMPLETE_SESSIONS
                    && trustedOrPreviouslyResponded(message.getFrom().asBareJid())) {
                completeLegacySession(legacySender);
            }
        }
        return keyTransportMessage;
    }

    /**
     * Legacy counterpart of {@link #completeSession}: answer a legacy PreKey
     * message with an empty legacy key transport so the peer's session becomes
     * acknowledged. Wrapped with the legacy backend — the stack the message
     * arrived on.
     */
    private void completeLegacySession(
            final org.whispersystems.libsignal.SignalProtocolAddress address) {
        final var legacy = getLegacyBackend();
        if (legacy == null) return;
        final XmppAxolotlMessage axolotlMessage =
                new XmppAxolotlMessage(account.getJid().asBareJid(), getOwnDeviceId());
        final var wrapped = legacy.encryptKey(address, axolotlMessage.getInnerKey());
        if (wrapped == null) {
            Log.d(Config.LOGTAG, getLogprefix(account)
                    + "could not wrap legacy session completion for " + address);
            return;
        }
        axolotlMessage.addLegacyWrappedKey(
                address.getDeviceId(), wrapped.serialized, wrapped.isPreKeyMessage);
        try {
            final Jid jid = Jid.of(address.getName());
            mXmppConnectionService.sendMessagePacket(account,
                    mXmppConnectionService.getMessageGenerator()
                            .generateKeyTransportMessage(jid, axolotlMessage));
        } catch (final IllegalArgumentException e) {
            Log.d(Config.LOGTAG, getLogprefix(account)
                    + "invalid jid in legacy session completion: " + address.getName());
        }
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
                    X509Certificate certificate = store.getFingerprintCertificate(CryptoHelper.bytesToHex(identityKey.getPublicKey().serialize()));
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
        final long age = System.currentTimeMillis() - latest.getTimestamp();
        if (age < 0 || age > KEM_SPK_ROTATION_MS) return null;
        return latest;
    }

    /** Publish our device ID and bundle to the OMEMO2 PEP nodes. Called after legacy publish. */
    public void publishOmemo2BundlesIfNeeded(final SignedPreKeyRecord signedPreKeyRecord,
                                             final Set<PreKeyRecord> preKeyRecords) {
        // Guard against first-run race where onUpgrade transaction may not have committed yet.
        mXmppConnectionService.databaseBackend.ensureKyberTablesExist();
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
    }

    // Republish when fewer than half of the published one-time KEM prekeys remain.
    // Below this threshold new sessions fall back to the signed (last-resort) KEM
    // prekey, which gives weaker forward secrecy for the handshake itself.
    private static final int MIN_KEM_PREKEYS = NUM_KEYS_TO_PUBLISH / 2;

    /**
     * Mirror of {@link #replenishKyberPreKeysIfNeeded()} for the legacy v0.3
     * one-time prekeys. Called after a legacy PreKeySignalMessage is consumed,
     * since libsignal deletes the matched prekey as a side effect.
     */
    private void replenishLegacyPreKeysIfNeeded() {
        final var legacy = getLegacyBackend();
        if (legacy == null) return;
        final int remaining = mXmppConnectionService.databaseBackend.countLegacyPreKeys(account);
        if (remaining >= NUM_KEYS_TO_PUBLISH / 2) return;
        Log.i(Config.LOGTAG, getLogprefix(account)
                + "legacy prekey stock low (" + remaining
                + ") — republishing v0.3 bundle");
        publishLegacyBundleIfNeeded(true);
    }

    /** Republish the OMEMO2 bundle with a fresh batch of one-time KEM prekeys if stock is low. */
    private void replenishKyberPreKeysIfNeeded() {
        if (axolotlStore.getKyberOneTimePreKeyCount() >= MIN_KEM_PREKEYS) return;
        Log.i(Config.LOGTAG, getLogprefix(account)
                + "KEM prekey stock low — republishing OMEMO2 bundle");
        publishBundlesIfNeeded(false, false);
    }

    private static KyberPreKeyRecord generateKyberSignedPreKey(final IdentityKeyPair identityKeyPair, final int id) {
        final KEMKeyPair kemPair = KEMKeyPair.generate(KEMKeyType.KYBER_1024);
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
            final Iq publish = mXmppConnectionService.getIqGenerator()
                    .publishOmemo2DeviceIds(deviceIds, publishOptions);
            mXmppConnectionService.sendIqPacket(account, publish, r -> {
                if (r.getType() == Iq.Type.RESULT) {
                    Log.d(Config.LOGTAG, getLogprefix(account) + "Published OMEMO2 device ID.");
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
        // "not encrypted for this device". Mirrors buildHeader()'s addedPeer.
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
                postPreKeyMessageHandling(session, postponePreKeyMessageHandling, true);
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
