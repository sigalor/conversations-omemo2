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
import java.util.Collections;
import java.util.Date;
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

    private XmppConnectionService.OnCommentReceived mOnCommentReceived;
    private XmppConnectionService.OnPostRetracted mOnPostRetracted;


    public CommentsAdapter(XmppActivity activity, Post post, List<Comment> comments) {
        this.mActivity = activity;
        this.mPost = post;
        this.comments = comments;
    }

    @Override
    public void onAttachedToRecyclerView(@NonNull RecyclerView recyclerView) {
        super.onAttachedToRecyclerView(recyclerView);
        if (mActivity.xmppConnectionService != null) {
            this.mOnCommentReceived = (postUuid, comment) -> {
                if (mPost.getId().equals(postUuid)) {
                    mActivity.runOnUiThread(() -> {
                        boolean found = false;
                        for (Comment c : comments) {
                            if (c.getId().equals(comment.getId())) {
                                found = true;
                                break;
                            }
                        }
                        if (!found) {
                            comments.add(comment);
                            Collections.sort(comments, (c1, c2) -> {
                                Date d1 = c1.getPublished();
                                Date d2 = c2.getPublished();
                                if (d1 == null && d2 == null) return 0;
                                if (d1 == null) return -1;
                                if (d2 == null) return 1;
                                return d1.compareTo(d2);
                            });
                            notifyDataSetChanged();
                        }
                    });
                }
            };
            mActivity.xmppConnectionService.addOnCommentReceivedListener(this.mOnCommentReceived);

            this.mOnPostRetracted = new XmppConnectionService.OnPostRetracted() {
                @Override
                public void onPostRetracted(String postId) {
                    mActivity.runOnUiThread(() -> {
                        int position = -1;
                        for (int i = 0; i < comments.size(); ++i) {
                            if (comments.get(i).getId().equals(postId)) {
                                position = i;
                                break;
                            }
                        }
                        if (position != -1) {
                            comments.remove(position);
                            notifyItemRemoved(position);
                        }
                    });
                }

                @Override
                public void onPostRetractionFailed() {
                }
            };
            mActivity.xmppConnectionService.addOnPostRetractedListener(this.mOnPostRetracted);
        }
    }

    @Override
    public void onDetachedFromRecyclerView(@NonNull RecyclerView recyclerView) {
        super.onDetachedFromRecyclerView(recyclerView);
        if (mActivity.xmppConnectionService != null) {
            if (this.mOnCommentReceived != null) {
                mActivity.xmppConnectionService.removeOnCommentReceivedListener(this.mOnCommentReceived);
            }
            if (this.mOnPostRetracted != null) {
                mActivity.xmppConnectionService.removeOnPostRetractedListener(this.mOnPostRetracted);
            }
        }
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

                        final Account commentAuthorAccount = mActivity.xmppConnectionService.findAccountByJid(comment.getAuthor());
                        boolean iAmPostAuthor = postAuthorAccount != null && postAuthorAccount.isOnlineAndConnected();
                        boolean iAmCommentAuthor = commentAuthorAccount != null && commentAuthorAccount.isOnlineAndConnected();

                        if (iAmPostAuthor || iAmCommentAuthor) {
                            binding.retractButton.setVisibility(View.VISIBLE);

                            final Account accountToSendFrom = iAmCommentAuthor ? commentAuthorAccount : postAuthorAccount;
                            binding.retractButton.setOnClickListener(v -> {
                                new MaterialAlertDialogBuilder(mActivity)
                                        .setTitle(R.string.retract_comment)
                                        .setMessage(R.string.retract_comment_confirm)
                                        .setPositiveButton(R.string.retract, (dialog, which) -> retractComment(accountToSendFrom, comment))
                                        .setNegativeButton(R.string.cancel, null)
                                        .show();
                            });
                        } else {
                            binding.retractButton.setVisibility(View.GONE);
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
