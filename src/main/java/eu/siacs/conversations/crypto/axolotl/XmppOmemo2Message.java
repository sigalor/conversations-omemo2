package eu.siacs.conversations.crypto.axolotl;

import android.util.Base64;
import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.Mac;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xml.Namespace;
import eu.siacs.conversations.xml.Tag;
import eu.siacs.conversations.xml.XmlReader;
import eu.siacs.conversations.xmpp.Jid;

/**
 * OMEMO2 message (XEP-0384) with Stanza Content Encryption (XEP-0420).
 * AES-256-CBC + HMAC-SHA-256, HKDF-SHA-256 key derivation.
 * Uses the post-quantum (PQXDH) Signal Protocol sessions exclusively; these are
 * kept strictly separate from the legacy XEP-0384 v0.3 sessions and are never
 * shared with or reused from the legacy stack.
 */
public class XmppOmemo2Message {

    private static final String KEYTYPE = "AES";
    private static final String CIPHER_MODE = "AES/CBC/PKCS5Padding";
    private static final String HMAC_ALG = "HmacSHA256";
    private static final String HKDF_INFO = "OMEMO Message Key Material";
    private static final int MSG_KEY_LENGTH = 32;
    private static final int HKDF_OUTPUT_LENGTH = 80;
    private static final int MAC_LENGTH = 16;

    private final Jid from;
    private final int sourceDeviceId;
    private final Map<Jid, List<XmppAxolotlSession.AxolotlKey>> keysByJid = new HashMap<>();
    private byte[] messageKey;
    private byte[] payload;

    /** Result of decrypting an OMEMO2 payload. */
    public static class DecryptedSce {
        /** Plaintext body, or null if the SCE envelope contained no {@code <body>}. */
        public final String body;
        /** All elements inside SCE {@code <content>} (including {@code <body>} if present). */
        public final List<Element> elements;
        public final String fingerprint;

        DecryptedSce(final String body, final List<Element> elements, final String fingerprint) {
            this.body = body;
            this.elements = elements != null ? elements : Collections.emptyList();
            this.fingerprint = fingerprint;
        }
    }

    public XmppOmemo2Message(final Jid from, final int sourceDeviceId) {
        this.from = from;
        this.sourceDeviceId = sourceDeviceId;
        this.messageKey = new byte[MSG_KEY_LENGTH];
        new SecureRandom().nextBytes(this.messageKey);
    }

    private XmppOmemo2Message(final Element element, final Jid from) throws IllegalArgumentException {
        this.from = from;
        final Element header = element.findChild("header");
        if (header == null) throw new IllegalArgumentException("no header element");
        try {
            final int sid = Integer.parseInt(header.getAttribute("sid"));
            if (sid <= 0) throw new IllegalArgumentException("invalid sid: " + sid);
            this.sourceDeviceId = sid;
        } catch (final NumberFormatException e) {
            throw new IllegalArgumentException("invalid sid attribute");
        }
        for (final Element keysElement : header.getChildren()) {
            if (!"keys".equals(keysElement.getName())) continue;
            final String jidAttr = keysElement.getAttribute("jid");
            if (jidAttr == null) continue;
            final Jid jid;
            try {
                jid = Jid.of(jidAttr).asBareJid();
            } catch (final Exception e) {
                Log.w(Config.LOGTAG, "OMEMO2: invalid JID in keys element: " + jidAttr);
                continue;
            }
            final List<XmppAxolotlSession.AxolotlKey> keys = new ArrayList<>();
            for (final Element keyElement : keysElement.getChildren()) {
                if (!"key".equals(keyElement.getName())) continue;
                try {
                    final int rid = Integer.parseInt(keyElement.getAttribute("rid"));
                    final boolean kex = Boolean.parseBoolean(keyElement.getAttribute("kex"));
                    final String content = keyElement.getContent();
                    if (content == null) continue;
                    keys.add(new XmppAxolotlSession.AxolotlKey(rid,
                            Base64.decode(content.trim(), Base64.DEFAULT), kex));
                } catch (final Exception e) {
                    Log.w(Config.LOGTAG, "OMEMO2: invalid key element: " + e.getMessage());
                }
            }
            if (!keys.isEmpty()) keysByJid.put(jid, keys);
        }
        final Element payloadElement = element.findChild("payload");
        if (payloadElement != null) {
            final String content = payloadElement.getContent();
            if (content != null) this.payload = Base64.decode(content.trim(), Base64.DEFAULT);
        }
    }

