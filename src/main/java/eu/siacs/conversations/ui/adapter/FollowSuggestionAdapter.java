package eu.siacs.conversations.ui.adapter;

import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.Contact;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.ui.PostsActivity;
import eu.siacs.conversations.ui.util.AvatarWorkerTask;

public class FollowSuggestionAdapter extends RecyclerView.Adapter<FollowSuggestionAdapter.ViewHolder> {

    private final PostsActivity mPostsActivity;
    private final List<Contact> mContacts;
    private final XmppConnectionService mXmppConnectionService;

    public FollowSuggestionAdapter(PostsActivity activity, XmppConnectionService service, List<Contact> contacts) {
        this.mPostsActivity = activity;
        this.mXmppConnectionService = service;
        this.mContacts = contacts;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_follow_suggestion, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        final Contact contact = mContacts.get(position);
        holder.mContactName.setText(contact.getDisplayName());
        AvatarWorkerTask.loadAvatar(contact, holder.mAvatar, R.dimen.feed_suggestions_avatar_size);
        holder.mAvatar.setOnClickListener(v -> mPostsActivity.switchToContactDetails(contact));
        holder.mFollowButton.setOnClickListener(v -> {
            contact.setFollowed(true);
            mXmppConnectionService.updateContact(contact);
            mContacts.remove(contact);
            notifyDataSetChanged();
            mPostsActivity.loadPosts(); // Refresh posts to show new content
        });
    }

    @Override
    public int getItemCount() {
        return mContacts.size();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        final ImageView mAvatar;
        final TextView mContactName;
        final Button mFollowButton;

        ViewHolder(View view) {
            super(view);
            mAvatar = view.findViewById(R.id.contact_avatar);
            mContactName = view.findViewById(R.id.contact_name);
            mFollowButton = view.findViewById(R.id.follow_button);
        }
    }
}