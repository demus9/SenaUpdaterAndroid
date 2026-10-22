package com.intercom.update;

public final class UsbIdentity {
    private static final int SENA_VENDOR_ID = 0x092b;
    private static final int DRIVER_LISTED_30K_PRODUCT_ID = 0x3111;
    private static final int USER_OBSERVED_30K_ON_PRODUCT_ID = 0x3220;
    private static final int USER_OBSERVED_30K_OFF_PRODUCT_ID = 0x3211;
    private static final int GENERIC_DFU_PRODUCT_ID = 0xffff;

    private UsbIdentity() {
    }

    /** True for a Sena vendor ID combined with a product ID this app has no knowledge of. */
    public static boolean isUnknownSenaProduct(int vendorId, int productId) {
        return vendorId == SENA_VENDOR_ID
                && productId != USER_OBSERVED_30K_ON_PRODUCT_ID
                && productId != USER_OBSERVED_30K_OFF_PRODUCT_ID
                && productId != DRIVER_LISTED_30K_PRODUCT_ID
                && productId != GENERIC_DFU_PRODUCT_ID;
    }

    public static String descriptionFor(int vendorId, int productId) {
        if (vendorId != SENA_VENDOR_ID) {
            return "Not a Sena USB vendor ID.";
        }

        if (productId == USER_OBSERVED_30K_ON_PRODUCT_ID) {
            return "Observed on your Sena 30K while powered on and off; mode is not established by the PID.";
        }
        if (productId == USER_OBSERVED_30K_OFF_PRODUCT_ID) {
            return "Observed on your Sena 30K while powered off; the PID alone does not confirm DFU or bootloader mode.";
        }
        if (productId == DRIVER_LISTED_30K_PRODUCT_ID) {
            return "Listed as Sena 30K in the installed Windows driver; not observed on your headset.";
        }
        if (productId == GENERIC_DFU_PRODUCT_ID) {
            return "Generic Sena DFU identity; the 30K model is not confirmed.";
        }
        return "Not identified as one of this 30K's observed USB identities.";
    }
}
