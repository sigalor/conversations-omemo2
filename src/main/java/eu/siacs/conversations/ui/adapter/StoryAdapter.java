package eu.siacs.conversations.ui.adapter;

import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import android.os.Handler;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.bumptech.glide.Glide;

import java.util.ArrayList;
import java.util.List;
import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Contact;
import eu.siacs.conversations.entities.Story;
import eu.siacs.conversations.ui.StoryViewActivity;
import eu.siacs.conversations.ui.XmppActivity;
import eu.siacs.conversations.xmpp.Jid;

public class StoryAdapter extends RecyclerView.Adapter<StoryAdapter.StoryViewHolder> {

    private final XmppActivity activity;
    private final List<Story> stories;

    private final Handler handler = new Handler();
    private final Runnable refreshRunnable;
    private RecyclerView recyclerView;

    public StoryAdapter(XmppActivity activity, List<Story> stories) {
        this.activity = activity;
        this.stories = stories;
        this.refreshRunnable = new Runnable() {
            @Override
            public void run() {
                if (recyclerView != null) {
                    // This is a more efficient way to refresh just the visible items
                    final LinearLayoutManager layoutManager = (LinearLayoutManager) recyclerView.getLayoutManager();
                    if (layoutManager != null) {
                        final int first = layoutManager.findFirstVisibleItemPosition();
                        final int last = layoutManager.findLastVisibleItemPosition();
                        if (first != RecyclerView.NO_POSITION) {
                            notifyItemRangeChanged(first, (last - first) + 1, "payload_time");
                        }
                    }
                }
                handler.postDelayed(this, 60000); // Run again in 1 minute
            }
        };
    }

    @NonNull
    @Override
    public StoryViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.list_item_story, parent, false);
        return new StoryViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull StoryViewHolder holder, int position, @NonNull List<Object> payloads) {
        if (payloads.contains("payload_time")) {
            final Story story = stories.get(position);
            holder.storyTime.setText(DateUtils.getRelativeTimeSpanString(story.getPublished(), System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS));
        } else {
            super.onBindViewHolder(holder, position, payloads);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull StoryViewHolder holder, int position) {
        final Story story = stories.get(position);
        final Jid jid = story.getContact();
        Contact contact = null;Account storyAccount = null;
        Drawable avatar = null;

        final int avatarSize = activity.getResources().getDimensionPixelSize(R.dimen.avatar_story_size);

        for (Account account : activity.xmppConnectionService.getAccounts()) {
            final Contact c = account.getRoster().getContact(jid);
            if (c != null) {
                final Drawable d = activity.xmppConnectionService.getAvatarService().get(c, avatarSize);
                if (!(d instanceof eu.siacs.conversations.services.AvatarService.TextDrawable)) {
                    contact = c;
                    storyAccount = account;
                    avatar = d;
                    break;
                }
                if (contact == null) {
                    contact = c;
                    storyAccount = account;
                    avatar = d;
                }
            }
        }

        if (contact == null) {
            storyAccount = activity.xmppConnectionService.findAccountByJid(jid);
            if (storyAccount != null) {
                contact = storyAccount.getSelfContact();
                avatar = activity.xmppConnectionService.getAvatarService().get(contact, avatarSize);
            }
        }

        if (contact != null && avatar != null) {
            holder.storyTitle.setText(contact.getDisplayName());
            holder.storyImage.setImageDrawable(avatar);
        } else if (contact != null) {
            holder.storyTitle.setText(contact.getDisplayName());
            holder.storyImage.setImageResource(R.drawable.ic_person_black_48dp);
        } else {
            holder.storyTitle.setText(jid.asBareJid().toString());
            holder.storyImage.setImageResource(R.drawable.ic_person_black_48dp);
        }

        holder.storyTime.setText(DateUtils.getRelativeTimeSpanString(story.getPublished(), System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS));

        Glide.with(activity).load(story.getUrl()).into(holder.storyPreview);

        final Account finalStoryAccount = storyAccount;
        holder.itemView.setOnClickListener(v -> {
            Intent intent = new Intent(activity, StoryViewActivity.class);
            ArrayList<String> urls = new ArrayList<>();
            ArrayList<String> titles = new ArrayList<>();
            ArrayList<String> storyIds = new ArrayList<>();
            for (Story s : activity.xmppConnectionService.getStories()) {
                if (s.getContact().asBareJid().equals(story.getContact().asBareJid())) {
                    urls.add(s.getUrl());
                    titles.add(s.getTitle());
                    storyIds.add(s.getUuid());
                }
            }
            intent.putStringArrayListExtra(StoryViewActivity.EXTRA_URLS, urls);
            intent.putStringArrayListExtra(StoryViewActivity.EXTRA_TITLES, titles);
            intent.putStringArrayListExtra(StoryViewActivity.EXTRA_STORY_IDS, storyIds);
            intent.putExtra(StoryViewActivity.EXTRA_CONTACT, story.getContact().asBareJid().toString());
            if (finalStoryAccount != null) {
                intent.putExtra(StoryViewActivity.EXTRA_ACCOUNT, finalStoryAccount.getUuid());
            }
            activity.startActivity(intent);
        });
    }

    @Override
    public int getItemCount() {
        return stories.size();
    }

    static class StoryViewHolder extends RecyclerView.ViewHolder {

        final ImageView storyImage;
        final TextView storyTitle;
        final TextView storyTime;
        final ImageView storyPreview;

        StoryViewHolder(@NonNull View itemView) {
            super(itemView);
            storyImage = itemView.findViewById(R.id.story_image);
            storyTitle = itemView.findViewById(R.id.story_title);
            storyTime = itemView.findViewById(R.id.story_time);
            storyPreview = itemView.findViewById(R.id.story_preview);
        }
    }

    @Override
    public void onAttachedToRecyclerView(@NonNull RecyclerView recyclerView) {
        super.onAttachedToRecyclerView(recyclerView);
        this.recyclerView = recyclerView;
        handler.post(refreshRunnable);
    }

    @Override
    public void onDetachedFromRecyclerView(@NonNull RecyclerView recyclerView) {
        super.onDetachedFromRecyclerView(recyclerView);
        handler.removeCallbacks(refreshRunnable);
        this.recyclerView = null;
    }
}
