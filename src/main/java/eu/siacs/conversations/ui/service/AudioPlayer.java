package eu.siacs.conversations.ui.service;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.util.Log;
import android.view.View;
import android.widget.RelativeLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.common.primitives.Ints;

import java.lang.ref.WeakReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Conversational;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.ui.ConversationsActivity;
import eu.siacs.conversations.ui.adapter.MessageAdapter;
import eu.siacs.conversations.ui.util.PendingItem;
import eu.siacs.conversations.utils.TimeFrameUtils;
import eu.siacs.conversations.utils.WeakReferenceSet;

/**
 * Per-{@link MessageAdapter} binder for audio-message bubbles. The actual playback is owned by the
 * app-scoped {@link BackgroundAudioController} (backed by a media3 background service), so leaving
 * the chat or backgrounding the app no longer stops audio. This class only reflects controller
 * state into the visible bubbles and forwards user actions.
 */
public class AudioPlayer
        implements View.OnClickListener,
                SeekBar.OnSeekBarChangeListener,
                BackgroundAudioController.Listener {

    private final MessageAdapter messageAdapter;
    private final BackgroundAudioController controller;
    private final WeakReferenceSet<RelativeLayout> audioPlayerLayouts = new WeakReferenceSet<>();
    private final PendingItem<WeakReference<MaterialButton>> pendingOnClickView =
            new PendingItem<>();

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler();

    public AudioPlayer(final MessageAdapter adapter) {
        this.messageAdapter = adapter;
        this.controller = BackgroundAudioController.getInstance(adapter.getContext());
    }

    private static String formatTime(final int ms) {
        return TimeFrameUtils.formatElapsedTime(ms, false);
    }

    public void init(final RelativeLayout audioPlayer, final Message message) {
        audioPlayer.setTag(message);
        this.audioPlayerLayouts.addWeakReferenceTo(audioPlayer);
        this.controller.addListener(this);
        init(ViewHolder.get(audioPlayer), message);
    }

    private void init(final ViewHolder viewHolder, final Message message) {
        MessageAdapter.setTextColor(viewHolder.runtime, viewHolder.bubbleColor);
        MessageAdapter.setTextColor(viewHolder.title, viewHolder.bubbleColor);
        viewHolder.progress.setOnSeekBarChangeListener(this);
        executor.execute(
                () -> {
                    final MediaMetadataRetriever mediaMetadataRetriever =
                            new MediaMetadataRetriever();
                    try {
                        mediaMetadataRetriever.setDataSource(message.getRelativeFilePath());
                        final String artist =
                                mediaMetadataRetriever.extractMetadata(
                                        MediaMetadataRetriever.METADATA_KEY_ARTIST);
                        final String album =
                                mediaMetadataRetriever.extractMetadata(
                                        MediaMetadataRetriever.METADATA_KEY_TITLE);
                        if (artist != null && album != null) {
                            handler.post(
                                    () ->
                                            viewHolder.title.setText(
                                                    String.format("%s - %s", artist, album)));
                        }
                    } catch (final Exception e) {
                        Log.w(Config.LOGTAG, e);
                    } finally {
                        try {
                            mediaMetadataRetriever.release();
                        } catch (final Exception e) {
                            Log.e(Config.LOGTAG, "Error releasing MediaMetadataRetriever", e);
                        }
                    }
                });
        final ColorStateList color =
                MessageAdapter.bubbleToOnSurfaceColorStateList(
                        viewHolder.progress, viewHolder.bubbleColor);
        viewHolder.progress.setThumbTintList(color);
        viewHolder.progress.setProgressTintList(color);
        viewHolder.playPause.setOnClickListener(this);
        applyState(viewHolder, message);
    }

    /** Renders the play/pause icon, seekbar progress and runtime for a single bubble. */
    private void applyState(final ViewHolder viewHolder, final Message message) {
        final Context context = viewHolder.playPause.getContext();
        final String uuid = message.getUuid();
        if (controller.isCurrent(uuid)) {
            final boolean playing = controller.isPlaying();
            viewHolder.playPause.setIconResource(
                    playing ? R.drawable.rounded_pause_36 : R.drawable.rounded_play_arrow_36);
            viewHolder.playPause.setContentDescription(
                    context.getString(playing ? R.string.pause_audio : R.string.play_audio));
            viewHolder.progress.setEnabled(playing);
            final int duration = controller.getDurationMs();
            final int current = controller.getCurrentPositionMs();
            if (duration > 0) {
                viewHolder.progress.setProgress(
                        Math.min(Ints.saturatedCast(current * 100L / duration), 100));
                viewHolder.runtime.setText(
                        String.format("%s / %s", formatTime(current), formatTime(duration)));
            }
        } else {
            viewHolder.playPause.setIconResource(R.drawable.rounded_play_arrow_36);
            viewHolder.playPause.setContentDescription(context.getString(R.string.play_audio));
            viewHolder.runtime.setText(formatTime(message.getFileParams().runtime));
            viewHolder.progress.setProgress(0);
            viewHolder.progress.setEnabled(false);
        }
    }

    @Override
    public void onClick(final View v) {
        if (v.getId() == R.id.play_pause) {
            startStop((MaterialButton) v);
        }
    }

    private void startStop(final MaterialButton playPause) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(
                                messageAdapter.getActivity(),
                                Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        != PackageManager.PERMISSION_GRANTED) {
            pendingOnClickView.push(new WeakReference<>(playPause));
            ActivityCompat.requestPermissions(
                    messageAdapter.getActivity(),
                    new String[] {Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    ConversationsActivity.REQUEST_PLAY_PAUSE);
            return;
        }
        final RelativeLayout audioPlayer = (RelativeLayout) playPause.getParent();
        final Message message = (Message) audioPlayer.getTag();
        if (controller.isCurrent(message.getUuid())) {
            controller.toggleCurrentPlayPause();
        } else {
            final Uri uri =
                    Uri.fromFile(messageAdapter.getFileBackend().getFile(message));
            final long duration =
                    message.getFileParams() != null ? message.getFileParams().runtime : 0;
            controller.play(message, uri, duration, conversationTitle(message));
        }
    }

    private String conversationTitle(final Message message) {
        final Conversational conversation = message.getConversation();
        if (conversation instanceof Conversation) {
            return ((Conversation) conversation).getName().toString();
        }
        return null;
    }

    public void startStopPending() {
        final var reference = pendingOnClickView.pop();
        if (reference != null) {
            final var imageButton = reference.get();
            if (imageButton != null) {
                startStop(imageButton);
            }
        }
    }

    @Override
    public void onProgressChanged(
            final SeekBar seekBar, final int progress, final boolean fromUser) {
        final RelativeLayout audioPlayer = (RelativeLayout) seekBar.getParent();
        final Message message = (Message) audioPlayer.getTag();
        if (fromUser && message != null && controller.isCurrent(message.getUuid())) {
            controller.seekToFraction(progress / 100f);
        }
    }

    @Override
    public void onStartTrackingTouch(final SeekBar seekBar) {}

    @Override
    public void onStopTrackingTouch(final SeekBar seekBar) {}

    // region BackgroundAudioController.Listener
    @Override
    public void onAudioStateChanged() {
        for (final WeakReference<RelativeLayout> reference : audioPlayerLayouts) {
            final RelativeLayout audioPlayer = reference.get();
            if (audioPlayer == null || audioPlayer.getVisibility() != View.VISIBLE) {
                continue;
            }
            final Message message = (Message) audioPlayer.getTag();
            if (message != null) {
                applyState(ViewHolder.get(audioPlayer), message);
            }
        }
    }

    @Override
    public void onAudioProgress(final int currentMs, final int durationMs, final float fraction) {
        final Message current = controller.getCurrentMessage();
        if (current == null) {
            return;
        }
        for (final WeakReference<RelativeLayout> reference : audioPlayerLayouts) {
            final RelativeLayout audioPlayer = reference.get();
            if (audioPlayer == null || audioPlayer.getVisibility() != View.VISIBLE) {
                continue;
            }
            final Message message = (Message) audioPlayer.getTag();
            if (message == null || !message.getUuid().equals(current.getUuid())) {
                continue;
            }
            final ViewHolder viewHolder = ViewHolder.get(audioPlayer);
            viewHolder.progress.setProgress(
                    durationMs > 0
                            ? Math.min(Ints.saturatedCast(currentMs * 100L / durationMs), 100)
                            : 100);
            viewHolder.runtime.setText(
                    String.format("%s / %s", formatTime(currentMs), formatTime(durationMs)));
        }
    }
    // endregion

    /** Detach this binder's UI from the controller without stopping background playback. */
    public void stop() {
        this.controller.removeListener(this);
        this.audioPlayerLayouts.clear();
    }

    /** Detach the controller listener (called when the chat is no longer visible). */
    public void unregisterListener() {
        this.controller.removeListener(this);
    }

    public static class ViewHolder {
        private TextView runtime;
        private TextView title;
        private SeekBar progress;
        private MaterialButton playPause;
        private MessageAdapter.BubbleColor bubbleColor = MessageAdapter.BubbleColor.SURFACE;

        public static ViewHolder get(final RelativeLayout audioPlayer) {
            final var existingViewHolder =
                    (ViewHolder) audioPlayer.getTag(R.id.TAG_AUDIO_PLAYER_VIEW_HOLDER);
            if (existingViewHolder != null) {
                return existingViewHolder;
            }
            final ViewHolder viewHolder = new ViewHolder();
            viewHolder.runtime = audioPlayer.findViewById(R.id.runtime);
            viewHolder.title = audioPlayer.findViewById(R.id.title);
            viewHolder.progress = audioPlayer.findViewById(R.id.progress);
            viewHolder.playPause = audioPlayer.findViewById(R.id.play_pause);
            audioPlayer.setTag(R.id.TAG_AUDIO_PLAYER_VIEW_HOLDER, viewHolder);
            return viewHolder;
        }

        public void setBubbleColor(final MessageAdapter.BubbleColor bubbleColor) {
            this.bubbleColor = bubbleColor;
        }
    }
}
