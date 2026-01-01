package eu.siacs.conversations.ui;

import static android.view.View.VISIBLE;

import static eu.siacs.conversations.utils.AccountUtils.MANAGE_ACCOUNT_ACTIVITY;

import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.databinding.DataBindingUtil;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import eu.siacs.conversations.R;
import eu.siacs.conversations.databinding.ActivityStoriesBinding;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Story;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.medialib.activities.EditActivity;
import eu.siacs.conversations.ui.adapter.StoryAdapter;
import eu.siacs.conversations.ui.util.PendingItem;

public class StoriesActivity extends XmppActivity implements XmppConnectionService.OnStoriesUpdate {

    private static final int REQUEST_CHOOSE_STORY_IMAGE = 0x2b01;
    private static final int REQUEST_CAMERA_PERMISSION = 0x2b03;
    private static final int REQUEST_EDIT_STORY_IMAGE = 0x2b04;
    private Account mSelectedAccount;
    private final PendingItem<Uri> pendingTakePhotoUri = new PendingItem<>();

    private ActivityStoriesBinding binding;
    private StoryAdapter storyAdapter;
    private final List<Story> stories = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = DataBindingUtil.setContentView(this, R.layout.activity_stories);
        Activities.setStatusAndNavigationBarColors(this, findViewById(android.R.id.content));
        setSupportActionBar(binding.toolbar);
        configureActionBar(getSupportActionBar());
        binding.fabAddStory.setOnClickListener(v -> selectAccountToPublishStory());
        storyAdapter = new StoryAdapter(this, stories);
        binding.storiesList.setLayoutManager(new LinearLayoutManager(this));
        binding.storiesList.setAdapter(storyAdapter);

