package eu.siacs.conversations.ui.util;

import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.lang.ref.WeakReference;

import eu.siacs.conversations.AppSettings;
import eu.siacs.conversations.R;
import eu.siacs.conversations.crypto.OmemoSetting;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.ui.XmppActivity;

/**
 * One-time choice shown after updating to (or first installing) the post-quantum
 * build. Legacy OMEMO stays available either way — it is enabled by default and
 * selectable per chat — so nobody becomes unreachable for contacts on other or
 * older XMPP apps. What the user picks here is only which of the two stacks
 * chats use *by default*, which is what makes a slow PQ OMEMO2 rollout possible.
 * Chats the user has explicitly switched from the encryption menu keep their own
 * setting; the default can be changed any time under Settings -> Security.
 *
 * <p>It has to be offered by every screen the user can reach first, not just the
 * conversation overview: after a fresh sign-up the app goes straight to
 * {@link eu.siacs.conversations.ui.StartConversationActivity} (an empty
 * conversation list redirects there), where chats can be started — and messages
 * sent — long before the overview is ever seen. Asking only there meant new
 * users silently got the PQ OMEMO2 default and could not reach contacts on older
 * versions.
 */
public final class OmemoDefaultStackNotice {

    private static final String CHOSEN = "omemo_default_stack_chosen";

    /**
     * The dialog currently on screen and the activity showing it, so an activity
     * that is restarted before the user answers does not stack a second copy. A
     * *different* activity instance (after a rotation, say) does get a new one:
     * the old dialog's window died with its activity even though it still claims
     * to be showing, and the choice has to stay reachable.
     */
    private static WeakReference<AlertDialog> current = new WeakReference<>(null);

    private static WeakReference<XmppActivity> currentOwner = new WeakReference<>(null);

    private OmemoDefaultStackNotice() {}

    /**
     * Shows the choice if it has not been made yet, and reports whether a dialog
     * is now on screen (callers use that to hold back further first-run dialogs).
     */
    public static boolean showIfNeeded(@NonNull final XmppActivity activity) {
        final AlertDialog showing = current.get();
        if (showing != null && showing.isShowing() && currentOwner.get() == activity) {
            return true;
        }
        final var service = activity.xmppConnectionService;
        if (service == null || service.isOnboarding() || service.getAccounts().isEmpty()) {
            // Nothing to publish keys for yet, and account setup owns the screen.
            return false;
        }
        final SharedPreferences preferences =
                PreferenceManager.getDefaultSharedPreferences(activity.getApplicationContext());
        if (preferences.getBoolean(CHOSEN, false)) {
            return false;
        }
        if (preferences.contains(AppSettings.LEGACY_OMEMO_ENABLED)
                && !preferences.getBoolean(AppSettings.LEGACY_OMEMO_ENABLED, true)) {
            // This user went into Settings and switched legacy OMEMO off on
            // purpose. There is no default stack left to choose, and silently
            // handing them legacy back would undo a deliberate hardening
            // decision. Record the (only possible) answer and stay quiet.
            preferences.edit().putBoolean(CHOSEN, true).apply();
            return false;
        }
        final MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(activity);
        builder.setTitle(R.string.pq_omemo2_notice_title);
        builder.setMessage(
                activity.getString(
                        R.string.pq_omemo2_notice_message, activity.getString(R.string.app_name)));
        builder.setPositiveButton(
                R.string.default_to_post_quantum_omemo, (dialog, which) -> apply(activity, false));
        builder.setNegativeButton(
                R.string.default_to_legacy_omemo, (dialog, which) -> apply(activity, true));
        final AlertDialog dialog = builder.create();
        // A default has to be picked; back/outside must not silently answer it.
        dialog.setCancelable(false);
        dialog.setCanceledOnTouchOutside(false);
        dialog.show();
        current = new WeakReference<>(dialog);
        currentOwner = new WeakReference<>(activity);
        return true;
    }

    /**
     * Applies the choice: records it, makes sure legacy OMEMO is available (it is
     * the default, but be explicit — the choice is meaningless without it), and
     * publishes the legacy bundle right away so peers on older clients can reach
     * this device without waiting for the next reconnect.
     */
    private static void apply(final XmppActivity activity, final boolean legacy) {
        PreferenceManager.getDefaultSharedPreferences(activity.getApplicationContext())
                .edit()
                .putBoolean(AppSettings.LEGACY_OMEMO_ENABLED, true)
                .putBoolean(AppSettings.OMEMO_DEFAULT_LEGACY, legacy)
                .putBoolean(CHOSEN, true)
                .apply();
        OmemoSetting.load(activity);
        final var service = activity.xmppConnectionService;
        if (service == null) {
            return;
        }
        for (final Account account : service.getAccounts()) {
            final var axolotlService = account.getAxolotlService();
            if (axolotlService != null) {
                axolotlService.publishLegacyBundleNow();
            }
        }
    }
}
