package eu.siacs.conversations.ui.adapter;

import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.text.DateFormat;
import java.util.List;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.databinding.ItemCommentBinding;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Comment;
import eu.siacs.conversations.entities.Contact;
import eu.siacs.conversations.entities.Post;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.ui.XmppActivity;
import eu.siacs.conversations.ui.util.AvatarWorkerTask;
import eu.siacs.conversations.utils.AccountUtils;
import eu.siacs.conversations.utils.XmppUri;
import eu.siacs.conversations.xmpp.Jid;

public class CommentsAdapter extends RecyclerView.Adapter<CommentsAdapter.CommentViewHolder> {

    private final Post mPost;
    private final List<Comment> comments;
    private final XmppActivity mActivity;

    public CommentsAdapter(XmppActivity activity, Post post, List<Comment> comments) {
        this.mActivity = activity;
        this.mPost = post;
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

                    final Account postAuthorAccount = mActivity.xmppConnectionService.findAccountByJid(mPost.getAuthor());

                    Account contextAccount = postAuthorAccount != null ? postAuthorAccount : AccountUtils.getFirstEnabled(mActivity.xmppConnectionService.getAccounts());

                    if (contextAccount != null) {
                        if (authorJid.asBareJid().equals(contextAccount.getJid().asBareJid())) {
                            if (contextAccount.getDisplayName() == null) {
                                binding.commentAuthorName.setText(authorJid.asBareJid().toString());
                            } else {
                                binding.commentAuthorName.setText(contextAccount.getDisplayName());
                            }
                            final Account self = contextAccount;
                            binding.commentAuthorAvatar.setOnClickListener(v -> mActivity.switchToAccount(self));
                            binding.commentAuthorName.setOnClickListener(v -> mActivity.switchToAccount(self));
                            AvatarWorkerTask.loadAvatar(contextAccount, binding.commentAuthorAvatar, R.dimen.posts_comments_avatar_size);
                        } else {
                            Contact contact = contextAccount.getRoster().getContact(authorJid);
                            if (contact != null) {
                                if (contact.getDisplayName() == null) {
                                    binding.commentAuthorName.setText(contact.getJid().asBareJid().toString());
                                } else {
                                    binding.commentAuthorName.setText(contact.getDisplayName());
                                }
                                binding.commentAuthorAvatar.setOnClickListener(v -> mActivity.switchToContactDetails(contact));
                                binding.commentAuthorName.setOnClickListener(v -> mActivity.switchToContactDetails(contact));
                                AvatarWorkerTask.loadAvatar(contact, binding.commentAuthorAvatar, R.dimen.posts_comments_avatar_size);
                            } else {
                                binding.commentAuthorName.setText(authorJid.asBareJid().toString());
                                binding.commentAuthorAvatar.setImageResource(R.drawable.ic_person_24dp);
                            }
                        }

                        binding.retractButton.setVisibility(View.GONE);
                        if (postAuthorAccount != null && postAuthorAccount.isOnlineAndConnected()) {
                            binding.retractButton.setVisibility(View.VISIBLE);
                            binding.retractButton.setOnClickListener(v -> {
                                new MaterialAlertDialogBuilder(mActivity)
                                        .setTitle(R.string.retract_comment)
                                        .setMessage(R.string.retract_comment_confirm)
                                        .setPositiveButton(R.string.retract, (dialog, which) -> retractComment(postAuthorAccount, comment))
                                        .setNegativeButton(R.string.cancel, null)
                                        .show();
                            });
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

        private void retractComment(Account account, Comment comment) {
            if (mActivity.xmppConnectionService == null) {
                return;
            }
            try {
                final XmppUri uri = new XmppUri(mPost.getCommentsNode());
                final Jid jid = uri.getJid();
                final String node = uri.getParameter("node");

                mActivity.xmppConnectionService.retractPost(account, jid, node, comment.getId(), new XmppConnectionService.OnPostRetracted() {
                    @Override
                    public void onPostRetracted(String postId) {
                        mActivity.runOnUiThread(() -> {
                            int pos = getAdapterPosition();
                            if (pos != RecyclerView.NO_POSITION) {
                                comments.remove(pos);
                                notifyItemRemoved(pos);
                            }
                        });
                    }

                    @Override
                    public void onPostRetractionFailed() {
                        mActivity.runOnUiThread(() -> Toast.makeText(mActivity, R.string.error_retracting_comment, Toast.LENGTH_SHORT).show());
                    }
                });
            } catch (Exception e) {
                Log.e(Config.LOGTAG, "error retracting comment", e);
                mActivity.runOnUiThread(() -> Toast.makeText(mActivity, R.string.error_retracting_comment, Toast.LENGTH_SHORT).show());
            }
        }
    }
}
