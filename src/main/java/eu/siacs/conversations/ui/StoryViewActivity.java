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
import java.util.ArrayList;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.http.HttpConnectionManager;
import eu.siacs.conversations.xmpp.Jid;
import okhttp3.HttpUrl;

public class StoryViewActivity extends XmppActivity {

    public static final String EXTRA_URLS = "urls";
    public static final String EXTRA_TITLES = "titles";
    public static final String EXTRA_STORY_IDS = "story_ids";
    public static final String EXTRA_ACCOUNT = "account";
    public static final String EXTRA_CONTACT = "contact";

    private ImageView imageView;
    private TextView titleView;

    private ArrayList<String> urls;
    private ArrayList<String> titles;
    private ArrayList<String> storyIds;
    private int currentIndex = 0;
    private Jid contact;
    private Account mAccount;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTheme(R.style.Theme_Conversations3);
        setContentView(R.layout.activity_story_view);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        imageView = findViewById(R.id.story_image_view);
        titleView = findViewById(R.id.story_title_view);

        urls = getIntent().getStringArrayListExtra(EXTRA_URLS);
        titles = getIntent().getStringArrayListExtra(EXTRA_TITLES);
        storyIds = getIntent().getStringArrayListExtra(EXTRA_STORY_IDS);

        imageView.setOnClickListener(v -> {
            currentIndex++;
            if (currentIndex < urls.size()) {
                loadStory();
            } else {
                finish();
            }
        });

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
        if (item.getItemId() == R.id.action_delete_story) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.delete_story_dialog_title)
                    .setMessage(R.string.delete_story_dialog_message)
                    .setPositiveButton(R.string.delete, (dialog, which) -> {
                        xmppConnectionService.retractStory(mAccount, storyIds.get(currentIndex), new UiCallback<Void>() {
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
        String accountUuid = getIntent().getStringExtra(EXTRA_ACCOUNT);
        if (accountUuid != null) {
            mAccount = xmppConnectionService.findAccountByUuid(accountUuid);
        }
        invalidateOptionsMenu();
        loadStory();
    }

    private void loadStory() {
        if (urls == null || currentIndex >= urls.size()) {
            finish();return;
        }
        titleView.setText(titles.get(currentIndex));
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(titles.get(currentIndex));
        }
        final String url = urls.get(currentIndex);
        final HttpUrl httpUrl;
        try {
            httpUrl = HttpUrl.get(url);
        } catch (IllegalArgumentException e) {
            Toast.makeText(this, "Invalid URL", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        final boolean useTor = mAccount != null && (xmppConnectionService.useTorToConnect() || mAccount.isOnion());
        final boolean useI2p = mAccount != null && (xmppConnectionService.useI2PToConnect() || mAccount.isI2P());

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
