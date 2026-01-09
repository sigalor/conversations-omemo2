package eu.siacs.conversations.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.text.DateFormat;
import java.util.List;

import eu.siacs.conversations.R;
import eu.siacs.conversations.databinding.ItemPostBinding;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Contact;
import eu.siacs.conversations.entities.Post;
import eu.siacs.conversations.ui.XmppActivity;
import eu.siacs.conversations.ui.util.AvatarWorkerTask;
import eu.siacs.conversations.utils.AccountUtils;

public class PostsAdapter extends RecyclerView.Adapter<PostsAdapter.PostViewHolder> {

    private final List<Post> posts;
    private final XmppActivity mActivity;

    public PostsAdapter(XmppActivity activity, List<Post> posts) {
        this.mActivity = activity;
        this.posts = posts;
    }

    @NonNull
    @Override
    public PostViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new PostViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_post, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull PostViewHolder holder, int position) {
        holder.bind(posts.get(position));
    }

    @Override
    public int getItemCount() {
        return posts.size();
    }

    class PostViewHolder extends RecyclerView.ViewHolder {

        private final ItemPostBinding binding;

        PostViewHolder(@NonNull View itemView) {
            super(itemView);
            binding = ItemPostBinding.bind(itemView);
        }

        void bind(Post post) {
            if (post.getAuthor() != null) {
                binding.postAuthorName.setText(post.getAuthor().asBareJid().toString());
                if (mActivity.xmppConnectionService != null) {
                    Account account = AccountUtils.getFirstEnabled(mActivity.xmppConnectionService.getAccounts());
                    if (account != null) {
                        if (post.getAuthor().equals(account.getJid().asBareJid())) {
                            AvatarWorkerTask.loadAvatar(account, binding.postAuthorAvatar, R.dimen.bubble_avatar_size);
                        } else {
                            Contact contact = account.getRoster().getContact(post.getAuthor());
                            if (contact != null) {
                                AvatarWorkerTask.loadAvatar(contact, binding.postAuthorAvatar, R.dimen.bubble_avatar_size);
                            } else {
                                binding.postAuthorAvatar.setImageResource(R.drawable.ic_person_24dp);
                            }
                        }
                    } else {
                        binding.postAuthorAvatar.setImageResource(R.drawable.ic_person_24dp);
                    }
                }
            } else {
                binding.postAuthorName.setText(null);
                binding.postAuthorAvatar.setImageResource(R.drawable.ic_person_24dp);
            }
            binding.postTitle.setText(post.getTitle());
            binding.postContent.setText(post.getContent());
            if (post.getPublished() != null) {
                binding.postTimestamp.setText(DateFormat.getDateTimeInstance().format(post.getPublished()));
            } else {
                binding.postTimestamp.setText(null);
            }
        }
    }
}