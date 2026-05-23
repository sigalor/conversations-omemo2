package eu.siacs.conversations.persistance;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.DatabaseUtils;
import net.zetetic.database.sqlcipher.SQLiteDatabase;
import net.zetetic.database.sqlcipher.SQLiteOpenHelper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;

import java.io.File;
import java.util.List;

import eu.siacs.conversations.AppSettings;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.services.UnifiedPushBroker;
import eu.siacs.conversations.utils.FileHelper;

public class UnifiedPushDatabase extends SQLiteOpenHelper {

    private static final String DATABASE_NAME = "unified-push-distributor";
    private static final int DATABASE_VERSION = 1;
    private static final String REKEY_MIGRATION_IN_PROGRESS = "rekey_migration_updb_in_progress";

    private static UnifiedPushDatabase instance;

    public static synchronized void closeInstance() {
        if (instance != null) {
            instance.close();
            instance = null;
        }
    }

    /**
     * Re-encrypts the UPDB with a new password (Argon2id) or reverts to auto-encryption.
     * Mirrors DatabaseBackend.migrate() semantics:
     * {@code oldPassword null} = old UPDB is auto-encrypted;
     * {@code newPassword null} = new UPDB uses auto-encryption.
     */
    public static synchronized void migrate(Context context, char[] oldPassword, char[] newPassword) throws Exception {
        closeInstance();
        System.loadLibrary("sqlcipher");
        final AppSettings settings = new AppSettings(context);
        final File dbFile = context.getDatabasePath(DATABASE_NAME);

        // Generate new key material in memory; persisted only AFTER the file rename.
        final byte[] newSalt;
        final byte[] newAutoKey;
        final byte[] newRawKey;
        if (newPassword != null) {
            newSalt = eu.siacs.conversations.Argon2KeyDerivation.INSTANCE.generateSalt();
            newAutoKey = null;
            newRawKey = eu.siacs.conversations.Argon2KeyDerivation.INSTANCE
                    .deriveRawKeyBytes(newPassword, newSalt);
        } else {
            newSalt = null;
            newAutoKey = eu.siacs.conversations.Argon2KeyDerivation.INSTANCE.generateRandomKey();
            newRawKey = eu.siacs.conversations.Argon2KeyDerivation.INSTANCE
                    .deriveAutoRawKeyBytes(newAutoKey);
        }

        try {
            if (!dbFile.exists()) {
                persistNewKeyState(settings, newPassword, newSalt, newAutoKey);
                return;
            }

            final File tempFile = context.getDatabasePath(DATABASE_NAME + ".tmp");
            if (tempFile.exists() && !tempFile.delete()) {
                throw new java.io.IOException("Failed to delete existing temporary database file");
            }
            if (tempFile.getParentFile() != null && !tempFile.getParentFile().exists()
                    && !tempFile.getParentFile().mkdirs()) {
                throw new java.io.IOException("Failed to create database directory");
            }
            if (!tempFile.createNewFile()) {
                throw new java.io.IOException("Failed to create temporary database file");
            }

            // Derive the OLD key using the UPDB-specific salt (not the main DB's).
            final byte[] oldRawKey;
            final byte[] oldUpdbSalt = settings.getArgon2SaltForUpdb();
            if (oldUpdbSalt != null) {
                // UPDB was Argon2id-encrypted with user password.
                if (oldPassword == null) {
                    throw new eu.siacs.conversations.EncryptionException(
                            "Old password required to open Argon2id-encrypted UPDB", null,
                            eu.siacs.conversations.EncryptionException.Reason.NEEDS_SESSION_PASSWORD);
                }
                oldRawKey = eu.siacs.conversations.Argon2KeyDerivation.INSTANCE
                        .deriveRawKeyBytes(oldPassword, oldUpdbSalt);
            } else {
                // UPDB was auto-encrypted — read its auto key (never generate here).
                final byte[] storedUpdbAutoKey =
                        new eu.siacs.conversations.SecurePasswordStorage(context).readAutoKeyForUpdb();
                if (storedUpdbAutoKey == null) {
                    throw new eu.siacs.conversations.EncryptionException(
                            "Cannot open auto-encrypted UPDB: auto key missing", null,
                            eu.siacs.conversations.EncryptionException.Reason.KEYSTORE_ERROR);
                }
                try {
                    oldRawKey = eu.siacs.conversations.Argon2KeyDerivation.INSTANCE
                            .deriveAutoRawKeyBytes(storedUpdbAutoKey);
                } finally {
                    java.util.Arrays.fill(storedUpdbAutoKey, (byte) 0);
                }
            }

            SQLiteDatabase db;
            try {
                db = SQLiteDatabase.openDatabase(
                        dbFile.getAbsolutePath(), oldRawKey, null,
                        SQLiteDatabase.OPEN_READWRITE | SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING,
                        DatabaseBackend.ARGON2_DATABASE_HOOK);
            } finally {
                java.util.Arrays.fill(oldRawKey, (byte) 0);
            }

            int version = db.getVersion();

            final boolean isEmpty;
            try (final Cursor c = db.rawQuery(
                    "SELECT count(*) FROM sqlite_master WHERE type='table'", null)) {
                isEmpty = version == 0 && c != null && c.moveToFirst() && c.getInt(0) == 0;
            }
            if (isEmpty) {
                db.close();
                tempFile.delete();
                FileHelper.secureDelete(dbFile);
                FileHelper.secureDelete(new File(dbFile.getAbsolutePath() + "-wal"));
                FileHelper.secureDelete(new File(dbFile.getAbsolutePath() + "-shm"));
                persistNewKeyState(settings, newPassword, newSalt, newAutoKey);
                return;
            }

            try {
                db.rawExecSQL("PRAGMA cipher_default_use_hmac = ON;");
                db.rawExecSQL("PRAGMA cipher_default_memory_security = ON;");
                // Wrap in SQL string literal (not blob literal) for SQLCipher raw-key detection.
                final String keyStr = new String(newRawKey, java.nio.charset.StandardCharsets.UTF_8);
                final String attachKeySql = "'" + keyStr.replace("'", "''") + "'";
                db.rawExecSQL("ATTACH DATABASE " + DatabaseUtils.sqlEscapeString(tempFile.getAbsolutePath())
                        + " AS encrypted KEY " + attachKeySql);
                db.rawExecSQL("SELECT sqlcipher_export('encrypted');");
                db.rawExecSQL("PRAGMA encrypted.user_version = " + version);
                db.rawExecSQL("DETACH DATABASE encrypted;");
            } finally {
                db.close();
            }

            final File backupFile = context.getDatabasePath(DATABASE_NAME + ".bak");
            if (backupFile.exists() && !backupFile.delete()) {
                throw new java.io.IOException("Failed to delete existing backup file");
            }

            PreferenceManager.getDefaultSharedPreferences(context)
                    .edit().putBoolean(REKEY_MIGRATION_IN_PROGRESS, true).commit();

            boolean prefsUpdated = false;
            try {
                if (!dbFile.renameTo(backupFile)) {
                    throw new java.io.IOException("Failed to backup old database file");
                }
                if (!tempFile.renameTo(dbFile)) {
                    if (!backupFile.renameTo(dbFile)) {
                        Log.e(Config.LOGTAG, "updb rekey: CRITICAL — failed to rollback after temp rename failure");
                    }
                    throw new java.io.IOException("Failed to rename temporary database file");
                }
                persistNewKeyState(settings, newPassword, newSalt, newAutoKey);
                prefsUpdated = true;
                PreferenceManager.getDefaultSharedPreferences(context)
                        .edit().remove(REKEY_MIGRATION_IN_PROGRESS).commit();
                FileHelper.secureDelete(backupFile);
                FileHelper.secureDelete(new File(backupFile.getAbsolutePath() + "-wal"));
                FileHelper.secureDelete(new File(backupFile.getAbsolutePath() + "-shm"));
                FileHelper.secureDelete(new File(dbFile.getAbsolutePath() + "-wal"));
                FileHelper.secureDelete(new File(dbFile.getAbsolutePath() + "-shm"));
            } catch (Exception e) {
                if (!prefsUpdated) {
                    PreferenceManager.getDefaultSharedPreferences(context)
                            .edit().remove(REKEY_MIGRATION_IN_PROGRESS).apply();
                }
                throw e;
            }
        } finally {
            java.util.Arrays.fill(newRawKey, (byte) 0);
            if (newAutoKey != null) java.util.Arrays.fill(newAutoKey, (byte) 0);
        }
    }

