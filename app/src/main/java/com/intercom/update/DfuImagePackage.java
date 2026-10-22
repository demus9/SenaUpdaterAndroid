package com.intercom.update;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class DfuImagePackage {
    private static final int COMPONENT_COUNT_OFFSET = 0x20;
    private static final int IMAGE_DATA_OFFSET_OFFSET = 0x2c;
    private static final int FIRST_COMPONENT_SIZE_OFFSET = 0x30;
    private static final int SECOND_COMPONENT_OFFSET_OFFSET = 0x4c;
    private static final int SECOND_COMPONENT_SIZE_OFFSET = 0x50;
    private static final int THIRD_COMPONENT_OFFSET_OFFSET = 0x6c;
    private static final int THIRD_COMPONENT_SIZE_OFFSET = 0x70;
    private static final int HEADER_TABLE_END = 0x84;
    private static final int COMPONENT_COUNT = 3;

    public final List<Component> components;
    public final String error;
    public final int imageDataOffset;

    private DfuImagePackage(int imageDataOffset, List<Component> components, String error) {
        this.imageDataOffset = imageDataOffset;
        this.components = Collections.unmodifiableList(components);
        this.error = error;
    }

    public boolean isValid() {
        return error == null;
    }

    public String validationError() {
        return error;
    }

    public static DfuImagePackage inspect(byte[] image) {
        if (image.length < HEADER_TABLE_END) {
            return invalid("The file is too short to contain a 30K firmware component table.");
        }
        if (readUInt32(image, COMPONENT_COUNT_OFFSET) != COMPONENT_COUNT) {
            return invalid("Unsupported firmware component count.");
        }

        long dataOffsetLong = readUInt32(image, IMAGE_DATA_OFFSET_OFFSET);
        if (dataOffsetLong < HEADER_TABLE_END || dataOffsetLong > image.length) {
            return invalid("Invalid firmware header size.");
        }
        int dataOffset = (int) dataOffsetLong;

        List<Component> components = new ArrayList<>(COMPONENT_COUNT);
        components.add(new Component(
                dataOffset,
                readUInt32(image, FIRST_COMPONENT_SIZE_OFFSET)));
        components.add(new Component(
                readUInt32(image, SECOND_COMPONENT_OFFSET_OFFSET),
                readUInt32(image, SECOND_COMPONENT_SIZE_OFFSET)));
        components.add(new Component(
                readUInt32(image, THIRD_COMPONENT_OFFSET_OFFSET),
                readUInt32(image, THIRD_COMPONENT_SIZE_OFFSET)));

        long previousEnd = dataOffset;
        for (Component component : components) {
            long end = (long) component.offset + component.size;
            if (component.size <= 0 || component.offset < dataOffset
                    || component.offset != previousEnd || end > image.length) {
                return invalid("Firmware component offsets or sizes are invalid.");
            }
            previousEnd = end;
        }
        if (previousEnd != image.length) {
            return invalid("Firmware component ranges do not cover the complete image.");
        }

        return new DfuImagePackage(dataOffset, components, null);
    }

    private static DfuImagePackage invalid(String error) {
        return new DfuImagePackage(0, new ArrayList<>(), error);
    }

    private static int readUInt32(byte[] bytes, int offset) {
        long value = (bytes[offset] & 0xffL)
                | ((bytes[offset + 1] & 0xffL) << 8)
                | ((bytes[offset + 2] & 0xffL) << 16)
                | ((bytes[offset + 3] & 0xffL) << 24);
        return value > Integer.MAX_VALUE ? -1 : (int) value;
    }

    public static final class Component {
        public final int offset;
        public final int size;

        private Component(int offset, int size) {
            this.offset = offset;
            this.size = size;
        }
    }
}
