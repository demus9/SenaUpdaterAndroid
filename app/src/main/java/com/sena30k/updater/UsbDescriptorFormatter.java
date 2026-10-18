package com.sena30k.updater;

import android.hardware.usb.UsbConstants;

import java.util.Locale;

public final class UsbDescriptorFormatter {
    private UsbDescriptorFormatter() {
    }

    public static String describeInterface(
            int id,
            int alternateSetting,
            int interfaceClass,
            int subclass,
            int protocol) {
        return String.format(
                Locale.ROOT,
                "Interface %d alt %d: class %02X (%s), subclass %02X (%s), protocol %02X (%s)",
                id,
                alternateSetting,
                interfaceClass,
                className(interfaceClass),
                subclass,
                subclassName(interfaceClass, subclass),
                protocol,
                protocolName(interfaceClass, subclass, protocol));
    }

    public static String describeEndpoint(int address, int direction, int type, int maxPacketSize) {
        String endpointDirection = direction == UsbConstants.USB_DIR_IN ? "IN" : "OUT";
        return String.format(
                Locale.ROOT,
                "Endpoint %02X %s %s, max packet %d",
                address,
                endpointDirection,
                endpointType(type),
                maxPacketSize);
    }

    private static String className(int interfaceClass) {
        switch (interfaceClass) {
            case 0x01:
                return "Audio";
            case 0x02:
                return "Communications";
            case 0x03:
                return "HID";
            case 0x08:
                return "Mass storage";
            case 0x0a:
                return "CDC data";
            case 0xe0:
                return "Wireless controller";
            case 0xef:
                return "Miscellaneous";
            case 0xfe:
                return "Application specific";
            case 0xff:
                return "Vendor specific";
            default:
                return "Other";
        }
    }

    private static String subclassName(int interfaceClass, int subclass) {
        if (interfaceClass == 0xfe && subclass == 0x01) {
            return "DFU";
        }
        return "Unclassified";
    }

    private static String protocolName(int interfaceClass, int subclass, int protocol) {
        if (interfaceClass == 0xfe && subclass == 0x01) {
            if (protocol == 0x01) {
                return "DFU runtime";
            }
            if (protocol == 0x02) {
                return "DFU mode";
            }
        }
        return "Unclassified";
    }

    private static String endpointType(int type) {
        switch (type) {
            case UsbConstants.USB_ENDPOINT_XFER_CONTROL:
                return "control";
            case UsbConstants.USB_ENDPOINT_XFER_ISOC:
                return "isochronous";
            case UsbConstants.USB_ENDPOINT_XFER_BULK:
                return "bulk";
            case UsbConstants.USB_ENDPOINT_XFER_INT:
                return "interrupt";
            default:
                return "unknown";
        }
    }
}
