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

    public static final String PEP_OMEMO2_DEVICE_LIST = Namespace.OMEMO2_DEVICES;
    public static final String PEP_OMEMO2_DEVICE_LIST_NOTIFY = PEP_OMEMO2_DEVICE_LIST + "+notify";
    public static final String PEP_OMEMO2_BUNDLES = Namespace.OMEMO2_BUNDLES;

    final Account account;
    public final XmppConnectionService mXmppConnectionService;
    private final SQLiteAxolotlStore axolotlStore;
    private final SessionMap sessions;
    private final Map<Jid, Set<Integer>> deviceIds;
    private final Map<String, XmppAxolotlMessage> messageCache;
    private final Map<String, XmppOmemo2Message> omemo2MessageCache = new HashMap<>();
    // Lazily created when the global legacy-OMEMO flag is enabled. Kept null
    // otherwise so the old-libsignal stack contributes nothing at runtime when
    // the user hasn't opted in.
    private volatile eu.siacs.conversations.crypto.axolotl.legacy.LegacyAxolotlBackend legacyBackend = null;
    // Set when a server-side check finds our OMEMO2 bundle node missing/empty so
    // the next publishBundlesIfNeeded() forces a republish even if the local KEM
    // store is non-empty (e.g. a previous publish IQ failed). See Fix 1.
    private volatile boolean forceOmemo2BundleRepublish = false;
    private final FetchStatusMap fetchStatusMap;
    private final Map<Jid, Boolean> fetchDeviceListStatus = new HashMap<>();
    private final HashMap<Jid, List<OnDeviceIdsFetched>> fetchDeviceIdsMap = new HashMap<>();
    private final SerialSingleThreadExecutor executor;
    private final Set<SignalProtocolAddress> healingAttempts = new HashSet<>();
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
        this.deviceIds = new HashMap<>();
        this.messageCache = new HashMap<>();
        this.sessions = new SessionMap(mXmppConnectionService, axolotlStore, account);
        this.fetchStatusMap = new FetchStatusMap();
        this.executor = new SerialSingleThreadExecutor("Axolotl");
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
     * Independently confirm that our OMEMO2 bundle is actually present on the
     * server (PEP node {@code urn:xmpp:omemo:2:bundles}). {@link
     * #publishBundlesIfNeeded(boolean, boolean)} only inspects the legacy bundle
     * node and the local KEM-prekey store, so a bundle that was generated locally
     * but whose publish IQ failed — or whose PEP node was lost server-side — would
     * never be re-published. Peers would then fetch an empty OMEMO2 bundle and
     * could not establish a post-quantum session. If the node is absent, carries
     * no bundle, or carries no KEM material, force a republish (independent of the
     * local KEM-prekey count).
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

    private boolean hasErrorFetchingDeviceList(Jid jid) {
        Boolean status = fetchDeviceListStatus.get(jid);
        return status != null && !status;
    }

    public boolean hasErrorFetchingDeviceList(List<Jid> jids) {
        for (Jid jid : jids) {
            if (hasErrorFetchingDeviceList(jid)) {
                return true;
            }
        }
        return false;
    }

    public boolean fetchMapHasErrors(List<Jid> jids) {
        for (Jid jid : jids) {
            if (deviceIds.get(jid) != null) {
                for (Integer foreignId : this.deviceIds.get(jid)) {
                    SignalProtocolAddress address = new SignalProtocolAddress(jid.toString(), foreignId);
                    if (fetchStatusMap.getAll(address.getName()).containsValue(FetchStatus.ERROR)) {
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

    public boolean hasVerifiedKeys(String name) {
        for (XmppAxolotlSession session : this.sessions.getAll(name).values()) {
            if (session.getTrust().isVerified()) {
                return true;
            }
        }
        return false;
    }

    public String getOwnFingerprint() {
        return CryptoHelper.bytesToHex(axolotlStore.getIdentityKeyPair().getPublicKey().serialize());
    }

    public Set<IdentityKey> getKeysWithTrust(FingerprintStatus status) {
        return axolotlStore.getContactKeysWithTrust(account.getJid().asBareJid().toString(), status);
    }

    public Set<IdentityKey> getKeysWithTrust(FingerprintStatus status, Jid jid) {
        return axolotlStore.getContactKeysWithTrust(jid.asBareJid().toString(), status);
    }

    public Set<IdentityKey> getKeysWithTrust(FingerprintStatus status, List<Jid> jids) {
        Set<IdentityKey> keys = new HashSet<>();
        for (Jid jid : jids) {
            keys.addAll(axolotlStore.getContactKeysWithTrust(jid.toString(), status));
        }
        return keys;
    }

    public Set<Jid> findCounterpartsBySourceId(int sid) {
        return sessions.findCounterpartsForSourceId(sid);
    }

    public long getNumTrustedKeys(Jid jid) {
        return axolotlStore.getContactNumTrustedKeys(jid.asBareJid().toString());
    }

    public boolean anyTargetHasNoTrustedKeys(List<Jid> jids) {
        for (Jid jid : jids) {
            if (axolotlStore.getContactNumTrustedKeys(jid.asBareJid().toString()) == 0) {
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

        public LegacySessionInfo(String fingerprint, FingerprintStatus status) {
            this.fingerprint = fingerprint;
            this.status = status;
        }
    }

    @Nullable
    private String getLegacyFingerprint(String bareJid, int deviceId) {
        final var legacy = getLegacyBackend();
        if (legacy == null) return null;
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
        final String bareJid = contact.getJid().asBareJid().toString();
        final List<Integer> deviceIds = mXmppConnectionService.databaseBackend.getLegacySubDeviceSessions(account, bareJid);
        final List<LegacySessionInfo> out = new ArrayList<>();
        for (Integer deviceId : deviceIds) {
            final String fingerprint = getLegacyFingerprint(bareJid, deviceId);
            if (fingerprint != null) {
                out.add(new LegacySessionInfo(fingerprint, getFingerprintTrust(fingerprint)));
            }
        }
        return out;
    }

    public List<LegacySessionInfo> findOwnLegacySessions() {
        final String bareJid = account.getJid().asBareJid().toString();
        final List<Integer> deviceIds = mXmppConnectionService.databaseBackend.getLegacySubDeviceSessions(account, bareJid);
        final List<LegacySessionInfo> out = new ArrayList<>();
        for (Integer deviceId : deviceIds) {
            if (deviceId == getOwnDeviceId()) continue;
            final String fingerprint = getLegacyFingerprint(bareJid, deviceId);
            if (fingerprint != null) {
                out.add(new LegacySessionInfo(fingerprint, getFingerprintTrust(fingerprint)));
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
    }

    public void regenerateKeys(boolean wipeOther) {
        axolotlStore.regenerate();
        sessions.clear();
        fetchStatusMap.clear();
        fetchDeviceIdsMap.clear();
        fetchDeviceListStatus.clear();
        publishBundlesIfNeeded(true, wipeOther);
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
        return this.deviceIds.get(account.getJid().asBareJid());
    }

    public void registerDevices(final Jid jid, @NonNull final Set<Integer> deviceIds) {
        registerDevices(jid, deviceIds, false);
    }

    public void registerDevices(final Jid jid, @NonNull final Set<Integer> deviceIds, final boolean isOmemo2) {
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
        if (me) {
            if (mXmppConnectionService.getOmemoAutoExpiry() != 0) {
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
                // skipped by CSI)
                if (isOmemo2) {
                    this.lastOmemo2DeviceListNotificationHash = 0;
                } else {
                    this.lastDeviceListNotificationHash = 0;
                }
                publishOwnDeviceId(deviceIds);
            }
        }
        final Set<Integer> oldSet = this.deviceIds.get(jid);
        final boolean changed = oldSet == null || oldSet.hashCode() != hash;
        this.deviceIds.put(jid, deviceIds);
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
        final Iq packet = mXmppConnectionService.getIqGenerator().retrieveBundlesForDevice(account.getJid().asBareJid(), getOwnDeviceId());
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

            PreKeyBundle bundle = IqParser.bundle(response);
            final Map<Integer, ECPublicKey> keys = IqParser.preKeyPublics(response);
            boolean flush = false;
            if (bundle == null) {
                Log.w(Config.LOGTAG, AxolotlService.getLogprefix(account) + "Received invalid bundle:" + response);
                flush = true;
            }
            if (keys == null) {
                Log.w(Config.LOGTAG, AxolotlService.getLogprefix(account) + "Received invalid prekeys:" + response);
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
                    }
                } catch (InvalidKeyIdException e) {
                    Log.i(Config.LOGTAG, AxolotlService.getLogprefix(account) + "Adding new signedPreKey with ID " + (numSignedPreKeys + 1) + " to PEP.");
                    signedPreKeyRecord = generateSignedPreKey(identityKeyPair, numSignedPreKeys + 1);
                    axolotlStore.storeSignedPreKey(signedPreKeyRecord.getId(), signedPreKeyRecord);
                    changed = true;
                }

                // Validate PreKeys
                Set<PreKeyRecord> preKeyRecords = new HashSet<>();
                if (keys != null) {
                    for (Integer id : keys.keySet()) {
                        try {
                            PreKeyRecord preKeyRecord = axolotlStore.loadPreKey(id);
                            if (preKeyRecord.getKeyPair().getPublicKey().equals(keys.get(id))) {
                                preKeyRecords.add(preKeyRecord);
                            }
                        } catch (InvalidKeyIdException ignored) {
                        }
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


                // OMEMO2/PQ post-migration safeguard. Everything above only
                // inspects the *legacy* EC bundle node (PEP_BUNDLES). A user
                // upgrading from a legacy-only build still has a valid legacy
                // bundle, so `changed` stays false and the separate OMEMO2 bundle
                // node (PEP_OMEMO2_BUNDLES, which carries the KEM prekeys) would
                // never be published. The consequences are exactly the migration
                // bug we see: peers fetch an empty OMEMO2 bundle and cannot build
                // a PQ session with us (their first reply fails / loops on trust),
                // and our own incoming first PQ messages fail to decrypt because
                // the referenced KEM prekeys were never generated. If we have no
                // one-time KEM prekeys published yet, force a full (re)publish.
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
            final int spkId = curId == 0 ? 1 : curId + 1;
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
        return !hasAny(jid) && (!deviceIds.containsKey(jid) || deviceIds.get(jid).isEmpty());
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
            mXmppConnectionService.sendIqPacket(account, packet, response -> {
                if (response.getType() == Iq.Type.RESULT) {
                    fetchDeviceListStatus.put(jid, true);
                    final Element item = IqParser.getItem(response);
                    final Set<Integer> deviceIds = IqParser.deviceIds(item);
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
                }
            });
        }
    }

    private void fetchDeviceIds(List<Jid> jids, final OnMultipleDeviceIdFetched callback) {
        final ArrayList<Jid> unfinishedJids = new ArrayList<>(jids);
        synchronized (unfinishedJids) {
            for (Jid jid : unfinishedJids) {
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
        mXmppConnectionService.sendIqPacket(account, omemo2Packet, response -> {
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
                    final PreKeyBundle preKeyBundle = new PreKeyBundle(0, address.getDeviceId(),
                            chosenPkId, chosenPk,
                            bundle.getSignedPreKeyId(), bundle.getSignedPreKey(),
                            bundle.getSignedPreKeySignature(), bundle.getIdentityKey(),
                            kemPreKeyId, kemPreKeyPublic, kemPreKeySig);
                    try {
                        final SignalProtocolAddress localAddress = getOwnAxolotlAddress();
                        new SessionBuilder(axolotlStore, address, localAddress).process(preKeyBundle);
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
                    } catch (UntrustedIdentityException | InvalidKeyException e) {
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
            // OMEMO2 failed.
            fetchStatusMap.put(address, FetchStatus.ERROR);
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
        mXmppConnectionService.sendIqPacket(account, legacyPacket, response -> {
            if (response.getType() != Iq.Type.RESULT) {
                Log.d(Config.LOGTAG, getLogprefix(account)
                        + "legacy bundle fetch failed for " + address + ": " + response);
                fetchStatusMap.put(address, FetchStatus.ERROR);
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
        final boolean allowLegacy =
                legacy != null
                        && conversation.getBooleanAttribute(
                                Conversation.ATTRIBUTE_ALLOW_LEGACY_OMEMO, false);
        Set<SignalProtocolAddress> addresses = new HashSet<>();
        for (Jid jid : getCryptoTargets(conversation)) {
            Log.d(Config.LOGTAG, AxolotlService.getLogprefix(account) + "Finding devices without session for " + jid);
            final Set<Integer> ids = deviceIds.get(jid);
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
        Set<Integer> ownIds = this.deviceIds.get(account.getJid().asBareJid());
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
            if (!hasEmptyDeviceList(jid)) {
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
            if (!hasEmptyDeviceList(iterator.next())) {
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
            for (final Jid jid : unfinished) {
                final Iq packet = mXmppConnectionService.getIqGenerator().retrieveOmemo2DeviceIds(jid);
                mXmppConnectionService.sendIqPacket(account, packet, response -> {
                    if (response.getType() == Iq.Type.RESULT) {
                        final Element item = IqParser.getItem(response);
                        final Set<Integer> deviceIds = IqParser.omemo2DeviceIds(item);
                        // Record the fetch outcome so the OMEMO2 trust guard
                        // (ConversationFragment#trustOmemo2KeysIfNeeded) can fail
                        // closed instead of reopening TrustKeysActivity forever.
                        // Previously this method never populated
                        // fetchDeviceListStatus, so hasErrorFetchingDeviceList()
                        // was permanently false for OMEMO2. An EMPTY result means
                        // the peer published no PQ-OMEMO2 devices (e.g. a
                        // legacy-only client): treat it like an error here so the
                        // send fails closed rather than looping the trust dialog.
                        // Recovery is automatic — once the peer publishes an
                        // OMEMO2 device list, registerOmemo2Devices() clears this
                        // status again (see there).
                        fetchDeviceListStatus.put(jid, !deviceIds.isEmpty());
                        registerDevices(jid, deviceIds, true);
                    } else if (response.getType() == Iq.Type.TIMEOUT) {
                        fetchDeviceListStatus.remove(jid);
                    } else {
                        fetchDeviceListStatus.put(jid, false);
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

    public boolean hasPendingKeyFetches(List<Jid> jids) {
        SignalProtocolAddress ownAddress = new SignalProtocolAddress(account.getJid().asBareJid().toString(), 1);
        if (fetchStatusMap.getAll(ownAddress.getName()).containsValue(FetchStatus.PENDING)) {
            return true;
        }
        synchronized (this.fetchDeviceIdsMap) {
            for (Jid jid : jids) {
                SignalProtocolAddress foreignAddress = new SignalProtocolAddress(jid.asBareJid().toString(), 1);
                if (fetchStatusMap.getAll(foreignAddress.getName()).containsValue(FetchStatus.PENDING) || this.fetchDeviceIdsMap.containsKey(jid)) {
                    return true;
                }
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
     * Wrap the message's inner AES-GCM key for each of the conversation's
     * peer devices that has a legacy XEP-0384 v0.3 session, and attach the
     * results to {@code axolotlMessage}. Returns true if at least one legacy
     * device was added.
     */
    private boolean addLegacyDevicesForConversation(final XmppAxolotlMessage axolotlMessage,
                                                    final Conversation c) {
        final var legacy = getLegacyBackend();
        if (legacy == null) return false;
        if (!c.getBooleanAttribute(Conversation.ATTRIBUTE_ALLOW_LEGACY_OMEMO, false)) {
            // Per-conversation opt-in: user must explicitly enable legacy
            // OMEMO for this specific chat from the encryption menu.
            return false;
        }
        boolean added = false;
        for (final Jid jid : getCryptoTargets(c)) {
            final Set<Integer> ids = deviceIds.get(jid);
            if (ids == null) continue;
            for (final Integer deviceId : ids) {
                final var address = new org.whispersystems.libsignal.SignalProtocolAddress(
                        jid.toString(), deviceId);
                if (!legacy.hasSession(address)) continue;
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
        final Set<Integer> ids = deviceIds.get(jid);
        if (ids == null) return;
        final int ownDeviceId = getOwnDeviceId();
        for (final Integer deviceId : ids) {
            if (deviceId == ownDeviceId) continue;
            final var address = new org.whispersystems.libsignal.SignalProtocolAddress(
                    jid.toString(), deviceId);
            if (!legacy.hasSession(address)) continue;
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
        final Set<Integer> ids = deviceIds.get(jid.asBareJid());
        if (ids != null) {
            for (final Integer deviceId : ids) {
                final var address = new org.whispersystems.libsignal.SignalProtocolAddress(
                        jid.toString(), deviceId);
                if (!legacy.hasSession(address)) continue;
                final var wrapped = legacy.encryptKey(address, axolotlMessage.getInnerKey());
                if (wrapped == null) continue;
                axolotlMessage.addLegacyWrappedKey(deviceId, wrapped.serialized, wrapped.isPreKeyMessage);
                added = true;
            }
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
                final XmppAxolotlMessage axolotlMessage = new XmppAxolotlMessage(account.getJid().asBareJid(), getOwnDeviceId());
                axolotlMessage.encrypt(child.getContent());
                if (useLegacy) {
                    // Legacy (classical) verification, only when legacy OMEMO is
                    // enabled and a legacy session with the peer device is in use.
                    final var wrapped = legacy == null ? null : legacy.encryptKey(legacyAddress, axolotlMessage.getInnerKey());
                    if (wrapped == null) {
                        throw new CryptoFailedException("legacy RTP key wrap failed for " + address);
                    }
                    axolotlMessage.addLegacyWrappedKey(address.getDeviceId(), wrapped.serialized, wrapped.isPreKeyMessage);
                } else if (omemo2Session != null) {
                    axolotlMessage.addDevice(omemo2Session, true);
                } else {
                    throw new CryptoFailedException("no OMEMO2 session for RTP verification with " + address);
                }
                fingerprint.addChild(axolotlMessage.toElement());
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
            fingerprint = identityKeyFingerprintForAddress(legacyAddr(address));
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
                final Element encrypted = child.findChildEnsureSingle(XmppAxolotlMessage.CONTAINERTAG, AxolotlService.PEP_PREFIX);
                final XmppAxolotlMessage xmppAxolotlMessage = XmppAxolotlMessage.fromElement(encrypted, from.asBareJid());
                XmppAxolotlMessage.XmppAxolotlPlaintextMessage plaintext;
                int verifiedDeviceId;
                String verifiedFingerprint;
                final XmppAxolotlSession session = getReceivingSession(xmppAxolotlMessage);
                try {
                    // Prefer OMEMO2 (post-quantum) verification.
                    plaintext = xmppAxolotlMessage.decrypt(session, getOwnDeviceId());
                    final Integer preKeyId = session.getPreKeyIdAndReset();
                    if (preKeyId != null) {
                        // OMEMO2 (PQ) verification path — complete on the OMEMO2 stack.
                        postponedSessions.put(session, true);
                    }
                    if (session.isFresh()) {
                        pepVerificationFutures.add(putFreshSession(session));
                    } else if (Config.REQUIRE_RTP_VERIFICATION) {
                        pepVerificationFutures.add(Futures.immediateFuture(session));
                    }
                    verifiedDeviceId = session.getRemoteAddress().getDeviceId();
                    verifiedFingerprint = plaintext.getFingerprint();
                } catch (final CryptoFailedException omemo2Failure) {
                    // Fall back to legacy verification ONLY when legacy OMEMO is
                    // enabled and a legacy session with the sender is in use.
                    final var legacy = getLegacyBackend();
                    final var legacyAddress = legacyAddr(
                            new SignalProtocolAddress(from.asBareJid().toString(), xmppAxolotlMessage.getSenderDeviceId()));
                    if (legacy == null || !legacy.hasSession(legacyAddress)) {
                        throw omemo2Failure;
                    }
                    final String fp = identityKeyFingerprintForAddress(legacyAddress);
                    if (Config.REQUIRE_RTP_VERIFICATION) {
                        final FingerprintStatus status = fp == null ? null : getFingerprintTrust(fp);
                        if (status == null || !status.isVerified()) {
                            throw new NotVerifiedException("legacy session with " + fp + " was not verified");
                        }
                    }
                    plaintext = xmppAxolotlMessage.decryptLegacy(
                            legacy, legacyAddress, getOwnDeviceId(), fp);
                    if (plaintext == null) {
                        throw omemo2Failure;
                    }
                    replenishLegacyPreKeysIfNeeded();
                    verifiedDeviceId = xmppAxolotlMessage.getSenderDeviceId();
                    verifiedFingerprint = plaintext.getFingerprint();
                }
                fingerprint.setContent(plaintext.getPlaintext());
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
        final String fingerprint = identityKeyFingerprintForAddress(legacySender);
        try {
            final var pt = message.decryptLegacy(legacy, legacySender, ownDeviceId, fingerprint);
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

    /** Look up the identity-key fingerprint for an old-libsignal-shaped
     *  address. The identities table is shared with the primary stack, so the
     *  same fingerprint applies regardless of which stack a session lives in. */
    private String identityKeyFingerprintForAddress(
            final org.whispersystems.libsignal.SignalProtocolAddress address) {
        try {
            final var primaryAddr = new org.signal.libsignal.protocol.SignalProtocolAddress(
                    address.getName(), address.getDeviceId());
            final IdentityKey ik = axolotlStore.getIdentity(primaryAddr);
            if (ik != null) {
                return CryptoHelper.bytesToHex(ik.getPublicKey().serialize());
            }
        } catch (final Exception ignored) {
        }
        return null;
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

    private void completeSession(XmppAxolotlSession session) {
        final XmppAxolotlMessage axolotlMessage = new XmppAxolotlMessage(account.getJid().asBareJid(), getOwnDeviceId());
        axolotlMessage.addDevice(session, true);
        try {
            final Jid jid = Jid.of(session.getRemoteAddress().getName());
            final var packet = mXmppConnectionService.getMessageGenerator().generateKeyTransportMessage(jid, axolotlMessage);
            mXmppConnectionService.sendMessagePacket(account, packet);
        } catch (IllegalArgumentException e) {
            throw new Error("Remote addresses are created from jid and should convert back to jid", e);
        }
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
        if (!message.hasPayload()) return;
        final var packet = mXmppConnectionService.getMessageGenerator()
                .generateOmemo2KeyTransportMessage(jid, message);
        mXmppConnectionService.sendMessagePacket(account, packet);
    }

    public XmppAxolotlMessage.XmppAxolotlKeyTransportMessage processReceivingKeyTransportMessage(XmppAxolotlMessage message, final boolean postponePreKeyMessageHandling) {
        final XmppAxolotlMessage.XmppAxolotlKeyTransportMessage keyTransportMessage;
        final XmppAxolotlSession session = getReceivingSession(message);
        try {
            keyTransportMessage = message.getParameters(session, getOwnDeviceId());
            Integer preKeyId = session.getPreKeyIdAndReset();
            if (preKeyId != null) {
                // Legacy XEP-0384 v0.3 key-transport wire format.
                postPreKeyMessageHandling(session, postponePreKeyMessageHandling, false);
            }
        } catch (CryptoFailedException e) {
            Log.d(Config.LOGTAG, "could not decrypt keyTransport message " + e.getMessage());
            return null;
        }

        if (session.isFresh() && keyTransportMessage != null) {
            putFreshSession(session);
        }

        return keyTransportMessage;
    }

    public XmppAxolotlMessage.XmppAxolotlKeyTransportMessage processReceivingOmemo2KeyTransportMessage(
            final XmppOmemo2Message message, final Jid expectedTo) {
        final XmppOmemo2Message.DecryptedSce decryptedSce;
        try {
            decryptedSce = processReceivingOmemo2PayloadMessage(message, false, expectedTo);
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
        private final T payload;

        private OmemoVerifiedPayload(OmemoVerification omemoVerification, T payload) {
            this.deviceId = omemoVerification.getDeviceId();
            this.fingerprint = omemoVerification.getFingerprint();
            this.payload = payload;
        }

        public int getDeviceId() {
            return deviceId;
        }

        public String getFingerprint() {
            return fingerprint;
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

    /** Publish our device ID and bundle to the OMEMO2 PEP nodes. Called after legacy publish. */
    public void publishOmemo2BundlesIfNeeded(final SignedPreKeyRecord signedPreKeyRecord,
                                             final Set<PreKeyRecord> preKeyRecords) {
        // Guard against first-run race where onUpgrade transaction may not have committed yet.
        mXmppConnectionService.databaseBackend.ensureKyberTablesExist();
        // Signed KEM prekey (last-resort): persisted across sessions, protected against replay.
        final KyberPreKeyRecord kyberSignedPreKeyRecord = generateKyberSignedPreKey(
                axolotlStore.getIdentityKeyPair(), axolotlStore.getCurrentKemPreKeyId() + 1);
        axolotlStore.storeKyberLastResortPreKey(
                kyberSignedPreKeyRecord.getId(), kyberSignedPreKeyRecord);

        // One-time KEM prekeys: deleted after single use.
        final int startKemId = axolotlStore.getCurrentKemPreKeyId() + 1;
        final List<KyberPreKeyRecord> kyberPreKeyRecords = new ArrayList<>();
        for (int i = 0; i < NUM_KEYS_TO_PUBLISH; i++) {
            final KyberPreKeyRecord record = generateKyberSignedPreKey(
                    axolotlStore.getIdentityKeyPair(), startKemId + i);
            kyberPreKeyRecords.add(record);
            axolotlStore.storeKyberPreKey(record.getId(), record);
        }

        publishOmemo2Bundle(signedPreKeyRecord, preKeyRecords, kyberSignedPreKeyRecord, kyberPreKeyRecords, true);
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
        final Iq publish = mXmppConnectionService.getIqGenerator().publishOmemo2Bundles(
                signedPreKeyRecord, axolotlStore.getIdentityKeyPair().getPublicKey(),
                preKeyRecords, kyberSignedPreKeyRecord, kyberPreKeyRecords, getOwnDeviceId(), publishOptions);
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
        final Set<Integer> known = deviceIds.get(jid);
        if (known == null || !known.equals(ids)) {
            clearErrorsInFetchStatusMap(jid);
            // Also clear a stale device-list fetch error (set by
            // fetchOmemo2DeviceIds when a previous fetch returned empty/failed),
            // so a peer migrating to PQ OMEMO2 recovers automatically: the trust
            // guard stops failing closed once a non-empty list is known.
            if (!ids.isEmpty()) {
                fetchDeviceListStatus.remove(jid);
            }
        }
        // Store in the same deviceIds map so sessions can be built for these devices.
        registerDevices(jid, ids, true);
    }

    // --- OMEMO2 encryption ---

    @Nullable
    public XmppOmemo2Message encryptOmemo2(final Message message) {
        final Conversation conversation = (Conversation) message.getConversation();
        final boolean isMuc = conversation.getMode() == Conversation.MODE_MULTI;
        final Jid toJid = isMuc ? conversation.getJid().asBareJid() : message.getCounterpart();

        final boolean isRetraction = message.isDeleted() && message.getRetractId() != null;
        final String content;
        if (isRetraction) {
            // A fallback body so the SCE envelope is a real content message (not a no-body
            // stanza that gets dropped); clients that don't grok <retract> still see this.
            content = "This message has been retracted by the sender.";
        } else if (message.hasFileOnRemoteHost()) {
            content = message.getFileParams().url;
        } else {
            content = message.getRawBody();
        }

        // Collect all SCE content elements per XEP-0420 / XEP-0384
        final List<Element> extraContent = new ArrayList<>();
        for (final Element payload : message.getPayloads()) {
            extraContent.add(payload);
        }
        if (message.getSubject() != null && !message.getSubject().isEmpty()) {
            final Element subject = new Element("subject");
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
        for (final XmppAxolotlSession session : remoteSessions) {
            message.addDevice(session);
        }
        for (final XmppAxolotlSession session : ownSessions) {
            message.addDevice(session);
        }
        return true;
    }

    private boolean buildOmemo2Header(final XmppOmemo2Message message, final Jid jid) {
        if (jid == null) return false;
        final Set<XmppAxolotlSession> sessions = new HashSet<>(
                this.sessions.getAll(getAddressForJid(jid).getName()).values());
        if (sessions.isEmpty()) return false;
        sessions.addAll(findOwnSessions());
        for (final XmppAxolotlSession session : sessions) {
            message.addDevice(session);
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
                    .setAttribute("name", "OMEMO2")
                    .setAttribute("namespace", eu.siacs.conversations.xml.Namespace.OMEMO2);
            mXmppConnectionService.sendMessagePacket(account, basePacket);
        });
    }

    // --- OMEMO2 decryption ---

    public XmppOmemo2Message.DecryptedSce processReceivingOmemo2PayloadMessage(
            final XmppOmemo2Message message, final boolean postponePreKeyMessageHandling,
            final Jid expectedTo)
            throws NotEncryptedForThisDeviceException, BrokenSessionException, OutdatedSenderException {

        final SignalProtocolAddress senderAddress = new SignalProtocolAddress(
                message.getFrom().toString(), message.getSenderDeviceId());
        final XmppAxolotlSession session = getReceivingSession(senderAddress);
        final int ownDeviceId = getOwnDeviceId();

        XmppOmemo2Message.DecryptedSce decrypted = null;
        try {
            decrypted = message.decrypt(session, ownDeviceId, account.getJid().asBareJid(), expectedTo);
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
        } catch (final BrokenSessionException e) {
            throw e;
        } catch (final CryptoFailedException e) {
            Log.w(Config.LOGTAG, getLogprefix(account) + "OMEMO2 decrypt failed from " + message.getFrom(), e);
        }

        if (session.isFresh() && decrypted != null) {
            putFreshSession(session);
        }
        return decrypted;
    }
}
