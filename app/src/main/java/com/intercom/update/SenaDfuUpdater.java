package com.intercom.update;

import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public final class SenaDfuUpdater {
    private static final int DATA_ACK_TIMEOUT_MILLIS = 8_000;
    private static final int COMMAND_ACK_TIMEOUT_MILLIS = 15_000;
    private static final int ACK_READ_RETRIES = 2;
    private static final int BLOCK_TRANSITION_PAUSE_MILLIS = 350;
    private static final int RESPONSE_BUFFER_BYTES = 1_023;
    private static final byte[] MODEL_NAME_UTF16LE =
            "Sena 30K".getBytes(StandardCharsets.UTF_16LE);

    private SenaDfuUpdater() {
    }

    public static void transfer(
            UsbDeviceConnection connection,
            UsbEndpoint outEndpoint,
            UsbEndpoint inEndpoint,
            File stagedImage,
            String expectedSha256,
            ProgressListener progressListener) throws IOException {
        FirmwareImageInspector.Result image;
        try (InputStream input = new FileInputStream(stagedImage)) {
            image = FirmwareImageInspector.inspect(input);
        }
        if (!FirmwareTransferPlan.supports(image)) {
            throw new IOException("The image does not have a valid three-component 30K layout.");
        }
        if (!image.sha256.equals(expectedSha256)) {
            throw new IOException("The staged firmware image changed after inspection.");
        }

        DfuImagePackage.Component first = image.dfuPackage.components.get(0);
        DfuImagePackage.Component second = image.dfuPackage.components.get(1);
        int firstReports = FirmwareTransferPlan.reportCount(first.size);
        int secondReports = FirmwareTransferPlan.reportCount(second.size);
        int totalReports = firstReports + secondReports;

        identifyHeadset(connection, outEndpoint, inEndpoint);

        long bytesSent = 0;
        int reportsSent = 0;
        expectCommand(connection, outEndpoint, inEndpoint, 0x05, 0x00, 0x00);
        try (InputStream input = new FileInputStream(stagedImage)) {
            FirmwareTransferPlan.skipExactly(input, first.offset);
            bytesSent += sendBlock(
                    connection, outEndpoint, inEndpoint, input, first.size,
                    bytesSent, reportsSent, totalReports, "1", progressListener);
        }
        reportsSent += firstReports;

        expectCommand(connection, outEndpoint, inEndpoint, 0x07, 0x00, 0x00);
        pauseForBlockTransition();
        expectCommand(connection, outEndpoint, inEndpoint, 0x05, 0x01, 0x00);
        try (InputStream input = new FileInputStream(stagedImage)) {
            FirmwareTransferPlan.skipExactly(input, second.offset);
            bytesSent += sendBlock(
                    connection, outEndpoint, inEndpoint, input, second.size,
                    bytesSent, reportsSent, totalReports, "2", progressListener);
        }
        reportsSent += secondReports;

        expectCommand(connection, outEndpoint, inEndpoint, 0x07, 0x00, 0x00);
        expectCommand(connection, outEndpoint, inEndpoint, 0x08, 0x00, 0x00);
        progressListener.onProgress(
                bytesSent, reportsSent, totalReports, "Finalization acknowledged.");
    }

    private static void identifyHeadset(
            UsbDeviceConnection connection,
            UsbEndpoint outEndpoint,
            UsbEndpoint inEndpoint) throws IOException {
        expectCommand(connection, outEndpoint, inEndpoint, 0x1a, 0x00, 0x00);
        byte[] identity = sendCommand(connection, outEndpoint, inEndpoint, 0x03, 0x1d, 0x00);
        if (!contains(identity, MODEL_NAME_UTF16LE)) {
            throw new IOException("The connected DFU device did not identify as a Sena 30K.");
        }
        expectCommand(connection, outEndpoint, inEndpoint, 0x01, 0x1d, 0x00);
        expectCommand(connection, outEndpoint, inEndpoint, 0x50, 0x1d, 0x00);
        expectCommand(connection, outEndpoint, inEndpoint, 0x02, 0x00, 0x00);
        expectCommand(connection, outEndpoint, inEndpoint, 0x03, 0x04, 0x00);
        expectCommand(connection, outEndpoint, inEndpoint, 0x03, 0x15, 0x00);
        expectCommand(connection, outEndpoint, inEndpoint, 0x03, 0x5a, 0x00);
        expectCommand(connection, outEndpoint, inEndpoint, 0x03, 0x5b, 0x00);
        expectCommand(connection, outEndpoint, inEndpoint, 0x03, 0x5c, 0x00);
    }

    private static long sendBlock(
            UsbDeviceConnection connection,
            UsbEndpoint outEndpoint,
            UsbEndpoint inEndpoint,
            InputStream input,
            int blockBytes,
            long bytesAlreadySent,
            int reportsAlreadySent,
            int totalReports,
            String componentNumber,
            ProgressListener progressListener) throws IOException {
        byte[] payload = new byte[FirmwareTransferPlan.MAX_DATA_BYTES_PER_REPORT];
        long blockBytesSent = 0;
        int blockReport = 0;
        while (blockBytesSent < blockBytes) {
            int length = FirmwareTransferPlan.nextChunkLength(blockBytes - blockBytesSent);
            FirmwareTransferPlan.readExactly(input, payload, length);
            sendReportAndExpectCommand(
                    connection,
                    outEndpoint,
                    inEndpoint,
                    FirmwareTransferPlan.dataReport(payload, length),
                    0x06,
                    reportsAlreadySent + blockReport + 1);
            blockBytesSent += length;
            blockReport++;

            int reportsSent = reportsAlreadySent + blockReport;
            if (reportsSent % 32 == 0 || blockBytesSent == blockBytes) {
                progressListener.onProgress(
                        bytesAlreadySent + blockBytesSent,
                        reportsSent,
                        totalReports,
                        "Transferring component " + componentNumber + "…");
            }
        }
        return blockBytesSent;
    }

    private static void expectCommand(
            UsbDeviceConnection connection,
            UsbEndpoint outEndpoint,
            UsbEndpoint inEndpoint,
            int command,
            int argumentLow,
            int argumentHigh) throws IOException {
        sendCommand(connection, outEndpoint, inEndpoint, command, argumentLow, argumentHigh);
    }

    private static byte[] sendCommand(
            UsbDeviceConnection connection,
            UsbEndpoint outEndpoint,
            UsbEndpoint inEndpoint,
            int command,
            int argumentLow,
            int argumentHigh) throws IOException {
        return sendReportAndExpectCommand(
                connection,
                outEndpoint,
                inEndpoint,
                FirmwareTransferPlan.command(command, argumentLow, argumentHigh),
                command,
                0);
    }

    private static byte[] sendReportAndExpectCommand(
            UsbDeviceConnection connection,
            UsbEndpoint outEndpoint,
            UsbEndpoint inEndpoint,
            byte[] report,
            int expectedCommand,
            int reportNumber) throws IOException {
        int written = connection.bulkTransfer(
                outEndpoint, report, 0, report.length, COMMAND_ACK_TIMEOUT_MILLIS);
        if (written != report.length) {
            throw new IOException("USB write for DFU command 0x"
                    + hexByte(expectedCommand) + " returned " + written + " of "
                    + report.length + " bytes.");
        }

        int timeout = reportNumber == 0
                ? COMMAND_ACK_TIMEOUT_MILLIS : DATA_ACK_TIMEOUT_MILLIS;
        byte[] response = new byte[RESPONSE_BUFFER_BYTES];
        int responseLength = -1;
        int readAttempts = 0;
        for (int attempt = 0; attempt <= ACK_READ_RETRIES; attempt++) {
            readAttempts++;
            responseLength = connection.bulkTransfer(
                    inEndpoint, response, 0, response.length, timeout);
            if (responseLength >= 0) {
                break;
            }
        }
        if (responseLength < 0) {
            String detail = reportNumber == 0
                    ? "command 0x" + hexByte(expectedCommand)
                    : "firmware report " + reportNumber + " (command 0x"
                            + hexByte(expectedCommand) + ")";
            throw new IOException("Timed out waiting for headset acknowledgement for " + detail
                    + " after " + readAttempts + " read attempts of " + timeout
                    + " ms each. The command/report was not retransmitted.");
        }
        if (responseLength < FirmwareTransferPlan.REPORT_HEADER_BYTES) {
            throw new IOException("Short headset acknowledgement for command 0x"
                    + hexByte(expectedCommand) + ": " + responseLength
                    + " bytes after " + readAttempts + " read attempt(s).");
        }
        if ((response[0] & 0xff) != 0x02
                || (response[1] & 0xff) != expectedCommand) {
            throw new IOException("Invalid headset acknowledgement for command 0x"
                    + hexByte(expectedCommand) + ": received report ID 0x"
                    + hexByte(response[0]) + ", command 0x" + hexByte(response[1])
                    + ", length " + responseLength + " bytes.");
        }
        return response;
    }

    private static void pauseForBlockTransition() throws IOException {
        try {
            Thread.sleep(BLOCK_TRANSITION_PAUSE_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Transfer was interrupted between firmware components.", e);
        }
    }

    private static String hexByte(int value) {
        return String.format(java.util.Locale.ROOT, "%02X", value & 0xff);
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

    public interface ProgressListener {
        void onProgress(long bytesSent, int reportsSent, int totalReports, String message);
    }
}
