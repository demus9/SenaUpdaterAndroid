package com.sena30k.updater;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

public final class FirmwareImageInspector {
    private static final int MAX_IMAGE_BYTES = 32 * 1024 * 1024;
    private static final int HEADER_BYTES = 32;
    private FirmwareImageInspector() {
    }

    public static Result inspect(InputStream input) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable on this Android device.", e);
        }

        byte[] buffer = new byte[32 * 1024];
        ByteArrayOutputStream image = new ByteArrayOutputStream();
        long totalBytes = 0;
        int read;

        while ((read = input.read(buffer)) != -1) {
            if (read == 0) {
                continue;
            }
            totalBytes += read;
            if (totalBytes > MAX_IMAGE_BYTES) {
                throw new IOException("Firmware image exceeds the 32 MiB inspection limit.");
            }
            digest.update(buffer, 0, read);
            image.write(buffer, 0, read);
        }

        if (totalBytes == 0) {
            throw new IOException("The selected firmware file is empty.");
        }

        byte[] imageBytes = image.toByteArray();
        String version = readVersion(imageBytes, Math.min(imageBytes.length, HEADER_BYTES));
        String sha256 = toHex(digest.digest());
        DfuImagePackage dfuPackage = DfuImagePackage.inspect(imageBytes);

        return new Result(totalBytes, sha256, version, dfuPackage);
    }

    private static String readVersion(byte[] header, int length) {
        int textLength = 0;
        while (textLength < length && header[textLength] != 0) {
            if ((header[textLength] & 0x80) != 0) {
                return null;
            }
            textLength++;
        }
        if (textLength == 0 || textLength == length) {
            return null;
        }

        String value = new String(header, 0, textLength, StandardCharsets.US_ASCII);
        if (!value.matches("v[0-9]+\\.[0-9]+(?:\\.[0-9]+)?-build[0-9]+")) {
            return null;
        }
        return value;
    }

    private static String toHex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return result.toString();
    }

    public static final class Result {
        public final long sizeBytes;
        public final String sha256;
        public final String version;
        public final DfuImagePackage dfuPackage;

        private Result(long sizeBytes, String sha256, String version, DfuImagePackage dfuPackage) {
            this.sizeBytes = sizeBytes;
            this.sha256 = sha256;
            this.version = version;
            this.dfuPackage = dfuPackage;
        }
    }
}
