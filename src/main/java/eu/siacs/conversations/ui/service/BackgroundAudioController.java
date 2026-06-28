package eu.siacs.conversations.ui.service;

import android.content.ComponentName;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionCommand;
import androidx.media3.session.SessionToken;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.services.AudioPlaybackService;

/**
 * App-scoped singleton that owns the single {@link MediaController} talking to {@link
 * AudioPlaybackService}. Both the in-bubble player ({@link AudioPlayer}) and the under-toolbar
 * mini-player ({@link AudioMiniPlayer}) drive and observe playback through here, so audio is no
 * longer tied to a conversation's {@code MessageAdapter} lifecycle and keeps playing in the
 * background.
 */
public class BackgroundAudioController implements SensorEventListener {

    public interface Listener {
        /** Play/pause/track/stop changed — refresh play-pause icons and bar visibility. */
        void onAudioStateChanged();

        /** Periodic position update while playing. */
        void onAudioProgress(int currentMs, int durationMs, float fraction);
    }

    private static final int REFRESH_INTERVAL = 250;
    private static BackgroundAudioController instance;

    public static synchronized BackgroundAudioController getInstance(final Context context) {
        if (instance == null) {
            instance = new BackgroundAudioController(context.getApplicationContext());
        }
        return instance;
    }

    private final Context appContext;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final List<Runnable> pending = new ArrayList<>();

    private ListenableFuture<MediaController> future;
    private MediaController controller;

    private Message currentMessage;
    private String currentTitle;

    // raise-to-ear (foreground only)
    private final SensorManager sensorManager;
    private final Sensor proximitySensor;
    private PowerManager.WakeLock wakeLock;
    private boolean foreground;
    private boolean earpiece;

    private BackgroundAudioController(final Context appContext) {
        this.appContext = appContext;
        this.sensorManager = (SensorManager) appContext.getSystemService(Context.SENSOR_SERVICE);
        this.proximitySensor =
                sensorManager == null ? null : sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
        final PowerManager powerManager =
                (PowerManager) appContext.getSystemService(Context.POWER_SERVICE);
        if (powerManager != null) {
            this.wakeLock =
                    powerManager.newWakeLock(
                            PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
                            BackgroundAudioController.class.getSimpleName());
            this.wakeLock.setReferenceCounted(false);
        }
    }

