package de.monocles.chat;

import android.os.Handler;
import android.os.Looper;

import com.google.gson.Gson;

import java.io.IOException;
import java.util.regex.Pattern;

import eu.siacs.conversations.http.HttpConnectionManager;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.ui.XmppActivity;
import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * REST client for the monocles.net account provisioning service
 * (https://account.monocles.net). Signup flow: POST /signup returns a Mollie
 * checkout URL plus a private ref token; GET /signup/status is polled until the
 * webhook has provisioned the account; POST /signup/setup-link yields a
 * one-time credential-setup link (valid 48h).
 */
public class MonoclesAccountService {

    public static final String BASE_URL = "https://account.monocles.net";
    public static final String PORTAL_URL = BASE_URL + "/";
    public static final String TERMS_URL = BASE_URL + "/terms.html";
    public static final String PRIVACY_URL = BASE_URL + "/privacy.html";

    public static final String XMPP_DOMAIN = "monocles.net";

    public static final String PLAN_STARTER = "starter";
    public static final String PLAN_FULL = "full";

    // Mirrors the server-side validation so typos don't burn the 5/min rate limit
    public static final Pattern USERNAME_PATTERN = Pattern.compile("[a-z0-9]{3,32}");
    public static final Pattern EMAIL_PATTERN = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");

    public static final String STATUS_WAITING = "waiting";
    public static final String STATUS_READY = "ready";
    public static final String STATUS_FAILED = "failed";

    public interface Callback<T> {
        void onSuccess(T result);

        /** httpCode is 0 for network/IO failures. */
        void onError(int httpCode, String detail);
    }

    public static class SignupResponse {
        public String checkout_url;
        public String ref;
    }

    public static class StatusResponse {
        public String status;
        public String username;
    }

    public static class SetupLinkResponse {
        public String setup_link;
    }

    private static class ApiError {
        String detail;
    }

    private final OkHttpClient client;
    private final Gson gson = new Gson();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public MonoclesAccountService() {
        final XmppConnectionService service = XmppActivity.staticXmppConnectionService;
        final boolean useTor = service != null && service.useTorToConnect();
        final boolean useI2P = service != null && service.useI2PToConnect();
        this.client = HttpConnectionManager.newBuilder(useTor, useI2P).build();
    }

    public void signup(final String username, final String email, final String plan, final String lang, final Callback<SignupResponse> callback) {
        final HttpUrl url = HttpUrl.get(BASE_URL).newBuilder()
                .addPathSegment("signup")
                .addQueryParameter("username", username)
                .addQueryParameter("email", email)
                .addQueryParameter("plan", plan)
                .addQueryParameter("lang", lang)
                .build();
        enqueue(new Request.Builder().url(url).post(RequestBody.create(new byte[0], null)).build(), SignupResponse.class, callback);
    }

    public void status(final String ref, final Callback<StatusResponse> callback) {
        final HttpUrl url = HttpUrl.get(BASE_URL).newBuilder()
                .addPathSegment("signup")
                .addPathSegment("status")
                .addQueryParameter("ref", ref)
                .build();
        enqueue(new Request.Builder().url(url).get().build(), StatusResponse.class, callback);
    }

    public void setupLink(final String ref, final Callback<SetupLinkResponse> callback) {
        final HttpUrl url = HttpUrl.get(BASE_URL).newBuilder()
                .addPathSegment("signup")
                .addPathSegment("setup-link")
                .addQueryParameter("ref", ref)
                .build();
        enqueue(new Request.Builder().url(url).post(RequestBody.create(new byte[0], null)).build(), SetupLinkResponse.class, callback);
    }

    private <T> void enqueue(final Request request, final Class<T> responseClass, final Callback<T> callback) {
        client.newCall(request).enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(final Call call, final IOException e) {
                deliverError(callback, 0, e.getMessage());
            }

            @Override
            public void onResponse(final Call call, final Response response) {
                try (final ResponseBody body = response.body()) {
                    final String content = body == null ? "" : body.string();
                    if (response.isSuccessful()) {
                        final T result = gson.fromJson(content, responseClass);
                        if (result == null) {
                            deliverError(callback, 0, "empty response");
                        } else {
                            mainHandler.post(() -> callback.onSuccess(result));
                        }
                    } else {
                        deliverError(callback, response.code(), extractDetail(content));
                    }
                } catch (final Exception e) {
                    deliverError(callback, 0, e.getMessage());
                }
            }
        });
    }

    private String extractDetail(final String content) {
        try {
            final ApiError error = gson.fromJson(content, ApiError.class);
            if (error != null && error.detail != null) {
                return error.detail;
            }
        } catch (final Exception ignored) {
        }
        return content;
    }

    private <T> void deliverError(final Callback<T> callback, final int code, final String detail) {
        mainHandler.post(() -> callback.onError(code, detail));
    }
}
