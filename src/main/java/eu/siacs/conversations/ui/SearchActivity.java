/*
 * Copyright (c) 2018, Daniel Gultsch All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation and/or
 * other materials provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors
 * may be used to endorse or promote products derived from this software without
 * specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package eu.siacs.conversations.ui;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Patterns;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.widget.PopupMenu;
import androidx.databinding.DataBindingUtil;
import androidx.recyclerview.widget.ConcatAdapter;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.common.base.Strings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;

import eu.siacs.conversations.R;
import eu.siacs.conversations.databinding.ActivitySearchBinding;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Bookmark;
import eu.siacs.conversations.entities.Contact;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Conversational;
import eu.siacs.conversations.entities.DownloadableFile;
import eu.siacs.conversations.entities.ListItem;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.persistance.FileBackend;
import eu.siacs.conversations.services.MessageSearchTask;
import eu.siacs.conversations.ui.adapter.ConversationSearchAdapter;
import eu.siacs.conversations.ui.adapter.SearchResultAdapter;
import eu.siacs.conversations.ui.adapter.SectionHeaderAdapter;
import eu.siacs.conversations.ui.interfaces.OnSearchResultsAvailable;
import eu.siacs.conversations.ui.util.ChangeWatcher;
import eu.siacs.conversations.ui.util.PendingItem;
import eu.siacs.conversations.ui.util.ShareUtil;
import eu.siacs.conversations.ui.util.ViewUtil;
import eu.siacs.conversations.utils.FtsUtils;
import eu.siacs.conversations.utils.MessageUtils;
import eu.siacs.conversations.utils.StylingHelper;
import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xmpp.Jid;

import static eu.siacs.conversations.ui.util.SoftKeyboardUtils.hideSoftKeyboard;
import static eu.siacs.conversations.ui.util.SoftKeyboardUtils.showKeyboard;

public class SearchActivity extends XmppActivity
        implements TextWatcher,
                OnSearchResultsAvailable,
                SearchResultAdapter.OnResultActionListener,
                ConversationSearchAdapter.OnConversationClickedListener {

    private static final String EXTRA_SEARCH_TERM = "search-term";
    public static final String EXTRA_CONVERSATION_UUID = "uuid";

    private enum Filter {
        ALL,
        TEXT,
        MEDIA,
        FILES,
        LINKS
    }

    private ActivitySearchBinding binding;
    private SearchResultAdapter adapter;
    private ConversationSearchAdapter conversationAdapter;
    private SectionHeaderAdapter conversationsHeader;
    private SectionHeaderAdapter messagesHeader;
    /** Full, unfiltered message result set; the filter chips produce the displayed subset. */
    private final List<Message> allResults = new ArrayList<>();
    /** Matching contacts and group bookmarks for the "Conversations" section (global search only). */
    private final List<ListItem> conversationResults = new ArrayList<>();
    private Filter activeFilter = Filter.ALL;
    /** Results arrive newest-first from the search task; this flips the displayed order. */
    private boolean sortOldestFirst = false;
    private String uuid;
    private final ChangeWatcher<List<String>> currentSearch = new ChangeWatcher<>();
    private final PendingItem<String> pendingSearchTerm = new PendingItem<>();
    private final PendingItem<List<String>> pendingSearch = new PendingItem<>();

    @Override
    public void onCreate(final Bundle bundle) {
        final Intent intent = getIntent();
        this.uuid = intent == null ? null : Strings.emptyToNull(intent.getStringExtra(EXTRA_CONVERSATION_UUID));
        final String searchTerm = bundle == null ? null : bundle.getString(EXTRA_SEARCH_TERM);
        if (searchTerm != null) {
            pendingSearchTerm.push(searchTerm);
        }
        super.onCreate(bundle);
        this.binding = DataBindingUtil.setContentView(this, R.layout.activity_search);
        Activities.setStatusAndNavigationBarColors(this, binding.getRoot());
        setSupportActionBar(this.binding.toolbar);
        configureActionBar(getSupportActionBar());
        // Conversation context (avatar + name) is only useful when searching across all chats.
        final boolean globalSearch = uuid == null;
        this.adapter = new SearchResultAdapter(this, globalSearch);
        this.adapter.setOnResultActionListener(this);
        this.conversationAdapter = new ConversationSearchAdapter(this);
        this.conversationAdapter.setOnConversationClickedListener(this);
        // Use isolated stable ids so the concatenated adapters' item ids never collide.
        final ConcatAdapter.Config config =
                new ConcatAdapter.Config.Builder()
                        .setIsolateViewTypes(true)
                        .setStableIdMode(
                                ConcatAdapter.Config.StableIdMode.ISOLATED_STABLE_IDS)
                        .build();
        this.conversationsHeader = new SectionHeaderAdapter(1);
        this.messagesHeader = new SectionHeaderAdapter(2);
        final ConcatAdapter concatAdapter =
                new ConcatAdapter(
                        config,
                        conversationsHeader,
                        conversationAdapter,
                        messagesHeader,
                        adapter);
        this.binding.searchResults.setLayoutManager(new LinearLayoutManager(this));
        this.binding.searchResults.setAdapter(concatAdapter);
        this.binding.filterChips.setOnCheckedStateChangeListener(
                (group, checkedIds) -> {
                    activeFilter = filterForCheckedChip(group.getCheckedChipId());
                    applyFilterAndRender();
                });
        this.binding.sortDirection.setOnClickListener(
                v -> {
                    sortOldestFirst = !sortOldestFirst;
                    applyFilterAndRender();
                });
        renderChrome();
    }

    private Filter filterForCheckedChip(final int checkedId) {
        if (checkedId == R.id.chip_text) return Filter.TEXT;
        if (checkedId == R.id.chip_media) return Filter.MEDIA;
        if (checkedId == R.id.chip_files) return Filter.FILES;
        if (checkedId == R.id.chip_links) return Filter.LINKS;
        return Filter.ALL;
    }

    @Override
    public boolean onCreateOptionsMenu(final Menu menu) {
        getMenuInflater().inflate(R.menu.activity_search, menu);
        final MenuItem searchActionMenuItem = menu.findItem(R.id.action_search);
        final EditText searchField = searchActionMenuItem.getActionView().findViewById(R.id.search_field);
        final String term = pendingSearchTerm.pop();
        if (term != null) {
            searchField.append(term);
            final List<String> searchTerm = FtsUtils.parse(term);
            if (xmppConnectionService != null) {
                if (currentSearch.watch(searchTerm)) {
                    xmppConnectionService.search(searchTerm, uuid, this);
                }
            } else {
                pendingSearch.push(searchTerm);
            }
        }
        searchField.addTextChangedListener(this);
        searchField.setHint(R.string.search_messages);
        searchField.setContentDescription(getString(R.string.search_messages));
        searchField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_AUTO_COMPLETE);
        if (term == null) {
            showKeyboard(searchField);
        }
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            hideSoftKeyboard(this);
        }
        return super.onOptionsItemSelected(item);
    }

    // -- result actions ------------------------------------------------------------------------

    @Override
    public void onResultClicked(final Message message) {
        openConversation(message);
    }

    @Override
    public void onMediaClicked(final Message message) {
        final DownloadableFile file =
                xmppConnectionService.getFileBackend().getFile(message);
        if (file != null && file.exists()) {
            final Message.FileParams fp = message.getFileParams();
            final String name = fp == null ? null : fp.getName();
            final String displayName = name == null ? file.getName() : name;
            ViewUtil.view(this, file, displayName, message.getConversation().getUuid(), message.getUuid());
        } else {
            // Not downloaded locally — jump to the conversation so it can be fetched there.
            openConversation(message);
        }
    }

    @Override
    public void onResultLongClicked(final View anchor, final Message message) {
        final PopupMenu popupMenu = new PopupMenu(this, anchor);
        popupMenu.inflate(R.menu.search_result_context);
        final Menu menu = popupMenu.getMenu();

        final boolean deleted = message.isDeleted();
        final boolean waitingOfferedSending =
                message.getStatus() == Message.STATUS_WAITING
                        || message.getStatus() == Message.STATUS_UNSEND
                        || message.getStatus() == Message.STATUS_OFFERED;
        final boolean cancelable =
                (message.getTransferable() != null && !deleted)
                        || waitingOfferedSending && message.needsUploading();
        if (message.isFileOrImage() && !deleted && !cancelable) {
            final String path = message.getRelativeFilePath();
            if (path == null
                    || !path.startsWith("/")
                    || FileBackend.inConversationsDirectory(this, path)) {
                menu.findItem(R.id.save_to_downloads).setVisible(true);
            }
        }
        if (message.isGeoUri()) {
            menu.findItem(R.id.copy_message).setVisible(false);
            menu.findItem(R.id.quote_message).setVisible(false);
        }
        popupMenu.setOnMenuItemClickListener(item -> performAction(item.getItemId(), message));
        popupMenu.show();
    }

    private boolean performAction(final int menuId, final Message message) {
        if (menuId == R.id.open_conversation) {
            openConversation(message);
        } else if (menuId == R.id.share_with) {
            ShareUtil.share(this, message);
        } else if (menuId == R.id.copy_message) {
            ShareUtil.copyToClipboard(this, message);
        } else if (menuId == R.id.save_to_downloads) {
            xmppConnectionService.copyAttachmentToDownloadsFolder(message, new UiCallback<>() {
                @Override
                public void success(Integer object) {
                    runOnUiThread(() -> Toast.makeText(SearchActivity.this, R.string.save_to_downloads_success, Toast.LENGTH_LONG).show());
                }

                @Override
                public void error(int errorCode, Integer object) {
                    runOnUiThread(() -> Toast.makeText(SearchActivity.this, object, Toast.LENGTH_LONG).show());
                }

                @Override
                public void userInputRequired(PendingIntent pi, Integer object) {
                }
            });
        } else if (menuId == R.id.copy_url) {
            ShareUtil.copyUrlToClipboard(this, message);
        } else if (menuId == R.id.quote_message) {
            quote(message);
        } else {
            return false;
        }
        return true;
    }

    private void openConversation(final Message message) {
        final Element thread = message.getThread();
        switchToConversationOnMessage(
                wrap(message.getConversation()),
                thread == null ? null : thread.getContent(),
                message.getUuid());
    }

    @Override
    public void onConversationClicked(final ListItem item) {
        hideSoftKeyboard(this);
        if (item instanceof Bookmark bookmark) {
            final Jid jid = bookmark.getFullJid();
            if (jid == null) {
                Toast.makeText(this, R.string.invalid_jid, Toast.LENGTH_SHORT).show();
                return;
            }
            final Conversation conversation =
                    xmppConnectionService.findOrCreateConversation(
                            bookmark.getAccount(), jid, true, true, true);
            bookmark.setConversation(conversation);
            if (!bookmark.autojoin()) {
                bookmark.setAutojoin(true);
                xmppConnectionService.createBookmark(bookmark.getAccount(), bookmark);
            }
            switchToConversation(conversation);
        } else if (item instanceof Contact contact) {
            final Conversation conversation =
                    xmppConnectionService.findOrCreateConversation(
                            contact.getAccount(), contact.getJid(), false, true);
            switchToConversation(conversation);
        }
    }

    @Override
    public void onSaveInstanceState(Bundle bundle) {
        List<String> term = currentSearch.get();
        if (term != null && term.size() > 0) {
            bundle.putString(EXTRA_SEARCH_TERM, FtsUtils.toUserEnteredString(term));
        }
        super.onSaveInstanceState(bundle);
    }

    private void quote(Message message) {
        switchToConversationAndQuote(wrap(message.getConversation()), MessageUtils.prepareQuote(message));
    }

    private Conversation wrap(Conversational conversational) {
        if (conversational instanceof Conversation) {
            return (Conversation) conversational;
        } else {
            return xmppConnectionService.findOrCreateConversation(conversational.getAccount(),
                    conversational.getJid(),
                    conversational.getMode() == Conversational.MODE_MULTI,
                    true,
                    true);
        }
    }

    @Override
    protected void refreshUiReal() {

    }

    @Override
    protected void onBackendConnected() {
        final List<String> searchTerm = pendingSearch.pop();
        if (searchTerm != null && currentSearch.watch(searchTerm)) {
            xmppConnectionService.search(searchTerm, uuid, this);
        }
    }

    @Override
    public void beforeTextChanged(CharSequence s, int start, int count, int after) {

    }

    @Override
    public void onTextChanged(CharSequence s, int start, int before, int count) {

    }

    @Override
    public void afterTextChanged(Editable s) {
        final List<String> term = FtsUtils.parse(s.toString().trim());
        if (!currentSearch.watch(term)) {
            return;
        }
        if (term.isEmpty()) {
            MessageSearchTask.cancelRunningTasks();
            this.allResults.clear();
            this.conversationResults.clear();
            this.adapter.setHighlightedTerm(null);
            this.conversationAdapter.setHighlightedTerm(null);
            this.adapter.submitItems(new ArrayList<>());
            this.conversationAdapter.submitItems(new ArrayList<>());
            applyFilterAndRender();
        } else {
            // Contacts/groups match synchronously against the in-memory roster, so the
            // Conversations section can appear instantly while the message FTS query runs.
            this.conversationAdapter.setHighlightedTerm(term);
            recomputeConversations(term);
            applyFilterAndRender();
            xmppConnectionService.search(term, uuid, this);
        }
    }

    @Override
    public void onSearchResultsAvailable(List<String> term, List<Message> messages) {
        runOnUiThread(() -> {
            this.allResults.clear();
            this.allResults.addAll(messages);
            this.adapter.setHighlightedTerm(term);
            this.conversationAdapter.setHighlightedTerm(term);
            // Covers the initial / restored-term path, which doesn't pass through afterTextChanged.
            recomputeConversations(term);
            applyFilterAndRender();
        });
    }

    private void applyFilterAndRender() {
        final List<Message> filtered = new ArrayList<>();
        for (final Message message : allResults) {
            if (matchesFilter(message, activeFilter)) {
                filtered.add(message);
            }
        }
        if (sortOldestFirst) {
            Collections.reverse(filtered);
        }
        adapter.submitItems(filtered);
        conversationAdapter.submitItems(new ArrayList<>(conversationResults));
        updateSectionHeaders(conversationResults.size(), filtered.size());
        renderChrome();
    }

    /**
     * Show the inline "Conversations" / "Messages" dividers only when there is a conversation
     * section to separate from the messages. When the search is a plain single-section message
     * list (no contact/group hits) the dividers stay hidden and the top result-count header is
     * used instead — so message-only searches look exactly as before.
     */
    private void updateSectionHeaders(final int conversationCount, final int messageCount) {
        final boolean sectioned = conversationCount > 0;
        conversationsHeader.setHeader(
                sectioned ? getString(R.string.search_section_conversations) : null,
                sectioned ? String.valueOf(conversationCount) : null);
        final boolean showMessages = sectioned && messageCount > 0;
        messagesHeader.setHeader(
                showMessages ? getString(R.string.search_section_messages) : null,
                showMessages ? String.valueOf(messageCount) : null);
    }

    /** Recompute the matching contacts and group bookmarks for the current query (global only). */
    private void recomputeConversations(final List<String> term) {
        conversationResults.clear();
        if (uuid != null || xmppConnectionService == null) {
            return;
        }
        final String needle = FtsUtils.toUserEnteredString(term);
        if (needle == null || needle.trim().isEmpty()) {
            return;
        }
        for (final Account account : xmppConnectionService.getAccounts()) {
            if (!account.isEnabled()) {
                continue;
            }
            for (final Contact contact : account.getRoster().getContacts()) {
                if (contact.showInContactList() && contact.match(this, needle)) {
                    conversationResults.add(contact);
                }
            }
            final Contact self = new Contact(account.getSelfContact());
            self.setSystemName(getString(R.string.note_to_self));
            if (self.match(this, needle)) {
                conversationResults.add(self);
            }
            for (final Bookmark bookmark : account.getBookmarks()) {
                if (bookmark.match(this, needle)) {
                    conversationResults.add(bookmark);
                }
            }
        }
        Collections.sort(conversationResults);
    }

    private boolean matchesFilter(final Message message, final Filter filter) {
        final int viewType = SearchResultAdapter.viewTypeFor(message);
        return switch (filter) {
            case ALL -> true;
            case MEDIA -> viewType == SearchResultAdapter.TYPE_MEDIA;
            case FILES -> viewType == SearchResultAdapter.TYPE_FILE;
            case LINKS -> viewType == SearchResultAdapter.TYPE_TEXT && termMatchesLink(message);
            case TEXT -> viewType == SearchResultAdapter.TYPE_TEXT && !termMatchesLink(message);
        };
    }

    /**
     * Whether the current search term was matched <em>inside a URL</em> within the message —
     * regardless of whether the message is a bare link or text mixed with a link. The Links
     * filter only wants hits in the links themselves, never in the surrounding prose, so a
     * message whose term appears only in its text is excluded (and stays under Text).
     */
    private boolean termMatchesLink(final Message message) {
        final List<String> terms = currentSearch.get();
        final String body = message.getBody();
        if (terms == null || body == null) {
            return false;
        }
        final List<String> words = StylingHelper.filterHighlightedWords(terms);
        if (words.isEmpty()) {
            return false;
        }
        final Matcher matcher = Patterns.WEB_URL.matcher(body);
        while (matcher.find()) {
            final String url = matcher.group().toLowerCase(Locale.US);
            for (final String word : words) {
                if (!word.isEmpty() && url.contains(word.toLowerCase(Locale.US))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Update chips, the result-count header and the empty-state overlay for the current state. */
    private void renderChrome() {
        final List<String> term = currentSearch.get();
        final boolean hasQuery = term != null && !term.isEmpty();
        final boolean hasMessages = !allResults.isEmpty();
        final boolean hasConversations = !conversationResults.isEmpty();
        final int shownMessages = adapter.getItemCount();

        // The chips filter messages and the sort toggle reorders them, so the row only makes
        // sense once there are messages to act on.
        binding.filterRow.setVisibility(hasQuery && hasMessages ? View.VISIBLE : View.GONE);

        if (!hasQuery) {
            binding.resultCount.setVisibility(View.GONE);
            showEmptyState(
                    getString(R.string.search_empty_title),
                    getString(R.string.search_empty_subtitle));
            return;
        }

        if (!hasMessages && !hasConversations) {
            binding.resultCount.setVisibility(View.GONE);
            showEmptyState(
                    getString(R.string.search_no_results_title),
                    getString(R.string.search_no_results_subtitle, FtsUtils.toUserEnteredString(term)));
            return;
        }

        hideEmptyState();

        // When the result list is split into sections, the counts live in the inline section
        // headers; otherwise the single top header carries the message count as before.
        if (hasConversations) {
            binding.resultCount.setVisibility(View.GONE);
        } else if (shownMessages > 0) {
            binding.resultCount.setVisibility(View.VISIBLE);
            binding.resultCount.setText(
                    getResources()
                            .getQuantityString(
                                    R.plurals.search_results_count, shownMessages, shownMessages));
        } else {
            // Query matched messages, but the active filter hides them all and there are no
            // conversation hits to show instead — surface the empty state, keep the chips visible.
            binding.resultCount.setVisibility(View.GONE);
            showEmptyState(
                    getString(R.string.search_no_results_title),
                    getString(R.string.search_no_results_subtitle, FtsUtils.toUserEnteredString(term)));
        }
    }

    private void showEmptyState(final String title, final String subtitle) {
        binding.emptyState.setVisibility(View.VISIBLE);
        binding.emptyTitle.setText(title);
        binding.emptySubtitle.setText(subtitle);
    }

    private void hideEmptyState() {
        binding.emptyState.setVisibility(View.GONE);
    }

    @Override
    public void onContactPictureClicked(Message message) {
        String fingerprint;
        if (message.getEncryption() == Message.ENCRYPTION_PGP || message.getEncryption() == Message.ENCRYPTION_DECRYPTED) {
            fingerprint = "pgp";
        } else {
            fingerprint = message.getFingerprint();
        }
        if (message.getStatus() == Message.STATUS_RECEIVED) {
            final Contact contact = message.getContact();
            if (contact != null) {
                if (contact.isSelf()) {
                    switchToAccount(message.getConversation().getAccount(), fingerprint);
                } else {
                    switchToContactDetails(contact, fingerprint);
                }
            }
        } else {
            switchToAccount(message.getConversation().getAccount(), fingerprint);
        }
    }
}
