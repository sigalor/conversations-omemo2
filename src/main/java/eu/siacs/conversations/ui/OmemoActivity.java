package eu.siacs.conversations.ui;

import android.content.Intent;
import android.view.ContextMenu;
import android.view.MenuItem;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.databinding.DataBindingUtil;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.crypto.axolotl.FingerprintStatus;
import eu.siacs.conversations.crypto.axolotl.XmppAxolotlSession;
import eu.siacs.conversations.databinding.ContactKeyBinding;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.utils.CryptoHelper;
import eu.siacs.conversations.utils.XmppUri;

public abstract class OmemoActivity extends XmppActivity {

	private Account mSelectedAccount;
	protected String mSelectedFingerprint;
	// The fingerprint exactly as shown to the user (hybrid for PQ OMEMO2 rows,
	// classical hex without the 0x05 prefix otherwise). Copy actions MUST use
	// this so the clipboard always matches the displayed value; trust, distrust
	// and QR/URI verification stay keyed on the classical mSelectedFingerprint.
	protected String mSelectedFingerprintDisplay;

	protected XmppUri mPendingFingerprintVerificationUri = null;

	@Override
	public void onCreateContextMenu(ContextMenu menu, View v, ContextMenu.ContextMenuInfo menuInfo) {
		super.onCreateContextMenu(menu, v, menuInfo);
		Object account = v.getTag(R.id.TAG_ACCOUNT);
		Object fingerprint = v.getTag(R.id.TAG_FINGERPRINT);
		Object fingerprintStatus = v.getTag(R.id.TAG_FINGERPRINT_STATUS);
		if (account instanceof Account
				&& fingerprint instanceof String
				&& fingerprintStatus instanceof FingerprintStatus) {
			getMenuInflater().inflate(R.menu.omemo_key_context, menu);
			MenuItem distrust = menu.findItem(R.id.distrust_key);
			MenuItem verifyScan = menu.findItem(R.id.verify_scan);
			if (this instanceof TrustKeysActivity) {
				distrust.setVisible(false);
				verifyScan.setVisible(false);
			} else {
				FingerprintStatus status = (FingerprintStatus) fingerprintStatus;
				if (!status.isActive() || status.isVerified()) {
					verifyScan.setVisible(false);
				}
				distrust.setVisible(status.isVerified() || (!status.isActive() && status.isTrusted()));
			}
			// TODO can we rework this into using Intents?
			this.mSelectedAccount = (Account) account;
			this.mSelectedFingerprint = (String) fingerprint;
			final Object fingerprintDisplay = v.getTag(R.id.TAG_FINGERPRINT_DISPLAY);
			this.mSelectedFingerprintDisplay = fingerprintDisplay instanceof String
					? (String) fingerprintDisplay
					: ((String) fingerprint).substring(2);
		}
	}

	@Override
	public boolean onContextItemSelected(final MenuItem item) {
		final var itemId = item.getItemId();
		if (itemId == R.id.distrust_key) {
			showPurgeKeyDialog(mSelectedAccount, mSelectedFingerprint);
			return true;
		} else if (itemId == R.id.copy_omemo_key) {
			copyOmemoFingerprint(mSelectedFingerprintDisplay);
			return true;
		} else if (itemId == R.id.verify_scan) {
			ScanActivity.scan(this);
			return true;
		} else {
			return super.onContextItemSelected(item);
		}
	}

	@Override
	public void onActivityResult(final int requestCode, final int resultCode, final Intent intent) {
		super.onActivityResult(requestCode, resultCode, intent);
		if (requestCode == ScanActivity.REQUEST_SCAN_QR_CODE && resultCode == RESULT_OK) {
			String result = intent.getStringExtra(ScanActivity.INTENT_EXTRA_RESULT);
			XmppUri uri = new XmppUri(result == null ? "" : result);
			if (xmppConnectionServiceBound) {
				processFingerprintVerification(uri);
			} else {
				this.mPendingFingerprintVerificationUri = uri;
			}
		}
	}

	protected abstract void processFingerprintVerification(XmppUri uri);

