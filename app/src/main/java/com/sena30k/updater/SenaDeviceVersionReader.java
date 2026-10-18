package com.sena30k.updater;

import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class SenaDeviceVersionReader {
    private static final int TIMEOUT_MILLIS = 10_000;
    private static final int REPORT_BYTES = FirmwareTransferPlan.REPORT_BYTES;
    private static final byte[] MODEL_NAME_UTF16LE =
            "Sena 30K".getBytes(StandardCharsets.UTF_16LE);

    private SenaDeviceVersionReader() {
    }

    private static final java.util.Set<String> KNOWN_VERSIONS = new java.util.HashSet<>(
            java.util.Arrays.asList("v4.0", "v4.1", "v4.4.1", "v4.5", "v4.5.1"));

    /** Whether the headset's version reply for this firmware version is known to the app. */
    public static boolean isKnownVersion(String versionText) {
        return versionText != null && KNOWN_VERSIONS.contains(versionText);
    }

    public static Result read(
            UsbDeviceConnection connection,
            UsbEndpoint outEndpoint,
            UsbEndpoint inEndpoint) throws IOException {
        sendCommand(connection, outEndpoint, inEndpoint, 0x1a, 0x00, 0x00);
        byte[] modelResponse =
                sendCommand(connection, outEndpoint, inEndpoint, 0x03, 0x1d, 0x00);
        if (!contains(modelResponse, MODEL_NAME_UTF16LE)) {
            throw new IOException("The connected DFU device did not identify as a Sena 30K.");
        }
        byte[] versionResponse =
                sendCommand(connection, outEndpoint, inEndpoint, 0x01, 0x1d, 0x00);
        return parseVersionResponse(versionResponse);
    }

    static Result parseVersionResponse(byte[] response) throws IOException {
        if (response == null || response.length < 8
                || (response[0] & 0xff) != 0x02
                || (response[1] & 0xff) != 0x01
                || (response[2] & 0xff) != 0x00
                || (response[3] & 0xff) != 0x04) {
            throw new IOException("The headset returned an invalid firmware-version response.");
        }
        int value0 = response[4] & 0xff;
        int value1 = response[5] & 0xff;
        int value2 = response[6] & 0xff;
        int value3 = response[7] & 0xff;
        // Only values observed on a headset whose version was confirmed by the official updater.
        String version = null;
        int packed = (value0 << 24) | (value1 << 16) | (value2 << 8) | value3;
        switch (packed) {
            case 0x00000001:
                version = "v4.0";
                break;
            case 0x01000003:
                version = "v4.1";
                break;
            case 0x04020101:
                version = "v4.4.1";
                break;
            case 0x05000000:
                version = "v4.5";
                break;
            case 0x05020100:
                version = "v4.5.1";
                break;
            default:
                break;
        }
        return new Result(version, value0, value1, value2, value3);
    }

    private static byte[] sendCommand(
            UsbDeviceConnection connection,
            UsbEndpoint outEndpoint,
            UsbEndpoint inEndpoint,
            int command,
            int argumentLow,
            int argumentHigh) throws IOException {
        byte[] report = FirmwareTransferPlan.command(command, argumentLow, argumentHigh);
        int written = connection.bulkTransfer(
                outEndpoint, report, 0, report.length, TIMEOUT_MILLIS);
        if (written != report.length) {
            throw new IOException("USB write for version-query command 0x"
                    + Integer.toHexString(command) + " returned " + written
                    + " of " + report.length + " bytes.");
        }

        byte[] response = new byte[REPORT_BYTES];
        int responseLength = connection.bulkTransfer(
                inEndpoint, response, 0, response.length, TIMEOUT_MILLIS);
        if (responseLength < FirmwareTransferPlan.REPORT_HEADER_BYTES) {
            throw new IOException("Timed out or received a short response for version-query "
                    + "command 0x" + Integer.toHexString(command)
                    + " (USB read result " + responseLength + ").");
        }
        if ((response[0] & 0xff) != 0x02 || (response[1] & 0xff) != command) {
            throw new IOException("Unexpected response for version-query command 0x"
                    + Integer.toHexString(command) + ": report ID 0x"
                    + hexByte(response[0]) + ", command 0x" + hexByte(response[1]) + ".");
        }
        byte[] exactResponse = new byte[responseLength];
        System.arraycopy(response, 0, exactResponse, 0, responseLength);
        return exactResponse;
    }

    private static boolean contains(byte[] data, byte[] pattern) {
        for (int start = 0; start <= data.length - pattern.length; start++) {
            boolean matches = true;
            for (int index = 0; index < pattern.length; index++) {
                if (data[start + index] != pattern[index]) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return true;
            }
        }
        return false;
    }

    private static String hexByte(byte value) {
        return String.format(java.util.Locale.ROOT, "%02X", value & 0xff);
    }

    public static final class Result {
        public final String version;
        public final int raw0;
        public final int raw1;
        public final int raw2;
        public final int raw3;

        private Result(String version, int raw0, int raw1, int raw2, int raw3) {
            this.version = version;
            this.raw0 = raw0;
            this.raw1 = raw1;
            this.raw2 = raw2;
            this.raw3 = raw3;
        }

        public String rawValue() {
            return String.format(
                    java.util.Locale.ROOT, "%02X %02X %02X %02X",
                    raw0, raw1, raw2, raw3);
        }
    }
}
