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
import eu.siacs.conversations.xmpp.Jid;

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
                final Jid authorJid = post.getAuthor();
                if (mActivity.xmppConnectionService != null) {
                    Account account = AccountUtils.getFirstEnabled(mActivity.xmppConnectionService.getAccounts());
                    if (account != null) {
                        if (authorJid.asBareJid().equals(account.getJid().asBareJid())) {
                            binding.postAuthorName.setText(account.getDisplayName());
                            AvatarWorkerTask.loadAvatar(account, binding.postAuthorAvatar, R.dimen.bubble_avatar_size);
                            final Contact self = account.getSelfContact();
                            binding.postAuthorAvatar.setOnClickListener(v -> mActivity.switchToContactDetails(self));
                            binding.postAuthorName.setOnClickListener(v -> mActivity.switchToContactDetails(self));
                        } else {
                            Contact contact = account.getRoster().getContact(authorJid);
                            if (contact != null) {
                                binding.postAuthorName.setText(contact.getDisplayName());
                                AvatarWorkerTask.loadAvatar(contact, binding.postAuthorAvatar, R.dimen.bubble_avatar_size);
                                binding.postAuthorAvatar.setOnClickListener(v -> mActivity.switchToContactDetails(contact));
                                binding.postAuthorName.setOnClickListener(v -> mActivity.switchToContactDetails(contact));
                            } else {
                                binding.postAuthorName.setText(authorJid.asBareJid().toString());
                                binding.postAuthorAvatar.setImageResource(R.drawable.ic_person_24dp);
                                binding.postAuthorAvatar.setOnClickListener(null);
                                binding.postAuthorName.setOnClickListener(null);
                            }
                        }
                    } else {
                        binding.postAuthorName.setText(authorJid.asBareJid().toString());
                        binding.postAuthorAvatar.setImageResource(R.drawable.ic_person_24dp);
                        binding.postAuthorAvatar.setOnClickListener(null);
                        binding.postAuthorName.setOnClickListener(null);
                    }
                } else {
                    binding.postAuthorName.setText(authorJid.asBareJid().toString());
                    binding.postAuthorAvatar.setOnClickListener(null);
                    binding.postAuthorName.setOnClickListener(null);
                }
            } else {
                binding.postAuthorName.setText(null);
                binding.postAuthorAvatar.setImageResource(R.drawable.ic_person_24dp);
                binding.postAuthorAvatar.setOnClickListener(null);
                binding.postAuthorName.setOnClickListener(null);
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