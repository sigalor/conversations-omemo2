package eu.siacs.conversations.parser;

import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;
import androidx.annotation.NonNull;
import com.google.common.base.CharMatcher;
import com.google.common.io.BaseEncoding;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.crypto.axolotl.AxolotlService;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Comment;
import eu.siacs.conversations.entities.Contact;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Post;
import eu.siacs.conversations.entities.Room;
import eu.siacs.conversations.entities.Story;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xml.Namespace;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.OnUpdateBlocklist;
import eu.siacs.conversations.xmpp.forms.Data;
import im.conversations.android.xmpp.model.stanza.Iq;
import java.io.ByteArrayInputStream;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import org.signal.libsignal.protocol.IdentityKey;
import org.signal.libsignal.protocol.InvalidKeyException;
import org.signal.libsignal.protocol.ecc.ECPublicKey;
import org.signal.libsignal.protocol.kem.KEMKeyPair;
import org.signal.libsignal.protocol.kem.KEMKeyType;
import org.signal.libsignal.protocol.kem.KEMPublicKey;
import org.signal.libsignal.protocol.state.PreKeyBundle;

public class IqParser extends AbstractParser implements Consumer<Iq> {

    public IqParser(final XmppConnectionService service, final Account account) {
        super(service, account);
    }

    public static List<Jid> items(final Iq packet) {
        ArrayList<Jid> items = new ArrayList<>();
        final Element query = packet.findChild("query", Namespace.DISCO_ITEMS);
        if (query == null) {
            return items;
        }
        for (Element child : query.getChildren()) {
            if ("item".equals(child.getName())) {
                Jid jid = child.getAttributeAsJid("jid");
                if (jid != null) {
                    items.add(jid);
                }
            }
        }
        return items;
    }

    public static Room parseRoom(Iq packet) {
        final Element query = packet.findChild("query", Namespace.DISCO_INFO);
        if (query == null) {
            return null;
        }
        final Element x = query.findChild("x");
        if (x == null) {
            return null;
        }
        final Element identity = query.findChild("identity");
        Data data = Data.parse(x);
        String address = packet.getFrom().toString();
        String name = identity == null ? null : identity.getAttribute("name");
        String roomName = data.getValue("muc#roomconfig_roomname");
        String description = data.getValue("muc#roominfo_description");
        String language = data.getValue("muc#roominfo_lang");
        String occupants = data.getValue("muc#roominfo_occupants");
        int nusers;
        try {
            nusers = occupants == null ? 0 : Integer.parseInt(occupants);
        } catch (NumberFormatException e) {
            nusers = 0;
        }

        return new Room(
                address,
                TextUtils.isEmpty(roomName) ? name : roomName,
                description,
                language,
                nusers);
    }

    private void rosterItems(final Account account, final Element query) {
        final String version = query.getAttribute("ver");
        if (version != null) {
            account.getRoster().setVersion(version);
        }
        for (final Element item : query.getChildren()) {
            if (item.getName().equals("item")) {
                final Jid jid = Jid.Invalid.getNullForInvalid(item.getAttributeAsJid("jid"));
                if (jid == null) {
                    continue;
                }
                final String name = item.getAttribute("name");
                final String subscription = item.getAttribute("subscription");
                final Contact contact = account.getRoster().getContact(jid);
                boolean bothPre =
                        contact.getOption(Contact.Options.TO)
                                && contact.getOption(Contact.Options.FROM);
                if (!contact.getOption(Contact.Options.DIRTY_PUSH)) {
                    contact.setServerName(name);
                    contact.parseGroupsFromElement(item);
                }
                if ("remove".equals(subscription)) {
                    contact.resetOption(Contact.Options.IN_ROSTER);
                    contact.resetOption(Contact.Options.DIRTY_DELETE);
                    contact.resetOption(Contact.Options.PREEMPTIVE_GRANT);
                } else {
                    contact.setOption(Contact.Options.IN_ROSTER);
                    contact.resetOption(Contact.Options.DIRTY_PUSH);
                    contact.parseSubscriptionFromElement(item);
                }
                boolean both =
                        contact.getOption(Contact.Options.TO)
                                && contact.getOption(Contact.Options.FROM);
                if ((both != bothPre) && both) {
                    Log.d(
                            Config.LOGTAG,
                            account.getJid().asBareJid()
                                    + ": gained mutual presence subscription with "
                                    + contact.getJid());
                    AxolotlService axolotlService = account.getAxolotlService();
                    if (axolotlService != null) {
                        axolotlService.clearErrorsInFetchStatusMap(contact.getJid());
                    }
                }
                mXmppConnectionService.getAvatarService().clear(contact);
            }
        }
        mXmppConnectionService.updateConversationUi();
        mXmppConnectionService.updateRosterUi(XmppConnectionService.UpdateRosterReason.PUSH);
        mXmppConnectionService.getShortcutService().refresh();
        mXmppConnectionService.syncRoster(account);
    }

    public static String avatarData(final Iq packet) {
        final Element pubsub = packet.findChild("pubsub", Namespace.PUBSUB);
        if (pubsub == null) {
            return null;
        }
        final Element items = pubsub.findChild("items");
        if (items == null) {
            return null;
        }
        return AbstractParser.avatarData(items);
    }