    /**
     * Persists UPDB key state after a successful file rename.
     * For Argon2id mode: writes UPDB-specific salt (password managed by DatabaseBackend).
     * For auto mode: writes new UPDB auto key, clears old UPDB salt.
     */
    private static void persistNewKeyState(
            AppSettings settings, char[] newPassword, byte[] newSalt, byte[] newAutoKey) {
        if (newPassword != null) {
            settings.setUpdbPasswordAndSalt(newPassword, newSalt);
            settings.setArgon2idKdf(); // idempotent if already set by DatabaseBackend
            settings.clearAutoKeyForUpdb(); // clean up any pre-existing UPDB auto key
        } else {
            // Auto mode: write new UPDB auto key first, then update state and clear old salt.
            settings.writeAutoKeyForUpdb(newAutoKey);
            settings.setAutoKeyMode(); // idempotent
            settings.clearUpdbArgon2Salt(); // clear old Argon2id UPDB salt if any
        }
    }

    /**
     * Derives the key bytes for the UnifiedPush distributor database. The UPDB is always
     * encrypted — either with a user password (Argon2id, UPDB-specific salt) or with a
     * hardware-bound random auto key.
     */
    private static byte[] getKeyBytesForUpdb(Context context) {
        final AppSettings appSettings = new AppSettings(context);
        final byte[] updbSalt = appSettings.getArgon2SaltForUpdb();
        if (updbSalt != null) {
            // UPDB is Argon2id-encrypted with a user password.
            final char[] password = appSettings.getDatabasePasswordChars();
            try {
                return eu.siacs.conversations.Argon2KeyDerivation.INSTANCE
                        .deriveRawKeyBytes(password, updbSalt);
            } finally {
                java.util.Arrays.fill(password, '\0');
            }
        }
        // Auto mode: use or generate a hardware-bound random UPDB key.
        final byte[] rawAutoKey = appSettings.getOrCreateAutoKeyForUpdb();
        try {
            return eu.siacs.conversations.Argon2KeyDerivation.INSTANCE.deriveAutoRawKeyBytes(rawAutoKey);
        } finally {
            java.util.Arrays.fill(rawAutoKey, (byte) 0);
        }
    }

