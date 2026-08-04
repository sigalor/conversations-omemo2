package eu.siacs.conversations.ui.adapter;

import static android.view.View.GONE;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.preference.PreferenceManager;
import android.text.Editable;
import android.text.Layout;
import android.text.Spanned;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.style.ImageSpan;
import android.text.style.ClickableSpan;
import android.text.format.DateUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.URLSpan;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.util.LruCache;
import android.view.accessibility.AccessibilityEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.AttrRes;
import androidx.annotation.ColorInt;
import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.cardview.widget.CardView;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;
import androidx.core.graphics.ColorUtils;
import androidx.core.util.Pair;
import androidx.core.widget.ImageViewCompat;
import androidx.databinding.DataBindingUtil;
import androidx.media3.common.util.Log;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.imageview.ShapeableImageView;
import com.google.android.material.shape.CornerFamily;
import com.google.android.material.shape.ShapeAppearanceModel;

import de.monocles.chat.BobTransfer;
import de.monocles.chat.InlineImageSpan;
import de.monocles.chat.MessageTextActionModeCallback;
import de.monocles.chat.Util;
import de.monocles.chat.WebxdcPage;
import de.monocles.chat.WebxdcUpdate;
import de.monocles.chat.EmojiSearch;
import de.monocles.chat.GetThumbnailForCid;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.color.MaterialColors;
import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;

import com.lelloman.identicon.view.GithubIdenticonView;

import android.text.StaticLayout;
import de.monocles.chat.ui.AlbumLayout;
import de.monocles.chat.ui.CollapsableTextView;
import eu.siacs.conversations.entities.Story;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.ui.AddReactionActivity;
import eu.siacs.conversations.ui.StoryViewActivity;
import io.ipfs.cid.Cid;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import me.saket.bettermovementmethod.BetterLinkMovementMethod;

import net.fellbaum.jemoji.EmojiManager;

import eu.siacs.conversations.AppSettings;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.crypto.axolotl.FingerprintStatus;
import eu.siacs.conversations.databinding.ItemMessageDateBubbleBinding;
import eu.siacs.conversations.databinding.ItemMessageRtpSessionBinding;
import eu.siacs.conversations.databinding.ItemMessageStatusBinding;
import eu.siacs.conversations.databinding.LinkDescriptionBinding;
import eu.siacs.conversations.databinding.ItemMessageEndBinding;
import eu.siacs.conversations.databinding.ItemMessageStartBinding;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Conversational;
import eu.siacs.conversations.entities.DownloadableFile;
import eu.siacs.conversations.entities.Message.FileParams;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.entities.MucOptions;
import eu.siacs.conversations.entities.Reaction;
import eu.siacs.conversations.entities.RtpSessionStatus;
import eu.siacs.conversations.entities.Transferable;
import eu.siacs.conversations.persistance.FileBackend;
import eu.siacs.conversations.services.MessageArchiveService;
import eu.siacs.conversations.services.NotificationService;
import eu.siacs.conversations.ui.Activities;
import eu.siacs.conversations.ui.BindingAdapters;
import eu.siacs.conversations.ui.ConversationFragment;
import eu.siacs.conversations.ui.ConversationsActivity;
import eu.siacs.conversations.ui.XmppActivity;
import eu.siacs.conversations.ui.service.AudioPlayer;
import eu.siacs.conversations.ui.text.DividerSpan;
import eu.siacs.conversations.ui.text.FixedURLSpan;
import eu.siacs.conversations.ui.text.QuoteSpan;
import eu.siacs.conversations.ui.util.Attachment;
import eu.siacs.conversations.ui.util.AvatarWorkerTask;
import eu.siacs.conversations.ui.util.MyLinkify;
import eu.siacs.conversations.ui.util.QuoteHelper;
import eu.siacs.conversations.ui.util.ShareUtil;
import eu.siacs.conversations.ui.util.ViewUtil;
import eu.siacs.conversations.utils.CryptoHelper;
import eu.siacs.conversations.utils.Emoticons;
import eu.siacs.conversations.utils.GeoHelper;
import eu.siacs.conversations.utils.MessageUtils;
import eu.siacs.conversations.utils.StylingHelper;
import eu.siacs.conversations.utils.TimeFrameUtils;
import eu.siacs.conversations.utils.UIHelper;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.mam.MamReference;
import eu.siacs.conversations.xml.Element;