    /**
     * Decode an OMEMO2 EC public key from either the spec-compliant 32-byte raw format or the
     * legacy 33-byte libsignal-prefixed format (0x05 || 32 bytes).
     */
    private static ECPublicKey decodeOmemo2EcPublicKey(final byte[] bytes) throws InvalidKeyException {
        if (bytes.length == 32) {
            return ECPublicKey.fromPublicKeyBytes(bytes);
        } else if (bytes.length == 33 && bytes[0] == 0x05) {
            return new ECPublicKey(bytes);
        }
        throw new InvalidKeyException("bad key type <0x" + String.format("%02x", bytes.length > 0 ? bytes[0] : 0) + ">");
    }

    public static Element getItem(final Iq packet) {
        final Element pubsub = packet.findChild("pubsub", Namespace.PUBSUB);
        if (pubsub == null) {
            return null;
        }
        final Element items = pubsub.findChild("items");
        if (items == null) {
            return null;
        }
        return items.findChild("item");
    }

    @NonNull
    public static Set<Integer> deviceIds(final Element item) {
        Set<Integer> deviceIds = new HashSet<>();
        if (item != null) {
            final Element list = item.findChild("list");
            if (list != null) {
                for (Element device : list.getChildren()) {
                    if (!device.getName().equals("device")) {
                        continue;
                    }
                    try {
                        Integer id = Integer.valueOf(device.getAttribute("id"));
                        if (id > 0) {
                            deviceIds.add(id);
                        }
                    } catch (NumberFormatException e) {
                        Log.e(
                                Config.LOGTAG,
                                AxolotlService.LOGPREFIX
                                        + " : "
                                        + "Encountered invalid <device> node in PEP ("
                                        + e.getMessage()
                                        + "):"
                                        + device.toString()
                                        + ", skipping...");
                    }
                }
            }
        }
        return deviceIds;
    }

