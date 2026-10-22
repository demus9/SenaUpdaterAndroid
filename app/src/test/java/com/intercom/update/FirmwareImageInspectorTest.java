package com.intercom.update;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

public class FirmwareImageInspectorTest {
    @Test
    public void recognizesVersionHeader() throws Exception {
        byte[] image = new byte[128];
        byte[] version = "v4.5.1-build0".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(version, 0, image, 0, version.length);

        FirmwareImageInspector.Result result =
                FirmwareImageInspector.inspect(new ByteArrayInputStream(image));

        assertEquals("v4.5.1-build0", result.version);
    }

    @Test
    public void recognizesTwoSegmentVersionHeader() throws Exception {
        byte[] image = new byte[128];
        byte[] version = "v4.5-build0".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(version, 0, image, 0, version.length);

        FirmwareImageInspector.Result result =
                FirmwareImageInspector.inspect(new ByteArrayInputStream(image));

        assertEquals("v4.5-build0", result.version);
    }

    @Test
    public void rejectsEmptyImage() {
        try {
            FirmwareImageInspector.inspect(new ByteArrayInputStream(new byte[0]));
        } catch (IOException expected) {
            assertEquals("The selected firmware file is empty.", expected.getMessage());
            return;
        }
        throw new AssertionError("Expected an empty image to be rejected.");
    }

    @Test
    public void rejectsUnrecognizedHeader() throws Exception {
        byte[] image = new byte[]{'x', 0, 1, 2};
        FirmwareImageInspector.Result result =
                FirmwareImageInspector.inspect(new ByteArrayInputStream(image));

        assertNull(result.version);
    }
}
