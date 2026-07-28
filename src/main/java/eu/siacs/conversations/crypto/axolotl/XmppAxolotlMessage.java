package eu.siacs.conversations.crypto.axolotl;

import android.util.Base64;
import android.util.Log;

import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.KeyGenerator;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.utils.Compatibility;
import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xmpp.Jid;

public class XmppAxolotlMessage {
    public static final String CONTAINERTAG = "encrypted";
    private static final String HEADER = "header";
    private static final String SOURCEID = "sid";
    private static final String KEYTAG = "key";
    private static final String REMOTEID = "rid";
    private static final String IVTAG = "iv";
    private static final String PAYLOAD = "payload";

    private static final String KEYTYPE = "AES";
    private static final String CIPHERMODE = "AES/GCM/NoPadding";
    private static final String PROVIDER = "BC";
    private final List<XmppAxolotlSession.AxolotlKey> keys;
    private final Jid from;
    private final int sourceDeviceId;
    private byte[] innerKey;
    private byte[] ciphertext = null;
    private byte[] iv = null;

    private XmppAxolotlMessage(final Element axolotlMessage, final Jid from) throws IllegalArgumentException {
        this.from = from;
        Element header = axolotlMessage.findChild(HEADER);
        if (header == null) {
            throw new IllegalArgumentException("missing header");
        }
        try {
            final int sid = Integer.parseInt(header.getAttribute(SOURCEID));
            if (sid <= 0) throw new IllegalArgumentException("invalid source id: " + sid);
            this.sourceDeviceId = sid;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid source id");
        }
        List<Element> keyElements = header.getChildren();
        this.keys = new ArrayList<>();
        for (Element keyElement : keyElements) {
            switch (keyElement.getName()) {
                case KEYTAG:
                    try {
                        int recipientId = Integer.parseInt(keyElement.getAttribute(REMOTEID));
                        byte[] key = Base64.decode(keyElement.getContent().trim(), Base64.DEFAULT);
                        boolean isPreKey = keyElement.getAttributeAsBoolean("prekey");
                        this.keys.add(new XmppAxolotlSession.AxolotlKey(recipientId, key, isPreKey));
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("invalid remote id");
                    }
                    break;
                case IVTAG:
                    if (this.iv != null) {
                        throw new IllegalArgumentException("Duplicate iv entry");
                    }
                    iv = Base64.decode(keyElement.getContent().trim(), Base64.DEFAULT);
                    break;
                default:
                    Log.w(Config.LOGTAG, "Unexpected element in header: " + keyElement.toString());
                    break;
            }
        }
        // Reject degenerate headers (no keys, no iv). A legitimate sender always
        // produces at least one <key> and an <iv>. An empty header is the
        // payload of the dual-encryption downgrade attack — the recipient would
        // otherwise treat it as a "no payload" key-transport message and skip
        // straight past, dropping any sibling OMEMO2 content with it.
        if (this.keys.isEmpty()) {
            throw new IllegalArgumentException("legacy header carries no <key> entries");
        }
        if (this.iv == null) {
            throw new IllegalArgumentException("legacy header carries no <iv>");
        }
        final Element payloadElement = axolotlMessage.findChildEnsureSingle(PAYLOAD, AxolotlService.PEP_PREFIX);
        if (payloadElement != null) {
            ciphertext = Base64.decode(payloadElement.getContent().trim(), Base64.DEFAULT);
        }
    }

    XmppAxolotlMessage(Jid from, int sourceDeviceId) {
        this.from = from;
        this.sourceDeviceId = sourceDeviceId;
        this.keys = new ArrayList<>();
        this.iv = generateIv();
        this.innerKey = generateKey();
    }

    public static int parseSourceId(final Element axolotlMessage) throws IllegalArgumentException {
        final Element header = axolotlMessage.findChild(HEADER);
        if (header == null) {
            throw new IllegalArgumentException("No header found");
        }
        try {
            return Integer.parseInt(header.getAttribute(SOURCEID));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid source id");
        }
    }

    public static XmppAxolotlMessage fromElement(Element element, Jid from) {
        return new XmppAxolotlMessage(element, from);
    }

