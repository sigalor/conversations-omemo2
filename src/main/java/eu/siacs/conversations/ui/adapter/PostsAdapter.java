package eu.siacs.conversations.ui.adapter;

import android.app.Dialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ClickableSpan;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.MediaController;
import android.widget.Toast;
import android.widget.VideoView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.databinding.ItemPostBinding;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Comment;
import eu.siacs.conversations.entities.Contact;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.entities.Post;
import eu.siacs.conversations.entities.StubConversation;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.ui.CreatePostActivity;
import eu.siacs.conversations.ui.OnSearchPerformed;
import eu.siacs.conversations.ui.UiCallback;
import eu.siacs.conversations.ui.XmppActivity;
import eu.siacs.conversations.ui.util.AvatarWorkerTask;
import eu.siacs.conversations.utils.AccountUtils;
import eu.siacs.conversations.utils.XmppUri;
import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xml.XmlReader;
import eu.siacs.conversations.xmpp.Jid;
import io.noties.markwon.Markwon;

public class PostsAdapter extends RecyclerView.Adapter<PostsAdapter.PostViewHolder> {

    private final List<Post> posts;
    private final XmppActivity mActivity;
    private final Set<Post> expandedPosts = new HashSet<>();
    private final ActivityResultLauncher<Intent> postResultLauncher;
    private final Markwon markwon;
    private final OnSearchPerformed mOnSearchPerformed;

    public PostsAdapter(XmppActivity activity, List<Post> posts, ActivityResultLauncher<Intent> launcher, OnSearchPerformed onSearchPerformed) {
        this.mActivity = activity;
        this.posts = posts;
        this.postResultLauncher = launcher;
        this.markwon = Markwon.create(activity);
        this.mOnSearchPerformed = onSearchPerformed;
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

    private void showImagePreviewDialog(String url) {
        if (mActivity == null || url == null) {
            return;
        }
        final Dialog dialog = new Dialog(mActivity);
        dialog.setContentView(R.layout.dialog_image_preview);
        ImageView imageView = dialog.findViewById(R.id.image_view);
        Glide.with(mActivity).load(url).into(imageView);
        imageView.setOnClickListener(v -> dialog.dismiss());
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
        dialog.show();
    }

    private void showVideoPreviewDialog (String url) {
        if (mActivity == null || url == null) {
            return;
        }
        final Dialog dialog = new Dialog(mActivity);
        dialog.setContentView(R.layout.dialog_video_preview);
        VideoView videoView = dialog.findViewById(R.id.video_view);
        android.widget.FrameLayout frameLayout = dialog.findViewById(R.id.video_frame);
        Glide.with(mActivity)
                .asFile()
                .load(url)
                .into(new CustomTarget<File>() {
                    @Override
                    public void onResourceReady(@NonNull File resource, @Nullable Transition<? super File> transition) {
                        videoView.setVideoURI(Uri.fromFile(resource));
                        videoView.setOnPreparedListener(mp -> {
                            mp.setLooping(true);
                            videoView.start();
                        });
                    }

                    @Override
                    public void onLoadCleared(@Nullable Drawable placeholder) {
                        // Do nothing
                    }

                    @Override
                    public void onLoadFailed(@Nullable Drawable errorDrawable) {
                        Toast.makeText(mActivity, R.string.download_failed_file_not_found, Toast.LENGTH_SHORT).show();
                    }
                });
        MediaController controller = new MediaController(mActivity);
        controller.setAnchorView(frameLayout);
        videoView.setMediaController(controller);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }
        dialog.show();
    }

    class PostViewHolder extends RecyclerView.ViewHolder {

        private final ItemPostBinding binding;

        PostViewHolder(@NonNull View itemView) {
            super(itemView);
            binding = ItemPostBinding.bind(itemView);
        }

