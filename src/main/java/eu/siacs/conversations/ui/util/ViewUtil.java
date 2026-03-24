package eu.siacs.conversations.ui.util;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;


import java.io.File;
import java.util.List;

import de.monocles.chat.MediaViewerActivity;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.DownloadableFile;
import eu.siacs.conversations.persistance.FileBackend;
import me.drakeet.support.toast.ToastCompat;

public class ViewUtil {

    public static void view(Context context, Attachment attachment) {
        view(context, attachment, null, null, null);
    }

    public static void view(Context context, Attachment attachment, @Nullable String conversationUuid) {
        view(context, attachment, conversationUuid, null, null);
    }

    public static void view(Context context, Attachment attachment, @Nullable String conversationUuid, @Nullable String accountUuid, @Nullable String jidString) {
        File file = new File(attachment.getUri().getPath());
        final String mime = attachment.getMime() == null ? "*/*" : attachment.getMime();
        view(context, file, mime, file.getName(), conversationUuid, attachment.getUuid().toString(), accountUuid, jidString);
    }

    public static void view(Context context, DownloadableFile file, final String displayName, @Nullable String conversationUuid, @Nullable String messageUuid) {
        if (!file.exists()) {
            Toast.makeText(context, R.string.file_deleted, Toast.LENGTH_SHORT).show();
            return;
        }
        String mime = file.getMimeType();
        if (mime == null) {
            mime = "*/*";
        }
        view(context, file, mime, displayName, conversationUuid, messageUuid, null, null);
    }

    public static void view(Context context, File file, String mime, final String displayName, @Nullable String conversationUuid, @Nullable String messageUuid) {
        view(context, file, mime, displayName, conversationUuid, messageUuid, null, null);
    }

    public static void view(Context context, File file, String mime, final String displayName,
                            @Nullable String conversationUuid, @Nullable String messageUuid,
                            @Nullable String accountUuid, @Nullable String jidString) {
        Log.d(Config.LOGTAG, "viewing " + file.getAbsolutePath() + " " + mime);
        final Uri uri;
        try {
            uri = FileBackend.getUriForFile(context, file, displayName);
        } catch (SecurityException e) {
            Log.d(Config.LOGTAG, "No permission to access " + file.getAbsolutePath(), e);
            ToastCompat.makeText(context, context.getString(R.string.no_permission_to_access_x, file.getAbsolutePath()), ToastCompat.LENGTH_SHORT).show();
            return;
        }
        // use internal viewer for images and videos
        if ((mime.startsWith("image/") || mime.startsWith("video/")) &&
                PreferenceManager.getDefaultSharedPreferences(context).getBoolean("internal_meda_viewer", context.getResources().getBoolean(R.bool.internal_meda_viewer))) {

            final Intent intent = new Intent(context, MediaViewerActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.putExtra(mime.startsWith("image/") ? "image" : "video", Uri.fromFile(file));

            if (conversationUuid != null) intent.putExtra("conversation_uuid", conversationUuid);
            if (messageUuid != null) intent.putExtra("message_uuid", messageUuid);
            if (accountUuid != null) intent.putExtra("account", accountUuid);
            if (jidString != null) intent.putExtra("jid", jidString);

            context.startActivity(intent);
        } else {
            final Intent openIntent = new Intent(Intent.ACTION_VIEW);
            openIntent.setDataAndType(uri, mime);
            openIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try {
                context.startActivity(openIntent);
            } catch (final ActivityNotFoundException e) {
                Toast.makeText(context, R.string.no_application_found_to_open_file, Toast.LENGTH_SHORT).show();
            }
        }
    }
}
