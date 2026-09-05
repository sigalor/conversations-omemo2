package eu.siacs.conversations.crypto.axolotl;

import eu.siacs.conversations.xmpp.Jid;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Whether a 1:1 peer supports PQ-OMEMO2, checked proactively (at conversation-open time)
 * rather than only reactively (a send attempt failing). Reuses the existing PEP device-list
 * fetch mechanism in {@link AxolotlService} -- an empty result is the app's own established
 * convention for "peer doesn't support PQ-OMEMO2" (see AxolotlService.fetchOmemo2DeviceIds's
 * own "fail closed" comment); this class only changes WHEN that check runs and WHAT the UI
 * does with a negative result, not how support is detected.
 */
public class Omemo2CapabilityChecker {

    public enum CapabilityResult {
        SUPPORTED,
        UNSUPPORTED,
        CHECK_FAILED
    }

    static CapabilityResult resultForDeviceIds(final List<Integer> deviceIds) {
        if (deviceIds == null) {
            return CapabilityResult.CHECK_FAILED;
        }
        return deviceIds.isEmpty() ? CapabilityResult.UNSUPPORTED : CapabilityResult.SUPPORTED;
    }

    /**
     * Checks whether {@code peer} (a 1:1 contact) supports PQ-OMEMO2.
     *
     * <p>{@link AxolotlService#fetchOmemo2DeviceIds(List, AxolotlService.OnMultipleDeviceIdFetched)}
     * -- the method this class was originally designed against per the design research -- is
     * {@code private} and its completion callback ({@code OnMultipleDeviceIdFetched.fetched()})
     * carries no per-JID result data; per-peer outcomes only live in {@code AxolotlService}'s own
     * internal device-id map. Rather than widen that method's visibility or change its shape (used
     * by several other call sites), {@link AxolotlService} exposes a small single-peer public
     * overload -- {@code fetchOmemo2DeviceIds(Jid, Consumer)} -- built for this checker, which
     * drives the same private fetch and then reads back the resulting device list for just that
     * JID. See that method's own javadoc for exactly how a null vs. empty vs. non-empty list is
     * derived.
     */
    public static void checkOneToOne(
            final AxolotlService axolotlService,
            final Jid peer,
            final Consumer<CapabilityResult> callback) {
        axolotlService.fetchOmemo2DeviceIds(
                peer, deviceIds -> callback.accept(resultForDeviceIds(deviceIds)));
    }

    /**
     * All-or-nothing aggregation of a MUC's per-occupant results: any single {@code UNSUPPORTED}
     * occupant blocks the whole room (no partial/selective encryption is possible in a shared
     * room -- a message sent to the room goes to every occupant, so if even one of them can't
     * receive a PQ-OMEMO2-encrypted message, the room as a whole can't be gated open).
     * {@code UNSUPPORTED} wins over {@code CHECK_FAILED} (a confirmed negative is a stronger,
     * more specific verdict than "still don't know"), which in turn wins over {@code SUPPORTED}
     * (every occupant must be confirmed supported, not just none confirmed unsupported).
     */
    public static CapabilityResult aggregateMucResults(final List<CapabilityResult> results) {
        if (results.contains(CapabilityResult.UNSUPPORTED)) {
            return CapabilityResult.UNSUPPORTED; // all-or-nothing: any one occupant blocks the room
        }
        if (results.contains(CapabilityResult.CHECK_FAILED)) {
            return CapabilityResult.CHECK_FAILED;
        }
        return CapabilityResult.SUPPORTED;
    }

    /**
     * Checks whether every occupant in {@code occupants} (typically {@code
     * AxolotlService#getCryptoTargets(Conversation)} for a MUC -- i.e. {@code
     * MucOptions#getMembers(false)}, the same real-JID list already used to decide who this
     * room's OMEMO2 sessions are built for) supports PQ-OMEMO2, and aggregates the result via
     * {@link #aggregateMucResults(List)}.
     *
     * <p>Drives {@link AxolotlService#fetchOmemo2DeviceIds(List, Consumer)} -- itself built on
     * top of the same private, multi-JID batch fetch {@link #checkOneToOne} ultimately reduces
     * to -- with the *entire* occupant list in one call, so the whole room is checked with one
     * batch of IQs in flight together rather than {@code occupants.size()} independent
     * round-trips. An empty occupant list is vacuously {@code SUPPORTED}: there is nothing that
     * could block the room.
     */
    public static void checkMuc(
            final AxolotlService axolotlService,
            final List<Jid> occupants,
            final Consumer<CapabilityResult> callback) {
        if (occupants.isEmpty()) {
            callback.accept(CapabilityResult.SUPPORTED);
            return;
        }
        axolotlService.fetchOmemo2DeviceIds(
                occupants,
                (Map<Jid, List<Integer>> deviceIdsByOccupant) -> {
                    final List<CapabilityResult> results = new ArrayList<>(occupants.size());
                    for (final Jid occupant : occupants) {
                        results.add(resultForDeviceIds(deviceIdsByOccupant.get(occupant)));
                    }
                    callback.accept(aggregateMucResults(results));
                });
    }
}
