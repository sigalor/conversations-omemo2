package eu.siacs.conversations.ui;

import static android.view.View.VISIBLE;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.ActionBar;
import androidx.databinding.DataBindingUtil;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.bottomnavigation.BottomNavigationView;

import java.util.ArrayList;
import java.util.List;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.databinding.ActivityPostsBinding;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Contact;
import eu.siacs.conversations.entities.Post;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.ui.adapter.PostsAdapter;
import eu.siacs.conversations.utils.AccountUtils;
import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xml.Namespace;
import eu.siacs.conversations.xml.XmlReader;
import eu.siacs.conversations.xmpp.Jid;

public class PostsActivity extends XmppActivity {

    private ActivityPostsBinding binding;
    private PostsAdapter postsAdapter;
    private List<Post> postList = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = DataBindingUtil.setContentView(this, R.layout.activity_posts);
        setSupportActionBar(binding.toolbar);
        configureActionBar(getSupportActionBar());

        binding.postsList.setLayoutManager(new LinearLayoutManager(this));
        postsAdapter = new PostsAdapter(this, postList);
        binding.postsList.setAdapter(postsAdapter);

        binding.fabCreatePost.setOnClickListener(v -> {
            Intent intent = new Intent(this, CreatePostActivity.class);
            startActivity(intent);
        });


        BottomNavigationView bottomNavigationView=findViewById(R.id.bottom_navigation);
        bottomNavigationView.setBackgroundColor(Color.TRANSPARENT);
        bottomNavigationView.setOnItemSelectedListener(item -> {

            switch (item.getItemId()) {
                case R.id.chats: {
                    startActivity(new Intent(getApplicationContext(), ConversationsActivity.class));
                    overridePendingTransition(R.animator.fade_in, R.animator.fade_out);
                    return true;
                }
                case R.id.feeds: {
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
                    Intent i = new Intent(getApplicationContext(), CallsActivity.class);
                    i.putExtra("show_nav_bar", true);
                    startActivity(i);
                    overridePendingTransition(R.animator.fade_in, R.animator.fade_out);
                    return true;
                }
                default:
                    throw new IllegalStateException("Unexpected value: " + item.getItemId());
            }
        });
    }

    @Override
    public void onStart() {
        super.onStart();
        if (postList.isEmpty()) {
            loadPosts();
        }
        BottomNavigationView bottomNavigationView=findViewById(R.id.bottom_navigation);
        bottomNavigationView.setSelectedItemId(R.id.feeds);

        if (getBooleanPreference("show_nav_bar", R.bool.show_nav_bar) && getIntent().getBooleanExtra("show_nav_bar", false)) {
            bottomNavigationView.setVisibility(VISIBLE);
        } else {
            bottomNavigationView.setVisibility(View.GONE);
        }
    }

    @Override
    public void onBackendConnected() {
        refreshUiReal();
    }

    @Override
    protected void refreshUiReal() {
        if (postList.isEmpty()) {
            loadPosts();
        }

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

        // Show badge for missed calls in bottom nav
        boolean hasNewMissedCalls = xmppConnectionService.getNotificationService().hasNewMissedCalls();
        var callsBadge = bottomnav.getOrCreateBadge(R.id.calls);
        callsBadge.setVisible(hasNewMissedCalls);
        ActionBar actionBar = getSupportActionBar();
        boolean showNavBar = binding.bottomNavigation.getVisibility() == VISIBLE;
        if (actionBar != null) {
            actionBar.setHomeButtonEnabled(!showNavBar);
            actionBar.setDisplayHomeAsUpEnabled(!showNavBar);
        }
    }

    private void loadPosts() {
        if (xmppConnectionService == null) {
            return;
        }
        postList.clear();
        postList.addAll(xmppConnectionService.databaseBackend.getPosts());
        java.util.Collections.sort(postList, (p1, p2) -> Long.compare(p2.getPublished().getTime(), p1.getPublished().getTime()));
        postsAdapter.notifyDataSetChanged();

        for(Account account : xmppConnectionService.getAccounts()) {
            if(account.isOnlineAndConnected()) {
                final List<Jid> sourcesToFetch = new ArrayList<>();
                sourcesToFetch.add(account.getJid().asBareJid());
                for (eu.siacs.conversations.entities.Contact contact : account.getRoster().getContacts()) {
                    if (contact.getOption(Contact.Options.FROM)) {
                        sourcesToFetch.add(contact.getJid().asBareJid());
                    }
                }

                for (Jid source : sourcesToFetch) {
                    xmppConnectionService.fetchPubsubItems(source, "urn:xmpp:microblog:0", new XmppConnectionService.OnPubsubItemsFetched() {
                        @Override
                        public void onPubsubItemsFetched(String feedXml) {
                            try {
                                final XmlReader reader = new XmlReader();
                                reader.setInputStream(new java.io.ByteArrayInputStream(feedXml.getBytes()));
                                final Element pubsub = reader.readElement(reader.readTag());
                                final List<Post> newPosts = new ArrayList<>();
                                if (pubsub != null && pubsub.getName().equals("pubsub")) {
                                    final Element items = pubsub.findChild("items");
                                    if (items != null) {
                                        for (Element item : items.getChildren()) {
                                            if ("item".equals(item.getName())) {
                                                Element entry = item.findChild("entry", Namespace.ATOM);
                                                if (entry != null) {
                                                    Post p = Post.fromElement(entry);
                                                    newPosts.add(p);
                                                    xmppConnectionService.databaseBackend.createPost(p, account);
                                                }
                                            }
                                        }
                                        runOnUiThread(() -> {
                                            boolean added = false;
                                            for(Post newPost : newPosts) {
                                                boolean found = false;
                                                for(Post existingPost : postList) {
                                                    if (existingPost.getId() != null && existingPost.getId().equals(newPost.getId())) {
                                                        found = true;
                                                        break;
                                                    }
                                                }
                                                if (!found) {
                                                    postList.add(newPost);
                                                    added = true;
                                                }
                                            }
                                            if (added) {
                                                java.util.Collections.sort(postList, (p1, p2) -> {
                                                    if (p1.getPublished() == null && p2.getPublished() == null)
                                                        return 0;
                                                    if (p1.getPublished() == null) return 1;
                                                    if (p2.getPublished() == null) return -1;
                                                    return p2.getPublished().compareTo(p1.getPublished());
                                                });
                                                postsAdapter.notifyDataSetChanged();
                                            }
                                        });
                                    }
                                }
                            } catch (Exception e) {
                                runOnUiThread(() -> {
                                    Toast.makeText(PostsActivity.this, "Error parsing posts.", Toast.LENGTH_SHORT).show();
                                    Log.e(Config.LOGTAG,"error parsing posts",e);
                                });
                            }
                        }

                        @Override
                        public void onPubsubItemsFetchFailed() {
                            // Silently ignore for now
                        }
                    });
                }
            }
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.activity_posts, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        } else if (item.getItemId() == R.id.action_refresh) {
            xmppConnectionService.databaseBackend.clearPosts();
            postList.clear();
            postsAdapter.notifyDataSetChanged();
            loadPosts();
            return true;
        }
        return super.onOptionsItemSelected(item);
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
}