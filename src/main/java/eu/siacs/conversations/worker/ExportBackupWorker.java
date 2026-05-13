package eu.siacs.conversations.worker;

import static eu.siacs.conversations.utils.Compatibility.s;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.database.Cursor;
import net.zetetic.database.sqlcipher.SQLiteDatabase;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.util.Base64;
import android.util.Log;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.documentfile.provider.DocumentFile;
import androidx.work.ForegroundInfo;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import com.google.common.base.Optional;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.gson.stream.JsonWriter;
import de.monocles.chat.pinnedmessage.PinnedMessage;
import eu.siacs.conversations.AppSettings;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.Conversations;
import eu.siacs.conversations.R;
import eu.siacs.conversations.crypto.axolotl.SQLiteAxolotlStore;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Contact;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.persistance.DatabaseBackend;
import eu.siacs.conversations.persistance.FileBackend;
import eu.siacs.conversations.services.QuickConversationsService;
import eu.siacs.conversations.utils.BackupFileHeader;
import eu.siacs.conversations.utils.Compatibility;
import eu.siacs.conversations.xmpp.Jid;
import java.io.BufferedWriter;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.GZIPOutputStream;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;
import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.modes.AEADBlockCipher;
import org.bouncycastle.crypto.modes.GCMBlockCipher;
import org.bouncycastle.crypto.params.AEADParameters;
import org.bouncycastle.crypto.params.KeyParameter;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public class ExportBackupWorker extends Worker {

    private static final SimpleDateFormat DATE_FORMAT =
            new SimpleDateFormat("yyyy-MM-dd-HH-mm", Locale.US);

    private static final String KEY_TYPE = "AES";
    private static final String CIPHER_MODE = "AES/GCM/NoPadding";
    private static final String PROVIDER = "BC";

    public static final String MIME_TYPE = "application/vnd.conversations.backup";

    private static final String MESSAGE_STRING_FORMAT = "(%s) %s: %s\n";

    private static final int NOTIFICATION_ID = 19;
    private static final int BACKUP_CREATED_NOTIFICATION_ID = 23;

    private static final int PENDING_INTENT_FLAGS =
            s()
                    ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                    : PendingIntent.FLAG_UPDATE_CURRENT;

    private final boolean recurringBackup;

    private long lastNotificationUpdate = 0;
    boolean ReadableLogsEnabled = false;
    private DatabaseBackend mDatabaseBackend;
    private List<Account> mAccounts;


    public ExportBackupWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
        final var inputData = workerParams.getInputData();
        this.recurringBackup = inputData.getBoolean("recurring_backup", false);
    }

    @NonNull
    @Override
    public Result doWork() {
        setForegroundAsync(getForegroundInfo());
        final List<Uri> files;
        try {
            files = export();
        } catch (final Exception e) {
            Log.d(Config.LOGTAG, "could not create backup", e);
            showToast(R.string.could_not_create_backup);
            return Result.failure();
        } finally {
            getApplicationContext()
                    .getSystemService(NotificationManager.class)
                    .cancel(NOTIFICATION_ID);
        }
        Log.d(Config.LOGTAG, "done creating " + files.size() + " backup files");
        if (files.isEmpty() || recurringBackup) {
            return Result.success();
        }
        notifySuccess(files);
        return Result.success();
    }

    @NonNull
    @Override
    public ForegroundInfo getForegroundInfo() {
        Log.d(Config.LOGTAG, "getForegroundInfo()");
        final NotificationCompat.Builder notification = getNotification();
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            return new ForegroundInfo(
                    NOTIFICATION_ID,
                    notification.build(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            return new ForegroundInfo(NOTIFICATION_ID, notification.build());
        }
    }

    private List<Uri> export()
            throws Exception {
        final Context context = getApplicationContext();
        final var appSettings = new AppSettings(context);
        final var backupLocation = appSettings.getBackupLocation();
        final var database = DatabaseBackend.getInstance(context);
        final var accounts = database.getAccounts();

        int currentCount = 0;
        final int max = accounts.size();
        final ImmutableList.Builder<Uri> locations = new ImmutableList.Builder<>();
        Log.d(Config.LOGTAG, "starting backup for " + max + " accounts");
        for (final Account account : accounts) {
            if (isStopped()) {
                Log.d(Config.LOGTAG, "ExportBackupWorker has stopped. Returning what we have");
                return locations.build();
            }
            final String password = account.getPassword();
            if (Strings.nullToEmpty(password).trim().isEmpty()) {
                Log.d(
                        Config.LOGTAG,
                        String.format(
                                "skipping backup for %s because password is empty. unable to"
                                        + " encrypt",
                                account.getJid().asBareJid()));
                currentCount++;
                continue;
            }
            final Uri uri;
            try {
                uri = export(database, account, password, backupLocation, max, currentCount);
            } catch (final WorkStoppedException e) {
                Log.d(Config.LOGTAG, "ExportBackupWorker has stopped. Returning what we have");
                return locations.build();
            }
            locations.add(uri);
            currentCount++;
        }
        return locations.build();
    }

    private Uri export(
            final DatabaseBackend database,
            final Account account,
            final String password,
            final Uri backupLocation,
            final int max,
            final int count)
            throws Exception {
        final var context = getApplicationContext();
        final SecureRandom secureRandom = new SecureRandom();
        Log.d(
                Config.LOGTAG,
                String.format(
                        "exporting data for account %s (%s)",
                        account.getJid().asBareJid(), account.getUuid()));
        final byte[] IV = new byte[12];
        final byte[] salt = new byte[16];
        secureRandom.nextBytes(IV);
        secureRandom.nextBytes(salt);
        final BackupFileHeader backupFileHeader =
                new BackupFileHeader(
                        context.getString(R.string.app_name),
                        account.getJid(),
                        System.currentTimeMillis(),
                        IV,
                        salt);
        final var notification = getNotification();
        final var cancelPendingIntent =
                WorkManager.getInstance(context).createCancelPendingIntent(getId());
        notification.addAction(
                new NotificationCompat.Action.Builder(
                                R.drawable.ic_cancel_24dp,
                                context.getString(R.string.cancel),
                                cancelPendingIntent)
                        .build());
        final Progress progress = new Progress(notification, max, count);
        final String filename =
                String.format(
                        "%s.%s.ceb",
                        account.getJid().asBareJid().toString(), DATE_FORMAT.format(new Date()));
        final Uri location;
        if ("file".equalsIgnoreCase(backupLocation.getScheme())) {
            final File file = new File(backupLocation.getPath(), filename);
            final File directory = file.getParentFile();
            if (directory != null && directory.mkdirs()) {
                Log.d(Config.LOGTAG, "created backup directory " + directory.getAbsolutePath());
            }
            location = Uri.fromFile(file);
        } else {
            final var tree = DocumentFile.fromTreeUri(context, backupLocation);
            if (tree == null) {
                throw new IOException(
                        String.format(
                                "DocumentFile.fromTreeUri returned null for %s", backupLocation));
            }
            final var file = tree.createFile(MIME_TYPE, filename);
            if (file == null) {
                throw new IOException(
                        String.format("Could not create %s in %s", filename, backupLocation));
            }
            location = file.getUri();
        }

        try (final OutputStream outputStream = context.getContentResolver().openOutputStream(location)) {
            final DataOutputStream dataOutputStream = new DataOutputStream(outputStream);
            backupFileHeader.write(dataOutputStream);
            dataOutputStream.flush();

            final AEADBlockCipher aeadBlockCipher = GCMBlockCipher.newInstance(AESEngine.newInstance());
            final byte[] key = getKey(password, salt);
            aeadBlockCipher.init(true, new AEADParameters(new KeyParameter(key), 128, IV));
            final org.bouncycastle.crypto.io.CipherOutputStream cipherOutputStream = new org.bouncycastle.crypto.io.CipherOutputStream(outputStream, aeadBlockCipher);
            final GZIPOutputStream gzipOutputStream = new GZIPOutputStream(cipherOutputStream);
            try (final JsonWriter jsonWriter = new JsonWriter(new OutputStreamWriter(gzipOutputStream, StandardCharsets.UTF_8))) {
                jsonWriter.beginArray();
                final SQLiteDatabase db = database.getReadableDatabase();
                final String uuid = account.getUuid();
                accountExport(db, uuid, jsonWriter);
                simpleExport(db, Conversation.TABLENAME, Conversation.ACCOUNT, uuid, jsonWriter);
                fileExport(db, uuid, jsonWriter, progress);
                messageExport(db, uuid, jsonWriter, progress);
                webxdcExport(db, uuid, jsonWriter, progress);
                simpleExport(db, PinnedMessage.TABLENAME, PinnedMessage.ACCOUNT_UUID, uuid, jsonWriter);
                simpleExport(db, "muted_participants", null, null, jsonWriter);
                for (final String table :
                        Arrays.asList(
                                SQLiteAxolotlStore.PREKEY_TABLENAME,
                                SQLiteAxolotlStore.SIGNED_PREKEY_TABLENAME,
                                SQLiteAxolotlStore.SESSION_TABLENAME,
                                SQLiteAxolotlStore.IDENTITIES_TABLENAME)) {
                    simpleExport(db, table, SQLiteAxolotlStore.ACCOUNT, uuid, jsonWriter);
                }
                jsonWriter.endArray();
                jsonWriter.flush();
            }
        } catch (Exception e) {
            deleteFile(context, location);
            throw e;
        }
        if ("file".equalsIgnoreCase(location.getScheme())) {
            mediaScannerScanFile(new File(location.getPath()));
        }
        Log.d(Config.LOGTAG, "written backup to " + location);

        if (getFileSize(context, location) > 80) {
            cleanup(backupLocation, account.getJid());
        } else {
            Log.w(Config.LOGTAG, "Backup file " + location + " is too small. Skipping cleanup.");
            showToast(R.string.could_not_create_backup);
        }

        mDatabaseBackend = DatabaseBackend.getInstance(Conversations.getContext());
        mAccounts = mDatabaseBackend.getAccounts();
        final SharedPreferences ReadableLogs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        ReadableLogsEnabled = ReadableLogs.getBoolean("export_plain_text_logs", getApplicationContext().getResources().getBoolean(R.bool.plain_text_logs));

        try {
            if (ReadableLogsEnabled) {  // todo
                List<Conversation> conversations = mDatabaseBackend.getConversations(Conversation.STATUS_AVAILABLE);
                conversations.addAll(mDatabaseBackend.getConversations(Conversation.STATUS_ARCHIVED));
                for (Conversation conversation : conversations) {
                    writeToFile(conversation);
                    Log.d(Config.LOGTAG, "Exporting readable logs for " + conversation.getJid());
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return location;
    }

    private void showToast(final int resId) {
        new Handler(Looper.getMainLooper())
                .post(() -> Toast.makeText(getApplicationContext(), resId, Toast.LENGTH_LONG).show());
    }

    private long getFileSize(Context context, Uri uri) {
        final String scheme = uri.getScheme();
        if ("file".equalsIgnoreCase(scheme)) {
            final String path = uri.getPath();
            return path == null ? 0 : new File(path).length();
        } else {
            final DocumentFile file = DocumentFile.fromSingleUri(context, uri);
            return file == null ? 0 : file.length();
        }
    }

    private void cleanup(Uri backupLocation, Jid jid) {
        final Context context = getApplicationContext();
        final String prefix = jid.asBareJid().toString() + ".";
        final String scheme = backupLocation.getScheme();
        if ("file".equalsIgnoreCase(scheme)) {
            final String path = backupLocation.getPath();
            if (path == null) {
                return;
            }
            final File directory = new File(path);
            final File[] files =
                    directory.listFiles(
                            (dir, name) -> name.startsWith(prefix) && name.endsWith(".ceb"));
            if (files != null && files.length > 3) {
                Arrays.sort(files, (f1, f2) -> f1.getName().compareTo(f2.getName()));
                for (int i = 0; i < files.length - 3; i++) {
                    if (files[i].delete()) {
                        Log.d(Config.LOGTAG, "deleted old backup " + files[i].getName());
                    }
                }
            }
        } else {
            final DocumentFile tree = DocumentFile.fromTreeUri(context, backupLocation);
            if (tree == null) {
                return;
            }
            final DocumentFile[] files = tree.listFiles();
            final List<DocumentFile> backups = new ArrayList<>();
            for (DocumentFile file : files) {
                final String name = file.getName();
                if (name != null && name.startsWith(prefix) && name.endsWith(".ceb")) {
                    backups.add(file);
                }
            }
            if (backups.size() > 3) {
                Collections.sort(
                        backups,
                        (f1, f2) -> {
                            String n1 = f1.getName();
                            String n2 = f2.getName();
                            if (n1 == null) return -1;
                            if (n2 == null) return 1;
                            return n1.compareTo(n2);
                        });
                for (int i = 0; i < backups.size() - 3; i++) {
                    final DocumentFile fileToDelete = backups.get(i);
                    if (fileToDelete.delete()) {
                        Log.d(Config.LOGTAG, "deleted old backup " + fileToDelete.getName());
                    }
                }
            }
        }
    }

    private NotificationCompat.Builder getNotification() {
        final var context = getApplicationContext();
        final NotificationCompat.Builder notification =
                new NotificationCompat.Builder(context, "backup");
        notification
                .setContentTitle(context.getString(R.string.notification_create_backup_title))
                .setSmallIcon(R.drawable.ic_archive_24dp)
                .setProgress(1, 0, false);
        notification.setOngoing(true);
        notification.setLocalOnly(true);
        return notification;
    }

    private void throwIfWorkStopped() throws WorkStoppedException {
        if (isStopped()) {
            throw new WorkStoppedException();
        }
    }

    private void deleteFile(Context context, Uri uri) {
        if ("file".equalsIgnoreCase(uri.getScheme())) {
            final var file = new File(uri.getPath());
            if (file.delete()) {
                Log.d(Config.LOGTAG, "deleted " + file.getAbsolutePath());
            }
        } else {
            final var documentFile = DocumentFile.fromSingleUri(context, uri);
            if (documentFile != null && documentFile.delete()) {
                Log.d(Config.LOGTAG, "deleted " + uri);
            }
        }
    }

    private void mediaScannerScanFile(final File file) {
        final Intent intent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
        intent.setData(Uri.fromFile(file));
        getApplicationContext().sendBroadcast(intent);
    }

    private void webxdcExport(final SQLiteDatabase db, final String uuid, final JsonWriter writer, final Progress progress) throws IOException, WorkStoppedException {
        final var notificationManager = getApplicationContext().getSystemService(NotificationManager.class);

        final Cursor cursor = db.rawQuery("select webxdc_updates.* from " + Conversation.TABLENAME + " join webxdc_updates webxdc_updates on " + Conversation.TABLENAME + ".uuid=webxdc_updates." + Message.CONVERSATION + " where conversations.accountUuid=?", new String[]{uuid});
        int size = cursor != null ? cursor.getCount() : 0;
        Log.d(Config.LOGTAG, "exporting " + size + " WebXDC updates for account " + uuid);
        int i = 0;
        int p = Integer.MIN_VALUE;
        while (cursor != null && cursor.moveToNext()) {
            throwIfWorkStopped();
            writer.beginObject();
            writer.name("table");
            writer.value("webxdc_updates");
            writer.name("values");
            writer.beginObject();
            for (int j = 0; j < cursor.getColumnCount(); ++j) {
                final String name = cursor.getColumnName(j);
                writer.name(name);
                final String value = cursor.getString(j);
                writer.value(value);
            }
            writer.endObject();
            writer.endObject();
            final int percentage = i * 100 / (size == 0 ? 1 : size);
            if (p < percentage && (SystemClock.elapsedRealtime() - lastNotificationUpdate) > 2_000) {
                p = percentage;
                lastNotificationUpdate = SystemClock.elapsedRealtime();
                notificationManager.notify(NOTIFICATION_ID, progress.build(p));
            }
            i++;
        }
        if (cursor != null) {
            cursor.close();
        }
    }

    private static void accountExport(
            final SQLiteDatabase db, final String uuid, final JsonWriter writer)
            throws IOException {
        try (final Cursor accountCursor =
                db.query(
                        Account.TABLENAME,
                        null,
                        Account.UUID + "=?",
                        new String[] {uuid},
                        null,
                        null,
                        null)) {
            while (accountCursor != null && accountCursor.moveToNext()) {
                writer.beginObject();
                writer.name("table");
                writer.value(Account.TABLENAME);
                writer.name("values");
                writer.beginObject();
                for (int i = 0; i < accountCursor.getColumnCount(); ++i) {
                    final String name = accountCursor.getColumnName(i);
                    writer.name(name);
                    final String value = accountCursor.getString(i);
                    if (value == null
                            || Account.ROSTERVERSION.equals(accountCursor.getColumnName(i))) {
                        writer.nullValue();
                    } else if (Account.OPTIONS.equals(accountCursor.getColumnName(i))
                            && value.matches("\\d+")) {
                        int intValue = Integer.parseInt(value);
                        intValue |= 1 << Account.OPTION_DISABLED;
                        writer.value(intValue);
                    } else {
                        writer.value(value);
                    }
                }
                writer.endObject();
                writer.endObject();
            }
        }
    }

    private void simpleExport(
            final SQLiteDatabase db,
            final String table,
            final String column,
            final String uuid,
            final JsonWriter writer)
            throws IOException, WorkStoppedException {
        final String selection = column != null ? column + "=?" : null;
        final String[] selectionArgs = uuid != null ? new String[]{uuid} : null;
        try (final Cursor cursor =
                db.query(table, null, selection, selectionArgs, null, null, null)) {
            while (cursor != null && cursor.moveToNext()) {
                throwIfWorkStopped();
                writer.beginObject();
                writer.name("table");
                writer.value(table);
                writer.name("values");
                writer.beginObject();
                for (int i = 0; i < cursor.getColumnCount(); ++i) {
                    final String name = cursor.getColumnName(i);
                    writer.name(name);
                    final String value = cursor.getString(i);
                    writer.value(value);
                }
                writer.endObject();
                writer.endObject();
            }
        }
    }

    private void messageExport(
            final SQLiteDatabase db,
            final String uuid,
            final JsonWriter writer,
            final Progress progress)
            throws IOException, WorkStoppedException {
        final var notificationManager =
                getApplicationContext().getSystemService(NotificationManager.class);
        try (final Cursor cursor =
                db.rawQuery(
                        "select messages.* from messages join conversations on"
                                + " conversations.uuid=messages.conversationUuid where"
                                + " conversations.accountUuid=?",
                        new String[] {uuid})) {
            final int size = cursor != null ? cursor.getCount() : 0;
            Log.d(Config.LOGTAG, "exporting " + size + " messages for account " + uuid);
            int i = 0;
            int p = Integer.MIN_VALUE;
            while (cursor != null && cursor.moveToNext()) {
                throwIfWorkStopped();
                writer.beginObject();
                writer.name("table");
                writer.value(Message.TABLENAME);
                writer.name("values");
                writer.beginObject();
                for (int j = 0; j < cursor.getColumnCount(); ++j) {
                    final String name = cursor.getColumnName(j);
                    writer.name(name);
                    String value = cursor.getString(j);
                    if (Message.RELATIVE_FILE_PATH.equals(name)) {
                        value = toPortablePath(value);
                    }
                    writer.value(value);
                }
                writer.endObject();
                writer.endObject();
                final int percentage = i * 100 / (size == 0 ? 1 : size);
                if (p < percentage && (SystemClock.elapsedRealtime() - lastNotificationUpdate) > 2_000) {
                    p = percentage;
                    lastNotificationUpdate = SystemClock.elapsedRealtime();
                    notificationManager.notify(NOTIFICATION_ID, progress.build(p));
                }
                i++;
            }
        }
    }

    public static byte[] getKey(final String password, final byte[] salt) {
        Argon2Parameters params = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withIterations(3)
                .withMemoryAsKB(65536) // 64MB
                .withParallelism(4)
                .withSalt(salt)
                .build();
        Argon2BytesGenerator gen = new Argon2BytesGenerator();
        gen.init(params);
        byte[] result = new byte[32]; // 256-bit key for AES-256
        gen.generateBytes(password.toCharArray(), result);
        return result;
    }

    public static byte[] getLegacyKey(final String password, final byte[] salt)
            throws InvalidKeySpecException {
        final SecretKeyFactory factory;
        try {
            factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        return factory.generateSecret(new PBEKeySpec(password.toCharArray(), salt, 1024, 128))
                .getEncoded();
    }

    private void notifySuccess(final List<Uri> locations) {
        final var context = getApplicationContext();
        final var appSettings = new AppSettings(context);
        final String path = appSettings.getBackupLocationAsPath();
        final Intent intent = new Intent(Intent.ACTION_SEND_MULTIPLE);
        final ArrayList<Uri> uris = new ArrayList<>();
        for (final Uri uri : locations) {
            if ("file".equalsIgnoreCase(uri.getScheme())) {
                uris.add(FileBackend.getUriForFile(context, new File(uri.getPath()), new File(uri.getPath()).getName()));
            } else {
                uris.add(uri);
            }
        }
        intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setType(MIME_TYPE);
        final Intent chooser =
                Intent.createChooser(intent, context.getString(R.string.share_backup_files));
        final var shareFilesIntent =
                PendingIntent.getActivity(context, 190, chooser, PENDING_INTENT_FLAGS);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, "backup");
        builder.setContentTitle(context.getString(R.string.notification_backup_created_title))
                .setContentText(
                        context.getString(R.string.notification_backup_created_subtitle, path))
                .setStyle(
                        new NotificationCompat.BigTextStyle()
                                .bigText(
                                        context.getString(
                                                R.string.notification_backup_created_subtitle,
                                                path)))
                .setAutoCancel(true)
                .setSmallIcon(R.drawable.ic_archive_24dp);

        builder.addAction(
                R.drawable.ic_share_24dp,
                context.getString(R.string.share_backup_files),
                shareFilesIntent);
        builder.setLocalOnly(true);
        final var notificationManager = context.getSystemService(NotificationManager.class);
        notificationManager.notify(BACKUP_CREATED_NOTIFICATION_ID, builder.build());
    }

    private static class Progress {
        private final NotificationCompat.Builder notification;
        private final int max;
        private final int count;

        private Progress(
                final NotificationCompat.Builder notification, final int max, final int count) {
            this.notification = notification;
            this.max = max;
            this.count = count;
        }

        private Notification build(int percentage) {
            notification.setProgress(max * 100, count * 100 + percentage, false);
            return notification.build();
        }
    }

    private static class WorkStoppedException extends Exception {}

    private void writeToFile(Conversation conversation) {
        Jid accountJid = resolveAccountUuid(conversation.getAccountUuid());
        if (accountJid == null) return;
        Jid contactJid = conversation.getJid();
        final var context = getApplicationContext();
        final var appSettings = new AppSettings(context);
        final String path = appSettings.getBackupLocationAsPath();
        final File dir = new File(path, accountJid.asBareJid().toString());
        dir.mkdirs();

        BufferedWriter bw = null;
        try {
            for (Message message : mDatabaseBackend.getMessagesIterable(conversation)) {
                if (isStopped()) return;
                if (message == null)
                    continue;
                if (message.getType() == Message.TYPE_TEXT || message.hasFileOnRemoteHost()) {
                    String date = DATE_FORMAT.format(new Date(message.getTimeSent()));
                    if (bw == null) {
                        bw = new BufferedWriter(new FileWriter(
                                new File(dir, contactJid.asBareJid().toString() + ".txt")));
                    }
                    String jid = null;
                    switch (message.getStatus()) {
                        case Message.STATUS_RECEIVED:
                            jid = getMessageCounterpart(message);
                            break;
                        case Message.STATUS_SEND:
                        case Message.STATUS_SEND_RECEIVED:
                        case Message.STATUS_SEND_DISPLAYED:
                        case Message.STATUS_SEND_FAILED:
                            jid = accountJid.asBareJid().toString();
                            break;
                    }
                    if (jid != null) {
                        String body = message.hasFileOnRemoteHost() ? message.getFileParams().url.toString() : message.getBody();
                        bw.write(String.format(MESSAGE_STRING_FORMAT, date, jid, body.replace("\\\n", "\\ \n").replace("\n", "\\ \n")));
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                if (bw != null) {
                    bw.close();
                }
            } catch (IOException e1) {
                e1.printStackTrace();
            }
        }
    }

    private Jid resolveAccountUuid(String accountUuid) {
        for (Account account : mAccounts) {
            if (account.getUuid().equals(accountUuid)) {
                return account.getJid();
            }
        }
        return null;
    }

    private String getMessageCounterpart(Message message) {
        String trueCounterpart = (String) message.getContentValues().get(Message.TRUE_COUNTERPART);
        if (trueCounterpart != null) {
            return trueCounterpart;
        } else {
            return message.getCounterpart().toString();
        }
    }

    private String toPortablePath(String path) {
        if (path == null) return null;
        final String cacheDir = getApplicationContext().getCacheDir().getAbsolutePath();
        final String filesDir = getApplicationContext().getFilesDir().getAbsolutePath();
        final String externalDir = Environment.getExternalStorageDirectory().getAbsolutePath();
        final String documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS).getAbsolutePath();

        if (path.startsWith(cacheDir)) {
            return "${CACHE}" + path.substring(cacheDir.length());
        } else if (path.startsWith(filesDir)) {
            return "${FILES}" + path.substring(filesDir.length());
        } else if (path.startsWith(externalDir)) {
            return "${EXTERNAL}" + path.substring(externalDir.length());
        } else if (path.startsWith(documentsDir)) {
            return "${DOCUMENTS}" + path.substring(documentsDir.length());
        }
        return path;
    }

    private Set<File> getFilesForAccount(SQLiteDatabase db, String accountUuid) {
        Set<File> files = new HashSet<>();
        final String filesDir = getApplicationContext().getFilesDir().getAbsolutePath();
        // Message attachments
        try (Cursor cursor = db.rawQuery("select " + Message.RELATIVE_FILE_PATH + " from " + Message.TABLENAME +
                " join " + Conversation.TABLENAME + " on " + Conversation.TABLENAME + ".uuid=" + Message.TABLENAME + "." + Message.CONVERSATION +
                " where " + Conversation.TABLENAME + "." + Conversation.ACCOUNT + "=? and " + Message.RELATIVE_FILE_PATH + " is not null",
                new String[]{accountUuid})) {
            while (cursor != null && cursor.moveToNext()) {
                String path = cursor.getString(0);
                if (path != null && path.startsWith(filesDir)) {
                    files.add(new File(path));
                }
            }
        }

        final File avatarDir = new File(getApplicationContext().getFilesDir(), "avatars");
        // Avatars
        // Account avatar
        try (Cursor cursor = db.query(Account.TABLENAME, new String[]{Account.AVATAR}, Account.AVATAR + " is not null and uuid=?", new String[]{accountUuid}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                files.add(new File(avatarDir, cursor.getString(0)));
            }
        }
        // Contact avatars
        try (Cursor cursor = db.query(Contact.TABLENAME, new String[]{Contact.AVATAR}, Contact.AVATAR + " is not null and " + Contact.ACCOUNT + "=?", new String[]{accountUuid}, null, null, null)) {
            while (cursor != null && cursor.moveToNext()) {
                files.add(new File(avatarDir, cursor.getString(0)));
            }
        }

        return files;
    }

    private void fileExport(SQLiteDatabase db, String uuid, JsonWriter writer, Progress progress) throws WorkStoppedException {
        final var notificationManager = getApplicationContext().getSystemService(NotificationManager.class);
        Set<File> files = getFilesForAccount(db, uuid);
        Log.d(Config.LOGTAG, "exporting " + files.size() + " files for account " + uuid);
        int i = 0;
        int p = Integer.MIN_VALUE;
        for (File file : files) {
            throwIfWorkStopped();
            if (file.exists() && file.canRead()) {
                try {
                    exportFile(file, writer);
                } catch (IOException e) {
                    Log.w(Config.LOGTAG, "failed to export file " + file.getAbsolutePath(), e);
                }
            }
            i++;
            final int percentage = i * 100 / Math.max(1, files.size());
            if (p < percentage && (SystemClock.elapsedRealtime() - lastNotificationUpdate) > 2_000) {
                p = percentage;
                lastNotificationUpdate = SystemClock.elapsedRealtime();
                notificationManager.notify(NOTIFICATION_ID, progress.build(p));
            }
        }
    }

    private void exportFile(File file, JsonWriter writer) throws IOException {
        final String portablePath = toPortablePath(file.getAbsolutePath());
        final byte[] buffer = new byte[1024 * 1024]; // 1MB chunks
        try (InputStream is = new FileInputStream(file)) {
            int length;
            int sequence = 0;
            while ((length = is.read(buffer)) > 0) {
                writer.beginObject();
                writer.name("table");
                writer.value("files");
                writer.name("values");
                writer.beginObject();
                writer.name("path");
                writer.value(portablePath);
                writer.name("sequence");
                writer.value(sequence++);
                writer.name("content");
                if (length == buffer.length) {
                    writer.value(Base64.encodeToString(buffer, Base64.NO_WRAP));
                } else {
                    byte[] smallBuffer = new byte[length];
                    System.arraycopy(buffer, 0, smallBuffer, 0, length);
                    writer.value(Base64.encodeToString(smallBuffer, Base64.NO_WRAP));
                }
                writer.endObject();
                writer.endObject();
            }
        }
    }
}
