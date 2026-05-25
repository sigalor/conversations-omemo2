package eu.siacs.conversations.crypto.axolotl;

import android.util.Base64;
import android.util.Log;

import java.io.ByteArrayOutputStream;
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
import eu.siacs.conversations.xmpp.Jid;

/**
 * OMEMO2 message as defined in XEP-0384.
 * Uses Stanza Content Encryption (XEP-0420), AES-256-CBC + HMAC-SHA-256,
 * and HKDF-SHA-256 key derivation. Signal Protocol sessions are reused
 * from the legacy OMEMO implementation.
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
    // Keys grouped by recipient JID (bare JID → list of encrypted keys for that JID's devices)
    private final Map<Jid, List<XmppAxolotlSession.AxolotlKey>> keysByJid = new HashMap<>();
    private byte[] messageKey;
    private byte[] payload; // AES-CBC ciphertext + 16-byte truncated HMAC

    public XmppOmemo2Message(final Jid from, final int sourceDeviceId) {
        this.from = from;
        this.sourceDeviceId = sourceDeviceId;
        this.messageKey = new byte[MSG_KEY_LENGTH];
        new SecureRandom().nextBytes(this.messageKey);
    }

    private XmppOmemo2Message(final Element element, final Jid from) throws IllegalArgumentException {
        this.from = from;
        final Element header = element.findChild("header");
        if (header == null) {
            throw new IllegalArgumentException("no header element");
        }
        try {
            this.sourceDeviceId = Integer.parseInt(header.getAttribute("sid"));
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
                    final byte[] key = Base64.decode(content.trim(), Base64.DEFAULT);
                    keys.add(new XmppAxolotlSession.AxolotlKey(rid, key, kex));
                } catch (final Exception e) {
                    Log.w(Config.LOGTAG, "OMEMO2: invalid key element: " + e.getMessage());
                }
            }
            if (!keys.isEmpty()) {
                keysByJid.put(jid, keys);
            }
        }
        final Element payloadElement = element.findChild("payload");
        if (payloadElement != null) {
            final String content = payloadElement.getContent();
            if (content != null) {
                this.payload = Base64.decode(content.trim(), Base64.DEFAULT);
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

    public static int parseSourceId(final Element element) throws IllegalArgumentException {
        final Element header = element.findChild("header");
        if (header == null) throw new IllegalArgumentException("no header element");
        try {
            return Integer.parseInt(header.getAttribute("sid"));
        } catch (final NumberFormatException e) {
            throw new IllegalArgumentException("invalid sid");
        }
    }

    /** Encrypt the message body wrapped in an SCE envelope. */
    public void encrypt(final String body, final Jid toJid, final boolean isMuc) throws CryptoFailedException {
        if (body == null) return;
        try {
            final byte[] derived = hkdf(messageKey, HKDF_INFO, HKDF_OUTPUT_LENGTH);
            final byte[] encKey = new byte[MSG_KEY_LENGTH];
            final byte[] authKey = new byte[MSG_KEY_LENGTH];
            final byte[] iv = new byte[16];
            System.arraycopy(derived, 0, encKey, 0, MSG_KEY_LENGTH);
            System.arraycopy(derived, MSG_KEY_LENGTH, authKey, 0, MSG_KEY_LENGTH);
            System.arraycopy(derived, MSG_KEY_LENGTH * 2, iv, 0, 16);

            final String envelope = buildSceEnvelope(body, toJid, isMuc);
            final byte[] envelopeBytes = envelope.getBytes(StandardCharsets.UTF_8);

            final Cipher cipher = Cipher.getInstance(CIPHER_MODE);
            cipher.init(Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(encKey, KEYTYPE),
                    new IvParameterSpec(iv));
            final byte[] ct = cipher.doFinal(envelopeBytes);

            final Mac mac = Mac.getInstance(HMAC_ALG);
            mac.init(new SecretKeySpec(authKey, HMAC_ALG));
            final byte[] fullMac = mac.doFinal(ct);

            this.payload = new byte[ct.length + MAC_LENGTH];
            System.arraycopy(ct, 0, this.payload, 0, ct.length);
            System.arraycopy(fullMac, 0, this.payload, ct.length, MAC_LENGTH);

        } catch (final NoSuchAlgorithmException | NoSuchPaddingException | InvalidKeyException
                | InvalidAlgorithmParameterException | IllegalBlockSizeException | BadPaddingException e) {
            throw new CryptoFailedException(e);
        }
    }

    /** Add a device session to the recipient key list. JID is derived from the session's address. */
    public void addDevice(final XmppAxolotlSession session) {
        addDevice(session, false);
    }

    public void addDevice(final XmppAxolotlSession session, final boolean ignoreSessionTrust) {
        final XmppAxolotlSession.AxolotlKey key = session.processSending(messageKey, ignoreSessionTrust);
        if (key == null) return;
        final String addressName = session.getRemoteAddress().getName();
        try {
            final Jid jid = Jid.of(addressName).asBareJid();
            keysByJid.computeIfAbsent(jid, k -> new ArrayList<>()).add(key);
        } catch (final Exception e) {
            Log.w(Config.LOGTAG, "OMEMO2: could not parse JID from session address: " + addressName);
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

    /** Convert to XML element for inclusion in a message stanza. */
    public Element toElement() {
        final Element encrypted = new Element("encrypted", Namespace.OMEMO2);
        final Element header = encrypted.addChild("header");
        header.setAttribute("sid", sourceDeviceId);
        for (final Map.Entry<Jid, List<XmppAxolotlSession.AxolotlKey>> entry : keysByJid.entrySet()) {
            final Element keysElement = header.addChild("keys");
            keysElement.setAttribute("jid", entry.getKey().asBareJid().toString());
            for (final XmppAxolotlSession.AxolotlKey k : entry.getValue()) {
                final Element keyElement = new Element("key");
                keyElement.setAttribute("rid", k.deviceId);
                if (k.prekey) {
                    keyElement.setAttribute("kex", "true");
                }
                keyElement.setContent(Base64.encodeToString(k.key, Base64.NO_WRAP));
                keysElement.addChild(keyElement);
            }
        }
        if (payload != null) {
            encrypted.addChild("payload")
                    .setContent(Base64.encodeToString(payload, Base64.NO_WRAP));
        }
        return encrypted;
    }

    /**
     * Decrypt the message payload for our device.
     *
     * @param session       the Signal Protocol session with the sender
     * @param ownDeviceId   this client's device ID
     * @return plaintext body, or null if no payload
     */
    public XmppAxolotlMessage.XmppAxolotlPlaintextMessage decrypt(
            final XmppAxolotlSession session, final int ownDeviceId) throws CryptoFailedException {
        if (!hasPayload()) return null;
        final byte[] msgKey = extractKey(session, ownDeviceId);
        if (msgKey == null) return null;
        if (msgKey.length != MSG_KEY_LENGTH) {
            throw new CryptoFailedException("OMEMO2 message key must be 32 bytes, got " + msgKey.length);
        }
        final String body = decryptPayload(msgKey);
        return new XmppAxolotlMessage.XmppAxolotlPlaintextMessage(body, session.getFingerprint());
    }

    private byte[] extractKey(final XmppAxolotlSession session, final int ownDeviceId)
            throws CryptoFailedException {
        final List<XmppAxolotlSession.AxolotlKey> candidates = new ArrayList<>();
        for (final List<XmppAxolotlSession.AxolotlKey> keys : keysByJid.values()) {
            for (final XmppAxolotlSession.AxolotlKey k : keys) {
                if (k.deviceId == ownDeviceId) {
                    candidates.add(k);
                }
            }
        }
        if (candidates.isEmpty()) {
            throw new NotEncryptedForThisDeviceException();
        }
        return session.processReceiving(candidates);
    }

    private String decryptPayload(final byte[] msgKey) throws CryptoFailedException {
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
                    new SecretKeySpec(encKey, KEYTYPE),
                    new IvParameterSpec(iv));
            final byte[] plaintext = cipher.doFinal(ct);

            return extractBodyFromSce(new String(plaintext, StandardCharsets.UTF_8));

        } catch (final NoSuchAlgorithmException | NoSuchPaddingException | InvalidKeyException
                | InvalidAlgorithmParameterException | IllegalBlockSizeException | BadPaddingException e) {
            throw new CryptoFailedException(e);
        }
    }

    // --- SCE envelope helpers ---

    private String buildSceEnvelope(final String body, final Jid toJid, final boolean isMuc) {
        final StringBuilder sb = new StringBuilder();
        sb.append("<envelope xmlns='").append(Namespace.SCE).append("'>");
        sb.append("<content>");
        sb.append("<body xmlns='jabber:client'>").append(escapeXml(body)).append("</body>");
        sb.append("</content>");
        sb.append("<rpad>").append(generateRpad()).append("</rpad>");
        sb.append("<from jid='").append(escapeXmlAttr(from.asBareJid().toString())).append("'/>");
        if (isMuc && toJid != null) {
            sb.append("<to jid='").append(escapeXmlAttr(toJid.asBareJid().toString())).append("'/>");
        }
        sb.append("</envelope>");
        return sb.toString();
    }

    private static String generateRpad() {
        final byte[] bytes = new byte[new SecureRandom().nextInt(100)];
        new SecureRandom().nextBytes(bytes);
        return Base64.encodeToString(bytes, Base64.NO_WRAP);
    }

    private static String extractBodyFromSce(final String sceXml) throws CryptoFailedException {
        // Locate <body ...> ... </body> inside <content>
        final int bodyTagStart = sceXml.indexOf("<body");
        if (bodyTagStart < 0) throw new CryptoFailedException("no <body> in SCE envelope");
        final int bodyContentStart = sceXml.indexOf('>', bodyTagStart);
        if (bodyContentStart < 0) throw new CryptoFailedException("malformed SCE envelope body tag");
        final int bodyEnd = sceXml.indexOf("</body>", bodyContentStart);
        if (bodyEnd < 0) throw new CryptoFailedException("unclosed <body> in SCE envelope");
        return unescapeXml(sceXml.substring(bodyContentStart + 1, bodyEnd));
    }

    private static String escapeXml(final String text) {
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private static String escapeXmlAttr(final String text) {
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace("\"", "&quot;");
    }

    private static String unescapeXml(final String text) {
        return text.replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'");
    }

    // --- HKDF ---

    private static byte[] hkdf(final byte[] ikm, final String info, final int length) {
        try {
            // Extract: PRK = HMAC-SHA-256(salt=zero_bytes, IKM)
            final Mac mac = Mac.getInstance(HMAC_ALG);
            mac.init(new SecretKeySpec(new byte[32], HMAC_ALG));
            final byte[] prk = mac.doFinal(ikm);

            // Expand: T(i) = HMAC-SHA-256(PRK, T(i-1) || info || i)
            final byte[] infoBytes = info.getBytes(StandardCharsets.UTF_8);
            final ByteArrayOutputStream output = new ByteArrayOutputStream(length);
            byte[] prev = new byte[0];
            int blocks = (length + 31) / 32;
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
        } catch (final NoSuchAlgorithmException | InvalidKeyException | java.io.IOException e) {
            throw new IllegalStateException("HKDF failed", e);
        }
    }
}
