package eu.siacs.conversations.ui;

import android.os.Bundle;
import android.text.format.DateUtils;
import android.text.method.ScrollingMovementMethod;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.appbar.AppBarLayout;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.Collections;

import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Contact;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.entities.Story;
import eu.siacs.conversations.parser.AbstractParser;
import eu.siacs.conversations.ui.util.AvatarWorkerTask;
import eu.siacs.conversations.ui.widget.AvatarView;
import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xml.Namespace;
import eu.siacs.conversations.xmpp.Jid;

public class StoryViewActivity extends XmppActivity implements StoryFragment.OnStoryInteractionListener {

    public static final String EXTRA_URLS = "urls";
    public static final String EXTRA_TITLES = "titles";
    public static final String EXTRA_STORY_IDS = "story_ids";
    public static final String EXTRA_ACCOUNT = "account";
    public static final String EXTRA_CONTACT = "contact";
    public static final String EXTRA_MIME_TYPES = "story_mime_types";

    private ViewPager2 viewPager;
    private TextView titleView;
    private View bottomPanel;
    private AppBarLayout appBarLayout;
    private AvatarView toolbarAvatar;
    private TextView toolbarTitle;
    private TextView toolbarSubtitle;
    private LinearLayout progressBarContainer;

    private ArrayList<String> urls;
    private ArrayList<String> titles;
    private ArrayList<String> storyIds;
    private ArrayList<String> mimeTypes;
    private Jid contact;
    private Account mAccount;

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

        appBarLayout = findViewById(R.id.app_bar_layout);
        toolbarAvatar = findViewById(R.id.toolbar_avatar);
        toolbarTitle = findViewById(R.id.toolbar_title);
        toolbarSubtitle = findViewById(R.id.toolbar_subtitle);
        progressBarContainer = findViewById(R.id.progress_bar_container);

        viewPager = findViewById(R.id.view_pager);
        titleView = findViewById(R.id.story_title_view);
        titleView.setMovementMethod(new ScrollingMovementMethod());

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

        setupProgressBars();

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
        showSystemUi();
    }

    private void setupProgressBars() {
        progressBarContainer.removeAllViews();
        for (int i = 0; i < urls.size(); i++) {
            ProgressBar progressBar = (ProgressBar) LayoutInflater.from(this).inflate(R.layout.story_progress_bar, progressBarContainer, false);
            progressBar.setMax(1000);
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) progressBar.getLayoutParams();
            params.weight = 1;
            progressBarContainer.addView(progressBar);
        }
    }

    public void onNextStory() {
        showSystemUi();
        final int currentItem = viewPager.getCurrentItem();
        final int nextItem = currentItem + 1;
        if (nextItem < urls.size()) {
            viewPager.setCurrentItem(nextItem, false);
        } else {
            finish();
        }
    }

    private void updateUiForPosition(int position) {
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
        if (storyContact != null) {
            displayName = storyContact.getDisplayName();
            if (mAccount != null) {
                Conversation conversation = xmppConnectionService.findOrCreateConversation(mAccount, contact, false, false);
                AvatarWorkerTask.loadAvatar(conversation, toolbarAvatar, R.dimen.muc_avatar_actionbar);
            }
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
                long twentyFourHoursAgo = System.currentTimeMillis() - 86400000;
                for (Story story : xmppConnectionService.getStories()) {
                    if (story.getUuid().equals(currentStoryId) && story.getPublished() >= twentyFourHoursAgo) {
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

        titleView.setText(titles.get(position));
    }

    private void hideSystemUi() {
        appBarLayout.animate().alpha(0f).setDuration(200);
        bottomPanel.animate().alpha(0f).setDuration(200);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN);
    }

    private void showSystemUi() {
        appBarLayout.animate().alpha(1f).setDuration(200);
        bottomPanel.animate().alpha(1f).setDuration(200);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    @Override
    protected void refreshUiReal() {
        updateUiForPosition(viewPager.getCurrentItem());
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
            if (mAccount != null) {
                Conversation conversation = xmppConnectionService.findOrCreateConversation(mAccount, contact, false, false);
                String storyTitle = titles.get(currentPos);
                if (conversation.getNextEncryption() != Message.ENCRYPTION_NONE) {
                    Message storyMessage = new Message(conversation, getString(R.string.reply_to_story) + " " + "\"" + titles.get(currentPos) + "\"", conversation.getNextEncryption(), Message.STATUS_RECEIVED);
                    conversation.setReplyTo(storyMessage);
                    switchToConversation(conversation);
                } else {
                    // Pause the story progress before showing the dialog
                    Fragment currentFragment = getSupportFragmentManager().findFragmentByTag("f" + viewPager.getCurrentItem());
                    if (currentFragment instanceof StoryFragment) {
                        ((StoryFragment) currentFragment).pauseStory();
                    }

                    final LinearLayout container = new LinearLayout(this);
                    container.setOrientation(LinearLayout.VERTICAL);
                    final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                    int margin = (int) (16 * getResources().getDisplayMetrics().density);
                    params.setMargins(margin, 0, margin, 0);
                    final TextInputEditText input = new TextInputEditText(this);
                    input.setLayoutParams(params);
                    input.setHint(R.string.compose_message_hint);
                    container.addView(input);

                    final AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                            .setTitle(R.string.reply_to_story)
                            .setView(container)
                            .setNegativeButton(R.string.cancel, null)
                            .setPositiveButton(R.string.send, (d, which) -> {
                                final String text = input.getText() != null ? input.getText().toString() : "";
                                String storyId = storyIds.get(currentPos);
                                if (storyId == null) {
                                    return;
                                }
                                String storyUri = "xmpp:" + contact.asBareJid().toString() + "?;node=urn:xmpp:pubsub-social-feed:stories:0;item=" + storyId;
                                final String messageBody = text.isEmpty() ? (storyTitle != null ? storyTitle : getString(R.string.reply_to_story)) : text;

                                Message storyMessage = new Message(
                                        conversation,
                                        messageBody,
                                        conversation.getNextEncryption(),
                                        Message.STATUS_SEND
                                );
                                Element reference = new Element("reference", "urn:xmpp:reference:0");
                                reference.setAttribute("type", "data");
                                reference.setAttribute("uri", storyUri);
                                storyMessage.addPayload(reference);
                                storyMessage.setType(Message.TYPE_STORY);

                                Message.FileParams storyParams = new Message.FileParams();
                                storyParams.url = storyUri;
                                storyMessage.setFileParams(storyParams);

                                xmppConnectionService.sendMessage(storyMessage);
                                switchToConversation(conversation);
                            })
                            .create();

                    dialog.setOnDismissListener(d -> {
                        // Resume story progress when the dialog is dismissed
                        Fragment fragment = getSupportFragmentManager().findFragmentByTag("f" + viewPager.getCurrentItem());
                        if (fragment instanceof StoryFragment) {
                            ((StoryFragment) fragment).resumeStory();
                        }
                    });
                    dialog.show();
                }
            }
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
            return StoryFragment.newInstance(urls.get(position), mimeTypes.get(position), urls);
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
        refreshUi();
    }

    @Override
    public void pauseStory() {
        hideSystemUi();
    }

    @Override
    public void resumeStory() {
        showSystemUi();
    }
}