        // Bottom Navigation Setup
        BottomNavigationView bottomNavigationView = findViewById(R.id.bottom_navigation);
        bottomNavigationView.setBackgroundColor(Color.TRANSPARENT);
        bottomNavigationView.setOnItemSelectedListener(item -> {
            switch (item.getItemId()) {
                case R.id.chats -> {
                    startActivity(new Intent(getApplicationContext(), ConversationsActivity.class));
                    overridePendingTransition(R.animator.fade_in, R.animator.fade_out);
                    return true;
                }
                case R.id.contactslist -> {
                    Intent i = new Intent(getApplicationContext(), StartConversationActivity.class);
                    i.putExtra("show_nav_bar", true);
                    startActivity(i);
                    overridePendingTransition(R.animator.fade_in, R.animator.fade_out);
                    return true;
                }
                case R.id.stories -> {
                    return true;
                }
                case R.id.manageaccounts -> {
                    Intent i = new Intent(getApplicationContext(), MANAGE_ACCOUNT_ACTIVITY);
                    i.putExtra("show_nav_bar", true);
                    startActivity(i);
                    overridePendingTransition(R.animator.fade_in, R.animator.fade_out);
                    return true;
                }
                default ->
                        throw new IllegalStateException("Unexpected value: " + item.getItemId());
            }
        });
    }

    @Override
    public void onStart() {
        super.onStart();
        BottomNavigationView bottomNavigationView=findViewById(R.id.bottom_navigation);
        bottomNavigationView.setSelectedItemId(R.id.stories);

        if (getBooleanPreference("show_nav_bar", R.bool.show_nav_bar) && getIntent().getBooleanExtra("show_nav_bar", false)) {
            bottomNavigationView.setVisibility(VISIBLE);
        } else {
            bottomNavigationView.setVisibility(View.GONE);
        }
        if (xmppConnectionService != null) {
            xmppConnectionService.setOnStoriesUpdateListener(this);
            getPreferences().edit().putLong("last_read_story_timestamp", System.currentTimeMillis()).apply();
            refresh();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (xmppConnectionService != null) {
            xmppConnectionService.removeOnStoriesUpdateListener(this);
        }
    }

    @Override
    protected void onBackendConnected() {
        if (xmppConnectionService != null) {
            xmppConnectionService.setOnStoriesUpdateListener(this);
            refresh();
        }
        refreshUiReal();
    }

    @Override
    public void onBackPressed() {
        if (findViewById(R.id.bottom_navigation).getVisibility() == VISIBLE) {
            Intent intent = new Intent(this, ConversationsActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
            startActivity(intent);
            overridePendingTransition(R.animator.fade_in, R.animator.fade_out);
        }

        super.onBackPressed();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.activity_stories, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_settings) {
            startActivity(new Intent(this, eu.siacs.conversations.ui.activity.SettingsActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, final Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == Activity.RESULT_OK) {
            if (requestCode == REQUEST_CHOOSE_STORY_IMAGE) {
                Uri uri;
                if (data != null && data.getData() != null) {
                    uri = data.getData();
                } else if (pendingTakePhotoUri.peek() != null) {
                    uri = pendingTakePhotoUri.pop();
                } else {
                    return; //No image was selected
                }
                if (mSelectedAccount != null) {
                    Intent intent = new Intent(this, EditActivity.class);
                    intent.setData(uri);
                    intent.putExtra(EditActivity.KEY_CHAT_NAME, mSelectedAccount.getDisplayName());
                    startActivityForResult(intent, REQUEST_EDIT_STORY_IMAGE);
                }
            } else if (requestCode == REQUEST_EDIT_STORY_IMAGE) {
                Uri uri = data != null ? data.getData() : null;
                if (uri != null && mSelectedAccount != null) {
                    String mimeType = data.getType();
                    if (mimeType == null) {
                        mimeType = getContentResolver().getType(uri);
                    }
                    if (mimeType == null) {
                        mimeType = "image/jpeg"; // Fallback for file URIs
                    }
                    final EditText input = new EditText(this);
                    input.setHint(R.string.title_optional);
                    String finalMimeType = mimeType;
                    new MaterialAlertDialogBuilder(this)
                            .setTitle(R.string.add_story_title)
                            .setView(input)
                            .setNegativeButton(R.string.cancel, null)
                            .setPositiveButton(R.string.publish, (dialog, which) -> {
                                final String title = input.getText().toString();
                                Toast.makeText(this, R.string.uploading_story, Toast.LENGTH_SHORT).show();
                                xmppConnectionService.uploadFileForUrl(mSelectedAccount, uri, finalMimeType, new UiCallback<String>() {
                                    @Override
                                    public void success(String url) {
                                        xmppConnectionService.publishStory(mSelectedAccount, url, finalMimeType, title, new UiCallback<Void>() {
                                            @Override
                                            public void success(Void aVoid) {
                                                runOnUiThread(() -> Toast.makeText(StoriesActivity.this, R.string.story_published, Toast.LENGTH_SHORT).show());
                                            }

                                            @Override
                                            public void error(int errorCode, Void object) {
                                                runOnUiThread(() -> Toast.makeText(StoriesActivity.this, errorCode, Toast.LENGTH_SHORT).show());
                                            }

                                            @Override
                                            public void userInputRequired(PendingIntent pi, Void object) {
                                                // not used
                                            }
                                        });
                                    }

                                    @Override
                                    public void error(int errorCode, String object) {
                                        runOnUiThread(() -> Toast.makeText(StoriesActivity.this, errorCode, Toast.LENGTH_SHORT).show());
                                    }

                                    @Override
                                    public void userInputRequired(PendingIntent pi, String object) {
                                        // not used
                                    }
                                });
                            })
                            .create()
                            .show();
                }
            }
        } else {
            pendingTakePhotoUri.pop();
        }
    }


    private void selectAccountToPublishStory() {
        final List<Account> accounts = xmppConnectionService.getAccounts().stream().filter(account -> account.getStatus() == Account.State.ONLINE).collect(Collectors.toList());
        if (accounts.isEmpty()) {
            Toast.makeText(this, R.string.no_active_account, Toast.LENGTH_SHORT).show();
        } else if (accounts.size() == 1) {
            openStoryImagePicker(accounts.get(0));
        } else {
            final AtomicReference<Account> selectedAccount = new AtomicReference<>(accounts.get(0));
            final MaterialAlertDialogBuilder alertDialogBuilder = new MaterialAlertDialogBuilder(this);
            alertDialogBuilder.setTitle(R.string.choose_account);
            final String[] asStrings =
                    accounts.stream().map(a -> a.getJid().asBareJid().toString()).toArray(String[]::new);
            alertDialogBuilder.setSingleChoiceItems(
                    asStrings, 0, (dialog, which) -> selectedAccount.set(accounts.get(which)));
            alertDialogBuilder.setNegativeButton(R.string.cancel, null);
            alertDialogBuilder.setPositiveButton(
                    R.string.ok, (dialog, which) -> openStoryImagePicker(selectedAccount.get()));
            alertDialogBuilder.create().show();
        }
    }

    private void openStoryImagePicker(Account account) {
        this.mSelectedAccount = account;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
        } else {
            final Intent galleryIntent = new Intent(Intent.ACTION_GET_CONTENT);
            galleryIntent.setType("image/*");

            final Intent cameraIntent = new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE);
            final Uri takePhotoUri = xmppConnectionService.getFileBackend().getTakePhotoUri();
            pendingTakePhotoUri.push(takePhotoUri);
            cameraIntent.putExtra(MediaStore.EXTRA_OUTPUT, takePhotoUri);

            final Intent chooserIntent = Intent.createChooser(galleryIntent, getString(R.string.perform_action_with));
            chooserIntent.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{cameraIntent});

            try {
                startActivityForResult(chooserIntent, REQUEST_CHOOSE_STORY_IMAGE);
            } catch (final ActivityNotFoundException e) {
                Toast.makeText(this, R.string.no_application_found, Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                openStoryImagePicker(mSelectedAccount);
            }
        }
    }

    @Override
    protected void refreshUiReal() {
        ActionBar actionBar = getSupportActionBar();

        // Show badge for unread message in bottom nav
        int unreadCount = xmppConnectionService.unreadCount();
        BottomNavigationView bottomnav = findViewById(R.id.bottom_navigation);
        var bottomBadge = bottomnav.getOrCreateBadge(R.id.chats);
        bottomBadge.setNumber(unreadCount);
        bottomBadge.setVisible(unreadCount > 0);
        bottomBadge.setHorizontalOffset(20);

        // Show badge for new stories in bottom nav
        long lastRead = getPreferences().getLong("last_read_story_timestamp", 0);
        boolean hasNewStories = xmppConnectionService.getStories().stream().anyMatch(s -> s.getPublished() > lastRead);
        var storiesBadge = bottomnav.getOrCreateBadge(R.id.stories);
        storiesBadge.setVisible(hasNewStories);

        boolean showNavBar = bottomnav.getVisibility() == VISIBLE;
        if (actionBar != null) {
            actionBar.setHomeButtonEnabled(!showNavBar);
            actionBar.setDisplayHomeAsUpEnabled(!showNavBar);
        }
        refresh();
    }

    private void refresh() {
        if (xmppConnectionService == null) {
            return;
        }
        if (xmppConnectionService.getAccounts().stream().noneMatch(account -> account.getStatus() == Account.State.ONLINE)) {
            binding.storiesList.setVisibility(View.GONE);
            binding.fabAddStory.setVisibility(View.GONE);
            binding.placeholder.setVisibility(View.VISIBLE);
            binding.placeholder.setText(R.string.no_active_account_to_show_stories);
            return;
        }
        binding.placeholder.setVisibility(View.GONE);
        binding.fabAddStory.setVisibility(View.VISIBLE);
        this.stories.clear();
        long twentyFourHoursAgo = System.currentTimeMillis() - 86400000;
        this.stories.addAll(
                this.xmppConnectionService.getStories().stream()
                        .filter(s -> s.getPublished() >= twentyFourHoursAgo)
                        .collect(Collectors.toMap(
                                story -> story.getContact().asBareJid(),
                                story -> story,
                                (a, b) -> a.getPublished() > b.getPublished() ? a : b
                        ))
                        .values()
        );
        Collections.sort(this.stories, (a, b) -> Long.compare(b.getPublished(), a.getPublished()));

        if (this.stories.isEmpty()) {
            binding.storiesList.setVisibility(View.GONE);
        } else {
            binding.storiesList.setVisibility(View.VISIBLE);
        }
        storyAdapter.notifyDataSetChanged();
    }

    @Override
    public void onStoriesUpdate() {
        runOnUiThread(this::refresh);
    }
}
