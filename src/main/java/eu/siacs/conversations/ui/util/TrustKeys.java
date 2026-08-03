package eu.siacs.conversations.ui.util;

import android.content.Intent;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

import eu.siacs.conversations.R;
import eu.siacs.conversations.crypto.axolotl.AxolotlService;
import eu.siacs.conversations.crypto.axolotl.FingerprintStatus;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.ui.TrustKeysActivity;
import eu.siacs.conversations.ui.XmppActivity;
import eu.siacs.conversations.xmpp.Jid;

/**
 * Decides whether a conversation still needs keys decided before anything may be
 * encrypted to it, and builds the {@link TrustKeysActivity} intent for it.
 *
 * <p>Every path that encrypts a message has to run this gate, not just typing in
 * a chat: sharing from another app and resending a failed message reach the
 * encryption layer just as directly, and skipping the gate there left the user
 * with a message that could not be sent and no way to fix it. The gate lives
 * here rather than in a single screen so the three callers cannot drift apart.
 *
 * <p>The caller launches the returned intent itself (with
 * {@code startActivityForResult}), so the result lands in whichever
 * fragment/activity is driving the send.
 */
public final class TrustKeys {

    private TrustKeys() {}

    /**
     * The trust screen to open before sending to {@code conversation}, or null
     * when nothing needs deciding (either everything is trusted, or sending is
     * impossible for a reason the user was just told about via a toast).
     */
    @Nullable
    public static Intent intentFor(
            @NonNull final XmppActivity activity, @NonNull final Conversation conversation) {
        return intentFor(activity, conversation, conversation.getNextEncryption());
    }

    /**
     * Same, for an explicit encryption — used when resending a message, which
     * goes out with the encryption it was composed with rather than whatever
     * the chat is set to now.
     */
    @Nullable
    public static Intent intentFor(
            @NonNull final XmppActivity activity,
            @NonNull final Conversation conversation,
            final int encryption) {
        if (encryption == Message.ENCRYPTION_AXOLOTL_OMEMO2) {
            return omemo2IntentFor(activity, conversation);
        } else if (encryption == Message.ENCRYPTION_AXOLOTL) {
            return legacyIntentFor(activity, conversation);
        }
        return null;
    }

    /**
     * Like {@link AxolotlService#anyTargetHasNoTrustedKeys}, but skips keyless group chat
     * members the user has explicitly confirmed to send without (via the trust screen). The
     * exclusion is self-healing: newly published keys show up as undecided contacts, which
     * re-opens the trust screen independently of this check. Consent is also single-cycle:
     * once an excluded member has trusted keys again, the stored exclusion is dropped here,
     * so if their keys ever vanish a second time the trust screen prompts afresh instead of
     * the old consent silently re-applying.
     */
    private static boolean anyTargetHasNoTrustedKeys(
            final XmppActivity activity,
            final Conversation conversation,
            final AxolotlService axolotlService,
            final List<Jid> targets,
            final int encryption) {
        final List<Jid> excludedKeyless = conversation.getKeylessExcludedCryptoTargets();
        boolean prunedExclusions = false;
        boolean anyTargetWithout = false;
        for (final Jid jid : targets) {
            if (axolotlService.getNumTrustedKeys(jid, encryption) > 0) {
                if (excludedKeyless.remove(jid)) {
                    prunedExclusions = true;
                }
            } else if (!excludedKeyless.contains(jid)) {
                anyTargetWithout = true;
            }
        }
        if (prunedExclusions) {
            conversation.setKeylessExcludedCryptoTargets(excludedKeyless);
            activity.xmppConnectionService.updateConversation(conversation);
        }
        return anyTargetWithout;
    }

    /**
     * A conversation with our own JID while the given stack has no keys for it at all —
     * i.e. note to self on the only device. Sending is safe: nothing exists to encrypt to
     * (or to leak to), the envelope goes out without any recipient key and the note lives
     * in local storage. If another own device appears, its keys make this false and the
     * regular trust gate takes over.
     */
    private static boolean isSingleDeviceNoteToSelf(
            final Conversation conversation,
            final AxolotlService axolotlService,
            final int encryption) {
        return conversation.getMode() == Conversation.MODE_SINGLE
                && conversation.getContact().isSelf()
                && axolotlService
                        .getFingerprintsForStack(conversation.getJid().asBareJid(), encryption)
                        .isEmpty();
    }

