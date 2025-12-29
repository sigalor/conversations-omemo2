package eu.siacs.conversations.ui;

import android.os.Bundle;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.core.util.Consumer;

import com.bumptech.glide.Glide;

import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.DownloadableFile;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.http.HttpConnectionManager;

public class StoryViewActivity extends XmppActivity {

    public static final String EXTRA_URL = "url";
    public static final String EXTRA_ACCOUNT = "account";

    private ImageView imageView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_story_view);
        imageView = findViewById(R.id.story_image_view);
    }

    @Override
    protected void refreshUiReal() {

    }

    @Override
    public void onBackendConnected() {
        String url = getIntent().getStringExtra(EXTRA_URL);
        String accountUuid = getIntent().getStringExtra(EXTRA_ACCOUNT);
        if (url != null && accountUuid != null) {
            Account account = xmppConnectionService.findAccountByUuid(accountUuid);
            if (account != null) {
                // Create a transient conversation and message to wrap the download request
                Conversation conversation = new Conversation(account.getDisplayName(), account, account.getJid().asBareJid(), Conversation.MODE_SINGLE);
                final Message message = new Message(conversation, "", Message.ENCRYPTION_NONE);
                message.getFileParams().url = url;

                HttpConnectionManager manager = this.xmppConnectionService.getHttpConnectionManager();
                manager.createNewDownloadConnection(message, false, new Consumer<DownloadableFile>() {
                    @Override
                    public void accept(final DownloadableFile file) {
                        runOnUiThread(() -> {
                            if (file != null && !isFinishing()) {
                                Glide.with(StoryViewActivity.this).load(file).into(imageView);
                            } else if (!isFinishing()) {
                                Toast.makeText(StoryViewActivity.this, R.string.download_failed_file_not_found, Toast.LENGTH_SHORT).show();
                                finish();
                            }
                        });
                    }
                });
            } else {
                Toast.makeText(this, R.string.no_active_account, Toast.LENGTH_SHORT).show();
                finish();
            }
        } else {
            finish();
        }
    }
}