package eu.siacs.conversations;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Environment;
import android.util.Log;

import androidx.annotation.BoolRes;
import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;
import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableSet;

import eu.siacs.conversations.persistance.FileBackend;
import eu.siacs.conversations.xmpp.Jid;

import java.security.SecureRandom;
import java.util.Objects;
import java.util.Set;

public class AppSettings {

    public static final String KEEP_FOREGROUND_SERVICE = "enable_foreground_service";
    public static final String AWAY_WHEN_SCREEN_IS_OFF = "away_when_screen_off";
    public static final String TREAT_VIBRATE_AS_SILENT = "treat_vibrate_as_silent";
    public static final String DND_ON_SILENT_MODE = "dnd_on_silent_mode";
    public static final String MANUALLY_CHANGE_PRESENCE = "manually_change_presence";
    public static final String BLIND_TRUST_BEFORE_VERIFICATION = "btbv";
    public static final String AUTOMATIC_MESSAGE_DELETION = "automatic_message_deletion";
    public static final String BROADCAST_LAST_ACTIVITY = "last_activity";
    public static final String THEME = "theme";
    public static final String DYNAMIC_COLORS = "dynamic_colors";
    public static final String SHOW_DYNAMIC_TAGS = "show_dynamic_tags";
    public static final String OMEMO = "omemo";
    public static final String OMEMO_AUTO_EXPIRY = "omemo_auto_expiry";
    public static final String ALLOW_SCREENSHOTS = "allow_screenshots";
    public static final String LOAD_PROVIDERS_EXTERNAL = "load_providers_list_external";
    public static final String RINGTONE = "call_ringtone";
    public static final String BTBV = "btbv";
    public static final String DATABASE_PASSWORD = "database_password";
    public static final String APP_LOCK_PIN = "app_lock_pin";

    public static final String CONFIRM_MESSAGES = "confirm_messages";
    public static final String ALLOW_MESSAGE_CORRECTION = "allow_message_correction";

    public static final String TRUST_SYSTEM_CA_STORE = "trust_system_ca_store";
    public static final String DANE_ENFORCED = "enforce_dane";
    public static final String REQUIRE_CHANNEL_BINDING = "channel_binding_required";
    public static final String REQUIRE_TLS_V1_3 = "require_tls_v1_3";
    public static final String NOTIFICATION_RINGTONE = "notification_ringtone";
    public static final String NOTIFICATION_HEADS_UP = "notification_headsup";
    public static final String NOTIFICATION_VIBRATE = "vibrate_on_notification";
    public static final String NOTIFICATION_LED = "led";
    public static final String SHOW_CONNECTION_OPTIONS = "show_connection_options";
    public static final String USE_TOR = "use_tor";
    public static final String USE_I2P = "use_i2p";
    public static final String USE_RELAYS = "use_relays";
    public static final String CHANNEL_DISCOVERY_METHOD = "channel_discovery_method";
    public static final String SEND_CRASH_REPORTS = "send_crash_reports";
    public static final String COLORFUL_CHAT_BUBBLES = "use_green_background";
    public static final String LARGE_FONT = "large_font";
    public static final String SHOW_LINK_PREVIEWS = "show_link_previews";
    public static final String SHOW_AVATARS = "show_avatars";
    public static final String CALL_INTEGRATION = "call_integration";
    public static final String ALIGN_START = "align_start";
    public static final String BACKUP_LOCATION = "backup_location";
    public static final String HIDE_EPHEMERAL_WARNING = "hide_ephemeral_warning";

    private static final String ACCEPT_INVITES_FROM_STRANGERS = "accept_invites_from_strangers";
    private static final String INSTALLATION_ID = "im.conversations.android.install_id";
    public static final String SECURE_TLS = "secure_tls";
    public static final String PREFER_IPV6 = "prefer_ipv6";
    public static final String UNENCRYPTED_REACTIONS = "allow_unencrypted_reactions";
    public static final String DELETE_UNUSED_FILES = "delete_unused_files";
    public static final String USE_INTERNAL_SECURE_STORAGE = "default_store_media_securely";
    public static final String SHOW_MAPS_INSIDE = "show_maps_inside";
    public static final String REQUIRE_PASSWORD_ON_STARTUP = "require_password_on_startup";
    public static final String CUSTOM_RESOURCE_NAME = "custom_resource_name";
    public static final int CUSTOM_RESOURCE_NAME_MAX_LENGTH = 64;

