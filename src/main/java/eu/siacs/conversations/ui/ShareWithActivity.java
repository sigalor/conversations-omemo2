package eu.siacs.conversations.ui;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.core.content.pm.ShortcutManagerCompat;
import androidx.databinding.DataBindingUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import com.google.android.material.chip.Chip;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.collect.Iterables;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.databinding.ActivityShareWithBinding;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.persistance.DatabaseBackend;
import eu.siacs.conversations.services.ShortcutService;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.ui.adapter.ConversationAdapter;
import eu.siacs.conversations.ui.util.TrustKeys;
import eu.siacs.conversations.xmpp.Jid;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class ShareWithActivity extends XmppActivity
        implements XmppConnectionService.OnConversationUpdate {

    private static final int REQUEST_STORAGE_PERMISSION = 0x733f32;
    private Conversation mPendingConversation = null;

    @Override
    public void onConversationUpdate() {
        refreshUi();
    }

    private static class Share {
        public String type;
        ArrayList<Uri> uris = new ArrayList<>();
        public String account;
        public String contact;
        public String text;
        public boolean asQuote = false;
    }

    private Share share;

    private static final int REQUEST_START_NEW_CONVERSATION = 0x0501;
    private static final int REQUEST_TRUST_KEYS_SHARE = 0x0502;
    private ConversationAdapter mAdapter;
    private final List<Conversation> mConversations = new ArrayList<>();
    private ActivityShareWithBinding binding;
    private boolean pendingMultiSend = false;
    private boolean pendingTrustedSend = false;
    private boolean captionPrefilled = false;
    private String[] pendingContacts = null;
    private String pendingContactsAccount = null;

    protected void onActivityResult(
            final int requestCode, final int resultCode, final Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_TRUST_KEYS_SHARE) {
            if (resultCode == RESULT_OK) {
                // Keys decided — retry, which checks the remaining recipients.
                // The service is rebound asynchronously after coming back from
                // the trust screen and is usually NOT bound yet here; without
                // the flag the retry would hit the bound-check in
                // sendToSelected() and the share would be dropped silently,
                // right after the user did what we asked of them.
                pendingTrustedSend = true;
                sendAfterTrustIfPossible();
            }
            return;
        }
        if (requestCode == REQUEST_START_NEW_CONVERSATION && resultCode == RESULT_OK) {
            final String[] contacts = data.getStringArrayExtra("contacts");
            if (contacts != null && contacts.length > 0) {
                // The user picked one or more contacts from the roster: add them to the
                // multi-selection rather than sending to a single recipient. The service
                // may not be (re)bound yet at this point, so defer if needed.
                pendingContacts = contacts;
                pendingContactsAccount = data.getStringExtra(EXTRA_ACCOUNT);
                maybeAddPendingContacts();
                return;
            }
            share.contact = data.getStringExtra("contact");
            share.account = data.getStringExtra(EXTRA_ACCOUNT);
        }
        if (xmppConnectionServiceBound
                && share != null
                && share.contact != null
                && share.account != null) {
            share();
        }
    }

    private void maybeAddPendingContacts() {
        if (!xmppConnectionServiceBound || pendingContacts == null) {
            return;
        }
        final String[] contacts = pendingContacts;
        final String accountJid = pendingContactsAccount;
        pendingContacts = null;
        pendingContactsAccount = null;
        addContactsToSelection(contacts, accountJid);
    }

    private void addContactsToSelection(final String[] contactJids, final String accountJid) {
        if (!xmppConnectionServiceBound) {
            return;
        }
        for (final String contactJid : contactJids) {
            final Jid jid;
            try {
                jid = Jid.of(contactJid);
            } catch (final IllegalArgumentException e) {
                continue;
            }
            final Account account = resolveAccountForContact(jid, accountJid);
            if (account == null) {
                continue;
            }
            final Conversation conversation =
                    xmppConnectionService.findOrCreateConversation(account, jid, false, true);
            if (conversation != null) {
                mAdapter.select(conversation);
            }
        }
        refreshUiReal();
    }

    private Account resolveAccountForContact(final Jid jid, final String accountJid) {
        if (!Strings.isNullOrEmpty(accountJid)) {
            try {
                final Account account =
                        xmppConnectionService.findAccountByJid(Jid.of(accountJid));
                if (account != null) {
                    return account;
                }
            } catch (final IllegalArgumentException e) {
                // fall through to roster lookup
            }
        }
        Account fallback = null;
        for (final Account account : xmppConnectionService.getAccounts()) {
            if (!account.isEnabled()) {
                continue;
            }
            if (fallback == null) {
                fallback = account;
            }
            if (account.getRoster().getContact(jid).showInContactList()) {
                return account;
            }
        }
        return fallback;
    }

    @Override
    public void onRequestPermissionsResult(
            final int requestCode,
            @NonNull final String[] permissions,
            @NonNull final int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (grantResults.length > 0)
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (requestCode == REQUEST_STORAGE_PERMISSION) {
                    if (pendingMultiSend) {
                        pendingMultiSend = false;
                        sendToSelected(mAdapter.getSelectedConversations());
                    } else if (this.mPendingConversation != null) {
                        share(this.mPendingConversation);
                    } else {
                        Log.d(Config.LOGTAG, "unable to find stored conversation");
                    }
                }
            } else {
                Toast.makeText(
                                this,
                                getString(
                                        R.string.no_storage_permission,
                                        getString(R.string.app_name)),
                                Toast.LENGTH_SHORT)
                        .show();
            }
    }

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        this.binding =
                DataBindingUtil.setContentView(this, R.layout.activity_share_with);
        setSupportActionBar(binding.toolbar);
        final var actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(false);
            actionBar.setHomeButtonEnabled(false);
        }
        Activities.setStatusAndNavigationBarColors(this, binding.getRoot());
        setTitle(R.string.title_activity_share_with);

        mAdapter = new ConversationAdapter(this, this.mConversations);
        mAdapter.setSelectionMode(true);
        mAdapter.setSelectionChangedListener(this::updateSendBar);
        binding.chooseConversationList.setLayoutManager(
                new LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false));
        binding.chooseConversationList.setAdapter(mAdapter);
        mAdapter.setConversationClickListener((view, conversation) -> share(conversation));
        binding.sendButton.setOnClickListener(v -> onSendClicked());
        updateSendBar();
        final var intent = getIntent();
        final var shortcutId = intent.getStringExtra(ShortcutManagerCompat.EXTRA_SHORTCUT_ID);
        this.share = new Share();
        if (shortcutId != null) {
            final var conversation = shortcutIdToConversation(shortcutId);
            if (conversation != null) {
                // we have everything we need. Jump into chat
                populateShare(intent);
                share(conversation);
            }
        }
    }

    private String shortcutIdToConversation(final String shortcutId) {
        final var shortcut =
                Iterables.tryFind(
                        ShortcutManagerCompat.getDynamicShortcuts(this),
                        si -> si.getId().equals(shortcutId));
        if (shortcut.isPresent()) {
            final var extras = shortcut.get().getExtras();
            if (extras == null) {
                return shortcutIdToConversationFallback(shortcutId);
            } else {
                final var conversation = extras.getString(ConversationsActivity.EXTRA_CONVERSATION);
                if (Strings.isNullOrEmpty(conversation)) {
                    return shortcutIdToConversationFallback(shortcutId);
                } else {
                    return conversation;
                }
            }
        } else {
            return shortcutIdToConversationFallback(shortcutId);
        }
    }

    private String shortcutIdToConversationFallback(final String shortcutId) {
        final var parts =
                Splitter.on(ShortcutService.ID_SEPARATOR).limit(2).splitToList(shortcutId);
        if (parts.size() == 2) {
            final var account = Jid.of(parts.get(0));
            final var jid = Jid.of(parts.get(1));
            final var database = DatabaseBackend.getInstance(getApplicationContext());
            return database.findConversationUuid(account, jid);
        } else {
            return null;
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.share_with, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        if (item.getItemId() == R.id.action_add) {
            final Intent intent =
                    new Intent(getApplicationContext(), ChooseContactActivity.class);
            // Let the user pick any contact(s) from the full roster and add them to
            // the multi-selection instead of sending to a single recipient right away.
            intent.putExtra(ChooseContactActivity.EXTRA_SELECT_MULTIPLE, true);
            intent.putExtra(ChooseContactActivity.EXTRA_SHOW_ENTER_JID, true);
            startActivityForResult(intent, REQUEST_START_NEW_CONVERSATION);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onStart() {
        super.onStart();
        final Intent intent = getIntent();
        if (intent == null) {
            return;
        }
        populateShare(intent);
        if (!captionPrefilled && !Strings.isNullOrEmpty(this.share.text)) {
            binding.caption.setText(this.share.text);
            captionPrefilled = true;
        }
        if (xmppConnectionServiceBound) {
            xmppConnectionService.populateWithOrderedConversations(
                    mConversations, this.share.uris.isEmpty(), false);
        }
    }

    private void populateShare(final Intent intent) {
        final String type = intent.getType();
        final String action = intent.getAction();
        final Uri data = intent.getData();
        if (Intent.ACTION_SEND.equals(action)) {
            final String text = intent.getStringExtra(Intent.EXTRA_TEXT);
            final Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            final boolean asQuote =
                    intent.getBooleanExtra(ConversationsActivity.EXTRA_AS_QUOTE, false);

            if (data != null && "geo".equals(data.getScheme())) {
                this.share.uris.clear();
                this.share.uris.add(data);
            } else if (type != null && uri != null) {
                this.share.uris.clear();
                this.share.uris.add(uri);
                this.share.type = type;
            } else {
                this.share.text = text;
                this.share.asQuote = asQuote;
            }
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            final ArrayList<Uri> uris = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            this.share.uris = uris == null ? new ArrayList<>() : uris;
        }
        final var shortcutId = intent.getStringExtra(Intent.EXTRA_SHORTCUT_ID);
        if (shortcutId != null) {
            final var index = shortcutId.indexOf('#');
            if (index >= 0) {
                this.share.account = shortcutId.substring(0, index);
                this.share.contact = shortcutId.substring(index+1);
            }
        }
        if (xmppConnectionServiceBound) {
            xmppConnectionService.populateWithOrderedConversations(
                    mConversations, this.share.uris.isEmpty(), false);
        }
    }

    @Override
    protected void onBackendConnected() {
        if (xmppConnectionServiceBound
                && share != null
                && ((share.contact != null && share.account != null))) {
            share();
            return;
        }
        maybeAddPendingContacts();
        refreshUiReal();
        sendAfterTrustIfPossible();
    }

    /**
     * Resumes a share that was interrupted by the trust screen, once the service
     * is available again. No-op until then (and until the user has actually
     * decided keys), so the ordinary bind on startup does not send anything.
     */
    private void sendAfterTrustIfPossible() {
        if (!pendingTrustedSend || !xmppConnectionServiceBound) {
            return;
        }
        pendingTrustedSend = false;
        onSendClicked();
    }

    private void share() {
        final Conversation conversation;
        Account account;
        try {
            account = xmppConnectionService.findAccountByJid(Jid.of(share.account));
        } catch (final IllegalArgumentException e) {
            account = null;
        }
        if (account == null) {
            return;
        }

        try {
            conversation =
                    xmppConnectionService.findOrCreateConversation(
                            account, Jid.of(share.contact), false, true);
        } catch (final IllegalArgumentException e) {
            return;
        }
        share(conversation);
    }

    private void share(final Conversation conversation) {
        if (!share.uris.isEmpty() && !hasStoragePermission(REQUEST_STORAGE_PERMISSION)) {
            mPendingConversation = conversation;
            return;
        }
        share(conversation.getUuid());
    }

    private void share(final String conversation) {
        final Intent intent = new Intent(this, ConversationsActivity.class);
        intent.putExtra(ConversationsActivity.EXTRA_CONVERSATION, conversation);
        if (!share.uris.isEmpty()) {
            intent.setAction(Intent.ACTION_SEND_MULTIPLE);
            intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, share.uris);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (share.type != null) {
                intent.putExtra(ConversationsActivity.EXTRA_TYPE, share.type);
            }
        } else if (share.text != null) {
            intent.setAction(ConversationsActivity.ACTION_VIEW_CONVERSATION);
            intent.putExtra(Intent.EXTRA_TEXT, share.text);
            intent.putExtra(ConversationsActivity.EXTRA_AS_QUOTE, share.asQuote);
        }
        try {
            startActivity(intent);
        } catch (final SecurityException e) {
            Toast.makeText(
                            this,
                            R.string.sharing_application_not_grant_permission,
                            Toast.LENGTH_SHORT)
                    .show();
            return;
        }
        finish();
    }

    private void updateSendBar() {
        final List<Conversation> selected =
                mAdapter == null ? new ArrayList<>() : mAdapter.getSelectedConversations();
        if (selected.isEmpty()) {
            binding.sendBar.setVisibility(View.GONE);
            return;
        }
        binding.sendBar.setVisibility(View.VISIBLE);
        binding.selectedChips.removeAllViews();
        for (final Conversation conversation : selected) {
            final Chip chip = new Chip(this);
            chip.setText(conversation.getName());
            chip.setCloseIconVisible(true);
            chip.setOnCloseIconClickListener(v -> mAdapter.deselect(conversation));
            binding.selectedChips.addView(chip);
        }
    }

    /**
     * Opens the trust screen for the first selected conversation that still has
     * keys to decide, and reports whether it did. Returning from that screen
     * re-runs the send, which lands here again for the next recipient until all
     * are settled — the same gate {@link ConversationFragment} applies before
     * sending from a chat.
     */
    private boolean trustKeysIfNeeded(final List<Conversation> selected) {
        for (final Conversation conversation : selected) {
            final Intent intent = TrustKeys.intentFor(this, conversation);
            if (intent != null) {
                startActivityForResult(intent, REQUEST_TRUST_KEYS_SHARE);
                return true;
            }
        }
        return false;
    }

    private void onSendClicked() {
        final List<Conversation> selected = mAdapter.getSelectedConversations();
        if (selected.isEmpty()) {
            return;
        }
        if (!share.uris.isEmpty() && !hasStoragePermission(REQUEST_STORAGE_PERMISSION)) {
            pendingMultiSend = true;
            return;
        }
        sendToSelected(selected);
    }

    private void sendToSelected(final List<Conversation> selected) {
        if (!xmppConnectionServiceBound || selected.isEmpty()) {
            return;
        }
        // Encrypting to a recipient whose keys were never decided fails, and
        // sharing has no chat window to report that in — the share just ends in
        // a message that cannot be sent, here or on any retry. Decide the keys
        // first, one recipient at a time, and only send once every recipient is
        // settled so nobody receives half a multi-share.
        if (trustKeysIfNeeded(selected)) {
            return;
        }
        final CharSequence captionInput = binding.caption.getText();
        final String caption = captionInput == null ? null : captionInput.toString().trim();
        if (share.uris.isEmpty()) {
            // Text share: the caption box holds the body (it is prefilled with share.text).
            final String body = Strings.isNullOrEmpty(caption) ? share.text : caption;
            if (Strings.isNullOrEmpty(body)) {
                return;
            }
            xmppConnectionService.shareToConversations(selected, null, null, null, body);
            finishAfterShare(selected);
            return;
        }
        // The shared file is uploaded/copied asynchronously, but the read permission an
        // external app grants us is tied to this activity's lifetime. Copy external
        // content:// uris into private storage up front (off the UI thread) so the read
        // happens before we finish(); our own FileProvider uris and file:// uris read
        // in-process regardless and are passed through unchanged.
        binding.sendButton.setEnabled(false);
        final List<Uri> uris = new ArrayList<>(share.uris);
        final String type = share.type;
        final String captionFinal = Strings.isNullOrEmpty(caption) ? null : caption;
        final String ownAuthority = getPackageName() + ".files";
        new Thread(
                        () -> {
                            final List<Uri> localUris = new ArrayList<>();
                            for (final Uri uri : uris) {
                                if ("content".equals(uri.getScheme())
                                        && !ownAuthority.equals(uri.getAuthority())) {
                                    try {
                                        final File tmp =
                                                new File(
                                                        getCacheDir(),
                                                        "share/"
                                                                + System.currentTimeMillis()
                                                                + "-"
                                                                + localUris.size());
                                        xmppConnectionService
                                                .getFileBackend()
                                                .copyFileToPrivateStorage(tmp, uri);
                                        localUris.add(Uri.fromFile(tmp));
                                    } catch (final Exception e) {
                                        Log.d(
                                                Config.LOGTAG,
                                                "unable to pre-copy shared uri, using original",
                                                e);
                                        localUris.add(uri);
                                    }
                                } else {
                                    localUris.add(uri);
                                }
                            }
                            runOnUiThread(
                                    () -> {
                                        xmppConnectionService.shareToConversations(
                                                selected, localUris, type, captionFinal, null);
                                        finishAfterShare(selected);
                                    });
                        })
                .start();
    }

    private void finishAfterShare(final List<Conversation> selected) {
        if (selected.size() == 1) {
            // Mirror the previous single-share behaviour by opening that conversation.
            final Intent intent = new Intent(this, ConversationsActivity.class);
            intent.putExtra(ConversationsActivity.EXTRA_CONVERSATION, selected.get(0).getUuid());
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            try {
                startActivity(intent);
            } catch (final SecurityException e) {
                // ignore: message was already handed to the service
            }
        } else {
            Toast.makeText(
                            this,
                            getString(R.string.shared_with_x_chats, selected.size()),
                            Toast.LENGTH_SHORT)
                    .show();
        }
        finish();
    }

    public void refreshUiReal() {
        // TODO inject desired order to not resort on refresh
        xmppConnectionService.populateWithOrderedConversations(
                mConversations, this.share != null && this.share.uris.isEmpty(), false);
        mAdapter.notifyDataSetChanged();
        updateSendBar();
    }
}
