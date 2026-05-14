package eu.siacs.conversations.ui.fragment.settings;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Bundle;
import android.text.InputType;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.ListPreference;
import androidx.preference.Preference;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.common.base.Strings;

import eu.siacs.conversations.AppSettings;
import eu.siacs.conversations.R;
import eu.siacs.conversations.crypto.OmemoSetting;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.persistance.DatabaseBackend;
import eu.siacs.conversations.persistance.UnifiedPushDatabase;
import eu.siacs.conversations.services.MemorizingTrustManager;
import eu.siacs.conversations.ui.ConversationsActivity;
import eu.siacs.conversations.ui.activity.SettingsActivity;
import eu.siacs.conversations.utils.CryptoHelper;
import eu.siacs.conversations.utils.FileHelper;
import eu.siacs.conversations.xmpp.Jid;
import p32929.easypasscodelock.Utils.EasyLock;

import java.security.KeyStoreException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class SecuritySettingsFragment extends XmppPreferenceFragment {

    private static final String REMOVE_TRUSTED_CERTIFICATES = "remove_trusted_certificates";
    private static final String SERVER_CONNECTION = "server_connection";

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        setPreferencesFromResource(R.xml.preferences_security, rootKey);
        final ListPreference omemo = findPreference(AppSettings.OMEMO);

        final Preference deleteOmemoPreference = findPreference("delete_omemo_identities");
        if (deleteOmemoPreference != null) {
            deleteOmemoPreference.setOnPreferenceClickListener(
                    preference -> deleteOmemoIdentities());
        }

        final ListPreference automaticMessageDeletion =
                findPreference(AppSettings.AUTOMATIC_MESSAGE_DELETION);
        final ListPreference omemoAutoExpiry =
                findPreference(AppSettings.OMEMO_AUTO_EXPIRY);
        final Preference serverConnection = findPreference(SERVER_CONNECTION);
        if (omemo == null || automaticMessageDeletion == null || omemoAutoExpiry == null || serverConnection == null) {
            throw new IllegalStateException("The preference resource file is missing preferences");
        }
        omemo.setSummaryProvider(new OmemoSummaryProvider());
        setValues(
                automaticMessageDeletion,
                R.array.automatic_message_deletion_values,
                value -> timeframeValueToName(requireContext(), value));
        setValues(
                omemoAutoExpiry,
                R.array.omemo_auto_expiry_values,
                value -> timeframeValueToName(requireContext(), value));

        final var appLockPreference = findPreference("app_lock_enabled");
        if (appLockPreference != null) {
            appLockPreference.setOnPreferenceChangeListener((preference, newValue) -> {
                if (!requireSettingsActivity().getBooleanPreference("app_lock_enabled", R.bool.app_lock_enabled)) {
                    EasyLock.setBackgroundColor(requireContext().getColor(R.color.black26));
                    EasyLock.setPassword(requireContext(), ConversationsActivity.class);
                } else {
                    EasyLock.disablePassword(requireContext(), ConversationsActivity.class);
                }
                return true;
            });
        }

        final Preference databaseEncryption = findPreference("database_encryption");
        if (databaseEncryption != null) {
            databaseEncryption.setOnPreferenceClickListener(preference -> {
                showDatabaseEncryptionDialog();
                return true;
            });
            updateDatabaseEncryptionSummary(databaseEncryption);
        }
    }

    private void updateDatabaseEncryptionSummary(Preference preference) {
        if (preference != null) {
            String password = new AppSettings(requireContext()).getDatabasePassword();
            preference.setSummary(password == null ? R.string.pref_database_encryption_summary_disabled : R.string.pref_database_encryption_summary_enabled);
        }
    }

    private void showDatabaseEncryptionDialog() {
        final String currentPassword = new AppSettings(requireContext()).getDatabasePassword();
        if (currentPassword == null) {
            showEnableEncryptionDialog();
        } else {
            showEncryptionOptionsDialog(currentPassword);
        }
    }

    private void showEnableEncryptionDialog() {
        final var builder = new MaterialAlertDialogBuilder(requireActivity());
        builder.setTitle(R.string.dialog_set_db_password_title);

        final var layout = new LinearLayout(requireContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding, padding, padding);

        final var warningText = new android.widget.TextView(requireContext());
        warningText.setText(R.string.dialog_db_password_policy_warning);
        warningText.setTextColor(requireContext().getColor(android.R.color.holo_red_dark));
        warningText.setPadding(0, 0, 0, padding);
        layout.addView(warningText);

        final KeyguardManager keyguardManager = (KeyguardManager) requireContext().getSystemService(Context.KEYGUARD_SERVICE);
        if (keyguardManager != null && !keyguardManager.isDeviceSecure()) {
            final var lockWarningText = new android.widget.TextView(requireContext());
            lockWarningText.setText(R.string.dialog_db_password_no_lock_warning);
            lockWarningText.setTextColor(requireContext().getColor(android.R.color.holo_orange_dark));
            lockWarningText.setPadding(0, 0, 0, padding);
            layout.addView(lockWarningText);
        }

        final var passwordInput = new com.google.android.material.textfield.TextInputEditText(requireContext());
        passwordInput.setHint(R.string.dialog_db_password_hint);
        passwordInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        layout.addView(passwordInput);

        final var confirmInput = new com.google.android.material.textfield.TextInputEditText(requireContext());
        confirmInput.setHint(R.string.dialog_db_password_confirm_hint);
        confirmInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        layout.addView(confirmInput);

        builder.setView(layout);
        builder.setPositiveButton(android.R.string.ok, (dialog, which) -> {
            final var password = new char[passwordInput.length()];
            passwordInput.getText().getChars(0, passwordInput.length(), password, 0);
            final var confirm = new char[confirmInput.length()];
            confirmInput.getText().getChars(0, confirmInput.length(), confirm, 0);

            if (password.length == 0) {
                Toast.makeText(requireContext(), "Password cannot be empty", Toast.LENGTH_SHORT).show();
            } else if (password.length < 8) {
                Toast.makeText(requireContext(), R.string.toast_db_password_error_too_short, Toast.LENGTH_SHORT).show();
            } else if (CryptoHelper.isEqual(password, confirm)) {
                passwordInput.getText().clear();
                confirmInput.getText().clear();
                performMigration(null, password);
                FileHelper.zero(confirm);
                return;
            } else {
                Toast.makeText(requireContext(), R.string.toast_db_password_error_mismatch, Toast.LENGTH_SHORT).show();
            }
            FileHelper.zero(password);
            FileHelper.zero(confirm);
        });
        builder.setNegativeButton(android.R.string.cancel, null);
        builder.show();
    }

    private void showEncryptionOptionsDialog(String currentPassword) {
        final var builder = new MaterialAlertDialogBuilder(requireActivity());
        builder.setTitle(R.string.pref_database_encryption);
        String[] options = {getString(R.string.dialog_change_db_password_title), getString(R.string.pref_database_encryption_summary_disabled)};
        builder.setItems(options, (dialog, which) -> {
            if (which == 0) {
                showChangePasswordDialog(currentPassword);
            } else {
                showDisableEncryptionDialog(currentPassword);
            }
        });
        builder.show();
    }

    private void showChangePasswordDialog(String currentPassword) {
        final var builder = new MaterialAlertDialogBuilder(requireActivity());
        builder.setTitle(R.string.dialog_change_db_password_title);

        final var layout = new LinearLayout(requireContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding, padding, padding);

        final var warningText = new android.widget.TextView(requireContext());
        warningText.setText(R.string.dialog_db_password_policy_warning);
        warningText.setTextColor(requireContext().getColor(android.R.color.holo_red_dark));
        warningText.setPadding(0, 0, 0, padding);
        layout.addView(warningText);

        final KeyguardManager keyguardManager = (KeyguardManager) requireContext().getSystemService(Context.KEYGUARD_SERVICE);
        if (keyguardManager != null && !keyguardManager.isDeviceSecure()) {
            final var lockWarningText = new android.widget.TextView(requireContext());
            lockWarningText.setText(R.string.dialog_db_password_no_lock_warning);
            lockWarningText.setTextColor(requireContext().getColor(android.R.color.holo_orange_dark));
            lockWarningText.setPadding(0, 0, 0, padding);
            layout.addView(lockWarningText);
        }

        final var currentInput = new com.google.android.material.textfield.TextInputEditText(requireContext());
        currentInput.setHint(R.string.dialog_db_password_current_hint);
        currentInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        layout.addView(currentInput);

        final var passwordInput = new com.google.android.material.textfield.TextInputEditText(requireContext());
        passwordInput.setHint(R.string.dialog_db_password_hint);
        passwordInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        layout.addView(passwordInput);

        final var confirmInput = new com.google.android.material.textfield.TextInputEditText(requireContext());
        confirmInput.setHint(R.string.dialog_db_password_confirm_hint);
        confirmInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        layout.addView(confirmInput);

        builder.setView(layout);
        builder.setPositiveButton(android.R.string.ok, (dialog, which) -> {
            final var current = new char[currentInput.length()];
            currentInput.getText().getChars(0, currentInput.length(), current, 0);
            final var password = new char[passwordInput.length()];
            passwordInput.getText().getChars(0, passwordInput.length(), password, 0);
            final var confirm = new char[confirmInput.length()];
            confirmInput.getText().getChars(0, confirmInput.length(), confirm, 0);

            final char[] currentPasswordArr = currentPassword.toCharArray();
            if (!CryptoHelper.isEqual(current, currentPasswordArr)) {
                Toast.makeText(requireContext(), R.string.toast_db_password_error_wrong, Toast.LENGTH_SHORT).show();
            } else if (password.length < 8) {
                Toast.makeText(requireContext(), R.string.toast_db_password_error_too_short, Toast.LENGTH_SHORT).show();
            } else if (CryptoHelper.isEqual(password, confirm)) {
                currentInput.getText().clear();
                passwordInput.getText().clear();
                confirmInput.getText().clear();
                performMigration(currentPassword, password);
                FileHelper.zero(current);
                FileHelper.zero(confirm);
                FileHelper.zero(currentPasswordArr);
                return;
            } else {
                Toast.makeText(requireContext(), R.string.toast_db_password_error_mismatch, Toast.LENGTH_SHORT).show();
            }
            FileHelper.zero(current);
            FileHelper.zero(password);
            FileHelper.zero(confirm);
            FileHelper.zero(currentPasswordArr);
        });
        builder.setNegativeButton(android.R.string.cancel, null);
        builder.show();
    }

    private void showDisableEncryptionDialog(String currentPassword) {
        final var builder = new MaterialAlertDialogBuilder(requireActivity());
        builder.setTitle(R.string.pref_database_encryption_summary_disabled);

        final var layout = new LinearLayout(requireContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding, padding, padding);

        final var warningText = new android.widget.TextView(requireContext());
        warningText.setText(R.string.dialog_db_password_policy_warning);
        warningText.setTextColor(requireContext().getColor(android.R.color.holo_red_dark));
        warningText.setPadding(0, 0, 0, padding);
        layout.addView(warningText);

        final KeyguardManager keyguardManager = (KeyguardManager) requireContext().getSystemService(Context.KEYGUARD_SERVICE);
        if (keyguardManager != null && !keyguardManager.isDeviceSecure()) {
            final var lockWarningText = new android.widget.TextView(requireContext());
            lockWarningText.setText(R.string.dialog_db_password_no_lock_warning);
            lockWarningText.setTextColor(requireContext().getColor(android.R.color.holo_orange_dark));
            lockWarningText.setPadding(0, 0, 0, padding);
            layout.addView(lockWarningText);
        }

        final var currentInput = new com.google.android.material.textfield.TextInputEditText(requireContext());
        currentInput.setHint(R.string.dialog_db_password_current_hint);
        currentInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        layout.addView(currentInput);

        builder.setView(layout);
        builder.setPositiveButton(android.R.string.ok, (dialog, which) -> {
            final char[] current = new char[currentInput.length()];
            currentInput.getText().getChars(0, currentInput.length(), current, 0);
            final char[] currentPasswordArr = currentPassword.toCharArray();
            if (CryptoHelper.isEqual(current, currentPasswordArr)) {
                currentInput.getText().clear();
                performMigration(currentPassword, null);
            } else {
                Toast.makeText(requireContext(), R.string.toast_db_password_error_wrong, Toast.LENGTH_SHORT).show();
            }
            FileHelper.zero(current);
            FileHelper.zero(currentPasswordArr);
        });
        builder.setNegativeButton(android.R.string.cancel, null);
        builder.show();
    }

    private void performMigration(String oldPassword, char[] newPassword) {
        final var progressBuilder = new MaterialAlertDialogBuilder(requireActivity());
        progressBuilder.setTitle("Database Migration");
        progressBuilder.setMessage("Please wait...");
        progressBuilder.setCancelable(false);
        final var progressBar = new android.widget.ProgressBar(requireContext());
        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        progressBar.setPadding(padding, padding, padding, padding);
        progressBuilder.setView(progressBar);
        final AlertDialog progressDialog = progressBuilder.show();

        new Thread(() -> {
            final char[] oldPasswordArr = oldPassword == null ? null : oldPassword.toCharArray();
            try {
                DatabaseBackend.migrate(requireContext(), oldPasswordArr, newPassword);
                UnifiedPushDatabase.migrate(requireContext(), oldPasswordArr, newPassword);
                requireService().databaseBackend = DatabaseBackend.getInstance(requireContext());
                requireActivity().runOnUiThread(() -> {
                    progressDialog.dismiss();
                    Toast.makeText(requireContext(), newPassword == null ? R.string.toast_db_password_success_disabled : R.string.toast_db_password_success_set, Toast.LENGTH_SHORT).show();
                    updateDatabaseEncryptionSummary(findPreference("database_encryption"));
                });
            } catch (Exception e) {
                requireActivity().runOnUiThread(() -> {
                    progressDialog.dismiss();
                    new MaterialAlertDialogBuilder(requireActivity())
                            .setTitle("Error")
                            .setMessage(e.getMessage())
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                });
            } finally {
                FileHelper.zero(oldPasswordArr);
                FileHelper.zero(newPassword);
            }
        }).start();
    }


    @Override
    protected void onSharedPreferenceChanged(@NonNull String key) {
        super.onSharedPreferenceChanged(key);
        switch (key) {
            case AppSettings.OMEMO -> {
                OmemoSetting.load(requireContext());
            }
            case AppSettings.TRUST_SYSTEM_CA_STORE -> {
                requireService().updateMemorizingTrustManager();
                reconnectAccounts();
            }
            case AppSettings.DANE_ENFORCED, AppSettings.REQUIRE_CHANNEL_BINDING, AppSettings.REQUIRE_TLS_V1_3 -> {
                reconnectAccounts();
            }
            case AppSettings.AUTOMATIC_MESSAGE_DELETION -> {
                requireService().expireOldMessages(true);
            }
            case AppSettings.OMEMO_AUTO_EXPIRY -> {
                //No immediate action required
            }
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        requireActivity().setTitle(R.string.pref_title_security);
    }

    @Override
    public boolean onPreferenceTreeClick(final Preference preference) {
        if (REMOVE_TRUSTED_CERTIFICATES.equals(preference.getKey())) {
            showRemoveCertificatesDialog();
            return true;
        }
        return super.onPreferenceTreeClick(preference);
    }

    private void showRemoveCertificatesDialog() {
        final MemorizingTrustManager mtm = requireService().getMemorizingTrustManager();
        final ArrayList<String> aliases = Collections.list(mtm.getCertificates());
        if (aliases.isEmpty()) {
            Toast.makeText(requireActivity(), R.string.toast_no_trusted_certs, Toast.LENGTH_LONG)
                    .show();
            return;
        }
        final ArrayList<Integer> selectedItems = new ArrayList<>();
        final MaterialAlertDialogBuilder dialogBuilder =
                new MaterialAlertDialogBuilder(requireActivity());
        dialogBuilder.setTitle(getString(R.string.dialog_manage_certs_title));
        dialogBuilder.setMultiChoiceItems(
                aliases.toArray(new CharSequence[0]),
                null,
                (dialog, indexSelected, isChecked) -> {
                    if (isChecked) {
                        selectedItems.add(indexSelected);
                    } else if (selectedItems.contains(indexSelected)) {
                        selectedItems.remove(Integer.valueOf(indexSelected));
                    }
                    if (dialog instanceof AlertDialog alertDialog) {
                        alertDialog
                                .getButton(DialogInterface.BUTTON_POSITIVE)
                                .setEnabled(!selectedItems.isEmpty());
                    }
                });

        dialogBuilder.setPositiveButton(
                getString(R.string.dialog_manage_certs_positivebutton),
                (dialog, which) -> confirmCertificateDeletion(aliases, selectedItems));
        dialogBuilder.setNegativeButton(R.string.cancel, null);
        final AlertDialog removeCertsDialog = dialogBuilder.create();
        removeCertsDialog.show();
        removeCertsDialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
    }

    private void confirmCertificateDeletion(
            final ArrayList<String> aliases, final ArrayList<Integer> selectedItems) {
        final int count = selectedItems.size();
        if (count == 0) {
            return;
        }
        final MemorizingTrustManager mtm = requireService().getMemorizingTrustManager();
        for (int i = 0; i < count; i++) {
            try {
                final int item = Integer.parseInt(selectedItems.get(i).toString());
                final String alias = aliases.get(item);
                mtm.deleteCertificate(alias);
            } catch (final KeyStoreException e) {
                Toast.makeText(
                                requireActivity(),
                                "Error: " + e.getLocalizedMessage(),
                                Toast.LENGTH_LONG)
                        .show();
            }
        }
        reconnectAccounts();
        Toast.makeText(
                        requireActivity(),
                        getResources()
                                .getQuantityString(
                                        R.plurals.toast_delete_certificates, count, count),
                        Toast.LENGTH_LONG)
                .show();
    }

    private static class OmemoSummaryProvider
            implements Preference.SummaryProvider<ListPreference> {

        @Nullable
        @Override
        public CharSequence provideSummary(@NonNull ListPreference preference) {
            final var context = preference.getContext();
            final var sharedPreferences = preference.getSharedPreferences();
            final String value;
            if (sharedPreferences == null) {
                value = null;
            } else {
                value =
                        sharedPreferences.getString(
                                preference.getKey(),
                                context.getString(R.string.omemo_setting_default));
            }
            return switch (Strings.nullToEmpty(value)) {
                case "always" -> context.getString(R.string.pref_omemo_setting_summary_always);
                case "default_off" -> context.getString(
                        R.string.pref_omemo_setting_summary_default_off);
                default -> context.getString(R.string.pref_omemo_setting_summary_default_on);
            };
        }
    }

    public SettingsActivity requireSettingsActivity() {
        final var activity = requireActivity();
        if (activity instanceof SettingsActivity settingsActivity) {
            return settingsActivity;
        }
        throw new IllegalStateException(
                String.format(
                        "%s is not %s",
                        activity.getClass().getName(), SettingsActivity.class.getName()));
    }

    private boolean deleteOmemoIdentities() {
        final MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(requireActivity());
        builder.setTitle(R.string.pref_delete_omemo_identities);
        final List<CharSequence> accounts = new ArrayList<>();
        for (Account account : requireService().getAccounts()) {
            if (account.isEnabled()) {
                accounts.add(account.getJid().asBareJid().toString());
            }
        }
        final boolean[] checkedItems = new boolean[accounts.size()];
        builder.setMultiChoiceItems(
                accounts.toArray(new CharSequence[accounts.size()]),
                checkedItems,
                (dialog, which, isChecked) -> {
                    checkedItems[which] = isChecked;
                    final AlertDialog alertDialog = (AlertDialog) dialog;
                    for (boolean item : checkedItems) {
                        if (item) {
                            alertDialog.getButton(DialogInterface.BUTTON_POSITIVE).setEnabled(true);
                            return;
                        }
                    }
                    alertDialog.getButton(DialogInterface.BUTTON_POSITIVE).setEnabled(false);
                });
        builder.setNegativeButton(R.string.cancel, null);
        builder.setPositiveButton(
                R.string.delete_selected_keys,
                (dialog, which) -> {
                    for (int i = 0; i < checkedItems.length; ++i) {
                        if (checkedItems[i]) {
                            try {
                                Jid jid = Jid.of(accounts.get(i).toString());
                                Account account = requireService().findAccountByJid(jid);
                                if (account != null) {
                                    account.getAxolotlService().regenerateKeys(true);
                                }
                                Toast.makeText(requireActivity(), R.string.omemo_identities_reset, Toast.LENGTH_LONG).show();
                            } catch (IllegalArgumentException e) {
                                Toast.makeText(requireActivity(), R.string.failed_to_reset_omemo_identities, Toast.LENGTH_LONG).show();
                            }
                        }
                    }
                });
        final AlertDialog dialog = builder.create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
        return true;
    }
}
