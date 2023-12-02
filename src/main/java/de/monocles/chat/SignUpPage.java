package de.monocles.chat;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.appcompat.widget.Toolbar;

import eu.siacs.conversations.R;
import eu.siacs.conversations.ui.MagicCreateActivity;

public class SignUpPage extends MagicCreateActivity {
    final String url = "https://ocean.monocles.eu/apps/registration/";
    private WebView webView;
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sign_up);
        setSupportActionBar((Toolbar) findViewById(R.id.toolbar));
        getSupportActionBar().setDisplayShowHomeEnabled(true);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        webView = (WebView) findViewById(R.id.sign_up_view);

        if(isNetworkAvailable()) {
            webView.getSettings().setJavaScriptEnabled(true);
            webView.loadUrl(url);
            webView.setWebViewClient(new WebViewClient() {
                 @Override
                 public boolean shouldOverrideUrlLoading(WebView view, String url) {
                     if (url.startsWith("tel:") || url.startsWith("sms:") || url.startsWith("smsto:") || url.startsWith("mailto:") || url.startsWith("mms:") || url.startsWith("mmsto:")) {
                         Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                         try {
                         startActivity(intent);
                         } catch (android.content.ActivityNotFoundException ex) {
                             Toast.makeText(SignUpPage.this, R.string.no_application_found, Toast.LENGTH_LONG).show();
                         }
                     } else {
                         webView.getSettings().setJavaScriptEnabled(true);
                         webView.getSettings().setPluginState(WebSettings.PluginState.ON);
                         webView.getSettings().setBuiltInZoomControls(true);
                         webView.getSettings().setSupportZoom(true);

                         webView.setScrollBarStyle(WebView.SCROLLBARS_OUTSIDE_OVERLAY);
                         webView.setScrollbarFadingEnabled(false);

                         view.loadUrl(url);
                     }
                     return true;
                 }
            });
        } else {
            Toast.makeText(this, R.string.account_status_no_internet, Toast.LENGTH_LONG).show();
        }
    }

    private boolean isNetworkAvailable() {
        ConnectivityManager connectivityManager
                = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo activeNetworkInfo = connectivityManager != null ? connectivityManager.getActiveNetworkInfo() : null;
        return activeNetworkInfo != null && activeNetworkInfo.isConnected();
    }
}