    private UnifiedPushDatabase(@Nullable Context context) {
        this(context, getKeyBytesForUpdb(context));
    }

    private UnifiedPushDatabase(@Nullable Context context, byte[] keyBytes) {
        super(context, DATABASE_NAME, keyBytes, null, DATABASE_VERSION, 0, null,
                DatabaseBackend.ARGON2_DATABASE_HOOK, true);
    }

    public static UnifiedPushDatabase getInstance(final Context context) {
        synchronized (UnifiedPushDatabase.class) {
            if (instance == null) {
                System.loadLibrary("sqlcipher");
                resetOnInterruptedMigration(context);
                encryptLegacyPlaintextDatabase(context);
                instance = new UnifiedPushDatabase(context.getApplicationContext());
            }
            return instance;
        }
    }

    /**
     * If the process was killed during a UPDB rekey migration, delete all UPDB files and reset
     * the UPDB key state to auto mode. UPDB data (push endpoint registrations) is non-critical
     * and repopulated automatically, so a full crash recovery is unnecessary.
     *
     * Clearing the UPDB Argon2id salt ensures getKeyBytesForUpdb() always falls back to auto
     * mode on the next open, regardless of whether the crash happened before or after
     * persistNewKeyState() updated the prefs.
     */
    private static void resetOnInterruptedMigration(Context context) {
        if (!androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(REKEY_MIGRATION_IN_PROGRESS, false)) return;

        Log.w(Config.LOGTAG, "updb rekey: sentinel set — resetting UPDB after interrupted migration");

        final File dbFile     = context.getDatabasePath(DATABASE_NAME);
        final File tempFile   = context.getDatabasePath(DATABASE_NAME + ".tmp");
        final File backupFile = context.getDatabasePath(DATABASE_NAME + ".bak");

        for (final File f : new File[]{
                dbFile,     new File(dbFile.getAbsolutePath()     + "-wal"), new File(dbFile.getAbsolutePath()     + "-shm"),
                tempFile,   new File(tempFile.getAbsolutePath()   + "-wal"), new File(tempFile.getAbsolutePath()   + "-shm"),
                backupFile, new File(backupFile.getAbsolutePath() + "-wal"), new File(backupFile.getAbsolutePath() + "-shm"),
        }) {
            if (f.exists()) FileHelper.secureDelete(f);
        }

        // Reset UPDB to auto mode so a fresh DB is created with a consistent key state.
        new eu.siacs.conversations.AppSettings(context).clearUpdbArgon2Salt();

        androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
                .edit().remove(REKEY_MIGRATION_IN_PROGRESS).apply();
    }

