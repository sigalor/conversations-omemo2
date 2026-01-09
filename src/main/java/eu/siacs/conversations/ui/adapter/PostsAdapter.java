package eu.siacs.conversations.ui.adapter;

import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.RecyclerView;
import java.text.DateFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import eu.siacs.conversations.R;
import eu.siacs.conversations.databinding.ItemPostBinding;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Contact;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.entities.Post;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.ui.CreatePostActivity;
import eu.siacs.conversations.ui.XmppActivity;
import eu.siacs.conversations.ui.util.AvatarWorkerTask;
import eu.siacs.conversations.utils.AccountUtils;
import eu.siacs.conversations.xmpp.Jid;

public class PostsAdapter extends RecyclerView.Adapter<PostsAdapter.PostViewHolder> {

    private final List<Post> posts;
    private final XmppActivity mActivity;
    private final Set<Post> expandedPosts = new HashSet<>();

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
            final boolean isExpanded = expandedPosts.contains(post);
            binding.postContentSummary.setVisibility(isExpanded ? View.GONE : View.VISIBLE);
            binding.postContentFull.setVisibility(isExpanded ? View.VISIBLE : View.GONE);
            binding.postActions.setVisibility(isExpanded ? View.VISIBLE : View.GONE);

            binding.postContentSummary.setText(post.getContent());
            binding.postContentFull.setText(post.getContent());

            itemView.setOnClickListener(v -> {
                if (expandedPosts.contains(post)) {
                    expandedPosts.remove(post);
                } else {
                    expandedPosts.add(post);
                }
                notifyItemChanged(getAdapterPosition());
            });

            binding.replyButton.setOnClickListener(v -> {
                if (post.getAuthor() != null) {
                    Account account = AccountUtils.getFirstEnabled(mActivity.xmppConnectionService.getAccounts());
                    if (account != null) {
                        final eu.siacs.conversations.entities.Conversation conversation = mActivity.xmppConnectionService.findOrCreateConversation(account, post.getAuthor(), false, true);
                        if (conversation != null) {
                            final Message messageToReply = new Message(conversation, post.getTitle(), conversation.getNextEncryption());
                            messageToReply.setServerMsgId(post.getId());
                            conversation.setReplyTo(messageToReply);
                            mActivity.switchToConversation(conversation);
                        }
                    }
                }
            });

            binding.commentButton.setOnClickListener(v -> {
                Intent intent = new Intent(mActivity, CreatePostActivity.class);
                intent.putExtra("in_reply_to_id", post.getId());
                intent.putExtra("in_reply_to_node", post.getCommentsNode());
                mActivity.startActivity(intent);
            });

            binding.shareButton.setOnClickListener(v -> {
                Intent intent = new Intent(Intent.ACTION_SEND);
                intent.setType("text/plain");
                intent.putExtra(Intent.EXTRA_TEXT, post.getTitle() + "\n" + post.getContent());
                mActivity.startActivity(Intent.createChooser(intent, mActivity.getString(R.string.share_post_with)));
            });

            if (post.getAuthor() != null && mActivity.xmppConnectionService != null) {
                Account account = AccountUtils.getFirstEnabled(mActivity.xmppConnectionService.getAccounts());
                if (account != null && post.getAuthor().asBareJid().equals(account.getJid().asBareJid())) {
                    binding.editButton.setVisibility(View.VISIBLE);
                    binding.deleteButton.setVisibility(View.VISIBLE);
                    binding.editButton.setOnClickListener(v -> {
                        Intent intent = new Intent(mActivity, CreatePostActivity.class);
                        intent.putExtra("post_id", post.getId());
                        intent.putExtra("title", post.getTitle());
                        intent.putExtra("content", post.getContent());
                        mActivity.startActivity(intent);
                    });
                    binding.deleteButton.setOnClickListener(v -> {                        new AlertDialog.Builder(mActivity)
                            .setTitle(R.string.retract_post)
                            .setMessage(R.string.retract_post_confirm)
                            .setPositiveButton(R.string.retract, (dialog, which) -> {
                                mActivity.xmppConnectionService.retractPost("urn:xmpp:microblog:0", post.getId(), new XmppConnectionService.OnPostRetracted() {
                                    @Override
                                    public void onPostRetracted() {
                                        mActivity.runOnUiThread(() -> {
                                            int pos = getAdapterPosition();
                                            if (pos != RecyclerView.NO_POSITION) {
                                                posts.remove(pos);
                                                notifyItemRemoved(pos);
                                            }
                                        });
                                    }

                                    @Override
                                    public void onPostRetractionFailed() {
                                        mActivity.runOnUiThread(() -> {
                                            Toast.makeText(mActivity, R.string.error_retract_post, Toast.LENGTH_SHORT).show();
                                        });
                                    }
                                }); })
                            .setNegativeButton(R.string.cancel, null)
                            .show();
                    });
                } else {
                    binding.editButton.setVisibility(View.GONE);
                    binding.deleteButton.setVisibility(View.GONE);
                }
            } else {
                binding.editButton.setVisibility(View.GONE);
                binding.deleteButton.setVisibility(View.GONE);
            }

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
            if (post.getPublished() != null) {
                binding.postTimestamp.setText(DateFormat.getDateTimeInstance().format(post.getPublished()));
            } else {
                binding.postTimestamp.setText(null);
            }
        }
    }
}