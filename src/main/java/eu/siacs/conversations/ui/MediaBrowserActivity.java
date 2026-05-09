package eu.siacs.conversations.ui;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.databinding.DataBindingUtil;
import androidx.fragment.app.Fragment;
import androidx.viewpager2.adapter.FragmentStateAdapter;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.tabs.TabLayoutMediator;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import eu.siacs.conversations.R;
import eu.siacs.conversations.databinding.ActivityMediaBrowserBinding;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Contact;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.ui.adapter.MediaAdapter;
import eu.siacs.conversations.ui.interfaces.OnMediaLoaded;
import eu.siacs.conversations.ui.util.Attachment;
import eu.siacs.conversations.xmpp.Jid;

public class MediaBrowserActivity extends XmppActivity implements OnMediaLoaded {

    private ActivityMediaBrowserBinding binding;
    private final HashSet<Attachment> selectedAttachments = new HashSet<>();
    private ActionMode mActionMode;
    private String mSearchQuery = null;
    private List<Attachment> mAttachments = new ArrayList<>();

    private final ActionMode.Callback mActionModeCallback = new ActionMode.Callback() {
        @Override
        public boolean onCreateActionMode(ActionMode mode, Menu menu) {
            mode.getMenuInflater().inflate(R.menu.media_browser_context, menu);
            return true;
        }

        @Override
        public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
            return false;
        }

