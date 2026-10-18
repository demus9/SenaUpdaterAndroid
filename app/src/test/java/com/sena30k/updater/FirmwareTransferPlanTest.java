package com.sena30k.updater;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.junit.Test;

public class FirmwareTransferPlanTest {
    @Test
    public void usesReportCountsConfirmedByBothCaptures() {
        assertEquals(1_042, FirmwareTransferPlan.reportCount(1_060_874));
        assertEquals(5_115, FirmwareTransferPlan.reportCount(5_211_712));
    }

    @Test
    public void framesDataWithBigEndianLengthAndZeroPadding() {
        byte[] report = FirmwareTransferPlan.dataReport(new byte[]{1, 2, 3}, 3);

        assertEquals(FirmwareTransferPlan.REPORT_BYTES, report.length);
        assertEquals(0x01, report[0] & 0xff);
        assertEquals(0x06, report[1] & 0xff);
        assertEquals(0, report[2] & 0xff);
        assertEquals(3, report[3] & 0xff);
        assertEquals(1, report[4] & 0xff);
        assertEquals(3, report[6] & 0xff);
        assertEquals(0, report[7] & 0xff);
        assertEquals(0, report[report.length - 1] & 0xff);
    }

    @Test
    public void supportsUnknownVersionsWithValidPackageTables() throws Exception {
        assertTrue(FirmwareTransferPlan.supports(inspect("v4.5-build0")));
        assertTrue(FirmwareTransferPlan.supports(inspect("v4.5.1-build0")));
        assertTrue(FirmwareTransferPlan.supports(inspect("v4.6-build0")));
        assertTrue(FirmwareTransferPlan.isProtocolObservedVersion(inspect("v4.5-build0")));
        assertTrue(FirmwareTransferPlan.isProtocolObservedVersion(inspect("v4.5.1-build0")));
        assertTrue(!FirmwareTransferPlan.isProtocolObservedVersion(inspect("v4.6-build0")));
    }

    @Test
    public void readsRequestedBytesExactly() throws Exception {
        byte[] buffer = new byte[3];
        FirmwareTransferPlan.readExactly(
                new ByteArrayInputStream(new byte[]{4, 5, 6}), buffer, buffer.length);

        assertEquals(4, buffer[0] & 0xff);
        assertEquals(6, buffer[2] & 0xff);
    }

    private FirmwareImageInspector.Result inspect(String version) throws Exception {
        byte[] image = new byte[0x106];
        byte[] versionBytes = version.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(versionBytes, 0, image, 0, versionBytes.length);
        putUInt32(image, 0x20, 3);
        putUInt32(image, 0x2c, 0x100);
        putUInt32(image, 0x30, 3);
        putUInt32(image, 0x4c, 0x103);
        putUInt32(image, 0x50, 2);
        putUInt32(image, 0x6c, 0x105);
        putUInt32(image, 0x70, 1);
        System.arraycopy(new byte[]{1, 2, 3, 4, 5, 6}, 0, image, 0x100, 6);
        return FirmwareImageInspector.inspect(new ByteArrayInputStream(image));
    }

    private void putUInt32(byte[] image, int offset, int value) {
        image[offset] = (byte) value;
        image[offset + 1] = (byte) (value >>> 8);
        image[offset + 2] = (byte) (value >>> 16);
        image[offset + 3] = (byte) (value >>> 24);
    }
}
