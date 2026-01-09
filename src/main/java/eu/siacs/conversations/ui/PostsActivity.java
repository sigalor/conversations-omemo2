package eu.siacs.conversations.ui;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Toast;

import androidx.databinding.DataBindingUtil;
import androidx.recyclerview.widget.LinearLayoutManager;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.databinding.ActivityPostsBinding;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Post;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.ui.adapter.PostsAdapter;
import eu.siacs.conversations.utils.AccountUtils;
import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xml.Namespace;
import eu.siacs.conversations.xml.XmlReader;

public class PostsActivity extends XmppActivity {

    private ActivityPostsBinding binding;
    private PostsAdapter postsAdapter;
    private List<Post> postList = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = DataBindingUtil.setContentView(this, R.layout.activity_posts);
        setSupportActionBar(binding.toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        binding.postsList.setLayoutManager(new LinearLayoutManager(this));
        postsAdapter = new PostsAdapter(postList);
        binding.postsList.setAdapter(postsAdapter);

        binding.fabCreatePost.setOnClickListener(v -> {
            Intent intent = new Intent(this, CreatePostActivity.class);
            startActivity(intent);
        });
    }

    @Override
    protected void refreshUiReal() {

    }

    @Override
    public void onStart() {
        super.onStart();
        loadPosts();
    }

    @Override
    public void onBackendConnected() {
        loadPosts();
    }

    private void loadPosts() {
        if (xmppConnectionService == null) {
            return;
        }
        Account account = AccountUtils.getFirstEnabled(xmppConnectionService.getAccounts());
        if (account == null) {
            Toast.makeText(this, R.string.no_active_account, Toast.LENGTH_SHORT).show();
            return;
        }
        xmppConnectionService.fetchPubsubItems(account.getJid().asBareJid(), "urn:xmpp:microblog:0", new XmppConnectionService.OnPubsubItemsFetched() {
            @Override
            public void onPubsubItemsFetched(String feedXml) {
                try {
                    final XmlReader reader = new XmlReader();
                    reader.setInputStream(new java.io.ByteArrayInputStream(feedXml.getBytes()));
                    final Element pubsub = reader.readElement(reader.readTag());
                    final List<Post> posts = new ArrayList<>();
                    if (pubsub != null && pubsub.getName().equals("pubsub")) {
                        final Element items = pubsub.findChild("items");
                        if (items != null) {
                            for (Element item : items.getChildren()) {
                                if ("item".equals(item.getName())) {
                                    Element entry = item.findChild("entry", Namespace.ATOM);
                                    if (entry != null) {
                                        posts.add(Post.fromElement(entry));
                                    }
                                }
                            }
                        }
                    }
                    runOnUiThread(() -> {
                        postList.clear();
                        postList.addAll(posts);
                        postsAdapter.notifyDataSetChanged();
                        if (posts.isEmpty()) {
                            Toast.makeText(PostsActivity.this, "No posts found.", Toast.LENGTH_SHORT).show();
                        }
                    });
                } catch (IOException e) {
                    runOnUiThread(() -> {
                        Toast.makeText(PostsActivity.this, "Error parsing posts.", Toast.LENGTH_SHORT).show();
                        Log.e(Config.LOGTAG,"error parsing posts",e);
                    });
                }
            }

            @Override
            public void onPubsubItemsFetchFailed() {
                runOnUiThread(() -> {
                    Toast.makeText(PostsActivity.this, "Failed to fetch posts.", Toast.LENGTH_SHORT).show();
                });
            }
        });
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
            loadPosts();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
