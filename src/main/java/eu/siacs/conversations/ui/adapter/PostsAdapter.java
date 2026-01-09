package eu.siacs.conversations.ui.adapter;

import android.app.AlertDialog;
import android.content.Intent;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.databinding.ItemPostBinding;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Comment;
import eu.siacs.conversations.entities.Contact;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.entities.Post;
import eu.siacs.conversations.entities.StubConversation;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.ui.CreatePostActivity;
import eu.siacs.conversations.ui.UiCallback;
import eu.siacs.conversations.ui.XmppActivity;
import eu.siacs.conversations.ui.util.AvatarWorkerTask;
import eu.siacs.conversations.utils.AccountUtils;
import eu.siacs.conversations.utils.XmppUri;
import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xml.Namespace;
import eu.siacs.conversations.xml.XmlReader;
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
            binding.postActions.setVisibility(isExpanded ?View.VISIBLE :View.GONE);
            if (isExpanded && post.getCommentsNode() != null) {
                loadComments(post, binding.commentsList);
            } else {
                binding.commentsList.setVisibility(View.GONE);
                binding.commentsList.setAdapter(null);
            }
            final boolean hasAttachment = post.getAttachmentUrl() != null;
            final boolean isImage = hasAttachment && post.getAttachmentType() != null && post.getAttachmentType().startsWith("image/");
            final boolean isVideo = hasAttachment && post.getAttachmentType() != null && post.getAttachmentType().startsWith("video/");

            binding.postContentSummary.setVisibility(isExpanded ? View.GONE : View.VISIBLE);
            binding.postContentFull.setVisibility(isExpanded ? View.VISIBLE : View.GONE);
            binding.postActions.setVisibility(isExpanded ? View.VISIBLE : View.GONE);
            binding.attachmentHint.setVisibility(hasAttachment && !isExpanded ? View.VISIBLE : View.GONE);
            binding.postImage.setVisibility(isExpanded && (isImage || isVideo) ? View.VISIBLE : View.GONE);
            binding.downloadButton.setVisibility(isExpanded && hasAttachment && !isImage && !isVideo ? View.VISIBLE : View.GONE);

            if (isExpanded && (isImage || isVideo)) {
                Glide.with(mActivity).load(post.getAttachmentUrl()).into(binding.postImage);
            }

            binding.downloadButton.setOnClickListener(v -> {
                if (mActivity.xmppConnectionService != null) {
                    final Account account = AccountUtils.getFirstEnabled(mActivity.xmppConnectionService.getAccounts());
                    if (account == null) {
                        Toast.makeText(mActivity, R.string.no_active_account, Toast.LENGTH_SHORT).show();
                        return;
                    }

                    final var message = new Message(new StubConversation(account, "", null, 0), null, Message.ENCRYPTION_NONE);
                    message.setType(Message.TYPE_FILE);
                    Message.FileParams params = new Message.FileParams();
                    params.url = post.getAttachmentUrl();
                    message.setFileParams(params);

                    mActivity.xmppConnectionService.getHttpConnectionManager().createNewDownloadConnection(message, false, (file) -> {
                        mActivity.xmppConnectionService.copyAttachmentToDownloadsFolder(message, new UiCallback<Integer>() {
                            @Override
                            public void success(Integer object) {
                                mActivity.runOnUiThread(() -> Toast.makeText(mActivity, R.string.save_to_downloads_success, Toast.LENGTH_SHORT).show());
                            }

                            @Override
                            public void error(int errorCode, Integer object) {
                                mActivity.runOnUiThread(() -> Toast.makeText(mActivity, errorCode, Toast.LENGTH_SHORT).show());
                            }

                            @Override
                            public void userInputRequired(android.app.PendingIntent pi, Integer object) {

                            }
                        });
                    });
                    Toast.makeText(mActivity, R.string.download_started, Toast.LENGTH_SHORT).show();
                }
            });

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
                    binding.deleteButton.setOnClickListener(v -> {
                        new AlertDialog.Builder(mActivity)
                                .setTitle(R.string.retract_post)
                                .setMessage(R.string.retract_post_confirm)
                                .setPositiveButton(R.string.retract, (dialog, which) -> {
                                    mActivity.xmppConnectionService.retractPost("urn:xmpp:microblog:0", post.getId(), new XmppConnectionService.OnPostRetracted() {
                                        @Override
                                        public void onPostRetracted() {
                                            mActivity.runOnUiThread(() -> {
                                                int pos = getAdapterPosition();
                                                if(pos != RecyclerView.NO_POSITION) {
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
                                    });
                                })
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



    private void loadComments(final Post post, final RecyclerView recyclerView) {
        if (mActivity.xmppConnectionService == null) {
            return;
        }
        try {
            final XmppUri uri = new XmppUri(post.getCommentsNode());
            final Jid jid = uri.getJid();
            final String node = uri.getParameter("node");
            if (jid != null && node != null) {
                mActivity.xmppConnectionService.fetchPubsubItems(jid, node, new XmppConnectionService.OnPubsubItemsFetched() {
                    @Override
                    public void onPubsubItemsFetched(String feedXml) {
                        try {
                            final XmlReader reader = new XmlReader();
                            reader.setInputStream(new java.io.ByteArrayInputStream(feedXml.getBytes()));
                            final Element feed = reader.readElement(reader.readTag());
                            final List<Comment> comments = new ArrayList<>();
                            if (feed != null && "feed".equals(feed.getName()) && Namespace.ATOM.equals(feed.getNamespace())) {
                                for (Element child : feed.getChildren()) {
                                    if ("entry".equals(child.getName()) && Namespace.ATOM.equals(child.getNamespace())) {
                                        comments.add(Comment.fromElement(child));
                                    }
                                }
                            }
                            mActivity.runOnUiThread(() -> {
                                if (!comments.isEmpty()) {
                                    CommentsAdapter commentsAdapter = new CommentsAdapter(mActivity, comments);
                                    recyclerView.setLayoutManager(new LinearLayoutManager(mActivity));
                                    recyclerView.setAdapter(commentsAdapter);
                                    recyclerView.setVisibility(View.VISIBLE);
                                }
                            });
                        } catch (Exception e) {
                            Log.e(Config.LOGTAG, "error parsing comments", e);
                        }
                    }

                    @Override
                    public void onPubsubItemsFetchFailed() {
                        Log.e(Config.LOGTAG, "failed to fetch comments for post "+post.getId());
                    }
                });
            }
        } catch (final Exception e) {
            Log.e(Config.LOGTAG, "error parsing comments node uri", e);
        }
    }
    }
}