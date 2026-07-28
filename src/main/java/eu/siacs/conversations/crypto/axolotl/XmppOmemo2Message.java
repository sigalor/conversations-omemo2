package eu.siacs.conversations.crypto.axolotl;

import android.util.Base64;
import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.signal.libsignal.protocol.kdf.HKDF;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.parser.AbstractParser;
import eu.siacs.conversations.utils.Random;
import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xml.Namespace;
import eu.siacs.conversations.xml.Tag;
import eu.siacs.conversations.xml.XmlReader;
import eu.siacs.conversations.xmpp.Jid;

/**
 * OMEMO2 message (XEP-0384) with Stanza Content Encryption (XEP-0420).
 * AES-256-GCM, HKDF-SHA-256 key derivation.
 * Uses the post-quantum (PQXDH) Signal Protocol sessions exclusively; these are
 * kept strictly separate from the legacy XEP-0384 v0.3 sessions and are never
 * shared with or reused from the legacy stack.
 */
public class XmppOmemo2Message {

    private static final String KEYTYPE = "AES";
    private static final String CIPHER_MODE = "AES/GCM/NoPadding";
    private static final String HKDF_INFO = "OMEMO Payload";
    private static final int MSG_KEY_LENGTH = 32;
    private static final int IV_LENGTH = 12;
    private static final int HKDF_OUTPUT_LENGTH = MSG_KEY_LENGTH + IV_LENGTH;
    private static final int TAG_LENGTH = 16;
    // Key-commitment HKDF label + length. AES-256-GCM is not a committing AEAD, so a ciphertext
    // can be opened under two different keys (the "invisible salamander"). We publish a single
    // shared commitment to the message key alongside the payload; recipients recompute it from
    // their unwrapped key and reject on mismatch, which makes the scheme key-committing and
    // closes both salamander collisions and malicious-sender equivocation. Distinct info string
    // domain-separates it from the payload key/IV, so it is independent of them and leaks neither.
    private static final byte[] COMMIT_INFO =
            "monocles:omemo2:key-commitment:v1".getBytes(StandardCharsets.UTF_8);
    private static final int COMMIT_LENGTH = 32;

