package eu.siacs.conversations.crypto.axolotl;

import eu.siacs.conversations.xmpp.Jid;
import java.util.List;
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
}
