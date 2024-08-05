package de.monocles.chat;

import static android.view.View.VISIBLE;
import static eu.siacs.conversations.ui.ActionBarActivity.configureActionBar;
import static eu.siacs.conversations.utils.AccountUtils.MANAGE_ACCOUNT_ACTIVITY;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.MenuItem;
import android.webkit.DownloadListener;
import android.webkit.URLUtil;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import eu.siacs.conversations.R;
import eu.siacs.conversations.ui.ConversationsActivity;
import eu.siacs.conversations.ui.StartConversationActivity;
import eu.siacs.conversations.utils.MimeUtils;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;

import android.annotation.SuppressLint;
import android.widget.Toast;

import com.google.android.material.bottomnavigation.BottomNavigationView;

public class WebXDCStore extends AppCompatActivity {
    private long mFileDownloadedId = -1;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_webxdc_store);
        setSupportActionBar(findViewById(R.id.toolbar));
        final ActionBar actionBar = getSupportActionBar();
        if (actionBar == null) {
            return;
        }
        configureActionBar(actionBar);
        getSupportActionBar().setDisplayShowHomeEnabled(true);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        WebView webView = findViewById(R.id.web);
        String URL = "https://webxdc.org/apps/";
        webView.loadUrl(URL);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setCacheMode(WebSettings.LOAD_CACHE_ELSE_NETWORK);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                view.loadUrl(url);
                return true;
            }
        });
        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition, String mimeType, long contentLength) {
                DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                request.setMimeType(mimeType);
                request.allowScanningByMediaScanner();
                String extension = MimeUtils.guessMimeTypeFromUri(getApplicationContext(), Uri.parse(url));
                String filename = URLUtil.guessFileName(url, contentDisposition, extension);
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename);
                DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                dm.enqueue(request);
                mFileDownloadedId = dm.enqueue(request);
                Toast.makeText(getApplicationContext(), R.string.download_started, Toast.LENGTH_LONG).show();
                BroadcastReceiver receiver = new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        long downloadedID = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                        if (downloadedID == mFileDownloadedId) {
                            String action = intent.getAction();
                            if (action.equals(DownloadManager.ACTION_DOWNLOAD_COMPLETE)) {
                                Uri uri = dm.getUriForDownloadedFile(mFileDownloadedId);
                                intent = new Intent(Intent.ACTION_SEND);
                                // Intent intent = new Intent(getApplicationContext(), ConversationsActivity.class);
                                // intent.setAction(ConversationsActivity.ACTION_VIEW_CONVERSATION);
                                intent.setType("application/xdc+zip");
                                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                                intent.putExtra(Intent.EXTRA_STREAM, uri);
                                startActivity(Intent.createChooser(intent, "Share WebXDC"));
                            }
                        }
                    }
                };
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    registerReceiver(receiver, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_EXPORTED);
                }
            }
        });
    }

    protected void onStart() {
        super.onStart();

        // Initialize and assign variable
        BottomNavigationView bottomNavigationView = findViewById(R.id.bottom_navigation);

        // Set Home selected
        bottomNavigationView.setSelectedItemId(R.id.webxdc);

        // Perform item selected listener
        bottomNavigationView.setOnNavigationItemSelectedListener(new BottomNavigationView.OnNavigationItemSelectedListener() {
            @Override
            public boolean onNavigationItemSelected(@NonNull MenuItem item) {

                switch (item.getItemId()) {
                    case R.id.chats -> {
                        startActivity(new Intent(getApplicationContext(), ConversationsActivity.class));
                        overridePendingTransition(R.animator.fade_in, R.animator.fade_out);
                        return true;
                    }
                    case R.id.contactslist -> {
                        startActivity(new Intent(getApplicationContext(), StartConversationActivity.class));
                        overridePendingTransition(R.animator.fade_in, R.animator.fade_out);
                        return true;
                    }
                    case R.id.webxdc -> {
                        return true;
                    }
                    case R.id.manageaccounts -> {
                        startActivity(new Intent(getApplicationContext(), MANAGE_ACCOUNT_ACTIVITY));
                        overridePendingTransition(R.animator.fade_in, R.animator.fade_out);
                        return true;
                    }
                        /* TODO:
                    case R.id.calls:
                        startActivity(new Intent(getApplicationContext(), CallsActivity.class));
                        overridePendingTransition(R.animator.fade_in, R.animator.fade_out);
                        return true;
                    case R.id.stories:
                        startActivity(new Intent(getApplicationContext(),MediaBrowserActivity.class));
                        overridePendingTransition(R.animator.fade_in, R.animator.fade_out);
                        return true;
                         */
                    default ->
                            throw new IllegalStateException("Unexpected value: " + item.getItemId());
                }
            }
        });
        bottomNavigationView.setVisibility(VISIBLE);
    }
}