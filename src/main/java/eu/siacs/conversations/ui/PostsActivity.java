package eu.siacs.conversations.ui;

import static android.view.View.VISIBLE;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.widget.SearchView;
import androidx.databinding.DataBindingUtil;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.bottomnavigation.BottomNavigationView;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.databinding.ActivityPostsBinding;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Contact;
import eu.siacs.conversations.entities.Post;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.ui.adapter.FollowSuggestionAdapter;
import eu.siacs.conversations.ui.adapter.PostsAdapter;
import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xml.Namespace;
import eu.siacs.conversations.xml.XmlReader;
import eu.siacs.conversations.xmpp.Jid;

public class PostsActivity extends XmppActivity implements XmppConnectionService.OnPostReceived, XmppConnectionService.OnPostRetracted, OnSearchPerformed {

    private ActivityPostsBinding binding;
    private PostsAdapter postsAdapter;
    private List<Post> postList = new ArrayList<>();
    private List<Post> allPosts = new ArrayList<>();
    private String mCurrentQuery = "";
    private SearchView mSearchView;

    private FollowSuggestionAdapter mFollowSuggestionAdapter;
    private List<Contact> mFollowSuggestions = new ArrayList<>();
    private boolean mSuggestionsVisible = true;

    private final ActivityResultLauncher<Intent> postResultLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK) {
                    loadPosts();
                }
            });

    private void toggleSuggestionsVisibility() {
        mSuggestionsVisible = !mSuggestionsVisible;
        binding.followSuggestionsList.setVisibility(mSuggestionsVisible ? View.VISIBLE : View.GONE);
        binding.toggleSuggestionsButton.animate().rotation(mSuggestionsVisible ? -180 : 0).setDuration(300).start();
        getPreferences(MODE_PRIVATE).edit().putBoolean("suggestions_visible", mSuggestionsVisible).apply();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mSuggestionsVisible = getPreferences(MODE_PRIVATE).getBoolean("suggestions_visible", true);
        binding = DataBindingUtil.setContentView(this, R.layout.activity_posts);
        Activities.setStatusAndNavigationBarColors(this, binding.getRoot());
        setSupportActionBar(binding.toolbar);
        configureActionBar(getSupportActionBar());

        binding.postsList.setLayoutManager(new LinearLayoutManager(this));
        postsAdapter = new PostsAdapter(this, postList, postResultLauncher, this);
        binding.postsList.setAdapter(postsAdapter);

        binding.fabCreatePost.setOnClickListener(v -> {
            Intent intent = new Intent(this, CreatePostActivity.class);
            postResultLauncher.launch(intent);
        });

        binding.swipeContainer.setOnRefreshListener(() -> {
            loadPosts();
            binding.swipeContainer.setRefreshing(false);
        });

        binding.followSuggestionsHeader.setOnClickListener(v -> toggleSuggestionsVisibility());
        binding.toggleSuggestionsButton.setOnClickListener(v -> toggleSuggestionsVisibility());

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

        handleIntent(getIntent());
    }

    @Override
    public void onStart() {
        super.onStart();
        if (xmppConnectionService != null) {
            xmppConnectionService.addOnPostReceivedListener(this);
            xmppConnectionService.addOnPostRetractedListener(this);
        }
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
    public void onStop() {
        super.onStop();
        if (xmppConnectionService != null) {
            xmppConnectionService.removeOnPostReceivedListener(this);
            xmppConnectionService.removeOnPostRetractedListener(this);
        }
    }

    @Override
    public void onBackendConnected() {
        if (xmppConnectionService != null) {
            xmppConnectionService.addOnPostReceivedListener(this);
            xmppConnectionService.addOnPostRetractedListener(this);
            if (mFollowSuggestionAdapter == null) {
                mFollowSuggestionAdapter = new FollowSuggestionAdapter(this, xmppConnectionService, mFollowSuggestions);
                binding.followSuggestionsList.setAdapter(mFollowSuggestionAdapter);
            }
        }
        refreshUiReal();
    }

    @Override
    public void onPostReceived(final Post post) {
        runOnUiThread(() -> {
            if (post == null || post.getId() == null) {
                return;
            }

            int existingPostIndex = -1;
            for (int i = 0; i < allPosts.size(); i++) {
                Post existingPost = allPosts.get(i);
                if (post.getId().equals(existingPost.getId())) {
                    existingPostIndex = i;
                    break;
                }
            }

            if (existingPostIndex != -1) {
                allPosts.set(existingPostIndex, post);
            } else {
                allPosts.add(0, post);
            }
            filterAndDisplayPosts(mCurrentQuery);
        });
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

    public void loadPosts() {
        if (xmppConnectionService == null) {
            return;
        }
        allPosts.clear();
        allPosts.addAll(xmppConnectionService.databaseBackend.getPosts());
        filterAndDisplayPosts(mCurrentQuery);

        mFollowSuggestions.clear();
        for(final Account account : xmppConnectionService.getAccounts()) {
            if(account.isOnlineAndConnected()) {
                final List<Jid> sourcesToFetch = new ArrayList<>();
                sourcesToFetch.add(account.getJid().asBareJid());
                for (Contact contact : account.getRoster().getContacts()) {
                    if (contact.isFollowed()) {
                        sourcesToFetch.add(contact.getJid().asBareJid());
                    } else if (contact.showInRoster()) {
                        mFollowSuggestions.add(contact);
                    }
                }

                if (mFollowSuggestions.isEmpty()) {
                    binding.followSuggestionsHeader.setVisibility(View.GONE);
                    binding.followSuggestionsList.setVisibility(View.GONE);
                } else {
                    binding.followSuggestionsHeader.setVisibility(View.VISIBLE);
                    binding.followSuggestionsList.setVisibility(mSuggestionsVisible ? View.VISIBLE : View.GONE);
                    binding.toggleSuggestionsButton.setRotation(mSuggestionsVisible ? -180 : 0);
                }
                if (mFollowSuggestionAdapter != null) {
                    mFollowSuggestionAdapter.notifyDataSetChanged();
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
                                if (pubsub != null) {
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
                                            for(Post newPost : newPosts) {
                                                boolean found = false;
                                                for(Post existingPost : allPosts) {
                                                    if (existingPost.getId() != null && existingPost.getId().equals(newPost.getId())) {
                                                        found = true;
                                                        break;
                                                    }
                                                }
                                                if (!found) {
                                                    allPosts.add(newPost);
                                                }
                                            }
                                            filterAndDisplayPosts(mCurrentQuery);
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
        MenuItem searchItem = menu.findItem(R.id.action_search);
        mSearchView = (SearchView) searchItem.getActionView();
        mSearchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                filterAndDisplayPosts(query);
                return true;
            }

            @Override
            public boolean onQueryTextChange(String newText) {
                filterAndDisplayPosts(newText);
                return true;
            }
        });
        searchItem.setOnActionExpandListener(new MenuItem.OnActionExpandListener() {
            @Override
            public boolean onMenuItemActionExpand(MenuItem item) {
                return true;
            }

            @Override
            public boolean onMenuItemActionCollapse(MenuItem item) {
                filterAndDisplayPosts("");
                return true;
            }
        });
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
        if (mSearchView != null && !mSearchView.isIconified()) {
            mSearchView.setIconified(true);
            return;
        }
        if (mCurrentQuery != null && !mCurrentQuery.isEmpty()) {
            filterAndDisplayPosts("");
            return;
        }

        if (findViewById(R.id.bottom_navigation).getVisibility() == VISIBLE) {
            Intent intent = new Intent(this, ConversationsActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
            startActivity(intent);
            overridePendingTransition(R.animator.fade_in, R.animator.fade_out);
        }

        super.onBackPressed();
    }
    @Override
    public void onPostRetracted(final String postId) {
        runOnUiThread(() -> {
            if (postId == null) {
                return;
            }
            allPosts.removeIf(p -> p.getId().equals(postId));
            filterAndDisplayPosts(mCurrentQuery);
        });
    }

    @Override
    public void onPostRetractionFailed() {
        runOnUiThread(() -> Toast.makeText(this, R.string.error_retract_post, Toast.LENGTH_SHORT).show());
    }

    @Override
    public void onSearchPerformed(String query) {
        if (mSearchView != null) {
            mSearchView.setIconified(false);
            mSearchView.setQuery(query, true);
        }
    }

    private void filterAndDisplayPosts(String query) {
        this.mCurrentQuery = query;
        postList.clear();
        if (query.isEmpty()) {
            postList.addAll(allPosts);
        } else {
            postList.addAll(allPosts.stream().filter(p -> (p.getContent() != null && p.getContent().contains(query)) || (p.getTitle() != null && p.getTitle().contains(query))).collect(Collectors.toList()));
        }
        java.util.Collections.sort(postList, (p1, p2) -> {
            if (p1.getPublished() == null && p2.getPublished() == null) return 0;
            if (p1.getPublished() == null) return 1;
            if (p2.getPublished() == null) return -1;
            return p2.getPublished().compareTo(p1.getPublished());
        });
        postsAdapter.notifyDataSetChanged();
    }

    private void handleIntent(Intent intent) {
        if (intent != null) {
            final String postUuid = intent.getStringExtra("post_uuid");
            if (postUuid != null) {
                //This is a rough search. A better way would be to scroll to the item
                filterAndDisplayPosts(postUuid);
            }
            final String hashtag = intent.getStringExtra("hashtag");
            if (hashtag != null) {
                onSearchPerformed(hashtag);
            }
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }
}
