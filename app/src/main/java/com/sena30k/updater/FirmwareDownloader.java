package com.sena30k.updater;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public final class FirmwareDownloader {
    public static final int MAX_IMAGE_BYTES = 32 * 1024 * 1024;
    private static final int MAX_INDEX_BYTES = 2 * 1024 * 1024;
    private static final int TIMEOUT_MILLIS = 20_000;

    public interface Progress {
        void onBytes(long received, long total);
    }

    private FirmwareDownloader() {
    }

    public static String fetchIndex() throws IOException {
        HttpURLConnection connection = open(FirmwareCatalog.BASE_URL + "Firmware");
        try (InputStream input = connection.getInputStream()) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[16 * 1024];
            int read;
            while ((read = input.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
                if (buffer.size() > MAX_INDEX_BYTES) {
                    throw new IOException("The firmware index is unexpectedly large.");
                }
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            connection.disconnect();
        }
    }

    /** Downloads to {@code target}; a partial file is removed on any failure or cancellation. */
    public static void download(
            String fileName,
            File target,
            Progress progress,
            java.util.concurrent.atomic.AtomicBoolean cancelled) throws IOException {
        if (!FirmwareCatalog.isSafeFileName(fileName)) {
            throw new IOException("Refusing to download an unexpected file name.");
        }
        HttpURLConnection connection = open(FirmwareCatalog.BASE_URL + fileName);
        boolean complete = false;
        try (InputStream input = connection.getInputStream();
                OutputStream output = new FileOutputStream(target)) {
            long total = connection.getContentLengthLong();
            if (total > MAX_IMAGE_BYTES) {
                throw new IOException("The firmware file is larger than the 32 MiB limit.");
            }
            byte[] chunk = new byte[32 * 1024];
            long received = 0;
            int read;
            while ((read = input.read(chunk)) != -1) {
                if (cancelled.get()) {
                    throw new IOException("Download cancelled.");
                }
                received += read;
                if (received > MAX_IMAGE_BYTES) {
                    throw new IOException("The firmware file is larger than the 32 MiB limit.");
                }
                output.write(chunk, 0, read);
                progress.onBytes(received, total);
            }
            if (total >= 0 && received != total) {
                throw new IOException("Download ended early (" + received + " of "
                        + total + " bytes).");
            }
            if (received == 0) {
                throw new IOException("The server returned an empty file.");
            }
            complete = true;
        } finally {
            connection.disconnect();
            if (!complete) {
                target.delete();
            }
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(TIMEOUT_MILLIS);
        connection.setReadTimeout(TIMEOUT_MILLIS);
        connection.setInstanceFollowRedirects(false);
        int status = connection.getResponseCode();
        if (status != HttpURLConnection.HTTP_OK) {
            connection.disconnect();
            throw new IOException(status == 403 || status == 404
                    ? "The server has no such file (HTTP " + status + ")."
                    : "The server answered HTTP " + status + ".");
        }
        return connection;
    }
}
