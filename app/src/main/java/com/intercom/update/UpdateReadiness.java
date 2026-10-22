package com.intercom.update;

public final class UpdateReadiness {
    private UpdateReadiness() {
    }

    public static boolean isFirmwareTransferValidated() {
        return true;
    }

    public static String blockedReason() {
        return "Choose an image with the validated 30K package structure and a supported version "
                + "before transferring.";
    }

    public static String safetyNotice() {
        return "Images with a valid three-component 30K layout can be selected even when their "
                + "version has not been tested. Only v4.5 and v4.5.1 transfer behavior is "
                + "confirmed by captures. Components 1 and 2 are sent; component 3 is not. "
                + "Integrity digests are not checked and firmware authenticity is not verified. "
                + "An incompatible image, unsupported downgrade, or interruption may make the "
                + "headset unusable. Keep the phone awake and cable connected.";
    }
}
