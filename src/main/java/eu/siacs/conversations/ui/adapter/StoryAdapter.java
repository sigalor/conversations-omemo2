package eu.siacs.conversations.ui.adapter;

import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
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
        Contact contact;
        Account storyAccount;

        // Check if the story author is one of our own accounts
        storyAccount = activity.xmppConnectionService.findAccountByJid(jid);

        if (storyAccount != null) {
            // It's our own story
            contact = storyAccount.getSelfContact();
        } else {
            // It's from someone else. Find which of our accounts knows them.
            contact = null;
            storyAccount = null;
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
        final Account finalStoryAccount = storyAccount;
        holder.itemView.setOnClickListener(v -> {
            Intent intent = new Intent(activity, StoryViewActivity.class);
            intent.putExtra(StoryViewActivity.EXTRA_URL, story.getUrl());
            if (finalStoryAccount != null) {
                intent.putExtra(StoryViewActivity.EXTRA_ACCOUNT, finalStoryAccount.getUuid());
            }
            intent.putExtra(StoryViewActivity.EXTRA_TITLE, story.getTitle());
            intent.putExtra(StoryViewActivity.EXTRA_STORY_ID, story.getUuid());
            intent.putExtra(StoryViewActivity.EXTRA_CONTACT, story.getContact().asBareJid().toString());
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

        StoryViewHolder(@NonNull View itemView) {
            super(itemView);
            storyImage = itemView.findViewById(R.id.story_image);
            storyTitle = itemView.findViewById(R.id.story_title);
        }
    }
}