    // In-memory session password: the char[] the user typed at startup.
    // Never written to disk. Zeroed when no longer needed. Null when locked.
    private static volatile char[] sSessionPassword = null;

    public static boolean isSessionUnlocked() {
        return sSessionPassword != null;
    }

    /** Store the entered password for this process lifetime. Zeros any previously held value. */
    public static void setSessionPassword(final char[] password) {
        final char[] old = sSessionPassword;
        sSessionPassword = password != null ? password.clone() : null;
        if (old != null) java.util.Arrays.fill(old, '\0');
    }

    /** Zero and discard the session password (e.g. on wrong-key error so the user retries). */
    public static void clearSessionPassword() {
        final char[] pw = sSessionPassword;
        sSessionPassword = null;
        if (pw != null) java.util.Arrays.fill(pw, '\0');
    }

    /** Returns the live session-password array. Callers must NOT modify or zero it. */
    public static char[] getSessionPassword() {
        return sSessionPassword;
    }

    public boolean isPasswordOnStartupRequired() {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(REQUIRE_PASSWORD_ON_STARTUP, false);
    }

    private static final String EXTERNAL_STORAGE_AUTHORITY =
            "com.android.externalstorage.documents";

    public static final Set<Jid> SECURE_DOMAINS;

    static {
        final var builder = new ImmutableSet.Builder<Jid>();
        if (Objects.nonNull(Config.MAGIC_CREATE_DOMAIN)) {
            builder.add(Jid.ofDomain(Config.MAGIC_CREATE_DOMAIN));
        }
        if (Objects.nonNull(Config.QUICKSY_DOMAIN)) {
            builder.add(Config.QUICKSY_DOMAIN);
        }
        SECURE_DOMAINS = builder.build();
    }

    private final Context context;

    public AppSettings(final Context context) {
        this.context = context;
    }

    public Uri getRingtone() {
        final SharedPreferences sharedPreferences =
                PreferenceManager.getDefaultSharedPreferences(context);
        final String incomingCallRingtone =
                sharedPreferences.getString(
                        RINGTONE, context.getString(R.string.incoming_call_ringtone));
        return Strings.isNullOrEmpty(incomingCallRingtone) ? null : Uri.parse(incomingCallRingtone);
    }

    public void setRingtone(final Uri uri) {
        final SharedPreferences sharedPreferences =
                PreferenceManager.getDefaultSharedPreferences(context);
        sharedPreferences.edit().putString(RINGTONE, uri == null ? null : uri.toString()).apply();
    }

    public Uri getNotificationTone() {
        final SharedPreferences sharedPreferences =
                PreferenceManager.getDefaultSharedPreferences(context);
        final String incomingCallRingtone =
                sharedPreferences.getString(
                        NOTIFICATION_RINGTONE, context.getString(R.string.notification_ringtone));
        return Strings.isNullOrEmpty(incomingCallRingtone) ? null : Uri.parse(incomingCallRingtone);
    }

    public void setNotificationTone(final Uri uri) {
        final SharedPreferences sharedPreferences =
                PreferenceManager.getDefaultSharedPreferences(context);
        sharedPreferences
                .edit()
                .putString(NOTIFICATION_RINGTONE, uri == null ? null : uri.toString())
                .apply();
    }

    public boolean isBTBVEnabled() {
        return getBooleanPreference(BTBV, R.bool.btbv);
    }

    public boolean isTrustSystemCAStore() {
        return getBooleanPreference(TRUST_SYSTEM_CA_STORE, R.bool.trust_system_ca_store);
    }

    public boolean isDANEnforced() {
        return getBooleanPreference(DANE_ENFORCED, R.bool.enforce_dane);
    }

    public boolean isAllowScreenshots() {
        return getBooleanPreference(ALLOW_SCREENSHOTS, R.bool.allow_screenshots);
    }

    public boolean isColorfulChatBubbles() {
        return getBooleanPreference(COLORFUL_CHAT_BUBBLES, R.bool.use_green_background);
    }

    public boolean isLargeFont() {
        return getBooleanPreference(LARGE_FONT, R.bool.large_font);
    }

    public boolean showLinkPreviews() {
        return getBooleanPreference(SHOW_LINK_PREVIEWS, R.bool.show_link_previews);
    }

    public boolean isShowAvatars() {
        return getBooleanPreference(SHOW_AVATARS, R.bool.show_avatars);
    }