    // region listeners
    public void addListener(final Listener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(final Listener listener) {
        listeners.remove(listener);
    }

    private void notifyState() {
        for (final Listener listener : listeners) {
            listener.onAudioStateChanged();
        }
    }
    // endregion

    private void ensureController() {
        if (controller != null || future != null) {
            return;
        }
        final SessionToken token =
                new SessionToken(
                        appContext, new ComponentName(appContext, AudioPlaybackService.class));
        future = new MediaController.Builder(appContext, token).buildAsync();
        future.addListener(
                () -> {
                    try {
                        controller = future.get();
                        controller.addListener(playerListener);
                        for (final Runnable runnable : pending) {
                            runnable.run();
                        }
                        pending.clear();
                        notifyState();
                        startTicker();
                    } catch (final Exception e) {
                        Log.w(Config.LOGTAG, "unable to connect audio MediaController", e);
                    }
                },
                ContextCompat.getMainExecutor(appContext));
    }

    private void run(final Runnable action) {
        ensureController();
        if (controller != null) {
            action.run();
        } else {
            pending.add(action);
        }
    }

    /** Start (or restart) playback of an audio message. Only the bubble has the file info. */
    public void play(
            final Message message, final Uri fileUri, final long durationMs, final String title) {
        run(
                () -> {
                    final MediaItem item =
                            new MediaItem.Builder()
                                    .setMediaId(message.getUuid())
                                    .setUri(fileUri)
                                    .setMediaMetadata(
                                            new MediaMetadata.Builder()
                                                    .setTitle(
                                                            appContext.getString(
                                                                    R.string.voice_message))
                                                    .setIsBrowsable(false)
                                                    .setIsPlayable(true)
                                                    .build())
                                    .build();
                    currentMessage = message;
                    currentTitle = title;
                    controller.setMediaItem(item);
                    controller.prepare();
                    controller.play();
                    notifyState();
                });
        registerProximity();
    }

    public void toggleCurrentPlayPause() {
        run(
                () -> {
                    if (controller.isPlaying()) {
                        controller.pause();
                    } else {
                        controller.play();
                        registerProximity();
                    }
                    notifyState();
                });
    }

    public void seekToFraction(final float fraction) {
        run(
                () -> {
                    final long duration = controller.getDuration();
                    if (duration != C.TIME_UNSET && duration > 0) {
                        controller.seekTo((long) (duration * fraction));
                    }
                });
    }

    public void stop() {
        run(
                () -> {
                    controller.stop();
                    controller.clearMediaItems();
                });
        currentMessage = null;
        currentTitle = null;
        releaseEarpiece();
        unregisterProximity();
        notifyState();
    }

    private final Player.Listener playerListener =
            new Player.Listener() {
                @Override
                public void onIsPlayingChanged(final boolean isPlaying) {
                    if (isPlaying) {
                        startTicker();
                    } else {
                        setEarpiece(false);
                    }
                    notifyState();
                }

                @Override
                public void onPlaybackStateChanged(final int playbackState) {
                    if (playbackState == Player.STATE_ENDED) {
                        if (controller != null) {
                            controller.clearMediaItems();
                        }
                        handleStopped();
                    } else if (playbackState == Player.STATE_IDLE
                            && controller != null
                            && controller.getMediaItemCount() == 0) {
                        // stopped from the notification / lock screen
                        handleStopped();
                    }
                    notifyState();
                }

                @Override
                public void onMediaItemTransition(
                        @Nullable final MediaItem mediaItem, final int reason) {
                    if (mediaItem == null) {
                        handleStopped();
                    }
                    notifyState();
                }
            };

    private void handleStopped() {
        currentMessage = null;
        currentTitle = null;
        releaseEarpiece();
        unregisterProximity();
    }

    // region ticker
    private final Runnable ticker =
            new Runnable() {
                @Override
                public void run() {
                    if (controller != null && controller.isPlaying()) {
                        final long rawDuration = controller.getDuration();
                        final int duration =
                                rawDuration == C.TIME_UNSET ? 0 : (int) rawDuration;
                        final int current = (int) controller.getCurrentPosition();
                        final float fraction = duration > 0 ? current / (float) duration : 0f;
                        for (final Listener listener : listeners) {
                            listener.onAudioProgress(current, duration, fraction);
                        }
                        handler.postDelayed(this, REFRESH_INTERVAL);
                    }
                }
            };

    private void startTicker() {
        handler.removeCallbacks(ticker);
        handler.post(ticker);
    }
    // endregion

    // region state queries
    public boolean isConnected() {
        return controller != null;
    }

    public boolean isCurrent(final String uuid) {
        return currentMessage != null && currentMessage.getUuid().equals(uuid);
    }

    public boolean isPlaying() {
        return controller != null && controller.isPlaying();
    }

    public boolean isPlayingMessage(final String uuid) {
        return isCurrent(uuid) && isPlaying();
    }

    public Message getCurrentMessage() {
        return currentMessage;
    }

    public String getCurrentTitle() {
        return currentTitle;
    }

    public int getCurrentPositionMs() {
        return controller == null ? 0 : (int) controller.getCurrentPosition();
    }

    public int getDurationMs() {
        if (controller == null) {
            return 0;
        }
        final long duration = controller.getDuration();
        return duration == C.TIME_UNSET ? 0 : (int) duration;
    }

    public float getProgressFraction() {
        final int duration = getDurationMs();
        return duration > 0 ? getCurrentPositionMs() / (float) duration : 0f;
    }
    // endregion

    // region raise-to-ear (foreground only)
    /** Called by the host activity so earpiece routing only happens while the app is visible. */
    public void setForeground(final boolean foreground) {
        this.foreground = foreground;
        if (!foreground) {
            setEarpiece(false);
            unregisterProximity();
        } else if (isPlaying()) {
            registerProximity();
        }
    }

    private void registerProximity() {
        if (foreground && sensorManager != null && proximitySensor != null) {
            sensorManager.registerListener(
                    this, proximitySensor, SensorManager.SENSOR_DELAY_NORMAL);
        }
    }

    private void unregisterProximity() {
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
    }

    private void setEarpiece(final boolean earpiece) {
        if (this.earpiece == earpiece || controller == null) {
            return;
        }
        this.earpiece = earpiece;
        final Bundle args = new Bundle();
        args.putBoolean(AudioPlaybackService.EXTRA_EARPIECE, earpiece);
        controller.sendCustomCommand(
                new SessionCommand(AudioPlaybackService.CMD_SET_EARPIECE, Bundle.EMPTY), args);
        if (earpiece) {
            acquireWakeLock();
        } else {
            releaseWakeLock();
        }
    }

    private void releaseEarpiece() {
        earpiece = false;
        releaseWakeLock();
    }

    private void acquireWakeLock() {
        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire();
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }

    @Override
    public void onSensorChanged(final SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_PROXIMITY) {
            return;
        }
        if (!foreground || !isPlaying()) {
            return;
        }
        final boolean near =
                event.values[0] < 5f && event.values[0] != proximitySensor.getMaximumRange();
        setEarpiece(near);
    }

    @Override
    public void onAccuracyChanged(final Sensor sensor, final int accuracy) {}
    // endregion
}
