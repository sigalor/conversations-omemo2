package eu.siacs.conversations.ui.fragment.settings;

import android.Manifest;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import androidx.preference.PreferenceManager;
import android.util.Log;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.OutOfQuotaPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.common.base.Strings;
import com.google.common.primitives.Longs;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.persistance.FileBackend;
import eu.siacs.conversations.ui.activity.SettingsActivity;
import eu.siacs.conversations.utils.ChatBackgroundHelper;
import eu.siacs.conversations.worker.ExportBackupWorker;
import me.drakeet.support.toast.ToastCompat;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class BackupSettingsFragment extends XmppPreferenceFragment {

    public static final String CREATE_ONE_OFF_BACKUP = "create_one_off_backup";
    private static final String RECURRING_BACKUP = "recurring_backup";
    public static final int REQUEST_EXPORT_SETTINGS = 0xbf8701;
    public static final int REQUEST_IMPORT_SETTINGS = 0xbf8703;

    private final ActivityResultLauncher<String> requestStorageForBackupLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestPermission(),
                    isGranted -> {
                        if (isGranted) {
                            startOneOffBackup();
                        } else {
                            Toast.makeText(
                                            requireActivity(),
                                            getString(
                                                    R.string.no_storage_permission,
                                                    getString(R.string.app_name)),
                                            Toast.LENGTH_LONG)
                                    .show();
                        }
                    });

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        setPreferencesFromResource(R.xml.preferences_backup, rootKey);
        final var createOneOffBackup = findPreference(CREATE_ONE_OFF_BACKUP);
        final var export = findPreference("export");
        final ListPreference recurringBackup = findPreference(RECURRING_BACKUP);
        final var backupDirectory = findPreference("backup_directory");
        if (createOneOffBackup == null || recurringBackup == null || backupDirectory == null) {
            throw new IllegalStateException(
                    "The preference resource file is missing some preferences");
        }
        backupDirectory.setSummary(
                getString(
                        R.string.pref_create_backup_summary,
                        FileBackend.getBackupDirectory(requireContext()).getAbsolutePath()));
        createOneOffBackup.setOnPreferenceClickListener(this::onBackupPreferenceClicked);
        export.setOnPreferenceClickListener(this::onExportClicked);
        setValues(
                recurringBackup,
                R.array.recurring_backup_values,
                value -> timeframeValueToName(requireContext(), value));

        final var importSettingsPreference = findPreference("import_settings");
        if (importSettingsPreference != null) {
            importSettingsPreference.setOnPreferenceClickListener(preference -> {
                if (requireSettingsActivity().hasStoragePermission(REQUEST_IMPORT_SETTINGS)) {
                    importSettings();
                }
                return true;
            });
        }

        final var exportSettingsPreference = findPreference("export_settings");
        if (exportSettingsPreference != null) {
            exportSettingsPreference.setOnPreferenceClickListener(preference -> {
                if (requireSettingsActivity().hasStoragePermission(REQUEST_EXPORT_SETTINGS)) {
                    exportSettings();
                }
                return true;
            });
        }
    }

    @Override
    protected void onSharedPreferenceChanged(@NonNull String key) {
        super.onSharedPreferenceChanged(key);
        if (RECURRING_BACKUP.equals(key)) {
            final var sharedPreferences = getPreferenceManager().getSharedPreferences();
            if (sharedPreferences == null) {
                return;
            }
            final Long recurringBackupInterval =
                    Longs.tryParse(
                            Strings.nullToEmpty(
                                    sharedPreferences.getString(RECURRING_BACKUP, null)));
            if (recurringBackupInterval == null) {
                return;
            }
            Log.d(
                    Config.LOGTAG,
                    "recurring backup interval changed to: " + recurringBackupInterval);
            final var workManager = WorkManager.getInstance(requireContext());
            if (recurringBackupInterval <= 0) {
                workManager.cancelUniqueWork(RECURRING_BACKUP);
            } else {
                final Constraints constraints =
                        new Constraints.Builder()
                                .setRequiresBatteryNotLow(true)
                                .setRequiresStorageNotLow(true)
                                .build();

                final PeriodicWorkRequest periodicWorkRequest =
                        new PeriodicWorkRequest.Builder(
                                ExportBackupWorker.class,
                                recurringBackupInterval,
                                TimeUnit.SECONDS)
                                .setConstraints(constraints)
                                .setInputData(
                                        new Data.Builder()
                                                .putBoolean("recurring_backup", true)
                                                .build())
                                .build();
                workManager.enqueueUniquePeriodicWork(
                        RECURRING_BACKUP, ExistingPeriodicWorkPolicy.UPDATE, periodicWorkRequest);
            }
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        requireActivity().setTitle(R.string.backup);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (grantResults.length > 0) {
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (requestCode == REQUEST_EXPORT_SETTINGS) {
                    exportSettings();
                }
                if (requestCode == REQUEST_IMPORT_SETTINGS) {
                    importSettings();
                }
            } else {
                ToastCompat.makeText(
                        requireActivity(),

                        R.string.no_storage_permission,
                        ToastCompat.LENGTH_SHORT).show();
            }
        }
    }

    private boolean onBackupPreferenceClicked(final Preference preference) {
        new AlertDialog.Builder(requireActivity())
            .setTitle(R.string.disable_all_accounts)
            .setMessage(R.string.disable_all_accounts_question)
            .setPositiveButton(R.string.yes, (dialog, whichButton) -> {
                for (final var account : requireService().getAccounts()) {
                    account.setOption(Account.OPTION_DISABLED, true);
                    if (!requireService().updateAccount(account)) {
                        Toast.makeText(requireActivity(), R.string.unable_to_update_account, Toast.LENGTH_SHORT).show();
                    }
                }
                aboutToStartOneOffBackup();
            })
            .setNegativeButton(R.string.no, (dialog, whichButton) -> aboutToStartOneOffBackup()).show();
        return true;
    }

    private void aboutToStartOneOffBackup() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    requireContext(), Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                requestStorageForBackupLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            } else {
                startOneOffBackup();
            }
        } else {
            startOneOffBackup();
        }
    }

    private void startOneOffBackup() {
        final OneTimeWorkRequest exportBackupWorkRequest =
                new OneTimeWorkRequest.Builder(ExportBackupWorker.class)
                        .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                        .build();
        WorkManager.getInstance(requireContext())
                .enqueueUniqueWork(
                        CREATE_ONE_OFF_BACKUP, ExistingWorkPolicy.KEEP, exportBackupWorkRequest);
        final MaterialAlertDialogBuilder builder =
                new MaterialAlertDialogBuilder(requireActivity());
        builder.setMessage(R.string.backup_started_message);
        builder.setPositiveButton(R.string.ok, null);
        builder.create().show();
    }

    @SuppressWarnings({ "unchecked" })
    private boolean importSettings() {
        boolean success;
        ObjectInputStream input = null;
        try {
            final File file = new File(FileBackend.getBackupDirectory(requireContext()).getAbsolutePath(),"settings.dat");
            input = new ObjectInputStream(new FileInputStream(file));
            SharedPreferences.Editor prefEdit = PreferenceManager.getDefaultSharedPreferences(requireSettingsActivity()).edit();
            prefEdit.clear();
            Map<String, ?> entries = (Map<String, ?>) input.readObject();
            for (Map.Entry<String, ?> entry : entries.entrySet()) {
                Object value = entry.getValue();
                String key = entry.getKey();

                if (value instanceof Boolean)
                    prefEdit.putBoolean(key, ((Boolean) value).booleanValue());
                else if (value instanceof Float)
                    prefEdit.putFloat(key, ((Float) value).floatValue());
                else if (value instanceof Integer)
                    prefEdit.putInt(key, ((Integer) value).intValue());
                else if (value instanceof Long)
                    prefEdit.putLong(key, ((Long) value).longValue());
                else if (value instanceof String)
                    prefEdit.putString(key, ((String) value));
            }
            prefEdit.commit();
            success = true;
        } catch (Exception e) {
            success = false;
            e.printStackTrace();
        } finally {
            try {
                if (input != null) {
                    input.close();
                }
            } catch (IOException ex) {
                ex.printStackTrace();
            }
        }
        if (success) {
            new Thread(() -> runOnUiThread(() -> requireActivity().recreate())).start();
            ToastCompat.makeText(requireActivity(), R.string.success_import_settings, ToastCompat.LENGTH_SHORT).show();
        } else {
            ToastCompat.makeText(requireActivity(), R.string.error_import_settings, ToastCompat.LENGTH_SHORT).show();
        }
        return success;
    }

    private boolean exportSettings() {
        boolean success = false;
        ObjectOutputStream output = null;
        try {
            final File file = new File(FileBackend.getBackupDirectory(requireContext()).getAbsolutePath(), "settings.dat");
            final File directory = file.getParentFile();
            if (directory != null && directory.mkdirs()) {
                Log.d(Config.LOGTAG, "created backup directory " + directory.getAbsolutePath());
            }
            output = new ObjectOutputStream(new FileOutputStream(file));
            SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(requireSettingsActivity());
            output.writeObject(pref.getAll());
            success = true;
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                if (output != null) {
                    output.flush();
                    output.close();
                }
            } catch (IOException ex) {
                ex.printStackTrace();
            }
        }
        if (success) {
            new Thread(() -> runOnUiThread(() -> requireActivity().recreate())).start();
            ToastCompat.makeText(requireActivity(), R.string.success_export_settings, ToastCompat.LENGTH_SHORT).show();
        } else {
            ToastCompat.makeText(requireActivity(), R.string.error_export_settings, ToastCompat.LENGTH_SHORT).show();
        }
        return success;
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

    private boolean onExportClicked(final Preference preference) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                            requireContext(), Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                requestStorageForBackupLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            } else {
                startExport();
            }
        } else {
            startExport();
        }
        return true;
    }

    private void startExport() {
        final OneTimeWorkRequest exportBackupWorkRequest =
                new OneTimeWorkRequest.Builder(de.monocles.chat.ExportBackupService.class)
                        .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                        .build();
        WorkManager.getInstance(requireContext())
                .enqueueUniqueWork(
                        CREATE_ONE_OFF_BACKUP, ExistingWorkPolicy.KEEP, exportBackupWorkRequest);
        final MaterialAlertDialogBuilder builder =
                new MaterialAlertDialogBuilder(requireActivity());
        builder.setMessage(R.string.backup_started_message);
        builder.setPositiveButton(R.string.ok, null);
        builder.create().show();
    }
}