    private static Integer signedPreKeyId(final Element bundle) {
        final Element signedPreKeyPublic = bundle.findChild("signedPreKeyPublic");
        if (signedPreKeyPublic == null) {
            return null;
        }
        try {
            return Integer.valueOf(signedPreKeyPublic.getAttribute("signedPreKeyId"));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static ECPublicKey signedPreKeyPublic(final Element bundle) {
        final String signedPreKeyPublic = bundle.findChildContent("signedPreKeyPublic");
        if (signedPreKeyPublic == null) {
            return null;
        }
        try {
            return new ECPublicKey(base64decode(signedPreKeyPublic));
        } catch (final IllegalArgumentException | InvalidKeyException e) {
            Log.e(Config.LOGTAG, AxolotlService.LOGPREFIX
                    + " : Invalid signedPreKeyPublic in PEP: " + e.getMessage());
            return null;
        }
    }

    private static byte[] signedPreKeySignature(final Element bundle) {
        final String signedPreKeySignature = bundle.findChildContent("signedPreKeySignature");
        if (signedPreKeySignature == null) {
            return null;
        }
        try {
            return base64decode(signedPreKeySignature);
        } catch (final IllegalArgumentException e) {
            Log.e(
                    Config.LOGTAG,
                    AxolotlService.LOGPREFIX + " : Invalid base64 in signedPreKeySignature");
            return null;
        }
    }

    private static IdentityKey identityKey(final Element bundle) {
        final String identityKey = bundle.findChildContent("identityKey");
        if (identityKey == null) {
            return null;
        }
        try {
            return new IdentityKey(base64decode(identityKey), 0);
        } catch (final IllegalArgumentException | InvalidKeyException e) {
            Log.e(
                    Config.LOGTAG,
                    AxolotlService.LOGPREFIX
                            + " : "
                            + "Invalid identityKey in PEP: "
                            + e.getMessage());
            return null;
        }
    }

    // -----------------------------------------------------------------------
    // Legacy XEP-0384 v0.3 bundle parsers (old libsignal types). Wire format
    // is identical to the post-PQ bundle; only the consuming library differs.
    // -----------------------------------------------------------------------

    public static org.whispersystems.libsignal.IdentityKey legacyIdentityKey(final Element bundle) {
        final String b64 = bundle.findChildContent("identityKey");
        if (b64 == null) return null;
        try {
            return new org.whispersystems.libsignal.IdentityKey(base64decode(b64), 0);
        } catch (final IllegalArgumentException | org.whispersystems.libsignal.InvalidKeyException e) {
            Log.w(Config.LOGTAG, AxolotlService.LOGPREFIX + " : invalid legacy identityKey: " + e);
            return null;
        }
    }

    public static org.whispersystems.libsignal.ecc.ECPublicKey legacySignedPreKeyPublic(final Element bundle) {
        final String b64 = bundle.findChildContent("signedPreKeyPublic");
        if (b64 == null) return null;
        try {
            return org.whispersystems.libsignal.ecc.Curve.decodePoint(base64decode(b64), 0);
        } catch (final org.whispersystems.libsignal.InvalidKeyException e) {
            Log.w(Config.LOGTAG, AxolotlService.LOGPREFIX + " : invalid legacy spk: " + e);
            return null;
        }
    }

    public static Integer legacySignedPreKeyId(final Element bundle) {
        final Element spk = bundle.findChild("signedPreKeyPublic");
        if (spk == null) return null;
        final String id = spk.getAttribute("signedPreKeyId");
        if (id == null) return null;
        try {
            return Integer.parseInt(id);
        } catch (final NumberFormatException e) {
            return null;
        }
    }

    public static byte[] legacySignedPreKeySignature(final Element bundle) {
        final String b64 = bundle.findChildContent("signedPreKeySignature");
        if (b64 == null) return null;
        try {
            return base64decode(b64);
        } catch (final IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Parse the legacy bundle stripped of its one-time prekeys. Returns a partial
     * {@code PreKeyBundle} with {@code preKeyId = 0} (sentinel) — the caller
     * picks a one-time prekey from {@link #legacyPreKeyPublics(Iq)} and assembles
     * the final bundle before {@code SessionBuilder.process()}.
     */
    public static org.whispersystems.libsignal.state.PreKeyBundle legacyBundle(final Iq packet) {
        final Element item = getItem(packet);
        if (item == null) return null;
        final Element bundle = item.findChild("bundle");
        if (bundle == null) return null;
        final org.whispersystems.libsignal.IdentityKey ik = legacyIdentityKey(bundle);
        final org.whispersystems.libsignal.ecc.ECPublicKey spk = legacySignedPreKeyPublic(bundle);
        final Integer spkId = legacySignedPreKeyId(bundle);
        final byte[] spkSig = legacySignedPreKeySignature(bundle);
        if (ik == null || spk == null || spkId == null || spkSig == null) return null;
        // registrationId and deviceId are placeholders; deviceId comes from the
        // outer fetch context, registration is unused by the protocol after
        // session build.
        return new org.whispersystems.libsignal.state.PreKeyBundle(
                0, 1, 0, null, spkId, spk, spkSig, ik);
    }

    public static Map<Integer, org.whispersystems.libsignal.ecc.ECPublicKey>
            legacyPreKeyPublics(final Iq packet) {
        final Map<Integer, org.whispersystems.libsignal.ecc.ECPublicKey> out = new HashMap<>();
        final Element item = getItem(packet);
        if (item == null) return out;
        final Element bundle = item.findChild("bundle");
        if (bundle == null) return out;
        final Element prekeys = bundle.findChild("prekeys");
        if (prekeys == null) return out;
        for (final Element pk : prekeys.getChildren()) {
            if (!"preKeyPublic".equals(pk.getName())) continue;
            final String content = pk.getContent();
            final String idAttr = pk.getAttribute("preKeyId");
            if (content == null || idAttr == null) continue;
            try {
                final int id = Integer.parseInt(idAttr);
                final var ec = org.whispersystems.libsignal.ecc.Curve
                        .decodePoint(base64decode(content), 0);
                out.put(id, ec);
            } catch (final IllegalArgumentException
                           | org.whispersystems.libsignal.InvalidKeyException e) {
                Log.w(Config.LOGTAG, AxolotlService.LOGPREFIX
                        + " : skipping invalid legacy prekey: " + e);
            }
        }
        return out;
    }

    public static Map<Integer, ECPublicKey> preKeyPublics(final Iq packet) {
        Map<Integer, ECPublicKey> preKeyRecords = new HashMap<>();
        Element item = getItem(packet);
        if (item == null) {
            Log.d(
                    Config.LOGTAG,
                    AxolotlService.LOGPREFIX
                            + " : "
                            + "Couldn't find <item> in bundle IQ packet: "
                            + packet);
            return null;
        }
        final Element bundleElement = item.findChild("bundle");
        if (bundleElement == null) {
            return null;
        }
        final Element prekeysElement = bundleElement.findChild("prekeys");
        if (prekeysElement == null) {
            Log.d(
                    Config.LOGTAG,
                    AxolotlService.LOGPREFIX
                            + " : "
                            + "Couldn't find <prekeys> in bundle IQ packet: "
                            + packet);
            return null;
        }
        for (Element preKeyPublicElement : prekeysElement.getChildren()) {
            if (!preKeyPublicElement.getName().equals("preKeyPublic")) {
                Log.d(
                        Config.LOGTAG,
                        AxolotlService.LOGPREFIX
                                + " : "
                                + "Encountered unexpected tag in prekeys list: "
                                + preKeyPublicElement);
                continue;
            }
            final String preKey = preKeyPublicElement.getContent();
            if (preKey == null) {
                continue;
            }
            Integer preKeyId = null;
            try {
                preKeyId = Integer.valueOf(preKeyPublicElement.getAttribute("preKeyId"));
                final ECPublicKey preKeyPublic = new ECPublicKey(base64decode(preKey));
                preKeyRecords.put(preKeyId, preKeyPublic);
            } catch (NumberFormatException e) {
                Log.e(
                        Config.LOGTAG,
                        AxolotlService.LOGPREFIX
                                + " : "
                                + "could not parse preKeyId from preKey "
                                + preKeyPublicElement.toString());
            } catch (Throwable e) {
                Log.e(
                        Config.LOGTAG,
                        AxolotlService.LOGPREFIX
                                + " : "
                                + "Invalid preKeyPublic (ID="
                                + preKeyId
                                + ") in PEP: "
                                + e.getMessage()
                                + ", skipping...");
            }
        }
        return preKeyRecords;
    }

    private static byte[] base64decode(String input) {
        return BaseEncoding.base64().decode(CharMatcher.whitespace().removeFrom(input));
    }

    public static Pair<X509Certificate[], byte[]> verification(final Iq packet) {
        Element item = getItem(packet);
        Element verification =
                item != null ? item.findChild("verification", AxolotlService.PEP_PREFIX) : null;
        Element chain = verification != null ? verification.findChild("chain") : null;
        String signature = verification != null ? verification.findChildContent("signature") : null;
        if (chain != null && signature != null) {
            List<Element> certElements = chain.getChildren();
            X509Certificate[] certificates = new X509Certificate[certElements.size()];
            try {
                CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
                int i = 0;
                for (final Element certElement : certElements) {
                    final String cert = certElement.getContent();
                    if (cert == null) {
                        continue;
                    }
                    certificates[i] =
                            (X509Certificate)
                                    certificateFactory.generateCertificate(
                                            new ByteArrayInputStream(
                                                    BaseEncoding.base64().decode(cert)));
                    ++i;
                }
                return new Pair<>(certificates, BaseEncoding.base64().decode(signature));
            } catch (CertificateException e) {
                return null;
            }
        } else {
            return null;
        }
    }

    public static PreKeyBundle bundle(final Iq bundle) {
        final Element bundleItem = getItem(bundle);
        if (bundleItem == null) {
            return null;
        }
        final Element bundleElement = bundleItem.findChild("bundle");
        if (bundleElement == null) {
            return null;
        }
        final ECPublicKey signedPreKeyPublic = signedPreKeyPublic(bundleElement);
        final Integer signedPreKeyId = signedPreKeyId(bundleElement);
        final byte[] signedPreKeySignature = signedPreKeySignature(bundleElement);
        final IdentityKey identityKey = identityKey(bundleElement);
        if (signedPreKeyId == null
                || signedPreKeyPublic == null
                || identityKey == null
                || signedPreKeySignature == null
                || signedPreKeySignature.length == 0) {
            return null;
        }
        try {
            // libsignal 0.94.1: deviceId must be non-zero; preKeyId sentinel for "absent" is -1
            // (not 0); KEM placeholder satisfies mandatory Kyber fields without a real key.
            final KEMKeyPair kemPlaceholder = KEMKeyPair.generate(KEMKeyType.MLKEM1024);
            return new PreKeyBundle(
                    0,
                    1,
                    -1,
                    null,
                    signedPreKeyId,
                    signedPreKeyPublic,
                    signedPreKeySignature,
                    identityKey,
                    0,
                    kemPlaceholder.getPublicKey(),
                    new byte[0]);
        } catch (final IllegalArgumentException e) {
            Log.w(Config.LOGTAG, "IqParser.bundle: failed to build PreKeyBundle: " + e.getMessage());
            return null;
        }
    }

    /** Parse an OMEMO2 device list item. */
    public static Set<Integer> omemo2DeviceIds(final Element item) {
        final Set<Integer> ids = new HashSet<>();
        if (item == null) return ids;
        final Element devices = item.findChild("devices", Namespace.OMEMO2);
        if (devices == null) return ids;
        for (final Element device : devices.getChildren()) {
            if (!"device".equals(device.getName())) continue;
            try {
                final int id = Integer.parseInt(device.getAttribute("id"));
                if (id > 0) ids.add(id);
            } catch (final NumberFormatException e) {
                Log.w(Config.LOGTAG, "OMEMO2: invalid device id: " + device);
            }
        }
        return ids;
    }

    /** Parse an OMEMO2 bundle from a pubsub IQ result. */
    public static PreKeyBundle omemo2Bundle(final Iq packet) {
        final Element item = getItem(packet);
        if (item == null) return null;
        final Element bundle = item.findChild("bundle", Namespace.OMEMO2);
        if (bundle == null) return null;
        return omemo2BundleFromElement(bundle);
    }

    private static PreKeyBundle omemo2BundleFromElement(final Element bundle) {
        // <spk id='N'>b64</spk>
        final Element spkEl = bundle.findChild("spk");
        if (spkEl == null) return null;
        Integer spkId;
        try {
            spkId = Integer.valueOf(spkEl.getAttribute("id"));
        } catch (final NumberFormatException e) {
            return null;
        }
        final String spkContent = spkEl.getContent();
        if (spkContent == null) return null;
        ECPublicKey spk;
        try {
            spk = decodeOmemo2EcPublicKey(base64decode(spkContent));
        } catch (final Exception e) {
            Log.w(Config.LOGTAG, "OMEMO2: invalid spk: " + e.getMessage());
            return null;
        }
        // <spks>b64</spks>
        final String spksContent = bundle.findChildContent("spks");
        if (spksContent == null) return null;
        byte[] spks;
        try {
            spks = base64decode(spksContent);
        } catch (final Exception e) {
            return null;
        }
        // <ik>b64</ik>
        final String ikContent = bundle.findChildContent("ik");
        if (ikContent == null) return null;
        IdentityKey ik;
        try {
            ik = new IdentityKey(decodeOmemo2EcPublicKey(base64decode(ikContent)));
        } catch (final Exception e) {
            Log.w(Config.LOGTAG, "OMEMO2: invalid ik: " + e.getMessage());
            return null;
        }
        // <kem-spk id='N'>b64</kem-spk> and <kem-spks>b64</kem-spks> (PQXDH)
        KEMPublicKey kemSpkPublic = null;
        byte[] kemSpkSig = null;
        final Element kemSpkEl = bundle.findChild("kem-spk");
        final String kemSpksContent = bundle.findChildContent("kem-spks");
        if (kemSpkEl != null && kemSpksContent != null
                && hasFixedAlgorithm(kemSpkEl, PQ_KEM_ALGORITHM)) {
            final String kemSpkContent = kemSpkEl.getContent();
            if (kemSpkContent != null) {
                try {
                    final byte[] serialized = base64decode(kemSpkContent);
                    if (isMlKem1024(serialized, "kem-spk")) {
                        kemSpkPublic = new KEMPublicKey(serialized);
                        kemSpkSig = base64decode(kemSpksContent);
                    }
                } catch (final Exception e) {
                    Log.w(Config.LOGTAG, "OMEMO2: invalid kem-spk: " + e.getMessage());
                }
            }
        }
        if (kemSpkPublic == null) {
            // No usable <kem-spk>: absent, malformed, or not ML-KEM-1024. Refuse the bundle
            // outright rather than substituting a placeholder key. The old placeholder still
            // failed closed — the recomputed KEM binding could not match the signed one — but it
            // burned an ML-KEM keygen on every bad bundle and reported the failure as an opaque
            // signature mismatch instead of naming the real cause.
            Log.w(Config.LOGTAG, "OMEMO2: bundle has no usable <kem-spk> — refusing");
            return null;
        }
        Integer kemSpkId = 0;
        if (kemSpkEl != null) {
            try {
                kemSpkId = Integer.valueOf(kemSpkEl.getAttribute("id"));
            } catch (final NumberFormatException e) {
                kemSpkId = 0;
            }
        }
        try {
            return new PreKeyBundle(0, 1, -1, null, spkId, spk, spks, ik, kemSpkId, kemSpkPublic, kemSpkSig);
        } catch (final IllegalArgumentException e) {
            Log.w(Config.LOGTAG, "IqParser.omemo2Bundle: failed to build PreKeyBundle: " + e.getMessage());
            return null;
        }
    }

    /** Parse OMEMO2 EC prekey public keys from a bundle IQ result. */
    public static Map<Integer, ECPublicKey> omemo2PreKeyPublics(final Iq packet) {
        final Map<Integer, ECPublicKey> result = new HashMap<>();
        final Element item = getItem(packet);
        if (item == null) return result;
        final Element bundle = item.findChild("bundle", Namespace.OMEMO2);
        if (bundle == null) return result;
        final Element prekeys = bundle.findChild("prekeys");
        if (prekeys == null) return result;
        for (final Element pk : prekeys.getChildren()) {
            if (!"pk".equals(pk.getName())) continue;
            final String pkContent = pk.getContent();
            if (pkContent == null) continue;
            int pkId;
            try {
                pkId = Integer.parseInt(pk.getAttribute("id"));
            } catch (final NumberFormatException e) {
                continue;
            }
            try {
                result.put(pkId, decodeOmemo2EcPublicKey(base64decode(pkContent)));
            } catch (final Exception e) {
                Log.w(Config.LOGTAG, "OMEMO2: invalid pk (id=" + pkId + "): " + e.getMessage());
            }
        }
        return result;
    }

    /** Parse OMEMO2 one-time KEM prekeys from a bundle IQ result. */
    public static List<KemBundleKey> omemo2KemPreKeys(final Iq packet) {
        final List<KemBundleKey> keys = new ArrayList<>();
        final Element item = getItem(packet);
        if (item == null) return keys;
        final Element bundle = item.findChild("bundle", Namespace.OMEMO2);
        if (bundle == null) return keys;
        final Element kemPrekeys = bundle.findChild("kem-prekeys");
        if (kemPrekeys == null) return keys;
        for (final Element kemPk : kemPrekeys.getChildren()) {
            if (!"kem-pk".equals(kemPk.getName())) continue;
            if (!hasFixedAlgorithm(kemPk, PQ_KEM_ALGORITHM)) continue;
            final String content = kemPk.getContent();
            if (content == null) continue;
            int id;
            try {
                id = Integer.parseInt(kemPk.getAttribute("id"));
            } catch (final NumberFormatException e) {
                continue;
            }
            final String sigAttr = kemPk.getAttribute("sig");
            final byte[] sig = (sigAttr != null) ? base64decode(sigAttr) : new byte[0];
            try {
                final byte[] serialized = base64decode(content);
                if (!isMlKem1024(serialized, "kem-pk")) continue;
                keys.add(new KemBundleKey(id, new KEMPublicKey(serialized), sig));
            } catch (final Exception e) {
                Log.w(Config.LOGTAG, "OMEMO2: invalid kem-pk (id=" + id + "): " + e.getMessage());
            }
        }
        return keys;
    }

    public static final class KemBundleKey {
        public final int id;
        public final KEMPublicKey publicKey;
        public final byte[] signature;

        public KemBundleKey(final int id, final KEMPublicKey publicKey, final byte[] signature) {
            this.id = id;
            this.publicKey = publicKey;
            this.signature = signature;
        }
    }

    /** The monocles PQ-OMEMO2 hybrid identity carried in a bundle. */
    public static final class PqIdentity {
        public final byte[] identityKey;
        public final byte[] signature;

        public PqIdentity(final byte[] identityKey, final byte[] signature) {
            this.identityKey = identityKey;
            this.signature = signature;
        }
    }

    /**
     * Parse the post-quantum hybrid identity ({@code <pq-ik>} / {@code <pq-sig>})
     * from an OMEMO2 bundle IQ result, or null if absent/malformed. A null result
     * means the peer published no PQ identity; this build refuses such bundles
     * (never downgrade) — enforcement is in {@code buildSessionFromOmemo2PEP}.
     */
    public static PqIdentity omemo2PqIdentity(final Iq packet) {
        final Element item = getItem(packet);
        if (item == null) return null;
        final Element bundle = item.findChild("bundle", Namespace.OMEMO2);
        if (bundle == null) return null;
        final Element pqIkEl = bundle.findChild("pq-ik");
        if (pqIkEl == null || !hasFixedAlgorithm(pqIkEl, PQ_IDENTITY_ALGORITHM)) return null;
        final String pqIkContent = pqIkEl.getContent();
        final String pqSigContent = bundle.findChildContent("pq-sig");
        if (pqIkContent == null || pqSigContent == null) return null;
        try {
            return new PqIdentity(base64decode(pqIkContent), base64decode(pqSigContent));
        } catch (final Exception e) {
            Log.w(Config.LOGTAG, "OMEMO2: invalid pq-ik/pq-sig: " + e.getMessage());
            return null;
        }
    }

    /** The signature algorithm {@code urn:monocles:omemo-pq:1} fixes for {@code <pq-ik>}. */
    private static final String PQ_IDENTITY_ALGORITHM = "ML-DSA-87";

    /** The KEM algorithm {@code urn:monocles:omemo-pq:1} fixes for {@code <kem-spk>}/{@code <kem-pk>}. */
    private static final String PQ_KEM_ALGORITHM = "ML-KEM-1024";

    /**
     * Reject any KEM public key that is not FIPS 203 ML-KEM-1024 (proto-XEP §5.1.1).
     *
     * <p>This is the one check that cannot be skipped as pedantry. Round-3 CRYSTALS-Kyber-1024 has
     * byte-identical key and ciphertext sizes and deserializes perfectly happily — libsignal still
     * supports it — but derives a different shared secret. Without this test a peer publishing
     * Round-3 keys would pass signature verification (both sides hash the same bytes) and we would
     * silently complete a PQXDH handshake on the weaker, superseded, non-standard algorithm while
     * the signed transcript asserts ML-KEM-1024. The tag byte is the only thing that distinguishes
     * them on the wire.
     */
    private static boolean isMlKem1024(final byte[] serializedKey, final String element) {
        if (eu.siacs.conversations.utils.CryptoHelper.isMlKem1024PublicKey(serializedKey)) {
            return true;
        }
        Log.w(Config.LOGTAG, "OMEMO2: refusing <" + element + "> that is not ML-KEM-1024 (tag 0x"
                + (serializedKey.length > 0 ? String.format("%02x", serializedKey[0]) : "??")
                + ", expected 0x0a)");
        return false;
    }

    /**
     * This namespace version fixes one algorithm per key type, so a {@code type} attribute is
     * decorative: absent means the fixed algorithm, and naming the fixed algorithm is equally fine.
     * Anything else is refused rather than parsed as the fixed algorithm anyway.
     *
     * <p>Failing closed matters because {@code type} is not itself covered by the bundle signature:
     * the v3 transcript binds the algorithm *identifiers this build uses* (see {@code
     * pq_bundle_transcript}), not the attribute a peer wrote. Silently reinterpreting a key labelled
     * as some future algorithm would be exactly the downgrade that binding exists to prevent, so a
     * second algorithm must arrive with a transcript version bump, not with a new attribute value.
     */
    private static boolean hasFixedAlgorithm(final Element element, final String algorithm) {
        final String type = element.getAttribute("type");
        if (type == null || algorithm.equals(type)) return true;
        Log.w(Config.LOGTAG, "OMEMO2: refusing <" + element.getName() + "> with unsupported type='"
                + type + "' (this version implements " + algorithm + " only)");
        return false;
    }

    private boolean isFromConference(final Iq packet) {
        final Jid from = packet.getFrom();
        return from != null && mXmppConnectionService.isMuc(account, from);
    }

    private static boolean revealsClientIdentity(final Iq packet) {
        return packet.hasChild("query", "http://jabber.org/protocol/disco#info")
                || packet.hasChild("query", "jabber:iq:version")
                || packet.hasChild("time", "urn:xmpp:time");
    }

    @Override
    public void accept(final Iq packet) {
        final boolean isGet = packet.getType() == Iq.Type.GET;
        final boolean isRequest = isGet || packet.getType() == Iq.Type.SET;
        if (packet.getType() == Iq.Type.ERROR || packet.getType() == Iq.Type.TIMEOUT) {
            return;
        }
        // Same rule as in PresenceParser: an unparsable 'from' cannot be compared against
        // conversations or roster entries, and we could not address a reply to it either.
        final Jid sender = packet.getFrom();
        if (sender != null && !Jid.Invalid.isValid(sender)) {
            Log.d(
                    Config.LOGTAG,
                    account.getJid().asBareJid()
                            + ": ignoring iq with unparsable from '"
                            + sender
                            + "'");
            return;
        }
        if (packet.hasChild("query", Namespace.ROSTER) && packet.fromServer(account)) {
            final Element query = packet.findChild("query");
            // If this is in response to a query for the whole roster:
            if (packet.getType() == Iq.Type.RESULT) {
                account.getRoster().markAllAsNotInRoster();
            }
            this.rosterItems(account, query);
        } else if (packet.hasChild("pubsub", Namespace.PUBSUB)) {
            Element pubsub = packet.findChild("pubsub", Namespace.PUBSUB);
            Element items = pubsub.findChild("items");
            if (items != null) {
                String node = items.getAttribute("node");
                if (Namespace.PUBSUB_STORIES.equals(node)) {
                    Jid from = packet.getFrom();
                    if (from != null) {
                        for (Element item : items.getChildren()) {
                            if (item.getName().equals("item")) {
                                Story story = Story.fromElement(item, from);
                                if (story != null) {
                                    mXmppConnectionService.onStoryReceived(story);
                                }
                            }
                        }
                    }
                } else if (node != null && node.equals(Namespace.ATOM) || node != null && node.startsWith("urn:xmpp:microblog:0") || node != null && node.startsWith(Namespace.PUBSUB_SOCIAL_FEED)) {
                    for (Element child : items.getChildren()) {
                        if ("item".equals(child.getName())) {
                            Element entry = child.findChild("entry", Namespace.ATOM);
                            if (entry != null) {
                                try {
                                    Element inReplyTo = entry.findChild("in-reply-to", "http://purl.org/syndication/thread/1.0");
                                    if (inReplyTo != null) {
                                        Comment comment = Comment.fromElement(entry);
                                        String originalPostUuid = inReplyTo.getAttribute("ref");
                                        if (originalPostUuid != null && originalPostUuid.startsWith("urn:uuid:")) {
                                            originalPostUuid = originalPostUuid.substring(9);
                                        }
                                        mXmppConnectionService.notifyOnCommentReceived(originalPostUuid, comment);
                                    } else {
                                        // Handle items that are not comments as new posts.
                                        Post post = Post.fromElement(child);
                                        if (post != null) {
                                            mXmppConnectionService.onPostReceived(post, account);
                                        }
                                    }
                                } catch (Exception e) {
                                    Log.d(Config.LOGTAG, "error creating post/comment from pubsub item in message", e);
                                }
                            }
                        } else if ("retract".equals(child.getName())) {
                            final String postId = child.getAttribute("id");
                            if (postId != null) {
                                mXmppConnectionService.onPostRetracted(postId);
                            }
                        }
                    }
                }
            }
        } else if ((packet.hasChild("block", Namespace.BLOCKING)
                || packet.hasChild("blocklist", Namespace.BLOCKING))
                && packet.fromServer(account)) {
            // Block list or block push.
            Log.d(Config.LOGTAG, "Received blocklist update from server");
            final Element blocklist = packet.findChild("blocklist", Namespace.BLOCKING);
            final Element block = packet.findChild("block", Namespace.BLOCKING);
            final Collection<Element> items =
                    blocklist != null
                            ? blocklist.getChildren()
                            : (block != null ? block.getChildren() : null);
            // If this is a response to a blocklist query, clear the block list and replace with the
            // new one.
            // Otherwise, just update the existing blocklist.
            if (packet.getType() == Iq.Type.RESULT) {
                account.clearBlocklist();
                account.getXmppConnection().getFeatures().setBlockListRequested(true);
            }
            if (items != null) {
                final Collection<Jid> jids = new ArrayList<>(items.size());
                // Create a collection of Jids from the packet
                for (final Element item : items) {
                    if (item.getName().equals("item")) {
                        final Jid jid =
                                Jid.Invalid.getNullForInvalid(item.getAttributeAsJid("jid"));
                        if (jid != null) {
                            jids.add(jid);
                        }
                    }
                }
                account.getBlocklist().addAll(jids);
                if (packet.getType() == Iq.Type.SET) {
                    boolean removed = false;
                    for (Jid jid : jids) {
                        removed |= mXmppConnectionService.removeBlockedConversations(account, jid);
                    }
                    if (removed) {
                        mXmppConnectionService.updateConversationUi();
                    }
                }
            }
            // Update the UI
            mXmppConnectionService.updateBlocklistUi(OnUpdateBlocklist.Status.BLOCKED);
            if (packet.getType() == Iq.Type.SET) {
                final Iq response = packet.generateResponse(Iq.Type.RESULT);
                mXmppConnectionService.sendIqPacket(account, response, null);
            }
        } else if (packet.hasChild("unblock", Namespace.BLOCKING)
                && packet.fromServer(account)
                && packet.getType() == Iq.Type.SET) {
            Log.d(Config.LOGTAG, "Received unblock update from server");
            final Collection<Element> items =
                    packet.findChild("unblock", Namespace.BLOCKING).getChildren();
            if (items.isEmpty()) {
                // No children to unblock == unblock all
                account.getBlocklist().clear();
            } else {
                final Collection<Jid> jids = new ArrayList<>(items.size());
                for (final Element item : items) {
                    if (item.getName().equals("item")) {
                        final Jid jid =
                                Jid.Invalid.getNullForInvalid(item.getAttributeAsJid("jid"));
                        if (jid != null) {
                            jids.add(jid);
                        }
                    }
                }
                account.getBlocklist().removeAll(jids);
            }
            mXmppConnectionService.updateBlocklistUi(OnUpdateBlocklist.Status.UNBLOCKED);
            final Iq response = packet.generateResponse(Iq.Type.RESULT);
            mXmppConnectionService.sendIqPacket(account, response, null);
        } else if (packet.hasChild("open", "http://jabber.org/protocol/ibb")
                || packet.hasChild("data", "http://jabber.org/protocol/ibb")
                || packet.hasChild("close", "http://jabber.org/protocol/ibb")) {
            mXmppConnectionService.getJingleConnectionManager().deliverIbbPacket(account, packet);
        } else if (isRequest && isFromConference(packet) && revealsClientIdentity(packet)) {
            final Iq response = packet.generateResponse(Iq.Type.ERROR);
            final Element error = response.addChild("error");
            error.setAttribute("type", "cancel");
            error.addChild("service-unavailable", "urn:ietf:params:xml:ns:xmpp-stanzas");
            mXmppConnectionService.sendIqPacket(account, response, null);
        } else if (packet.hasChild("query", "http://jabber.org/protocol/disco#info")) {
            final Iq response =
                    mXmppConnectionService.getIqGenerator().discoResponse(account, packet);
            mXmppConnectionService.sendIqPacket(account, response, null);
        } else if (packet.hasChild("query", "jabber:iq:version") && isGet) {
            final Iq response = mXmppConnectionService.getIqGenerator().versionResponse(packet);
            mXmppConnectionService.sendIqPacket(account, response, null);
        } else if (packet.hasChild("ping", "urn:xmpp:ping") && isGet) {
            final Iq response = packet.generateResponse(Iq.Type.RESULT);
            mXmppConnectionService.sendIqPacket(account, response, null);
        } else if (packet.hasChild("time", "urn:xmpp:time") && isGet) {
            final Iq response;
            if (mXmppConnectionService.useTorToConnect() || account.isOnion()) {
                response = packet.generateResponse(Iq.Type.ERROR);
                final Element error = response.addChild("error");
                error.setAttribute("type", "cancel");
                error.addChild("not-allowed", "urn:ietf:params:xml:ns:xmpp-stanzas");
            } else {
                response = mXmppConnectionService.getIqGenerator().entityTimeResponse(packet);
            }
            mXmppConnectionService.sendIqPacket(account, response, null);
        } else if (packet.hasChild("push", Namespace.UNIFIED_PUSH)
                && packet.getType() == Iq.Type.SET) {
            final Jid transport = packet.getFrom();
            final Element push = packet.findChild("push", Namespace.UNIFIED_PUSH);
            final boolean success =
                    push != null
                            && mXmppConnectionService.processUnifiedPushMessage(
                            account, transport, push);
            final Iq response;
            if (success) {
                response = packet.generateResponse(Iq.Type.RESULT);
            } else {
                response = packet.generateResponse(Iq.Type.ERROR);
                final Element error = response.addChild("error");
                error.setAttribute("type", "cancel");
                error.setAttribute("code", "404");
                error.addChild("item-not-found", "urn:ietf:params:xml:ns:xmpp-stanzas");
            }
            mXmppConnectionService.sendIqPacket(account, response, null);
        } else if (packet.getFrom() != null) {
            final Contact contact = account.getRoster().getContact(packet.getFrom());
            final Conversation conversation = mXmppConnectionService.find(account, packet.getFrom());
            if (packet.hasChild("data", "urn:xmpp:bob") && isGet && (conversation == null ? contact != null && contact.canInferPresence() : conversation.canInferPresence())) {
                mXmppConnectionService.sendIqPacket(account, mXmppConnectionService.getIqGenerator().bobResponse(packet), null);
            } else if (packet.getType() == Iq.Type.GET || packet.getType() == Iq.Type.SET) {
                final var response = packet.generateResponse(Iq.Type.ERROR);
                final Element error = response.addChild("error");
                error.setAttribute("type", "cancel");
                error.addChild("feature-not-implemented", "urn:ietf:params:xml:ns:xmpp-stanzas");
                account.getXmppConnection().sendIqPacket(response, null);
            }
        }
    }
}
