package com.sena30k.updater;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputLayout;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MainActivity extends AppCompatActivity {
    private static final int PICK_FIRMWARE_REQUEST = 1;
    private static final int SENA_VENDOR_ID = 0x092b;
    private static final int SENA_30K_CAPTURED_DFU_PRODUCT_ID = 0x3211;
    private static final int MAX_STAGED_IMAGE_BYTES = 32 * 1024 * 1024;
    private static final String USB_PERMISSION_ACTION =
            "com.sena30k.updater.USB_PERMISSION";
    private static final int USB_ACTION_NONE = 0;
    private static final int USB_ACTION_READ_VERSION = 1;
    private static final int USB_ACTION_TRANSFER = 2;
    private static final int USB_ACTION_RETRY_CHECK = 3;

    private final ExecutorService inspectionWorker = Executors.newSingleThreadExecutor();
    private final ExecutorService downloadWorker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean downloadCancelled = new AtomicBoolean();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private TabLayout tabLayout;
    private View updatePageView;
    private View downloadPageView;
    private FirmwareCatalog catalog;
    private List<FirmwareCatalog.Entry> currentVersions = new ArrayList<>();
    private String selectedModel;
    private String selectedLanguage;
    private int selectedVersionIndex = -1;
    private boolean downloadInProgress;
    private TextView catalogStatus;
    private TextView downloadInfo;
    private TextView downloadStatus;
    private MaterialAutoCompleteTextView modelDropdown;
    private MaterialAutoCompleteTextView languageDropdown;
    private MaterialAutoCompleteTextView versionDropdown;
    private TextInputLayout languageLayout;
    private TextInputLayout versionLayout;
    private MaterialCheckBox onlyLatestCheck;
    private Button downloadButton;
    private Button reloadButton;
    private ProgressBar downloadProgress;
    private TextView usbStatus;
    private TextView usbSummary;
    private TextView firmwareStatus;
    private TextView selectedFileName;
    private TextView deviceVersionStatus;
    private TextView transferProgressStatus;
    private ProgressBar transferProgressBar;
    private Button updateButton;
    private Button chooseButton;
    private Button retryButton;
    private File stagedImageFile;
    private FirmwareImageInspector.Result selectedImage;
    private UsbDevice dfuDevice;
    private DfuInterface dfuInterface;
    private boolean transferInProgress;
    private boolean transferAttempted;
    private boolean awaitingUsbPermission;
    private boolean versionQueryInProgress;
    private boolean permissionReceiverRegistered;
    private boolean retryAvailable;
    private int pendingUsbAction = USB_ACTION_NONE;
    private int selectionGeneration;
    private String lastAutoVersionQueryDeviceName;
    private String detectedDeviceVersion;

    private final BroadcastReceiver usbPermissionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!USB_PERMISSION_ACTION.equals(intent.getAction())) {
                return;
            }
            awaitingUsbPermission = false;
            UsbDevice device;
            if (Build.VERSION.SDK_INT >= 33) {
                device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class);
            } else {
                device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            }
            boolean granted = intent.getBooleanExtra(
                    UsbManager.EXTRA_PERMISSION_GRANTED, false);
            if (!granted || device == null) {
                firmwareStatus.setText("USB permission was not granted; no firmware was sent.");
                pendingUsbAction = USB_ACTION_NONE;
                updateButtonState();
                return;
            }
            if (dfuDevice == null || !dfuDevice.getDeviceName().equals(device.getDeviceName())
                    || findDfuInterface(device) == null) {
                firmwareStatus.setText(
                        "USB permission was returned for a different or unsupported device.");
                pendingUsbAction = USB_ACTION_NONE;
                updateButtonState();
                return;
            }
            int action = pendingUsbAction;
            pendingUsbAction = USB_ACTION_NONE;
            if (action == USB_ACTION_TRANSFER) {
                startFirmwareTransfer(device);
            } else if (action == USB_ACTION_RETRY_CHECK) {
                startDeviceVersionRead(device, true);
            } else {
                startDeviceVersionRead(device, false);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildScreen();
        IntentFilter filter = new IntentFilter(USB_PERMISSION_ACTION);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbPermissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(usbPermissionReceiver, filter);
        }
        permissionReceiverRegistered = true;
        refreshUsbDevices();
        loadCatalog();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshUsbDevices();
    }

    @Override
    protected void onDestroy() {
        inspectionWorker.shutdownNow();
        downloadCancelled.set(true);
        downloadWorker.shutdownNow();
        if (permissionReceiverRegistered) {
            unregisterReceiver(usbPermissionReceiver);
        }
        deleteStagedImage();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (transferInProgress) {
            Toast.makeText(this, "Do not leave while firmware transfer is in progress.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_FIRMWARE_REQUEST || resultCode != RESULT_OK
                || data == null || data.getData() == null) {
            return;
        }

        Uri uri = data.getData();
        beginFirmwareSelection(uri, getDisplayName(uri), null);
    }

    private void beginFirmwareSelection(Uri uri, String displayName, File sourceToDelete) {
        selectionGeneration++;
        int generation = selectionGeneration;
        deleteStagedImage();
        stagedImageFile = null;
        selectedImage = null;
        transferAttempted = false;
        retryAvailable = false;
        transferProgressBar.setProgress(0);
        transferProgressStatus.setText("No transfer in progress.");
        selectedFileName.setText(displayName);
        firmwareStatus.setText("Copying the selected firmware image and reading its version…");
        updateButtonState();
        inspectionWorker.execute(() -> stageAndInspectFirmware(uri, generation, sourceToDelete));
    }

    private void buildScreen() {
        LinearLayout updatePage = newPage();
        addText(updatePage,
                "Firmware writes are limited to the captured 30K DFU identity and a recognized "
                        + "three-component package layout.",
                14, false);
        LinearLayout content = addCard(updatePage, "Headset");
        usbSummary = addText(content, "Scanning…", 15, false);
        deviceVersionStatus = addText(content, "Headset firmware: not read yet", 15, false);
        LinearLayout headsetDetails = new LinearLayout(this);
        headsetDetails.setOrientation(LinearLayout.VERTICAL);
        headsetDetails.setVisibility(View.GONE);
        MaterialButton expandButton = new MaterialButton(this, null,
                com.google.android.material.R.attr.materialIconButtonStyle);
        expandButton.setText("Details ▾");
        expandButton.setOnClickListener(view -> {
            boolean show = headsetDetails.getVisibility() != View.VISIBLE;
            headsetDetails.setVisibility(show ? View.VISIBLE : View.GONE);
            expandButton.setText(show ? "Details ▴" : "Details ▾");
        });
        content.addView(expandButton, matchWrap());
        content.addView(headsetDetails, matchWrap());
        usbStatus = addText(headsetDetails, "Scanning…", 14, false);
        Button readVersionButton = newButton(false);
        readVersionButton.setText("Read headset firmware version");
        readVersionButton.setOnClickListener(view -> requestUsbAction(
                dfuDevice, USB_ACTION_READ_VERSION));
        headsetDetails.addView(readVersionButton, matchWrap());
        Button scanButton = newButton(false);
        scanButton.setText("Scan USB devices");
        scanButton.setOnClickListener(view -> refreshUsbDevices());
        headsetDetails.addView(scanButton, matchWrap());

        content = addCard(updatePage, "Firmware image");
        selectedFileName = addText(content, "No file selected", 15, false);
        firmwareStatus = addText(content, "Select the 30K .img file to inspect it.", 15, false);
        chooseButton = newButton(false);
        chooseButton.setText("Choose firmware .img");
        chooseButton.setOnClickListener(view -> chooseFirmware());
        content.addView(chooseButton, matchWrap());

        content = addCard(updatePage, "Update");
        addText(content, UpdateReadiness.blockedReason(), 15, false);
        updateButton = newButton(true);
        updateButton.setText("Transfer firmware");
        updateButton.setEnabled(false);
        updateButton.setOnClickListener(view -> confirmFirmwareTransfer());
        content.addView(updateButton, matchWrap());
        retryButton = newButton(false);
        retryButton.setText("Retry update (reinitialize)");
        retryButton.setVisibility(ViewGroup.GONE);
        retryButton.setOnClickListener(view -> beginRetry());
        content.addView(retryButton, matchWrap());
        transferProgressBar = new LinearProgressIndicator(this);
        transferProgressBar.setMax(6_157);
        transferProgressBar.setProgress(0);
        content.addView(transferProgressBar, matchWrap());
        transferProgressStatus = addText(content, "No transfer in progress.", 14, false);
        addText(content, UpdateReadiness.safetyNotice(), 14, false);

        assembleScreen(updatePage);
    }

    private void chooseFirmware() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, PICK_FIRMWARE_REQUEST);
    }

    private void stageAndInspectFirmware(Uri uri, int generation, File sourceToDelete) {
        File stagedFile = null;
        try {
            stagedFile = File.createTempFile("sena-firmware-", ".img", getCacheDir());
            try (InputStream input = getContentResolver().openInputStream(uri);
                    OutputStream output = new FileOutputStream(stagedFile)) {
                if (input == null) {
                    throw new IOException("Android could not open the selected document.");
                }
                byte[] buffer = new byte[32 * 1024];
                long totalBytes = 0;
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (read == 0) {
                        continue;
                    }
                    totalBytes += read;
                    if (totalBytes > MAX_STAGED_IMAGE_BYTES) {
                        throw new IOException("Firmware image exceeds the 32 MiB size limit.");
                    }
                    output.write(buffer, 0, read);
                }
            }

            FirmwareImageInspector.Result result;
            try (InputStream input = new java.io.FileInputStream(stagedFile)) {
                result = FirmwareImageInspector.inspect(input);
            }
            File finalStagedFile = stagedFile;
            mainHandler.post(() -> {
                if (generation != selectionGeneration) {
                    finalStagedFile.delete();
                    return;
                }
                deleteStagedImage();
                stagedImageFile = finalStagedFile;
                selectedImage = result;
                showFirmwareResult(result);
                updateButtonState();
            });
        } catch (IOException | SecurityException e) {
            if (stagedFile != null) {
                stagedFile.delete();
            }
            String message = e.getMessage() == null
                    ? e.getClass().getSimpleName() : e.getMessage();
            mainHandler.post(() -> {
                if (generation == selectionGeneration) {
                    firmwareStatus.setText("Could not inspect file: " + message);
                    updateButtonState();
                }
            });
        } finally {
            if (sourceToDelete != null) {
                sourceToDelete.delete();
            }
        }
    }

    private void showFirmwareResult(FirmwareImageInspector.Result result) {
        String version = result.version == null ? "unrecognized header" : result.version;
        StringBuilder details = new StringBuilder(
                "Selected image version: " + version
                        + "\nFile size: " + result.sizeBytes + " bytes");
        if (result.dfuPackage.isValid()) {
            details.append("\n30K package layout: 3 components (integrity digests not checked).");
            for (int index = 0; index < result.dfuPackage.components.size(); index++) {
                DfuImagePackage.Component component = result.dfuPackage.components.get(index);
                details.append("\nComponent ").append(index + 1)
                        .append(": offset ").append(component.offset)
                        .append(", ").append(component.size).append(" bytes");
            }
        } else {
            details.append("\nContainer not recognized: ")
                    .append(result.dfuPackage.validationError());
        }
        if (FirmwareTransferPlan.isProtocolObservedVersion(result)) {
            details.append("\nTransfer sequence captured for this image version.");
        } else {
            details.append("\nThis version was not validated against an official transfer capture.");
        }
        firmwareStatus.setText(details.toString());
    }

    private void refreshUsbDevices() {
        String previousDeviceName = dfuDevice == null ? null : dfuDevice.getDeviceName();
        dfuDevice = null;
        dfuInterface = null;
        UsbManager usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        if (usbManager == null) {
            usbStatus.setText("Android USB host service is unavailable.");
            usbSummary.setText("USB unavailable");
            updateButtonState();
            return;
        }

        List<UsbDevice> devices = new ArrayList<>(usbManager.getDeviceList().values());
        devices.sort(Comparator.comparing(UsbDevice::getDeviceName));
        if (devices.isEmpty()) {
            usbStatus.setText("No USB devices are currently visible to Android.");
            usbSummary.setText("No headset connected");
            lastAutoVersionQueryDeviceName = null;
            detectedDeviceVersion = null;
            deviceVersionStatus.setText("Headset firmware: device disconnected");
            updateButtonState();
            return;
        }

        StringBuilder text = new StringBuilder();
        for (UsbDevice device : devices) {
            if (device.getVendorId() == SENA_VENDOR_ID
                    && device.getProductId() == SENA_30K_CAPTURED_DFU_PRODUCT_ID
                    && dfuInterface == null) {
                DfuInterface candidate = findDfuInterface(device);
                if (candidate != null) {
                    dfuDevice = device;
                    dfuInterface = candidate;
                }
            }
            if (text.length() > 0) {
                text.append("\n\n");
            }
            text.append(device.getDeviceName())
                    .append("\nVID:PID ")
                    .append(hex(device.getVendorId()))
                    .append(':')
                    .append(hex(device.getProductId()));

            text.append('\n').append(UsbIdentity.descriptionFor(
                    device.getVendorId(), device.getProductId()));
            appendUsbInterfaceDetails(text, device);
        }
        if (dfuInterface == null) {
            text.append("\n\nNo captured 30K DFU interface found. Transfer requires "
                    + "092B:3211 with interrupt endpoints 0x02 OUT and 0x81 IN.");
        } else {
            text.append("\n\nCaptured Sena 30K DFU interface is available.");
        }
        usbStatus.setText(text.toString());
        usbSummary.setText(dfuDevice != null
                ? "Sena 30K connected (update mode)"
                : "No Sena 30K in update mode detected");
        if (dfuDevice == null) {
            lastAutoVersionQueryDeviceName = null;
            detectedDeviceVersion = null;
            deviceVersionStatus.setText("Headset firmware: captured DFU device not detected");
        } else if (!dfuDevice.getDeviceName().equals(previousDeviceName)
                || !dfuDevice.getDeviceName().equals(lastAutoVersionQueryDeviceName)) {
            lastAutoVersionQueryDeviceName = dfuDevice.getDeviceName();
            detectedDeviceVersion = null;
            deviceVersionStatus.setText("Headset firmware: reading version…");
            requestUsbAction(dfuDevice, USB_ACTION_READ_VERSION);
        }
        updateButtonState();
    }

    private DfuInterface findDfuInterface(UsbDevice device) {
        if (device.getVendorId() != SENA_VENDOR_ID
                || device.getProductId() != SENA_30K_CAPTURED_DFU_PRODUCT_ID) {
            return null;
        }
        for (int interfaceIndex = 0; interfaceIndex < device.getInterfaceCount(); interfaceIndex++) {
            UsbInterface usbInterface = device.getInterface(interfaceIndex);
            if (usbInterface.getInterfaceClass() != UsbConstants.USB_CLASS_HID) {
                continue;
            }
            UsbEndpoint outEndpoint = null;
            UsbEndpoint inEndpoint = null;
            for (int endpointIndex = 0;
                    endpointIndex < usbInterface.getEndpointCount();
                    endpointIndex++) {
                UsbEndpoint endpoint = usbInterface.getEndpoint(endpointIndex);
                if (endpoint.getType() != UsbConstants.USB_ENDPOINT_XFER_INT) {
                    continue;
                }
                if (endpoint.getAddress() == 0x02) {
                    outEndpoint = endpoint;
                } else if (endpoint.getAddress() == 0x81) {
                    inEndpoint = endpoint;
                }
            }
            if (outEndpoint != null && inEndpoint != null) {
                return new DfuInterface(usbInterface, outEndpoint, inEndpoint);
            }
        }
        return null;
    }

    private boolean canChangeImage() {
        return !transferInProgress && !transferAttempted && !awaitingUsbPermission
                && !versionQueryInProgress && !downloadInProgress;
    }

    private void updateButtonState() {
        if (updateButton == null) {
            return;
        }
        boolean supportedImage = stagedImageFile != null
                && stagedImageFile.isFile()
                && FirmwareTransferPlan.supports(selectedImage);
        updateButton.setEnabled(
                !transferInProgress && !transferAttempted && !awaitingUsbPermission
                        && !downloadInProgress
                        && !versionQueryInProgress && supportedImage
                        && dfuDevice != null && dfuInterface != null);
        updateButton.setText(transferInProgress
                ? "Transferring firmware…"
                : "Transfer firmware");
        if (chooseButton != null) {
            chooseButton.setEnabled(canChangeImage());
        }
        if (retryButton != null) {
            retryButton.setVisibility(retryAvailable ? ViewGroup.VISIBLE : ViewGroup.GONE);
            retryButton.setEnabled(
                    retryAvailable && !transferInProgress && !awaitingUsbPermission
                            && !versionQueryInProgress && stagedImageFile != null
                            && dfuDevice != null && dfuInterface != null);
        }
        if (downloadButton != null) {
            updateDownloadControls();
        }
    }

    private void confirmFirmwareTransfer() {
        if (!FirmwareTransferPlan.supports(selectedImage) || stagedImageFile == null) {
            return;
        }
        int componentOneBytes = selectedImage.dfuPackage.components.get(0).size;
        int componentTwoBytes = selectedImage.dfuPackage.components.get(1).size;
        String imageVersion = selectedImage.version == null
                ? "an image with an unrecognized version header" : selectedImage.version;
        String validationWarning = FirmwareTransferPlan.isProtocolObservedVersion(selectedImage)
                ? "This image version has a matching official transfer capture."
                : "WARNING: this version has NOT been validated in an official transfer capture.";
        String message = "This experimental operation writes " + imageVersion
                + " to the connected Sena 30K. It sends components 1 and 2 ("
                + componentOneBytes + " + " + componentTwoBytes
                + " bytes); component 3 is intentionally not sent, matching both captured "
                + "official updates. " + validationWarning + " Embedded integrity digests are "
                + "not checked and the file is not authenticated by Sena. An incompatible "
                + "image, unsupported downgrade, disconnect, or interruption may leave the "
                + "headset unusable. Rollback behavior is unknown. Continue only with a "
                + "known-good image and a stable USB connection.";
        new MaterialAlertDialogBuilder(this)
                .setTitle("Confirm experimental firmware write")
                .setMessage(message)
                .setNegativeButton("Cancel", (dialog, which) -> dialog.dismiss())
                .setPositiveButton("I understand", (dialog, which) ->
                        requestUsbAction(dfuDevice, USB_ACTION_TRANSFER))
                .show();
    }

    private void requestUsbAction(UsbDevice requestedDevice, int action) {
        UsbManager usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        if (usbManager == null || requestedDevice == null) {
            firmwareStatus.setText("USB device is unavailable; no firmware was sent.");
            return;
        }
        UsbDevice device = usbManager.getDeviceList().get(requestedDevice.getDeviceName());
        if (device == null || findDfuInterface(device) == null) {
            firmwareStatus.setText("The captured 30K DFU interface is no longer connected.");
            refreshUsbDevices();
            return;
        }
        if (usbManager.hasPermission(device)) {
            dispatchUsbAction(device, action);
            return;
        }

        Intent permissionIntent = new Intent(USB_PERMISSION_ACTION)
                .setPackage(getPackageName());
        // UsbManager adds the device and permission result as fill-in extras.
        int pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            pendingIntentFlags |= PendingIntent.FLAG_MUTABLE;
        }
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                this,
                0,
                permissionIntent,
                pendingIntentFlags);
        firmwareStatus.setText("Waiting for Android USB permission; no firmware has been sent.");
        awaitingUsbPermission = true;
        pendingUsbAction = action;
        updateButtonState();
        try {
            usbManager.requestPermission(device, pendingIntent);
        } catch (SecurityException e) {
            awaitingUsbPermission = false;
            pendingUsbAction = USB_ACTION_NONE;
            firmwareStatus.setText("Could not request USB permission: " + errorMessage(e));
            updateButtonState();
        }
    }

    private void dispatchUsbAction(UsbDevice device, int action) {
        if (action == USB_ACTION_TRANSFER) {
            startFirmwareTransfer(device);
        } else if (action == USB_ACTION_RETRY_CHECK) {
            startDeviceVersionRead(device, true);
        } else {
            startDeviceVersionRead(device, false);
        }
    }

    private void beginRetry() {
        if (!retryAvailable || dfuDevice == null || !FirmwareTransferPlan.supports(selectedImage)) {
            return;
        }
        requestUsbAction(dfuDevice, USB_ACTION_RETRY_CHECK);
    }

    private void startDeviceVersionRead(UsbDevice device, boolean forRetry) {
        if (versionQueryInProgress || transferInProgress) {
            return;
        }
        DfuInterface endpoints = findDfuInterface(device);
        if (endpoints == null) {
            deviceVersionStatus.setText("Headset firmware: captured DFU interface unavailable");
            return;
        }
        versionQueryInProgress = true;
        deviceVersionStatus.setText(forRetry
                ? "Headset firmware: rechecking before retry…"
                : "Headset firmware: reading version…");
        updateButtonState();
        inspectionWorker.execute(() -> performDeviceVersionRead(device, forRetry));
    }

    private void performDeviceVersionRead(UsbDevice device, boolean forRetry) {
        UsbManager usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        UsbDeviceConnection connection = null;
        UsbInterface claimedInterface = null;
        boolean interfaceClaimed = false;
        SenaDeviceVersionReader.Result result = null;
        String failure = null;
        try {
            if (usbManager == null) {
                throw new IOException("Android USB host service is unavailable.");
            }
            UsbDevice attached = usbManager.getDeviceList().get(device.getDeviceName());
            DfuInterface endpoints = attached == null ? null : findDfuInterface(attached);
            if (attached == null || endpoints == null || !usbManager.hasPermission(attached)) {
                throw new IOException("The permitted Sena 30K DFU device disconnected or changed.");
            }
            connection = usbManager.openDevice(attached);
            if (connection == null) {
                throw new IOException("Android could not open the headset for version detection.");
            }
            claimedInterface = endpoints.usbInterface;
            interfaceClaimed = connection.claimInterface(claimedInterface, true);
            if (!interfaceClaimed) {
                throw new IOException("Android could not claim the headset interface.");
            }
            result = SenaDeviceVersionReader.read(
                    connection, endpoints.outEndpoint, endpoints.inEndpoint);
        } catch (IOException | SecurityException e) {
            failure = errorMessage(e);
        } finally {
            if (interfaceClaimed && connection != null) {
                connection.releaseInterface(claimedInterface);
            }
            if (connection != null) {
                connection.close();
            }
        }
        SenaDeviceVersionReader.Result versionResult = result;
        String readFailure = failure;
        mainHandler.post(() -> {
            versionQueryInProgress = false;
            if (readFailure != null) {
                detectedDeviceVersion = null;
                deviceVersionStatus.setText("Headset firmware: read failed — " + readFailure);
            } else if (versionResult.version == null) {
                detectedDeviceVersion = null;
                deviceVersionStatus.setText("Headset firmware: unknown (response "
                        + versionResult.rawValue() + ")");
            } else {
                detectedDeviceVersion = versionResult.version;
                deviceVersionStatus.setText(
                        "Headset firmware: " + versionResult.version + " (detected)");
            }
            updateButtonState();
            if (forRetry && readFailure == null) {
                confirmRetryAfterVersionCheck(device, versionResult);
            }
        });
    }

    private void confirmRetryAfterVersionCheck(
            UsbDevice device,
            SenaDeviceVersionReader.Result versionResult) {
        if (versionResult.version != null
                && selectedImage.version != null
                && versionResult.version.equals(
                        selectedImage.version.replaceAll("[-_ ]?build\\d+$", ""))) {
            retryAvailable = false;
            transferAttempted = false;
            firmwareStatus.setText("The headset reports " + versionResult.version
                    + ", matching the selected image. No retry is needed.");
            updateButtonState();
            return;
        }
        String current = versionResult.version == null
                ? "an unknown version (" + versionResult.rawValue() + ")"
                : versionResult.version;
        new MaterialAlertDialogBuilder(this)
                .setTitle("Reinitialize and retry update?")
                .setMessage("The headset was reidentified and reports " + current + ". Its "
                        + "firmware state after the failed transfer is still uncertain. This "
                        + "restarts from component 1; do not proceed if the headset is behaving "
                        + "abnormally. Continue only if the connection is stable.")
                .setNegativeButton("Cancel", (dialog, which) -> updateButtonState())
                .setPositiveButton("Retry from beginning", (dialog, which) -> {
                    retryAvailable = false;
                    transferAttempted = false;
                    updateButtonState();
                    startFirmwareTransfer(device);
                })
                .show();
    }

    private void startFirmwareTransfer(UsbDevice permittedDevice) {
        if (transferInProgress || transferAttempted || stagedImageFile == null
                || !FirmwareTransferPlan.supports(selectedImage)) {
            firmwareStatus.setText("The selected image is not ready for transfer.");
            return;
        }
        transferInProgress = true;
        transferAttempted = true;
        retryAvailable = false;
        int firstReports = FirmwareTransferPlan.reportCount(
                selectedImage.dfuPackage.components.get(0).size);
        int secondReports = FirmwareTransferPlan.reportCount(
                selectedImage.dfuPackage.components.get(1).size);
        transferProgressBar.setMax(firstReports + secondReports);
        transferProgressBar.setProgress(0);
        transferProgressStatus.setText("Preparing transfer… 0 / "
                + (firstReports + secondReports) + " reports acknowledged.");
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        updateButtonState();
        firmwareStatus.setText("Opening the verified Sena 30K DFU interface…");
        File imageFile = stagedImageFile;
        String imageSha256 = selectedImage.sha256;
        inspectionWorker.execute(() -> performFirmwareTransfer(
                permittedDevice, imageFile, imageSha256));
    }

    private void performFirmwareTransfer(
            UsbDevice permittedDevice,
            File imageFile,
            String imageSha256) {
        UsbManager usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        UsbDeviceConnection connection = null;
        UsbInterface claimedInterface = null;
        boolean interfaceClaimed = false;
        String failure = null;
        try {
            if (usbManager == null) {
                throw new IOException("Android USB host service is unavailable.");
            }
            UsbDevice device = usbManager.getDeviceList().get(permittedDevice.getDeviceName());
            DfuInterface endpoints = device == null ? null : findDfuInterface(device);
            if (device == null || endpoints == null || !usbManager.hasPermission(device)) {
                throw new IOException(
                        "The permitted Sena 30K DFU interface disconnected or changed.");
            }
            connection = usbManager.openDevice(device);
            if (connection == null) {
                throw new IOException("Android could not open the Sena 30K DFU interface.");
            }
            claimedInterface = endpoints.usbInterface;
            interfaceClaimed = connection.claimInterface(claimedInterface, true);
            if (!interfaceClaimed) {
                throw new IOException("Android could not claim the Sena 30K DFU interface.");
            }
            SenaDfuUpdater.transfer(
                    connection,
                    endpoints.outEndpoint,
                    endpoints.inEndpoint,
                    imageFile,
                    imageSha256,
                    (bytesSent, reportsSent, totalReports, message) ->
                            mainHandler.post(() -> {
                                transferProgressBar.setMax(totalReports);
                                transferProgressBar.setProgress(reportsSent);
                                int percent = totalReports == 0
                                        ? 0 : reportsSent * 100 / totalReports;
                                transferProgressStatus.setText(
                                        message + " " + reportsSent + " / " + totalReports
                                                + " reports acknowledged (" + percent + "%); "
                                                + bytesSent + " bytes sent.");
                            }));
        } catch (IOException | SecurityException e) {
            failure = errorMessage(e);
        } finally {
            if (interfaceClaimed && connection != null) {
                connection.releaseInterface(claimedInterface);
            }
            if (connection != null) {
                connection.close();
            }
        }
        String transferFailure = failure;
        mainHandler.post(() -> {
            transferInProgress = false;
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            updateButtonState();
            if (transferFailure == null) {
                // The image has been sent; clear it so another one must be chosen on purpose.
                transferAttempted = false;
                retryAvailable = false;
                String sentName = selectedFileName.getText().toString();
                deleteStagedImage();
                selectedImage = null;
                selectedFileName.setText("No file selected (last sent: " + sentName + ")");
                transferProgressStatus.setText("Transfer acknowledgements complete.");
                firmwareStatus.setText(
                        "All transfer reports were acknowledged. This does not prove that the "
                                + "headset rebooted successfully; verify its firmware version "
                                + "with Sena's updater before use. You can choose another image "
                                + "if the headset is still in update mode.");
            } else {
                retryAvailable = true;
                transferProgressStatus.setText(
                        "Stopped at " + transferProgressBar.getProgress() + " / "
                                + transferProgressBar.getMax() + " acknowledged data reports.");
                firmwareStatus.setText(
                        "Transfer stopped: " + transferFailure
                                + "\nThe headset's firmware state is uncertain. Keep it "
                                + "connected. Use Retry update to reidentify it and, if you "
                                + "confirm, restart from component 1.");
            }
            updateButtonState();
        });
    }

    private String errorMessage(Exception error) {
        return error.getMessage() == null
                ? error.getClass().getSimpleName() : error.getMessage();
    }

    private void deleteStagedImage() {
        if (stagedImageFile != null && stagedImageFile.exists()
                && !stagedImageFile.delete()) {
            android.util.Log.w("Sena30KUpdater", "Could not remove the staged firmware image.");
        }
        stagedImageFile = null;
    }

    private void appendUsbInterfaceDetails(StringBuilder text, UsbDevice device) {
        int interfaceCount = device.getInterfaceCount();
        text.append("\nInterfaces: ").append(interfaceCount);

        for (int interfaceIndex = 0; interfaceIndex < interfaceCount; interfaceIndex++) {
            UsbInterface usbInterface = device.getInterface(interfaceIndex);
            text.append("\n")
                    .append(UsbDescriptorFormatter.describeInterface(
                            usbInterface.getId(),
                            usbInterface.getAlternateSetting(),
                            usbInterface.getInterfaceClass(),
                            usbInterface.getInterfaceSubclass(),
                            usbInterface.getInterfaceProtocol()));

            for (int endpointIndex = 0;
                    endpointIndex < usbInterface.getEndpointCount();
                    endpointIndex++) {
                UsbEndpoint endpoint = usbInterface.getEndpoint(endpointIndex);
                text.append("\n  ")
                        .append(UsbDescriptorFormatter.describeEndpoint(
                                endpoint.getAddress(),
                                endpoint.getDirection(),
                                endpoint.getType(),
                                endpoint.getMaxPacketSize()));
            }
        }
    }

    private String getDisplayName(Uri uri) {
        try (android.database.Cursor cursor = getContentResolver().query(
                uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    return cursor.getString(index);
                }
            }
        } catch (SecurityException e) {
            return "Selected file";
        }
        return "Selected file";
    }

    private void loadCatalog() {
        if (catalogStatus != null) {
            catalogStatus.setText("Loading firmware list…");
        }
        if (reloadButton != null) {
            reloadButton.setEnabled(false);
        }
        downloadWorker.execute(() -> {
            FirmwareCatalog loaded = null;
            String failure = null;
            try {
                loaded = FirmwareCatalog.parseIndex(FirmwareDownloader.fetchIndex());
                try (InputStream csv = getAssets().open("older_firmware.csv")) {
                    loaded.addOlderCsv(new String(
                            csv.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
                } catch (IOException e) {
                    android.util.Log.w("Sena30KUpdater", "Older firmware list unavailable.", e);
                }
            } catch (IOException | RuntimeException e) {
                failure = errorMessage(e);
            }
            FirmwareCatalog result = loaded;
            String error = failure;
            mainHandler.post(() -> {
                if (reloadButton != null) {
                    reloadButton.setEnabled(true);
                }
                if (error != null || result == null || result.models().isEmpty()) {
                    catalogStatus.setText("Could not load the firmware list"
                            + (error == null ? "." : ": " + error));
                    return;
                }
                catalog = result;
                catalogStatus.setText(result.models().size() + " headset models available.");
                modelDropdown.setAdapter(new ArrayAdapter<>(
                        this, android.R.layout.simple_list_item_1, result.models()));
                modelDropdown.setEnabled(true);
                if (selectedModel != null && result.models().contains(selectedModel)) {
                    onModelSelected(selectedModel);
                }
            });
        });
    }

    private LinearLayout buildDownloadPage() {
        LinearLayout page = newPage();
        LinearLayout card = addCard(page, "Firmware download");
        catalogStatus = addText(card, "Loading firmware list…", 14, false);

        TextInputLayout modelLayout = addDropdown(card, "Headset model");
        modelDropdown = (MaterialAutoCompleteTextView) modelLayout.getEditText();
        modelDropdown.setEnabled(false);
        modelDropdown.setOnItemClickListener((parent, view, position, id) ->
                onModelSelected((String) parent.getItemAtPosition(position)));

        languageLayout = addDropdown(card, "Language");
        languageDropdown = (MaterialAutoCompleteTextView) languageLayout.getEditText();
        languageLayout.setEnabled(false);
        languageDropdown.setOnItemClickListener((parent, view, position, id) ->
                onLanguageSelected((String) parent.getItemAtPosition(position)));

        onlyLatestCheck = new MaterialCheckBox(this);
        onlyLatestCheck.setText("Only show latest");
        onlyLatestCheck.setChecked(true);
        onlyLatestCheck.setOnCheckedChangeListener((button, checked) -> refreshVersionList());
        card.addView(onlyLatestCheck, matchWrap());

        versionLayout = addDropdown(card, "Firmware version");
        versionDropdown = (MaterialAutoCompleteTextView) versionLayout.getEditText();
        versionLayout.setEnabled(false);
        versionDropdown.setOnItemClickListener((parent, view, position, id) -> {
            selectedVersionIndex = position;
            showSelectedVersion();
        });

        downloadInfo = addText(card, "Select a headset model.", 14, false);
        downloadButton = newButton(true);
        downloadButton.setText("Download and select");
        downloadButton.setEnabled(false);
        downloadButton.setOnClickListener(view -> {
            if (downloadInProgress) {
                downloadCancelled.set(true);
            } else {
                startDownload();
            }
        });
        card.addView(downloadButton, matchWrap());
        downloadProgress = new LinearProgressIndicator(this);
        downloadProgress.setMax(1000);
        card.addView(downloadProgress, matchWrap());
        downloadStatus = addText(card, "", 14, false);
        reloadButton = newButton(false);
        reloadButton.setText("Reload list");
        reloadButton.setOnClickListener(view -> loadCatalog());
        card.addView(reloadButton, matchWrap());
        return page;
    }

    private TextInputLayout addDropdown(LinearLayout parent, String hint) {
        TextInputLayout layout = (TextInputLayout) getLayoutInflater()
                .inflate(R.layout.view_dropdown, parent, false);
        layout.setHint(hint);
        parent.addView(layout);
        return layout;
    }

    private void onModelSelected(String model) {
        selectedModel = model;
        modelDropdown.setText(model, false);
        List<String> languages = catalog.languages(model);
        languageDropdown.setAdapter(new ArrayAdapter<>(
                this, android.R.layout.simple_list_item_1, languages));
        languageLayout.setEnabled(languages.size() > 1);
        selectedLanguage = languages.get(0);
        languageDropdown.setText(selectedLanguage, false);
        refreshVersionList();
    }

    private void onLanguageSelected(String language) {
        selectedLanguage = language;
        refreshVersionList();
    }

    private void refreshVersionList() {
        if (catalog == null || selectedModel == null || selectedLanguage == null) {
            return;
        }
        currentVersions = catalog.versions(
                selectedModel, selectedLanguage, onlyLatestCheck.isChecked());
        List<String> labels = new ArrayList<>();
        for (FirmwareCatalog.Entry entry : currentVersions) {
            labels.add(entry.label());
        }
        versionDropdown.setAdapter(new ArrayAdapter<>(
                this, android.R.layout.simple_list_item_1, labels));
        versionLayout.setEnabled(labels.size() > 1);
        selectedVersionIndex = labels.isEmpty() ? -1 : 0;
        versionDropdown.setText(labels.isEmpty() ? "" : labels.get(0), false);
        showSelectedVersion();
    }

    private void showSelectedVersion() {
        if (selectedVersionIndex < 0 || selectedVersionIndex >= currentVersions.size()) {
            downloadInfo.setText("No firmware found for this selection.");
            return;
        }
        FirmwareCatalog.Entry entry = currentVersions.get(selectedVersionIndex);
        downloadInfo.setText("File: " + entry.fileName + (entry.older
                ? "\nOlder versions may be refused by the headset or be incompatible with it."
                : ""));
    }

    private void startDownload() {
        if (downloadInProgress || selectedVersionIndex < 0
                || selectedVersionIndex >= currentVersions.size() || !canChangeImage()) {
            return;
        }
        FirmwareCatalog.Entry entry = currentVersions.get(selectedVersionIndex);
        File target = new File(getCacheDir(), "download-" + entry.fileName);
        downloadInProgress = true;
        downloadCancelled.set(false);
        downloadProgress.setProgress(0);
        downloadStatus.setText("Downloading " + entry.fileName + "…");
        updateButtonState();
        downloadWorker.execute(() -> {
            String failure = null;
            try {
                FirmwareDownloader.download(entry.fileName, target, (received, total) ->
                        mainHandler.post(() -> {
                            if (total > 0) {
                                downloadProgress.setProgress((int) (received * 1000 / total));
                            }
                            downloadStatus.setText("Downloading " + entry.fileName + ": "
                                    + received / 1024 + " KiB"
                                    + (total > 0 ? " of " + total / 1024 + " KiB" : ""));
                        }), downloadCancelled);
            } catch (IOException | RuntimeException e) {
                failure = errorMessage(e);
            }
            String error = failure;
            mainHandler.post(() -> {
                downloadInProgress = false;
                if (error != null) {
                    downloadStatus.setText("Download failed: " + error);
                    updateButtonState();
                    return;
                }
                downloadStatus.setText("Downloaded " + entry.fileName
                        + ". It is now selected on the Update tab.");
                updateButtonState();
                tabLayout.selectTab(tabLayout.getTabAt(0));
                beginFirmwareSelection(Uri.fromFile(target), entry.fileName, target);
            });
        });
    }

    private void updateDownloadControls() {
        boolean valid = selectedVersionIndex >= 0 && selectedVersionIndex < currentVersions.size();
        downloadButton.setEnabled(downloadInProgress || (valid && canChangeImage()));
        downloadButton.setText(downloadInProgress ? "Cancel download" : "Download and select");
    }

    private LinearLayout newPage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(16), dp(8), dp(16), dp(24));
        return page;
    }

    private LinearLayout addCard(LinearLayout parent, String title) {
        MaterialCardView card = new MaterialCardView(this);
        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.setPadding(dp(16), dp(12), dp(16), dp(16));
        card.addView(inner);
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(12);
        parent.addView(card, params);
        addText(inner, title, 18, true);
        return inner;
    }

    private Button newButton(boolean filled) {
        return filled
                ? new MaterialButton(this)
                : new MaterialButton(this, null,
                        com.google.android.material.R.attr.materialButtonOutlinedStyle);
    }

    private void assembleScreen(LinearLayout updatePage) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        MaterialToolbar toolbar = new MaterialToolbar(this);
        toolbar.setTitle("Sena 30K updater");
        toolbar.setSubtitle("Experimental Android USB updater");
        root.addView(toolbar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        tabLayout = new TabLayout(this);
        tabLayout.addTab(tabLayout.newTab().setText("Update"));
        tabLayout.addTab(tabLayout.newTab().setText("Download"));
        root.addView(tabLayout, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout pages = new FrameLayout(this);
        ScrollView updateScroll = new ScrollView(this);
        updateScroll.addView(updatePage);
        updatePageView = updateScroll;

        LinearLayout downloadPage = buildDownloadPage();
        ScrollView downloadScroll = new ScrollView(this);
        downloadScroll.addView(downloadPage);
        downloadPageView = downloadScroll;
        downloadPageView.setVisibility(View.GONE);

        pages.addView(updatePageView);
        pages.addView(downloadPageView);
        root.addView(pages, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                boolean update = tab.getPosition() == 0;
                updatePageView.setVisibility(update ? View.VISIBLE : View.GONE);
                downloadPageView.setVisibility(update ? View.GONE : View.VISIBLE);
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
            }
        });

        ViewCompat.setOnApplyWindowInsetsListener(root, (view, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return windowInsets;
        });
        setContentView(root);
    }

    private TextView addText(LinearLayout parent, String text, int sizeSp, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(sizeSp);
        if (bold) {
            view.setTypeface(null, android.graphics.Typeface.BOLD);
        }
        view.setPadding(0, dp(8), 0, dp(8));
        parent.addView(view, matchWrap());
        return view;
    }

    private LinearLayout.LayoutParams matchWrap() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(4);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private String hex(int value) {
        return String.format(java.util.Locale.ROOT, "%04X", value);
    }

    private static final class DfuInterface {
        private final UsbInterface usbInterface;
        private final UsbEndpoint outEndpoint;
        private final UsbEndpoint inEndpoint;

        private DfuInterface(
                UsbInterface usbInterface,
                UsbEndpoint outEndpoint,
                UsbEndpoint inEndpoint) {
            this.usbInterface = usbInterface;
            this.outEndpoint = outEndpoint;
            this.inEndpoint = inEndpoint;
        }
    }
}
