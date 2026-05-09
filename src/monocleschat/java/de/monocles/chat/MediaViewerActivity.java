package de.monocles.chat;

import android.app.PendingIntent;
import android.app.PictureInPictureParams;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.BitmapFactory;
import android.media.AudioManager;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.util.Log;
import android.util.Rational;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.MimeTypeMap;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.databinding.DataBindingUtil;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.session.MediaSession;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.bumptech.glide.Glide;
import com.github.chrisbanes.photoview.PhotoView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.leinardi.android.speeddial.SpeedDialActionItem;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.databinding.ActivityMediaViewerBinding;
import eu.siacs.conversations.databinding.ItemMediaViewerBinding;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.persistance.FileBackend;
import eu.siacs.conversations.ui.MediaBrowserActivity;
import eu.siacs.conversations.ui.UiCallback;
import eu.siacs.conversations.ui.XmppActivity;
import eu.siacs.conversations.ui.interfaces.OnMediaLoaded;
import eu.siacs.conversations.ui.util.Attachment;
import eu.siacs.conversations.ui.util.Rationals;
import eu.siacs.conversations.utils.Compatibility;
import eu.siacs.conversations.utils.MimeUtils;
import eu.siacs.conversations.xmpp.Jid;
import me.drakeet.support.toast.ToastCompat;

public class MediaViewerActivity extends XmppActivity implements OnMediaLoaded, AudioManager.OnAudioFocusChangeListener {

    Integer oldOrientation;
    ExoPlayer player;
    File mFile;
    int height = 0;
    int width = 0;
    Rational aspect;
    int rotation = 0;
    boolean isImage = false;
    boolean isVideo = false;
    boolean isAudio = false;
    private ActivityMediaViewerBinding binding;
    private GestureDetector gestureDetector;
    MediaSession mediaSession;

    private MediaPagerAdapter pagerAdapter;
    private final List<Attachment> attachments = new ArrayList<>();
    private String initialMessageUuid;

