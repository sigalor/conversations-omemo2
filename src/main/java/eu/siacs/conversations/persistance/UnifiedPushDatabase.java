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

    public static synchronized void migrate(Context context, String oldPassword, String newPassword) throws Exception {
        closeInstance();
        System.loadLibrary("sqlcipher");
        File dbFile = context.getDatabasePath(DATABASE_NAME);
        if (!dbFile.exists()) return;

        File tempFile = context.getDatabasePath(DATABASE_NAME + ".tmp");
        if (tempFile.exists() && !tempFile.delete()) {
            throw new java.io.IOException("Failed to delete existing temporary database file");
        }
        if (!tempFile.createNewFile()) {
            throw new java.io.IOException("Failed to create temporary database file");
        }

        SQLiteDatabase db = SQLiteDatabase.openDatabase(dbFile.getAbsolutePath(), oldPassword == null ? "" : oldPassword, null, SQLiteDatabase.OPEN_READWRITE, null);
        int version = db.getVersion();
        try {
            String attachSql = "ATTACH DATABASE " + DatabaseUtils.sqlEscapeString(tempFile.getAbsolutePath()) + " AS encrypted KEY " + DatabaseUtils.sqlEscapeString(newPassword == null ? "" : newPassword);
            db.rawExecSQL(attachSql);
            db.rawExecSQL("SELECT sqlcipher_export('encrypted');");
            db.rawExecSQL("PRAGMA encrypted.user_version = " + version);
            db.rawExecSQL("DETACH DATABASE encrypted;");
        } finally {
            db.close();
        }

        final AppSettings settings = new AppSettings(context);
        final String savedPassword = settings.getDatabasePassword();
        try {
            // Update password in prefs FIRST
            settings.setDatabasePassword(newPassword);

            // Now perform file operations
            if (FileHelper.secureDelete(dbFile)) {
                FileHelper.secureDelete(new File(dbFile.getAbsolutePath() + "-wal"));
                FileHelper.secureDelete(new File(dbFile.getAbsolutePath() + "-shm"));
                if (!tempFile.renameTo(dbFile)) {
                    throw new java.io.IOException("Failed to rename temporary database file");
                }
            } else {
                throw new java.io.IOException("Failed to delete old database file");
            }
        } catch (Exception e) {
            // Rollback password in prefs if file operations fail
            try {
                settings.setDatabasePassword(savedPassword);
            } catch (Exception rollbackError) {
                Log.e("UnifiedPushDatabase", "Failed to rollback password after migration error", rollbackError);
            }
            throw e;
        }
    }

    private UnifiedPushDatabase(@Nullable Context context) {
        super(context, DATABASE_NAME, new AppSettings(context).getDatabasePassword(), null, DATABASE_VERSION, 0, null, null, true);
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
