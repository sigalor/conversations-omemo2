package eu.siacs.conversations.ui;

import android.net.Uri;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;

import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Contact;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.ui.util.AvatarWorkerTask;
import eu.siacs.conversations.ui.widget.AvatarView;
import eu.siacs.conversations.xmpp.Jid;

public class StoryViewActivity extends XmppActivity {

    public static final String EXTRA_URLS = "urls";
    public static final String EXTRA_TITLES = "titles";
    public static final String EXTRA_STORY_IDS = "story_ids";
    public static final String EXTRA_ACCOUNT = "account";
    public static final String EXTRA_CONTACT = "contact";
    public static final String EXTRA_MIME_TYPES = "story_mime_types";

    private ViewPager2 viewPager;
    private TextView titleView;
    private TextView progressView;
    private View bottomPanel;

    private ArrayList<String> urls;
    private ArrayList<String> titles;
    private ArrayList<String> storyIds;
    private ArrayList<String> mimeTypes;
    private Jid contact;
    private Account mAccount;

    private GestureDetector gestureDetector;

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

        viewPager = findViewById(R.id.view_pager);
        titleView = findViewById(R.id.story_title_view);
        progressView = findViewById(R.id.story_progress_view);
        bottomPanel = findViewById(R.id.bottom_panel);

        urls = getIntent().getStringArrayListExtra(EXTRA_URLS);
        titles = getIntent().getStringArrayListExtra(EXTRA_TITLES);
        storyIds = getIntent().getStringArrayListExtra(EXTRA_STORY_IDS);
        mimeTypes = getIntent().getStringArrayListExtra(EXTRA_MIME_TYPES);

        try {
            contact = Jid.of(getIntent().getStringExtra(EXTRA_CONTACT));
        } catch (final Exception e) {
            //ignore
        }

        class GestureListener extends GestureDetector.SimpleOnGestureListener {

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (isSystemUiVisible()) {
                    hideSystemUi();
                } else {
                    showSystemUi();
                }
                return true;
            }
        }

        gestureDetector = new GestureDetector(this, new GestureListener());

        viewPager.setOnTouchListener((v, event) -> gestureDetector.onTouchEvent(event));

        StoryPagerAdapter adapter = new StoryPagerAdapter(this);
        viewPager.setAdapter(adapter);
        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                super.onPageSelected(position);
                updateUiForPosition(position);
            }
        });

        updateUiForPosition(0);
    }

    private void updateUiForPosition(int position) {
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
            if (storyIds != null && position < storyIds.size()) {
                final String currentStoryId = storyIds.get(position);
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

        titleView.setText(titles.get(position));
        progressView.setText((position + 1) + " " + getString(R.string.of) + " " + urls.size());
    }

    private void hideSystemUi() {
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.hide();
        }
        bottomPanel.setVisibility(View.GONE);
    }

    private void showSystemUi() {
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.show();
        }
        bottomPanel.setVisibility(View.VISIBLE);
    }

    private boolean isSystemUiVisible() {
        ActionBar actionBar = getSupportActionBar();
        return actionBar != null && actionBar.isShowing();
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
                        xmppConnectionService.retractStory(mAccount, storyIds.get(viewPager.getCurrentItem()), new UiCallback<Void>() {
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
            int currentPos = viewPager.getCurrentItem();
            Message storyMessage = new Message(null, titles.get(currentPos), Message.ENCRYPTION_NONE, Message.STATUS_RECEIVED);
            Conversation conversation = xmppConnectionService.findOrCreateConversation(mAccount, contact, false, false);
            conversation.setReplyTo(storyMessage);
            switchToConversation(conversation);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private class StoryPagerAdapter extends FragmentStateAdapter {

        public StoryPagerAdapter(@NonNull FragmentActivity fragmentActivity) {
            super(fragmentActivity);
        }

        @NonNull
        @Override
        public Fragment createFragment(int position) {
            return StoryFragment.newInstance(urls.get(position), mimeTypes.get(position));
        }

        @Override
        public int getItemCount() {
            return urls.size();
        }
    }

    @Override
    protected void onBackendConnected() {
        String accountUuid = getIntent().getStringExtra(EXTRA_ACCOUNT);
        if (accountUuid != null) {
            mAccount = xmppConnectionService.findAccountByUuid(accountUuid);
        }
        invalidateOptionsMenu();
    }
}
