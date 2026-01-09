package eu.siacs.conversations.ui;

import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Toast;

import androidx.databinding.DataBindingUtil;

import eu.siacs.conversations.R;
import eu.siacs.conversations.databinding.ActivityCreatePostBinding;
import eu.siacs.conversations.services.XmppConnectionService;

public class CreatePostActivity extends XmppActivity {

    private ActivityCreatePostBinding binding;
    private String inReplyToId;
    private String inReplyToNode;
    private String postId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = DataBindingUtil.setContentView(this, R.layout.activity_create_post);
        Activities.setStatusAndNavigationBarColors(this, binding.getRoot());
        setSupportActionBar(binding.toolbar);
        configureActionBar(getSupportActionBar());
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        inReplyToId = getIntent().getStringExtra("in_reply_to_id");
        inReplyToNode = getIntent().getStringExtra("in_reply_to_node");
        postId = getIntent().getStringExtra("post_id");
        if (postId != null) {
            binding.postTitleEditText.setText(getIntent().getStringExtra("title"));
            binding.postContentEditText.setText(getIntent().getStringExtra("content"));
        }

        binding.publishButton.setOnClickListener(v -> publishPost());
    }

    @Override
    protected void refreshUiReal() {

    }

    @Override
    public void onBackendConnected() {
        // do nothing
    }

    private void publishPost() {
        String title = binding.postTitleEditText.getText().toString();
        String content = binding.postContentEditText.getText().toString();

        if (title.isEmpty() && content.isEmpty()) {
            Toast.makeText(this, R.string.title_or_content_are_required, Toast.LENGTH_SHORT).show();
            return;
        }if (xmppConnectionService != null) {
            xmppConnectionService.publishPost("urn:xmpp:microblog:0", title, content, inReplyToId, postId, new XmppConnectionService.OnPostPublished() {
                @Override                public void onPostPublished() {
                    runOnUiThread(() -> {
                        Toast.makeText(CreatePostActivity.this, R.string.post_published, Toast.LENGTH_SHORT).show();
                        finish();
                    });
                }

                @Override
                public void onPostPublishFailed() {
                    runOnUiThread(() -> {
                        Toast.makeText(CreatePostActivity.this, R.string.error_publish_post, Toast.LENGTH_SHORT).show();
                    });
                }
            });
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
