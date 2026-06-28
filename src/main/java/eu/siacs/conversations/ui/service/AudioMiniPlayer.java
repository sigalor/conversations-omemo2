package eu.siacs.conversations.ui.service;

import android.view.View;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Conversational;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.ui.ConversationsActivity;

/**
 * Binds the always-visible "now playing" bar (view_audio_miniplayer.xml, included under the toolbar
 * in {@link ConversationsActivity}) to the app-scoped {@link BackgroundAudioController}. Shows while
 * an audio message is loaded, mirrors play/pause and progress, and lets the user pause, stop, or
 * jump to the playing message.
 */
public class AudioMiniPlayer implements BackgroundAudioController.Listener {

    private final ConversationsActivity activity;
    private final BackgroundAudioController controller;
    private final View root;
    private final MaterialButton playPause;
    private final TextView subtitle;
    private final LinearProgressIndicator progress;

    public AudioMiniPlayer(final ConversationsActivity activity, final View root) {
        this.activity = activity;
        this.root = root;
        this.controller = BackgroundAudioController.getInstance(activity);
        this.playPause = root.findViewById(R.id.miniplayer_play_pause);
        this.subtitle = root.findViewById(R.id.miniplayer_subtitle);
        this.progress = root.findViewById(R.id.miniplayer_progress);
        final MaterialButton close = root.findViewById(R.id.miniplayer_close);
        this.playPause.setOnClickListener(v -> controller.toggleCurrentPlayPause());
        close.setOnClickListener(v -> controller.stop());
        this.root.setOnClickListener(v -> jumpToMessage());
        update();
    }

    /** Hook from the host activity's onResume — start observing and allow earpiece routing. */
    public void onResume() {
        controller.addListener(this);
        controller.setForeground(true);
        update();
    }

    /** Hook from the host activity's onPause — stop earpiece routing and observing. */
    public void onPause() {
        controller.setForeground(false);
        controller.removeListener(this);
    }

    private void jumpToMessage() {
        final Message message = controller.getCurrentMessage();
        if (message == null) {
            return;
        }
        final Conversational conversation = message.getConversation();
        if (conversation instanceof Conversation) {
            activity.openConversationAtMessage((Conversation) conversation, message.getUuid());
        }
    }

    @Override
    public void onAudioStateChanged() {
        update();
    }

    @Override
    public void onAudioProgress(final int currentMs, final int durationMs, final float fraction) {
        progress.setProgress(durationMs > 0 ? Math.round(fraction * 100) : 0);
    }

    private void update() {
        final Message message = controller.getCurrentMessage();
        if (message == null) {
            root.setVisibility(View.GONE);
            return;
        }
        root.setVisibility(View.VISIBLE);
        final boolean playing = controller.isPlaying();
        playPause.setIconResource(
                playing ? R.drawable.rounded_pause_36 : R.drawable.rounded_play_arrow_36);
        playPause.setContentDescription(
                activity.getString(playing ? R.string.pause_audio : R.string.play_audio));
        final String title = controller.getCurrentTitle();
        if (title != null && !title.isEmpty()) {
            subtitle.setText(title);
            subtitle.setVisibility(View.VISIBLE);
        } else {
            subtitle.setVisibility(View.GONE);
        }
        final int duration = controller.getDurationMs();
        final int current = controller.getCurrentPositionMs();
        progress.setProgress(duration > 0 ? Math.min(100, (int) (current * 100L / duration)) : 0);
    }
}
