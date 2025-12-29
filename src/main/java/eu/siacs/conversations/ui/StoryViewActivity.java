package eu.siacs.conversations.ui;

import android.os.Bundle;
import android.util.Log;
import android.widget.ImageView;
import android.widget.Toast;

import com.bumptech.glide.Glide;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.http.HttpConnectionManager;
import okhttp3.HttpUrl;

public class StoryViewActivity extends XmppActivity {

    public static final String EXTRA_URL = "url";
    public static final String EXTRA_ACCOUNT = "account";

    private ImageView imageView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_story_view);
        imageView = findViewById(R.id.story_image_view);
    }

    @Override
    protected void refreshUiReal() {

    }

    @Override
    public void onBackendConnected() {
        String url = getIntent().getStringExtra(EXTRA_URL);
        String accountUuid = getIntent().getStringExtra(EXTRA_ACCOUNT);

        if (url == null || accountUuid == null) {
            finish();
            return;
        }

        Account account = xmppConnectionService.findAccountByUuid(accountUuid);
        if (account == null) {
            Toast.makeText(this, R.string.no_active_account, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        final HttpUrl httpUrl;
        try {
            httpUrl = HttpUrl.get(url);
        } catch (IllegalArgumentException e) {
            Toast.makeText(this, "Invalid URL", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        final boolean useTor = xmppConnectionService.useTorToConnect() || account.isOnion();
        final boolean useI2p = xmppConnectionService.useI2PToConnect() || account.isI2P();

        // Use a background thread for networking and file I/O
        new Thread(() -> {
            File tempFile = null;
            try {
                // Create a temporary file in the cache directory
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