package de.monocles.chat;

// Stickers used to lie in `/storage/emulated/0/Documents/monocles chat/Stickers' which would get
// indexed by the system MediaScanner. Unfortunately, the conventional way of creating `.nomedia'
// won't solve the problem without requiring permission to gain access to all files in storage.
//
// To avoid that nasty permission, monocles chat would migrate them to the context directory which
// won't get indexed. The trick is that, it requires the old Stickers folder to never exist.
//
// Stickers later moved once more, from the external app directory (Android/data/..., readable by
// other apps with broad storage access) to the internal hidden storage (getFilesDir), matching
// where media, avatars and stories already live.

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import com.google.common.io.ByteStreams;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class StickersMigration {

    private static final String TAG = "StickerMigration";
    private static final File oldStickersDir =
            new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOCUMENTS) + "/monocles chat" + File.separator + "Stickers");
    public static File stickersDir;

    private static void copyDirectory(File source, File target) {
        if (source.isDirectory()) {
            if (!target.exists()) target.mkdirs();
            File[] children = source.listFiles();
            if (children != null) {
                for (File f : children) {
                    File newTarget = new File(target, f.getName());
                    copyDirectory(f, newTarget);
                }
            }
        } else {
            copyFile(source, target);
        }
    }

    private static void copyFile(File source, File target) {
        try (InputStream is = new FileInputStream(source);
             OutputStream os = new FileOutputStream(target)) {
            ByteStreams.copy(is, os);
        } catch (IOException e) {
            Log.e(TAG, "failed to migrate sticker file: " + source.toString() + ", " + e);
        }
    }

    public static void migrate(Context context) {
        if (isMigrated()) {
            Log.d(TAG, "Stickers already migrated, ignore migration request");
            return;
        }

        stickersDir = getStickersDir(context);

        copyDirectory(oldStickersDir, stickersDir);

        if (!deleteDirectory(oldStickersDir)) {
            File oldNoMedia = new File(oldStickersDir, ".nomedia");
            if (!oldNoMedia.exists()) {
                try {
                    oldNoMedia.createNewFile();
                } catch (IOException e) {
                    Log.e(TAG,"failed to finalize migration: " + e);
                }
            }
        }
    }

    private static boolean deleteDirectory(File dir) {
        if (dir.isDirectory()) {
            File[] children = dir.listFiles();
            if (children != null) {
                for (File f : children) {
                    deleteDirectory(f);
                }
            }
        }
        return dir.delete();
    }

    public static boolean isMigrated() {
        if (!oldStickersDir.exists() || !oldStickersDir.isDirectory()) {
            return true;
        }

        File oldNoMedia = new File(oldStickersDir, ".nomedia");
        return oldNoMedia.exists();
    }

    public static File getStickersDir(Context context) {
        stickersDir = new File(context.getFilesDir(), "Stickers");
        if (!stickersDir.exists()) {
            stickersDir.mkdirs();
        }
        return stickersDir;
    }

    private static void migrateExternalToInternal(Context context) {
        final File oldExternalDir = context.getExternalFilesDir("Stickers");
        if (oldExternalDir == null || !oldExternalDir.isDirectory()) {
            return;
        }
        final File[] children = oldExternalDir.listFiles();
        if (children == null || children.length == 0) {
            oldExternalDir.delete();
            return;
        }
        Log.d(TAG, "migrating stickers from external app storage to internal storage");
        // renameTo fails across storage volumes, so copy and delete
        copyDirectory(oldExternalDir, getStickersDir(context));
        deleteDirectory(oldExternalDir);
    }

    public static synchronized void requireMigration(Context context) {
        if (!isMigrated()) {
            Log.d(TAG,"Stickers migration started");
            migrate(context);
            Log.d(TAG,"Stickers migration finished");
        }
        migrateExternalToInternal(context);
    }
}