        @Override
        public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
            if (item.getItemId() == R.id.action_delete) {
                deleteSelectedMedia();
                return true;
            } else if (item.getItemId() == R.id.action_save) {
                new MaterialAlertDialogBuilder(MediaBrowserActivity.this)
                        .setTitle(R.string.action_save_to_downloads)
                        .setMessage(R.string.save_to_downloads_warning)
                        .setPositiveButton(R.string.confirm, (dialog, which) -> saveSelectedMedia())
                        .setNegativeButton(R.string.cancel, null)
                        .show();
                return true;
            }
            return false;
        }

        @Override
        public void onDestroyActionMode(ActionMode mode) {
            clearSelection();
            mActionMode = null;
        }
    };

    private final MediaAdapter.OnSelectionChangedListener mOnSelectionChangedListener = count -> {
        if (count > 0) {
            if (mActionMode == null) {
                mActionMode = startActionMode(mActionModeCallback);
            }
            if (mActionMode != null) {
                mActionMode.setTitle(String.valueOf(count));
            }
        } else if (mActionMode != null) {
            mActionMode.finish();
        }
    };

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        this.binding = DataBindingUtil.setContentView(this,R.layout.activity_media_browser);
        Activities.setStatusAndNavigationBarColors(this, binding.getRoot());
        setSupportActionBar(binding.toolbar);
        configureActionBar(getSupportActionBar());

        MediaBrowserPagerAdapter adapter = new MediaBrowserPagerAdapter(this);
        binding.viewPager.setAdapter(adapter);

        new TabLayoutMediator(binding.tabs, binding.viewPager, (tab, position) -> {
            tab.setText(getTabTitle(position));
        }).attach();
    }

    private String getTabTitle(int position) {
        return switch (MediaBrowserFragment.MediaType.values()[position]) {
            case ALL -> getString(R.string.tab_all);
            case IMAGES -> getString(R.string.tab_images);
            case VIDEOS -> getString(R.string.tab_videos);
            case AUDIO -> getString(R.string.tab_audio);
            case FILES -> getString(R.string.tab_files);
        };
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.activity_media_browser, menu);
        menu.findItem(R.id.action_clear_search).setVisible(mSearchQuery != null);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    protected void refreshUiReal() {

    }

    @Override
    protected void onBackendConnected() {
        loadMedia();
    }

    private void loadMedia() {
        if (xmppConnectionService == null) return;
        Intent intent = getIntent();
        String accountUuid = intent == null ? null : intent.getStringExtra("account");
        String jidString = intent == null ? null : intent.getStringExtra("jid");
        if (accountUuid != null && jidString != null) {
            Jid jid = Jid.of(jidString);
            xmppConnectionService.getAttachments(accountUuid, jid, mSearchQuery, 0, this);

            if (intent.getStringExtra("conversation_uuid") == null) {
                Account account = xmppConnectionService.findAccountByUuid(accountUuid);
                if (account != null) {
                    Conversation conversation = xmppConnectionService.findOrCreateConversation(account, jid, false, false);
                    intent.putExtra("conversation_uuid", conversation.getUuid());
                }
            }
        } else {
            xmppConnectionService.getAttachments(mSearchQuery, 0, this);
            if (getSupportActionBar() != null) {
                getSupportActionBar().setTitle(R.string.media_gallery);
            }
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_date_search) {
            showDatePicker();
            return true;
        } else if (item.getItemId() == R.id.action_clear_search) {
            mSearchQuery = null;
            loadMedia();
            invalidateOptionsMenu();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void showDatePicker() {
        final var datePicker = com.google.android.material.datepicker.MaterialDatePicker.Builder.datePicker()
                .setTitleText(R.string.search_by_date)
                .setSelection(com.google.android.material.datepicker.MaterialDatePicker.todayInUtcMilliseconds())
                .build();
        datePicker.addOnPositiveButtonClickListener(selection -> {
            java.util.Calendar calendar = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
            calendar.setTimeInMillis(selection);
            int year = calendar.get(java.util.Calendar.YEAR);
            int month = calendar.get(java.util.Calendar.MONTH) + 1;
            int day = calendar.get(java.util.Calendar.DAY_OF_MONTH);
            mSearchQuery = String.format(java.util.Locale.US, "%04d-%02d-%02d", year, month, day);
            loadMedia();
            invalidateOptionsMenu();
        });
        datePicker.show(getSupportFragmentManager(), "date_picker");
    }

    private void saveSelectedMedia() {
        int count = selectedAttachments.size();
        for (Attachment attachment : selectedAttachments) {
            final var path = xmppConnectionService.getFileBackend().getOriginalPath(attachment.getUri());
            if (path != null) {
                final var file = new java.io.File(path);
                xmppConnectionService.copyAttachmentToDownloadsFolder(file, new UiCallback<Integer>() {
                    @Override
                    public void success(Integer object) {
                        // ignore
                    }

                    @Override
                    public void error(int errorCode, Integer object) {
                        runOnUiThread(() -> Toast.makeText(MediaBrowserActivity.this, object, Toast.LENGTH_SHORT).show());
                    }

                    @Override
                    public void userInputRequired(PendingIntent pi, Integer object) {
                        // ignore
                    }
                });
            }
        }
        Toast.makeText(this, getString(R.string.save_to_downloads_success), Toast.LENGTH_SHORT).show();
        clearSelection();
    }

    private void deleteSelectedMedia() {
        xmppConnectionService.deleteMedia(new java.util.ArrayList<>(selectedAttachments));
        clearSelection();
        loadMedia();
    }

    private void clearSelection() {
        selectedAttachments.clear();
        mOnSelectionChangedListener.onSelectionChanged(0);
        notifyFragmentsSelectionChanged();
    }

    private void notifyFragmentsSelectionChanged() {
        for (Fragment fragment : getSupportFragmentManager().getFragments()) {
            if (fragment instanceof MediaBrowserFragment mediaBrowserFragment) {
                if (mediaBrowserFragment.getMediaAdapter() != null) {
                    mediaBrowserFragment.getMediaAdapter().setSelectedAttachments(selectedAttachments);
                }
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (mActionMode != null) {
            mActionMode.finish();
        } else {
            super.onBackPressed();
        }
    }

    public static void launch(Context context, Contact contact) {
        launch(context, contact.getAccount(), contact.getJid().asBareJid().toString());
    }

    public static void launch(Context context, Conversation conversation) {
        launch(context, conversation.getAccount(), conversation.getJid().asBareJid().toString());
    }

    private static void launch(Context context, Account account, String jid) {
        final Intent intent = new Intent(context, MediaBrowserActivity.class);
        intent.putExtra("account",account.getUuid());
        intent.putExtra("jid",jid);
        context.startActivity(intent);
    }

    @Override
    public void onMediaLoaded(List<Attachment> attachments) {
        mAttachments = attachments;
        runOnUiThread(()->{
            for (Fragment fragment : getSupportFragmentManager().getFragments()) {
                if (fragment instanceof MediaBrowserFragment mediaBrowserFragment) {
                    mediaBrowserFragment.setAttachments(attachments);
                }
            }
        });
    }

    public MediaAdapter.OnSelectionChangedListener getSelectionChangedListener() {
        return mOnSelectionChangedListener;
    }

    public HashSet<Attachment> getSelectedAttachments() {
        return selectedAttachments;
    }

    private class MediaBrowserPagerAdapter extends FragmentStateAdapter {

        public MediaBrowserPagerAdapter(@NonNull XmppActivity activity) {
            super(activity);
        }

        @NonNull
        @Override
        public Fragment createFragment(int position) {
            MediaBrowserFragment fragment = MediaBrowserFragment.create(MediaBrowserFragment.MediaType.values()[position]);
            fragment.setAttachments(mAttachments);
            return fragment;
        }

        @Override
        public int getItemCount() {
            return MediaBrowserFragment.MediaType.values().length;
        }
    }
}
