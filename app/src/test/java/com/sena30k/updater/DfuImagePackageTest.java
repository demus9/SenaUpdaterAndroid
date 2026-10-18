package com.sena30k.updater;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DfuImagePackageTest {
    private static final int DATA_OFFSET = 0x100;

    @Test
    public void validatesComponentTableWithoutCheckingEmbeddedDigests() {
        byte[] image = sampleImage();

        DfuImagePackage result = DfuImagePackage.inspect(image);

        assertTrue(result.isValid());
        assertEquals(3, result.components.size());
        assertEquals(DATA_OFFSET, result.imageDataOffset);
        assertEquals(3, result.components.get(0).size);
        assertEquals(DATA_OFFSET + 3, result.components.get(1).offset);
    }

    @Test
    public void acceptsComponentDigestMismatchWhenLayoutIsValid() {
        byte[] image = sampleImage();
        image[0x34] ^= 1;

        DfuImagePackage result = DfuImagePackage.inspect(image);

        assertTrue(result.isValid());
    }

    @Test
    public void rejectsUnsignedFieldsOutsideSupportedRange() throws Exception {
        byte[] image = sampleImage();
        image[0x30] = (byte) 0xff;
        image[0x31] = (byte) 0xff;
        image[0x32] = (byte) 0xff;
        image[0x33] = (byte) 0xff;

        DfuImagePackage result = DfuImagePackage.inspect(image);

        assertFalse(result.isValid());
    }

    @Test
    public void rejectsGapsBetweenComponents() throws Exception {
        byte[] image = sampleImage();
        putUInt32(image, 0x4c, DATA_OFFSET + 4);

        DfuImagePackage result = DfuImagePackage.inspect(image);

        assertFalse(result.isValid());
    }

    private byte[] sampleImage() {
        byte[] image = new byte[DATA_OFFSET + 6];
        putUInt32(image, 0x20, 3);
        putUInt32(image, 0x2c, DATA_OFFSET);

        putUInt32(image, 0x30, 3);
        putUInt32(image, 0x4c, DATA_OFFSET + 3);
        putUInt32(image, 0x50, 2);

        putUInt32(image, 0x6c, DATA_OFFSET + 5);
        putUInt32(image, 0x70, 1);
        System.arraycopy(new byte[]{'a', 'b', 'c', 'd', 'e', 'f'}, 0, image, DATA_OFFSET, 6);
        return image;
    }

    private void putUInt32(byte[] image, int offset, int value) {
        image[offset] = (byte) value;
        image[offset + 1] = (byte) (value >>> 8);
        image[offset + 2] = (byte) (value >>> 16);
        image[offset + 3] = (byte) (value >>> 24);
    }
}
