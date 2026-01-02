package eu.siacs.conversations.ui;

import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.widget.Toolbar;
import androidx.swiperefreshlayout.widget.CircularProgressDrawable;

import com.bumptech.glide.Glide;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Contact;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.http.HttpConnectionManager;
import eu.siacs.conversations.ui.util.AvatarWorkerTask;
import eu.siacs.conversations.ui.widget.AvatarView;
import eu.siacs.conversations.xmpp.Jid;
import okhttp3.HttpUrl;

public class StoryViewActivity extends XmppActivity {

    public static final String EXTRA_URLS = "urls";
    public static final String EXTRA_TITLES = "titles";
    public static final String EXTRA_STORY_IDS = "story_ids";
    public static final String EXTRA_ACCOUNT = "account";
    public static final String EXTRA_CONTACT = "contact";
    public static final String EXTRA_MIME_TYPES = "story_mime_types";

    private ImageView imageView;
    private VideoView videoView;
    private TextView titleView;
    private TextView progressView;
    private View bottomPanel;

    private ArrayList<String> urls;
    private ArrayList<String> titles;
    private ArrayList<String> storyIds;
    private ArrayList<String> mimeTypes;
    private int currentIndex = 0;
    private Jid contact;
    private Account mAccount;
    private Message storyMessage;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_story_view);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setDisplayShowTitleEnabled(false);
        }

        imageView = findViewById(R.id.story_image_view);
        videoView = findViewById(R.id.story_video_view);
        titleView = findViewById(R.id.story_title_view);
        progressView = findViewById(R.id.story_progress_view);
        bottomPanel = findViewById(R.id.bottom_panel);

        urls = getIntent().getStringArrayListExtra(EXTRA_URLS);
        titles = getIntent().getStringArrayListExtra(EXTRA_TITLES);
        storyIds = getIntent().getStringArrayListExtra(EXTRA_STORY_IDS);
        mimeTypes = getIntent().getStringArrayListExtra(EXTRA_MIME_TYPES);

        View.OnTouchListener touchListener = (v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                if (event.getX() < v.getWidth() / 3) {
                    currentIndex--;
                    if (currentIndex >= 0) {
                        loadStory();
                    } else {
                        finish();
                    }
                } else if (event.getX() > v.getWidth() * 2 / 3) {
                    currentIndex++;
                    if (currentIndex < urls.size()) {
                        loadStory();
                    } else {
                        finish();
                    }
                } else {
                    if (isSystemUiVisible()) {
                        hideSystemUi();
                    } else {
                        showSystemUi();
                    }
                }
            }
            return true;
        };
        imageView.setOnTouchListener(touchListener);
        videoView.setOnTouchListener(touchListener);

        try {
            contact = Jid.of(getIntent().getStringExtra(EXTRA_CONTACT));
        } catch (final Exception e) {
            //ignore
        }
    }


        @Override
    protected void refreshUiReal() {

    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.activity_story_view, menu);
        MenuItem deleteButton = menu.findItem(R.id.action_delete_story);
        if (contact != null && xmppConnectionService != null) {
            final Account storyOwner = xmppConnectionService.findAccountByJid(contact);
            if (storyOwner != null && storyOwner.isOnlineAndConnected()) {
                deleteButton.setVisible(true);
                this.mAccount = storyOwner;
            }
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        final int itemId = item.getItemId();
        if (itemId == android.R.id.home) {
            finish();
            return true;
        } else if (itemId == R.id.action_delete_story) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.delete_story_dialog_title)
                    .setMessage(R.string.delete_story_dialog_message)
                    .setPositiveButton(R.string.delete, (dialog, which) -> {
                        xmppConnectionService.retractStory(mAccount, storyIds.get(currentIndex), new UiCallback<Void>() {
                            @Override
                            public void success(Void aVoid) {
                                runOnUiThread(() -> {
                                    Toast.makeText(StoryViewActivity.this, R.string.story_deleted, Toast.LENGTH_SHORT).show();
                                    finish();
                                });
                            }

                            @Override
                            public void error(int errorCode, Void object) {
                                runOnUiThread(() -> Toast.makeText(StoryViewActivity.this, errorCode, Toast.LENGTH_SHORT).show());
                            }

                            @Override
                            public void userInputRequired(android.app.PendingIntent pi, Void object) {

                            }
                        });
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .create()
                    .show();
            return true;
        } else if (itemId == R.id.action_reply_to_story) {
            if (storyMessage != null) {
                Conversation conversation = xmppConnectionService.findOrCreateConversation(mAccount, contact, false, false);
                conversation.setReplyTo(storyMessage);
                switchToConversation(conversation);
            }
            return true;
        }
        return super.onOptionsItemSelected(item);
    }


    @Override
    public void onBackendConnected() {
        String accountUuid = getIntent().getStringExtra(EXTRA_ACCOUNT);
        if (accountUuid != null) {
            mAccount = xmppConnectionService.findAccountByUuid(accountUuid);
        }
        invalidateOptionsMenu();
        loadStory();
    }

    private void loadStory() {
        if (urls == null || currentIndex >= urls.size()) {
            finish();
            return;
        }
        titleView.setText(titles.get(currentIndex));
        if (getSupportActionBar() != null) {
            Contact storyContact = null;
            if (contact != null && xmppConnectionService != null) {
                for (Account account : xmppConnectionService.getAccounts()) {
                    if (account.getStatus() == Account.State.ONLINE) {
                        final Contact c = account.getRoster().getContact(contact);
                        if (c != null) {
                            storyContact = c;
                            break;
                        }
                    }
                }
                if (storyContact == null) {
                    for (Account account : xmppConnectionService.getAccounts()) {
                        final Contact c = account.getRoster().getContact(contact);
                        if (c != null) {
                            storyContact = c;
                            break;
                        }
                    }
                }
            }

            String displayName;
            AvatarView toolbarAvatar = findViewById(R.id.toolbar_avatar);
            TextView toolbarTitle = findViewById(R.id.toolbar_title);
            TextView toolbarSubtitle = findViewById(R.id.toolbar_subtitle);
            if (storyContact != null) {
                displayName = storyContact.getDisplayName();
                Conversation conversation = xmppConnectionService.findOrCreateConversation(mAccount, contact, false, false);
                AvatarWorkerTask.loadAvatar(conversation, toolbarAvatar, R.dimen.muc_avatar_actionbar);
            } else if (contact != null) {
                displayName = contact.asBareJid().toString();
            } else {
                displayName = "";
            }
            toolbarTitle.setText(displayName);
            long publishedTimestamp = 0;
            if (storyIds != null && currentIndex < storyIds.size()) {
                final String currentStoryId = storyIds.get(currentIndex);
                if (xmppConnectionService != null) {
                    for (eu.siacs.conversations.entities.Story story : xmppConnectionService.getStories()) {
                        if (story.getUuid().equals(currentStoryId)) {
                            publishedTimestamp = story.getPublished();
                            break;
                        }
                    }
                }
            }
            if (publishedTimestamp > 0) {
                toolbarSubtitle.setText(DateUtils.getRelativeTimeSpanString(publishedTimestamp, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS));
            } else {
                toolbarSubtitle.setText("");
            }
        }
        progressView.setText((currentIndex + 1) + " " + getString(R.string.of) + " " + urls.size());

        showSystemUi();

        final String url = urls.get(currentIndex);
        final File cacheFile = xmppConnectionService.getFileBackend().getStoryCacheFile(url);

        final CircularProgressDrawable circularProgressDrawable = new CircularProgressDrawable(this);
        circularProgressDrawable.setStrokeWidth(10f);
        circularProgressDrawable.setCenterRadius(50f);
        circularProgressDrawable.setColorSchemeColors(0xFFFFFFFF);
        imageView.setImageDrawable(circularProgressDrawable);
        videoView.setVisibility(View.GONE);
        imageView.setVisibility(View.VISIBLE);

        new Thread(() -> {
            try {
                if (!cacheFile.exists() || cacheFile.length() == 0) {
                    Log.d(Config.LOGTAG, "Story not in cache. Downloading from: " + url);
                    runOnUiThread(circularProgressDrawable::start);
                    final HttpUrl httpUrl = HttpUrl.get(url);
                    final boolean useTor = mAccount != null && (xmppConnectionService.useTorToConnect() || mAccount.isOnion());
                    final boolean useI2p = mAccount != null && (xmppConnectionService.useI2PToConnect() || mAccount.isI2P());
                    try (InputStream inputStream = HttpConnectionManager.open(httpUrl, useTor, useI2p);
                         FileOutputStream outputStream = new FileOutputStream(cacheFile)) {

                        byte[] buffer = new byte[4096];
                        int bytesRead;
                        while ((bytesRead = inputStream.read(buffer)) != -1) {
                            outputStream.write(buffer, 0, bytesRead);
                        }
                    }
                } else {
                    Log.d(Config.LOGTAG, "Loading story from cache: " + cacheFile.getName());
                }

                runOnUiThread(() -> {
                    circularProgressDrawable.stop();
                    if (!isFinishing()) {
                        String mimeType = (mimeTypes != null && currentIndex < mimeTypes.size()) ? mimeTypes.get(currentIndex) : null;
                        if (mimeType == null) {
                            mimeType = getContentResolver().getType(Uri.fromFile(cacheFile));
                        }
                        videoView.stopPlayback();
                        if (mimeType != null && mimeType.startsWith("video/")) {
                            imageView.setVisibility(View.GONE);
                            videoView.setVisibility(View.VISIBLE);
                            videoView.setVideoURI(Uri.fromFile(cacheFile));
                            videoView.setOnPreparedListener(mp -> {
                                mp.setLooping(true);
                                videoView.start();
                            });
                        } else {
                            videoView.setVisibility(View.GONE);
                            imageView.setVisibility(View.VISIBLE);
                            Glide.with(StoryViewActivity.this).load(cacheFile).into(imageView);
                        }
                    }
                });

            } catch (IOException e) {
                Log.e(Config.LOGTAG, "Failed to download or load story", e);
                if (cacheFile != null && cacheFile.exists()) {
                    cacheFile.delete();
                }
                runOnUiThread(() -> {
                    Toast.makeText(StoryViewActivity.this, R.string.download_failed_file_not_found, Toast.LENGTH_SHORT).show();
                    finish();
                });
            }
        }).start();
    }

    private void hideSystemUi() {
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.hide();
        }
        bottomPanel.setVisibility(View.GONE);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN);
    }

    private void showSystemUi() {
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.show();
        }
        bottomPanel.setVisibility(View.VISIBLE);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    private boolean isSystemUiVisible() {
        return (getWindow().getDecorView().getSystemUiVisibility() & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0;
    }

}
