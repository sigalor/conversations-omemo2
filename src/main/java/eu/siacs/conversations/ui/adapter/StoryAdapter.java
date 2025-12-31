package eu.siacs.conversations.ui.adapter;

import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

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

    public StoryAdapter(XmppActivity activity, List<Story> stories) {
        this.activity = activity;
        this.stories = stories;
    }

    @NonNull
    @Override
    public StoryViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.list_item_story, parent, false);
        return new StoryViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull StoryViewHolder holder, int position) {
        final Story story = stories.get(position);
        final Jid jid = story.getContact();
        Contact contact = null;
        Account storyAccount = null;

        // Check if the story author is one of our own accounts
        storyAccount = activity.xmppConnectionService.findAccountByJid(jid);

        if (storyAccount != null) {
            // It's our own story
            contact = storyAccount.getSelfContact();
        } else {
            // It's from someone else. Find which of our accounts knows them.
            for (Account account : activity.xmppConnectionService.getAccounts()) {
                contact = account.getRoster().getContact(jid);
                if (contact != null) {
                    storyAccount = account; // The account that has this contact in its roster
                    break;
                }
            }
        }

        if (contact != null) {
            holder.storyTitle.setText(contact.getDisplayName());
            holder.storyImage.setImageDrawable(activity.xmppConnectionService.getAvatarService().get(contact, activity.getResources().getDimensionPixelSize(R.dimen.avatar_story_size)));
        } else {
            holder.storyTitle.setText(jid.asBareJid().toString());
            holder.storyImage.setImageResource(R.drawable.ic_person_black_48dp);
        }

        Glide.with(activity).load(story.getUrl()).into(holder.storyPreview);

        final Account finalStoryAccount = storyAccount;
        holder.itemView.setOnClickListener(v -> {
            Intent intent = new Intent(activity, StoryViewActivity.class);
            ArrayList<String> urls = new ArrayList<>();
            ArrayList<String> titles = new ArrayList<>();
            ArrayList<String> storyIds = new ArrayList<>();

            // This is the corrected logic: Get the FULL list from the service
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
        final ImageView storyPreview;

        StoryViewHolder(@NonNull View itemView) {
            super(itemView);
            storyImage = itemView.findViewById(R.id.story_image);
            storyTitle = itemView.findViewById(R.id.story_title);
            storyPreview = itemView.findViewById(R.id.story_preview);
        }
    }
}