    /** Encrypts a plaintext UPDB left over from a pre-encryption release. Mirrors DatabaseBackend. */
    private static void encryptLegacyPlaintextDatabase(Context context) {
        final File dbFile = context.getDatabasePath(DATABASE_NAME);
        if (!dbFile.exists() || !DatabaseBackend.looksLikePlainSqlite(dbFile)) return;

        Log.i(Config.LOGTAG, "updb rekey: plaintext database from pre-encryption release — encrypting");

        final eu.siacs.conversations.AppSettings settings = new eu.siacs.conversations.AppSettings(context);
        final byte[] newAutoKey = eu.siacs.conversations.Argon2KeyDerivation.INSTANCE.generateRandomKey();
        final byte[] newRawKey = eu.siacs.conversations.Argon2KeyDerivation.INSTANCE.deriveAutoRawKeyBytes(newAutoKey);
        try {
            final File tempFile = context.getDatabasePath(DATABASE_NAME + ".tmp");
            if (tempFile.exists() && !tempFile.delete()) {
                throw new java.io.IOException("Failed to delete existing temp file");
            }
            if (!tempFile.createNewFile()) {
                throw new java.io.IOException("Failed to create temp file");
            }

            final net.zetetic.database.sqlcipher.SQLiteDatabase db =
                    net.zetetic.database.sqlcipher.SQLiteDatabase.openDatabase(
                            dbFile.getAbsolutePath(), (byte[]) null, null,
                            net.zetetic.database.sqlcipher.SQLiteDatabase.OPEN_READWRITE
                                    | net.zetetic.database.sqlcipher.SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING,
                            null);
            try {
                final int version = db.getVersion();
                final String keyStr = new String(newRawKey, java.nio.charset.StandardCharsets.UTF_8);
                final String attachKeySql = "'" + keyStr.replace("'", "''") + "'";
                db.rawExecSQL("ATTACH DATABASE "
                        + android.database.DatabaseUtils.sqlEscapeString(tempFile.getAbsolutePath())
                        + " AS encrypted KEY " + attachKeySql);
                db.rawExecSQL("SELECT sqlcipher_export('encrypted');");
                db.rawExecSQL("PRAGMA encrypted.user_version = " + version);
                db.rawExecSQL("DETACH DATABASE encrypted;");
            } finally {
                db.close();
            }

            final File backupFile = context.getDatabasePath(DATABASE_NAME + ".bak");
            if (backupFile.exists()) backupFile.delete();

            androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
                    .edit().putBoolean(REKEY_MIGRATION_IN_PROGRESS, true).commit();

            boolean prefsUpdated = false;
            try {
                if (!dbFile.renameTo(backupFile)) {
                    throw new java.io.IOException("Failed to rename DB to backup");
                }
                if (!tempFile.renameTo(dbFile)) {
                    if (!backupFile.renameTo(dbFile)) {
                        Log.e(Config.LOGTAG, "updb rekey: CRITICAL — could not roll back legacy encryption");
                    }
                    throw new java.io.IOException("Failed to rename temp to DB");
                }
                settings.writeAutoKeyForUpdb(newAutoKey);
                prefsUpdated = true;
                androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
                        .edit().remove(REKEY_MIGRATION_IN_PROGRESS).commit();
                FileHelper.secureDelete(backupFile);
                FileHelper.secureDelete(new File(backupFile.getAbsolutePath() + "-wal"));
                FileHelper.secureDelete(new File(backupFile.getAbsolutePath() + "-shm"));
                FileHelper.secureDelete(new File(dbFile.getAbsolutePath() + "-wal"));
                FileHelper.secureDelete(new File(dbFile.getAbsolutePath() + "-shm"));
                Log.i(Config.LOGTAG, "updb rekey: legacy database successfully encrypted");
            } catch (Exception e) {
                if (!prefsUpdated) {
                    androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
                            .edit().remove(REKEY_MIGRATION_IN_PROGRESS).apply();
                }
                throw e;
            }
        } catch (Exception e) {
            Log.e(Config.LOGTAG, "updb rekey: failed to encrypt legacy plaintext database", e);
        } finally {
            java.util.Arrays.fill(newRawKey, (byte) 0);
            java.util.Arrays.fill(newAutoKey, (byte) 0);
        }
    }

