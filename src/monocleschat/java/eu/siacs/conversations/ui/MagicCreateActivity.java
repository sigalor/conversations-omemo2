package eu.siacs.conversations.ui;

import static eu.siacs.conversations.AppSettings.LOAD_PROVIDERS_EXTERNAL;

import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.databinding.DataBindingUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import de.monocles.chat.ProviderService;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.databinding.ActivityMagicCreateBinding;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.utils.CryptoHelper;
import eu.siacs.conversations.utils.InstallReferrerUtils;
import eu.siacs.conversations.xmpp.Jid;

public class MagicCreateActivity extends XmppActivity implements TextWatcher, MaterialSwitch.OnCheckedChangeListener {


    private boolean useOwnProvider = false;
    private boolean registerFromUri = false;
    public static final String EXTRA_DOMAIN = "domain";
    public static final String EXTRA_PRE_AUTH = "pre_auth";
    public static final String EXTRA_USERNAME = "username";
    public static final String EXTRA_REGISTER = "register";

    private ActivityMagicCreateBinding binding;
    private String domain;
    private String username;
    private String preAuth;
    private String selectedDomain;
    private final List<String> providerDomains = new ArrayList<>();

    @Override
    protected void refreshUiReal() {

    }

    @Override
    protected void onBackendConnected() {

    }

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        final Intent data = getIntent();
        this.domain = data == null ? null : data.getStringExtra(EXTRA_DOMAIN);
        this.preAuth = data == null ? null : data.getStringExtra(EXTRA_PRE_AUTH);
        this.username = data == null ? null : data.getStringExtra(EXTRA_USERNAME);
        this.registerFromUri = data == null ? null : data.getBooleanExtra(EXTRA_REGISTER, false);
        if (getResources().getBoolean(R.bool.portrait_only)) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        }
        super.onCreate(savedInstanceState);
        this.binding = DataBindingUtil.setContentView(this, R.layout.activity_magic_create);
        setSupportActionBar(this.binding.toolbar);
        boolean loadExternalList = getBooleanPreference(LOAD_PROVIDERS_EXTERNAL, R.bool.load_providers_list_external);
        if (!loadExternalList) binding.loadProvidersListExternalText.setText(R.string.local_providers_list);

        // Try fetching current providers list
        providerDomains.addAll(ProviderService.getProviders());
        try {
            if (new ProviderService().execute().get()) {
                providerDomains.clear();
                providerDomains.addAll(ProviderService.getProviders());
                if (loadExternalList && staticXmppConnectionService.hasInternetConnection()) binding.loadProvidersListExternalText.setText(R.string.external_providers_list);
            }
        } catch (Throwable e) {
            e.printStackTrace();
        }
        sortProviderDomains();
        selectedDomain = de.monocles.chat.Config.DOMAIN.getRandomServer();
        if (registerFromUri && !useOwnProvider && (this.preAuth != null || domain != null)) {
            binding.serverLayout.setEnabled(false);
            binding.serverLayout.setVisibility(View.GONE);
            binding.useOwn.setEnabled(false);
            binding.useOwn.setChecked(true);
            binding.useOwn.setVisibility(View.GONE);
            binding.servertitle.setText(R.string.your_server);
            binding.yourserver.setVisibility(View.VISIBLE);
            binding.yourserver.setText(domain);
        } else {
            binding.yourserver.setVisibility(View.GONE);
        }
        binding.useOwn.setOnCheckedChangeListener(this);
        binding.server.setText(selectedDomain);
        binding.server.setOnClickListener(v -> showProviderPicker());
        binding.serverLayout.setEndIconOnClickListener(v -> showProviderPicker());
        setSupportActionBar(this.binding.toolbar);
        configureActionBar(getSupportActionBar(), this.domain == null);
        if (username != null && domain != null) {
            binding.title.setText(R.string.your_server_invitation);
            binding.instructions.setText(getString(R.string.magic_create_text_fixed, domain));
            binding.username.setEnabled(false);
            binding.username.setText(this.username);
            selectedDomain = this.domain;
            binding.server.setText(selectedDomain);
            binding.servertitle.setVisibility(View.GONE);
            binding.serverLayout.setVisibility(View.GONE);
            binding.useOwn.setVisibility(View.GONE);
            binding.loadProvidersListExternalText.setVisibility(View.GONE);
            updateFullJidInformation(this.username);
        } else if (domain != null) {
            binding.instructions.setText(getString(R.string.magic_create_text_on_x, domain));
            selectedDomain = this.domain;
            binding.server.setText(selectedDomain);
            binding.servertitle.setVisibility(View.GONE);
            binding.serverLayout.setVisibility(View.GONE);
            binding.useOwn.setVisibility(View.GONE);
            binding.loadProvidersListExternalText.setVisibility(View.GONE);
        }
        binding.createAccount.setOnClickListener(v -> {
            try {
                final String username = binding.username.getText().toString();
                final boolean fixedUsername;
                final Jid jid;
                if (this.domain != null && this.username != null) {
                    fixedUsername = true;
                    jid = Jid.ofLocalAndDomain(this.username, this.domain);
                } else if (this.domain != null) {
                    fixedUsername = false;
                    jid = Jid.ofLocalAndDomain(username, this.domain);
                } else {
                    fixedUsername = false;
                    domain = updateDomain();
                    jid = Jid.ofLocalAndDomain(username, domain);
                }
                if (!jid.getLocal().equals(jid.getLocal())) {
                    binding.username.setError(getString(R.string.invalid_username));
                    binding.username.requestFocus();
                } else {
                    binding.username.setError(null);
                    Account account = xmppConnectionService.findAccountByJid(jid);
                    String password = CryptoHelper.createPassword(new SecureRandom());
                    if (account == null) {
                        account = new Account(jid, password);
                        account.setOption(Account.OPTION_REGISTER, true);
                        account.setOption(Account.OPTION_DISABLED, true);
                        account.setOption(Account.OPTION_MAGIC_CREATE, true);
                        account.setOption(Account.OPTION_FIXED_USERNAME, fixedUsername);
                        if (this.preAuth != null) {
                            account.setKey(Account.KEY_PRE_AUTH_REGISTRATION_TOKEN, this.preAuth);
                        }
                        xmppConnectionService.createAccount(account);
                    }
                    Intent intent = new Intent(MagicCreateActivity.this, EditAccountActivity.class);
                    intent.putExtra("jid", account.getJid().asBareJid().toString());
                    intent.putExtra("init", true);
                    intent.putExtra("existing", false);
                    intent.putExtra("useownprovider", useOwnProvider);
                    intent.putExtra("register", registerFromUri);
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    final MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
                    builder.setTitle(getString(R.string.create_account));
                    builder.setCancelable(false);
                    StringBuilder messasge = new StringBuilder();
                    messasge.append(getString(R.string.secure_password_generated));
                    messasge.append("\n\n");
                    messasge.append(getString(R.string.password));
                    messasge.append(": ");
                    messasge.append(password);
                    messasge.append("\n\n");
                    messasge.append(getString(R.string.change_password_in_next_step));
                    builder.setMessage(messasge);
                    builder.setPositiveButton(getString(R.string.copy_to_clipboard), (dialogInterface, i) -> {
                        if (copyTextToClipboard(password, R.string.create_account)) {
                            StartConversationActivity.addInviteUri(intent, getIntent());
                            startActivity(intent);
                            overridePendingTransition(R.animator.fade_in, R.animator.fade_out);
                            finish();
                            overridePendingTransition(R.animator.fade_in, R.animator.fade_out);
                        }
                    });
                    builder.create().show();
                }
            } catch (IllegalArgumentException e) {
                binding.username.setError(getString(R.string.invalid_username));
                binding.username.requestFocus();
            }
        });
        binding.username.addTextChangedListener(this);
    }

    private void sortProviderDomains() {
        Collections.sort(providerDomains, String::compareToIgnoreCase);
        final List<String> recommended = de.monocles.chat.Config.DOMAIN.DOMAINS;
        for (int i = recommended.size() - 1; i >= 0; i--) {
            final String domain = recommended.get(i);
            if (providerDomains.remove(domain)) {
                providerDomains.add(0, domain);
            }
        }
    }

    private void showProviderPicker() {
        final BottomSheetDialog dialog = new BottomSheetDialog(this);
        final View sheet = LayoutInflater.from(this).inflate(R.layout.dialog_provider_picker, null);
        dialog.setContentView(sheet);
        final RecyclerView list = sheet.findViewById(R.id.provider_list);
        final EditText search = sheet.findViewById(R.id.provider_search);
        final ProviderAdapter adapter = new ProviderAdapter(providerDomains, selectedDomain, domain -> {
            selectedDomain = domain;
            binding.server.setText(domain);
            updateFullJidInformation(binding.username.getText().toString());
            dialog.dismiss();
        });
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                adapter.filter(s.toString());
            }
        });
        dialog.show();
    }

    private interface OnProviderSelected {
        void onSelected(String domain);
    }

    private static class ProviderAdapter extends RecyclerView.Adapter<ProviderAdapter.ViewHolder> {

        private final List<String> allDomains;
        private final List<String> filteredDomains;
        private final String selectedDomain;
        private final OnProviderSelected listener;

        ProviderAdapter(final List<String> domains, final String selectedDomain, final OnProviderSelected listener) {
            this.allDomains = new ArrayList<>(domains);
            this.filteredDomains = new ArrayList<>(domains);
            this.selectedDomain = selectedDomain;
            this.listener = listener;
        }

        void filter(final String query) {
            filteredDomains.clear();
            final String needle = query.trim().toLowerCase();
            for (final String domain : allDomains) {
                if (needle.isEmpty() || domain.toLowerCase().contains(needle)) {
                    filteredDomains.add(domain);
                }
            }
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            final View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_provider, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            final String domain = filteredDomains.get(position);
            holder.domain.setText(domain);
            holder.recommended.setVisibility(de.monocles.chat.Config.DOMAIN.DOMAINS.contains(domain) ? View.VISIBLE : View.GONE);
            holder.selected.setVisibility(domain.equals(selectedDomain) ? View.VISIBLE : View.INVISIBLE);
            holder.itemView.setOnClickListener(v -> listener.onSelected(domain));
        }

        @Override
        public int getItemCount() {
            return filteredDomains.size();
        }

        static class ViewHolder extends RecyclerView.ViewHolder {
            final TextView domain;
            final TextView recommended;
            final ImageView selected;

            ViewHolder(@NonNull View itemView) {
                super(itemView);
                domain = itemView.findViewById(R.id.provider_domain);
                recommended = itemView.findViewById(R.id.provider_recommended);
                selected = itemView.findViewById(R.id.provider_selected);
            }
        }
    }

    private String updateDomain() {
        String getUpdatedDomain = null;
        if (domain == null && !useOwnProvider) {
            getUpdatedDomain = selectedDomain != null ? selectedDomain : Config.MAGIC_CREATE_DOMAIN;
        }
        if (useOwnProvider) {
            getUpdatedDomain = binding.ownServer.getText().toString();
        }
        return getUpdatedDomain;
    }

    @Override
    public void beforeTextChanged(CharSequence s, int start, int count, int after) {

    }

    @Override
    public void onTextChanged(CharSequence s, int start, int before, int count) {

    }

    @Override
    public void afterTextChanged(Editable s) {
        updateFullJidInformation(s.toString());
    }

    private void updateFullJidInformation(String username) {
        if (useOwnProvider && !registerFromUri) {
            this.domain = updateDomain();
        } else if (!registerFromUri && selectedDomain != null && !selectedDomain.isEmpty()) {
            this.domain = selectedDomain;
        }
        if (username.trim().isEmpty()) {
            binding.fullJid.setVisibility(View.INVISIBLE);
        } else {
            try {
                binding.fullJid.setVisibility(View.VISIBLE);
                final Jid jid;
                if (this.domain == null) {
                    jid = Jid.ofLocalAndDomain(username, Config.MAGIC_CREATE_DOMAIN);
                } else {
                    jid = Jid.ofLocalAndDomain(username, this.domain);
                }
                binding.fullJid.setText(getString(R.string.your_full_jid_will_be, jid.toString()));
            } catch (IllegalArgumentException e) {
                binding.fullJid.setVisibility(View.INVISIBLE);
            }

        }
    }

    @Override
    public void onDestroy() {
        InstallReferrerUtils.markInstallReferrerExecuted(this);
        super.onDestroy();
    }

    @Override
    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
        if (binding.useOwn.isChecked()) {
            binding.serverLayout.setEnabled(false);
            binding.server.setEnabled(false);
            binding.fullJid.setVisibility(View.GONE);
            useOwnProvider = true;
            binding.ownServerLayout.setVisibility(View.VISIBLE);
        } else {
            binding.serverLayout.setEnabled(true);
            binding.server.setEnabled(true);
            binding.fullJid.setVisibility(View.VISIBLE);
            useOwnProvider = false;
            binding.ownServerLayout.setVisibility(View.GONE);
        }
        registerFromUri = false;
        updateFullJidInformation(binding.username.getText().toString());
    }
}
