package com.intercom.update;

import java.io.IOException;
import java.io.InputStream;

public final class FirmwareTransferPlan {
    public static final int REPORT_BYTES = 1_023;
    public static final int REPORT_HEADER_BYTES = 4;
    public static final int MAX_DATA_BYTES_PER_REPORT = REPORT_BYTES - REPORT_HEADER_BYTES;

    private FirmwareTransferPlan() {
    }

    public static boolean supports(FirmwareImageInspector.Result image) {
        return image != null && image.dfuPackage.isValid();
    }

    public static boolean isProtocolObservedVersion(FirmwareImageInspector.Result image) {
        return image != null
                && ("v4.5-build0".equals(image.version)
                || "v4.5.1-build0".equals(image.version));
    }

    public static byte[] command(int command, int argumentLow, int argumentHigh) {
        checkByte(command);
        checkByte(argumentLow);
        checkByte(argumentHigh);
        byte[] report = new byte[REPORT_BYTES];
        report[0] = 0x01;
        report[1] = (byte) command;
        report[2] = (byte) argumentLow;
        report[3] = (byte) argumentHigh;
        return report;
    }

    public static byte[] dataReport(byte[] data, int length) {
        if (data == null || length <= 0 || length > MAX_DATA_BYTES_PER_REPORT
                || length > data.length) {
            throw new IllegalArgumentException("Invalid firmware report payload length.");
        }
        byte[] report = new byte[REPORT_BYTES];
        report[0] = 0x01;
        report[1] = 0x06;
        report[2] = (byte) (length >>> 8);
        report[3] = (byte) length;
        System.arraycopy(data, 0, report, REPORT_HEADER_BYTES, length);
        return report;
    }

    public static int nextChunkLength(long remainingBytes) {
        if (remainingBytes <= 0) {
            throw new IllegalArgumentException("No firmware bytes remain in this block.");
        }
        return (int) Math.min(MAX_DATA_BYTES_PER_REPORT, remainingBytes);
    }

    public static int reportCount(long blockBytes) {
        if (blockBytes <= 0) {
            throw new IllegalArgumentException("A firmware block must not be empty.");
        }
        return (int) ((blockBytes + MAX_DATA_BYTES_PER_REPORT - 1) / MAX_DATA_BYTES_PER_REPORT);
    }

    public static void skipExactly(InputStream input, long bytesToSkip) throws IOException {
        if (bytesToSkip < 0) {
            throw new IllegalArgumentException("The skip length must not be negative.");
        }
        long remaining = bytesToSkip;
        while (remaining > 0) {
            long skipped = input.skip(remaining);
            if (skipped == 0) {
                if (input.read() == -1) {
                    throw new IOException("The selected firmware image ended before its data.");
                }
                skipped = 1;
            }
            remaining -= skipped;
        }
    }

    public static void readExactly(InputStream input, byte[] buffer, int length) throws IOException {
        if (length < 0 || length > buffer.length) {
            throw new IllegalArgumentException("Invalid firmware read length.");
        }
        int offset = 0;
        while (offset < length) {
            int read = input.read(buffer, offset, length - offset);
            if (read == -1) {
                throw new IOException("The selected firmware image ended during transfer.");
            }
            if (read > 0) {
                offset += read;
            }
        }
    }

    private static void checkByte(int value) {
        if (value < 0 || value > 0xff) {
            throw new IllegalArgumentException("Protocol values must fit in one byte.");
        }
    }
}