    public static XmppOmemo2Message fromElement(final Element element, final Jid from) {
        try {
            return new XmppOmemo2Message(element, from);
        } catch (final IllegalArgumentException e) {
            Log.w(Config.LOGTAG, "OMEMO2: could not parse message: " + e.getMessage());
            return null;
        }
    }

    public static int parseSourceId(final Element element) throws IllegalArgumentException {
        final Element header = element.findChild("header");
        if (header == null) throw new IllegalArgumentException("no header element");
        try {
            return Integer.parseInt(header.getAttribute("sid"));
        } catch (final NumberFormatException e) {
            throw new IllegalArgumentException("invalid sid");
        }
    }

    /**
     * Encrypt content into an SCE envelope.
     *
     * @param body         plaintext message body, or null if this is a metadata-only stanza
     * @param extraContent additional elements to place inside SCE {@code <content>} (e.g.
     *                     {@code <live-location-update>}), may be null
     * @param toJid        recipient JID (used for {@code <to>} in MUC envelopes)
     * @param isMuc        whether the conversation is a MUC
     */
    public void encrypt(final String body, final List<Element> extraContent,
            final Jid toJid, final boolean isMuc) throws CryptoFailedException {
        try {
            final byte[] derived = hkdf(messageKey, HKDF_INFO, HKDF_OUTPUT_LENGTH);
            final byte[] encKey = new byte[MSG_KEY_LENGTH];
            final byte[] authKey = new byte[MSG_KEY_LENGTH];
            final byte[] iv = new byte[16];
            System.arraycopy(derived, 0, encKey, 0, MSG_KEY_LENGTH);
            System.arraycopy(derived, MSG_KEY_LENGTH, authKey, 0, MSG_KEY_LENGTH);
            System.arraycopy(derived, MSG_KEY_LENGTH * 2, iv, 0, 16);

            final byte[] envelopeBytes = buildSceEnvelope(body, extraContent, toJid, isMuc)
                    .getBytes(StandardCharsets.UTF_8);

            final Cipher cipher = Cipher.getInstance(CIPHER_MODE);
            cipher.init(Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(encKey, KEYTYPE), new IvParameterSpec(iv));
            final byte[] ct = cipher.doFinal(envelopeBytes);

            final Mac mac = Mac.getInstance(HMAC_ALG);
            mac.init(new SecretKeySpec(authKey, HMAC_ALG));
            final byte[] fullMac = mac.doFinal(ct);

            this.payload = new byte[ct.length + MAC_LENGTH];
            System.arraycopy(ct, 0, this.payload, 0, ct.length);
            System.arraycopy(fullMac, 0, this.payload, ct.length, MAC_LENGTH);

        } catch (final NoSuchAlgorithmException | NoSuchPaddingException | InvalidKeyException
                | InvalidAlgorithmParameterException | IllegalBlockSizeException
                | BadPaddingException e) {
            throw new CryptoFailedException(e);
        }
    }

    public void addDevice(final XmppAxolotlSession session) {
        addDevice(session, false);
    }

    public void addDevice(final XmppAxolotlSession session, final boolean ignoreSessionTrust) {
        final XmppAxolotlSession.AxolotlKey key = session.processSending(messageKey, ignoreSessionTrust);
        if (key == null) return;
        try {
            final Jid jid = Jid.of(session.getRemoteAddress().getName()).asBareJid();
            keysByJid.computeIfAbsent(jid, k -> new ArrayList<>()).add(key);
        } catch (final Exception e) {
            Log.w(Config.LOGTAG, "OMEMO2: could not parse JID from session address: "
                    + session.getRemoteAddress().getName());
        }
    }

    public boolean hasPayload() {
        return payload != null;
    }

    public Jid getFrom() {
        return from;
    }

    public int getSenderDeviceId() {
        return sourceDeviceId;
    }

    public Element toElement() {
        final Element encrypted = new Element("encrypted", Namespace.OMEMO2);
        final Element header = encrypted.addChild("header");
        header.setAttribute("sid", sourceDeviceId);
        for (final Map.Entry<Jid, List<XmppAxolotlSession.AxolotlKey>> entry : keysByJid.entrySet()) {
            final Element keysEl = header.addChild("keys");
            keysEl.setAttribute("jid", entry.getKey().asBareJid().toString());
            for (final XmppAxolotlSession.AxolotlKey k : entry.getValue()) {
                final Element keyEl = new Element("key");
                keyEl.setAttribute("rid", k.deviceId);
                if (k.prekey) keyEl.setAttribute("kex", "true");
                keyEl.setContent(Base64.encodeToString(k.key, Base64.NO_WRAP));
                keysEl.addChild(keyEl);
            }
        }
        if (payload != null) {
            encrypted.addChild("payload")
                    .setContent(Base64.encodeToString(payload, Base64.NO_WRAP));
        }
        return encrypted;
    }

