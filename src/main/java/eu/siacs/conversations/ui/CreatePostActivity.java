package eu.siacs.conversations.ui;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;
import androidx.databinding.DataBindingUtil;

import eu.siacs.conversations.R;
import eu.siacs.conversations.databinding.ActivityCreatePostBinding;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.utils.FileUtils;

public class CreatePostActivity extends XmppActivity {

    private ActivityCreatePostBinding binding;
    private String inReplyToId;
    private String inReplyToNode;
    private String postId;
    private Uri attachmentUri;
    private Uri mCameraUri;

    private final ActivityResultLauncher<String> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (isGranted) {
                    openCamera();
                } else {
                    Toast.makeText(this, R.string.no_camera_permission, Toast.LENGTH_SHORT).show();
                }
            });

    private final ActivityResultLauncher<Intent> takePictureLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK) {
                    attachmentUri = mCameraUri;
                    binding.attachmentPreview.setImageURI(attachmentUri);
                    binding.attachmentPreview.setVisibility(View.VISIBLE);
                }
            });

    private final ActivityResultLauncher<String> attachFileLauncher = registerForActivityResult(
            new ActivityResultContracts.GetContent(),
            uri -> {
                if (uri != null) {
                    this.attachmentUri = uri;
                    binding.attachmentPreview.setImageURI(attachmentUri);
                    binding.attachmentPreview.setVisibility(View.VISIBLE);
                }
            }
    );


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
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        inReplyToId = getIntent().getStringExtra("in_reply_to_id");
        inReplyToNode = getIntent().getStringExtra("in_reply_to_node");
        postId = getIntent().getStringExtra("post_id");
        if (postId != null) {
            binding.postTitleEditText.setText(getIntent().getStringExtra("title"));
            binding.postContentEditText.setText(getIntent().getStringExtra("content"));
        }

        binding.publishButton.setOnClickListener(v -> publishPost());
        binding.attachFileButton.setOnClickListener(v -> attachFileLauncher.launch("*/*"));
        binding.attachImageButton.setOnClickListener(v -> {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                openCamera();
            } else {
                requestPermissionLauncher.launch(Manifest.permission.CAMERA);
            }
        });
    }

    private void openCamera() {
        if (xmppConnectionService == null) {
            Toast.makeText(this, R.string.not_connected_try_again, Toast.LENGTH_SHORT).show();
            return;
        }
        mCameraUri = xmppConnectionService.getFileBackend().getTakePhotoUri();
        Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, mCameraUri);
        takePictureLauncher.launch(takePictureIntent);
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

        if (title.isEmpty() && content.isEmpty() && attachmentUri == null) {
            Toast.makeText(this, R.string.title_or_content_or_attachment_required, Toast.LENGTH_SHORT).show();
            return;
        }

        if (xmppConnectionService != null) {
            if (attachmentUri != null) {
                final String mimeType = getContentResolver().getType(attachmentUri);
                xmppConnectionService.uploadFileForUrl(xmppConnectionService.getAccounts().get(0), attachmentUri, mimeType, new UiCallback<String>() {
                    @Override
                    public void success(String url) {
                        publish(title, content, url, mimeType);
                    }

                    @Override
                    public void error(int errorCode, String object) {
                        runOnUiThread(() -> Toast.makeText(CreatePostActivity.this, errorCode, Toast.LENGTH_SHORT).show());
                    }

                    @Override
                    public void userInputRequired(android.app.PendingIntent pi, String object) {
                        // Not handled
                    }
                });
            } else {
                publish(title, content, null, null);
            }
        }
    }

    private void publish(String title, String content, String attachmentUrl, String attachmentType) {
        xmppConnectionService.publishPost("urn:xmpp:microblog:0", title, content, inReplyToId, postId, attachmentUrl, attachmentType, new XmppConnectionService.OnPostPublished() {
            @Override
            public void onPostPublished() {
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