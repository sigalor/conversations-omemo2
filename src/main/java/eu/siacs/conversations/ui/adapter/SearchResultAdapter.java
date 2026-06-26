/*
 * Copyright (c) 2018, Daniel Gultsch All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation and/or
 * other materials provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors
 * may be used to endorse or promote products derived from this software without
 * specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package eu.siacs.conversations.ui.adapter;

import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.imageview.ShapeableImageView;

import java.util.ArrayList;
import java.util.List;

import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Conversational;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.ui.XmppActivity;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.ui.util.AvatarWorkerTask;
import eu.siacs.conversations.utils.StylingHelper;
import eu.siacs.conversations.utils.UIHelper;

/**
 * Dedicated RecyclerView adapter for the message-search screen. Unlike the chat
 * {@link MessageAdapter} (a heavyweight, chat-shared ArrayAdapter) this renders compact
 * result rows: text snippets with the search term highlighted, inline media thumbnails, and
 * file rows — each optionally prefixed with the conversation it was found in.
 */
public class SearchResultAdapter
        extends RecyclerView.Adapter<SearchResultAdapter.ResultViewHolder> {

    public static final int TYPE_TEXT = 0;
    public static final int TYPE_MEDIA = 1;
    public static final int TYPE_FILE = 2;

    public interface OnResultActionListener {
        /** Row tapped — jump to the message in its conversation. */
        void onResultClicked(Message message);

        /** Inline media thumbnail tapped — open the media viewer. */
        void onMediaClicked(Message message);

        /** Row long-pressed — show the result context menu anchored at the view. */
        void onResultLongClicked(View anchor, Message message);

        /** Avatar tapped — open contact/account details. */
        void onContactPictureClicked(Message message);
    }

    private final XmppActivity activity;
    private final boolean showConversationContext;
    private final float density;
    private final List<Message> items = new ArrayList<>();
    private List<String> highlightedTerms;
    private OnResultActionListener listener;

    public SearchResultAdapter(final XmppActivity activity, final boolean showConversationContext) {
        this.activity = activity;
        this.showConversationContext = showConversationContext;
        final DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
        this.density = metrics.density;
        setHasStableIds(true);
    }

    public void setOnResultActionListener(final OnResultActionListener listener) {
        this.listener = listener;
    }

    public void setHighlightedTerm(final List<String> terms) {
        this.highlightedTerms = terms == null ? null : StylingHelper.filterHighlightedWords(terms);
    }

    /** Classify a message into one of the row view types. Reused by the search filters. */
    public static int viewTypeFor(final Message message) {
        if (message.isFileOrImage()
                && message.getEncryption() != Message.ENCRYPTION_PGP
                && message.getEncryption() != Message.ENCRYPTION_DECRYPTION_FAILED) {
            final Message.FileParams fp = message.getFileParams();
            final String mediaType = fp.getMediaType();
            final boolean hasDimensions = fp.width > 0 && fp.height > 0;
            final boolean isVideo = mediaType != null && mediaType.startsWith("video/");
            if (hasDimensions || isVideo) {
                return TYPE_MEDIA;
            }
            return TYPE_FILE;
        }
        return TYPE_TEXT;
    }

    public void submitItems(final List<Message> newItems) {
        final List<Message> old = new ArrayList<>(items);
        final DiffUtil.DiffResult diff =
                DiffUtil.calculateDiff(
                        new DiffUtil.Callback() {
                            @Override
                            public int getOldListSize() {
                                return old.size();
                            }

                            @Override
                            public int getNewListSize() {
                                return newItems.size();
                            }

                            @Override
                            public boolean areItemsTheSame(int o, int n) {
                                final String a = old.get(o).getUuid();
                                final String b = newItems.get(n).getUuid();
                                return a != null && a.equals(b);
                            }

                            @Override
                            public boolean areContentsTheSame(int o, int n) {
                                return old.get(o) == newItems.get(n);
                            }
                        });
        items.clear();
        items.addAll(newItems);
        diff.dispatchUpdatesTo(this);
    }

    @Override
    public long getItemId(final int position) {
        final String uuid = items.get(position).getUuid();
        return uuid == null ? RecyclerView.NO_ID : uuid.hashCode();
    }

    @Override
    public int getItemViewType(final int position) {
        return viewTypeFor(items.get(position));
    }

    @NonNull
    @Override
    public ResultViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        final LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        final int layout =
                switch (viewType) {
                    case TYPE_MEDIA -> R.layout.item_search_result_media;
                    case TYPE_FILE -> R.layout.item_search_result_file;
                    default -> R.layout.item_search_result_text;
                };
        return new ResultViewHolder(inflater.inflate(layout, parent, false), viewType);
    }

    @Override
    public void onBindViewHolder(@NonNull ResultViewHolder holder, int position) {
        final Message message = items.get(position);
        holder.bind(message);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    /** Display name of the conversation a result belongs to (falls back to the JID for stubs). */
    private static CharSequence conversationName(final Message message) {
        final Conversational conversational = message.getConversation();
        if (conversational instanceof Conversation conversation) {
            return conversation.getName();
        }
        final Jid jid = conversational.getJid();
        return jid == null ? "" : jid.asBareJid().toString();
    }

    private void applyHighlight(final TextView view, final String text) {
        final SpannableStringBuilder builder = new SpannableStringBuilder(text == null ? "" : text);
        if (highlightedTerms != null) {
            StylingHelper.highlight(view, builder, highlightedTerms);
        }
        view.setText(builder);
    }

    class ResultViewHolder extends RecyclerView.ViewHolder {
        private final int viewType;
        private final ImageView avatar;
        private final TextView conversationName;
        private final TextView timestamp;
        private final TextView senderName;
        private final TextView snippet; // text snippet OR media/file caption
        private final ShapeableImageView media; // TYPE_MEDIA only
        private final ImageView fileIcon; // TYPE_FILE only
        private final TextView fileName; // TYPE_FILE only

        ResultViewHolder(final View itemView, final int viewType) {
            super(itemView);
            this.viewType = viewType;
            this.avatar = itemView.findViewById(R.id.avatar);
            this.conversationName = itemView.findViewById(R.id.conversation_name);
            this.timestamp = itemView.findViewById(R.id.timestamp);
            this.senderName = itemView.findViewById(R.id.sender_name);
            this.snippet = itemView.findViewById(R.id.snippet);
            this.media = itemView.findViewById(R.id.media);
            this.fileIcon = itemView.findViewById(R.id.file_icon);
            this.fileName = itemView.findViewById(R.id.file_name);
        }

        void bind(final Message message) {
            bindHeader(message);

            switch (viewType) {
                case TYPE_MEDIA -> bindMedia(message);
                case TYPE_FILE -> bindFile(message);
                default -> bindText(message);
            }

            itemView.setOnClickListener(
                    v -> {
                        if (listener != null) listener.onResultClicked(message);
                    });
            itemView.setOnLongClickListener(
                    v -> {
                        if (listener != null) {
                            listener.onResultLongClicked(v, message);
                            return true;
                        }
                        return false;
                    });
        }

        private void bindHeader(final Message message) {
            final CharSequence convName = conversationName(message);
            if (showConversationContext) {
                conversationName.setVisibility(View.VISIBLE);
                conversationName.setText(convName);
                if (avatar != null) {
                    avatar.setVisibility(View.VISIBLE);
                    AvatarWorkerTask.loadAvatar(message, avatar, R.dimen.avatar);
                    avatar.setOnClickListener(
                            v -> {
                                if (listener != null) listener.onContactPictureClicked(message);
                            });
                }
            } else {
                conversationName.setVisibility(View.GONE);
                if (avatar != null) avatar.setVisibility(View.GONE);
            }
            timestamp.setText(
                    UIHelper.readableTimeDifference(activity, message.getTimeSent(), true));

            // Sender is only worth showing when it adds information beyond the conversation name,
            // i.e. group participants and our own outgoing messages — not incoming 1:1 messages
            // where it would just repeat the conversation name.
            final String sender = UIHelper.getMessageDisplayName(message);
            final boolean redundant =
                    showConversationContext
                            && convName != null
                            && convName.toString().contentEquals(sender == null ? "" : sender);
            if (TextUtils.isEmpty(sender) || redundant) {
                senderName.setVisibility(View.GONE);
            } else {
                senderName.setVisibility(View.VISIBLE);
                senderName.setText(sender);
            }
        }

        private void bindText(final Message message) {
            applyHighlight(snippet, message.getBody());
        }

        private void bindMedia(final Message message) {
            final Message.FileParams fp = message.getFileParams();
            sizeMediaPreview(fp.width, fp.height);
            activity.loadBitmap(message, media);
            media.setOnClickListener(
                    v -> {
                        if (listener != null) listener.onMediaClicked(message);
                    });
            bindCaption(message);
        }

        private void bindFile(final Message message) {
            if (fileIcon != null) {
                fileIcon.setImageResource(R.drawable.ic_attach_file_24dp);
            }
            final Message.FileParams fp = message.getFileParams();
            final String name = fp.getName();
            fileName.setText(
                    TextUtils.isEmpty(name)
                            ? UIHelper.getFileDescriptionString(activity, message)
                            : name);
            bindCaption(message);
        }

        /** Show the caption (display body with the file URL stripped) only when present. */
        private void bindCaption(final Message message) {
            final String caption = message.getBody();
            if (caption == null || caption.trim().isEmpty()) {
                snippet.setVisibility(View.GONE);
            } else {
                snippet.setVisibility(View.VISIBLE);
                applyHighlight(snippet, caption);
            }
        }

        private void sizeMediaPreview(int w, int h) {
            if (media == null) return;
            final int maxPx = (int) (220 * density);
            final int scaledW;
            final int scaledH;
            if (w <= 0 || h <= 0) {
                scaledW = maxPx;
                scaledH = maxPx;
            } else if (Math.max(w, h) <= maxPx) {
                scaledW = (int) (w * density);
                scaledH = (int) (h * density);
            } else if (w >= h) {
                scaledW = maxPx;
                scaledH = (int) (h / ((double) w / maxPx));
            } else {
                scaledH = maxPx;
                scaledW = (int) (w / ((double) h / maxPx));
            }
            final ViewGroup.LayoutParams params = media.getLayoutParams();
            params.width = Math.max(scaledW, (int) (density * 48));
            params.height = Math.max(scaledH, (int) (density * 48));
            media.setLayoutParams(params);
        }
    }
}