    private static byte[] generateKey() {
        try {
            KeyGenerator generator = KeyGenerator.getInstance(KEYTYPE);
            generator.init(128);
            return generator.generateKey().getEncoded();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] generateIv() {
        final SecureRandom random = new SecureRandom();
        final byte[] iv = new byte[12];
        random.nextBytes(iv);
        return iv;
    }

    private static byte[] getPaddedBytes(String plaintext) {
        int plainLength = plaintext.getBytes().length;
        int pad = Math.max(64, (plainLength / 32 + 1) * 32) - plainLength;
        SecureRandom random = new SecureRandom();
        int left = random.nextInt(pad);
        int right = pad - left;
        StringBuilder builder = new StringBuilder(plaintext);
        for (int i = 0; i < left; ++i) {
            builder.insert(0, random.nextBoolean() ? "\t" : " ");
        }
        for (int i = 0; i < right; ++i) {
            builder.append(random.nextBoolean() ? "\t" : " ");
        }
        return builder.toString().getBytes();
    }

    public boolean hasPayload() {
        return ciphertext != null;
    }

    void encrypt(final String plaintext) throws CryptoFailedException {
        if (plaintext == null) return;

        try {
            SecretKey secretKey = new SecretKeySpec(innerKey, KEYTYPE);
            IvParameterSpec ivSpec = new IvParameterSpec(iv);
            Cipher cipher = Compatibility.twentyEight() ? Cipher.getInstance(CIPHERMODE) : Cipher.getInstance(CIPHERMODE, PROVIDER);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, ivSpec);
            final byte[] gcmOutput = cipher.doFinal(
                    Config.OMEMO_PADDING ? getPaddedBytes(plaintext) : plaintext.getBytes());
            // Monocles / Conversations variant of OMEMO v0.3: instead of leaving
            // the 16-byte GCM auth tag at the end of the payload, splice it onto
            // the AES key so the wrapped blob is 32 bytes (16-byte AES key ||
            // 16-byte auth tag). The payload then carries only the ciphertext.
            // Older Monocles releases REQUIRE this format and reject 16-byte
            // keys with OutdatedSenderException, so we always emit it.
            final int authTagLen = 16;
            final byte[] keyPlusTag = new byte[innerKey.length + authTagLen];
            final byte[] taglessCiphertext = new byte[gcmOutput.length - authTagLen];
            System.arraycopy(innerKey, 0, keyPlusTag, 0, innerKey.length);
            System.arraycopy(gcmOutput, taglessCiphertext.length,
                    keyPlusTag, innerKey.length, authTagLen);
            System.arraycopy(gcmOutput, 0, taglessCiphertext, 0, taglessCiphertext.length);
            this.innerKey = keyPlusTag;
            this.ciphertext = taglessCiphertext;
        } catch (NoSuchAlgorithmException | NoSuchPaddingException | InvalidKeyException
                | IllegalBlockSizeException | BadPaddingException | NoSuchProviderException
                | InvalidAlgorithmParameterException e) {
            throw new CryptoFailedException(e);
        }
    }

    public Jid getFrom() {
        return this.from;
    }

    int getSenderDeviceId() {
        return sourceDeviceId;
    }

    void addDevice(XmppAxolotlSession session) {
        addDevice(session, false);
    }

    void addDevice(XmppAxolotlSession session, boolean ignoreSessionTrust) {
        // Force standard 16-byte key wrapping for legacy stack to ensure interop.
        XmppAxolotlSession.AxolotlKey key = session.processSending(innerKey, ignoreSessionTrust);
        if (key != null) {
            keys.add(key);
        }
    }

    public byte[] getInnerKey() {
        return innerKey;
    }

    public byte[] getIV() {
        return this.iv;
    }

    public Element toElement() {
        Element encryptionElement = new Element(CONTAINERTAG, AxolotlService.PEP_PREFIX);
        Element headerElement = encryptionElement.addChild(HEADER);
        headerElement.setAttribute(SOURCEID, sourceDeviceId);
        for (XmppAxolotlSession.AxolotlKey key : keys) {
            Element keyElement = new Element(KEYTAG);
            keyElement.setAttribute(REMOTEID, key.deviceId);
            if (key.prekey) {
                keyElement.setAttribute("prekey", "true");
            }
            keyElement.setContent(Base64.encodeToString(key.key, Base64.NO_WRAP));
            headerElement.addChild(keyElement);
        }
        headerElement.addChild(IVTAG).setContent(Base64.encodeToString(iv, Base64.NO_WRAP));
        if (ciphertext != null) {
            Element payload = encryptionElement.addChild(PAYLOAD);
            payload.setContent(Base64.encodeToString(ciphertext, Base64.NO_WRAP));
        }
        return encryptionElement;
    }

    private byte[] unpackKey(XmppAxolotlSession session, Integer sourceDeviceId) throws CryptoFailedException {
        ArrayList<XmppAxolotlSession.AxolotlKey> possibleKeys = new ArrayList<>();
        for (XmppAxolotlSession.AxolotlKey key : keys) {
            if (key.deviceId == sourceDeviceId) {
                possibleKeys.add(key);
            }
        }
        if (possibleKeys.size() == 0) {
            throw new NotEncryptedForThisDeviceException();
        }
        return session.processReceiving(possibleKeys);
    }