import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class MessageAdapter extends RecyclerView.Adapter<MessageAdapter.MessageItemViewHolder> {

    public static final String DATE_SEPARATOR_BODY = "DATE_SEPARATOR";
    private static final Executor THUMBNAIL_EXECUTOR = Executors.newSingleThreadExecutor();
    private static final int END = 0;
    private static final int START = 1;
    private static final int STATUS = 2;
    private static final int DATE_SEPARATOR = 3;
    private static final int RTP_SESSION = 4;
    private final XmppActivity activity;
    private final AudioPlayer audioPlayer;
    private final List<Message> messages;
    private List<String> highlightedTerm = null;
    private final DisplayMetrics metrics;
    private ConversationFragment mConversationFragment = null;
    private OnContactPictureClicked mOnContactPictureClickedListener;
    private OnContactPictureClicked mOnMessageBoxClickedListener;
    private OnContactPictureLongClicked mOnContactPictureLongClickedListener;
    private OnInlineImageLongClicked mOnInlineImageLongClickedListener;
    private BubbleDesign bubbleDesign = new BubbleDesign(false, false, false, true);
    private final boolean mForceNames;
    private final Map<String, WebxdcUpdate> lastWebxdcUpdate = new HashMap<>();
    private String selectionUuid = null;
    private final AppSettings appSettings;
    private ReplyClickListener replyClickListener;
    private OnDateSeparatorClickListener onDateSeparatorClickListener;

    private final float imagePreviewWidthTarget;
    private final float bubbleRadiusDim;
    private final float imageRadiusDim;
    private final float density;
    private final float padding8dp;
    private final float padding22dp;
    private final float targetImageWidthSmallThreshold;
    private final float targetImageWidthLargeThreshold;
    private boolean allowRelativeTimestamps = true;

    private final Typeface notoRegular;
    private final Typeface notoBold;
    private final Typeface notoItalic;

    private static final long LIVE_LOCATION_PREVIEW_REFRESH_MS = 60_000L;
    private final java.util.Map<String, String> liveLocationPreviewUrl = new java.util.HashMap<>();
    private final java.util.Map<String, Long> liveLocationPreviewTime = new java.util.HashMap<>();

    /** Whether the row at {@code position} is a message bubble (and so can be swiped to reply). */
    public boolean isSwipeableMessage(final int position) {
        if (position < 0 || position >= messages.size()) {
            return false;
        }
        final int type = getItemViewType(messages.get(position), bubbleDesign.alignStart);
        return type == START || type == END;
    }

    public MessageAdapter(
            final XmppActivity activity, final List<Message> messages, final boolean forceNames) {
        this.messages = messages;
        // Must be assigned before constructing AudioPlayer below, which reaches back into this
        // adapter via getContext() (now backed by this.activity rather than ArrayAdapter's super).
        this.activity = activity;
        this.density = activity.getResources().getDisplayMetrics().density;
        this.imagePreviewWidthTarget = activity.getResources().getDimension(R.dimen.image_preview_width);
        this.bubbleRadiusDim = activity.getResources().getDimension(R.dimen.bubble_radius);
        this.imageRadiusDim = activity.getResources().getDimension(R.dimen.image_radius);
        this.padding8dp = 8 * this.density;
        this.padding22dp = 22 * this.density;
        this.targetImageWidthSmallThreshold = 110 * this.density;
        this.targetImageWidthLargeThreshold = 200 * this.density;
        this.audioPlayer = new AudioPlayer(this);
        metrics = getContext().getResources().getDisplayMetrics();
        appSettings = new AppSettings(activity);
        updatePreferences();
        this.mForceNames = forceNames;
        notoRegular = ResourcesCompat.getFont(activity, R.font.noto_sans_regular);
        notoBold = ResourcesCompat.getFont(activity, R.font.noto_sans_bold);
        notoItalic = ResourcesCompat.getFont(activity, R.font.noto_sans_italic);
        final SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(activity);
        allowRelativeTimestamps = !p.getBoolean("always_full_timestamps", activity.getResources().getBoolean(R.bool.always_full_timestamps));
    }

    public MessageAdapter(final XmppActivity activity, final List<Message> messages) {
        this(activity, messages, false);
    }

    @Override
    public int getItemCount() {
        return messages.size();
    }

    /** Kept for the handful of callers that pre-date the RecyclerView migration. */
    public Message getItem(final int position) {
        return messages.get(position);
    }

    /** Kept for the handful of callers that pre-date the RecyclerView migration. */
    public int getCount() {
        return messages.size();
    }

    /** Replaces {@code ArrayAdapter#getContext()} for the many internal callers. */
    public Context getContext() {
        return activity;
    }

    private static void resetClickListener(View... views) {
        for (View view : views) {
            if (view != null) view.setOnClickListener(null);
        }
    }

    public void flagScreenOn() {
        activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    public void flagScreenOff() {
        activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    public void setVolumeControl(final int stream) {
        activity.setVolumeControlStream(stream);
    }

    public void setOnContactPictureClicked(OnContactPictureClicked listener) {
        this.mOnContactPictureClickedListener = listener;
    }

    public void setOnMessageBoxClicked(OnContactPictureClicked listener) {
        this.mOnMessageBoxClickedListener = listener;
    }

    public void setReplyClickListener(ReplyClickListener listener) {
        this.replyClickListener = listener;
    }

    public void setOnDateSeparatorClickListener(OnDateSeparatorClickListener listener) {
        this.onDateSeparatorClickListener = listener;
    }

    public void setConversationFragment(ConversationFragment frag) {
        mConversationFragment = frag;
    }

    public void quoteText(String text) {
        if (mConversationFragment != null) mConversationFragment.quoteText(text);
    }

    public boolean hasSelection() {
        return selectionUuid != null;
    }

    public Activity getActivity() {
        return activity;
    }

    public void setOnContactPictureLongClicked(OnContactPictureLongClicked listener) {
        this.mOnContactPictureLongClickedListener = listener;
    }

    public void setOnInlineImageLongClicked(OnInlineImageLongClicked listener) {
        this.mOnInlineImageLongClickedListener = listener;
    }

    private static int getItemViewType(final Message message, final boolean alignStart) {
        if (message.getType() == Message.TYPE_STATUS) {
            if (DATE_SEPARATOR_BODY.equals(message.getBody())) {
                return DATE_SEPARATOR;
            } else {
                return STATUS;
            }
        } else if (message.getType() == Message.TYPE_RTP_SESSION) {
            return RTP_SESSION;
        } else if (message.getStatus() <= Message.STATUS_RECEIVED || alignStart) {
            return START;
        } else {
            return END;
        }
    }

    @Override
    public int getItemViewType(final int position) {
        return getItemViewType(Objects.requireNonNull(getItem(position)), bubbleDesign.alignStart);
    }


    private void displayStatus(
            final BubbleMessageItemViewHolder viewHolder,
            final Message message,
            final BubbleColor bubbleColor) {
        final int status = message.getStatus();
        final boolean error;
        final Transferable transferable = message.getTransferable();

        final boolean sent = status != Message.STATUS_RECEIVED;
        final boolean showUserNickname =
                message.getConversation().getMode() == Conversation.MODE_MULTI
                        && viewHolder instanceof StartBubbleMessageItemViewHolder;
        final String fileSize;
        if (message.isFileOrImage()
                || transferable != null
                || MessageUtils.unInitiatedButKnownSize(message)) {
            final FileParams params = message.getFileParams();
            fileSize = params.size != null ? UIHelper.filesizeToString(params.size) : null;
            if (message.getStatus() == Message.STATUS_SEND_FAILED
                    || (transferable != null
                    && (transferable.getStatus() == Transferable.STATUS_FAILED
                    || transferable.getStatus()
                    == Transferable.STATUS_CANCELLED))) {
                error = true;
            } else {
                error = message.getStatus() == Message.STATUS_SEND_FAILED;
            }
        } else {
            fileSize = null;
            error = message.getStatus() == Message.STATUS_SEND_FAILED;
        }

        if (sent) {
            final @DrawableRes Integer receivedIndicator =
                    getMessageStatusAsDrawable(message, status);
            if (receivedIndicator == null) {
                viewHolder.indicatorReceived().setVisibility(View.INVISIBLE);
            } else {
                viewHolder.indicatorReceived().setImageResource(receivedIndicator);
                if (status == Message.STATUS_SEND_FAILED) {
                    setImageTintError(viewHolder.indicatorReceived());
                } else {
                    setImageTint(viewHolder.indicatorReceived(), bubbleColor);
                }
                viewHolder.indicatorReceived().setVisibility(View.VISIBLE);
            }
        } else {
            viewHolder.indicatorReceived().setVisibility(View.GONE);
        }
        final var additionalStatusInfo = getAdditionalStatusInfo(message, status);

        if (error && sent) {
            viewHolder
                    .time()
                    .setTextColor(
                            MaterialColors.getColor(
                                    viewHolder.time(), androidx.appcompat.R.attr.colorError));
        } else {
            setTextColor(viewHolder.time(), bubbleColor);
        }
        setTextColor(viewHolder.subject(), bubbleColor);
        if (message.getEncryption() == Message.ENCRYPTION_NONE) {
            viewHolder.indicatorSecurity().setVisibility(View.GONE);
        } else {
            final boolean omemo2 = message.getEncryption() == Message.ENCRYPTION_AXOLOTL_OMEMO2;
            boolean verified = false;
            if (message.getEncryption() == Message.ENCRYPTION_AXOLOTL || omemo2) {
                final FingerprintStatus fingerprintStatus =
                        message.getConversation()
                                .getAccount()
                                .getAxolotlService()
                                .getFingerprintTrust(message.getFingerprint());
                if (fingerprintStatus != null && fingerprintStatus.isVerified()) {
                    verified = true;
                }
            }
            // Like legacy OMEMO: a shield means the sending fingerprint is verified,
            // a lock means it is not. PQ OMEMO2 uses its own shield/lock variants so
            // it stays visually distinct from legacy. (Own outgoing messages carry
            // our own fingerprint, which is verified, so they show a shield; a carbon
            // of a message sent from another of our devices carries that device's
            // unverified fingerprint, so it shows a lock.)
            if (verified) {
                viewHolder.indicatorSecurity().setImageResource(omemo2
                        ? R.drawable.ic_shield_omemo2_verified_24dp
                        : R.drawable.ic_verified_user_24dp);
            } else if (omemo2) {
                viewHolder.indicatorSecurity().setImageResource(R.drawable.ic_lock_omemo2_24dp);
            } else {
                viewHolder.indicatorSecurity().setImageResource(R.drawable.ic_lock_24dp);
            }
            if (error && sent) {
                setImageTintError(viewHolder.indicatorSecurity());
            } else {
                setImageTint(viewHolder.indicatorSecurity(), bubbleColor);
            }
            viewHolder.indicatorSecurity().setVisibility(View.VISIBLE);
        }

        if (message.edited()) {
            viewHolder.indicatorEdit().setVisibility(View.VISIBLE);
            if (error && sent) {
                setImageTintError(viewHolder.indicatorEdit());
            } else {
                setImageTint(viewHolder.indicatorEdit(), bubbleColor);
            }
        } else {
            viewHolder.indicatorEdit().setVisibility(View.GONE);
        }

        if (message.getEphemeralTimer() > 0) {
            viewHolder.indicatorEphemeral().setVisibility(View.VISIBLE);
            if (error && sent) {
                setImageTintError(viewHolder.indicatorEphemeral());
            } else {
                setImageTint(viewHolder.indicatorEphemeral(), bubbleColor);
            }
        } else {
            viewHolder.indicatorEphemeral().setVisibility(View.GONE);
        }

        final String formattedTime =
                UIHelper.readableTimeDifferenceFull(getContext(), message.getTimeSent(), allowRelativeTimestamps);
        final String bodyLanguage = message.getBodyLanguage();
        final ImmutableList.Builder<String> timeInfoBuilder = new ImmutableList.Builder<>();

        if (bodyLanguage != null) {
            timeInfoBuilder.add(bodyLanguage.toUpperCase(Locale.US));
        }
        // for space reasons we display only 'additional status info' (send progress or concrete
        // failure reason) or the time
        if (additionalStatusInfo != null) {
            timeInfoBuilder.add(additionalStatusInfo);
        } else {
            timeInfoBuilder.add(formattedTime);
        }
        final String timeRow = Joiner.on(" · ").join(timeInfoBuilder.build());
        // Put the file size on its own line above the time/details. Otherwise "1.2 MB · 12:34"
        // (plus the encryption status) makes the footer — and therefore the whole bubble — wider
        // than the media/caption above it.
        viewHolder.time().setText(fileSize != null ? fileSize + "\n" + timeRow : timeRow);
    }

    public static @DrawableRes Integer getMessageStatusAsDrawable(
            final Message message, final int status) {
        final var transferable = message.getTransferable();
        return switch (status) {
            case Message.STATUS_WAITING -> R.drawable.ic_more_horiz_24dp;
            case Message.STATUS_UNSEND -> transferable == null ? null : R.drawable.ic_upload_24dp;
            case Message.STATUS_SEND -> R.drawable.ic_done_24dp;
            case Message.STATUS_SEND_RECEIVED, Message.STATUS_SEND_DISPLAYED ->
                    R.drawable.ic_done_all_24dp;
            case Message.STATUS_SEND_FAILED -> {
                final String errorMessage = message.getErrorMessage();
                if (Message.ERROR_MESSAGE_CANCELLED.equals(errorMessage)) {
                    yield R.drawable.ic_cancel_24dp;
                } else {
                    yield R.drawable.ic_error_24dp;
                }
            }
            case Message.STATUS_OFFERED -> R.drawable.ic_p2p_24dp;
            default -> null;
        };
    }

    @Nullable
    private String getAdditionalStatusInfo(final Message message, final int mergedStatus) {
        final String additionalStatusInfo;
        if (mergedStatus == Message.STATUS_SEND_FAILED) {
            final String errorMessage = Strings.nullToEmpty(message.getErrorMessage());
            final String[] errorParts = errorMessage.split("\\u001f", 2);
            if (errorParts.length == 2 && errorParts[0].equals("file-too-large")) {
                additionalStatusInfo = getContext().getString(R.string.file_too_large);
            } else {
                additionalStatusInfo = null;
            }
        } else if (mergedStatus == Message.STATUS_UNSEND) {
            final var transferable = message.getTransferable();
            if (transferable == null) {
                return null;
            }
            return getContext().getString(R.string.sending_file, transferable.getProgress());
        } else {
            additionalStatusInfo = null;
        }
        return additionalStatusInfo;
    }

    private void displayInfoMessage(
            BubbleMessageItemViewHolder viewHolder,
            CharSequence text,
            final BubbleColor bubbleColor) {
        viewHolder.storyPreview().setVisibility(View.GONE);
        viewHolder.downloadButton().setVisibility(View.GONE);
        viewHolder.audioPlayer().setVisibility(View.GONE);
        viewHolder.image().setVisibility(View.GONE);
        viewHolder.messageBody().setTypeface(notoItalic);
        viewHolder.messageBody().setVisibility(View.VISIBLE);
        viewHolder.messageBox().setBackgroundTintMode(PorterDuff.Mode.SRC);
        viewHolder.statusLine().setBackground(ContextCompat.getDrawable(activity, R.drawable.background_message_bubble));
        viewHolder.statusLine().setBackgroundTintList(bubbleToColorStateList(viewHolder.statusLine(), bubbleColor));
        if (viewHolder.username() != null) {
            viewHolder.username().setBackground(ContextCompat.getDrawable(activity, R.drawable.background_message_bubble));
            viewHolder.username().setBackgroundTintList(bubbleToColorStateList(viewHolder.username(), bubbleColor));
        }
        viewHolder.messageBody().setEllipsize(null);
        viewHolder.messageBody().setMaxLines(Integer.MAX_VALUE);
        viewHolder.showMore().setVisibility(View.GONE);
        viewHolder.messageBody().setText(text);
        viewHolder
                .messageBody()
                .setTextColor(bubbleToOnSurfaceVariant(viewHolder.messageBody(), bubbleColor));
        viewHolder.messageBody().setTextIsSelectable(false);
    }

    private void displayPubSubMessage(
            final BubbleMessageItemViewHolder viewHolder,
            final Message message,
            final BubbleColor bubbleColor) {

        // First, handle the text part of the message.
        // This ensures the text is always displayed for a story reply.
        displayTextMessage(viewHolder, message, bubbleColor);

        // Now, find and display the story preview.
        final Pair<Jid, String> storyReference = message.getStoryReference();
        Story story = null;
        if (storyReference != null) {
            final XmppConnectionService xmppService = activity.xmppConnectionService;
            if (xmppService != null) {
                for (final Story s : activity.xmppConnectionService.getStories()) {
                    if (s.getUuid().equals(storyReference.second)) {
                        story = s;
                        break;
                    }
                }
            }
        }

        // Only show the preview if a valid story was actually found.
        if (story != null) {
            viewHolder.storyPreview().setVisibility(View.VISIBLE);
            viewHolder.storyTitle().setText(story.getTitle());
            Glide.with(activity).load(story.getUrl()).into(viewHolder.storyThumbnail());
            final Story finalStory = story;
            viewHolder.storyPreview().setOnClickListener(v -> {
                final Intent intent = new Intent(activity, StoryViewActivity.class);
                intent.putExtra(StoryViewActivity.EXTRA_URLS, new ArrayList<>(Collections.singletonList(finalStory.getUrl())));
                intent.putExtra(StoryViewActivity.EXTRA_TITLES, new ArrayList<>(Collections.singletonList(finalStory.getTitle())));
                intent.putExtra(StoryViewActivity.EXTRA_STORY_IDS, new ArrayList<>(Collections.singletonList(finalStory.getUuid())));
                intent.putExtra(StoryViewActivity.EXTRA_MIME_TYPES, new ArrayList<>(Collections.singletonList(finalStory.getType())));
                intent.putExtra(StoryViewActivity.EXTRA_CONTACT, finalStory.getContact().asBareJid().toString());
                intent.putExtra(StoryViewActivity.EXTRA_ACCOUNT, message.getConversation().getAccount().getUuid());
                activity.startActivity(intent);
            });
        } else {
            // If no story is found, we MUST hide the preview to prevent recycling issues.
            viewHolder.storyPreview().setVisibility(View.GONE);
        }
    }

    /**
     * Renders the photos and videos of a message as one album, and returns whether it did. The
     * album replaces the single-file preview, so the message's own photo is the first tile, and
     * any documents of the same message are listed underneath it by the caller. A tile that is
     * not on the device yet says so instead of showing an empty frame — over an encrypted
     * transport the file only arrives once it has been fetched and decrypted.
     */
    private boolean displayAlbum(
            final BubbleMessageItemViewHolder viewHolder, final List<Message> photos) {
        final AlbumLayout album = viewHolder.album();
        album.removeAllViews();
        album.setVisibility(View.VISIBLE);
        viewHolder.image().setVisibility(GONE);
        viewHolder.downloadButton().setVisibility(GONE);
        viewHolder.audioPlayer().setVisibility(GONE);
        final int tiles = Math.min(photos.size(), AlbumLayout.MAX_TILES);
        final float radius = activity.getResources().getDimension(R.dimen.image_radius);
        final LayoutInflater inflater = LayoutInflater.from(activity);
        for (int i = 0; i < tiles; i++) {
            final Message photo = photos.get(i);
            final MaterialCardView tile =
                    (MaterialCardView) inflater.inflate(R.layout.item_album_tile, album, false);
            tile.setShapeAppearanceModel(AlbumLayout.shapeFor(i, tiles, radius));
            final ImageView image = tile.findViewById(R.id.album_image);
            final TextView label = tile.findViewById(R.id.album_label);
            final TextView badge = tile.findViewById(R.id.album_badge);
            final DownloadableFile file =
                    activity.xmppConnectionService.getFileBackend().getFile(photo);
            final boolean downloaded = file != null && file.exists() && file.canRead();
            final int runtime = photo.getFileParams() == null ? 0 : photo.getFileParams().runtime;
            if (isVideo(photo)) {
                if (runtime > 0) {
                    badge.setText(TimeFrameUtils.formatElapsedTime(runtime * 1000L, false));
                    badge.setVisibility(View.VISIBLE);
                } else {
                    badge.setText("");
                    badge.setVisibility(View.GONE);
                }
            } else {
                badge.setVisibility(GONE);
            }
            if (downloaded) {
                activity.loadBitmap(photo, image);
                label.setVisibility(GONE);
            } else {
                image.setImageDrawable(null);
                final long size = photo.getFileParams() == null ? 0 : photo.getFileParams().getSize();
                label.setText(size > 0 ? UIHelper.filesizeToString(size) : "");
                label.setCompoundDrawablesRelativeWithIntrinsicBounds(
                        0, R.drawable.ic_download_24dp, 0, 0);
                label.setVisibility(View.VISIBLE);
            }
            // The last tile stands in for every photo the album does not show.
            final int hidden = photos.size() - tiles;
            if (i == tiles - 1 && hidden > 0) {
                label.setText(activity.getString(R.string.album_more, hidden));
                label.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0);
                label.setBackgroundColor(
                        ContextCompat.getColor(activity, R.color.album_more_scrim));
                label.setTextColor(ContextCompat.getColor(activity, R.color.white));
                label.setVisibility(View.VISIBLE);
                tile.setContentDescription(
                        activity.getResources()
                                .getQuantityString(
                                        R.plurals.album_more_description, hidden, hidden));
            } else {
                label.setBackgroundColor(Color.TRANSPARENT);
                tile.setContentDescription(
                        UIHelper.getFileDescriptionString(activity, photo));
            }
            tile.setOnClickListener(
                    v -> {
                        if (downloaded) {
                            openDownloadable(photo);
                        } else {
                            ConversationFragment.downloadFile(activity, photo);
                        }
                    });
            tile.setOnLongClickListener(
                    v -> {
                        viewHolder.messageBox().performLongClick();
                        return true;
                    });
            album.addView(tile);
        }
        return true;
    }

    /**
     * The media type of a file, preferring what the sender declared (XEP-0446) over what its file
     * name suggests: an upload URL does not have to carry a telling extension, and the declared
     * type is the only thing available before the file has been downloaded.
     */
    private static String mimeOf(final Message message) {
        final Message.FileParams params = message.getFileParams();
        final String declared = params == null ? null : params.getMediaType();
        return Strings.isNullOrEmpty(declared) ? message.getMimeType() : declared;
    }

    /** Whether this message carries something an album is built from: a photo or a video. */
    private static boolean isVisualMedia(final Message message) {
        if (message.getType() == Message.TYPE_IMAGE) {
            return true;
        }
        final String mime = mimeOf(message);
        return mime != null && (mime.startsWith("image/") || mime.startsWith("video/"));
    }

    private static boolean isVideo(final Message message) {
        final String mime = mimeOf(message);
        return mime != null && mime.startsWith("video/");
    }

    /**
     * Renders a message that shares several files (XEP-0447). Photos and videos go into an album;
     * documents, audio and anything else are listed underneath it, because a document tile in a
     * photo grid says nothing about the document. Each file is a message row of its own, so
     * tapping any of them downloads or opens that single file with the very same machinery a
     * one-file message uses.
     */
    private void displayAttachments(
            final BubbleMessageItemViewHolder viewHolder, final Message message) {
        final LinearLayout container = viewHolder.attachments();
        container.removeAllViews();
        if (!message.hasAttachments()) {
            container.setVisibility(GONE);
            viewHolder.album().setVisibility(GONE);
            return;
        }
        final List<Message> media = new ArrayList<>();
        final List<Message> files = new ArrayList<>();
        for (final Message file : message.getFileMessages()) {
            (isVisualMedia(file) ? media : files).add(file);
        }
        // One photo is not an album: it keeps the full-width preview it gets on its own, and only
        // the remaining files are listed. Two or more take over the bubble as a grid, which also
        // moves the message's own file into the grid or into the list below it.
        final List<Message> listed;
        if (media.size() > 1) {
            displayAlbum(viewHolder, media);
            listed = files;
        } else {
            viewHolder.album().setVisibility(GONE);
            listed = message.getAttachments();
        }
        if (listed.isEmpty()) {
            container.setVisibility(GONE);
            return;
        }
        container.setVisibility(View.VISIBLE);
        final LayoutInflater inflater = LayoutInflater.from(activity);
        for (final Message attachment : listed) {
            final View row = inflater.inflate(R.layout.item_message_attachment, container, false);
            final ShapeableImageView thumbnail = row.findViewById(R.id.attachment_thumbnail);
            final TextView name = row.findViewById(R.id.attachment_name);
            final TextView details = row.findViewById(R.id.attachment_details);
            final Message.FileParams params = attachment.getFileParams();
            final String fileName = params == null ? null : params.getName();
            name.setText(
                    Strings.isNullOrEmpty(fileName)
                            ? UIHelper.getFileDescriptionString(activity, attachment)
                            : fileName);
            final DownloadableFile file =
                    activity.xmppConnectionService.getFileBackend().getFile(attachment);
            final boolean downloaded = file != null && file.exists() && file.canRead();
            final long size = params == null ? 0 : params.getSize();
            final String sizeText = size > 0 ? UIHelper.filesizeToString(size) : null;
            if (downloaded) {
                details.setText(sizeText == null ? "" : sizeText);
                details.setVisibility(sizeText == null ? GONE : View.VISIBLE);
            } else {
                final String action =
                        activity.getString(
                                R.string.download_x_file,
                                UIHelper.getFileDescriptionString(activity, attachment));
                details.setText(sizeText == null ? action : action + " · " + sizeText);
                details.setVisibility(View.VISIBLE);
            }
            if (downloaded && attachment.getType() == Message.TYPE_IMAGE) {
                activity.loadBitmap(attachment, thumbnail);
            } else {
                thumbnail.setImageResource(
                        MediaAdapter.getImageDrawable(Attachment.of(attachment)));
            }
            row.setOnClickListener(
                    v -> {
                        if (downloaded) {
                            openDownloadable(attachment);
                        } else {
                            ConversationFragment.downloadFile(activity, attachment);
                        }
                    });
            row.setOnLongClickListener(
                    v -> {
                        viewHolder.messageBox().performLongClick();
                        return true;
                    });
            container.addView(row);
        }
    }

    private void displayEmojiMessage(
            final BubbleMessageItemViewHolder viewHolder,
            final Message message,
            final BubbleColor bubbleColor) {
        displayTextMessage(viewHolder, message, bubbleColor);
        viewHolder.downloadButton().setVisibility(GONE);
        viewHolder.audioPlayer().setVisibility(GONE);
        viewHolder.image().setVisibility(GONE);
        viewHolder.messageBody().setTypeface(notoRegular);
        viewHolder.messageBody().setVisibility(View.VISIBLE);
        setTextColor(viewHolder.messageBody(), bubbleColor);
        viewHolder.messageBox().setBackgroundTintMode(PorterDuff.Mode.CLEAR);
        viewHolder.statusLine().setBackground(ContextCompat.getDrawable(activity, R.drawable.background_message_bubble));
        viewHolder.statusLine().setBackgroundTintList(bubbleToColorStateList(viewHolder.statusLine(), bubbleColor));
        if (viewHolder.username() != null) {
            viewHolder.username().setBackground(ContextCompat.getDrawable(activity, R.drawable.background_message_bubble));
            viewHolder.username().setBackgroundTintList(bubbleToColorStateList(viewHolder.username(), bubbleColor));
        }
        final var body = getSpannableBody(message);
        ImageSpan[] imageSpans = body.getSpans(0, body.length(), ImageSpan.class);
        float size = imageSpans.length == 1 || Emoticons.isEmoji(body.toString()) ? 5.0f : 2.0f;
        body.setSpan(
                new RelativeSizeSpan(size), 0, body.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        viewHolder.messageBody().setText(body);
    }

    private void applyQuoteSpan(
            final TextView textView,
            Editable body,
            int start,
            int end,
            final BubbleColor bubbleColor,
            final boolean makeEdits) {
        if (makeEdits && start > 1 && !"\n\n".equals(body.subSequence(start - 2, start).toString())) {
            body.insert(start++, "\n");
            body.setSpan(
                    new DividerSpan(false), start - 2, start, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            end++;
        }
        if (makeEdits && end < body.length() - 1 && !"\n\n".equals(body.subSequence(end, end + 2).toString())) {
            body.insert(end, "\n");
            body.setSpan(
                    new DividerSpan(false),
                    end,
                    end + ("\n".equals(body.subSequence(end + 1, end + 2).toString()) ? 2 : 1),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            );
        }
        final DisplayMetrics metrics = getContext().getResources().getDisplayMetrics();
        body.setSpan(
                new QuoteSpan(bubbleToOnSurfaceVariant(textView, bubbleColor), metrics),
                start,
                end,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    public void handleTextQuotes(final TextView textView, final Editable body) {
        handleTextQuotes(textView, body, true);
    }

    public void handleTextQuotes(final TextView textView, final Editable body, final boolean deleteMarkers) {
        final boolean colorfulBackground = this.bubbleDesign.colorfulChatBubbles;
        final BubbleColor bubbleColor = colorfulBackground ? (deleteMarkers ? BubbleColor.SECONDARY : BubbleColor.TERTIARY) : BubbleColor.SURFACE;
        handleTextQuotes(textView, body, bubbleColor, deleteMarkers);
    }

    /**
     * Applies QuoteSpan to group of lines which starts with > or » characters. Appends likebreaks
     * and applies DividerSpan to them to show a padding between quote and text.
     */
    public boolean handleTextQuotes(
            final TextView textView,
            final Editable body,
            final BubbleColor bubbleColor,
            final boolean deleteMarkers) {
        boolean startsWithQuote = false;
        int quoteDepth = 1;
        while (QuoteHelper.bodyContainsQuoteStart(body) && quoteDepth <= Config.QUOTE_MAX_DEPTH) {
            char previous = '\n';
            int lineStart = -1;
            int lineTextStart = -1;
            int quoteStart = -1;
            int skipped = 0;
            for (int i = 0; i <= body.length(); i++) {
                if (!deleteMarkers && QuoteHelper.isRelativeSizeSpanned(body, i)) {
                    skipped++;
                    continue;
                }
                char current = body.length() > i ? body.charAt(i) : '\n';
                if (lineStart == -1) {
                    if (previous == '\n') {
                        if (i < body.length() && QuoteHelper.isPositionQuoteStart(body, i)) {
                            // Line start with quote
                            lineStart = i;
                            if (quoteStart == -1) quoteStart = i - skipped;
                            if (i == 0) startsWithQuote = true;
                        } else if (quoteStart >= 0) {
                            // Line start without quote, apply spans there
                            applyQuoteSpan(textView, body, quoteStart, i - 1, bubbleColor, deleteMarkers);
                            quoteStart = -1;
                        }
                    }
                } else {
                    // Remove extra spaces between > and first character in the line
                    // > character will be removed too
                    if (current != ' ' && lineTextStart == -1) {
                        lineTextStart = i;
                    }
                    if (current == '\n') {
                        if (deleteMarkers) {
                            i -= lineTextStart - lineStart;
                            body.delete(lineStart, lineTextStart);
                            if (i == lineStart) {
                                // Avoid empty lines because span over empty line can be hidden
                                body.insert(i++, " ");
                            }
                        } else {
                            body.setSpan(new RelativeSizeSpan(i - (lineTextStart - lineStart) == lineStart ? 1 : 0), lineStart, lineTextStart, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE | StylingHelper.XHTML_REMOVE << Spanned.SPAN_USER_SHIFT);
                        }
                        lineStart = -1;
                        lineTextStart = -1;
                    }
                }
                previous = current;
                skipped = 0;
            }
            if (quoteStart >= 0) {
                // Apply spans to finishing open quote
                applyQuoteSpan(textView, body, quoteStart, body.length(), bubbleColor, deleteMarkers);
            }
            quoteDepth++;
        }
        return startsWithQuote;
    }

    private SpannableStringBuilder getSpannableBody(final Message message) {
        Drawable fallbackImg = ResourcesCompat.getDrawable(activity.getResources(), R.drawable.ic_photo_24dp, null);
        return message.getSpannableBody(new Thumbnailer(message), fallbackImg);
    }

    private void displayTextMessage(
            final BubbleMessageItemViewHolder viewHolder,
            final Message message,
            final BubbleColor bubbleColor) {
        if (message.getType() != Message.TYPE_STORY)
            viewHolder.storyPreview().setVisibility(View.GONE);
        viewHolder.inReplyToQuote().setVisibility(GONE);
        viewHolder.downloadButton().setVisibility(GONE);
        viewHolder.image().setVisibility(GONE);
        viewHolder.audioPlayer().setVisibility(GONE);
        viewHolder.messageBody().setVisibility(View.VISIBLE);
        viewHolder.inReplyToBox().setCardBackgroundColor(
                ContextCompat.getColor(activity, R.color.quote_box_scrim));
        setTextColor(viewHolder.messageBody(), bubbleColor);
        setTextSize(viewHolder.messageBody(), this.bubbleDesign.largeFont);
        setSmallTextSize(viewHolder.inReplyTo(), this.bubbleDesign.largeFont);
        setTextSize(viewHolder.inReplyToQuote(), this.bubbleDesign.largeFont);
        viewHolder.messageBody().setTypeface(notoRegular);
        viewHolder.messageBox().setBackgroundTintMode(PorterDuff.Mode.SRC);
        viewHolder.statusLine().setBackground(ContextCompat.getDrawable(activity, R.drawable.background_message_bubble));
        viewHolder.statusLine().setBackgroundTintList(bubbleToColorStateList(viewHolder.statusLine(), bubbleColor));
        if (viewHolder.username() != null) {
            viewHolder.username().setBackground(ContextCompat.getDrawable(activity, R.drawable.background_message_bubble));
            viewHolder.username().setBackgroundTintList(bubbleToColorStateList(viewHolder.username(), bubbleColor));
        }

        final ViewGroup.LayoutParams layoutParams = viewHolder.messageBody().getLayoutParams();
        layoutParams.width = ViewGroup.LayoutParams.WRAP_CONTENT;
        viewHolder.messageBody().setLayoutParams(layoutParams);
        // Reset any caption width cap left over from a recycled media bubble; media branches
        // (audio/file) re-apply a cap so the caption wraps to the width of the media above it.
        viewHolder.messageBody().setMaxWidth(Integer.MAX_VALUE);
        viewHolder.inReplyToQuote().setTextSize(
                TypedValue.COMPLEX_UNIT_SP, appSettings.isLargeFont() ? 18 : 14);
        final ViewGroup.LayoutParams qlayoutParams = viewHolder.inReplyToQuote().getLayoutParams();
        qlayoutParams.width = ViewGroup.LayoutParams.WRAP_CONTENT;
        viewHolder.inReplyToQuote().setLayoutParams(qlayoutParams);
        // Wrap the reply quote at a fixed width (the same screen-based reference the body uses),
        // independent of the bubble's per-pass available width, so a multi-line quote always wraps
        // the same way and the quote card stops shrinking/expanding between measure passes.
        final int quoteMaxWidth = Math.max(
                1, (int) (activity.getResources().getDisplayMetrics().widthPixels - (120 * density)));
        viewHolder.inReplyTo().setMaxWidth(quoteMaxWidth);
        viewHolder.inReplyToQuote().setMaxWidth(quoteMaxWidth);

        final var rawBody = message.getBody();
        if (Strings.isNullOrEmpty(rawBody)) {
            viewHolder.messageBody().setEllipsize(null);
            viewHolder.messageBody().setMaxLines(Integer.MAX_VALUE);
            viewHolder.showMore().setVisibility(GONE);
            viewHolder.messageBody().setText("");
            viewHolder.messageBody().setTextIsSelectable(false);
            toggleWhisperInfo(viewHolder, message, bubbleColor);
            return;
        }
        viewHolder.messageBody().setTextIsSelectable(true);
        final String nick = UIHelper.getMessageDisplayName(message);
        SpannableStringBuilder body = getSpannableBody(message);
        final var processMarkup = body.getSpans(0, body.length(), Message.PlainTextSpan.class).length > 0;
        if (body.length() > Config.MAX_DISPLAY_MESSAGE_CHARS) {
            body = new SpannableStringBuilder(body, 0, Config.MAX_DISPLAY_MESSAGE_CHARS);
            body.append("…");
        }
        if (processMarkup)
            StylingHelper.format(body, viewHolder.messageBody().getCurrentTextColor());
        MyLinkify.addLinks(body, message.getConversation().getAccount(), message.getConversation().getJid(), activity.xmppConnectionService);
        boolean startsWithQuote = processMarkup && handleTextQuotes(viewHolder.messageBody(), body, bubbleColor, true);
        for (final android.text.style.QuoteSpan quote : body.getSpans(0, body.length(), android.text.style.QuoteSpan.class)) {
            int start = body.getSpanStart(quote);
            int end = body.getSpanEnd(quote);
            if (start < 0 || end < 0) continue;

            body.removeSpan(quote);
            applyQuoteSpan(viewHolder.messageBody(), body, start, end, bubbleColor, true);
            if (start == 0) {
                if (message.getInReplyTo() == null) {
                    startsWithQuote = true;
                } else {
                    viewHolder.inReplyToQuote().setText(body.subSequence(start, end));
                    viewHolder.inReplyToQuote().setVisibility(View.VISIBLE);
                    viewHolder.inReplyToBox().setVisibility(View.VISIBLE);
                    body.delete(start, end);
                    while (body.length() > start && body.charAt(start) == '\n')
                        body.delete(start, 1); // Newlines after quote
                }
            }
        }
        boolean hasMeCommand = body.toString().startsWith(Message.ME_COMMAND);
        if (hasMeCommand) {
            body.replace(0, Message.ME_COMMAND.length(), String.format("%s ", nick));
        }
        if (!message.isPrivateMessage()) {
            if (hasMeCommand && body.length() > nick.length()) {
                body.setSpan(
                        new StyleSpan(Typeface.BOLD_ITALIC),
                        0,
                        nick.length(),
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        } else {
            String privateMarker;
            if (message.getStatus() <= Message.STATUS_RECEIVED) {
                privateMarker = activity.getString(R.string.private_message);
            } else {
                Jid cp = message.getCounterpart();
                privateMarker =
                        activity.getString(
                                R.string.private_message_to,
                                Strings.nullToEmpty(cp == null ? null : cp.getResource()));
            }
            body.insert(0, privateMarker);
            int privateMarkerIndex = privateMarker.length();
            if (startsWithQuote) {
                body.insert(privateMarkerIndex, "\n\n");
                body.setSpan(
                        new DividerSpan(false),
                        privateMarkerIndex,
                        privateMarkerIndex + 2,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else {
                body.insert(privateMarkerIndex, " ");
            }
            body.setSpan(
                    new ForegroundColorSpan(
                            bubbleToOnSurfaceVariant(viewHolder.messageBody(), bubbleColor)),
                    0,
                    privateMarkerIndex,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            body.setSpan(
                    new StyleSpan(Typeface.BOLD),
                    0,
                    privateMarkerIndex,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (hasMeCommand) {
                body.setSpan(
                        new StyleSpan(Typeface.BOLD_ITALIC),
                        privateMarkerIndex + 1,
                        privateMarkerIndex + 1 + nick.length(),
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        if (message.getConversation().getMode() == Conversation.MODE_MULTI
                && message.getStatus() == Message.STATUS_RECEIVED) {
            if (message.getConversation() instanceof Conversation conversation) {
                Pattern pattern =
                        NotificationService.generateNickHighlightPattern(
                                conversation.getMucOptions().getActualNick());
                Matcher matcher = pattern.matcher(body);
                while (matcher.find()) {
                    body.setSpan(
                            new StyleSpan(Typeface.BOLD),
                            matcher.start(),
                            matcher.end(),
                            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
        }

        for (final var emoji : EmojiManager.extractEmojisInOrderWithIndex(body.toString())) {
            var end = emoji.getCharIndex() + emoji.getEmoji().getEmoji().length();
            if (body.length() > end && body.charAt(end) == '\uFE0F') end++;
            body.setSpan(
                    new RelativeSizeSpan(1.2f),
                    emoji.getCharIndex(),
                    end,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        // Make custom emoji bigger too, to match emoji
        for (final var span : body.getSpans(0, body.length(), InlineImageSpan.class)) {
            body.setSpan(
                    new RelativeSizeSpan(2.0f),
                    body.getSpanStart(span),
                    body.getSpanEnd(span),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        if (highlightedTerm != null) {
            StylingHelper.highlight(viewHolder.messageBody(), body, highlightedTerm);
        }

        viewHolder.messageBody().setAutoLinkMask(0);

        if (activity.xmppConnectionService.getBooleanPreference("set_text_collapsable", R.bool.set_text_collapsable)) {
            final DisplayMetrics currentMetrics = activity.getResources().getDisplayMetrics();
            final int maxWidth = Math.max(1, (int) (currentMetrics.widthPixels - (120 * density)));
            final StaticLayout staticLayout;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                staticLayout = StaticLayout.Builder.obtain(body, 0, body.length(), viewHolder.messageBody().getPaint(), maxWidth)
                        .setLineSpacing(viewHolder.messageBody().getLineSpacingExtra(), viewHolder.messageBody().getLineSpacingMultiplier())
                        .setIncludePad(viewHolder.messageBody().getIncludeFontPadding())
                        .setBreakStrategy(viewHolder.messageBody().getBreakStrategy())
                        .setHyphenationFrequency(viewHolder.messageBody().getHyphenationFrequency())
                        .build();
            } else {
                staticLayout = new StaticLayout(body, viewHolder.messageBody().getPaint(), maxWidth, Layout.Alignment.ALIGN_NORMAL,
                        viewHolder.messageBody().getLineSpacingMultiplier(), viewHolder.messageBody().getLineSpacingExtra(),
                        viewHolder.messageBody().getIncludeFontPadding());
            }

            final boolean isLong = staticLayout.getLineCount() > 10;

            if (!isLong) {
                viewHolder.messageBody().setEllipsize(null);
                viewHolder.messageBody().setMaxLines(Integer.MAX_VALUE);
                viewHolder.showMore().setVisibility(View.GONE);
                viewHolder.showMore().setOnClickListener(null);
            } else {
                if (message.isExpanded()) {
                    viewHolder.messageBody().setEllipsize(null);
                    viewHolder.messageBody().setMaxLines(Integer.MAX_VALUE);
                    viewHolder.showMore().setText(R.string.show_less);
                } else {
                    viewHolder.messageBody().setEllipsize(TextUtils.TruncateAt.END);
                    viewHolder.messageBody().setMaxLines(10);
                    viewHolder.showMore().setText(R.string.show_more);
                }
                viewHolder.showMore().setVisibility(View.VISIBLE);

                viewHolder.showMore().setOnClickListener(v -> {
                    android.transition.TransitionSet set = new android.transition.TransitionSet();
                    set.addTransition(new android.transition.ChangeBounds());
                    set.addTransition(new android.transition.Fade());
                    set.setOrdering(android.transition.TransitionSet.ORDERING_TOGETHER);
                    set.setDuration(300);
                    android.transition.TransitionManager.beginDelayedTransition(viewHolder.messageBox(), set);

                    message.setExpanded(!message.isExpanded());
                    if (message.isExpanded()) {
                        viewHolder.messageBody().setEllipsize(null);
                        viewHolder.messageBody().setMaxLines(Integer.MAX_VALUE);
                        viewHolder.showMore().setText(R.string.show_less);
                    } else {
                        viewHolder.messageBody().setEllipsize(TextUtils.TruncateAt.END);
                        viewHolder.messageBody().setMaxLines(10);
                        viewHolder.showMore().setText(R.string.show_more);
                    }
                });
            }
        } else {
            viewHolder.messageBody().setEllipsize(null);
            viewHolder.messageBody().setMaxLines(Integer.MAX_VALUE);
            viewHolder.showMore().setVisibility(GONE);
        }

        viewHolder.messageBody().setText(body);

        if (body.length() <= 0) viewHolder.messageBody().setVisibility(GONE);
        BetterLinkMovementMethod method = getBetterLinkMovementMethod();
        viewHolder.messageBody().setMovementMethod(method);
    }

    private final BetterLinkMovementMethod mBetterLinkMovementMethod = new BetterLinkMovementMethod() {
        @Override
        protected void dispatchUrlLongClick(TextView tv, ClickableSpan span) {
            if (span instanceof URLSpan || mOnInlineImageLongClickedListener == null) {
                tv.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_CANCEL, 0f, 0f, 0));
                super.dispatchUrlLongClick(tv, span);
                return;
            }

            Spannable body = (Spannable) tv.getText();
            ImageSpan[] imageSpans = body.getSpans(body.getSpanStart(span), body.getSpanEnd(span), ImageSpan.class);
            if (imageSpans.length > 0) {
                Uri uri = Uri.parse(imageSpans[0].getSource());
                Cid cid = BobTransfer.cid(uri);
                if (cid == null) return;
                if (mOnInlineImageLongClickedListener.onInlineImageLongClicked(cid)) {
                    tv.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_CANCEL, 0f, 0f, 0));
                }
            }
        }
    };

    private BetterLinkMovementMethod getBetterLinkMovementMethod() {
        mBetterLinkMovementMethod.setOnLinkLongClickListener((tv, url) -> {
            tv.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_CANCEL, 0f, 0f, 0));
            ShareUtil.copyLinkToClipboard(activity, url);
            return true;
        });
        return mBetterLinkMovementMethod;
    }

    private void displayDownloadableMessage(
            final BubbleMessageItemViewHolder viewHolder,
            final Message message,
            final String text,
            final BubbleColor bubbleColor) {
        displayTextMessage(viewHolder, message, bubbleColor);
        viewHolder.image().setVisibility(GONE);
        List<Element> thumbs = message.getFileParams() != null ? message.getFileParams().getThumbnails() : null;
        if (thumbs != null && !thumbs.isEmpty()) {
            for (Element thumb : thumbs) {
                Uri uri = Uri.parse(thumb.getAttribute("uri"));
                if (Objects.equals(uri.getScheme(), "data")) {
                    String[] parts = uri.getSchemeSpecificPart().split(",", 2);
                    parts = parts[0].split(";");
                    if (!parts[0].equals("image/blurhash") && !parts[0].equals("image/thumbhash") && !parts[0].equals("image/jpeg") && !parts[0].equals("image/png") && !parts[0].equals("image/webp") && !parts[0].equals("image/gif"))
                        continue;
                } else if (Objects.equals(uri.getScheme(), "cid")) {
                    Cid cid = BobTransfer.cid(uri);
                    if (cid == null) continue;
                    DownloadableFile f = activity.xmppConnectionService.getFileForCid(cid);
                    if (f == null || !f.canRead()) {
                        if (!message.trusted() && !message.getConversation().canInferPresence())
                            continue;
                        if (message.getEncryption() != Message.ENCRYPTION_NONE)
                            continue;

                        try {
                            new BobTransfer(BobTransfer.uri(cid), message.getConversation().getAccount(), message.getCounterpart(), activity.xmppConnectionService).start();
                        } catch (final NoSuchAlgorithmException | URISyntaxException ignored) {
                        }
                        continue;
                    }
                } else {
                    continue;
                }

                int width = message.getFileParams().width;
                if (width < 1 && thumb.getAttribute("width") != null)
                    width = Integer.parseInt(thumb.getAttribute("width"));
                if (width < 1) width = 1920;

                int height = message.getFileParams().height;
                if (height < 1 && thumb.getAttribute("height") != null)
                    height = Integer.parseInt(thumb.getAttribute("height"));
                if (height < 1) height = 1080;

                viewHolder.image().setVisibility(View.VISIBLE);
                imagePreviewLayout(width, height, viewHolder.image(), message.getInReplyTo() != null, true, viewHolder);
                activity.loadBitmap(message, viewHolder.image());
                viewHolder.image().setOnClickListener(v -> ConversationFragment.downloadFile(activity, message));
                viewHolder.image().setOnLongClickListener(v -> { viewHolder.messageBox().performLongClick(); return true; });

                break;
            }
        }
        viewHolder.statusLine().setBackground(ContextCompat.getDrawable(activity, R.drawable.background_message_bubble));
        viewHolder.statusLine().setBackgroundTintList(bubbleToColorStateList(viewHolder.statusLine(), bubbleColor));
        if (viewHolder.username() != null) {
            viewHolder.username().setBackground(ContextCompat.getDrawable(activity, R.drawable.background_message_bubble));
            viewHolder.username().setBackgroundTintList(bubbleToColorStateList(viewHolder.username(), bubbleColor));
        }
        viewHolder.audioPlayer().setVisibility(GONE);
        viewHolder.downloadButton().setVisibility(View.VISIBLE);
        viewHolder.downloadButton().setText(text);
        final var attachment = Attachment.of(message);
        final @DrawableRes int imageResource = MediaAdapter.getImageDrawable(attachment);
        viewHolder.downloadButton().setIconResource(imageResource);
        viewHolder
                .downloadButton()
                .setOnClickListener(v -> ConversationFragment.downloadFile(activity, message));
        viewHolder.downloadButton().setOnLongClickListener(v -> { viewHolder.messageBox().performLongClick(); return true; });
    }

    private void displayWebxdcMessage(BubbleMessageItemViewHolder viewHolder, final Message message, final BubbleColor bubbleColor) {
        Cid webxdcCid = message.getFileParams().getCids().get(0);
        WebxdcPage webxdc = new WebxdcPage(activity, webxdcCid, message);
        displayTextMessage(viewHolder, message, bubbleColor);
        viewHolder.image().setVisibility(GONE);
        viewHolder.statusLine().setBackground(ContextCompat.getDrawable(activity, R.drawable.background_message_bubble));
        viewHolder.statusLine().setBackgroundTintList(bubbleToColorStateList(viewHolder.statusLine(), bubbleColor));
        if (viewHolder.username() != null) {
            viewHolder.username().setBackground(ContextCompat.getDrawable(activity, R.drawable.background_message_bubble));
            viewHolder.username().setBackgroundTintList(bubbleToColorStateList(viewHolder.username(), bubbleColor));
        }
        viewHolder.audioPlayer().setVisibility(GONE);
        viewHolder.downloadButton().setVisibility(View.VISIBLE);
        viewHolder.downloadButton().setIconResource(0);
        viewHolder.downloadButton().setText(activity.getString(R.string.open) + " " + webxdc.getName());
        viewHolder.downloadButton().setOnClickListener(v -> {
            Conversation conversation = (Conversation) message.getConversation();
            if (!conversation.switchToSession("webxdc\0" + message.getUuid())) {
                conversation.startWebxdc(webxdc);
            }
        });
        viewHolder.downloadButton().setOnLongClickListener(v -> { viewHolder.messageBox().performLongClick(); return true; });
        viewHolder.image().setOnClickListener(v -> {
            Conversation conversation = (Conversation) message.getConversation();
            if (!conversation.switchToSession("webxdc\0" + message.getUuid())) {
                conversation.startWebxdc(webxdc);
            }
        });
        viewHolder.image().setOnLongClickListener(v -> { viewHolder.messageBox().performLongClick(); return true; });

        final WebxdcUpdate lastUpdate;
        synchronized (lastWebxdcUpdate) {
            lastUpdate = lastWebxdcUpdate.get(message.getUuid());
        }
        if (lastUpdate == null) {
            new Thread(() -> {
                final WebxdcUpdate update = activity.xmppConnectionService.findLastWebxdcUpdate(message);
                if (update != null) {
                    synchronized (lastWebxdcUpdate) {
                        lastWebxdcUpdate.put(message.getUuid(), update);
                    }
                    activity.xmppConnectionService.updateConversationUi();
                }
            }).start();
        } else {
            if (lastUpdate.getSummary() != null || lastUpdate.getDocument() != null) {
                viewHolder.messageBody().setVisibility(View.VISIBLE);
                viewHolder.messageBody().setText(
                        (lastUpdate.getDocument() == null ? "" : lastUpdate.getDocument() + "\n") +
                                (lastUpdate.getSummary() == null ? "" : lastUpdate.getSummary())
                );
            }
        }

        final LruCache<String, Drawable> cache = activity.xmppConnectionService.getDrawableCache();
        final Drawable d = cache.get("webxdc:icon:" + webxdcCid);
        if (d == null) {
            new Thread(() -> {
                Drawable icon = webxdc.getIcon();
                if (icon != null) {
                    cache.put("webxdc:icon:" + webxdcCid, icon);
                    activity.xmppConnectionService.updateConversationUi();
                }
            }).start();
        } else {
            viewHolder.image().setVisibility(View.VISIBLE);
            viewHolder.image().setImageDrawable(d);
            imagePreviewLayout(d.getIntrinsicWidth(), d.getIntrinsicHeight(), viewHolder.image(), message.getInReplyTo() != null, true, viewHolder);
        }
    }

    private void displayOpenableMessage(
            final BubbleMessageItemViewHolder viewHolder,
            final Message message,
            final BubbleColor bubbleColor) {
        displayTextMessage(viewHolder, message, bubbleColor);
        viewHolder.image().setVisibility(GONE);
        viewHolder.audioPlayer().setVisibility(GONE);
        viewHolder.downloadButton().setVisibility(View.VISIBLE);
        viewHolder.statusLine().setBackground(ContextCompat.getDrawable(activity, R.drawable.background_message_bubble));
        viewHolder.statusLine().setBackgroundTintList(bubbleToColorStateList(viewHolder.statusLine(), bubbleColor));
        if (viewHolder.username() != null) {
            viewHolder.username().setBackground(ContextCompat.getDrawable(activity, R.drawable.background_message_bubble));
            viewHolder.username().setBackgroundTintList(bubbleToColorStateList(viewHolder.username(), bubbleColor));
        }
        viewHolder
                .downloadButton()
                .setText(
                        activity.getString(
                                R.string.open_x_file,
                                UIHelper.getFileDescriptionString(activity, message)));
        final var attachment = Attachment.of(message);
        final @DrawableRes int imageResource = MediaAdapter.getImageDrawable(attachment);
        viewHolder.downloadButton().setIconResource(imageResource);
        viewHolder.downloadButton().setOnClickListener(v -> openDownloadable(message));
        viewHolder.downloadButton().setOnLongClickListener(v -> { viewHolder.messageBox().performLongClick(); return true; });
        constrainCaptionWidth(
                viewHolder, (int) activity.getResources().getDimension(R.dimen.image_preview_width));
    }

    private void displayURIMessage(
            BubbleMessageItemViewHolder viewHolder, final Message message, final BubbleColor bubbleColor) {
        displayTextMessage(viewHolder, message, bubbleColor);
        viewHolder.messageBody().setVisibility(View.GONE);
        viewHolder.image().setVisibility(View.GONE);
        viewHolder.audioPlayer().setVisibility(View.GONE);
        viewHolder.downloadButton().setVisibility(View.VISIBLE);
        viewHolder.statusLine().setBackground(ContextCompat.getDrawable(activity, R.drawable.background_message_bubble));
        viewHolder.statusLine().setBackgroundTintList(bubbleToColorStateList(viewHolder.statusLine(), bubbleColor));
        if (viewHolder.username() != null) {
            viewHolder.username().setBackground(ContextCompat.getDrawable(activity, R.drawable.background_message_bubble));
            viewHolder.username().setBackgroundTintList(bubbleToColorStateList(viewHolder.username(), bubbleColor));
        }
        final var uri = message.wholeIsKnownURI();
        if ("bitcoin".equals(uri.getScheme())) {
            final var amount = uri.getQueryParameter("amount");
            final var formattedAmount = amount == null || amount.isEmpty() ? "" : amount + " ";
            viewHolder.downloadButton().setIconResource(R.drawable.bitcoin_24dp);
            viewHolder.downloadButton().setText("Send " + formattedAmount + "Bitcoin");
        } else if ("bitcoincash".equals(uri.getScheme())) {
            final var amount = uri.getQueryParameter("amount");
            final var formattedAmount = amount == null || amount.isEmpty() ? "" : amount + " ";
            viewHolder.downloadButton().setIconResource(R.drawable.bitcoin_cash_24dp);
            viewHolder.downloadButton().setText("Send " + formattedAmount + "Bitcoin Cash");
        } else if ("ethereum".equals(uri.getScheme())) {
            final var amount = uri.getQueryParameter("value");
            final var formattedAmount = amount == null || amount.isEmpty() ? "" : amount + " ";
            viewHolder.downloadButton().setIconResource(R.drawable.eth_24dp);
            viewHolder.downloadButton().setText("Send " + formattedAmount + "via Ethereum");
        } else if ("monero".equals(uri.getScheme())) {
            final var amount = uri.getQueryParameter("tx_amount");
            final var formattedAmount = amount == null || amount.isEmpty() ? "" : amount + " ";
            viewHolder.downloadButton().setIconResource(R.drawable.monero_24dp);
            viewHolder.downloadButton().setText("Send " + formattedAmount + "Monero");
        } else if ("wownero".equals(uri.getScheme())) {
            final var amount = uri.getQueryParameter("tx_amount");
            final var formattedAmount = amount == null || amount.isEmpty() ? "" : amount + " ";
            viewHolder.downloadButton().setIconResource(R.drawable.wownero_24dp);
            viewHolder.downloadButton().setText("Send " + formattedAmount + "Wownero");
        } else if ("taler".equals(uri.getScheme())) {
            final var amount = uri.getQueryParameter("amount");
            final var formattedAmount = amount == null || amount.isEmpty() ? "" : amount + " ";
            viewHolder.downloadButton().setIconResource(R.drawable.taler_icon_24dp);
            viewHolder.downloadButton().setText("Send " + formattedAmount + "Taler");
        }
        viewHolder.downloadButton().setOnClickListener(v -> new FixedURLSpan(message.getRawBody()).onClick(v));
        viewHolder.downloadButton().setOnLongClickListener(v -> { viewHolder.messageBox().performLongClick(); return true; });
    }

    private void displayLocationMessage(
            final BubbleMessageItemViewHolder viewHolder, final Message message, final BubbleColor bubbleColor) {
        displayTextMessage(viewHolder, message, bubbleColor);

        final eu.siacs.conversations.utils.LiveLocationManager.IncomingSession liveSession =
                eu.siacs.conversations.utils.LiveLocationManager.getInstance().getSessionForMessage(message.getUuid());
        final boolean isActiveLive = liveSession != null ||
                eu.siacs.conversations.utils.LiveLocationManager.getInstance().isActiveLiveLocationMessage(message.getUuid()) ||
                (message.getStatus() == Message.STATUS_RECEIVED && isLiveLocationPayloadActive(message));

        String freshUrl;
        if (liveSession != null) {
            freshUrl = GeoHelper.MapPreviewUriFromCoords(liveSession.latitude, liveSession.longitude, activity);
        } else {
            final Element el = getLiveLocationElement(message);
            if (el != null && el.getAttribute("last_lat") != null && el.getAttribute("last_lon") != null) {
                try {
                    double lat = Double.parseDouble(el.getAttribute("last_lat"));
                    double lon = Double.parseDouble(el.getAttribute("last_lon"));
                    freshUrl = GeoHelper.MapPreviewUriFromCoords(lat, lon, activity);
                } catch (Exception ignored) {
                    freshUrl = GeoHelper.MapPreviewUri(message, activity);
                }
            } else {
                freshUrl = GeoHelper.MapPreviewUri(message, activity);
            }
        }

        final String url;
        final String liveKey = message.getUuid();
        if (isActiveLive) {
            final long now = System.currentTimeMillis();
            final Long lastRefresh = liveLocationPreviewTime.get(liveKey);
            final String cachedUrl = liveLocationPreviewUrl.get(liveKey);
            if (cachedUrl != null && lastRefresh != null
                    && now - lastRefresh < LIVE_LOCATION_PREVIEW_REFRESH_MS) {
                url = cachedUrl;
            } else {
                url = freshUrl;
                liveLocationPreviewUrl.put(liveKey, freshUrl);
                liveLocationPreviewTime.put(liveKey, now);
            }
        } else {
            url = freshUrl;
            liveLocationPreviewUrl.remove(liveKey);
            liveLocationPreviewTime.remove(liveKey);
        }

        viewHolder.audioPlayer().setVisibility(GONE);
        if (message.isGeoUri() && viewHolder.messageBody().getVisibility() == GONE) {
            viewHolder.messageBox().setBackgroundTintMode(PorterDuff.Mode.CLEAR);
            viewHolder.statusLine().setBackground(ContextCompat.getDrawable(activity, R.drawable.background_message_bubble));
            viewHolder.statusLine().setBackgroundTintList(bubbleToColorStateList(viewHolder.statusLine(), bubbleColor));
            // Do not setBackground()/setBackgroundTintList() on the quote box: it is a
            // MaterialCardView, which mishandles a custom background and ends up showing no fill on
            // some replies. Leave it to its cardBackgroundColor (surfaceVariant) like text replies.
            // Leave the quote text transparent so the quote card's own background (surfaceVariant)
            // shows through. Tinting it with the bubble colour made the quote text read as the
            // message-bubble background instead of the card.
            viewHolder.inReplyToQuote().setBackground(null);
            if (viewHolder.username() != null) {
                viewHolder.username().setBackground(ContextCompat.getDrawable(activity, R.drawable.background_message_bubble));
                viewHolder.username().setBackgroundTintList(bubbleToColorStateList(viewHolder.statusLine(), bubbleColor));
            }
        }
        if (activity.xmppConnectionService != null && activity.xmppConnectionService.getBooleanPreference("show_maps_inside", R.bool.show_maps_inside)) {
            Glide.with(activity)
                    .load(Uri.parse(url))
                    .placeholder(R.drawable.marker)
                    .error(R.drawable.marker)
                    .into(viewHolder.image());
            viewHolder.image().setVisibility(View.VISIBLE);
            imagePreviewLayout(540, 540, viewHolder.image(), message.getInReplyTo() != null, true, viewHolder);
            viewHolder.image().setOnClickListener(v -> showLocation(message));
            viewHolder.image().setOnLongClickListener(v -> { viewHolder.messageBox().performLongClick(); return true; });
            if (isActiveLive) {
                viewHolder.downloadButton().setVisibility(View.VISIBLE);
                viewHolder.downloadButton().setText(R.string.live_location_active);
                setLiveLocationButtonIcon(viewHolder.downloadButton(), true);
                viewHolder.downloadButton().setOnClickListener(v -> showLocation(message));
                viewHolder.downloadButton().setOnLongClickListener(v -> { viewHolder.messageBox().performLongClick(); return true; });
            } else if (getLiveLocationElement(message) != null) {
                viewHolder.downloadButton().setVisibility(View.VISIBLE);
                viewHolder.downloadButton().setText(R.string.live_location);
                setLiveLocationButtonIcon(viewHolder.downloadButton(), false);
                viewHolder.downloadButton().setOnClickListener(v -> showLocation(message));
                viewHolder.downloadButton().setOnLongClickListener(v -> { viewHolder.messageBox().performLongClick(); return true; });
            } else {
                viewHolder.downloadButton().setVisibility(GONE);
            }
        } else {
            viewHolder.image().setVisibility(GONE);
            viewHolder.downloadButton().setVisibility(View.VISIBLE);
            if (isActiveLive) {
                viewHolder.downloadButton().setText(R.string.live_location_active);
                setLiveLocationButtonIcon(viewHolder.downloadButton(), true);
            } else if (getLiveLocationElement(message) != null) {
                viewHolder.downloadButton().setText(R.string.live_location);
                setLiveLocationButtonIcon(viewHolder.downloadButton(), false);
            } else {
                viewHolder.downloadButton().setText(R.string.show_location);
                final var attachment = Attachment.of(message);
                viewHolder.downloadButton().setIconResource(MediaAdapter.getImageDrawable(attachment));
            }
            viewHolder.downloadButton().setOnClickListener(v -> showLocation(message));
            viewHolder.downloadButton().setOnLongClickListener(v -> { viewHolder.messageBox().performLongClick(); return true; });
        }
    }

    private void displayAudioMessage(
            final BubbleMessageItemViewHolder viewHolder,
            Message message,
            final BubbleColor bubbleColor) {
        displayTextMessage(viewHolder, message, bubbleColor);
        viewHolder.image().setVisibility(View.GONE);
        viewHolder.downloadButton().setVisibility(View.GONE);
        viewHolder.statusLine().setBackground(ContextCompat.getDrawable(activity, R.drawable.background_message_bubble));
        viewHolder.statusLine().setBackgroundTintList(bubbleToColorStateList(viewHolder.statusLine(), bubbleColor));
        if (viewHolder.username() != null) {
            viewHolder.username().setBackground(ContextCompat.getDrawable(activity, R.drawable.background_message_bubble));
            viewHolder.username().setBackgroundTintList(bubbleToColorStateList(viewHolder.username(), bubbleColor));
        }
        final RelativeLayout audioPlayer = viewHolder.audioPlayer();
        audioPlayer.setVisibility(View.VISIBLE);
        AudioPlayer.ViewHolder.get(audioPlayer).setBubbleColor(bubbleColor);
        this.audioPlayer.init(audioPlayer, message);
        audioPlayer.setOnLongClickListener(v -> { viewHolder.messageBox().performLongClick(); return true; });
        constrainCaptionWidth(
                viewHolder, (int) activity.getResources().getDimension(R.dimen.audio_player_width));
    }

    /**
     * Constrain a media caption so it wraps to roughly the width of the media above it instead of
     * stretching the bubble wider than the image/audio/file. Images and videos already get their
     * caption matched to the preview width in {@link #imagePreviewLayout}; this covers the
     * audio-player and file-download rows whose caption would otherwise be {@code wrap_content}.
     */
    private void constrainCaptionWidth(
            final BubbleMessageItemViewHolder viewHolder, final int maxWidthPx) {
        if (maxWidthPx <= 0 || viewHolder.messageBody().getVisibility() == GONE) {
            return;
        }
        // Keep a readable minimum so a long caption on small media doesn't wrap to a sliver.
        final int floorPx = (int) (140 * this.density);
        viewHolder.messageBody().setMaxWidth(Math.max(maxWidthPx, floorPx));
    }

    private void displayMediaPreviewMessage(
            final BubbleMessageItemViewHolder viewHolder,
            final Message message,
            final BubbleColor bubbleColor) {
        displayTextMessage(viewHolder, message, bubbleColor);
        viewHolder.downloadButton().setVisibility(View.GONE);
        viewHolder.audioPlayer().setVisibility(View.GONE);
        viewHolder.image().setVisibility(View.VISIBLE);
        if (message.isFileOrImage() && viewHolder.messageBody().getVisibility() == GONE) {
            viewHolder.messageBox().setBackgroundTintMode(PorterDuff.Mode.CLEAR);
            viewHolder.statusLine().setBackground(ContextCompat.getDrawable(activity, R.drawable.background_message_bubble));
            viewHolder.statusLine().setBackgroundTintList(bubbleToColorStateList(viewHolder.statusLine(), bubbleColor));
            // Do not setBackground()/setBackgroundTintList() on the quote box: it is a
            // MaterialCardView, which mishandles a custom background and ends up showing no fill on
            // some replies. Leave it to its cardBackgroundColor (surfaceVariant) like text replies.
            // Leave the quote text transparent so the quote card's own background (surfaceVariant)
            // shows through. Tinting it with the bubble colour made the quote text read as the
            // message-bubble background instead of the card.
            viewHolder.inReplyToQuote().setBackground(null);
            if (message.getInReplyTo() != null) {
                viewHolder.inReplyToBox().setCardBackgroundColor(
                        ColorUtils.compositeColors(
                                ContextCompat.getColor(activity, R.color.quote_box_scrim),
                                bubbleToColorStateList(viewHolder.inReplyToBox(), bubbleColor)
                                        .getDefaultColor()));
            }
            if (viewHolder.username() != null) {
                viewHolder.username().setBackground(ContextCompat.getDrawable(activity, R.drawable.background_message_bubble));
                viewHolder.username().setBackgroundTintList(bubbleToColorStateList(viewHolder.statusLine(), bubbleColor));
            }
        }
        final FileParams params = message.getFileParams();
        imagePreviewLayout(params.width, params.height, viewHolder.image(), message.getInReplyTo() != null, viewHolder.messageBody().getVisibility() != GONE, viewHolder);
        activity.loadBitmap(message, viewHolder.image());
        viewHolder.image().setOnClickListener(v -> openDownloadable(message));
        viewHolder.image().setOnLongClickListener(v -> { viewHolder.messageBox().performLongClick(); return true; });
    }

    private void imagePreviewLayout(int w, int h, ShapeableImageView image, boolean otherAbove, boolean otherBelow, BubbleMessageItemViewHolder viewHolder) {
        // metrics.density is used multiple times, cache it locally or make it a member if metrics is stable.
        // Assuming 'density' is now a member variable 'this.density' initialized in constructor.

        final int scaledW;
        final int scaledH;

        // Use the pre-fetched imagePreviewWidthTarget
        if (Math.max(h, w) * this.density <= this.imagePreviewWidthTarget) {
            scaledW = (int) (w * this.density);
            scaledH = (int) (h * this.density);
        } else if (Math.max(h, w) <= this.imagePreviewWidthTarget) {
            scaledW = w;
            scaledH = h;
        } else if (w <= h) {
            scaledW = (int) (w / ((double) h / this.imagePreviewWidthTarget));
            scaledH = (int) this.imagePreviewWidthTarget;
        } else {
            scaledW = (int) this.imagePreviewWidthTarget;
            scaledH = (int) (h / ((double) w / this.imagePreviewWidthTarget));
        }

        // When a caption is shown under a narrow image, the caption gets capped to the image width
        // (so the image stays borderless) — but for a very small image that makes the caption an
        // unreadably narrow column. Scale such an image up to a minimum preview width so the image
        // still fills the bubble AND the caption has room.
        int imageW = scaledW;
        int imageH = scaledH;
        if (otherBelow && imageW > 0) {
            final int minCaptionedWidth = (int) this.targetImageWidthLargeThreshold;
            if (imageW < minCaptionedWidth) {
                imageH = (int) ((long) imageH * minCaptionedWidth / imageW);
                imageW = minCaptionedWidth;
            }
        }

        // Decide "small" purely from the (stable) scaled image width and the fixed thresholds.
        // IMPORTANT: do NOT factor in the live messageBody/downloadButton measured width here.
        // Those reflect the *previous* layout pass, and since the !small branch below then sets
        // the body width from the image, reading them back created a feedback loop that made the
        // image enlarge then shrink again on every rebind/refresh.
        final float currentTargetImageWidth =
                otherBelow ? this.targetImageWidthLargeThreshold : this.targetImageWidthSmallThreshold;

        final boolean small = imageW < currentTargetImageWidth;

        ViewGroup.LayoutParams currentParams = image.getLayoutParams();
        if (currentParams instanceof LinearLayout.LayoutParams linearParams) {
            if (linearParams.width != imageW || linearParams.height != imageH) {
                linearParams.width = imageW;
                linearParams.height = imageH;
                image.setLayoutParams(linearParams); // Only set if changed
            }
        } else {
            // Fallback or if it's a different type of LayoutParams initially
            image.setLayoutParams(new LinearLayout.LayoutParams(imageW, imageH));
        }


        // --- Start of Simplified Corner Rounding ---
        ShapeAppearanceModel.Builder shapeBuilder = new ShapeAppearanceModel.Builder();

        // Set all corners to be rounded with imageRadiusDim (or use bubbleRadiusDim if preferred)
        shapeBuilder = shapeBuilder.setAllCorners(CornerFamily.ROUNDED, this.imageRadiusDim);

        image.setShapeAppearanceModel(shapeBuilder.build());
        // --- End of Simplified Corner Rounding ---


        // Top inset only when something (a reply quote) sits above the image; otherwise the image
        // is the top of the bubble and must be flush to it, or the inset shows the bubble
        // background as a "border" above the image.
        if (small && otherAbove) {
            image.setPadding(0, (int) this.padding8dp, 0, 0);
        } else {
            image.setPadding(0, 0, 0, 0);
        }


        // The rest of the logic for adjusting messageBody and inReplyToQuote widths
        // can remain if it's still relevant to your layout when an image is present.
        // However, if the image corners are always fully rounded, the visual interaction
        // with these elements might change, so review if this is still needed as is.
        // Match the caption width to the image whenever a caption is shown (otherBelow), not only
        // for large images. Otherwise a narrow image (e.g. a portrait photo) with a wider caption
        // lets the bubble grow to the caption width, leaving the centered image with background
        // "borders" on the sides instead of filling the bubble.
        if (!small || otherBelow) {
            final ViewGroup.LayoutParams bodyLayoutParams = viewHolder.messageBody().getLayoutParams();
            int targetWidth = (int) (imageW - this.padding22dp);

            if (bodyLayoutParams.width != targetWidth) {
                bodyLayoutParams.width = targetWidth;
                viewHolder.messageBody().setLayoutParams(bodyLayoutParams);
            }

            if (viewHolder.inReplyToQuote().getVisibility() == View.VISIBLE) {
                final ViewGroup.LayoutParams qLayoutParams = viewHolder.inReplyToQuote().getLayoutParams();
                if (qLayoutParams.width != targetWidth) {
                    qLayoutParams.width = targetWidth;
                    viewHolder.inReplyToQuote().setLayoutParams(qLayoutParams);
                }
            }
        }
    }

    private void toggleWhisperInfo(
            final BubbleMessageItemViewHolder viewHolder,
            final Message message,
            final BubbleColor bubbleColor) {
        if (message.isPrivateMessage()) {
            final String privateMarker;
            if (message.getStatus() <= Message.STATUS_RECEIVED) {
                privateMarker = activity.getString(R.string.private_message);
            } else {
                Jid cp = message.getCounterpart();
                privateMarker =
                        activity.getString(
                                R.string.private_message_to,
                                Strings.nullToEmpty(cp == null ? null : cp.getResource()));
            }
            final SpannableString body = new SpannableString(privateMarker);
            body.setSpan(
                    new ForegroundColorSpan(
                            bubbleToOnSurfaceVariant(viewHolder.messageBody(), bubbleColor)),
                    0,
                    privateMarker.length(),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            body.setSpan(
                    new StyleSpan(Typeface.BOLD),
                    0,
                    privateMarker.length(),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            viewHolder.messageBody().setText(body);
            viewHolder.messageBody().setTypeface(notoRegular);
            viewHolder.messageBody().setVisibility(View.VISIBLE);
        } else {
            viewHolder.messageBody().setVisibility(GONE);
        }
    }

    private void loadMoreMessages(final Conversation conversation) {
        conversation.setLastClearHistory(0, null);
        activity.runOnUiThread(() -> activity.xmppConnectionService.updateConversation(conversation));
        conversation.setHasMessagesLeftOnServer(true);
        conversation.setFirstMamReference(null);
        long timestamp = conversation.getLastMessageTransmitted().getTimestamp();
        if (timestamp == 0) {
            timestamp = System.currentTimeMillis();
        }
        conversation.messagesLoaded.set(true);
        MessageArchiveService.Query query =
                activity.xmppConnectionService
                        .getMessageArchiveService()
                        .query(conversation, new MamReference(0), timestamp, false);
        if (query != null) {
            Toast.makeText(activity, R.string.fetching_history_from_server, Toast.LENGTH_LONG)
                    .show();
        } else {
            Toast.makeText(
                            activity,
                            R.string.not_fetching_history_retention_period,
                            Toast.LENGTH_SHORT)
                    .show();
        }
    }

    @NonNull
    @Override
    public MessageItemViewHolder onCreateViewHolder(
            final @NonNull ViewGroup parent, final int type) {
        final MessageItemViewHolder viewHolder =
                switch (type) {
                    case RTP_SESSION ->
                            new RtpSessionMessageItemViewHolder(
                                    DataBindingUtil.inflate(
                                            LayoutInflater.from(parent.getContext()),
                                            R.layout.item_message_rtp_session,
                                            parent,
                                            false));
                    case DATE_SEPARATOR ->
                            new DateSeperatorMessageItemViewHolder(
                                    DataBindingUtil.inflate(
                                            LayoutInflater.from(parent.getContext()),
                                            R.layout.item_message_date_bubble,
                                            parent,
                                            false));
                    case STATUS ->
                            new StatusMessageItemViewHolder(
                                    DataBindingUtil.inflate(
                                            LayoutInflater.from(parent.getContext()),
                                            R.layout.item_message_status,
                                            parent,
                                            false));
                    case END ->
                            new EndBubbleMessageItemViewHolder(
                                    DataBindingUtil.inflate(
                                            LayoutInflater.from(parent.getContext()),
                                            R.layout.item_message_end,
                                            parent,
                                            false));
                    case START ->
                            new StartBubbleMessageItemViewHolder(
                                    DataBindingUtil.inflate(
                                            LayoutInflater.from(parent.getContext()),
                                            R.layout.item_message_start,
                                            parent,
                                            false));
                    default -> {
                        Log.e("MessageAdapter", "Unable to create ViewHolder for type: " + type);
                        throw new AssertionError("Unable to create ViewHolder for type: " + type);
                    }
                };
        return viewHolder;
    }

    @Override
    public void onBindViewHolder(
            final @NonNull MessageItemViewHolder viewHolder, final int position) {
        final Message message = getItem(position);
        if (message == null) {
            return;
        }
        final int type = getItemViewType(message, bubbleDesign.alignStart);
        viewHolder.position = position;

        if (type == DATE_SEPARATOR
                && viewHolder instanceof DateSeperatorMessageItemViewHolder messageItemViewHolder) {
            render(message, messageItemViewHolder);
        } else if (type == RTP_SESSION
                && viewHolder instanceof RtpSessionMessageItemViewHolder messageItemViewHolder) {
            render(message, messageItemViewHolder);
        } else if (type == STATUS
                && viewHolder instanceof StatusMessageItemViewHolder messageItemViewHolder) {
            render(message, messageItemViewHolder);
        } else if ((type == END || type == START)
                && viewHolder instanceof BubbleMessageItemViewHolder messageItemViewHolder) {
            render(position, message, messageItemViewHolder);
        }
    }

    @Override
    public void onViewRecycled(final @NonNull MessageItemViewHolder holder) {
        super.onViewRecycled(holder);
        // Clear any leftover swipe-to-reply translation when the bubble is actually recycled, so a
        // reused view never appears "stuck" shifted. Doing this here (not in onBindViewHolder) means
        // it never resets the translation of a bubble that is being actively swiped — which a
        // mid-swipe rebind would otherwise do, making the bubble flicker back and forth.
        if (holder instanceof BubbleMessageItemViewHolder bubble) {
            bubble.messageBox().setTranslationX(0f);
        }
    }

    private View render(
            final int position,
            final Message message,
            final BubbleMessageItemViewHolder viewHolder) {
        viewHolder.storyPreview().setVisibility(View.GONE); //reset view state
        // Both legacy AXOLOTL and AXOLOTL_OMEMO2 (PQ OMEMO2) carry per-device
        // trust state — surface the "not verified yet" warning in both cases.
        final boolean omemoEncryption =
                message.getEncryption() == Message.ENCRYPTION_AXOLOTL
                        || message.getEncryption() == Message.ENCRYPTION_AXOLOTL_OMEMO2;
        final boolean isInValidSession =
                message.isValidInSession() && (!omemoEncryption || message.isTrusted());
        final Conversational conversation = message.getConversation();
        final Account account = conversation.getAccount();
        final List<Element> commands = message.getCommands();

        viewHolder.linkDescriptions().setOnItemClickListener((adapter, v, pos, id) -> {
            final var desc = (Element) adapter.getItemAtPosition(pos);
            var url = desc.findChildContent("url", "https://ogp.me/ns#");
            // should we prefer about? Maybe, it's the real original link, but it's not what we show the user
            if (url == null || url.isEmpty())
                url = desc.getAttribute("{http://www.w3.org/1999/02/22-rdf-syntax-ns#}about");
            if (url == null || url.isEmpty()) return;
            new FixedURLSpan(url).onClick(v);
        });

        if (viewHolder.messageBody() != null) {
            viewHolder.messageBody().setCustomSelectionActionModeCallback(new MessageTextActionModeCallback(this, viewHolder.messageBody()));
        }

        if (viewHolder.time() != null) {
            if (message.isAttention()) {
                viewHolder.time().setTypeface(notoBold);
            } else {
                viewHolder.time().setTypeface(notoRegular);
            }
        }

        final var black = MaterialColors.getColor(viewHolder.root(), com.google.android.material.R.attr.colorSecondaryContainer) == viewHolder.root().getContext().getColor(android.R.color.black);
        final boolean colorfulBackground = this.bubbleDesign.colorfulChatBubbles;
        final boolean received = message.getStatus() == Message.STATUS_RECEIVED;
        final BubbleColor bubbleColor;
        if (received) {
            if (isInValidSession) {
                // Colourful: light secondary-container tint with neutral text. Black-theme keeps
                // SECONDARY; otherwise the plain surface.
                bubbleColor =
                        colorfulBackground
                                ? BubbleColor.RECEIVED_COLORFUL
                                : (black ? BubbleColor.SECONDARY : BubbleColor.SURFACE);
            } else {
                bubbleColor = BubbleColor.WARNING;
            }
        } else {
            if (!colorfulBackground && black) {
                bubbleColor = BubbleColor.SECONDARY;
            } else {
                // Colourful: sent bubbles use the primary container (themed → follows custom &
                // dynamic colours, light in light mode / dark in dark mode) with neutral black/white
                // text. Distinct from the lighter received bubble.
                bubbleColor = colorfulBackground ? BubbleColor.SENT_COLORFUL : BubbleColor.SURFACE_HIGH;
            }
        }

        if (viewHolder.threadIdenticon() != null) {
            viewHolder.threadIdenticon().setVisibility(GONE);
            final Element thread = message.getThread();
            if (thread != null) {
                final String threadId = thread.getContent();
                if (threadId != null) {
                    final var roles = MaterialColors.getColorRoles(activity, UIHelper.getColorForName(threadId));
                    viewHolder.threadIdenticon().setVisibility(View.VISIBLE);
                    viewHolder.threadIdenticon().setColor(roles.getAccent());
                    viewHolder.threadIdenticon().setHash(UIHelper.identiconHash(threadId));
                }
            }
        }

        final var mergeIntoTop = mergeIntoTop(position, message);
        final var mergeIntoBottom = mergeIntoBottom(position, message);
        final var showAvatar =
                bubbleDesign.showAvatars
                        || (viewHolder instanceof StartBubbleMessageItemViewHolder
                        && message.getConversation().getMode() == Conversation.MODE_MULTI);
        setBubblePadding(viewHolder.root(), mergeIntoTop, mergeIntoBottom);
        if (showAvatar) {
            final var requiresAvatar =
                    viewHolder instanceof StartBubbleMessageItemViewHolder
                            ? !mergeIntoTop
                            : !mergeIntoBottom;
            setRequiresAvatar(viewHolder, requiresAvatar, message);
            AvatarWorkerTask.loadAvatar(message, viewHolder.contactPicture(), R.dimen.avatar);
        } else {
            viewHolder.contactPicture().setVisibility(View.GONE);
        }
        setAvatarDistance(viewHolder.messageBox(), viewHolder.getClass(), showAvatar);
        viewHolder.messageBox().setClipToOutline(true); //remove to show tails
        resetClickListener(viewHolder.messageBox(), viewHolder.messageBody());

        viewHolder.messageBox().setOnClickListener(v -> {
            if (MessageAdapter.this.mOnMessageBoxClickedListener != null) {
                MessageAdapter.this.mOnMessageBoxClickedListener
                        .onContactPictureClicked(message);
            }
        });

        final float[] lastTouchRaw = {0f, 0f};
        viewHolder.messageBox().setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                lastTouchRaw[0] = event.getRawX();
                lastTouchRaw[1] = event.getRawY();
            }
            return false;
        });
        viewHolder.messageBox().setOnLongClickListener(v -> {
            if (MessageAdapter.this.mOnMessageLongPressListener != null) {
                MessageAdapter.this.mOnMessageLongPressListener.onMessageLongPress(message, viewHolder.messageBox(), lastTouchRaw[0], lastTouchRaw[1]);
                return true;
            }
            return false;
        });
        viewHolder.messageBody().setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                lastTouchRaw[0] = event.getRawX();
                lastTouchRaw[1] = event.getRawY();
            }
            return false;
        });
        viewHolder.messageBody().setOnLongClickListener(v -> {
            if (MessageAdapter.this.mOnMessageLongPressListener != null) {
                MessageAdapter.this.mOnMessageLongPressListener.onMessageLongPress(message, viewHolder.messageBox(), lastTouchRaw[0], lastTouchRaw[1]);
                return true;
            }
            return false;
        });

        viewHolder.messageBody().setOnClickListener(v -> {
            if (MessageAdapter.this.mOnMessageBoxClickedListener != null) {
                MessageAdapter.this.mOnMessageBoxClickedListener
                        .onContactPictureClicked(message);
            }
        });
        viewHolder.messageBody().setAccessibilityDelegate(null);

        viewHolder
                .contactPicture()
                .setOnClickListener(
                        v -> {
                            if (MessageAdapter.this.mOnContactPictureClickedListener != null) {
                                MessageAdapter.this.mOnContactPictureClickedListener
                                        .onContactPictureClicked(message);
                            }
                        });
        viewHolder
                .contactPicture()
                .setOnLongClickListener(
                        v -> {
                            if (MessageAdapter.this.mOnContactPictureLongClickedListener != null) {
                                MessageAdapter.this.mOnContactPictureLongClickedListener
                                        .onContactPictureLongClicked(v, message);
                                return true;
                            } else {
                                return false;
                            }
                        });

        final Transferable transferable = message.getTransferable();
        final boolean unInitiatedButKnownSize = MessageUtils.unInitiatedButKnownSize(message);
        final boolean expired = message.getExpireAt() > 0 && message.getExpireAt() < System.currentTimeMillis();

        final boolean muted = message.getStatus() == Message.STATUS_RECEIVED && conversation.getMode() == Conversation.MODE_MULTI && activity.xmppConnectionService.isMucUserMuted(new MucOptions.User(null, conversation.getJid(), message.getOccupantId(), null, null));
        if (muted) {
            // Muted MUC participant
            displayInfoMessage(viewHolder, "Muted", bubbleColor);
        } else if (expired || Message.DELETED_MESSAGE_BODY.equals(message.getBody())) {
            displayInfoMessage(viewHolder, activity.getString(R.string.message_has_disappeared), bubbleColor);
        } else if (message.getType() == Message.TYPE_STORY || message.getStoryReference() != null) {
            displayPubSubMessage(viewHolder, message, bubbleColor);
        } else if (unInitiatedButKnownSize || message.isDeleted() || (transferable != null && transferable.getStatus() != Transferable.STATUS_UPLOADING)) {
            if (unInitiatedButKnownSize || (message.isDeleted() && message.getModerated() == null) || transferable != null && transferable.getStatus() == Transferable.STATUS_OFFER) {
                displayDownloadableMessage(viewHolder, message, activity.getString(R.string.download_x_file, UIHelper.getFileDescriptionString(activity, message)), bubbleColor);
            } else if (transferable != null && transferable.getStatus() == Transferable.STATUS_OFFER_CHECK_FILESIZE) {
                displayDownloadableMessage(viewHolder, message, activity.getString(R.string.check_x_filesize, UIHelper.getFileDescriptionString(activity, message)), bubbleColor);
            } else {
                displayInfoMessage(viewHolder, UIHelper.getMessagePreview(activity.xmppConnectionService, message).first, bubbleColor);
            }
        } else if (message.isFileOrImage()
                && message.getEncryption() != Message.ENCRYPTION_PGP
                && message.getEncryption() != Message.ENCRYPTION_DECRYPTION_FAILED) {
            if (message.getFileParams().width > 0 && message.getFileParams().height > 0) {
                displayMediaPreviewMessage(viewHolder, message, bubbleColor);
            } else if (message.getFileParams().runtime > 0) {
                displayAudioMessage(viewHolder, message, bubbleColor);
            } else if ("application/webxdc+zip".equals(message.getFileParams().getMediaType()) && message.getConversation() instanceof Conversation && !message.getFileParams().getCids().isEmpty()) {
                if (message.getThread() != null) {
                    displayWebxdcMessage(viewHolder, message, bubbleColor);
                } else {
                    Element thread = new Element("thread", "jabber:client");
                    thread.setContent(UUID.randomUUID().toString());
                    message.setThread(thread);
                    displayWebxdcMessage(viewHolder, message, bubbleColor);
                }
            } else {
                displayOpenableMessage(viewHolder, message, bubbleColor);
            }
        } else if (message.getEncryption() == Message.ENCRYPTION_PGP) {
            if (account.isPgpDecryptionServiceConnected()) {
                if (conversation instanceof Conversation
                        && !account.hasPendingPgpIntent((Conversation) conversation)) {
                    displayInfoMessage(
                            viewHolder,
                            activity.getString(R.string.message_decrypting),
                            bubbleColor);
                } else {
                    displayInfoMessage(
                            viewHolder, activity.getString(R.string.pgp_message), bubbleColor);
                }
            } else {
                displayInfoMessage(
                        viewHolder, activity.getString(R.string.install_openkeychain), bubbleColor);
                viewHolder.messageBox().setOnClickListener(this::promptOpenKeychainInstall);
                viewHolder.messageBody().setOnClickListener(this::promptOpenKeychainInstall);
            }
        } else if (message.getEncryption() == Message.ENCRYPTION_DECRYPTION_FAILED) {
            displayInfoMessage(
                    viewHolder, activity.getString(R.string.decryption_failed), bubbleColor);
        } else if (message.getEncryption() == Message.ENCRYPTION_AXOLOTL_NOT_FOR_THIS_DEVICE
                || message.getEncryption() == Message.ENCRYPTION_AXOLOTL_OMEMO2_NOT_FOR_THIS_DEVICE) {
            displayInfoMessage(
                    viewHolder,
                    activity.getString(R.string.not_encrypted_for_this_device),
                    bubbleColor);
        } else if (message.getEncryption() == Message.ENCRYPTION_AXOLOTL_FAILED
                || message.getEncryption() == Message.ENCRYPTION_AXOLOTL_OMEMO2_FAILED) {
            displayInfoMessage(
                    viewHolder, activity.getString(R.string.omemo2_decryption_failed), bubbleColor);
        } else {
            if (message.wholeIsKnownURI() != null) {
                displayURIMessage(viewHolder, message, bubbleColor);
            } else if (message.isGeoUri()) {
                displayLocationMessage(viewHolder, message, bubbleColor);
            } else if (message.treatAsDownloadable()) {
                try {
                    final URI uri = message.getOob();
                    displayDownloadableMessage(viewHolder,
                            message,
                            activity.getString(
                                    R.string.check_x_filesize_on_host,
                                    UIHelper.getFileDescriptionString(activity, message),
                                    uri.getHost()),
                            bubbleColor);
                } catch (Exception e) {
                    displayDownloadableMessage(
                            viewHolder,
                            message,
                            activity.getString(
                                    R.string.check_x_filesize,
                                    UIHelper.getFileDescriptionString(activity, message)),
                            bubbleColor);
                }
            } else if (message.bodyIsOnlyEmojis() && message.getType() != Message.TYPE_PRIVATE) {
                displayEmojiMessage(viewHolder, message, bubbleColor);
            } else {
                displayTextMessage(viewHolder, message, bubbleColor);
            }
        }
        displayAttachments(viewHolder, message);
        /*
        if (!black && viewHolder.image().getLayoutParams().width > metrics.density * 110) {
            footerWrap = true;
        }

        viewHolder.messageBoxInner().setMinimumWidth(footerWrap ? (int) (110 * metrics.density) : 0);
        LinearLayout.LayoutParams statusParams = (LinearLayout.LayoutParams) viewHolder.statusLine().getLayoutParams();
        statusParams.width = footerWrap ? ViewGroup.LayoutParams.MATCH_PARENT : ViewGroup.LayoutParams.WRAP_CONTENT;
        viewHolder.statusLine().setLayoutParams(statusParams);
        */

        final Function<Reaction, GetThumbnailForCid> reactionThumbnailer = (r) -> new Thumbnailer(conversation.getAccount(), r, conversation.canInferPresence());
        if (received) {
            if (!muted && commands != null && conversation instanceof Conversation) {
                CommandButtonAdapter adapter = new CommandButtonAdapter(activity);
                adapter.addAll(commands);
                viewHolder.commandsList().setAdapter(adapter);
                viewHolder.commandsList().setVisibility(View.VISIBLE);
                viewHolder.commandsList().setOnItemClickListener((p, v, pos, id) -> {
                    final Element command = adapter.getItem(pos);
                    if (command != null) {
                        activity.startCommand(conversation.getAccount(), command.getAttributeAsJid("jid"), command.getAttribute("node"));
                    }
                });
            } else {
                // It's unclear if we can set this to null...
                ListAdapter adapter = viewHolder.commandsList().getAdapter();
                if (adapter instanceof ArrayAdapter) {
                    ((ArrayAdapter<?>) adapter).clear();
                }
                viewHolder.commandsList().setVisibility(GONE);
                viewHolder.commandsList().setOnItemClickListener(null);
            }
        }

        final boolean leftSpine = viewHolder instanceof StartBubbleMessageItemViewHolder;
        final int bubbleBackground;
        if (mergeIntoTop && mergeIntoBottom) {
            bubbleBackground =
                    leftSpine
                            ? R.drawable.message_bubble_received_group_middle
                            : R.drawable.message_bubble_sent_group_middle;
        } else if (mergeIntoBottom) {
            bubbleBackground =
                    leftSpine
                            ? R.drawable.message_bubble_received_group_top
                            : R.drawable.message_bubble_sent_group_top;
        } else if (mergeIntoTop) {
            bubbleBackground =
                    leftSpine
                            ? R.drawable.message_bubble_received_group_bottom
                            : R.drawable.message_bubble_sent_group_bottom;
        } else {
            bubbleBackground = R.drawable.message_bubble_single;
        }
        viewHolder.messageBox().setBackgroundResource(bubbleBackground);

        setBackgroundTint(viewHolder.messageBox(), bubbleColor);
        setTextColor(viewHolder.messageBody(), bubbleColor);
        viewHolder.messageBody().setLinkTextColor(bubbleToOnSurfaceColor(viewHolder.messageBody(), bubbleColor));

        if (received && viewHolder instanceof StartBubbleMessageItemViewHolder startViewHolder) {
            setTextColor(startViewHolder.encryption(), bubbleColor);
            if (isInValidSession) {
                startViewHolder.encryption().setVisibility(GONE);
            } else {
                startViewHolder.encryption().setVisibility(View.VISIBLE);
                if (omemoEncryption && !message.isTrusted()) {
                    startViewHolder.encryption().setText(R.string.not_trusted);
                } else {
                    startViewHolder
                            .encryption()
                            .setText(CryptoHelper.encryptionTypeToText(message.getEncryption()));
                }
            }
            final var aggregatedReactions = conversation instanceof Conversation ? ((Conversation) conversation).aggregatedReactionsFor(message, reactionThumbnailer) : message.getAggregatedReactions();
            BindingAdapters.setReactionsOnReceived(
                    viewHolder.reactions(),
                    aggregatedReactions,
                    reactions -> sendReactions(message, reactions),
                    emoji -> showDetailedReaction(message, emoji),
                    emoji -> sendCustomReaction(message, emoji),
                    reaction -> removeCustomReaction(conversation, reaction),
                    () -> {
                        if (mConversationFragment.requireTrustKeys()) {
                            return;
                        }

                        final var intent = new Intent(activity, AddReactionActivity.class);
                        intent.putExtra("conversation", message.getConversation().getUuid());
                        intent.putExtra("message", message.getUuid());
                        activity.startActivity(intent);
                    });
        } else {
            if (viewHolder instanceof StartBubbleMessageItemViewHolder startViewHolder) {
                startViewHolder.encryption().setVisibility(View.GONE);
            }
            BindingAdapters.setReactionsOnSent(
                    viewHolder.reactions(),
                    message.getAggregatedReactions(),
                    reactions -> sendReactions(message, reactions),
                    emoji -> showDetailedReaction(message, emoji));
        }

        var subject = message.getSubject();
        if (subject == null && message.getThread() != null) {
            final var thread = ((Conversation) message.getConversation()).getThread(message.getThread().getContent());
            if (thread != null) subject = thread.getSubject();
        }
        if (muted || subject == null) {
            viewHolder.subject().setVisibility(GONE);
        } else {
            viewHolder.subject().setVisibility(View.VISIBLE);
            viewHolder.subject().setText(subject);
        }


        WeakReference<ReplyClickListener> listener = new WeakReference<>(replyClickListener);
        if (message.getInReplyTo() == null) {
            viewHolder.inReplyToBox().setVisibility(GONE);
        } else {
            viewHolder.inReplyToBox().setVisibility(View.VISIBLE);
            viewHolder.inReplyTo().setText(UIHelper.getMessageDisplayName(message.getInReplyTo()));
            viewHolder.inReplyToBox().setOnClickListener(v -> {
                ReplyClickListener l = listener.get();
                if (l != null) {
                    l.onReplyClick(message);
                }
            });
            setTextColor(viewHolder.inReplyTo(), bubbleColor);
        }

        if (appSettings.showLinkPreviews()) {
            final var descriptions = message.getLinkDescriptions();
            viewHolder.linkDescriptions().setAdapter(new ArrayAdapter<>(activity, 0, descriptions) {
                @NonNull
                @Override
                public View getView(int position, View view, @NonNull ViewGroup parent) {
                    final LinkDescriptionBinding binding = DataBindingUtil.inflate(LayoutInflater.from(parent.getContext()), R.layout.link_description, parent, false);
                    binding.title.setText(Objects.requireNonNull(getItem(position)).findChildContent("title", "https://ogp.me/ns#"));
                    binding.description.setText(Objects.requireNonNull(getItem(position)).findChildContent("description", "https://ogp.me/ns#"));
                    binding.url.setText(Objects.requireNonNull(getItem(position)).findChildContent("url", "https://ogp.me/ns#"));
                    final var video = Objects.requireNonNull(getItem(position)).findChildContent("video", "https://ogp.me/ns#");
                    if (video != null && !video.isEmpty()) {
                        binding.playButton.setVisibility(View.VISIBLE);
                        binding.playButton.setOnClickListener((v) -> {
                            new FixedURLSpan(video).onClick(v);
                        });
                    }
                    return binding.getRoot();
                }
            });
            Util.justifyListViewHeightBasedOnChildren(viewHolder.linkDescriptions(), (int) (metrics.density * 100), true);
        }

        displayStatus(viewHolder, message, bubbleColor);

        if (message.getConversation().getMode() == Conversation.MODE_SINGLE && viewHolder.username() != null) {
            viewHolder.username().setText(null);
            viewHolder.username().setVisibility(GONE);
        }

        final boolean multiReceived =
                message.getConversation().getMode() == Conversation.MODE_MULTI
                        && message.getStatus() <= Message.STATUS_RECEIVED;
        final boolean showUserNickname =
                message.getConversation().getMode() == Conversation.MODE_MULTI
                        && viewHolder instanceof StartBubbleMessageItemViewHolder;
        if (!showAvatar || (message.getConversation().getMode() == Conversation.MODE_SINGLE && showAvatar)) {
            if (mForceNames || multiReceived || showUserNickname || (message.getTrueCounterpart() != null && message.getContact() != null)) {
                final String displayName = UIHelper.getMessageDisplayName(message);
                if (viewHolder.username() != null && displayName != null) {
                    viewHolder.username().setVisibility(View.VISIBLE);
                    viewHolder.username().setText(UIHelper.getColoredUsername(activity.xmppConnectionService, message));
                }
            } else if (viewHolder.username() != null) {
                viewHolder.username().setText(null);
                viewHolder.username().setVisibility(GONE);
            }
        }

        viewHolder.messageBody().setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void sendAccessibilityEvent(@NonNull View host, int eventType) {
                super.sendAccessibilityEvent(host, eventType);
                if (eventType == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) {
                    if (viewHolder.messageBody().hasSelection()) {
                        selectionUuid = message.getUuid();
                    } else if (message.getUuid() != null && message.getUuid().equals(selectionUuid)) {
                        selectionUuid = null;
                    }
                }
            }
        });
        return viewHolder.root();
    }


    private View render(
            final Message message, final DateSeperatorMessageItemViewHolder viewHolder) {
        final boolean colorfulBackground = this.bubbleDesign.colorfulChatBubbles;
        if (UIHelper.today(message.getTimeSent())) {
            viewHolder.binding.messageBody.setText(R.string.today);
        } else if (UIHelper.yesterday(message.getTimeSent())) {
            viewHolder.binding.messageBody.setText(R.string.yesterday);
        } else {
            viewHolder.binding.messageBody.setText(
                    DateUtils.formatDateTime(
                            activity,
                            message.getTimeSent(),
                            DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_YEAR));
        }
        if (colorfulBackground) {
            setBackgroundTint(viewHolder.binding.messageBox, BubbleColor.PRIMARY);
            setTextColor(viewHolder.binding.messageBody, BubbleColor.PRIMARY);
        } else {
            setBackgroundTint(viewHolder.binding.messageBox, BubbleColor.SURFACE_HIGH);
            setTextColor(viewHolder.binding.messageBody, BubbleColor.SURFACE_HIGH);
        }
        viewHolder.binding.messageBox.setOnClickListener(v -> onDateSeparatorClickListener.onDateSeparatorClick(message.getTimeSent()));
        return viewHolder.binding.getRoot();
    }

    private View render(final Message message, final RtpSessionMessageItemViewHolder viewHolder) {
        final boolean colorfulBackground = this.bubbleDesign.colorfulChatBubbles;
        final boolean received = message.getStatus() <= Message.STATUS_RECEIVED;
        final RtpSessionStatus rtpSessionStatus = RtpSessionStatus.of(message.getBody());
        final long duration = rtpSessionStatus.duration;
        if (received) {
            if (duration > 0) {
                viewHolder.binding.messageBody.setText(
                        activity.getString(
                                R.string.incoming_call_duration_timestamp,
                                TimeFrameUtils.resolve(activity, duration),
                                UIHelper.readableTimeDifferenceFull(
                                        activity, message.getTimeSent(), allowRelativeTimestamps)));
            } else if (rtpSessionStatus.successful) {
                viewHolder.binding.messageBody.setText(R.string.incoming_call);
            } else {
                viewHolder.binding.messageBody.setText(
                        activity.getString(
                                R.string.missed_call_timestamp,
                                UIHelper.readableTimeDifferenceFull(
                                        activity, message.getTimeSent(), allowRelativeTimestamps)));
            }
        } else {
            if (duration > 0) {
                viewHolder.binding.messageBody.setText(
                        activity.getString(
                                R.string.outgoing_call_duration_timestamp,
                                TimeFrameUtils.resolve(activity, duration),
                                UIHelper.readableTimeDifferenceFull(
                                        activity, message.getTimeSent(), allowRelativeTimestamps)));
            } else {
                viewHolder.binding.messageBody.setText(
                        activity.getString(
                                R.string.outgoing_call_timestamp,
                                UIHelper.readableTimeDifferenceFull(
                                        activity, message.getTimeSent(), allowRelativeTimestamps)));
            }
        }
        if (colorfulBackground) {
            setBackgroundTint(viewHolder.binding.messageBox, BubbleColor.SECONDARY);
            setTextColor(viewHolder.binding.messageBody, BubbleColor.SECONDARY);
            setImageTint(viewHolder.binding.indicatorReceived, BubbleColor.SECONDARY);
        } else {
            setBackgroundTint(viewHolder.binding.messageBox, BubbleColor.SURFACE_HIGH);
            setTextColor(viewHolder.binding.messageBody, BubbleColor.SURFACE_HIGH);
            setImageTint(viewHolder.binding.indicatorReceived, BubbleColor.SURFACE_HIGH);
        }
        viewHolder.binding.indicatorReceived.setImageResource(
                RtpSessionStatus.getDrawable(received, rtpSessionStatus.successful));
        return viewHolder.binding.getRoot();
    }

    private View render(final Message message, final StatusMessageItemViewHolder viewHolder) {
        final var conversation = message.getConversation();
        if ("LOAD_MORE".equals(message.getBody())) {
            viewHolder.binding.statusMessage.setVisibility(View.GONE);
            viewHolder.binding.messagePhoto.setVisibility(View.GONE);
            viewHolder.binding.loadMoreMessages.setVisibility(View.VISIBLE);
            viewHolder.binding.loadMoreMessages.setOnClickListener(
                    v -> loadMoreMessages((Conversation) message.getConversation()));
        } else {
            viewHolder.binding.statusMessage.setVisibility(View.VISIBLE);
            viewHolder.binding.loadMoreMessages.setVisibility(View.GONE);
            viewHolder.binding.statusMessage.setText(message.getBody());
            boolean showAvatar;
            if (conversation.getMode() == Conversation.MODE_SINGLE) {
                showAvatar = true;
                AvatarWorkerTask.loadAvatar(
                        message, viewHolder.binding.messagePhoto, R.dimen.avatar_on_status_message);
            } else if (message.getCounterpart() != null
                    || message.getTrueCounterpart() != null
                    || (message.getCounterparts() != null
                    && !message.getCounterparts().isEmpty())) {
                showAvatar = true;
                AvatarWorkerTask.loadAvatar(
                        message, viewHolder.binding.messagePhoto, R.dimen.avatar_on_status_message);
            } else {
                showAvatar = false;
            }
            if (showAvatar) {
                viewHolder.binding.messagePhoto.setAlpha(0.5f);
                viewHolder.binding.messagePhoto.setVisibility(View.VISIBLE);
            } else {
                viewHolder.binding.messagePhoto.setVisibility(View.GONE);
            }
        }
        return viewHolder.binding.getRoot();
}

    private void setAvatarDistance(
            final LinearLayout messageBox,
            final Class<? extends BubbleMessageItemViewHolder> clazz,
            final boolean showAvatar) {
        final ViewGroup.MarginLayoutParams layoutParams =
                (ViewGroup.MarginLayoutParams) messageBox.getLayoutParams();
        if (showAvatar) {
            final var resources = messageBox.getResources();
            if (clazz == StartBubbleMessageItemViewHolder.class) {
                layoutParams.setMarginStart(
                        resources.getDimensionPixelSize(R.dimen.bubble_avatar_distance));
                layoutParams.setMarginEnd(0);
            } else if (clazz == EndBubbleMessageItemViewHolder.class) {
                layoutParams.setMarginStart(0);
                layoutParams.setMarginEnd(
                        resources.getDimensionPixelSize(R.dimen.bubble_avatar_distance));
            } else {
                throw new AssertionError("Avatar distances are not available on this view type");
            }
        } else {
            layoutParams.setMarginStart(0);
            layoutParams.setMarginEnd(0);
        }
        messageBox.setLayoutParams(layoutParams);
    }

    private void setBubblePadding(
            final ConstraintLayout root,
            final boolean mergeIntoTop,
            final boolean mergeIntoBottom) {
        final var resources = root.getResources();
        final var horizontal = resources.getDimensionPixelSize(R.dimen.bubble_horizontal_padding);
        final int top =
                resources.getDimensionPixelSize(
                        mergeIntoTop
                                ? R.dimen.bubble_vertical_padding_minimum
                                : R.dimen.bubble_vertical_padding);
        final int bottom =
                resources.getDimensionPixelSize(
                        mergeIntoBottom
                                ? R.dimen.bubble_vertical_padding_minimum
                                : R.dimen.bubble_vertical_padding);
        root.setPadding(horizontal, top, horizontal, bottom);
    }

    private void setRequiresAvatar(
            final BubbleMessageItemViewHolder viewHolder, final boolean requiresAvatar, Message message) {
        final var layoutParams = viewHolder.contactPicture().getLayoutParams();
        final boolean multiReceived =
                message.getConversation().getMode() == Conversation.MODE_MULTI
                        && message.getStatus() <= Message.STATUS_RECEIVED;
        final boolean showUserNickname =
                message.getConversation().getMode() == Conversation.MODE_MULTI
                        && viewHolder instanceof StartBubbleMessageItemViewHolder;
        if (requiresAvatar) {
            final var resources = viewHolder.contactPicture().getResources();
            final var avatarSize = resources.getDimensionPixelSize(R.dimen.bubble_avatar_size);
            layoutParams.height = avatarSize;
            viewHolder.contactPicture().setVisibility(View.VISIBLE);
            viewHolder.messageBox().setMinimumHeight(avatarSize);
            if (mForceNames || multiReceived || showUserNickname || (message.getTrueCounterpart() != null && message.getContact() != null)) {
                final String displayName = UIHelper.getMessageDisplayName(message);
                if (viewHolder.username() != null && displayName != null) {
                    viewHolder.username().setVisibility(View.VISIBLE);
                    viewHolder.username().setText(UIHelper.getColoredUsername(activity.xmppConnectionService, message));
                }
            } else if (viewHolder.username() != null) {
                viewHolder.username().setText(null);
                viewHolder.username().setVisibility(GONE);
            }
        } else {
            layoutParams.height = 0;
            viewHolder.contactPicture().setVisibility(View.INVISIBLE);
            if (viewHolder.username() != null) {
                viewHolder.username().setText(null);
                viewHolder.username().setVisibility(GONE);
            }
            viewHolder.messageBox().setMinimumHeight(0);
        }
        viewHolder.contactPicture().setLayoutParams(layoutParams);
    }

    private boolean mergeIntoTop(final int position, final Message message) {
        if (position < 0) {
            return false;
        }
        final var top = getItem(position - 1);
        return merge(top, message);
    }

    private boolean mergeIntoBottom(final int position, final Message message) {
        final Message bottom;
        try {
            bottom = getItem(position + 1);
        } catch (final IndexOutOfBoundsException e) {
            return false;
        }
        return merge(message, bottom);
    }

    private static boolean merge(final Message a, final Message b) {
        if (getItemViewType(a, false) != getItemViewType(b, false)) {
            return false;
        }
        final var receivedA = a.getStatus() == Message.STATUS_RECEIVED;
        final var receivedB = b.getStatus() == Message.STATUS_RECEIVED;
        if (receivedA != receivedB) {
            return false;
        }
        if (a.getConversation().getMode() == Conversation.MODE_MULTI
                && a.getStatus() == Message.STATUS_RECEIVED) {
            final var occupantIdA = a.getOccupantId();
            final var occupantIdB = b.getOccupantId();
            if (occupantIdA != null && occupantIdB != null) {
                if (!occupantIdA.equals(occupantIdB)) {
                    return false;
                }
            }
            final var counterPartA = a.getCounterpart();
            final var counterPartB = b.getCounterpart();
            if (counterPartA == null || !counterPartA.equals(counterPartB)) {
                return false;
            }
        }
        return b.getTimeSent() - a.getTimeSent() <= Config.MESSAGE_MERGE_WINDOW;
    }

    private boolean showDetailedReaction(final Message message, Map.Entry<EmojiSearch.Emoji, Collection<Reaction>> reaction) {
        final var c = message.getConversation();
        if (c instanceof Conversation conversation && c.getMode() == Conversational.MODE_MULTI) {
            final var reactions = reaction.getValue();
            final var mucOptions = conversation.getMucOptions();
            final var users = mucOptions.findUsers(reactions);
            if (users.isEmpty()) {
                return true;
            }
            final MaterialAlertDialogBuilder dialogBuilder =
                    new MaterialAlertDialogBuilder(activity);
            dialogBuilder.setTitle(reaction.getKey().toString());
            dialogBuilder.setMessage(UIHelper.concatNames(users));
            dialogBuilder.create().show();
            return true;
        } else {
            return false;
        }
    }

    private void sendReactions(final Message message, final Collection<String> reactions) {
        if (mConversationFragment.requireTrustKeys()) {
            return;
        }
        if (!message.isPrivateMessage() && activity.xmppConnectionService.sendReactions(message, reactions)) {
            return;
        }
        Toast.makeText(activity, R.string.could_not_add_reaction, Toast.LENGTH_LONG).show();
    }

    private void sendCustomReaction(final Message inReplyTo, final EmojiSearch.CustomEmoji emoji) {
        if (mConversationFragment.requireTrustKeys()) {
            return;
        }
        final var message = inReplyTo.reply();
        message.appendBody(emoji.toInsert());
        Message.configurePrivateMessage(message);
        new Thread(() -> activity.xmppConnectionService.sendMessage(message)).start();
    }

    private void removeCustomReaction(final Conversational conversation, final Reaction reaction) {
        if (mConversationFragment.requireTrustKeys()) {
            return;
        }
        if (!(conversation instanceof Conversation)) {
            Toast.makeText(activity, R.string.could_not_add_reaction, Toast.LENGTH_LONG).show();
            return;
        }

        final var message = new Message(conversation, " ", ((Conversation) conversation).getNextEncryption());
        final var envelope = ((Conversation) conversation).findMessageWithUuidOrRemoteId(reaction.envelopeId);
        if (envelope != null) {
            ((Conversation) conversation).remove(envelope);
            message.addPayload(envelope.getReply());
            message.getOrMakeHtml();
            message.putEdited(reaction.envelopeId, envelope.getServerMsgId());
        } else {
            message.putEdited(reaction.envelopeId, null);
        }

        new Thread(() -> activity.xmppConnectionService.sendMessage(message)).start();
    }

    private void addReaction(final Message message) {
        if (mConversationFragment.requireTrustKeys()) {
            return;
        }
        activity.addReaction(
                message,
                reactions -> {
                    if (activity.xmppConnectionService.sendReactions(message, reactions)) {
                        return;
                    }
                    Toast.makeText(activity, R.string.could_not_add_reaction, Toast.LENGTH_LONG)
                            .show();
                });
    }

    private void promptOpenKeychainInstall(View view) {
        activity.showInstallPgpDialog();
    }

    public FileBackend getFileBackend() {
        return activity.xmppConnectionService.getFileBackend();
    }

    public void stopAudioPlayer() {
        audioPlayer.stop();
    }

    public void unregisterListenerInAudioPlayer() {
        audioPlayer.unregisterListener();
    }

    public void startStopPending() {
        audioPlayer.startStopPending();
    }

    public void openDownloadable(Message message) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(
                                activity, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        != PackageManager.PERMISSION_GRANTED) {
            ConversationFragment.registerPendingMessage(activity, message);
            ActivityCompat.requestPermissions(
                    activity,
                    new String[] {Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    ConversationsActivity.REQUEST_OPEN_MESSAGE);
            return;
        }
        final DownloadableFile file =
                activity.xmppConnectionService.getFileBackend().getFile(message);
        final var fp = message.getFileParams();
        final var name = fp == null ? null : fp.getName();
        final var displayName = name == null ? file.getName() : name;
        ViewUtil.view(activity, file, displayName, message.getConversation().getUuid(), message.getUuid());
    }

    private void showLocation(Message message) {
        final eu.siacs.conversations.utils.LiveLocationManager mgr =
                eu.siacs.conversations.utils.LiveLocationManager.getInstance();
        final String liveSessionId = mgr.getSessionIdForMessage(message.getUuid());

        final eu.siacs.conversations.utils.LiveLocationManager.OutgoingSession outgoing =
                mgr.getOutgoingSession(message.getConversation().getUuid());
        final boolean isOurOutgoingLive = outgoing != null && message.getUuid().equals(outgoing.messageUuid);

        if (isOurOutgoingLive) {
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.live_location)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setNeutralButton(R.string.stop_live_location, (d, w) -> {
                        if (activity.xmppConnectionService != null) {
                            activity.xmppConnectionService.stopLiveLocationSharing(message.getConversation().getUuid());
                            activity.xmppConnectionService.updateConversationUi();
                            Toast.makeText(activity, R.string.live_location_stopped_toast, Toast.LENGTH_SHORT).show();
                        }
                    })
                    .setPositiveButton(R.string.open_map, (d, w) -> openLiveLocationMap(message, liveSessionId != null ? liveSessionId : outgoing.sessionId, mgr))
                    .show();
            return;
        }

        // Fall back to session ID from the persisted payload (works after restart too)
        final String resolvedSessionId = liveSessionId != null ? liveSessionId : getLiveLocationSessionId(message);
        if (resolvedSessionId != null) {
            openLiveLocationMap(message, resolvedSessionId, mgr);
            return;
        }
        for (Intent intent : GeoHelper.createGeoIntentsFromMessage(activity, message)) {
            if (intent.resolveActivity(getContext().getPackageManager()) != null) {
                getContext().startActivity(intent);
                return;
            }
        }
        Toast.makeText(
                        activity,
                        R.string.no_application_found_to_display_location,
                        Toast.LENGTH_SHORT)
                .show();
    }

    private static void setLiveLocationButtonIcon(final com.google.android.material.button.MaterialButton button, final boolean active) {
        if (active) {
            button.setIconTint(ColorStateList.valueOf(0xFFE53935));
            button.setIconResource(R.drawable.ic_live_dot);
        } else {
            button.setIconTint(ColorStateList.valueOf(0xFF9E9E9E));
            button.setIconResource(R.drawable.ic_live_stopped);
        }
    }

    private static Element getLiveLocationElement(Message message) {
        for (Element el : message.getPayloads()) {
            if ("live-location".equals(el.getName()) && eu.siacs.conversations.xml.Namespace.LIVE_LOCATION.equals(el.getNamespace())) {
                return el;
            }
        }
        return null;
    }

    private static String getLiveLocationSessionId(Message message) {
        final Element el = getLiveLocationElement(message);
        return el != null ? el.getAttribute("id") : null;
    }

    private static boolean isLiveLocationPayloadActive(Message message) {
        final Element el = getLiveLocationElement(message);
        if (el == null) return false;
        final String sessionId = el.getAttribute("id");
        if (eu.siacs.conversations.utils.LiveLocationManager.getInstance().isSessionStopped(sessionId)) return false;
        final String expiresStr = el.getAttribute("expires");
        if (expiresStr == null) return false;
        try {
            return System.currentTimeMillis() < eu.siacs.conversations.parser.AbstractParser.parseTimestamp(expiresStr);
        } catch (Exception ignored) {
            return false;
        }
    }

    private void openLiveLocationMap(final Message message, final String liveSessionId, final eu.siacs.conversations.utils.LiveLocationManager mgr) {
        double lat = 0, lon = 0;
        final eu.siacs.conversations.utils.LiveLocationManager.IncomingSession incoming = mgr.getSession(liveSessionId);
        if (incoming != null) {
            lat = incoming.latitude;
            lon = incoming.longitude;
        } else {
            final eu.siacs.conversations.utils.LiveLocationManager.OutgoingSession outgoing =
                    mgr.getOutgoingSession(message.getConversation().getUuid());
            if (outgoing != null && liveSessionId != null && liveSessionId.equals(outgoing.sessionId)) {
                lat = outgoing.latitude;
                lon = outgoing.longitude;
            } else {
                // Try to get last known position from payload attributes
                final Element el = getLiveLocationElement(message);
                if (el != null && el.getAttribute("last_lat") != null && el.getAttribute("last_lon") != null) {
                    try {
                        lat = Double.parseDouble(el.getAttribute("last_lat"));
                        lon = Double.parseDouble(el.getAttribute("last_lon"));
                    } catch (Exception ignored) {}
                }
                if (lat == 0 && lon == 0) {
                    final String rawBody = message.getRawBody();
                    if (rawBody != null) {
                        try {
                            final org.osmdroid.util.GeoPoint gp = GeoHelper.parseGeoPoint(android.net.Uri.parse(rawBody));
                            lat = gp.getLatitude();
                            lon = gp.getLongitude();
                        } catch (Exception ignored) {}
                    }
                }
            }
        }
        loadAvatarForSession(message, liveSessionId);
        final Intent intent = new Intent(activity, eu.siacs.conversations.ui.ShowLocationActivity.class);
        intent.setAction("eu.siacs.conversations.location.show");
        intent.putExtra("latitude", lat);
        intent.putExtra("longitude", lon);
        intent.putExtra("live_session_id", liveSessionId);
        activity.startActivity(intent);
    }

    private void loadAvatarForSession(final Message message, final String sessionId) {
        if (activity.xmppConnectionService == null) return;
        final int sizePx = activity.getResources().getDimensionPixelSize(R.dimen.avatar);
        final Drawable drawable;
        if (message.getStatus() == Message.STATUS_RECEIVED) {
            drawable = activity.xmppConnectionService.getAvatarService()
                    .get(message.getConversation().getContact(), sizePx, false);
        } else {
            drawable = activity.xmppConnectionService.getAvatarService()
                    .get(message.getConversation().getAccount(), sizePx, false);
        }
        eu.siacs.conversations.utils.LiveLocationManager.getInstance().setSessionAvatar(sessionId, drawable);
    }

    public void updatePreferences() {
        this.bubbleDesign =
                new BubbleDesign(
                        appSettings.isColorfulChatBubbles(),
                       appSettings.isAlignStart(),
                        appSettings.isLargeFont(),
                        appSettings.isShowAvatars());
    }

    public void setHighlightedTerm(List<String> terms) {
        this.highlightedTerm = terms == null ? null : StylingHelper.filterHighlightedWords(terms);
    }

    public interface OnContactPictureClicked {
        void onContactPictureClicked(Message message);
    }

    public interface OnContactPictureLongClicked {
        void onContactPictureLongClicked(View v, Message message);
    }

    public interface OnInlineImageLongClicked {
        boolean onInlineImageLongClicked(Cid cid);
    }

    private static void setBackgroundTint(final LinearLayout view, final BubbleColor bubbleColor) {
        view.setBackgroundTintList(bubbleToColorStateList(view, bubbleColor));
    }

    private static ColorStateList bubbleToColorStateList(
            final View view, final BubbleColor bubbleColor) {
        final @AttrRes int colorAttributeResId =
                switch (bubbleColor) {
                    case SURFACE ->
                            Activities.isNightMode(view.getContext())
                                    ? com.google.android.material.R.attr.colorSurfaceBright
                                    : com.google.android.material.R.attr.colorOnSurfaceInverse;
                    case SURFACE_HIGH -> com.google.android.material.R.attr
                            .colorSurfaceContainerHigh;
                    case PRIMARY -> com.google.android.material.R.attr.colorPrimaryContainer;
                    case SECONDARY, RECEIVED_COLORFUL ->
                            com.google.android.material.R.attr.colorSecondaryContainer;
                    case SENT_COLORFUL -> com.google.android.material.R.attr.colorPrimaryContainer;
                    case TERTIARY -> com.google.android.material.R.attr.colorTertiaryContainer;
                    case WARNING -> com.google.android.material.R.attr.colorErrorContainer;
                };
        return ColorStateList.valueOf(MaterialColors.getColor(view, colorAttributeResId));
    }

    public static void setImageTint(final ImageView imageView, final BubbleColor bubbleColor) {
        ImageViewCompat.setImageTintList(
                imageView, bubbleToOnSurfaceColorStateList(imageView, bubbleColor));
    }

    public static void setImageTintError(final ImageView imageView) {
        ImageViewCompat.setImageTintList(
                imageView,
                ColorStateList.valueOf(
                        MaterialColors.getColor(imageView, androidx.appcompat.R.attr.colorError)));
    }

    public static void setTextColor(final TextView textView, final BubbleColor bubbleColor) {
        final var color = bubbleToOnSurfaceColor(textView, bubbleColor);
        textView.setTextColor(color);
        if (BubbleColor.SURFACES.contains(bubbleColor)) {
            textView.setLinkTextColor(
                    MaterialColors.getColor(textView, androidx.appcompat.R.attr.colorPrimary));
        } else {
            textView.setLinkTextColor(color);
        }
    }

    private static void setTextSize(final TextView textView, final boolean largeFont) {
        textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, largeFont ? 18 : 14);
    }

    private static void setSmallTextSize(final TextView textView, final boolean largeFont) {
        textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, largeFont ? 16 : 12);
    }

    private static @ColorInt int bubbleToOnSurfaceVariant(
            final View view, final BubbleColor bubbleColor) {
        final @AttrRes int colorAttributeResId;
        if (BubbleColor.SURFACES.contains(bubbleColor)) {
            colorAttributeResId = com.google.android.material.R.attr.colorOnSurfaceVariant;
        } else {
            colorAttributeResId = bubbleToOnSurface(bubbleColor);
        }
        return MaterialColors.getColor(view, colorAttributeResId);
    }

    private static @ColorInt int bubbleToOnSurfaceColor(
            final View view, final BubbleColor bubbleColor) {
        return MaterialColors.getColor(view, bubbleToOnSurface(bubbleColor));
    }

    public static ColorStateList bubbleToOnSurfaceColorStateList(
            final View view, final BubbleColor bubbleColor) {
        return ColorStateList.valueOf(bubbleToOnSurfaceColor(view, bubbleColor));
    }

    private static @AttrRes int bubbleToOnSurface(final BubbleColor bubbleColor) {
        return switch (bubbleColor) {
            // Colourful bubbles deliberately use neutral onSurface (black/white) text rather than
            // the container's coloured on-colour.
            case SURFACE, SURFACE_HIGH, RECEIVED_COLORFUL, SENT_COLORFUL ->
                    com.google.android.material.R.attr.colorOnSurface;
            case PRIMARY -> com.google.android.material.R.attr.colorOnPrimaryContainer;
            case SECONDARY -> com.google.android.material.R.attr.colorOnSecondaryContainer;
            case TERTIARY -> com.google.android.material.R.attr.colorOnTertiaryContainer;
            case WARNING -> com.google.android.material.R.attr.colorOnErrorContainer;
        };
    }

    public enum BubbleColor {
        SURFACE,
        SURFACE_HIGH,
        PRIMARY,
        SECONDARY,
        TERTIARY,
        WARNING,
        // "Colourful chat bubbles": a themed container background (so it follows custom themes and
        // dynamic colours, and is light in light mode / dark in dark mode) paired with neutral
        // black/white onSurface text instead of the role's coloured on-container text.
        RECEIVED_COLORFUL, // secondary container
        SENT_COLORFUL; // primary container

        private static final Collection<BubbleColor> SURFACES =
                Arrays.asList(BubbleColor.SURFACE, BubbleColor.SURFACE_HIGH);
    }

    private static class BubbleDesign {
        public final boolean colorfulChatBubbles;
        public final boolean alignStart;
        public final boolean largeFont;
        public final boolean showAvatars;

        private BubbleDesign(
                final boolean colorfulChatBubbles,
               final boolean alignStart,
                final boolean largeFont,
                final boolean showAvatars) {
            this.colorfulChatBubbles = colorfulChatBubbles;
            this.alignStart = alignStart;
            this.largeFont = largeFont;
            this.showAvatars = showAvatars;
        }
    }

    abstract static class MessageItemViewHolder extends RecyclerView.ViewHolder {

        final View itemView;
        public int position;

        private MessageItemViewHolder(@NonNull View itemView) {
            super(itemView);
            this.itemView = itemView;
        }
    }

    private abstract static class BubbleMessageItemViewHolder extends MessageItemViewHolder {

        private BubbleMessageItemViewHolder(@NonNull View itemView) {
            super(itemView);
        }

        public abstract ConstraintLayout root();

        protected abstract ImageView indicatorEdit();

        protected abstract RelativeLayout audioPlayer();

        protected abstract LinearLayout messageBox();

        protected abstract MaterialButton downloadButton();

        protected abstract ShapeableImageView image();

        protected abstract ImageView indicatorSecurity();

        protected abstract ImageView indicatorReceived();

        protected abstract TextView time();

        protected abstract CollapsableTextView messageBody();

        protected abstract ImageView contactPicture();

        protected abstract ChipGroup reactions();

        protected abstract ListView commandsList();

        protected abstract View messageBoxInner();

        protected abstract View statusLine();

        protected abstract GithubIdenticonView threadIdenticon();

        protected abstract ListView linkDescriptions();

        protected abstract CardView inReplyToBox();

        protected abstract TextView inReplyTo();

        protected abstract TextView inReplyToQuote();

        protected abstract TextView subject();

        protected abstract ImageView indicatorEphemeral();

        protected abstract TextView username();

        protected abstract TextView showMore();
        protected abstract AlbumLayout album();

        protected abstract LinearLayout attachments();

        protected abstract LinearLayout storyPreview();
        protected abstract ShapeableImageView storyThumbnail();
        protected abstract TextView storyTitle();
    }

    private static class StartBubbleMessageItemViewHolder extends BubbleMessageItemViewHolder {

        private final ItemMessageStartBinding binding;

        public StartBubbleMessageItemViewHolder(@NonNull ItemMessageStartBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        @Override
        public ConstraintLayout root() {
            return (ConstraintLayout) this.binding.getRoot();
        }

        @Override
        protected ImageView indicatorEdit() {
            return this.binding.editIndicator;
        }

        @Override
        protected ImageView indicatorEphemeral() {
            return this.binding.ephemeralIndicator;
        }

        @Override
        protected RelativeLayout audioPlayer() {
            return this.binding.messageContent.audioPlayer;
        }

        @Override
        protected LinearLayout messageBox() {
            return this.binding.messageBox;
        }

        @Override
        protected MaterialButton downloadButton() {
            return this.binding.messageContent.downloadButton;
        }

        @Override
        protected ShapeableImageView image() {
            return this.binding.messageContent.messageImage;
        }

        protected ImageView indicatorSecurity() {
            return this.binding.securityIndicator;
        }

        @Override
        protected ImageView indicatorReceived() {
            return this.binding.indicatorReceived;
        }

        @Override
        protected TextView time() {
            return this.binding.messageTime;
        }

        @Override
        protected CollapsableTextView messageBody() {
            return this.binding.messageContent.messageBody;
        }

        protected TextView encryption() {
            return this.binding.messageEncryption;
        }

        @Override
        protected ImageView contactPicture() {
            return this.binding.messagePhoto;
        }

        @Override
        protected ChipGroup reactions() {
            return this.binding.reactions;
        }

        @Override
        protected ListView commandsList() {
            return this.binding.messageContent.commandsList;
        }

        @Override
        protected View messageBoxInner() {
            return this.binding.messageBoxInner;
        }

        @Override
        protected View statusLine() {
            return this.binding.statusLine;
        }

        @Override
        protected TextView username() {
            return this.binding.messageUsername;
        }

        @Override
        protected GithubIdenticonView threadIdenticon() {
            return this.binding.threadIdenticon;
        }

        @Override
        protected ListView linkDescriptions() {
            return this.binding.messageContent.linkDescriptions;
        }

        @Override
        protected CardView inReplyToBox() {
            return this.binding.messageContent.inReplyToBox;
        }

        @Override
        protected TextView inReplyTo() {
            return this.binding.messageContent.inReplyTo;
        }

        @Override
        protected TextView inReplyToQuote() {
            return this.binding.messageContent.inReplyToQuote;
        }

        @Override
        protected TextView subject() {
            return this.binding.messageSubject;
        }

        @Override
        protected TextView showMore() {
            return this.binding.messageContent.showMore;
        }

        @Override
        protected AlbumLayout album() {
            return this.binding.messageContent.album;
        }

        @Override
        protected LinearLayout attachments() {
            return this.binding.messageContent.attachments;
        }

        @Override
        protected LinearLayout storyPreview() {
            return this.binding.messageContent.storyPreview;
        }

        @Override
        protected ShapeableImageView storyThumbnail() {
            return this.binding.messageContent.storyThumbnail;
        }

        @Override
        protected TextView storyTitle() {
            return this.binding.messageContent.storyTitle;
        }
    }

    private static class EndBubbleMessageItemViewHolder extends BubbleMessageItemViewHolder {

        private final ItemMessageEndBinding binding;

        private EndBubbleMessageItemViewHolder(@NonNull ItemMessageEndBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        @Override
        public ConstraintLayout root() {
            return (ConstraintLayout) this.binding.getRoot();
        }

        @Override
        protected TextView username() {
            return null;
        }

        @Override
        protected TextView showMore() {
            return this.binding.messageContent.showMore;
        }

        @Override
        protected AlbumLayout album() {
            return this.binding.messageContent.album;
        }

        @Override
        protected LinearLayout attachments() {
            return this.binding.messageContent.attachments;
        }

        @Override
        protected LinearLayout storyPreview() {
            return this.binding.messageContent.storyPreview;
        }

        @Override
        protected ShapeableImageView storyThumbnail() {
            return this.binding.messageContent.storyThumbnail;
        }

        @Override
        protected TextView storyTitle() {
            return this.binding.messageContent.storyTitle;
        }

        @Override
        protected ImageView indicatorEdit() {
            return this.binding.editIndicator;
        }

        @Override
        protected ImageView indicatorEphemeral() {
            return this.binding.ephemeralIndicator;
        }

        @Override
        protected RelativeLayout audioPlayer() {
            return this.binding.messageContent.audioPlayer;
        }

        @Override
        protected LinearLayout messageBox() {
            return this.binding.messageBox;
        }

        @Override
        protected MaterialButton downloadButton() {
            return this.binding.messageContent.downloadButton;
        }

        @Override
        protected ShapeableImageView image() {
            return this.binding.messageContent.messageImage;
        }

        @Override
        protected ImageView indicatorSecurity() {
            return this.binding.securityIndicator;
        }

        @Override
        protected ImageView indicatorReceived() {
            return this.binding.indicatorReceived;
        }

        @Override
        protected TextView time() {
            return this.binding.messageTime;
        }

        @Override
        protected CollapsableTextView messageBody() {
            return this.binding.messageContent.messageBody;
        }

        @Override
        protected ImageView contactPicture() {
            return this.binding.messagePhoto;
        }

        @Override
        protected ChipGroup reactions() {
            return this.binding.reactions;
        }

        @Override
        protected ListView commandsList() {
            return this.binding.messageContent.commandsList;
        }

        @Override
        protected View messageBoxInner() {
            return this.binding.messageBoxInner;
        }

        @Override
        protected View statusLine() {
            return this.binding.statusLine;
        }

        @Override
        protected GithubIdenticonView threadIdenticon() {
            return this.binding.threadIdenticon;
        }

        @Override
        protected ListView linkDescriptions() {
            return this.binding.messageContent.linkDescriptions;
        }

        @Override
        protected CardView inReplyToBox() {
            return this.binding.messageContent.inReplyToBox;
        }

        @Override
        protected TextView inReplyTo() {
            return this.binding.messageContent.inReplyTo;
        }

        @Override
        protected TextView inReplyToQuote() {
            return this.binding.messageContent.inReplyToQuote;
        }

        @Override
        protected TextView subject() {
            return this.binding.messageSubject;
        }
    }

    private static class DateSeperatorMessageItemViewHolder extends MessageItemViewHolder {

        private final ItemMessageDateBubbleBinding binding;

        private DateSeperatorMessageItemViewHolder(@NonNull ItemMessageDateBubbleBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }

    private static class RtpSessionMessageItemViewHolder extends MessageItemViewHolder {

        private final ItemMessageRtpSessionBinding binding;

        private RtpSessionMessageItemViewHolder(@NonNull ItemMessageRtpSessionBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }

    private static class StatusMessageItemViewHolder extends MessageItemViewHolder {

        private final ItemMessageStatusBinding binding;

        private StatusMessageItemViewHolder(@NonNull ItemMessageStatusBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }

    class Thumbnailer implements GetThumbnailForCid {
        final Account account;
        final boolean canFetch;
        final Jid counterpart;

        public Thumbnailer(final Message message) {
            account = message.getConversation().getAccount();
            final boolean encrypted = message.getEncryption() != Message.ENCRYPTION_NONE;
            canFetch = !encrypted && (message.trusted() || message.getConversation().canInferPresence());
            counterpart = message.getCounterpart();
        }

        public Thumbnailer(final Account account, final Reaction reaction, final boolean allowFetch) {
            canFetch = allowFetch;
            counterpart = reaction.from;
            this.account = account;
        }

        @Override
        public Drawable getThumbnail(Cid cid) {
            try {
                DownloadableFile f = activity.xmppConnectionService.getFileForCid(cid);
                if (f == null || !f.canRead()) {
                    if (!canFetch) return null;

                    try {
                        new BobTransfer(BobTransfer.uri(cid), account, counterpart, activity.xmppConnectionService).start();
                    } catch (final NoSuchAlgorithmException | URISyntaxException ignored) { }
                    return null;
                }

                Drawable d = activity.xmppConnectionService.getFileBackend().getThumbnail(f, activity.getResources(), (int) (metrics.density * 288), true);
                if (d == null) {
                    warmThumbnailCache(f);
                }
                return d;
            } catch (final IOException e) {
                return null;
            }
        }
    }

    /**
     * Generate a thumbnail off the UI thread (warming the file-backend cache) and then refresh the
     * conversation so the now-cached thumbnail is picked up. Replaces the deprecated
     * {@code AsyncTask}-based {@code ThumbnailTask}.
     */
    private void warmThumbnailCache(final DownloadableFile file) {
        THUMBNAIL_EXECUTOR.execute(
                () -> {
                    try {
                        activity.xmppConnectionService
                                .getFileBackend()
                                .getThumbnail(
                                        file,
                                        activity.getResources(),
                                        (int) (metrics.density * 288),
                                        false);
                    } catch (final IOException e) {
                        // Thumbnail simply stays unavailable.
                    }
                    if (activity != null && activity.xmppConnectionService != null) {
                        activity.runOnUiThread(
                                () -> activity.xmppConnectionService.updateConversationUi());
                    }
                });
    }

    private Conversation wrap(Conversational conversational) {
        if (conversational instanceof Conversation) {
            return (Conversation) conversational;
        } else {
            return activity.xmppConnectionService.findOrCreateConversation(conversational.getAccount(),
                    conversational.getJid(),
                    conversational.getMode() == Conversational.MODE_MULTI,
                    true,
                    true);
        }
    }

    public interface ReplyClickListener {
        void onReplyClick(Message message);
    }

    public interface OnDateSeparatorClickListener {
        void onDateSeparatorClick(long timestamp);
    }

    private OnMessageLongPressListener mOnMessageLongPressListener;

    public interface OnMessageLongPressListener {
        void onMessageLongPress(Message message, View messageView, float rawX, float rawY);
    }

    public void setOnMessageLongPressListener(OnMessageLongPressListener listener) {
        this.mOnMessageLongPressListener = listener;
    }

}