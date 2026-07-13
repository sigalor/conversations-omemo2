package de.monocles.chat;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.text.Editable;
import android.text.InputFilter;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.databinding.DataBindingUtil;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.Locale;

import eu.siacs.conversations.R;
import eu.siacs.conversations.databinding.ActivityMonoclesSignupBinding;
import eu.siacs.conversations.ui.EditAccountActivity;
import eu.siacs.conversations.ui.XmppActivity;

/**
 * Native signup for a paid monocles.net account. Collects plan, username and
 * email, hands the payment off to the Mollie hosted checkout in the browser
 * and polls the provisioning service until the account is ready. The in-flight
 * ref token is persisted so the flow survives the app being backgrounded or
 * killed while the user pays in the browser.
 */
public class MonoclesSignupActivity extends XmppActivity {

    private static final String PREF_REF = "monocles_signup_ref";
    private static final String PREF_USERNAME = "monocles_signup_username";
    private static final String PREF_CHECKOUT_URL = "monocles_signup_checkout_url";
    private static final String PREF_CREATED = "monocles_signup_created";

    // matches SETUP_WINDOW_HOURS on the provisioning server
    private static final long SIGNUP_VALIDITY_MS = 48L * 60 * 60 * 1000;
    private static final long POLL_INTERVAL_MS = 5000;

    private enum Step {
        FORM, WAITING, READY, FAILED
    }

    private ActivityMonoclesSignupBinding binding;
    private MonoclesAccountService service;
    private Step step = Step.FORM;
    private String plan = MonoclesAccountService.PLAN_STARTER;
    private String ref;
    private String username;
    private String checkoutUrl;