        void bind(Post post) {
            binding.commentsList.setVisibility(View.GONE);
            binding.commentsList.setAdapter(null);
            binding.likeButton.setOnClickListener(null);
            binding.likeCount.setText("");
            binding.likeButton.setEnabled(false);
            binding.likeButton.setCompoundDrawablesWithIntrinsicBounds(R.drawable.favorite_border_24, 0, 0, 0);

            final boolean isExpanded = expandedPosts.contains(post);

            binding.postContentSummary.setVisibility(isExpanded ? View.GONE : View.VISIBLE);
            binding.postContentFull.setVisibility(isExpanded ? View.VISIBLE : View.GONE);
            binding.postActions.setVisibility(isExpanded ? View.VISIBLE : View.GONE);

            if (isExpanded) {
                loadCommentsAndLikes(post, this);
            }

            final View.OnClickListener expandClickListener = v -> {
                if (expandedPosts.contains(post)) {
                    expandedPosts.remove(post);
                } else {
                    expandedPosts.add(post);}
                notifyItemChanged(getAdapterPosition());
            };

            setupPostView(post, expandClickListener);
        }

        private void setupPostView(final Post post, final View.OnClickListener expandClickListener) {
            final boolean isExpanded = expandedPosts.contains(post);
            final boolean hasAttachment = post.getAttachmentUrl() != null;
            final boolean isImage = hasAttachment && post.getAttachmentType() != null && post.getAttachmentType().startsWith("image/");
            final boolean isVideo = hasAttachment && post.getAttachmentType() != null && post.getAttachmentType().startsWith("video/");

            binding.attachmentHint.setVisibility(hasAttachment && !isExpanded ? View.VISIBLE : View.GONE);
            binding.postImage.setVisibility(isExpanded && (isImage || isVideo) ? View.VISIBLE : View.GONE);
            binding.videoOverlayIcon.setVisibility(isExpanded && isVideo ? View.VISIBLE : View.GONE);
            binding.downloadButton.setVisibility(isExpanded && hasAttachment && !isImage && !isVideo ? View.VISIBLE : View.GONE);

            if (isExpanded && (isImage || isVideo)) {
                binding.attachmentProgress.setVisibility(View.VISIBLE);
                Glide.with(mActivity)
                        .load(post.getAttachmentUrl())                        .listener(new com.bumptech.glide.request.RequestListener<Drawable>() {
                            @Override
                            public boolean onLoadFailed(@Nullable com.bumptech.glide.load.engine.GlideException e, @Nullable Object model, @NonNull com.bumptech.glide.request.target.Target<Drawable> target, boolean isFirstResource) {
                                binding.attachmentProgress.setVisibility(View.GONE);
                                return false;
                            }

                            @Override
                            public boolean onResourceReady(@NonNull Drawable resource, @NonNull Object model, @NonNull com.bumptech.glide.request.target.Target<Drawable> target, @NonNull com.bumptech.glide.load.DataSource dataSource, boolean isFirstResource) {
                                binding.attachmentProgress.setVisibility(View.GONE);
                                return false;
                            }
                        })
                        .into(binding.postImage);binding.postImage.setOnClickListener(v -> {
                    if (isImage) showImagePreviewDialog(post.getAttachmentUrl());
                    else showVideoPreviewDialog(post.getAttachmentUrl());
                });
            }

            final List<Account> postAccounts = new ArrayList<>();
            if (post.getAuthor() != null && mActivity.xmppConnectionService != null) {
                for (Account account : mActivity.xmppConnectionService.getAccounts()) {
                    if (account.isOnlineAndConnected()) {
                        if (account.getJid().asBareJid().equals(post.getAuthor().asBareJid()) || (account.getRoster() != null && account.getRoster().getContact(post.getAuthor().asBareJid()) != null)) {
                            postAccounts.add(account);
                        }
                    }
                }
            }
            if (mActivity.xmppConnectionService == null) {
                binding.editButton.setVisibility(View.GONE);
                binding.deleteButton.setVisibility(View.GONE);
                binding.replyButton.setVisibility(View.GONE);
                binding.commentButton.setVisibility(View.GONE);
                binding.downloadButton.setVisibility(View.GONE);
                binding.likeButton.setVisibility(View.GONE);
            } else {
                final Account ownAccount = post.getAuthor() != null ? mActivity.xmppConnectionService.findAccountByJid(post.getAuthor().asBareJid()) : null;

                binding.downloadButton.setOnClickListener(v -> {
                    if (postAccounts.size() == 1) {
                        downloadAttachment(postAccounts.get(0), post);
                    } else {                        showAccountSelectionDialog(mActivity.getString(R.string.choose_account_for_download), postAccounts, account -> downloadAttachment(account, post));
                    }
                });

                if (post.getContent() != null) {
                    final CharSequence markdown = markwon.toMarkdown(post.getContent());
                    binding.postContentSummary.setText(markdown);
                    binding.postContentSummary.setMovementMethod(null);
                    binding.postContentSummary.setOnClickListener(expandClickListener);

                    final SpannableString spannable = new SpannableString(markdown);
                    final Matcher matcher = Pattern.compile("#[\\p{L}\\p{N}]+").matcher(spannable);
                    while (matcher.find()) {
                        final String hashtag = matcher.group(0);
                        spannable.setSpan(new ClickableSpan() {
                            @Override
                            public void onClick(@NonNull View widget) {
                                mOnSearchPerformed.onSearchPerformed(hashtag);
                            }
                        }, matcher.start(), matcher.end(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }

                    binding.postContentFull.setText(spannable);
                    binding.postContentFull.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
                    binding.postContentFull.setOnClickListener(null);

                } else {
                    binding.postContentSummary.setText("");
                    binding.postContentFull.setText("");
                }

                itemView.setOnClickListener(expandClickListener);
                binding.postTitle.setOnClickListener(expandClickListener);

                binding.commentButton.setOnClickListener(v -> {
                    if (post.getAuthor() != null) {
                        if (postAccounts.size() == 1) {
                            replyToPost(postAccounts.get(0), post);
                        } else {
                            showAccountSelectionDialog(mActivity.getString(R.string.choose_account_for_reply), postAccounts, account -> replyToPost(account, post));
                        }
                    }
                });

                binding.replyButton.setVisibility(View.GONE);

                binding.shareButton.setOnClickListener(v -> {
                    Intent intent = new Intent(Intent.ACTION_SEND);
                    intent.setType("text/plain");
                    intent.putExtra(Intent.EXTRA_TEXT, post.getTitle() + "\n" + post.getContent());
                    mActivity.startActivity(Intent.createChooser(intent, mActivity.getString(R.string.share_post_with)));
                });

                if (ownAccount != null && ownAccount.isOnlineAndConnected()) {
                    binding.editButton.setVisibility(View.VISIBLE);
                    binding.deleteButton.setVisibility(View.VISIBLE);
                    binding.editButton.setOnClickListener(v -> {
                        editPost(ownAccount, post);
                    });
                    binding.deleteButton.setOnClickListener(v -> {
                        new MaterialAlertDialogBuilder(mActivity)
                                .setTitle(R.string.retract_post)
                                .setMessage(R.string.retract_post_confirm)
                                .setPositiveButton(R.string.retract, (dialog, which) -> {
                                    mActivity.xmppConnectionService.retractPost(ownAccount, "urn:xmpp:microblog:0", post.getId(),
                                            new XmppConnectionService.OnPostRetracted() {

                                                @Override
                                                public void onPostRetracted(String postId) {
                                                    mActivity.runOnUiThread(() -> {
                                                        mActivity.xmppConnectionService.databaseBackend.deletePost(postId);
                                                        int pos = getAdapterPosition();
                                                        if (pos != RecyclerView.NO_POSITION) {
                                                            posts.remove(pos);
                                                            notifyItemRemoved(pos);
                                                        }
                                                    });
                                                }

                                                @Override
                                                public void onPostRetractionFailed() {
                                                    mActivity.runOnUiThread(() -> Toast.makeText(mActivity, R.string.error_retract_post, Toast.LENGTH_SHORT).show());
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

                if (post.getAuthor() != null) {
                    final Jid authorJid = post.getAuthor();
                    Account displayAccount = ownAccount;
                    if (displayAccount == null && postAccounts.size() > 0) {
                        displayAccount = postAccounts.get(0);
                    }
                    if (displayAccount == null) {
                        displayAccount = AccountUtils.getFirstEnabled(mActivity.xmppConnectionService.getAccounts());
                    }

                    if (displayAccount != null) {
                        if (authorJid.asBareJid().equals(displayAccount.getJid().asBareJid())) {
                            final String displayName = displayAccount.getDisplayName();
                            binding.postAuthorName.setText(displayName != null && !displayName.isEmpty() ? displayName : displayAccount.getJid().asBareJid().toString());
                            AvatarWorkerTask.loadAvatar(displayAccount, binding.postAuthorAvatar, R.dimen.bubble_avatar_size);
                            final Account self = displayAccount;
                            binding.postAuthorAvatar.setOnClickListener(v -> mActivity.switchToAccount(self));
                            binding.postAuthorName.setOnClickListener(v -> mActivity.switchToAccount(self));
                        } else {
                            Contact contact = displayAccount.getRoster().getContact(authorJid);
                            if (contact != null) {
                                final String displayName = contact.getDisplayName();
                                binding.postAuthorName.setText(displayName != null && !displayName.isEmpty() ? displayName : contact.getJid().asBareJid().toString());
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

        private void loadCommentsAndLikes(final Post post, final PostViewHolder holder) {
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
                                final Element pubsub = reader.readElement(reader.readTag());
                                final List<Comment> comments = new ArrayList<>();
                                final List<Comment> likes = new ArrayList<>();
                                if (pubsub != null) {
                                    final Element items = pubsub.findChild("items");
                                    if (items != null) {
                                        for (Element item : items.getChildren()) {
                                            if ("item".equals(item.getName())) {
                                                final Comment comment = Comment.fromElement(item);
                                                if (comment != null) {
                                                    if ("♥".equals(comment.getTitle())) {
                                                        likes.add(comment);
                                                    } else {
                                                        comments.add(comment);
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                mActivity.runOnUiThread(() -> {
                                    if (holder.getAdapterPosition() == RecyclerView.NO_POSITION || posts.get(holder.getAdapterPosition()) != post) {
                                        return;
                                    }
                                    if (!comments.isEmpty()) {
                                        java.util.Collections.sort(comments, (c1, c2) -> {
                                            Date d1 = c1.getPublished();
                                            Date d2 = c2.getPublished();
                                            if (d1 == null && d2 == null) return 0;
                                            if (d1 == null) return -1;
                                            if (d2 == null) return 1;
                                            return d1.compareTo(d2);
                                        });
                                        CommentsAdapter commentsAdapter = new CommentsAdapter(mActivity, post, comments);
                                        holder.binding.commentsList.setLayoutManager(new LinearLayoutManager(mActivity));
                                        holder.binding.commentsList.setAdapter(commentsAdapter);
                                        holder.binding.commentsList.setVisibility(View.VISIBLE);
                                    } else {
                                        holder.binding.commentsList.setVisibility(View.GONE);
                                    }
                                    // Update like button UI and logic
                                    setupLikeButton(post, holder, likes);
                                });
                            } catch (Exception e) {
                                Log.e(Config.LOGTAG, "error parsing comments", e);
                            }
                        }

                        @Override
                        public void onPubsubItemsFetchFailed() {
                            Log.e(Config.LOGTAG, "failed to fetch comments for post " + post.getId());
                        }
                    });
                }
            } catch (final Exception e) {
                Log.e(Config.LOGTAG, "error parsing comments node uri", e);
            }
        }

        private void setupLikeButton(final Post post, final PostViewHolder holder, final List<Comment> likes) {
            final List<Account> onlineAccounts = mActivity.xmppConnectionService.getAccounts().stream()
                    .filter(Account::isOnlineAndConnected)
                    .collect(java.util.stream.Collectors.toList());

            if (onlineAccounts.isEmpty()) {
                holder.binding.likeButton.setEnabled(false);
                holder.binding.likeCount.setText(String.valueOf(likes.size()));
                return;
            }
            holder.binding.likeButton.setEnabled(true);
            holder.binding.likeCount.setText(String.valueOf(likes.size()));

            Comment myLike = null;
            Account myLikerAccount = null;
            for (Account acc : onlineAccounts) {
                for (Comment like : likes) {
                    if (like.getAuthor() != null && like.getAuthor().asBareJid().equals(acc.getJid().asBareJid())) {
                        myLike = like;
                        myLikerAccount = acc;
                        break;
                    }
                }
                if (myLike != null) break;
            }

            final boolean hasLiked = myLike != null;
            holder.binding.likeButton.setCompoundDrawablesWithIntrinsicBounds(
                    hasLiked ? R.drawable.favorite_filled_24 : R.drawable.favorite_border_24, 0, 0, 0);

            final Comment finalMyLike = myLike;
            final Account finalMyLikerAccount = myLikerAccount;
            holder.binding.likeButton.setOnClickListener(v -> {
                if (hasLiked && finalMyLikerAccount != null) {
                    retractLike(finalMyLikerAccount, finalMyLike, post, holder);
                } else {
                    if (onlineAccounts.size() > 1) {
                        showAccountSelectionDialog(mActivity.getString(R.string.choose_account_for_like), onlineAccounts, selectedAccount -> {
                            publishLike(selectedAccount, post, holder);
                        });
                    } else {
                        publishLike(onlineAccounts.get(0), post, holder);
                    }
                }
            });
        }

        private void publishLike(final Account account, final Post post, final PostViewHolder holder) {
            mActivity.xmppConnectionService.publishComment(account, post.getCommentsNode(), "♥", new XmppConnectionService.OnPostPublished() {
                @Override
                public void onPostPublished() {
                    mActivity.runOnUiThread(() -> loadCommentsAndLikes(post, holder));
                }
                @Override
                public void onPostPublishFailed() {
                    mActivity.runOnUiThread(() -> Toast.makeText(mActivity, R.string.error_liking_post, Toast.LENGTH_SHORT).show());
                }
            });
        }

        private void retractLike(final Account account, final Comment like, final Post post, final PostViewHolder holder) {
            try {
                final XmppUri uri = new XmppUri(post.getCommentsNode());
                final Jid jid = uri.getJid();
                final String node = uri.getParameter("node");
                mActivity.xmppConnectionService.retractPost(account, jid, node, like.getId(), new XmppConnectionService.OnPostRetracted() {
                    @Override
                    public void onPostRetracted(String postId) {
                        mActivity.runOnUiThread(() -> loadCommentsAndLikes(post, holder));
                    }
                    @Override
                    public void onPostRetractionFailed() {
                        mActivity.runOnUiThread(() -> Toast.makeText(mActivity, R.string.error_removing_like, Toast.LENGTH_SHORT).show());
                    }
                });
            } catch (Exception e) {
                Log.e(Config.LOGTAG, "error retracting like", e);
                mActivity.runOnUiThread(() -> Toast.makeText(mActivity, R.string.error_removing_like, Toast.LENGTH_SHORT).show());
            }
        }
    }

            private void downloadAttachment(Account account, Post post) {
        if (mActivity.xmppConnectionService == null) {
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

    private void showAccountSelectionDialog(String title, List<Account> accounts, java.util.function.Consumer<Account> onAccountSelected) {
        if (accounts.size() == 0) {
            Toast.makeText(mActivity, R.string.no_active_account, Toast.LENGTH_SHORT).show();
            return;
        }
        if (accounts.size() == 1) {
            onAccountSelected.accept(accounts.get(0));
            return;
        }    final ArrayAdapter<String> adapter = new ArrayAdapter<>(mActivity, android.R.layout.simple_list_item_1);
        final java.util.Map<String, Account> accountMap = new java.util.HashMap<>();
        for (Account account : accounts) {
            String jid = account.getJid().asBareJid().toString();
            adapter.add(jid);
            accountMap.put(jid, account);
        }

        new MaterialAlertDialogBuilder(mActivity)
                .setTitle(title)
                .setAdapter(adapter, (dialog, which) -> {
                    String jid = adapter.getItem(which);
                    onAccountSelected.accept(accountMap.get(jid));
                })
                .create()
                .show();
    }

    private void replyToPost(Account account, Post post) {
        Intent intent = new Intent(mActivity, CreatePostActivity.class);
        intent.putExtra("in_reply_to_id", post.getId());
        intent.putExtra("in_reply_to_node", post.getCommentsNode());
        intent.putExtra("post_id", post.getId());
        intent.putExtra("account", account.getUuid());
        intent.putExtra("post_title", post.getTitle());
        intent.putExtra("post_content", post.getContent());
        postResultLauncher.launch(intent);
    }

    private void editPost(Account account, Post post) {
        Intent intent = new Intent(mActivity, CreatePostActivity.class);
        intent.putExtra("post_id", post.getId());
        intent.putExtra("title", post.getTitle());
        intent.putExtra("content", post.getContent());
        intent.putExtra("account", account.getUuid());
        if (post.getAttachmentUrl() != null) {
            intent.putExtra("attachment_url", post.getAttachmentUrl());
            intent.putExtra("attachment_type", post.getAttachmentType());
        }
        postResultLauncher.launch(intent);
    }
}
