package eu.siacs.conversations.crypto.axolotl;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.signal.libsignal.protocol.DuplicateMessageException;
import org.signal.libsignal.protocol.IdentityKey;
import org.signal.libsignal.protocol.InvalidKeyException;
import org.signal.libsignal.protocol.InvalidKeyIdException;
import org.signal.libsignal.protocol.InvalidMessageException;
import org.signal.libsignal.protocol.InvalidVersionException;
import org.signal.libsignal.protocol.LegacyMessageException;
import org.signal.libsignal.protocol.NoSessionException;
import org.signal.libsignal.protocol.SessionCipher;
import org.signal.libsignal.protocol.SignalProtocolAddress;
import org.signal.libsignal.protocol.UntrustedIdentityException;
import org.signal.libsignal.protocol.message.CiphertextMessage;
import org.signal.libsignal.protocol.message.PreKeySignalMessage;
import org.signal.libsignal.protocol.message.SignalMessage;

import java.util.Iterator;
import java.util.List;
import java.util.Optional;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.utils.CryptoHelper;

public class XmppAxolotlSession implements Comparable<XmppAxolotlSession> {
	private final SessionCipher cipher;
	private final SQLiteAxolotlStore sqLiteAxolotlStore;
	private final SignalProtocolAddress remoteAddress;
	private final Account account;
	private IdentityKey identityKey;
	private Integer preKeyId = null;
	private boolean fresh = true;
	private int lastWhisperCounter = -1;
	private byte[] lastWhisperRatchetKey = null;

	public XmppAxolotlSession(Account account, SQLiteAxolotlStore store,
	                          SignalProtocolAddress localAddress, SignalProtocolAddress remoteAddress,
	                          IdentityKey identityKey) {
		this(account, store, localAddress, remoteAddress);
		this.identityKey = identityKey;
	}

	public XmppAxolotlSession(Account account, SQLiteAxolotlStore store,
	                          SignalProtocolAddress localAddress, SignalProtocolAddress remoteAddress) {
		this.cipher = new SessionCipher(store, localAddress, remoteAddress);
		this.remoteAddress = remoteAddress;
		this.sqLiteAxolotlStore = store;
		this.account = account;
	}

	public Integer getPreKeyIdAndReset() {
		final Integer preKeyId = this.preKeyId;
		this.preKeyId = null;
		return preKeyId;
	}

	/**
	 * The Double Ratchet message counter + sender ratchet key of the last whisper
	 * (non-PreKey) message decrypted on this session, consumed once. A PreKey message
	 * starts a fresh chain so it records nothing and this returns {@code null}. Used by
	 * {@link AxolotlService} to apply XEP-0384's heartbeat rule (first message for a
	 * given ratchet key with counter ≥ 53 → send a heartbeat).
	 */
	public WhisperRatchet getLastWhisperRatchetAndReset() {
		if (this.lastWhisperRatchetKey == null) {
			return null;
		}
		final WhisperRatchet ratchet = new WhisperRatchet(this.lastWhisperCounter, this.lastWhisperRatchetKey);
		this.lastWhisperCounter = -1;
		this.lastWhisperRatchetKey = null;
		return ratchet;
	}

	public static class WhisperRatchet {
		public final int counter;
		public final byte[] ratchetKey;

		WhisperRatchet(final int counter, final byte[] ratchetKey) {
			this.counter = counter;
			this.ratchetKey = ratchetKey;
		}
	}

	public String getFingerprint() {
		return identityKey == null ? null : CryptoHelper.bytesToHex(identityKey.getPublicKey().serialize());
	}

	public IdentityKey getIdentityKey() {
		return identityKey;
	}

	public SignalProtocolAddress getRemoteAddress() {
		return remoteAddress;
	}

	public boolean isFresh() {
		return fresh;
	}

	public void setNotFresh() {
		this.fresh = false;
	}

	protected void setTrust(FingerprintStatus status) {
		sqLiteAxolotlStore.setFingerprintStatus(getFingerprint(), status);
	}

	public FingerprintStatus getTrust() {
		FingerprintStatus status = sqLiteAxolotlStore.getFingerprintStatus(getFingerprint());
		return (status == null) ? FingerprintStatus.createActiveUndecided() : status;
	}