    private final Handler pollHandler = new Handler(Looper.getMainLooper());
    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            pollStatus();
        }
    };

    @Override
    protected void refreshUiReal() {
    }

    @Override
    protected void onBackendConnected() {
    }

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        if (getResources().getBoolean(R.bool.portrait_only)) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        }
        super.onCreate(savedInstanceState);
        this.binding = DataBindingUtil.setContentView(this, R.layout.activity_monocles_signup);
        setSupportActionBar(this.binding.toolbar);
        configureActionBar(getSupportActionBar());
        this.service = new MonoclesAccountService();

        setupForm();
        setupWaiting();
        setupReady();

        if (restorePendingSignup()) {
            setStep(Step.WAITING);
        } else {
            setStep(Step.FORM);
        }
    }

    private void setupForm() {
        binding.planStarterCard.setOnClickListener(v -> selectPlan(MonoclesAccountService.PLAN_STARTER));
        binding.planFullCard.setOnClickListener(v -> selectPlan(MonoclesAccountService.PLAN_FULL));
        selectPlan(MonoclesAccountService.PLAN_STARTER);

        binding.username.setFilters(new InputFilter[]{
                new InputFilter.LengthFilter(32),
                (source, start, end, dest, dstart, dend) -> source.subSequence(start, end).toString().toLowerCase(Locale.ROOT)
        });
        final TextWatcher watcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                updateFullJidPreview();
                updatePayButton();
            }
        };
        binding.username.addTextChangedListener(watcher);
        binding.email.addTextChangedListener(watcher);
        binding.terms.setOnCheckedChangeListener((buttonView, isChecked) -> updatePayButton());
        setupTermsText();
        binding.payButton.setOnClickListener(v -> submitSignup());
    }

    private void setupTermsText() {
        final String termsLabel = getString(R.string.monocles_signup_terms_link);
        final String privacyLabel = getString(R.string.monocles_signup_privacy_link);
        final String text = getString(R.string.monocles_signup_terms_checkbox, termsLabel, privacyLabel);
        final SpannableStringBuilder spannable = new SpannableStringBuilder(text);
        setLinkSpan(spannable, termsLabel, MonoclesAccountService.TERMS_URL);
        setLinkSpan(spannable, privacyLabel, MonoclesAccountService.PRIVACY_URL);
        binding.terms.setText(spannable);
        binding.terms.setMovementMethod(LinkMovementMethod.getInstance());
    }

    private void setLinkSpan(final SpannableStringBuilder spannable, final String label, final String url) {
        final int start = spannable.toString().indexOf(label);
        if (start < 0) {
            return;
        }
        spannable.setSpan(new ClickableSpan() {
            @Override
            public void onClick(@NonNull View widget) {
                BrowserHelper.launchUri(MonoclesSignupActivity.this, Uri.parse(url));
            }
        }, start, start + label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private void selectPlan(final String plan) {
        this.plan = plan;
        final boolean starter = MonoclesAccountService.PLAN_STARTER.equals(plan);
        binding.planStarterCard.setChecked(starter);
        binding.planFullCard.setChecked(!starter);
        updatePayButton();
    }

    private String usernameInput() {
        final Editable editable = binding.username.getText();
        return editable == null ? "" : editable.toString().trim();
    }

    private String emailInput() {
        final Editable editable = binding.email.getText();
        return editable == null ? "" : editable.toString().trim();
    }

    private boolean usernameValid() {
        return MonoclesAccountService.USERNAME_PATTERN.matcher(usernameInput()).matches();
    }

    private boolean emailValid() {
        final String email = emailInput();
        return email.length() <= 254 && MonoclesAccountService.EMAIL_PATTERN.matcher(email).matches();
    }

    private void updatePayButton() {
        binding.payButton.setEnabled(usernameValid() && emailValid() && binding.terms.isChecked());
    }

    private void updateFullJidPreview() {
        final String username = usernameInput();
        if (username.isEmpty()) {
            binding.fullJid.setVisibility(View.INVISIBLE);
        } else {
            binding.fullJid.setVisibility(View.VISIBLE);
            binding.fullJid.setText(getString(R.string.your_full_jid_will_be, username + "@" + MonoclesAccountService.XMPP_DOMAIN));
        }
    }

    private void submitSignup() {
        if (!usernameValid()) {
            binding.usernameLayout.setError(getString(R.string.monocles_signup_error_invalid_username));
            return;
        }
        if (!emailValid()) {
            binding.emailLayout.setError(getString(R.string.monocles_signup_error_invalid_email));
            return;
        }
        binding.usernameLayout.setError(null);
        binding.emailLayout.setError(null);
        binding.formError.setVisibility(View.GONE);
        binding.payButton.setEnabled(false);
        binding.formProgress.setVisibility(View.VISIBLE);
        final String username = usernameInput();
        final String lang = "de".equals(Locale.getDefault().getLanguage()) ? "de" : "en";
        service.signup(username, emailInput(), plan, lang, new MonoclesAccountService.Callback<MonoclesAccountService.SignupResponse>() {
            @Override
            public void onSuccess(final MonoclesAccountService.SignupResponse result) {
                binding.formProgress.setVisibility(View.GONE);
                if (result.checkout_url == null || result.ref == null) {
                    showFormError(getString(R.string.monocles_signup_error_generic, "invalid server response"));
                    updatePayButton();
                    return;
                }
                MonoclesSignupActivity.this.ref = result.ref;
                MonoclesSignupActivity.this.username = username;
                MonoclesSignupActivity.this.checkoutUrl = result.checkout_url;
                persistPendingSignup();
                setStep(Step.WAITING);
                startPolling();
                BrowserHelper.launchUri(MonoclesSignupActivity.this, Uri.parse(result.checkout_url));
            }

            @Override
            public void onError(final int httpCode, final String detail) {
                binding.formProgress.setVisibility(View.GONE);
                updatePayButton();
                switch (httpCode) {
                    case 409:
                        binding.usernameLayout.setError(getString(R.string.monocles_signup_error_username_taken));
                        break;
                    case 429:
                        showFormError(getString(R.string.monocles_signup_error_rate_limited));
                        break;
                    case 400:
                        showFormError(getString(R.string.monocles_signup_error_generic, detail));
                        break;
                    case 0:
                        showFormError(getString(R.string.monocles_signup_error_network));
                        break;
                    default:
                        showFormError(getString(R.string.monocles_signup_error_generic, httpCode + " " + detail));
                        break;
                }
            }
        });
    }

    private void showFormError(final String message) {
        binding.formError.setText(message);
        binding.formError.setVisibility(View.VISIBLE);
    }

    private void setupWaiting() {
        binding.reopenPayment.setOnClickListener(v -> {
            if (checkoutUrl != null) {
                BrowserHelper.launchUri(this, Uri.parse(checkoutUrl));
            }
        });
        binding.cancelRestart.setOnClickListener(v -> new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.monocles_signup_cancel_restart)
                .setMessage(R.string.monocles_signup_cancel_confirm)
                .setPositiveButton(R.string.yes, (dialog, which) -> {
                    clearPendingSignup();
                    setStep(Step.FORM);
                })
                .setNegativeButton(R.string.no, null)
                .show());
        binding.tryAgain.setOnClickListener(v -> {
            clearPendingSignup();
            setStep(Step.FORM);
        });
    }

    private void setupReady() {
        binding.setPasswordButton.setOnClickListener(v -> fetchSetupLink());
        binding.openPortalButton.setOnClickListener(v ->
                BrowserHelper.launchUri(this, Uri.parse(MonoclesAccountService.PORTAL_URL)));
        binding.loginNowButton.setOnClickListener(v -> {
            final String jid = username + "@" + MonoclesAccountService.XMPP_DOMAIN;
            clearPendingSignup();
            final Intent intent = new Intent(this, EditAccountActivity.class);
            intent.putExtra("jid", jid);
            intent.putExtra("init", true);
            intent.putExtra(EditAccountActivity.EXTRA_FORCE_REGISTER, false);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
        });
    }

    private void fetchSetupLink() {
        binding.setPasswordButton.setEnabled(false);
        service.setupLink(ref, new MonoclesAccountService.Callback<MonoclesAccountService.SetupLinkResponse>() {
            @Override
            public void onSuccess(final MonoclesAccountService.SetupLinkResponse result) {
                binding.setPasswordButton.setEnabled(true);
                if (result.setup_link != null) {
                    BrowserHelper.launchUri(MonoclesSignupActivity.this, Uri.parse(result.setup_link));
                }
            }

            @Override
            public void onError(final int httpCode, final String detail) {
                binding.setPasswordButton.setEnabled(true);
                switch (httpCode) {
                    case 410:
                        new MaterialAlertDialogBuilder(MonoclesSignupActivity.this)
                                .setMessage(R.string.monocles_signup_setup_link_expired)
                                .setPositiveButton(R.string.ok, null)
                                .show();
                        break;
                    case 409:
                        // provisioning not finished after all — go back to waiting
                        setStep(Step.WAITING);
                        startPolling();
                        break;
                    case 429:
                        Toast.makeText(MonoclesSignupActivity.this, R.string.monocles_signup_error_rate_limited, Toast.LENGTH_LONG).show();
                        break;
                    case 0:
                        Toast.makeText(MonoclesSignupActivity.this, R.string.monocles_signup_error_network, Toast.LENGTH_LONG).show();
                        break;
                    default:
                        Toast.makeText(MonoclesSignupActivity.this, getString(R.string.monocles_signup_error_generic, httpCode + " " + detail), Toast.LENGTH_LONG).show();
                        break;
                }
            }
        });
    }

    private void setStep(final Step step) {
        this.step = step;
        binding.stepForm.setVisibility(step == Step.FORM ? View.VISIBLE : View.GONE);
        binding.stepWaiting.setVisibility(step == Step.WAITING ? View.VISIBLE : View.GONE);
        binding.stepFailed.setVisibility(step == Step.FAILED ? View.VISIBLE : View.GONE);
        binding.stepReady.setVisibility(step == Step.READY ? View.VISIBLE : View.GONE);
        if (step == Step.READY && username != null) {
            binding.readyTitle.setText(getString(R.string.monocles_signup_ready_title, username + "@" + MonoclesAccountService.XMPP_DOMAIN));
        }
        if (step != Step.WAITING) {
            stopPolling();
        }
    }

    private void startPolling() {
        stopPolling();
        pollHandler.post(pollRunnable);
    }

    private void stopPolling() {
        pollHandler.removeCallbacks(pollRunnable);
    }

    private void pollStatus() {
        if (step != Step.WAITING || ref == null) {
            return;
        }
        service.status(ref, new MonoclesAccountService.Callback<MonoclesAccountService.StatusResponse>() {
            @Override
            public void onSuccess(final MonoclesAccountService.StatusResponse result) {
                if (step != Step.WAITING) {
                    return;
                }
                if (MonoclesAccountService.STATUS_READY.equals(result.status)) {
                    setStep(Step.READY);
                } else if (MonoclesAccountService.STATUS_FAILED.equals(result.status)) {
                    setStep(Step.FAILED);
                } else {
                    scheduleNextPoll();
                }
            }

            @Override
            public void onError(final int httpCode, final String detail) {
                if (step != Step.WAITING) {
                    return;
                }
                if (httpCode == 404) {
                    clearPendingSignup();
                    Toast.makeText(MonoclesSignupActivity.this, R.string.monocles_signup_stale, Toast.LENGTH_LONG).show();
                    setStep(Step.FORM);
                } else {
                    // transient (network, 429): keep polling silently
                    scheduleNextPoll();
                }
            }
        });
    }

    private void scheduleNextPoll() {
        pollHandler.removeCallbacks(pollRunnable);
        pollHandler.postDelayed(pollRunnable, POLL_INTERVAL_MS);
    }

    private void persistPendingSignup() {
        PreferenceManager.getDefaultSharedPreferences(this).edit()
                .putString(PREF_REF, ref)
                .putString(PREF_USERNAME, username)
                .putString(PREF_CHECKOUT_URL, checkoutUrl)
                .putLong(PREF_CREATED, System.currentTimeMillis())
                .apply();
    }

    private void clearPendingSignup() {
        PreferenceManager.getDefaultSharedPreferences(this).edit()
                .remove(PREF_REF)
                .remove(PREF_USERNAME)
                .remove(PREF_CHECKOUT_URL)
                .remove(PREF_CREATED)
                .apply();
        this.ref = null;
        this.username = null;
        this.checkoutUrl = null;
    }

    private boolean restorePendingSignup() {
        final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        final String ref = preferences.getString(PREF_REF, null);
        final long created = preferences.getLong(PREF_CREATED, 0);
        if (ref == null || System.currentTimeMillis() - created > SIGNUP_VALIDITY_MS) {
            if (ref != null) {
                clearPendingSignup();
            }
            return false;
        }
        this.ref = ref;
        this.username = preferences.getString(PREF_USERNAME, null);
        this.checkoutUrl = preferences.getString(PREF_CHECKOUT_URL, null);
        return true;
    }

    @Override
    public void onStart() {
        super.onStart();
        if (step == Step.WAITING && ref != null) {
            startPolling();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        stopPolling();
    }
}
