package eu.siacs.conversations.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.text.DateFormat;
import java.util.List;
import eu.siacs.conversations.R;
import eu.siacs.conversations.databinding.ItemCommentBinding;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Comment;
import eu.siacs.conversations.entities.Contact;
import eu.siacs.conversations.ui.XmppActivity;
import eu.siacs.conversations.ui.util.AvatarWorkerTask;
import eu.siacs.conversations.utils.AccountUtils;
import eu.siacs.conversations.xmpp.Jid;

public class CommentsAdapter extends RecyclerView.Adapter<CommentsAdapter.CommentViewHolder> {

    private final List<Comment> comments;
    private final XmppActivity mActivity;

    public CommentsAdapter(XmppActivity activity, List<Comment> comments) {
        this.mActivity = activity;
        this.comments = comments;
    }

    @NonNull
    @Override
    public CommentViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new CommentViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_comment, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull CommentViewHolder holder, int position) {
        holder.bind(comments.get(position));
    }

    @Override
    public int getItemCount() {
        return comments.size();
    }

    class CommentViewHolder extends RecyclerView.ViewHolder {

        private final ItemCommentBinding binding;

        CommentViewHolder(@NonNull View itemView) {
            super(itemView);
            binding = ItemCommentBinding.bind(itemView);
        }

        void bind(Comment comment) {
            if (comment.getAuthor() != null) {
                final Jid authorJid = comment.getAuthor();
                if (mActivity.xmppConnectionService != null) {
                    Account account = AccountUtils.getFirstEnabled(mActivity.xmppConnectionService.getAccounts());
                    if (account != null) {
                        if (authorJid.asBareJid().equals(account.getJid().asBareJid())) {
                            binding.commentAuthorName.setText(account.getDisplayName());
                            AvatarWorkerTask.loadAvatar(account, binding.commentAuthorAvatar, R.dimen.posts_comments_avatar_size);
                        } else {
                            Contact contact = account.getRoster().getContact(authorJid);
                            if (contact != null) {
                                binding.commentAuthorName.setText(contact.getDisplayName());
                                AvatarWorkerTask.loadAvatar(contact, binding.commentAuthorAvatar, R.dimen.posts_comments_avatar_size);
                            } else {
                                binding.commentAuthorName.setText(authorJid.asBareJid().toString());
                                binding.commentAuthorAvatar.setImageResource(R.drawable.ic_person_24dp);
                            }
                        }
                    } else {
                        binding.commentAuthorName.setText(authorJid.asBareJid().toString());
                        binding.commentAuthorAvatar.setImageResource(R.drawable.ic_person_24dp);
                    }
                }
            } else {
                binding.commentAuthorName.setText(null);
                binding.commentAuthorAvatar.setImageResource(R.drawable.ic_person_24dp);
            }
            binding.commentContent.setText(comment.getTitle());
            if (comment.getPublished() != null) {
                binding.commentTimestamp.setText(DateFormat.getDateTimeInstance().format(comment.getPublished()));
            } else {
                binding.commentTimestamp.setText(null);
            }
        }
    }
}