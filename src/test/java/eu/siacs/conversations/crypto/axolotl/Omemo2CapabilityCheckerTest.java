package eu.siacs.conversations.crypto.axolotl;

import static org.junit.Assert.assertEquals;

import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class Omemo2CapabilityCheckerTest {

    @Test
    public void emptyDeviceListMapsToUnsupported() {
        assertEquals(
                Omemo2CapabilityChecker.CapabilityResult.UNSUPPORTED,
                Omemo2CapabilityChecker.resultForDeviceIds(Collections.emptyList()));
    }

    @Test
    public void nonEmptyDeviceListMapsToSupported() {
        assertEquals(
                Omemo2CapabilityChecker.CapabilityResult.SUPPORTED,
                Omemo2CapabilityChecker.resultForDeviceIds(List.of(1, 2)));
    }

    @Test
    public void nullDeviceListMapsToCheckFailed() {
        assertEquals(
                Omemo2CapabilityChecker.CapabilityResult.CHECK_FAILED,
                Omemo2CapabilityChecker.resultForDeviceIds(null));
    }
}