    @Override
    public void onCreate(final SQLiteDatabase sqLiteDatabase) {
        sqLiteDatabase.execSQL(
                "CREATE TABLE if not exists push (account TEXT, transport TEXT, application TEXT NOT NULL, instance TEXT NOT NULL UNIQUE, endpoint TEXT, expiration NUMBER DEFAULT 0)");
    }

    public boolean register(final String application, final String instance) {
        final SQLiteDatabase sqLiteDatabase = getWritableDatabase();
        sqLiteDatabase.beginTransaction();
        final Optional<String> existingApplication;
        try (final Cursor cursor =
                sqLiteDatabase.query(
                        "push",
                        new String[] {"application"},
                        "instance=?",
                        new String[] {instance},
                        null,
                        null,
                        null)) {
            if (cursor != null && cursor.moveToFirst()) {
                existingApplication = Optional.of(cursor.getString(0));
            } else {
                existingApplication = Optional.absent();
            }
        }
        if (existingApplication.isPresent()) {
            sqLiteDatabase.setTransactionSuccessful();
            sqLiteDatabase.endTransaction();
            return application.equals(existingApplication.get());
        }
        final ContentValues contentValues = new ContentValues();
        contentValues.put("application", application);
        contentValues.put("instance", instance);
        contentValues.put("expiration", 0);
        final long inserted = sqLiteDatabase.insert("push", null, contentValues);
        if (inserted > 0) {
            Log.d(Config.LOGTAG, "inserted new application/instance tuple into unified push db");
        }
        sqLiteDatabase.setTransactionSuccessful();
        sqLiteDatabase.endTransaction();
        return true;
    }

    public List<PushTarget> getRenewals(final String account, final String transport) {
        final ImmutableList.Builder<PushTarget> renewalBuilder = ImmutableList.builder();
        final long expiration = System.currentTimeMillis() + UnifiedPushBroker.TIME_TO_RENEW;
        final SQLiteDatabase sqLiteDatabase = getReadableDatabase();
        try (final Cursor cursor =
                sqLiteDatabase.query(
                        "push",
                        new String[] {"application", "instance"},
                        "account <> ? OR transport <> ? OR expiration < " + expiration,
                        new String[] {account, transport},
                        null,
                        null,
                        null)) {
            while (cursor != null && cursor.moveToNext()) {
                renewalBuilder.add(
                        new PushTarget(
                                cursor.getString(cursor.getColumnIndexOrThrow("application")),
                                cursor.getString(cursor.getColumnIndexOrThrow("instance"))));
            }
        }
        return renewalBuilder.build();
    }

    public ApplicationEndpoint getEndpoint(
            final String account, final String transport, final String instance) {
        final long expiration = System.currentTimeMillis() + UnifiedPushBroker.TIME_TO_RENEW;
        final SQLiteDatabase sqLiteDatabase = getReadableDatabase();
        try (final Cursor cursor =
                sqLiteDatabase.query(
                        "push",
                        new String[] {"application", "endpoint"},
                        "account = ? AND transport = ? AND instance = ? AND endpoint IS NOT NULL AND expiration >= "
                                + expiration,
                        new String[] {account, transport, instance},
                        null,
                        null,
                        null)) {
            if (cursor != null && cursor.moveToFirst()) {
                return new ApplicationEndpoint(
                        cursor.getString(cursor.getColumnIndexOrThrow("application")),
                        cursor.getString(cursor.getColumnIndexOrThrow("endpoint")));
            }
        }
        return null;
    }

