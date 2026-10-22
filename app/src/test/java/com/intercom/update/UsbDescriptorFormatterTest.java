package com.intercom.update;

import static org.junit.Assert.assertTrue;

import android.hardware.usb.UsbConstants;

import org.junit.Test;

public class UsbDescriptorFormatterTest {
    @Test
    public void identifiesStandardDfuModeInterfaceDescriptors() {
        String description = UsbDescriptorFormatter.describeInterface(
                2, 0, 0xfe, 0x01, 0x02);

        assertTrue(description.contains("class FE (Application specific)"));
        assertTrue(description.contains("subclass 01 (DFU)"));
        assertTrue(description.contains("protocol 02 (DFU mode)"));
    }

    @Test
    public void identifiesDfuRuntimeSeparatelyFromDfuMode() {
        String description = UsbDescriptorFormatter.describeInterface(
                0, 0, 0xfe, 0x01, 0x01);

        assertTrue(description.contains("protocol 01 (DFU runtime)"));
        assertTrue(!description.contains("protocol 01 (DFU mode)"));
    }

    @Test
    public void formatsEndpointWithoutPerformingTransfers() {
        String description = UsbDescriptorFormatter.describeEndpoint(
                0x81,
                UsbConstants.USB_DIR_IN,
                UsbConstants.USB_ENDPOINT_XFER_BULK,
                64);

        assertTrue(description.contains("Endpoint 81 IN bulk"));
        assertTrue(description.contains("max packet 64"));
    }
}