    private final Jid from;
    private final int sourceDeviceId;
    private final Map<Jid, List<XmppAxolotlSession.AxolotlKey>> keysByJid = new HashMap<>();
    private byte[] messageKey;
    private byte[] payload;
    private byte[] commit;
    private boolean messageKeyWiped = false;

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
        Random.SECURE_RANDOM.nextBytes(this.messageKey);
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
        final Element commitElement = element.findChild("commit");
        if (commitElement != null) {
            final String commitContent = commitElement.getContent();
            if (commitContent != null) {
                this.commit = Base64.decode(commitContent.trim(), Base64.DEFAULT);
            }
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
        if (messageKeyWiped) {
            throw new IllegalStateException("message key already wiped");
        }
        try {
            // AAD/Salt: cryptographically bind the derivation and the ciphertext to the context
            final byte[] binding = computeContextBinding(from, toJid, sourceDeviceId);

            final byte[] derived = HKDF.deriveSecrets(messageKey, binding,
                    HKDF_INFO.getBytes(StandardCharsets.UTF_8), HKDF_OUTPUT_LENGTH);
            final byte[] encKey = new byte[MSG_KEY_LENGTH];
            final byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(derived, 0, encKey, 0, MSG_KEY_LENGTH);
            System.arraycopy(derived, MSG_KEY_LENGTH, iv, 0, IV_LENGTH);

            final byte[] envelopeBytes = buildSceEnvelope(body, extraContent, toJid, isMuc)
                    .getBytes(StandardCharsets.UTF_8);

            final Cipher cipher = Cipher.getInstance(CIPHER_MODE);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(encKey, KEYTYPE),
                    new GCMParameterSpec(TAG_LENGTH * 8, iv));

            cipher.updateAAD(binding);

            this.payload = cipher.doFinal(envelopeBytes);

            // Key commitment: a single shared value that binds this ciphertext to exactly one
            // message key. Derived from the same message key + context binding but under a
            // distinct HKDF label, so it is independent of the AES key/IV. Recipients recompute
            // it from their unwrapped key and reject on mismatch (see decryptPayload), which is
            // what actually makes the AEAD key-committing.
            this.commit = HKDF.deriveSecrets(messageKey, binding, COMMIT_INFO, COMMIT_LENGTH);

            // Memory security: zero out sensitive keys
            java.util.Arrays.fill(derived, (byte) 0);
            java.util.Arrays.fill(encKey, (byte) 0);

        } catch (final NoSuchAlgorithmException | NoSuchPaddingException | InvalidKeyException
                | InvalidAlgorithmParameterException | IllegalBlockSizeException
                | BadPaddingException e) {
            throw new CryptoFailedException(e);
        }
    }

    public boolean addDevice(final XmppAxolotlSession session) {
        return addDevice(session, false);
    }

    /**
     * Wrap the message key for one device.
     *
     * @return true when a key was actually attached. False means the device was
     *         skipped — its session is not trusted-and-active, or the wrap
     *         failed — which callers MUST account for: a header that ended up
     *         with no recipient key produces a message only we can read.
     */
    public boolean addDevice(final XmppAxolotlSession session, final boolean ignoreSessionTrust) {
        if (messageKeyWiped) {
            throw new IllegalStateException("message key already wiped");
        }
        final XmppAxolotlSession.AxolotlKey key = session.processSending(messageKey, ignoreSessionTrust);
        if (key == null) return false;
        try {
            final Jid jid = Jid.of(session.getRemoteAddress().getName()).asBareJid();
            keysByJid.computeIfAbsent(jid, k -> new ArrayList<>()).add(key);
            return true;
        } catch (final Exception e) {
            Log.w(Config.LOGTAG, "OMEMO2: could not parse JID from session address: "
                    + session.getRemoteAddress().getName());
            return false;
        }
    }

    public boolean hasPayload() {
        return payload != null;
    }

    /**
     * Zero the symmetric message key. Call as soon as the last per-device wrap
     * ({@link #addDevice}) is done: the built {@code <encrypted>} element does not
     * need it, so there is no reason to keep 32 bytes of live key material in the
     * heap (or in the resend cache) any longer. Further {@code encrypt}/{@code
     * addDevice} calls after wiping throw {@link IllegalStateException}.
     */
    public void wipeMessageKey() {
        if (messageKey != null) {
            java.util.Arrays.fill(messageKey, (byte) 0);
        }
        messageKeyWiped = true;
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
        if (commit != null) {
            encrypted.addChild("commit")
                    .setContent(Base64.encodeToString(commit, Base64.NO_WRAP));
        }
        return encrypted;
    }

    /**
     * Decrypt the payload.
     *
     * @param expectedTo      the JID the SCE {@code <to>} element must match (XEP-0420 §4.5).
     *                        For 1:1 chats: the recipient JID as seen on the outer stanza
     *                        (own account JID for incoming, counterpart JID for carbon-sent).
     *                        For MUC: the room bare JID.
     * @param stanzaTimestamp the sending time derived from the stanza itself (delay/MAM
     *                        stamp, or receive time for live stanzas), in epoch millis;
     *                        null when unknown. XEP-0420 requires the SCE {@code <time>}
     *                        stamp to be verified against this, not against the local clock.
     * @return {@link DecryptedSce} containing the body (if any), all SCE content elements,
     *         and the sender fingerprint; or null if there is no payload
     */
    public DecryptedSce decrypt(final XmppAxolotlSession session, final int ownDeviceId,
            final Jid ownBareJid, final Jid expectedTo, final Long stanzaTimestamp)
            throws CryptoFailedException {
        if (!hasPayload()) return null;
        final byte[] msgKey = extractKey(session, ownDeviceId, ownBareJid);
        if (msgKey == null) return null;
        if (msgKey.length != MSG_KEY_LENGTH) {
            throw new CryptoFailedException(
                    "OMEMO2 message key must be 32 bytes, got " + msgKey.length);
        }
        return decryptPayload(msgKey, session.getFingerprint(), this.from, expectedTo, stanzaTimestamp);
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
            final Jid expectedFrom, final Jid expectedTo, final Long stanzaTimestamp)
            throws CryptoFailedException {
        try {
            // Verify AAD/Salt: must match the expected context
            final byte[] binding = computeContextBinding(expectedFrom, expectedTo, sourceDeviceId);

            // Key-commitment check, BEFORE using the key: recompute the commitment from the
            // unwrapped message key and require it to equal the single shared <commit> the sender
            // published. This is what makes the AEAD key-committing — a ciphertext opens under
            // exactly one message key — closing invisible-salamander collisions and malicious-
            // sender equivocation across a peer's devices / group members. Fail closed when it is
            // absent: every sender emits it, so a payload without one is a pre-commitment message
            // or an attack. Constant-time compare (both operands are 32-byte, non-secret digests).
            if (this.commit == null) {
                java.util.Arrays.fill(msgKey, (byte) 0);
                throw new CryptoFailedException("OMEMO2 payload is missing its key commitment");
            }
            final byte[] expectedCommit = HKDF.deriveSecrets(msgKey, binding, COMMIT_INFO, COMMIT_LENGTH);
            final boolean commitOk = java.security.MessageDigest.isEqual(expectedCommit, this.commit);
            java.util.Arrays.fill(expectedCommit, (byte) 0);
            if (!commitOk) {
                java.util.Arrays.fill(msgKey, (byte) 0);
                throw new CryptoFailedException("OMEMO2 key commitment mismatch");
            }

            final byte[] derived = HKDF.deriveSecrets(msgKey, binding,
                    HKDF_INFO.getBytes(StandardCharsets.UTF_8), HKDF_OUTPUT_LENGTH);
            final byte[] encKey = new byte[MSG_KEY_LENGTH];
            final byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(derived, 0, encKey, 0, MSG_KEY_LENGTH);
            System.arraycopy(derived, MSG_KEY_LENGTH, iv, 0, IV_LENGTH);

            final Cipher cipher = Cipher.getInstance(CIPHER_MODE);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(encKey, KEYTYPE),
                    new GCMParameterSpec(TAG_LENGTH * 8, iv));

            cipher.updateAAD(binding);

            final byte[] plaintext = cipher.doFinal(payload);

            // Memory security: the wrapped message key and the derived key/IV are
            // single-use — zero them the moment the payload is open. The raw
            // plaintext buffer is wiped after parsing (its content lives on only
            // as parsed strings, which the JVM does not let us scrub).
            java.util.Arrays.fill(derived, (byte) 0);
            java.util.Arrays.fill(encKey, (byte) 0);
            java.util.Arrays.fill(msgKey, (byte) 0);

            try {
                return parseSceContent(plaintext, fingerprint, expectedFrom, expectedTo, stanzaTimestamp);
            } finally {
                java.util.Arrays.fill(plaintext, (byte) 0);
            }

        } catch (final NoSuchAlgorithmException | NoSuchPaddingException | InvalidKeyException
                | InvalidAlgorithmParameterException | IllegalBlockSizeException
                | BadPaddingException e) {
            throw new CryptoFailedException(e);
        }
    }

    private static byte[] computeContextBinding(final Jid from, final Jid to, final int sid) {
        // Null JIDs are bound as empty segments. Callers currently guarantee a non-null from, but
        // both encrypt and decrypt go through here, so the binding stays symmetric either way and
        // a null never NPEs inside the crypto core; a from/to mismatch (including null vs. bound)
        // still fails GCM tag verification because the peer bound the real JID.
        final byte[] fromBytes = from == null
                ? new byte[0]
                : from.asBareJid().toString().getBytes(StandardCharsets.UTF_8);
        // A null recipient (SCE without a <to>, e.g. some metadata-only stanzas) is also a
        // legitimate case — matching the old "don't bind <to>" behaviour.
        final byte[] toBytes = to == null
                ? new byte[0]
                : to.asBareJid().toString().getBytes(StandardCharsets.UTF_8);
        final byte[] prefix = "OMEMO2".getBytes(StandardCharsets.UTF_8);
        final ByteBuffer buffer = ByteBuffer.allocate(prefix.length + 1 + fromBytes.length + 1 + toBytes.length + 1 + 4);
        buffer.put(prefix);
        buffer.put((byte) 0);
        buffer.put(fromBytes);
        buffer.put((byte) 0);
        buffer.put(toBytes);
        buffer.put((byte) 0);
        buffer.putInt(sid);
        return buffer.array();
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
        // XEP-0420 §4.4 affixed metadata: <from> and <to> bind the envelope to its
        // stanza-level routing, <time> binds it to a wall-clock window so replays
        // can be detected. ISO-8601 UTC.
        envelope.addChild(new Element("time").setAttribute("stamp", currentIsoTimestamp()));
        envelope.addChild(new Element("from").setAttribute("jid", from.asBareJid().toString()));
        if (toJid != null) {
            envelope.addChild(new Element("to").setAttribute("jid", toJid.asBareJid().toString()));
        }
        // Bucket padding: size <rpad> so the serialized envelope lands exactly on
        // the next PAD_BUCKET-byte boundary. The ciphertext length then reveals
        // only a coarse size class, not the exact content length — a fixed random
        // 1–200 byte rpad would still expose the body length to within 200 bytes.
        final int unpadded = envelope.toString().getBytes(StandardCharsets.UTF_8).length
                + RPAD_ELEMENT_OVERHEAD;
        final int target = ((unpadded / PAD_BUCKET) + 1) * PAD_BUCKET;
        envelope.addChild(new Element("rpad").setContent(generateRpad(target - unpadded)));
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
            final Jid expectedFrom, final Jid expectedTo, final Long stanzaTimestamp)
            throws CryptoFailedException {
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
                    // XEP-0420 (v0.5.0) affix verification: the <time> stamp MUST be
                    // checked against "the sending time derived from the stanza itself"
                    // (delay/MAM stamp, or receive time for live stanzas) — NOT the
                    // local wall clock. Comparing against the stanza time makes MAM
                    // catch-up of ANY age pass (both stamps are equally old — rejecting
                    // those would only destroy the backlog, since the ratchet has
                    // already advanced by now), while an old ciphertext replayed as a
                    // fresh live message is rejected: its SCE stamp disagrees with the
                    // stanza's sending time by more than the window. A future-dated
                    // stamp (beyond the window relative to the local clock) is always
                    // rejected as bogus. Same-session replays are additionally blocked
                    // by DuplicateMessageException; kex replays by one-time-prekey
                    // deletion and the last-resort tuple tracker.
                    // <time> is a REQUIRED affix in this SCE profile (§4.6.0): the
                    // whole point of the affix is replay detection, so an envelope
                    // that omits it — or carries an unparseable stamp — must be
                    // rejected rather than silently skipping the check (fail-open
                    // would let an attacker strip <time> to bypass replay defence).
                    final Element timeEl = envelope.findChild("time");
                    if (timeEl == null) {
                        throw new CryptoFailedException("SCE envelope missing required <time>");
                    }
                    final String stamp = timeEl.getAttribute("stamp");
                    if (stamp == null) {
                        throw new CryptoFailedException("SCE <time> missing stamp attribute");
                    }
                    final Long ts = parseIsoTimestamp(stamp);
                    if (ts == null) {
                        throw new CryptoFailedException("SCE <time> unparseable stamp: " + stamp);
                    }
                    final long now = System.currentTimeMillis();
                    if (ts - now > MAX_TIME_SKEW_MS) {
                        throw new CryptoFailedException(
                                "SCE <time> too far in the future: " + stamp);
                    }
                    final long reference = stanzaTimestamp != null ? stanzaTimestamp : now;
                    if (Math.abs(ts - reference) > MAX_TIME_SKEW_MS) {
                        throw new CryptoFailedException(
                                "SCE <time> (" + stamp + ") inconsistent with stanza"
                                        + " sending time — possible replay");
                    }
                    String body = null;
                    final List<Element> elements = new ArrayList<>();
                    for (final Element child : content.getChildren()) {
                        // XEP-0420 "Server-processed Elements": these belong on the
                        // outer stanza where the server can read them; receivers MUST
                        // discard them when found inside <content>. Accepting them
                        // here would let a sender smuggle authenticated-looking
                        // routing/archive/dedup directives past the handlers that
                        // deliberately only read them from the outer stanza.
                        if (isServerProcessedElement(child)) {
                            Log.w(Config.LOGTAG, "OMEMO2: discarding server-processed element <"
                                    + child.getName() + " xmlns='" + child.getNamespace()
                                    + "'> found inside SCE <content> (XEP-0420)");
                            continue;
                        }
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

    /**
     * XEP-0420 "Server-processed Elements" — elements the server must be able to
     * read, which are therefore forbidden inside the SCE {@code <content>}:
     * XEP-0334 processing hints, XEP-0359 stanza/origin IDs, XEP-0033 extended
     * addressing, and the XEP-0380 EME marker. Receivers MUST discard them.
     */
    private static boolean isServerProcessedElement(final Element el) {
        final String ns = el.getNamespace();
        final String name = el.getName();
        if ("urn:xmpp:hints".equals(ns)) {
            return true;
        }
        if ("urn:xmpp:sid:0".equals(ns) && ("stanza-id".equals(name) || "origin-id".equals(name))) {
            return true;
        }
        if ("http://jabber.org/protocol/address".equals(ns)) {
            return true;
        }
        return "urn:xmpp:eme:0".equals(ns) && "encryption".equals(name);
    }

    private static Long parseIsoTimestamp(final String stamp) {
        // The <time> stamp is a XEP-0082 DateTime (= RFC 3339): full date, 'T',
        // time with seconds, an OPTIONAL arbitrary-precision fraction, and a
        // MANDATORY zone designator ('Z' or ±HH:MM). The desktop client emits
        // nanosecond precision with a numeric offset (e.g.
        // "2026-07-07T08:25:36.119813030+00:00"), we emit second precision with
        // 'Z' — both are valid DateTimes and both MUST parse. Shape-check the
        // profile requirements here, then delegate to the shared strict parser
        // (AbstractParser.parseTimestamp: lenient(false), arbitrary fractional
        // precision, all numeric offset forms) — but refuse the looser shapes
        // that parser also accepts (date-only, SQL style, missing zone), which
        // are not valid for this affix.
        if (stamp.length() < 20 || stamp.charAt(10) != 'T') {
            return null;
        }
        final boolean zoned = stamp.charAt(stamp.length() - 1) == 'Z'
                || stamp.lastIndexOf('+') > 18
                || stamp.lastIndexOf('-') > 18;
        if (!zoned) {
            return null;
        }
        try {
            return AbstractParser.parseTimestamp(stamp);
        } catch (final java.text.ParseException e) {
            return null;
        }
    }

    /** Serialized size of an empty {@code <rpad></rpad>} element. */
    private static final int RPAD_ELEMENT_OVERHEAD = "<rpad></rpad>".length();
    /** Envelope size bucket for length hiding; see buildSceEnvelope. */
    private static final int PAD_BUCKET = 256;

    private static final char[] RPAD_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
                    .toCharArray();

    /**
     * Exactly {@code length} random characters (1 char == 1 UTF-8 byte, none of
     * which need XML escaping), so the padded envelope size is byte-exact.
     */
    private static String generateRpad(final int length) {
        final StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(RPAD_ALPHABET[Random.SECURE_RANDOM.nextInt(RPAD_ALPHABET.length)]);
        }
        return sb.toString();
    }
}