	/**
	 * Copies a fingerprint to the clipboard. The parameter must be the
	 * display-ready hex string (hybrid fingerprint, or classical WITHOUT the
	 * 0x05 key-type prefix) — i.e. exactly what is shown to the user — so
	 * that the clipboard always matches the displayed value.
	 */
	protected void copyOmemoFingerprint(String displayFingerprint) {
		if (copyTextToClipboard(CryptoHelper.prettifyFingerprint(displayFingerprint), R.string.omemo2_fingerprint)) {
			Toast.makeText(
					this,
					R.string.toast_message_omemo2_fingerprint,
					Toast.LENGTH_SHORT).show();
		}
	}

	protected void addFingerprintRow(LinearLayout keys, final XmppAxolotlSession session, boolean highlight) {
		final Account account = session.getAccount();
		final String fingerprint = session.getFingerprint();
		addFingerprintRow(keys, account, fingerprint, session.getTrust(), highlight);
	}

	protected void addFingerprintRow(LinearLayout keys, final Account account, final String fingerprint, FingerprintStatus status, boolean highlight) {
		addFingerprintRow(keys, account, fingerprint, status, highlight, false);
	}

	protected void addFingerprintRow(LinearLayout keys, final Account account, final String fingerprint, FingerprintStatus status, boolean highlight, boolean legacy) {
		addFingerprintRowWithListeners(keys,
				account,
				fingerprint,
				highlight,
				status,
				true,
				true,
				legacy,
				(buttonView, isChecked) -> account.getAxolotlService().setFingerprintTrust(fingerprint, FingerprintStatus.createActive(isChecked)));
	}

	protected void addFingerprintRowWithListeners(LinearLayout keys, final Account account,
	                                              final String fingerprint,
	                                              boolean highlight,
	                                              FingerprintStatus status,
	                                              boolean showTag,
	                                              boolean undecidedNeedEnablement,
	                                              CompoundButton.OnCheckedChangeListener
			                                              onCheckedChangeListener) {
		addFingerprintRowWithListeners(keys, account, fingerprint, highlight, status, showTag, undecidedNeedEnablement, false, onCheckedChangeListener);
	}

