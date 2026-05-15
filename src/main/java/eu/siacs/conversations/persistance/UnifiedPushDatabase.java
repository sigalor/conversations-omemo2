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

    private static UnifiedPushDatabase instance;

    public static synchronized void closeInstance() {
        if (instance != null) {
            instance.close();
            instance = null;
        }
    }

    public static synchronized void migrate(Context context, char[] oldPassword, char[] newPassword) throws Exception {
        closeInstance();
        System.loadLibrary("sqlcipher");
        File dbFile = context.getDatabasePath(DATABASE_NAME);
        if (!dbFile.exists()) {
            new AppSettings(context).setDatabasePassword(newPassword);
            return;
        }

        File tempFile = context.getDatabasePath(DATABASE_NAME + ".tmp");
        if (tempFile.exists() && !tempFile.delete()) {
            throw new java.io.IOException("Failed to delete existing temporary database file");
        }
        if (tempFile.getParentFile() != null && !tempFile.getParentFile().exists() && !tempFile.getParentFile().mkdirs()) {
            throw new java.io.IOException("Failed to create database directory");
        }
        if (!tempFile.createNewFile()) {
            throw new java.io.IOException("Failed to create temporary database file");
        }

        final byte[] oldPwBytes = oldPassword != null ? DatabaseBackend.charsToUtf8Bytes(oldPassword) : null;
        SQLiteDatabase db = SQLiteDatabase.openDatabase(
                dbFile.getAbsolutePath(), oldPwBytes, null,
                SQLiteDatabase.OPEN_READWRITE,
                oldPwBytes != null ? DatabaseBackend.DATABASE_HOOK : null);
        if (oldPwBytes != null) java.util.Arrays.fill(oldPwBytes, (byte) 0);
        int version = db.getVersion();
        try {
            // Set Argon2id parameters as connection-wide defaults so the attached DB inherits them
            db.rawExecSQL("PRAGMA cipher_default_kdf_algorithm = argon2id;");
            db.rawExecSQL("PRAGMA cipher_default_memory_limit = 65536;");
            db.rawExecSQL("PRAGMA cipher_default_kdf_iterations = 3;");
            db.rawExecSQL("PRAGMA cipher_default_kdf_parallelism = 4;");

            // ATTACH KEY must be a SQL string literal — unavoidable String; null it immediately.
            String newPwStr = newPassword == null ? "" : new String(newPassword);
            final String escapedNewPassword = DatabaseUtils.sqlEscapeString(newPwStr);
            newPwStr = null;
            String attachSql = "ATTACH DATABASE " + DatabaseUtils.sqlEscapeString(tempFile.getAbsolutePath()) + " AS encrypted KEY " + escapedNewPassword;
            db.rawExecSQL(attachSql);
            db.rawExecSQL("SELECT sqlcipher_export('encrypted');");
            db.rawExecSQL("PRAGMA encrypted.user_version = " + version);
            db.rawExecSQL("DETACH DATABASE encrypted;");
        } finally {
            db.close();
        }

        final AppSettings settings = new AppSettings(context);
        final File backupFile = context.getDatabasePath(DATABASE_NAME + ".bak");
        if (backupFile.exists() && !backupFile.delete()) {
            throw new java.io.IOException("Failed to delete existing backup file");
        }

        // Sentinel mirrors the DatabaseBackend migration pattern — protects against process kill
        // between the file rename and the prefs write.
        PreferenceManager.getDefaultSharedPreferences(context)
                .edit().putBoolean("rekey_migration_updb_in_progress", true).commit();

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
            settings.setDatabasePassword(newPassword);
            prefsUpdated = true;
            PreferenceManager.getDefaultSharedPreferences(context)
                    .edit().remove("rekey_migration_updb_in_progress").commit();
            FileHelper.secureDelete(backupFile);
            FileHelper.secureDelete(new File(backupFile.getAbsolutePath() + "-wal"));
            FileHelper.secureDelete(new File(backupFile.getAbsolutePath() + "-shm"));
            FileHelper.secureDelete(new File(dbFile.getAbsolutePath() + "-wal"));
            FileHelper.secureDelete(new File(dbFile.getAbsolutePath() + "-shm"));
        } catch (Exception e) {
            if (!prefsUpdated) {
                PreferenceManager.getDefaultSharedPreferences(context)
                        .edit().remove("rekey_migration_updb_in_progress").apply();
            }
            throw e;
        }
    }

    private UnifiedPushDatabase(@Nullable Context context) {
        super(context, DATABASE_NAME, getPasswordBytes(context), null, DATABASE_VERSION, 0, null, DatabaseBackend.DATABASE_HOOK, true);
    }

    private static byte[] getPasswordBytes(Context context) {
        try {
            final char[] chars = new AppSettings(context).getDatabasePasswordChars();
            if (chars == null) return null;
            final byte[] bytes = DatabaseBackend.charsToUtf8Bytes(chars);
            java.util.Arrays.fill(chars, '\0');
            return bytes;
        } catch (eu.siacs.conversations.EncryptionException e) {
            return null;
        }
    }

    public static UnifiedPushDatabase getInstance(final Context context) {
        synchronized (UnifiedPushDatabase.class) {
            if (instance == null) {
                System.loadLibrary("sqlcipher");
                instance = new UnifiedPushDatabase(context.getApplicationContext());
            }
            return instance;
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
