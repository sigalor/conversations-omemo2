package eu.siacs.conversations;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;

import com.bumptech.glide.Glide;
import com.bumptech.glide.Registry;
import com.bumptech.glide.annotation.GlideModule;
import com.bumptech.glide.integration.okhttp3.OkHttpUrlLoader;
import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.module.AppGlideModule;

import java.io.IOException;
import java.io.InputStream;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.Collections;
import java.util.List;

import eu.siacs.conversations.http.HttpConnectionManager;
import okhttp3.OkHttpClient;

@GlideModule
public class CustomGlideModule extends AppGlideModule {

    @Override
    public void registerComponents(@NonNull Context context, @NonNull Glide glide, @NonNull Registry registry) {

        // Start with the app's base OkHttpClient, which is already configured with the app's custom trust managers.
        // This is the crucial step that makes direct connections (without a proxy) work correctly.
        final OkHttpClient baseClient = HttpConnectionManager.okHttpClient(context);

        final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);

        // Create a dynamic ProxySelector. This selector's 'select' method will be called
        // for each new network request, so it will always use the most current proxy settings.
        ProxySelector proxySelector = new ProxySelector() {
            @Override
            public List<Proxy> select(URI uri) {
                final boolean useI2p = preferences.getBoolean("use_i2p", false);
                if (useI2p) {
                    return Collections.singletonList(HttpConnectionManager.getProxy(true));
                }
                final boolean useTor = preferences.getBoolean("use_tor", false);
                if (useTor) {
                    return Collections.singletonList(HttpConnectionManager.getProxy(false));
                }

                // When no custom proxy is active, it is VITAL to return Proxy.NO_PROXY.
                // Returning null or an empty list would cause OkHttp to fall back to system-wide proxies,
                // which we want to avoid. This forces a direct connection using our
                // correctly configured (and trusted) OkHttpClient.
                return Collections.singletonList(Proxy.NO_PROXY);
            }

            @Override
            public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
                // This method is called if a connection to a proxy fails.
                // You could add logging here for debugging if needed.
            }
        };

        // Create a new client by cloning the base client and setting our dynamic proxy selector.
        final OkHttpClient clientWithProxy = baseClient.newBuilder()
                .proxySelector(proxySelector)
                .build();

        final OkHttpUrlLoader.Factory factory = new OkHttpUrlLoader.Factory(clientWithProxy);

        registry.replace(GlideUrl.class, InputStream.class, factory);
    }
}
