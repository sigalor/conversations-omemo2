package eu.siacs.conversations.ui.adapter;

import android.text.SpannableStringBuilder;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.ListItem;
import eu.siacs.conversations.ui.XmppActivity;
import eu.siacs.conversations.ui.util.AvatarWorkerTask;
import eu.siacs.conversations.utils.StylingHelper;
import eu.siacs.conversations.xmpp.Jid;

/**
 * RecyclerView adapter for the "Conversations" section of the message-search screen: contacts
 * and group bookmarks whose name or address matches the query. Tapping a row opens that chat.
 * This is a local view over the already-loaded roster and bookmarks — it issues no network or
 * database queries of its own.
 */
public class ConversationSearchAdapter
        extends RecyclerView.Adapter<ConversationSearchAdapter.ConversationViewHolder> {

    public interface OnConversationClickedListener {
        void onConversationClicked(ListItem item);
    }

    private final XmppActivity activity;
    private final List<ListItem> items = new ArrayList<>();
    private List<String> highlightedTerms;
    private OnConversationClickedListener listener;

    public ConversationSearchAdapter(final XmppActivity activity) {
        this.activity = activity;
        setHasStableIds(true);
    }

    public void setOnConversationClickedListener(final OnConversationClickedListener listener) {
        this.listener = listener;
    }

    public void setHighlightedTerm(final List<String> terms) {
        this.highlightedTerms = terms == null ? null : StylingHelper.filterHighlightedWords(terms);
    }

    public void submitItems(final List<ListItem> newItems) {
        final List<ListItem> old = new ArrayList<>(items);
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
                                return key(old.get(o)).equals(key(newItems.get(n)));
                            }

                            @Override
                            public boolean areContentsTheSame(int o, int n) {
                                final ListItem a = old.get(o);
                                final ListItem b = newItems.get(n);
                                return key(a).equals(key(b))
                                        && a.getDisplayName().equals(b.getDisplayName());
                            }
                        });
        items.clear();
        items.addAll(newItems);
        diff.dispatchUpdatesTo(this);
    }

    /** Stable identity for a roster/bookmark row: account + bare JID. */
    private static String key(final ListItem item) {
        final Jid jid = item.getJid();
        final String account = item.getAccount() == null ? "" : item.getAccount().getUuid();
        return account + "/" + (jid == null ? item.getDisplayName() : jid.asBareJid().toString());
    }

    @Override
    public long getItemId(final int position) {
        return key(items.get(position)).hashCode();
    }

    @NonNull
    @Override
    public ConversationViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        final View view =
                LayoutInflater.from(parent.getContext())
                        .inflate(R.layout.item_search_result_conversation, parent, false);
        return new ConversationViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ConversationViewHolder holder, int position) {
        holder.bind(items.get(position));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    class ConversationViewHolder extends RecyclerView.ViewHolder {
        private final ImageView avatar;
        private final TextView name;
        private final TextView jid;

        ConversationViewHolder(final View itemView) {
            super(itemView);
            this.avatar = itemView.findViewById(R.id.avatar);
            this.name = itemView.findViewById(R.id.conversation_name);
            this.jid = itemView.findViewById(R.id.jid);
        }

        void bind(final ListItem item) {
            final String displayName = item.getDisplayName();
            final SpannableStringBuilder builder = new SpannableStringBuilder(displayName);
            if (highlightedTerms != null) {
                StylingHelper.highlight(name, builder, highlightedTerms);
            }
            name.setText(builder);

            final Jid address = item.getJid();
            final String bare = address == null ? null : address.asBareJid().toString();
            if (bare == null || bare.equals(displayName)) {
                jid.setVisibility(View.GONE);
            } else {
                jid.setVisibility(View.VISIBLE);
                jid.setText(bare);
            }

            AvatarWorkerTask.loadAvatar(item, avatar, R.dimen.avatar);

            itemView.setOnClickListener(
                    v -> {
                        if (listener != null) listener.onConversationClicked(item);
                    });
        }
    }
}