    /**
     * Decrypt the payload.
     *
     * @param expectedTo  the JID the SCE {@code <to>} element must match (XEP-0420 §4.5).
     *                    For 1:1 chats: the recipient JID as seen on the outer stanza
     *                    (own account JID for incoming, counterpart JID for carbon-sent).
     *                    For MUC: the room bare JID.
     * @return {@link DecryptedSce} containing the body (if any), all SCE content elements,
     *         and the sender fingerprint; or null if there is no payload
     */
    public DecryptedSce decrypt(final XmppAxolotlSession session, final int ownDeviceId,
            final Jid ownBareJid, final Jid expectedTo) throws CryptoFailedException {
        if (!hasPayload()) return null;
        final byte[] msgKey = extractKey(session, ownDeviceId, ownBareJid);
        if (msgKey == null) return null;
        if (msgKey.length != MSG_KEY_LENGTH) {
            throw new CryptoFailedException(
                    "OMEMO2 message key must be 32 bytes, got " + msgKey.length);
        }
        return decryptPayload(msgKey, session.getFingerprint(), this.from, expectedTo);
    }

    /**
     * Pick out the wrapped key(s) addressed to our device. Per XEP-0420 / XEP-0384
     * we MUST only consider {@code <keys jid="…">} entries whose {@code jid}
     * matches our own bare JID — a malicious sender could otherwise stuff a key
     * for our device under another user's {@code <keys>} block to confuse
     * routing or trick us into using a session we didn't expect.
     */
    private byte[] extractKey(final XmppAxolotlSession session, final int ownDeviceId,
            final Jid ownBareJid) throws CryptoFailedException {
        if (ownBareJid == null) {
            throw new CryptoFailedException("own JID not supplied for key extraction");
        }
        final List<XmppAxolotlSession.AxolotlKey> own =
                keysByJid.get(ownBareJid.asBareJid());
        if (own == null) throw new NotEncryptedForThisDeviceException();
        final List<XmppAxolotlSession.AxolotlKey> candidates = new ArrayList<>();
        for (final XmppAxolotlSession.AxolotlKey k : own) {
            if (k.deviceId == ownDeviceId) candidates.add(k);
        }
        if (candidates.isEmpty()) throw new NotEncryptedForThisDeviceException();
        return session.processReceiving(candidates);
    }

    private DecryptedSce decryptPayload(final byte[] msgKey, final String fingerprint,
            final Jid expectedFrom, final Jid expectedTo) throws CryptoFailedException {
        try {
            final byte[] derived = hkdf(msgKey, HKDF_INFO, HKDF_OUTPUT_LENGTH);
            final byte[] encKey = new byte[MSG_KEY_LENGTH];
            final byte[] authKey = new byte[MSG_KEY_LENGTH];
            final byte[] iv = new byte[16];
            System.arraycopy(derived, 0, encKey, 0, MSG_KEY_LENGTH);
            System.arraycopy(derived, MSG_KEY_LENGTH, authKey, 0, MSG_KEY_LENGTH);
            System.arraycopy(derived, MSG_KEY_LENGTH * 2, iv, 0, 16);

            final int ctLen = payload.length - MAC_LENGTH;
            if (ctLen < 0) throw new CryptoFailedException("OMEMO2 payload too short");

            final byte[] ct = new byte[ctLen];
            final byte[] receivedMac = new byte[MAC_LENGTH];
            System.arraycopy(payload, 0, ct, 0, ctLen);
            System.arraycopy(payload, ctLen, receivedMac, 0, MAC_LENGTH);

            final Mac mac = Mac.getInstance(HMAC_ALG);
            mac.init(new SecretKeySpec(authKey, HMAC_ALG));
            final byte[] computedMac = new byte[MAC_LENGTH];
            System.arraycopy(mac.doFinal(ct), 0, computedMac, 0, MAC_LENGTH);
            if (!MessageDigest.isEqual(computedMac, receivedMac)) {
                throw new CryptoFailedException("OMEMO2 HMAC verification failed");
            }

            final Cipher cipher = Cipher.getInstance(CIPHER_MODE);
            cipher.init(Cipher.DECRYPT_MODE,
                    new SecretKeySpec(encKey, KEYTYPE), new IvParameterSpec(iv));
            final byte[] plaintext = cipher.doFinal(ct);

            return parseSceContent(plaintext, fingerprint, expectedFrom, expectedTo);

        } catch (final NoSuchAlgorithmException | NoSuchPaddingException | InvalidKeyException
                | InvalidAlgorithmParameterException | IllegalBlockSizeException
                | BadPaddingException e) {
            throw new CryptoFailedException(e);
        }
    }

