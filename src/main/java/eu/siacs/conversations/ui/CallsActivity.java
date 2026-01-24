package eu.siacs.conversations.ui;

import static android.view.View.VISIBLE;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.databinding.DataBindingUtil;

import com.google.android.material.bottomnavigation.BottomNavigationView;

import eu.siacs.conversations.R;
import eu.siacs.conversations.databinding.ActivityCallsBinding;

public class CallsActivity extends XmppActivity {

    private ActivityCallsBinding binding;
    private CallsFragment callsFragment;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = DataBindingUtil.setContentView(this, R.layout.activity_calls);
        Activities.setStatusAndNavigationBarColors(this, findViewById(android.R.id.content));
        setSupportActionBar(binding.toolbar);
        configureActionBar(getSupportActionBar());

        callsFragment = (CallsFragment) getSupportFragmentManager().findFragmentById(R.id.fragment_container);
        if (callsFragment == null) {
            callsFragment = new CallsFragment();
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.fragment_container, callsFragment)
                    .commit();
        }

        BottomNavigationView bottomNavigationView = findViewById(R.id.bottom_navigation);
        bottomNavigationView.setBackgroundColor(Color.TRANSPARENT);
        bottomNavigationView.setOnItemSelectedListener(item -> {
            switch (item.getItemId()) {
                case R.id.chats: {
                    startActivity(new Intent(getApplicationContext(), ConversationsActivity.class));
                    overridePendingTransition(R.animator.fade_in, R.animator.fade_out);
                    return true;
                }
                case R.id.feeds: {
                    Intent i = new Intent(getApplicationContext(), PostsActivity.class);
                    i.putExtra("show_nav_bar", true);
                    startActivity(i);
                    overridePendingTransition(R.animator.fade_in, R.animator.fade_out);
                    return true;
                }
                case R.id.stories: {
                    Intent i = new Intent(getApplicationContext(), StoriesActivity.class);
                    i.putExtra("show_nav_bar", true);
                    startActivity(i);
                    overridePendingTransition(R.animator.fade_in, R.animator.fade_out);
                    return true;
                }
                case R.id.calls: {
                    return true;
                }
                default:
                    throw new IllegalStateException("Unexpected value: " + item.getItemId());
            }
        });
    }

    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }

    @Override
    public void onStart() {
        super.onStart();

        BottomNavigationView bottomNavigationView=findViewById(R.id.bottom_navigation);
        bottomNavigationView.setSelectedItemId(R.id.calls);

        if (getBooleanPreference("show_nav_bar", R.bool.show_nav_bar) && getIntent().getBooleanExtra("show_nav_bar", false)) {
            bottomNavigationView.setVisibility(VISIBLE);
        } else {
            bottomNavigationView.setVisibility(View.GONE);
        }
    }

    @Override
    protected void onBackendConnected() {
        // Clear missed call notifications and badge when the activity is displayed.
        if (xmppConnectionService != null) {
            xmppConnectionService.getNotificationService().clearMissedCalls();
        }
        refreshUiReal();
    }

    @Override
    public void onBackPressed() {
        if (findViewById(R.id.bottom_navigation).getVisibility() == VISIBLE) {
            Intent intent = new Intent(this, ConversationsActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
            startActivity(intent);
            overridePendingTransition(R.animator.fade_in, R.animator.fade_out);
        }

        super.onBackPressed();
    }

    protected void refreshUiReal() {
        if (xmppConnectionService == null) {
            return;
        }

        ActionBar actionBar = getSupportActionBar();

        // Show badge for unread message in bottom nav
        int unreadCount = xmppConnectionService.unreadCount();
        BottomNavigationView bottomnav = findViewById(R.id.bottom_navigation);
        var bottomBadge = bottomnav.getOrCreateBadge(R.id.chats);
        bottomBadge.setNumber(unreadCount);
        bottomBadge.setVisible(unreadCount > 0);
        bottomBadge.setHorizontalOffset(20);

        // Show badge for new stories in bottom nav
        long lastRead = getPreferences().getLong("last_read_story_timestamp", 0);
        boolean hasNewStories = xmppConnectionService.getStories().stream().anyMatch(s -> s.getPublished() > lastRead);
        var storiesBadge = bottomnav.getOrCreateBadge(R.id.stories);
        storiesBadge.setVisible(hasNewStories);

        // Show badge for new posts in bottom nav
        long lastReadPosts = getPreferences().getLong("last_read_post_timestamp", 0);
        boolean hasNewPosts = xmppConnectionService.databaseBackend.getPosts().stream().anyMatch(p -> p.getPublished() != null && p.getPublished().getTime() > lastReadPosts);
        var postsBadge = bottomnav.getOrCreateBadge(R.id.feeds);
        postsBadge.setVisible(hasNewPosts);

        // Show badge for missed calls in bottom nav
        boolean hasNewMissedCalls = xmppConnectionService.getNotificationService().hasNewMissedCalls();
        var callsBadge = bottomnav.getOrCreateBadge(R.id.calls);
        callsBadge.setVisible(hasNewMissedCalls);

        boolean showNavBar = bottomnav.getVisibility() == VISIBLE;
        if (actionBar != null) {
            actionBar.setHomeButtonEnabled(!showNavBar);
            actionBar.setDisplayHomeAsUpEnabled(!showNavBar);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.activity_stories, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_settings) {
            startActivity(new Intent(this, eu.siacs.conversations.ui.activity.SettingsActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}