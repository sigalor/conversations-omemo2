package eu.siacs.conversations.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import androidx.databinding.DataBindingUtil;
import androidx.recyclerview.widget.LinearLayoutManager;

import eu.siacs.conversations.R;
import eu.siacs.conversations.databinding.ActivityManageAccountsBinding;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.ui.adapter.AccountAdapter;

import java.util.ArrayList;
import java.util.List;

public class ChooseAccountForProfilePictureActivity extends XmppActivity {

    protected final List<Account> accountList = new ArrayList<>();
    protected AccountAdapter mAccountAdapter;

    @Override
    protected void refreshUiReal() {
        loadEnabledAccounts();
        mAccountAdapter.notifyDataSetChanged();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final ActivityManageAccountsBinding binding = DataBindingUtil.setContentView(this, R.layout.activity_manage_accounts);
        Activities.setStatusAndNavigationBarColors(this, binding.getRoot());
        setSupportActionBar(binding.toolbar);
        configureActionBar(getSupportActionBar(), false);

        // Setup LayoutManager for RecyclerView
        binding.accountList.setLayoutManager(new LinearLayoutManager(this));

        // Pass the click listener directly to the Adapter constructor.
        // We pass 'null' for drag listener, context menu listener, and move listener
        // because this activity does not need reordering or context actions.
        this.mAccountAdapter = new AccountAdapter(this, accountList,
                this::goToProfilePictureActivity,
                null,
                null,
                null);

        binding.accountList.setAdapter(this.mAccountAdapter);
    }

    @Override
    public void onStart() {
        super.onStart();
    }

    @Override
    protected void onBackendConnected() {
        loadEnabledAccounts();
        if (accountList.size() == 1) {
            goToProfilePictureActivity(accountList.get(0));
            return;
        }
        mAccountAdapter.notifyDataSetChanged();
    }

    private void loadEnabledAccounts() {
        accountList.clear();
        for(Account account : xmppConnectionService.getAccounts()) {
            if (account.isEnabled()) {
                accountList.add(account);
            }
        }
    }

    private void goToProfilePictureActivity(Account account) {
        final Intent startIntent = getIntent();
        final Uri uri = startIntent == null ? null : startIntent.getData();
        if (uri != null) {
            Intent intent = new Intent(this, PublishProfilePictureActivity.class);
            intent.putExtra(EXTRA_ACCOUNT, account.getJid().asBareJid().toString());
            intent.setData(uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try {
                startActivity(intent);
            } catch (SecurityException e) {
                Toast.makeText(this, R.string.sharing_application_not_grant_permission, Toast.LENGTH_SHORT).show();
                return;
            }
        }
        finish();
    }
}
