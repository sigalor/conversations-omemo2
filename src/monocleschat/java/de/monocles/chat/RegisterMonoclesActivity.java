package de.monocles.chat;

import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Bundle;

import androidx.databinding.DataBindingUtil;

import eu.siacs.conversations.R;
import eu.siacs.conversations.databinding.ActivityRegisterMonoclesBinding;
import eu.siacs.conversations.ui.MagicCreateActivity;
import eu.siacs.conversations.ui.StartConversationActivity;
import eu.siacs.conversations.ui.XmppActivity;

/**
 * Account-type chooser shown from the welcome screen: a paid monocles.net
 * account (chat + mail, optionally cloud) or a free chat-only account on a
 * public XMPP provider.
 */
public class RegisterMonoclesActivity extends XmppActivity {

    private ActivityRegisterMonoclesBinding binding;

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
        this.binding = DataBindingUtil.setContentView(this, R.layout.activity_register_monocles);
        setSupportActionBar(this.binding.toolbar);
        configureActionBar(getSupportActionBar());

        binding.getMonoclesAccount.setOnClickListener(v ->
                startActivity(new Intent(this, MonoclesSignupActivity.class)));

        binding.createFreeAccount.setOnClickListener(v -> {
            final Intent intent = new Intent(this, MagicCreateActivity.class);
            StartConversationActivity.addInviteUri(intent, getIntent());
            startActivity(intent);
        });
    }
}
