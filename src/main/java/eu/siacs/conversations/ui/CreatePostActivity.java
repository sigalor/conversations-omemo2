package eu.siacs.conversations.ui;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.databinding.DataBindingUtil;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import eu.siacs.conversations.R;
import eu.siacs.conversations.databinding.ActivityCreatePostBinding;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.xml.Namespace;

public class CreatePostActivity extends XmppActivity {

    private ActivityCreatePostBinding binding;
    private String inReplyToId;
    private String inReplyToNode;
    private String postId;
    private Uri attachmentUri;
    private Uri mCameraUri;
    private String accountUuid;

    private List<Account> onlineAccounts = new ArrayList<>();

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
                    binding.attachmentVideoView.setVisibility(View.GONE);
                }
            });

    private final ActivityResultLauncher<Intent> takeVideoLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK) {
                    attachmentUri = mCameraUri;
                    binding.attachmentVideoView.setVideoURI(attachmentUri);
                    binding.attachmentVideoView.setOnPreparedListener(mp -> {
                        mp.setLooping(true);
                        binding.attachmentVideoView.start();
                    });
                    binding.attachmentVideoView.setVisibility(View.VISIBLE);
                    binding.attachmentPreview.setVisibility(View.GONE);
                }
            });

    private final ActivityResultLauncher<String> attachFileLauncher = registerForActivityResult(
            new ActivityResultContracts.GetContent(),
            uri -> {
                if (uri != null) {
                    this.attachmentUri = uri;
                    final String mimeType = getContentResolver().getType(attachmentUri);
                    if (mimeType != null && mimeType.startsWith("image/")) {
                        binding.attachmentPreview.setImageURI(attachmentUri);
                        binding.attachmentPreview.setVisibility(View.VISIBLE);
                        binding.attachmentVideoView.setVisibility(View.GONE);
                    } else if (mimeType != null && mimeType.startsWith("video/")) {
                        binding.attachmentVideoView.setVideoURI(attachmentUri);
                        binding.attachmentVideoView.setOnPreparedListener(mp -> {
                            mp.setLooping(true);
                            binding.attachmentVideoView.start();
                        });
                        binding.attachmentVideoView.setVisibility(View.VISIBLE);
                        binding.attachmentPreview.setVisibility(View.GONE);
                    }
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
        accountUuid = getIntent().getStringExtra("account");
        if (postId != null) {
            binding.postTitleEditText.setText(getIntent().getStringExtra("title"));
            binding.postContentEditText.setText(getIntent().getStringExtra("content"));
            if (accountUuid != null) {
                binding.accountSpinner.setVisibility(View.GONE);
            }
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
        binding.attachVideoButton.setOnClickListener(v -> {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                openVideoCamera();
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

    private void openVideoCamera() {
        if (xmppConnectionService == null) {
            Toast.makeText(this, R.string.not_connected_try_again, Toast.LENGTH_SHORT).show();
            return;
        }
        mCameraUri = xmppConnectionService.getFileBackend().getTakeVideoUri();
        Intent takeVideoIntent = new Intent(MediaStore.ACTION_VIDEO_CAPTURE);
        takeVideoIntent.putExtra(MediaStore.EXTRA_OUTPUT, mCameraUri);
        takeVideoLauncher.launch(takeVideoIntent);
    }

    @Override
    protected void refreshUiReal() {

    }

    @Override
    public void onBackendConnected() {
        if (xmppConnectionService != null && accountUuid == null) {
            onlineAccounts.clear();
            List<String> accountJids = new ArrayList<>();
            for (Account account : xmppConnectionService.getAccounts()) {
                if (account.isOnlineAndConnected()) {
                    onlineAccounts.add(account);
                    accountJids.add(account.getJid().asBareJid().toString());
                }
            }
            ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, accountJids);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            binding.accountSpinner.setAdapter(adapter);
        }
    }

    private void publishPost() {
        String title = binding.postTitleEditText.getText().toString();
        String content = binding.postContentEditText.getText().toString();

        if (title.isEmpty() && content.isEmpty() && attachmentUri == null) {
            Toast.makeText(this, R.string.title_or_content_or_attachment_required, Toast.LENGTH_SHORT).show();
            return;
        }

        if (xmppConnectionService != null) {
            Account selectedAccount;
            if (accountUuid != null) {
                selectedAccount = xmppConnectionService.findAccountByUuid(accountUuid);
                if (selectedAccount == null) {
                    Toast.makeText(this, getString(R.string.account_not_found), Toast.LENGTH_SHORT).show();
                    return;
                }
            } else {
                if (binding.accountSpinner.getSelectedItemPosition() < 0 || binding.accountSpinner.getSelectedItemPosition() >= onlineAccounts.size()) {
                    Toast.makeText(this, R.string.no_active_account, Toast.LENGTH_SHORT).show();
                    return;
                }
                selectedAccount = onlineAccounts.get(binding.accountSpinner.getSelectedItemPosition());
            }

            if (inReplyToNode != null) {
                xmppConnectionService.publishComment(selectedAccount, inReplyToNode, title, inReplyToId, new XmppConnectionService.OnPostPublished() {
                    @Override
                    public void onPostPublished() {
                        runOnUiThread(() -> {
                            Toast.makeText(CreatePostActivity.this, R.string.comment_published, Toast.LENGTH_SHORT).show();
                            finish();
                        });
                    }

                    @Override
                    public void onPostPublishFailed() {
                        runOnUiThread(() -> {
                            Toast.makeText(CreatePostActivity.this, R.string.error_publish_comment, Toast.LENGTH_SHORT).show();
                        });
                    }
                });
            } else if (attachmentUri != null) {
                final String mimeType = getContentResolver().getType(attachmentUri);
                xmppConnectionService.uploadFileForUrl(selectedAccount, attachmentUri, mimeType, new UiCallback<String>() {
                    @Override
                    public void success(String url) {
                        publish(selectedAccount, title, content, url, mimeType);
                    }

                    @Override
                    public void error(int errorCode, String object) {
                        runOnUiThread(() -> Toast.makeText(CreatePostActivity.this, errorCode, Toast.LENGTH_SHORT).show());
                    }

                    @Override
                    public void userInputRequired(android.app.PendingIntent pi, String object) {

                    }
                });
            } else {
                publish(selectedAccount, title, content, null, null);
            }
        }
    }

    private void publish(Account account, String title, String content, String attachmentUrl, String attachmentType) {
        xmppConnectionService.publishPost(account, "urn:xmpp:microblog:0", title, content, attachmentUrl, attachmentType, postId, new XmppConnectionService.OnPostPublished() {
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