    public static String getMimeType(String path) {
        try {
            String type = null;
            String extension = path.substring(path.lastIndexOf(".") + 1, path.length());
            if (extension != null) {
                type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
            }
            return type;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        this.binding = DataBindingUtil.setContentView(this, R.layout.activity_media_viewer);

        binding.viewPager.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        player = new ExoPlayer.Builder(this).build();
        player.setRepeatMode(Player.REPEAT_MODE_OFF);
        player.addListener(new Player.Listener() {
            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                if (isPlaying) {
                    hideFAB();
                } else {
                    if (Compatibility.runsTwentyFour() && isInPictureInPictureMode()) {
                        hideFAB();
                    } else {
                        showFAB();
                    }
                }
            }

            @Override
            public void onPlayerError(@NonNull PlaybackException error) {
                Log.e(Config.LOGTAG, "PlayerError: ", error);
            }
        });

        pagerAdapter = new MediaPagerAdapter();
        binding.viewPager.setAdapter(pagerAdapter);
        binding.viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                super.onPageSelected(position);
                if (!attachments.isEmpty() && position < attachments.size()) {
                    onMediaItemSelected(attachments.get(position));
                }
            }
        });

        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                if (isImage) {
                    if (binding.speedDial.isShown()) {
                        hideFAB();
                    } else {
                        showFAB();
                    }
                }
                return super.onDown(e);
            }
        });

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null && actionBar.isShowing()) {
            actionBar.hide();
        }

        oldOrientation = getRequestedOrientation();

        WindowManager.LayoutParams layout = getWindow().getAttributes();
        if (useMaxBrightness()) {
            layout.screenBrightness = 1;
        }
        getWindow().setAttributes(layout);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
    }

    private void share() {
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType(getMimeType(mFile.toString()));
        share.putExtra(Intent.EXTRA_STREAM, FileBackend.getUriForFile(this, mFile, mFile.getName()));
        try {
            startActivity(Intent.createChooser(share, getText(R.string.share_with)));
        } catch (ActivityNotFoundException e) {
            ToastCompat.makeText(this, R.string.no_application_found_to_open_file, ToastCompat.LENGTH_SHORT).show();
        }
    }

    private void deleteFile() {
        final AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setNegativeButton(R.string.cancel, null);
        builder.setTitle(R.string.delete_file_dialog);
        builder.setMessage(R.string.delete_file_dialog_msg);
        builder.setPositiveButton(R.string.confirm, (dialog, which) -> {
            if (this.xmppConnectionService.getFileBackend().deleteFile(mFile)) {
                finish();
            }
        });
        builder.create().show();
    }

    private void open() {
        Uri uri;
        try {
            uri = FileBackend.getUriForFile(this, mFile, mFile.getName());
        } catch (SecurityException e) {
            Log.d(Config.LOGTAG, "No permission to access " + mFile.getAbsolutePath(), e);
            ToastCompat.makeText(this, this.getString(R.string.no_permission_to_access_x, mFile.getAbsolutePath()), ToastCompat.LENGTH_SHORT).show();
            return;
        }
        String mime = MimeUtils.guessMimeTypeFromUri(this, uri);
        Intent openIntent = new Intent(Intent.ACTION_VIEW);
        openIntent.setDataAndType(uri, mime);
        openIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (player != null && (isVideo || isAudio)) {
            openIntent.putExtra("position", player.getCurrentPosition());
        }
        try {
            this.startActivity(openIntent);
        } catch (ActivityNotFoundException e) {
            ToastCompat.makeText(this, R.string.no_application_found_to_open_file, ToastCompat.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void refreshUiReal() {
    }

    @Override
    public void onStart() {
        super.onStart();
        updateSpeedDialActions();
    }

    private void saveToDownloads(File file) {
        this.xmppConnectionService.copyAttachmentToDownloadsFolder(file, new UiCallback<>() {
            @Override
            public void success(Integer object) {
                runOnUiThread(() -> Toast.makeText(MediaViewerActivity.this, getString(R.string.save_to_downloads_success), Toast.LENGTH_LONG).show());
            }

            @Override
            public void error(int errorCode, Integer object) {
                runOnUiThread(() -> Toast.makeText(MediaViewerActivity.this, getString(object), Toast.LENGTH_LONG).show());
            }

            @Override
            public void userInputRequired(PendingIntent pi, Integer object) {
            }
        });
    }

    @OptIn(markerClass = UnstableApi.class)
    @RequiresApi(api = Build.VERSION_CODES.N)
    private void PIPVideo() {
        try {
            binding.speedDial.setVisibility(View.GONE);
            if (supportsPIP()) {
                if (Compatibility.runsTwentySix()) {
                    final Rational rational = new Rational(width, height);
                    if (rational.getDenominator() == 0) {
                        Log.w(Config.LOGTAG, "Invalid aspect ratio for PIP: width=" + width + ", height=" + height);
                        return;
                    }
                    final Rational clippedRational = Rationals.clip(rational);
                    final PictureInPictureParams params = new PictureInPictureParams.Builder()
                            .setAspectRatio(clippedRational)
                            .build();
                    this.enterPictureInPictureMode(params);
                } else {
                    this.enterPictureInPictureMode();
                }
            }
        } catch (final IllegalStateException e) {
            Log.w(Config.LOGTAG, "unable to enter picture in picture mode", e);
        } catch (final IllegalArgumentException e) {
            Log.w(Config.LOGTAG, "Illegal argument for picture in picture mode (aspect ratio?): " + e.getMessage());
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode, Configuration newConfig) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);
        if (isInPictureInPictureMode) {
            player.play();
            hideFAB();
        } else {
            showFAB();
        }
    }

    private void abandonAudioFocus() {
        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (audioManager != null) {
            audioManager.abandonAudioFocus(this);
            hasAudioFocus = false;
        }
    }

    private void requestAudioFocus() {
        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (audioManager != null) {
            int result = audioManager.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
            hasAudioFocus = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
        }
    }

    @Override
    public void onBackPressed() {
        if (isVideo && isPlaying() && supportsPIP()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                PIPVideo();
            }
        } else {
            super.onBackPressed();
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (isVideo) {
            PIPVideo();
        }
    }

    private int getRotation(Uri image) {
        try (final InputStream is = this.getContentResolver().openInputStream(image)) {
            return is == null ? 0 : FileBackend.getRotation(is);
        } catch (final Exception e) {
            return 0;
        }
    }

    private void rotateScreen(final int width, final int height, final int rotation) {
        if (width > height) {
            if (rotation == 0 || rotation == 180) {
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
            } else {
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
            }
        } else {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
        }
    }

    private boolean isPlaying() {
        return player != null
                && player.getPlaybackState() != Player.STATE_ENDED
                && player.getPlaybackState() != Player.STATE_IDLE
                && player.getPlayWhenReady();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (player != null && !isInPictureInPictureMode() && player.getPlaybackState() != Player.STATE_ENDED) {
                if (hasAudioFocus) {
                    player.play();
                }
            }
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (player != null && !isInPictureInPictureMode()) {
                player.pause();
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (player != null) {
            player.release();
            player = null;
        }
        if (mediaSession != null) {
            mediaSession.release();
            mediaSession = null;
        }
        abandonAudioFocus();
    }

    private boolean hasAudioFocus = false;

    @Override
    public void onStop() {
        if (player != null && (isVideo || isAudio)) {
            player.stop();
        }
        WindowManager.LayoutParams layout = getWindow().getAttributes();
        if (useMaxBrightness()) {
            layout.screenBrightness = -1;
        }
        getWindow().setAttributes(layout);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setRequestedOrientation(oldOrientation);
        super.onStop();
    }

    @Override
    protected void onBackendConnected() {
        Intent intent = getIntent();
        String convUuid = intent.getStringExtra("conversation_uuid");
        initialMessageUuid = intent.getStringExtra("message_uuid");

        if (player != null) {
            player.stop();
            player.clearMediaItems();
        }
        attachments.clear();
        pagerAdapter.notifyDataSetChanged();

        if (convUuid != null) {
            Conversation conversation = xmppConnectionService.findConversationByUuid(convUuid);
            if (conversation != null) {
                xmppConnectionService.getAttachments(conversation, 0, this);
                return;
            }
        }

        // Fallback for Media Browser: Try account and jid
        String accountUuid = intent.getStringExtra("account");
        String jidString = intent.getStringExtra("jid");
        if (accountUuid != null && jidString != null) {
            xmppConnectionService.getAttachments(accountUuid, Jid.of(jidString), 0, this);
            return;
        }

        setupSingleMediaFallback(intent);
    }

    private void setupSingleMediaFallback(Intent intent) {
        Uri uri = null;
        String mime = null;
        if (intent.hasExtra("image")) {
            uri = intent.getParcelableExtra("image");
            mime = "image/*";
        } else if (intent.hasExtra("video")) {
            uri = intent.getParcelableExtra("video");
            mime = "video/*";
        } else if (intent.hasExtra("audio")) {
            uri = intent.getParcelableExtra("audio");
            mime = "audio/*";
        }

        if (uri != null) {
            attachments.add(Attachment.of(UUID.randomUUID(), new File(uri.getPath()), mime));
            pagerAdapter.notifyDataSetChanged();
        }
    }

    public boolean useMaxBrightness() {
        return false;
    }

    public boolean useAutoRotateScreen() {
        return false;
    }

    @Override
    public void onAudioFocusChange(int focusChange) {
        switch (focusChange) {
            case AudioManager.AUDIOFOCUS_GAIN:
                hasAudioFocus = true;
                if (player != null && (player.getPlayWhenReady() || player.getPlaybackState() == Player.STATE_READY)) {
                    player.play();
                    player.setVolume(1.0f);
                }
                break;
            case AudioManager.AUDIOFOCUS_LOSS:
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                hasAudioFocus = false;
                if (player != null) {
                    player.pause();
                }
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                hasAudioFocus = true;
                if (player != null) {
                    player.setVolume(0.3f);
                }
                break;
        }
    }

    private boolean isDeletableFile(File file) {
        return (file == null || !file.toString().startsWith("/") || file.canWrite());
    }

    private void showFAB() {
        binding.speedDial.show();
    }

    private void hideFAB() {
        binding.speedDial.hide();
    }

    private boolean supportsPIP() {
        if (Compatibility.runsTwentyFour()) {
            return this.getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE);
        } else {
            return false;
        }
    }

    @Override
    public void onMediaLoaded(List<Attachment> loadedAttachments) {
        runOnUiThread(() -> {
            attachments.clear();
            for (Attachment a : loadedAttachments) {
                String mime = a.getMime();
                if (mime != null && (mime.startsWith("image/") || mime.startsWith("video/") || mime.startsWith("audio/"))) {
                    attachments.add(a);
                }
            }
            pagerAdapter.notifyDataSetChanged();
            if (initialMessageUuid != null) {
                for (int i = 0; i < attachments.size(); i++) {
                    if (attachments.get(i).getUuid().toString().equals(initialMessageUuid)) {
                        binding.viewPager.setCurrentItem(i, false);
                        break;
                    }
                }
            }
        });
    }

    private class MediaPagerAdapter extends RecyclerView.Adapter<MediaPagerAdapter.ViewHolder> {
        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            ItemMediaViewerBinding b = DataBindingUtil.inflate(LayoutInflater.from(parent.getContext()), R.layout.item_media_viewer, parent, false);
            return new ViewHolder(b);
        }

        @OptIn(markerClass = UnstableApi.class)
        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            Attachment attachment = attachments.get(position);
            if (attachment.getMime().startsWith("video/") || attachment.getMime().startsWith("audio/")) {
                holder.binding.messageImageView.setVisibility(View.GONE);
                holder.binding.messageVideoView.setVisibility(View.VISIBLE);
                holder.binding.messageVideoView.hideController();
                // Check if PlayerView is available
                if (position == binding.viewPager.getCurrentItem()) {
                    holder.binding.messageVideoView.setPlayer(player);
                } else {
                    holder.binding.messageVideoView.setPlayer(null);
                }
            } else {
                holder.binding.messageVideoView.setPlayer(null);
                holder.binding.messageVideoView.setVisibility(View.GONE);
                holder.binding.messageImageView.setVisibility(View.VISIBLE);
                Glide.with(MediaViewerActivity.this).load(attachment.getUri()).into(holder.binding.messageImageView);
                holder.binding.messageImageView.setOnPhotoTapListener((view, x, y) -> toggleFAB());
            }
        }

        @Override
        public int getItemCount() { return attachments.size(); }

        class ViewHolder extends RecyclerView.ViewHolder {
            ItemMediaViewerBinding binding;
            ViewHolder(ItemMediaViewerBinding b) { super(b.getRoot()); this.binding = b; }
        }
    }

    private void updateRotation(Attachment attachment) {
        Uri uri = attachment.getUri();
        String mime = attachment.getMime();
        if (mime.startsWith("image/")) {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(new File(uri.getPath()).getAbsolutePath(), options);
            height = options.outHeight;
            width = options.outWidth;
            rotation = getRotation(uri);
        } else if (mime.startsWith("video/")) {
            try {
                MediaMetadataRetriever retriever = new MediaMetadataRetriever();
                retriever.setDataSource(uri.getPath());
                height = Integer.valueOf(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
                width = Integer.valueOf(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
                rotation = Integer.valueOf(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));
            } catch (Exception e) {
                rotation = 0;
            }
        } else if (mime.startsWith("audio/")) {
            height = 0;
            width = 0;
            rotation = 0;
        }
        if (useAutoRotateScreen()) {
            rotateScreen(width, height, rotation);
        }
    }

    private void onMediaItemSelected(Attachment attachment) {
        mFile = new File(attachment.getUri().getPath());
        if (player == null) return;

        RecyclerView recyclerView = (RecyclerView) binding.viewPager.getChildAt(0);
        for (int i = 0; i < recyclerView.getChildCount(); i++) {
            View child = recyclerView.getChildAt(i);
            MediaPagerAdapter.ViewHolder h = (MediaPagerAdapter.ViewHolder) recyclerView.getChildViewHolder(child);
            if (h != null) {
                h.binding.messageVideoView.setPlayer(null);
            }
        }

        if (attachment.getMime().startsWith("video/") || attachment.getMime().startsWith("audio/")) {
            isVideo = attachment.getMime().startsWith("video/");
            isAudio = attachment.getMime().startsWith("audio/");
            isImage = false;
            player.stop();
            player.setMediaItem(MediaItem.fromUri(attachment.getUri()));
            player.prepare();
            player.setPlayWhenReady(true);
            requestAudioFocus();

            int position = binding.viewPager.getCurrentItem();
            MediaPagerAdapter.ViewHolder holder = (MediaPagerAdapter.ViewHolder) recyclerView.findViewHolderForAdapterPosition(position);
            if (holder != null) {
                holder.binding.messageVideoView.setPlayer(player);
            }
        } else {
            isVideo = false;
            isAudio = false;
            isImage = true;
            player.stop();
        }
        updateSpeedDialActions();
        updateRotation(attachment);
    }

    private void updateSpeedDialActions() {
        binding.speedDial.clearActionItems();
        if (isDeletableFile(mFile)) {
            binding.speedDial.addActionItem(new SpeedDialActionItem.Builder(R.id.action_delete, R.drawable.ic_delete_24dp)
                    .setLabel(R.string.delete)
                    .setFabImageTintColor(ContextCompat.getColor(this, R.color.white))
                    .create());
        }
        binding.speedDial.addActionItem(new SpeedDialActionItem.Builder(R.id.action_open, R.drawable.ic_open_in_new_white_24dp)
                .setLabel(R.string.open_with)
                .setFabImageTintColor(ContextCompat.getColor(this, R.color.white))
                .create()
        );
        binding.speedDial.addActionItem(new SpeedDialActionItem.Builder(R.id.action_share, R.drawable.ic_share_24dp)
                .setLabel(R.string.share)
                .setFabImageTintColor(ContextCompat.getColor(this, R.color.white))
                .create()
        );
        binding.speedDial.addActionItem(new SpeedDialActionItem.Builder(R.id.action_save, R.drawable.ic_save_24dp)
                .setLabel(R.string.save_to_downloads)
                .setFabImageTintColor(ContextCompat.getColor(this, R.color.white))
                .create()
        );

        binding.speedDial.setOnActionSelectedListener(actionItem -> {
            switch (actionItem.getId()) {
                case R.id.action_share:
                    share();
                    break;
                case R.id.action_open:
                    open();
                    break;
                case R.id.action_save:
                    new MaterialAlertDialogBuilder(MediaViewerActivity.this)
                            .setTitle(R.string.action_save_to_downloads)
                            .setMessage(R.string.save_to_downloads_warning)
                            .setPositiveButton(R.string.confirm, (dialog, which) -> saveToDownloads(mFile))
                            .setNegativeButton(R.string.cancel, null)
                            .show();
                    break;
                case R.id.action_delete:
                    deleteFile();
                    break;
                default:
                    return false;
            }
            return false;
        });
        binding.speedDial.getMainFab().setSupportImageTintList(ColorStateList.valueOf(getResources().getColor(R.color.white)));
    }

    private void toggleFAB() {
        if (binding.speedDial.isShown()) hideFAB(); else showFAB();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (player != null) {
            player.stop();
            player.clearMediaItems();
        }
        attachments.clear();
        pagerAdapter.notifyDataSetChanged();
        if (xmppConnectionService != null) {
            onBackendConnected();
        }
    }
}