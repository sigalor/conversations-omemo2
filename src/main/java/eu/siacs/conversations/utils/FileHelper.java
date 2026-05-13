package eu.siacs.conversations.utils;

import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.SecureRandom;

public class FileHelper {
    private static final String TAG = "FileHelper";

    /**
     * Attempts to securely delete a file by overwriting it with random data before unlinking.
     * This is a "best effort" approach as modern flash storage (SSD/EMMC) may wear-level
     * the writes to different physical blocks.
     *
     * @param file The file to securely delete.
     * @return true if the file was deleted successfully.
     */
    public static boolean secureDelete(File file) {
        if (file == null || !file.exists()) {
            return true;
        }

        if (file.length() > 0) {
            try (FileOutputStream fos = new FileOutputStream(file)) {
                long length = file.length();
                byte[] buffer = new byte[4096];
                SecureRandom random = new SecureRandom();
                long written = 0;
                while (written < length) {
                    random.nextBytes(buffer);
                    int toWrite = (int) Math.min(buffer.length, length - written);
                    fos.write(buffer, 0, toWrite);
                    written += toWrite;
                }
                fos.flush();
                fos.getFD().sync();
            } catch (IOException e) {
                Log.e(TAG, "Failed to overwrite file before deletion: " + file.getAbsolutePath(), e);
            }
        }

        return file.delete();
    }
}
