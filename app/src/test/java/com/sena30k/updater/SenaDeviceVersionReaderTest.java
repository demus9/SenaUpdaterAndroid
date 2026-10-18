package com.sena30k.updater;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.io.IOException;

import org.junit.Test;

public class SenaDeviceVersionReaderTest {
    @Test
    public void mapsObservedVersionResponses() throws Exception {
        assertEquals("v4.5", SenaDeviceVersionReader.parseVersionResponse(
                new byte[]{2, 1, 0, 4, 5, 0, 0, 0}).version);
        assertEquals("v4.5.1", SenaDeviceVersionReader.parseVersionResponse(
                new byte[]{2, 1, 0, 4, 5, 2, 1, 0}).version);
        assertEquals("v4.0", SenaDeviceVersionReader.parseVersionResponse(
                new byte[]{2, 1, 0, 4, 0, 0, 0, 1}).version);
        assertEquals("v4.1", SenaDeviceVersionReader.parseVersionResponse(
                new byte[]{2, 1, 0, 4, 1, 0, 0, 3}).version);
        assertEquals("v4.4.1", SenaDeviceVersionReader.parseVersionResponse(
                new byte[]{2, 1, 0, 4, 4, 2, 1, 1}).version);
    }

    @Test
    public void retainsUnknownVersionResponseInsteadOfGuessing() throws Exception {
        SenaDeviceVersionReader.Result result =
                SenaDeviceVersionReader.parseVersionResponse(
                        new byte[]{2, 1, 0, 4, 8, 1, 0, 0});

        assertNull(result.version);
        assertEquals("08 01 00 00", result.rawValue());
    }

    @Test
    public void rejectsMalformedVersionResponse() {
        try {
            SenaDeviceVersionReader.parseVersionResponse(new byte[]{2, 1, 0, 3, 5, 2, 1, 0});
        } catch (IOException expected) {
            return;
        }
        throw new AssertionError("Expected malformed version response to be rejected.");
    }
}