    public List<PushTarget> deletePushTargets() {
        final SQLiteDatabase sqLiteDatabase = getReadableDatabase();
        final ImmutableList.Builder<PushTarget> builder = new ImmutableList.Builder<>();
        try (final Cursor cursor = sqLiteDatabase.query("push",new String[]{"application","instance"},null,null,null,null,null)) {
            if (cursor != null && cursor.moveToFirst()) {
                builder.add(new PushTarget(
                        cursor.getString(cursor.getColumnIndexOrThrow("application")),
                        cursor.getString(cursor.getColumnIndexOrThrow("instance"))));
            }
        } catch (final Exception e) {
            Log.d(Config.LOGTAG,"unable to retrieve push targets",e);
            return builder.build();
        }
        sqLiteDatabase.delete("push",null,null);
        return builder.build();
    }

    public boolean hasEndpoints(final UnifiedPushBroker.Transport transport) {
        final SQLiteDatabase sqLiteDatabase = getReadableDatabase();
        try (final Cursor cursor =
                sqLiteDatabase.rawQuery(
                        "SELECT EXISTS(SELECT endpoint FROM push WHERE account = ? AND transport = ?)",
                        new String[] {
                            transport.account.getUuid(), transport.transport.toString()
                        })) {
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getInt(0) > 0;
            }
        }
        return false;
    }

    @Override
    public void onUpgrade(
            final SQLiteDatabase sqLiteDatabase, final int oldVersion, final int newVersion) {}

    public boolean updateEndpoint(
            final String instance,
            final String account,
            final String transport,
            final String endpoint,
            final long expiration) {
        final SQLiteDatabase sqLiteDatabase = getWritableDatabase();
        sqLiteDatabase.beginTransaction();
        final String existingEndpoint;
        try (final Cursor cursor =
                sqLiteDatabase.query(
                        "push",
                        new String[] {"endpoint"},
                        "instance=?",
                        new String[] {instance},
                        null,
                        null,
                        null)) {
            if (cursor != null && cursor.moveToFirst()) {
                existingEndpoint = cursor.getString(0);
            } else {
                existingEndpoint = null;
            }
        }
        final ContentValues contentValues = new ContentValues();
        contentValues.put("account", account);
        contentValues.put("transport", transport);
        contentValues.put("endpoint", endpoint);
        contentValues.put("expiration", expiration);
        sqLiteDatabase.update("push", contentValues, "instance=?", new String[] {instance});
        sqLiteDatabase.setTransactionSuccessful();
        sqLiteDatabase.endTransaction();
        return !endpoint.equals(existingEndpoint);
    }

    public List<PushTarget> getPushTargets(final String account, final String transport) {
        final ImmutableList.Builder<PushTarget> renewalBuilder = ImmutableList.builder();
        final SQLiteDatabase sqLiteDatabase = getReadableDatabase();
        try (final Cursor cursor =
                sqLiteDatabase.query(
                        "push",
                        new String[] {"application", "instance"},
                        "account = ?",
                        new String[] {account},
                        null,
                        null,
                        null)) {
            while (cursor != null && cursor.moveToNext()) {
                renewalBuilder.add(
                        new PushTarget(
                                cursor.getString(cursor.getColumnIndexOrThrow("application")),
                                cursor.getString(cursor.getColumnIndexOrThrow("instance"))));
            }
        }
        return renewalBuilder.build();
    }

    public boolean deleteInstance(final String instance) {
        final SQLiteDatabase sqLiteDatabase = getReadableDatabase();
        final int rows = sqLiteDatabase.delete("push", "instance=?", new String[] {instance});
        return rows >= 1;
    }

    public boolean deleteApplication(final String application) {
        final SQLiteDatabase sqLiteDatabase = getReadableDatabase();
        final int rows = sqLiteDatabase.delete("push", "application=?", new String[] {application});
        return rows >= 1;
    }

    public static class ApplicationEndpoint {
        public final String application;
        public final String endpoint;

        public ApplicationEndpoint(String application, String endpoint) {
            this.application = application;
            this.endpoint = endpoint;
        }
    }

    public static class PushTarget {
        public final String application;
        public final String instance;

        public PushTarget(final String application, final String instance) {
            this.application = application;
            this.instance = instance;
        }

        @NonNull
        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                    .add("application", application)
                    .add("instance", instance)
                    .toString();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            PushTarget that = (PushTarget) o;
            return Objects.equal(application, that.application)
                    && Objects.equal(instance, that.instance);
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(application, instance);
        }
    }
}