    // --- SCE envelope build/parse ---

    /**
     * Build the SCE envelope using the project's XML model. Going through
     * {@link Element#toString()} ensures attributes and element content are
     * escaped by a single, audited serializer (no ad-hoc {@code &}/{@code <}
     * replacement that could miss e.g. control characters in user-supplied
     * filenames or display names).
     */
    private String buildSceEnvelope(final String body, final List<Element> extraContent,
            final Jid toJid, final boolean isMuc) {
        final Element envelope = new Element("envelope", Namespace.SCE);
        final Element content = envelope.addChild("content");
        // Only emit a <body> when there is actual text. An empty <body></body>
        // decodes to "" on the peer (Element.getContent() joins zero text nodes
        // to an empty string, not null) and would otherwise render as a blank
        // message bubble. Mirrors the receive-side guard in
        // MessageParser.parseOmemo2Chat.
        if (body != null && !body.isEmpty()) {
            content.addChild(new Element("body", "jabber:client").setContent(body));
        }
        if (extraContent != null) {
            for (final Element el : extraContent) {
                content.addChild(el);
            }
        }
        envelope.addChild(new Element("rpad").setContent(generateRpad()));
        // XEP-0420 §4.4 affixed metadata: <from> and <to> bind the envelope to its
        // stanza-level routing, <time> binds it to a wall-clock window so replays
        // can be detected. ISO-8601 UTC.
        envelope.addChild(new Element("time").setAttribute("stamp", currentIsoTimestamp()));
        envelope.addChild(new Element("from").setAttribute("jid", from.asBareJid().toString()));
        if (toJid != null) {
            envelope.addChild(new Element("to").setAttribute("jid", toJid.asBareJid().toString()));
        }
        return envelope.toString();
    }