    public boolean isDeleteUnusedFiles() {
        return getBooleanPreference(DELETE_UNUSED_FILES, R.bool.delete_unused_files);
    }

    public boolean isCallIntegration() {
        return getBooleanPreference(CALL_INTEGRATION, R.bool.call_integration);
    }

    public boolean isAlignStart() {
        return getBooleanPreference(ALIGN_START, R.bool.align_start);
    }

    public boolean isSecureTLS() {
        return getBooleanPreference(SECURE_TLS, R.bool.secure_tls);
    }

    public boolean preferIPv6() {
        return getBooleanPreference(PREFER_IPV6, R.bool.prefer_ipv6);
    }

    public boolean isUseTor() {
        return getBooleanPreference(USE_TOR, R.bool.use_tor);
    }

    public boolean isUseRelays() {
        return getBooleanPreference(USE_RELAYS, R.bool.use_relays);
    }

    public boolean isExtendedConnectionOptions() {
        return getBooleanPreference(
                        AppSettings.SHOW_CONNECTION_OPTIONS, R.bool.show_connection_options);
    }

    public boolean isUseI2P() {
        return getBooleanPreference(USE_I2P, R.bool.use_i2p);
    }

    public String getCustomResourceName() {
        final String value =
                Strings.nullToEmpty(
                                PreferenceManager.getDefaultSharedPreferences(context)
                                        .getString(CUSTOM_RESOURCE_NAME, ""))
                        .trim();
        return value.length() > CUSTOM_RESOURCE_NAME_MAX_LENGTH
                ? value.substring(0, CUSTOM_RESOURCE_NAME_MAX_LENGTH)
                : value;
    }

    private boolean getBooleanPreference(@NonNull final String name, @BoolRes int res) {
        final SharedPreferences sharedPreferences =
                PreferenceManager.getDefaultSharedPreferences(context);
        return sharedPreferences.getBoolean(name, context.getResources().getBoolean(res));
    }

    public String getOmemo() {
        final SharedPreferences sharedPreferences =
                PreferenceManager.getDefaultSharedPreferences(context);
        return sharedPreferences.getString(
                OMEMO, context.getString(R.string.omemo_setting_default));
    }

    public Uri getBackupLocation() {
        final SharedPreferences sharedPreferences =
                PreferenceManager.getDefaultSharedPreferences(context);
        final String location = sharedPreferences.getString(BACKUP_LOCATION, null);
        if (Strings.isNullOrEmpty(location)) {
            final var directory = FileBackend.getBackupDirectory(context);
            return Uri.fromFile(directory);
        }
        return Uri.parse(location);
    }

    public String getBackupLocationAsPath() {
        return asPath(getBackupLocation());
    }

    public static String asPath(final Uri uri) {
        final var scheme = uri.getScheme();
        final var path = uri.getPath();
        if (path == null) {
            return uri.toString();
        }
        if ("file".equalsIgnoreCase(scheme)) {
            return path;
        } else if ("content".equalsIgnoreCase(scheme)) {
            if (EXTERNAL_STORAGE_AUTHORITY.equalsIgnoreCase(uri.getAuthority())) {
                final var parts = Splitter.on(':').limit(2).splitToList(path);
                if (parts.size() == 2 && "/tree/primary".equals(parts.get(0))) {
                    return Joiner.on('/')
                            .join(Environment.getExternalStorageDirectory(), parts.get(1));
                }
            }
        }
        return uri.toString();
    }

    public void setBackupLocation(final Uri uri) {
        final SharedPreferences sharedPreferences =
                PreferenceManager.getDefaultSharedPreferences(context);
        sharedPreferences
                .edit()
                .putString(BACKUP_LOCATION, uri == null ? "" : uri.toString())
                .apply();
    }

    public boolean isSendCrashReports() {
        return getBooleanPreference(SEND_CRASH_REPORTS, R.bool.send_crash_reports);
    }

    public void setSendCrashReports(boolean value) {
        final SharedPreferences sharedPreferences =
                PreferenceManager.getDefaultSharedPreferences(context);
        sharedPreferences.edit().putBoolean(SEND_CRASH_REPORTS, value).apply();
    }

    public boolean isRequireChannelBinding() {
        return getBooleanPreference(REQUIRE_CHANNEL_BINDING, R.bool.require_channel_binding);
    }

    public boolean isRequireTlsV13() {
        return getBooleanPreference(REQUIRE_TLS_V1_3, R.bool.require_tls_v1_3);
    }