	protected void addFingerprintRowWithListeners(LinearLayout keys, final Account account,
	                                              final String fingerprint,
	                                              boolean highlight,
	                                              FingerprintStatus status,
	                                              boolean showTag,
	                                              boolean undecidedNeedEnablement,
	                                              boolean legacy,
	                                              CompoundButton.OnCheckedChangeListener
			                                              onCheckedChangeListener) {
		ContactKeyBinding binding = DataBindingUtil.inflate(getLayoutInflater(), R.layout.contact_key, keys, true);
		binding.tglTrust.setVisibility(View.VISIBLE);
		registerForContextMenu(binding.getRoot());
		// For PQ OMEMO2 (non-legacy) devices show the HYBRID fingerprint, which
		// commits to both the classical and the post-quantum (ML-DSA-87) identity
		// key, so manual verification authenticates the post-quantum key too. Trust
		// and the QR/URI stay keyed on the classical fingerprint. Falls back to the
		// classical fingerprint when no pq_ik is pinned yet.
		String displayedFingerprint = fingerprint.substring(2);
		if (!legacy) {
			final String hybrid = account.getAxolotlService().hybridFingerprintFor(fingerprint);
			if (hybrid != null) {
				displayedFingerprint = hybrid;
			}
		}
		binding.getRoot().setTag(R.id.TAG_ACCOUNT, account);
		binding.getRoot().setTag(R.id.TAG_FINGERPRINT, fingerprint);
		binding.getRoot().setTag(R.id.TAG_FINGERPRINT_STATUS, status);
		// The exact value shown to the user; the context-menu copy action uses
		// this so that the clipboard always matches the display.
		binding.getRoot().setTag(R.id.TAG_FINGERPRINT_DISPLAY, displayedFingerprint);
		boolean x509 = Config.X509_VERIFICATION && status.getTrust() == FingerprintStatus.Trust.VERIFIED_X509;
		final View.OnClickListener toast;
		binding.tglTrust.setChecked(status.isTrusted());

		if (status.isActive()) {
			binding.key.setTextColor(MaterialColors.getColor(binding.key, com.google.android.material.R.attr.colorOnSurface));
			binding.keyType.setTextColor(MaterialColors.getColor(binding.keyType, com.google.android.material.R.attr.colorOnSurface));
			if (status.isVerified()) {
				binding.verifiedFingerprint.setVisibility(View.VISIBLE);
				binding.verifiedFingerprint.setAlpha(1.0f);
				binding.tglTrust.setVisibility(View.GONE);
				binding.verifiedFingerprint.setOnClickListener(v -> replaceToast(getString(R.string.this_device_has_been_verified), false));
				toast = null;
			} else {
				binding.verifiedFingerprint.setVisibility(View.GONE);
				binding.tglTrust.setVisibility(View.VISIBLE);
				binding.tglTrust.setOnCheckedChangeListener(onCheckedChangeListener);
				if (status.getTrust() == FingerprintStatus.Trust.UNDECIDED && undecidedNeedEnablement) {
					binding.buttonEnableDevice.setVisibility(View.VISIBLE);
					binding.buttonEnableDevice.setOnClickListener(v -> {
						account.getAxolotlService().setFingerprintTrust(fingerprint, FingerprintStatus.createActive(false));
						binding.buttonEnableDevice.setVisibility(View.GONE);
						binding.tglTrust.setVisibility(View.VISIBLE);
					});
					binding.tglTrust.setVisibility(View.GONE);
				} else {
					binding.tglTrust.setOnClickListener(null);
					binding.tglTrust.setEnabled(true);
				}
				toast = v -> hideToast();
			}
		} else {
			binding.key.setTextColor(MaterialColors.getColor(binding.key, com.google.android.material.R.attr.colorOnSurfaceVariant));
			binding.keyType.setTextColor(MaterialColors.getColor(binding.keyType, com.google.android.material.R.attr.colorOnSurfaceVariant));
			toast = v -> replaceToast(getString(R.string.this_device_is_no_longer_in_use), false);
			if (status.isVerified()) {
				binding.tglTrust.setVisibility(View.GONE);
				binding.verifiedFingerprint.setVisibility(View.VISIBLE);
				binding.verifiedFingerprint.setAlpha(0.4368f);
				binding.verifiedFingerprint.setOnClickListener(toast);
			} else {
				binding.tglTrust.setVisibility(View.VISIBLE);
				binding.verifiedFingerprint.setVisibility(View.GONE);
				binding.tglTrust.setEnabled(false);
			}
		}

		binding.getRoot().setOnClickListener(toast);
		binding.key.setOnClickListener(toast);
		binding.keyType.setOnClickListener(toast);
		if (showTag) {
			binding.keyType.setText(getString(legacy ? R.string.omemo_legacy_fingerprint : (x509 ? R.string.omemo2_fingerprint_x509 : R.string.omemo2_fingerprint)));
		} else {
			binding.keyType.setVisibility(View.GONE);
		}
		if (highlight) {
			binding.keyType.setTextColor(MaterialColors.getColor(binding.keyType, com.google.android.material.R.attr.colorPrimaryVariant));
			binding.keyType.setText(getString(legacy ? R.string.omemo_legacy_fingerprint_selected_message : (x509 ? R.string.omemo2_fingerprint_x509_selected_message : R.string.omemo2_fingerprint_selected_message)));
		} else {
			binding.keyType.setText(getString(legacy ? R.string.omemo_legacy_fingerprint : (x509 ? R.string.omemo2_fingerprint_x509 : R.string.omemo2_fingerprint)));
		}

		binding.key.setText(CryptoHelper.prettifyFingerprint(displayedFingerprint));
	}

	public void showPurgeKeyDialog(final Account account, final String fingerprint) {
		final MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
		builder.setTitle(R.string.distrust_omemo_key);
		builder.setMessage(R.string.distrust_omemo_key_text);
		builder.setNegativeButton(getString(R.string.cancel), null);
		builder.setPositiveButton(R.string.confirm,
				(dialog, which) -> {
					account.getAxolotlService().distrustFingerprint(fingerprint);
					refreshUi();
				});
		builder.create().show();
	}

    @Override
    public void onRequestPermissionsResult(
            final int requestCode,
            @NonNull final String[] permissions,
            @NonNull final int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        ScanActivity.onRequestPermissionResult(this, requestCode, grantResults);
    }
}