    private static String currentIsoTimestamp() {
        final java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat(
                "yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US);
        fmt.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        return fmt.format(new java.util.Date());
    }

    /** Maximum allowed clock skew between sender's {@code <time>} and our local clock. */
    private static final long MAX_TIME_SKEW_MS = 7L * 24 * 60 * 60 * 1000; // 7 days

    private static DecryptedSce parseSceContent(final byte[] plaintext, final String fingerprint,
            final Jid expectedFrom, final Jid expectedTo) throws CryptoFailedException {
        try {
            final XmlReader reader = new XmlReader();
            reader.setInputStream(new ByteArrayInputStream(plaintext));
            Tag tag;
            while ((tag = reader.readTag()) != null) {
                if ("envelope".equals(tag.getName())) {
                    final Element envelope = reader.readElement(tag);
                    final Element content = envelope.findChild("content");
                    if (content == null) {
                        throw new CryptoFailedException("SCE envelope missing <content>");
                    }
                    // XEP-0420 §4.5: MUST verify <from jid> matches stanza sender
                    final Element fromEl = envelope.findChild("from");
                    if (fromEl == null) {
                        throw new CryptoFailedException("SCE envelope missing <from>");
                    }
                    final String envelopeFromStr = fromEl.getAttribute("jid");
                    if (envelopeFromStr == null) {
                        throw new CryptoFailedException("SCE <from> missing jid attribute");
                    }
                    try {
                        final Jid envelopeFrom = Jid.of(envelopeFromStr).asBareJid();
                        if (!envelopeFrom.equals(expectedFrom.asBareJid())) {
                            throw new CryptoFailedException(
                                    "SCE <from> mismatch: expected " + expectedFrom.asBareJid()
                                            + " got " + envelopeFrom);
                        }
                    } catch (final IllegalArgumentException e) {
                        throw new CryptoFailedException("SCE <from> invalid JID: " + envelopeFromStr);
                    }
                    // XEP-0420 §4.5: MUST verify <to jid> matches the recipient JID.
                    // Defends against stanza re-routing / cross-context replay where a
                    // ciphertext addressed to one recipient is delivered to another whose
                    // device key also happens to be in the <header>.
                    final Element toEl = envelope.findChild("to");
                    if (toEl == null) {
                        throw new CryptoFailedException("SCE envelope missing <to>");
                    }
                    final String envelopeToStr = toEl.getAttribute("jid");
                    if (envelopeToStr == null) {
                        throw new CryptoFailedException("SCE <to> missing jid attribute");
                    }
                    if (expectedTo == null) {
                        throw new CryptoFailedException("no expected <to> JID for verification");
                    }
                    try {
                        final Jid envelopeTo = Jid.of(envelopeToStr).asBareJid();
                        if (!envelopeTo.equals(expectedTo.asBareJid())) {
                            throw new CryptoFailedException(
                                    "SCE <to> mismatch: expected " + expectedTo.asBareJid()
                                            + " got " + envelopeTo);
                        }
                    } catch (final IllegalArgumentException e) {
                        throw new CryptoFailedException("SCE <to> invalid JID: " + envelopeToStr);
                    }
                    // XEP-0420 §4.5: <time> is optional but if present SHOULD be checked.
                    // We tolerate ±7 days for MAM catch-up and clock drift; outside that
                    // window we reject as a likely replay.
                    final Element timeEl = envelope.findChild("time");
                    if (timeEl != null) {
                        final String stamp = timeEl.getAttribute("stamp");
                        if (stamp != null) {
                            final Long ts = parseIsoTimestamp(stamp);
                            if (ts != null) {
                                final long skew = Math.abs(System.currentTimeMillis() - ts);
                                if (skew > MAX_TIME_SKEW_MS) {
                                    throw new CryptoFailedException(
                                            "SCE <time> outside allowed skew window: " + stamp);
                                }
                            }
                        }
                    }
                    String body = null;
                    final List<Element> elements = new ArrayList<>();
                    for (final Element child : content.getChildren()) {
                        elements.add(child);
                        if ("body".equals(child.getName())) {
                            body = child.getContent();
                        }
                    }
                    return new DecryptedSce(body, elements, fingerprint);
                }
            }
            throw new CryptoFailedException("no SCE <envelope> found in plaintext");
        } catch (final IOException e) {
            throw new CryptoFailedException("failed to parse SCE envelope: " + e.getMessage());
        }
    }

    private static Long parseIsoTimestamp(final String stamp) {
        // Accept both "yyyy-MM-dd'T'HH:mm:ss'Z'" (what we emit) and the
        // millisecond-precision variant some other clients may use.
        final String[] patterns = {
                "yyyy-MM-dd'T'HH:mm:ss'Z'",
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                "yyyy-MM-dd'T'HH:mm:ssXXX",
        };
        for (final String p : patterns) {
            try {
                final java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat(p, java.util.Locale.US);
                fmt.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
                return fmt.parse(stamp).getTime();
            } catch (final java.text.ParseException ignored) {
            }
        }
        return null;
    }

    private static String generateRpad() {
        final SecureRandom rng = new SecureRandom();
        final byte[] bytes = new byte[rng.nextInt(200) + 1];
        rng.nextBytes(bytes);
        return Base64.encodeToString(bytes, Base64.NO_WRAP);
    }

    // --- HKDF ---

    private static byte[] hkdf(final byte[] ikm, final String info, final int length) {
        try {
            final Mac mac = Mac.getInstance(HMAC_ALG);
            mac.init(new SecretKeySpec(new byte[32], HMAC_ALG));
            final byte[] prk = mac.doFinal(ikm);

            final byte[] infoBytes = info.getBytes(StandardCharsets.UTF_8);
            final ByteArrayOutputStream output = new ByteArrayOutputStream(length);
            byte[] prev = new byte[0];
            final int blocks = (length + 31) / 32;
            for (int i = 1; i <= blocks; i++) {
                mac.init(new SecretKeySpec(prk, HMAC_ALG));
                mac.update(prev);
                mac.update(infoBytes);
                mac.update((byte) i);
                prev = mac.doFinal();
                output.write(prev);
            }
            final byte[] result = new byte[length];
            System.arraycopy(output.toByteArray(), 0, result, 0, length);
            return result;
        } catch (final NoSuchAlgorithmException | InvalidKeyException | IOException e) {
            throw new IllegalStateException("HKDF failed", e);
        }
    }
}