    /**
     * True when opening the trust screen could not possibly help: we are not
     * connected and this stack knows no keys at all for the targets, so nothing
     * can be fetched and there is nothing to decide. The screen would show a
     * permanent "Fetching keys…" (a request written to an unbound stream is
     * dropped) or the generic error card, and would come back on every single
     * send attempt. Reporting the real reason once is more honest.
     */
    private static boolean cannotFetchKeysNow(
            final Conversation conversation,
            final AxolotlService axolotlService,
            final List<Jid> targets,
            final int encryption) {
        if (conversation.getAccount().isOnlineAndConnected()) {
            return false;
        }
        for (final Jid jid : targets) {
            if (!axolotlService.getFingerprintsForStack(jid, encryption).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    @Nullable
    private static Intent omemo2IntentFor(
            final XmppActivity activity, final Conversation conversation) {
        final AxolotlService axolotlService = conversation.getAccount().getAxolotlService();
        if (axolotlService == null) return null;
        final List<Jid> targets = axolotlService.getCryptoTargets(conversation);
        final boolean hasUnaccepted = !conversation.getAcceptedCryptoTargets().containsAll(targets);
        final boolean hasUndecidedOwn = !axolotlService.getKeysWithTrust(FingerprintStatus.createActiveUndecided(), Message.ENCRYPTION_AXOLOTL_OMEMO2).isEmpty();
        final boolean hasUndecidedContacts = !axolotlService.getKeysWithTrust(FingerprintStatus.createActiveUndecided(), targets, Message.ENCRYPTION_AXOLOTL_OMEMO2).isEmpty();
        // Note to self with no other own devices: no keys exist for this stack at
        // all, so there is nothing to encrypt to — the encryption layer explicitly
        // accepts the empty self case (buildOmemo2Header) and the note is stored
        // locally; the wire envelope carries no readable key for anyone. Blocking
        // on "no trusted keys" made single-device note-to-self unusable.
        // Deliberately narrow: as soon as ANY key exists for this stack (another
        // own device, trusted or not), the normal gate applies unchanged.
        final boolean singleDeviceNoteToSelf =
                isSingleDeviceNoteToSelf(conversation, axolotlService, Message.ENCRYPTION_AXOLOTL_OMEMO2);
        final boolean hasNoTrustedKeys = !singleDeviceNoteToSelf
                && anyTargetHasNoTrustedKeys(activity, conversation, axolotlService, targets, Message.ENCRYPTION_AXOLOTL_OMEMO2);
        final boolean downloadInProgress =
                axolotlService.hasPendingKeyFetches(targets, Message.ENCRYPTION_AXOLOTL_OMEMO2);
        // 1:1 only: sending would fail anyway, so a toast is honest. In a group chat the
        // trust screen opens instead, where the user can explicitly choose to send without
        // the keyless member (instead of silently excluding them).
        if (hasNoTrustedKeys
                && !downloadInProgress
                && !hasUndecidedOwn
                && !hasUndecidedContacts
                && conversation.getMode() == Conversation.MODE_SINGLE
                && (axolotlService.hasErrorFetchingDeviceList(targets, Message.ENCRYPTION_AXOLOTL_OMEMO2)
                    || axolotlService.fetchMapHasErrors(targets, Message.ENCRYPTION_AXOLOTL_OMEMO2))) {
            Toast.makeText(activity, R.string.no_pq_omemo2_keys_for_contact, Toast.LENGTH_LONG).show();
            return null;
        }
        if (!singleDeviceNoteToSelf
                && !hasUndecidedOwn
                && !hasUndecidedContacts
                && conversation.getMode() == Conversation.MODE_SINGLE
                && cannotFetchKeysNow(conversation, axolotlService, targets, Message.ENCRYPTION_AXOLOTL_OMEMO2)) {
            Toast.makeText(activity, R.string.omemo_keys_unavailable_offline, Toast.LENGTH_LONG).show();
            return null;
        }
        axolotlService.createOmemo2SessionsIfNeeded(conversation);
        if (hasUndecidedOwn || hasUndecidedContacts || hasNoTrustedKeys || hasUnaccepted) {
            return trustKeysActivityIntent(
                    activity, conversation, targets, Message.ENCRYPTION_AXOLOTL_OMEMO2);
        }
        return null;
    }

    @Nullable
    private static Intent legacyIntentFor(
            final XmppActivity activity, final Conversation conversation) {
        final AxolotlService axolotlService = conversation.getAccount().getAxolotlService();
        if (axolotlService == null) return null;
        final List<Jid> targets = axolotlService.getCryptoTargets(conversation);
        boolean hasUnaccepted = !conversation.getAcceptedCryptoTargets().containsAll(targets);
        boolean hasUndecidedOwn =
                !axolotlService
                        .getKeysWithTrust(FingerprintStatus.createActiveUndecided(), Message.ENCRYPTION_AXOLOTL)
                        .isEmpty();
        boolean hasUndecidedContacts =
                !axolotlService
                        .getKeysWithTrust(FingerprintStatus.createActiveUndecided(), targets, Message.ENCRYPTION_AXOLOTL)
                        .isEmpty();
        boolean hasPendingKeys = !axolotlService.findDevicesWithoutSession(conversation).isEmpty();
        // Same single-device note-to-self exception as in omemo2IntentFor;
        // narrow on purpose (only when this stack has no keys for our JID at all),
        // because addOwnLegacyDevices does not re-check per-device trust.
        final boolean singleDeviceNoteToSelf =
                isSingleDeviceNoteToSelf(conversation, axolotlService, Message.ENCRYPTION_AXOLOTL);
        boolean hasNoTrustedKeys = !singleDeviceNoteToSelf
                && anyTargetHasNoTrustedKeys(activity, conversation, axolotlService, targets, Message.ENCRYPTION_AXOLOTL);
        boolean downloadInProgress =
                axolotlService.hasPendingKeyFetches(targets, Message.ENCRYPTION_AXOLOTL);
        // 1:1 only: sending would fail anyway, so a toast is honest. In a group chat the
        // trust screen opens instead, where the user can explicitly choose to send without
        // the keyless member (instead of silently excluding them).
        if (hasNoTrustedKeys
                && !downloadInProgress
                && !hasUndecidedOwn
                && !hasUndecidedContacts
                && conversation.getMode() == Conversation.MODE_SINGLE
                && (axolotlService.hasErrorFetchingDeviceList(targets, Message.ENCRYPTION_AXOLOTL)
                    || axolotlService.fetchMapHasErrors(targets, Message.ENCRYPTION_AXOLOTL))) {
            Toast.makeText(activity, R.string.no_omemo_keys_for_contact, Toast.LENGTH_LONG).show();
            return null;
        }
        if (!singleDeviceNoteToSelf
                && !hasUndecidedOwn
                && !hasUndecidedContacts
                && conversation.getMode() == Conversation.MODE_SINGLE
                && cannotFetchKeysNow(conversation, axolotlService, targets, Message.ENCRYPTION_AXOLOTL)) {
            Toast.makeText(activity, R.string.omemo_keys_unavailable_offline, Toast.LENGTH_LONG).show();
            return null;
        }
        if (hasUndecidedOwn
                || hasUndecidedContacts
                || hasPendingKeys
                || hasNoTrustedKeys
                || hasUnaccepted
                || downloadInProgress) {
            axolotlService.createSessionsIfNeeded(conversation);
            return trustKeysActivityIntent(
                    activity, conversation, targets, Message.ENCRYPTION_AXOLOTL);
        }
        return null;
    }

    private static Intent trustKeysActivityIntent(
            final XmppActivity activity,
            final Conversation conversation,
            final List<Jid> targets,
            final int encryption) {
        final Intent intent = new Intent(activity, TrustKeysActivity.class);
        final String[] contacts = new String[targets.size()];
        for (int i = 0; i < contacts.length; ++i) {
            contacts[i] = targets.get(i).toString();
        }
        intent.putExtra("contacts", contacts);
        intent.putExtra(
                XmppActivity.EXTRA_ACCOUNT,
                conversation.getAccount().getJid().asBareJid().toString());
        intent.putExtra("conversation", conversation.getUuid());
        intent.putExtra("encryption", encryption);
        return intent;
    }
}