	@Nullable
	byte[] processReceiving(List<AxolotlKey> possibleKeys) throws CryptoFailedException {
		byte[] plaintext = null;
		FingerprintStatus status = getTrust();
		if (!status.isCompromised()) {
			Iterator<AxolotlKey> iterator = possibleKeys.iterator();
			while (iterator.hasNext()) {
				AxolotlKey encryptedKey = iterator.next();
				try {
					if (encryptedKey.prekey) {
						PreKeySignalMessage preKeySignalMessage = new PreKeySignalMessage(encryptedKey.key);
						Optional<Integer> optionalPreKeyId = preKeySignalMessage.getPreKeyId();
						IdentityKey identityKey = preKeySignalMessage.getIdentityKey();
						if (!optionalPreKeyId.isPresent()) {
							if (iterator.hasNext()) {
								continue;
							}
							throw new CryptoFailedException("PreKeyWhisperMessage did not contain a PreKeyId");
						}
						preKeyId = optionalPreKeyId.get();
						if (this.identityKey != null && !this.identityKey.equals(identityKey)) {
							if (iterator.hasNext()) {
								continue;
							}
							throw new CryptoFailedException("Received PreKeyWhisperMessage but preexisting identity key changed.");
						}
						this.identityKey = identityKey;
						plaintext = cipher.decrypt(preKeySignalMessage);
					} else {
						SignalMessage signalMessage = new SignalMessage(encryptedKey.key);
						try {
							plaintext = cipher.decrypt(signalMessage);
							// Record the ratchet position for the XEP-0384 heartbeat rule.
							this.lastWhisperCounter = signalMessage.getCounter();
							this.lastWhisperRatchetKey = signalMessage.getSenderRatchetKey().serialize();
						} catch (InvalidMessageException | NoSessionException e) {
							if (iterator.hasNext()) {
								Log.w(Config.LOGTAG, account.getJid().asBareJid() + ": ignoring crypto exception because possible keys left to try", e);
								continue;
							}
							throw new BrokenSessionException(this.remoteAddress, e);
						}
						preKeyId = null;
					}
				} catch (InvalidVersionException | InvalidKeyException | LegacyMessageException
				         | InvalidMessageException | DuplicateMessageException
				         | InvalidKeyIdException | UntrustedIdentityException e) {
					if (iterator.hasNext()) {
						Log.w(Config.LOGTAG, account.getJid().asBareJid() + ": ignoring crypto exception because possible keys left to try", e);
						continue;
					}
					throw new CryptoFailedException("Error decrypting SignalMessage", e);
				}
				if (iterator.hasNext()) {
					break;
				}
			}
			if (!status.isActive()) {
				setTrust(status.toActive());
			}
		} else {
			throw new CryptoFailedException("not encrypting omemo message from fingerprint " + getFingerprint() + " because it was marked as compromised");
		}
		return plaintext;
	}

	@Nullable
	public AxolotlKey processSending(@NonNull byte[] outgoingMessage, boolean ignoreSessionTrust) {
		FingerprintStatus status = getTrust();
		if (ignoreSessionTrust || status.isTrustedAndActive()) {
			try {
				CiphertextMessage ciphertextMessage = cipher.encrypt(outgoingMessage);
				return new AxolotlKey(getRemoteAddress().getDeviceId(), ciphertextMessage.serialize(),
						ciphertextMessage.getType() == CiphertextMessage.PREKEY_TYPE);
			} catch (UntrustedIdentityException | NoSessionException e) {
				return null;
			}
		} else {
			return null;
		}
	}

	public Account getAccount() {
		return account;
	}

	@Override
	public int compareTo(XmppAxolotlSession o) {
		return getTrust().compareTo(o.getTrust());
	}

	public static class AxolotlKey {

		public final byte[] key;
		public final boolean prekey;
		public final int deviceId;

		public AxolotlKey(int deviceId, byte[] key, boolean prekey) {
			this.deviceId = deviceId;
			this.key = key;
			this.prekey = prekey;
		}
	}
}