    /**
     * Append a wrapped key produced outside this object (by the legacy XEP-0384
     * v0.3 stack). The wire format of {@code <key rid='…' prekey='…'>…</key>}
     * is identical regardless of the underlying libsignal version — only the
     * binary contents of the wrapped key differ.
     */
    public void addLegacyWrappedKey(final int recipientDeviceId,
                                    final byte[] wrappedBytes,
                                    final boolean prekey) {
        this.keys.add(new XmppAxolotlSession.AxolotlKey(
                recipientDeviceId, wrappedBytes, prekey));
    }

    /**
     * Decrypt this message using the legacy XEP-0384 v0.3 stack. Mirrors
     * {@link #decrypt(XmppAxolotlSession, Integer)} but the wrapped-key unwrap
     * goes through {@link eu.siacs.conversations.crypto.axolotl.legacy.LegacyAxolotlBackend}
     * instead of the new-libsignal session cipher.
     *
     * @param backend       the legacy backend (must have a session with
     *                      {@code senderAddress})
     * @param senderAddress sender JID + device id, in old-libsignal coordinates
     * @param ownDeviceId   this device's id
     * @param fingerprint   supplies the sending device's legacy fingerprint for
     *                      the resulting plaintext message. Evaluated only after
     *                      the unwrap succeeded, because a first-contact PreKey
     *                      message creates the very session the fingerprint is
     *                      read from.
     */
    public XmppAxolotlPlaintextMessage decryptLegacy(
            final eu.siacs.conversations.crypto.axolotl.legacy.LegacyAxolotlBackend backend,
            final org.whispersystems.libsignal.SignalProtocolAddress senderAddress,
            final int ownDeviceId,
            final java.util.function.Supplier<String> fingerprint) throws CryptoFailedException {
        // Find our wrapped key in the header.
        XmppAxolotlSession.AxolotlKey ours = null;
        for (final XmppAxolotlSession.AxolotlKey k : keys) {
            if (k.deviceId == ownDeviceId) {
                ours = k;
                break;
            }
        }
        if (ours == null) {
            throw new NotEncryptedForThisDeviceException();
        }
        final byte[] key;
        try {
            final var dec = backend.decryptKey(senderAddress, ours.key, ours.prekey);
            key = dec.key;
        } catch (final org.whispersystems.libsignal.DuplicateMessageException e) {
            // Replay; libsignal already moved past this in the ratchet. Drop.
            Log.w(Config.LOGTAG, "legacy axolotl duplicate message from " + senderAddress);
            return null;
        } catch (final Exception e) {
            throw new CryptoFailedException(e);
        }
        if (key == null) {
            return null;
        }
        try {
            final byte[] decryptionKey;
            final byte[] decryptionCiphertext;
            if (key.length == 32) {
                // Monocles-variant: 32-byte key containing [AES-128 key (16) || GCM auth tag (16)]
                decryptionKey = new byte[16];
                decryptionCiphertext = new byte[ciphertext.length + 16];
                System.arraycopy(key, 0, decryptionKey, 0, 16);
                System.arraycopy(ciphertext, 0, decryptionCiphertext, 0, ciphertext.length);
                System.arraycopy(key, 16, decryptionCiphertext, ciphertext.length, 16);
            } else if (key.length == 16) {
                // Standard OMEMO v0.3: 16-byte key; auth tag is already at the end of the ciphertext
                decryptionKey = key;
                decryptionCiphertext = ciphertext;
            } else {
                throw new CryptoFailedException("Unexpected legacy key length: " + key.length);
            }

            final Cipher cipher = Compatibility.twentyEight()
                    ? Cipher.getInstance(CIPHERMODE)
                    : Cipher.getInstance(CIPHERMODE, PROVIDER);
            final SecretKeySpec keySpec = new SecretKeySpec(decryptionKey, KEYTYPE);
            final IvParameterSpec ivSpec = new IvParameterSpec(iv);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);
            final String plaintext = new String(cipher.doFinal(decryptionCiphertext));
            return new XmppAxolotlPlaintextMessage(
                    Config.OMEMO_PADDING ? plaintext.trim() : plaintext, fingerprint.get());
        } catch (NoSuchAlgorithmException | NoSuchPaddingException | InvalidKeyException
                | InvalidAlgorithmParameterException | IllegalBlockSizeException
                | BadPaddingException | NoSuchProviderException e) {
            throw new CryptoFailedException(e);
        }
    }

    XmppAxolotlKeyTransportMessage getParameters(XmppAxolotlSession session, Integer sourceDeviceId) throws CryptoFailedException {
        return new XmppAxolotlKeyTransportMessage(session.getFingerprint(), unpackKey(session, sourceDeviceId), getIV());
    }

    /**
     * Legacy (XEP-0384 v0.3) counterpart of {@link #getParameters}: unwrap the
     * key-transport payload with the legacy backend. A message in the legacy
     * namespace must never be unwrapped with the OMEMO2 session cipher — the
     * stacks hold different ratchets for the same (jid, device), so doing that
     * both fails for real legacy senders and lets a re-wrapped OMEMO2 key blob
     * drive the OMEMO2 ratchet.
     *
     * @return the transported key + iv, or null when the message is a replay
     */
    XmppAxolotlKeyTransportMessage decryptLegacyKeyTransport(
            final eu.siacs.conversations.crypto.axolotl.legacy.LegacyAxolotlBackend backend,
            final org.whispersystems.libsignal.SignalProtocolAddress senderAddress,
            final int ownDeviceId,
            final java.util.function.Supplier<String> fingerprint) throws CryptoFailedException {
        XmppAxolotlSession.AxolotlKey ours = null;
        for (final XmppAxolotlSession.AxolotlKey k : keys) {
            if (k.deviceId == ownDeviceId) {
                ours = k;
                break;
            }
        }
        if (ours == null) {
            throw new NotEncryptedForThisDeviceException();
        }
        final byte[] key;
        try {
            key = backend.decryptKey(senderAddress, ours.key, ours.prekey).key;
        } catch (final org.whispersystems.libsignal.DuplicateMessageException e) {
            Log.w(Config.LOGTAG, "legacy key transport duplicate from " + senderAddress);
            return null;
        } catch (final Exception e) {
            throw new CryptoFailedException(e);
        }
        if (key == null) {
            return null;
        }
        return new XmppAxolotlKeyTransportMessage(fingerprint.get(), key, getIV());
    }

    /** Whether the wrapped key addressed to {@code deviceId} is a PreKey message. */
    boolean isPreKeyFor(final int deviceId) {
        for (final XmppAxolotlSession.AxolotlKey k : keys) {
            if (k.deviceId == deviceId) {
                return k.prekey;
            }
        }
        return false;
    }

    public XmppAxolotlPlaintextMessage decrypt(XmppAxolotlSession session, Integer sourceDeviceId) throws CryptoFailedException {
        XmppAxolotlPlaintextMessage plaintextMessage = null;
        byte[] key = unpackKey(session, sourceDeviceId);
        if (key != null) {
            try {
                final byte[] decryptionKey;
                final byte[] decryptionCiphertext;
                if (key.length == 32) {
                    // Monocles-variant: 32-byte key containing [AES-128 key (16) || GCM auth tag (16)]
                    decryptionKey = new byte[16];
                    decryptionCiphertext = new byte[ciphertext.length + 16];
                    System.arraycopy(key, 0, decryptionKey, 0, 16);
                    System.arraycopy(ciphertext, 0, decryptionCiphertext, 0, ciphertext.length);
                    System.arraycopy(key, 16, decryptionCiphertext, ciphertext.length, 16);
                } else if (key.length == 16) {
                    // Standard OMEMO v0.3: 16-byte key; auth tag is already at the end of the ciphertext
                    decryptionKey = key;
                    decryptionCiphertext = ciphertext;
                } else {
                    throw new CryptoFailedException("Unexpected legacy key length: " + key.length);
                }

                final Cipher cipher = Compatibility.twentyEight() ? Cipher.getInstance(CIPHERMODE) : Cipher.getInstance(CIPHERMODE, PROVIDER);
                SecretKeySpec keySpec = new SecretKeySpec(decryptionKey, KEYTYPE);
                IvParameterSpec ivSpec = new IvParameterSpec(iv);

                cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);

                String plaintext = new String(cipher.doFinal(decryptionCiphertext));
                plaintextMessage = new XmppAxolotlPlaintextMessage(Config.OMEMO_PADDING ? plaintext.trim() : plaintext, session.getFingerprint());

            } catch (NoSuchAlgorithmException | NoSuchPaddingException | InvalidKeyException
                    | InvalidAlgorithmParameterException | IllegalBlockSizeException
                    | BadPaddingException | NoSuchProviderException e) {
                throw new CryptoFailedException(e);
            }
        }
        return plaintextMessage;
    }

    public static class XmppAxolotlPlaintextMessage {
        private final String plaintext;
        private final String fingerprint;

        XmppAxolotlPlaintextMessage(String plaintext, String fingerprint) {
            this.plaintext = plaintext;
            this.fingerprint = fingerprint;
        }

        public String getPlaintext() {
            return plaintext;
        }


        public String getFingerprint() {
            return fingerprint;
        }
    }

    public static class XmppAxolotlKeyTransportMessage {
        private final String fingerprint;
        private final byte[] key;
        private final byte[] iv;

        public XmppAxolotlKeyTransportMessage(String fingerprint, byte[] key, byte[] iv) {
            this.fingerprint = fingerprint;
            this.key = key;
            this.iv = iv;
        }

        public String getFingerprint() {
            return fingerprint;
        }

        public byte[] getKey() {
            return key;
        }

        public byte[] getIv() {
            return iv;
        }
    }
}
