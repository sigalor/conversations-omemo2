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
import android.os.Bundle;
import android.os.Environment;
import android.view.MenuItem;
import android.webkit.DownloadListener;
import android.webkit.MimeTypeMap;
import android.webkit.URLUtil;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import eu.siacs.conversations.R;
import eu.siacs.conversations.ui.ConversationsActivity;
import eu.siacs.conversations.ui.StartConversationActivity;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;

import android.annotation.SuppressLint;

import com.google.android.material.bottomnavigation.BottomNavigationView;

import java.io.File;

public class WebXDCStore extends AppCompatActivity {
    private int mFileDownloadedId = -1;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_webview);
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
                String fileExtenstion = MimeTypeMap.getFileExtensionFromUrl(url);
                String filename = URLUtil.guessFileName(url, contentDisposition, fileExtenstion);
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename);
                DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                dm.enqueue(request);

                BroadcastReceiver receiver = new BroadcastReceiver() {

                    @Override
                    public void onReceive(Context context, Intent intent) {
                        String action = intent.getAction();
                        if (action.equals(DownloadManager.ACTION_DOWNLOAD_COMPLETE) ){
                            Uri uri = Uri.parse(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS) + File.separator + filename);
                            intent = new Intent(Intent.ACTION_SEND);
                            // Intent intent = new Intent(getApplicationContext(), ConversationsActivity.class);
                            // intent.setAction(ConversationsActivity.ACTION_VIEW_CONVERSATION);
                            intent.setType("application/xdc+zip");
                            intent.putExtra(Intent.EXTRA_STREAM, uri);
                            startActivity(Intent.createChooser(intent, "Share WebXDC"));
                        }
                    }
                };
                registerReceiver(receiver, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_EXPORTED);
            }
        });
    }



    protected void onStart() {
        super.onStart();

        // Initialize and assign variable
        BottomNavigationView bottomNavigationView = findViewById(R.id.bottom_navigation);

        // Set Home selected
        bottomNavigationView.setSelectedItemId(R.id.contactslist);

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
                    case R.id.manageaccounts -> {
                        startActivity(new Intent(getApplicationContext(), MANAGE_ACCOUNT_ACTIVITY));
                        overridePendingTransition(R.animator.fade_in, R.animator.fade_out);
                        return true;
                    }
                    case R.id.webxdc -> {
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