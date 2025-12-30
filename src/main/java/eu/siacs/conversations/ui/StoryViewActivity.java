package eu.siacs.conversations.ui;

import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.widget.Toolbar;

import com.bumptech.glide.Glide;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.http.HttpConnectionManager;
import eu.siacs.conversations.xmpp.Jid;
import okhttp3.HttpUrl;

public class StoryViewActivity extends XmppActivity {

    public static final String EXTRA_URL = "url";
    public static final String EXTRA_ACCOUNT = "account";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_STORY_ID = "story_id";
    public static final String EXTRA_CONTACT = "contact";

    private ImageView imageView;
    private TextView titleView;

    private String storyId;
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
        }

        imageView = findViewById(R.id.story_image_view);
        titleView = findViewById(R.id.story_title_view);

        final String title = getIntent().getStringExtra(EXTRA_TITLE);
        if (title != null) {
            titleView.setText(title);
            if (getSupportActionBar() != null) {
                getSupportActionBar().setTitle(title);
            }
        } else {
            if (getSupportActionBar() != null) {
                getSupportActionBar().setTitle(R.string.story);
            }
        }

        storyId = getIntent().getStringExtra(EXTRA_STORY_ID);
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
        if (mAccount != null && contact != null && mAccount.getJid().asBareJid().equals(contact)) {
            deleteButton.setVisible(true);
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        if (item.getItemId() == R.id.action_delete_story) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.delete_story_dialog_title)
                    .setMessage(R.string.delete_story_dialog_message)
                    .setPositiveButton(R.string.delete, (dialog, which) -> {
                        xmppConnectionService.retractStory(mAccount, storyId, new UiCallback<Void>() {
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
        }
        return super.onOptionsItemSelected(item);
    }


    @Override
    public void onBackendConnected() {
        String url = getIntent().getStringExtra(EXTRA_URL);
        String accountUuid = getIntent().getStringExtra(EXTRA_ACCOUNT);

        if (url == null || accountUuid == null) {
            finish();
            return;
        }

        mAccount = xmppConnectionService.findAccountByUuid(accountUuid);
        if (mAccount == null) {
            Toast.makeText(this, R.string.no_active_account, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        invalidateOptionsMenu();

        final HttpUrl httpUrl;
        try {
            httpUrl = HttpUrl.get(url);
        } catch (IllegalArgumentException e) {
            Toast.makeText(this, "Invalid URL", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        final boolean useTor = xmppConnectionService.useTorToConnect() || mAccount.isOnion();
        final boolean useI2p = xmppConnectionService.useI2PToConnect() || mAccount.isI2P();

        new Thread(() -> {
            File tempFile = null;
            try {
                tempFile = File.createTempFile("story", ".tmp", getCacheDir());
                try (InputStream inputStream = HttpConnectionManager.open(httpUrl, useTor, useI2p);
                     FileOutputStream outputStream = new FileOutputStream(tempFile)) {

                    byte[] buffer = new byte[4096];
                    int bytesRead;
                    while ((bytesRead = inputStream.read(buffer)) != -1) {
                        outputStream.write(buffer, 0, bytesRead);
                    }
                }

                final File finalTempFile = tempFile;
                runOnUiThread(() -> {
                    if (!isFinishing()) {
                        Glide.with(StoryViewActivity.this).load(finalTempFile).into(imageView);
                    }
                });

            } catch (IOException e) {
                Log.e(Config.LOGTAG, "Failed to download story image", e);
                if (tempFile != null) {
                    tempFile.delete();
                }
                runOnUiThread(() -> {
                    Toast.makeText(StoryViewActivity.this, R.string.download_failed_file_not_found, Toast.LENGTH_SHORT).show();
                    finish();
                });
            }
        }).start();
    }
}