    public synchronized long getInstallationId() {
        final var sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        final long existing = sharedPreferences.getLong(INSTALLATION_ID, 0);
        if (existing != 0) {
            return existing;
        }
        final var secureRandom = new SecureRandom();
        final var installationId = secureRandom.nextLong();
        sharedPreferences.edit().putLong(INSTALLATION_ID, installationId).apply();
        return installationId;
    }

    public synchronized void resetInstallationId() {
        final var secureRandom = new SecureRandom();
        final var installationId = secureRandom.nextLong();
        PreferenceManager.getDefaultSharedPreferences(context)
                .edit()
                .putLong(INSTALLATION_ID, installationId)
                .apply();
    }

    public char[] getDatabasePasswordChars() {
        if (isPasswordOnStartupRequired()) {
            if (sSessionPassword == null) {
                throw new EncryptionException(
                        "Database requires startup password",
                        null,
                        EncryptionException.Reason.NEEDS_SESSION_PASSWORD);
            }
            return sSessionPassword.clone(); // no String ever created in this path
        }
        // Normal mode: SharedPreferences API returns a String — unavoidable at the OS boundary.
        // Convert to char[] immediately and let the String reference go out of scope.
        try {
            final SharedPreferences encryptedPrefs = getEncryptedPreferences();
            String pw = encryptedPrefs.getString(DATABASE_PASSWORD, null);
            if (pw == null) {
                final SharedPreferences normalPrefs = PreferenceManager.getDefaultSharedPreferences(context);
                final String legacyPw = normalPrefs.getString(DATABASE_PASSWORD, null);
                if (legacyPw != null) {
                    encryptedPrefs.edit().putString(DATABASE_PASSWORD, legacyPw).commit();
                    normalPrefs.edit().remove(DATABASE_PASSWORD).commit();
                    Log.d("AppSettings", "Migrated database password to encrypted storage");
                    pw = legacyPw;
                }
            }
            return pw != null ? pw.toCharArray() : null;
        } catch (EncryptionException e) {
            throw e;
        } catch (Exception e) {
            Log.e("AppSettings", "Could not load encrypted shared preferences", e);
            throw new EncryptionException("Could not load encrypted shared preferences", e);
        }
    }

    public void setDatabasePassword(final char[] password) {
        if (password != null && password.length == 0) {
            throw new IllegalArgumentException("Password cannot be empty");
        }
        if (isPasswordOnStartupRequired()) {
            // Never write to disk in startup-required mode; update the in-memory session copy.
            if (password == null) {
                // Encryption disabled — clear session and remove the startup-required flag.
                clearSessionPassword();
                PreferenceManager.getDefaultSharedPreferences(context)
                        .edit().putBoolean(REQUIRE_PASSWORD_ON_STARTUP, false).commit();
            } else {
                setSessionPassword(password); // no String created
            }
            // Purge any stale entry that might exist in encrypted storage.
            try {
                getEncryptedPreferences().edit().remove(DATABASE_PASSWORD).commit();
            } catch (Exception ignored) {}
            PreferenceManager.getDefaultSharedPreferences(context).edit().remove(DATABASE_PASSWORD).apply();
            return;
        }
        // Normal mode: SharedPreferences only supports putString — create the String inline so
        // its scope is limited to this single call and no reference escapes this method.
        try {
            final SharedPreferences encryptedPrefs = getEncryptedPreferences();
            encryptedPrefs.edit()
                    .putString(DATABASE_PASSWORD, password != null ? new String(password) : null)
                    .commit();
            PreferenceManager.getDefaultSharedPreferences(context).edit().remove(DATABASE_PASSWORD).apply();
        } catch (EncryptionException e) {
            throw e;
        } catch (Exception e) {
            Log.e("AppSettings", "Could not save encrypted shared preferences", e);
            throw new EncryptionException("Could not save encrypted shared preferences", e);
        }
    }

    public void checkEncryptionOrThrow() throws EncryptionException {
        final char[] chars = getDatabasePasswordChars();
        if (chars != null) java.util.Arrays.fill(chars, '\0');
    }

    public SharedPreferences getEncryptedPreferences() throws Exception {
        return getEncryptedPreferences("encrypted_settings");
    }

    public SharedPreferences getEncryptedPreferences(String name) throws Exception {
        MasterKey masterKey = new MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .setRequestStrongBoxBacked(true)
                .build();
        return EncryptedSharedPreferences.create(
                context,
                name,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        );
    }
}
