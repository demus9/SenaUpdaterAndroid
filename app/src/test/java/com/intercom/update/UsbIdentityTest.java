package com.intercom.update;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class UsbIdentityTest {
    @Test
    public void labelsPoweredOnAndOffObservationWithoutClaimingMode() {
        String description = UsbIdentity.descriptionFor(0x092b, 0x3220);

        assertTrue(description.contains("powered on and off"));
        assertTrue(description.contains("mode is not established"));
    }

    @Test
    public void labelsPoweredOffObservationWithoutClaimingDfu() {
        String description = UsbIdentity.descriptionFor(0x092b, 0x3211);

        assertTrue(description.contains("while powered off"));
        assertTrue(description.contains("does not confirm DFU or bootloader mode"));
    }

    @Test
    public void keepsDriverListedIdDistinctFromObservedIds() {
        String description = UsbIdentity.descriptionFor(0x092b, 0x3111);

        assertTrue(description.contains("installed Windows driver"));
        assertTrue(description.contains("not observed on your headset"));
    }

    @Test
    public void rejectsUnrelatedVendorIds() {
        assertTrue(UsbIdentity.descriptionFor(0x1234, 0x3220)
                .contains("Not a Sena USB vendor ID"));
    }
}
