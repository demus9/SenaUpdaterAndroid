# Sena Firmware Updater for Android (unofficial)

An unofficial, independently written Android app that updates Sena headset firmware over USB (OTG). The goal is a universal updater for Sena devices; **currently only the Sena 30K is supported for flashing**. The USB protocol was reverse engineered from USB captures and the firmware image layout, not from Sena source code.

> **Use at your own risk.** This project is not affiliated with or endorsed by Sena Technologies. A failed or interrupted update can leave a headset unusable. The Sena name and logo belong to their owners. The software is tested only against a 30K; other models are not supported for flashing.

[![Buy Me a Coffee](https://img.shields.io/badge/Buy%20me%20a%20coffee-demus9-FFDD00?logo=buymeacoffee&logoColor=black)](https://buymeacoffee.com/demus9) If this saved you a trip to a PC, you can [support the project](https://buymeacoffee.com/demus9).
## Features

- **Update tab**
  - Detects the headset (`092B:3211` HID interface, endpoints `0x02` OUT / `0x81` IN) and reads its firmware version.
  - Lets you pick an `.img` file, shows its version and component layout, and asks for confirmation before sending.
  - Shows acknowledged-report progress, retries only the acknowledgement read (never retransmits data), and offers a retry after a failed write.
  - After a successful transfer you can select and send another image.
  - The headset card is collapsed to a short summary; expand it for USB details.
- **Download tab**
  - Loads the current firmware index from `https://firmware.sena.com/senabluetoothmanager/Firmware`.
  - Pick a headset model, a language (or "Universal" when all language files are identical) and a version. "Only show latest" selects the newest known version.
  - Older versions come from `app/src/main/assets/older_firmware.csv`, produced by `scan-older-firmware.ps1`. Only file names matching `[A-Za-z0-9_.-]{1,128}\.img` are downloaded, with a size cap and no redirects.
  - A downloaded file is handed to the Update tab and removed after it is staged.
- Material 3 UI with dynamic colors.

## Version detection

The headset answers the `01 1D` query with four bytes. Only these are known (confirmed against the official updater); anything else is shown as raw bytes:

| Bytes | Version |
|---|---|
| `00 00 00 01` | v4.0 |
| `01 00 00 03` | v4.1 |
| `04 02 01 01` | v4.4.1 |
| `05 00 00 00` | v4.5 |
| `05 02 01 00` | v4.5.1 |

## Known limits

- If an unknown Sena USB product ID is connected, the headset reports an unknown firmware version, or the newest `30KV2` firmware on Sena's server is not in the app's known version list, the app shows a "Contact developer" card that opens a prefilled GitHub issue.
- The transfer sequence is confirmed from captures of v4.5 and v4.5.1 only. Component 1 and component 2 are sent; component 3 is not. Other versions are assumed to use the same sequence.
- The headset may silently refuse a downgrade: flashing 3.0.1 transferred fully, but the headset kept reporting 4.5.1.
- Embedded MD5 values in images are not checked.
- A successful final acknowledgement does not prove the headset rebooted into working firmware. Verify with the official updater.
- Only `30KV2` is relevant to 30K hardware. Entries for other models are listed for convenience and untested.
- Keep the app visible, the phone awake and the cable connected during a write.
- The new UI has not been broadly tested on devices.

## Scanning for older firmware

`scan-older-firmware.ps1` probes the firmware server for older file names of each model (the server answers 200 for existing files and 403 otherwise) and writes `firmware-scan-results.csv` (Model, Version, File, Url, Bytes, ScannedAt).

```powershell
.\scan-older-firmware.ps1 -ListModels
.\scan-older-firmware.ps1 -Models 30KV2
.\scan-older-firmware.ps1 -All -DelayMs 200
```

Other parameters: `-MaxPatch`, `-MaxBuild`, `-OutFile`. It scans from the first minor version of the newest major version upward. Copy the result to `app/src/main/assets/older_firmware.csv` to bundle it.

## Build

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
```

The debug APK is created at `app\build\outputs\apk\debug\app-debug.apk`. Install it manually on the device; this project does not install via ADB.

## Using it

Power the headset OFF and connect it with a USB data cable (OTG adapter if needed). Grant USB permission, check the detected model and version, choose or download an image, and confirm.

## Privacy

The app collects no data. See [PRIVACY.md](PRIVACY.md).

## Icon

The launcher icon is rendered from `sena_update_logo.svg`.

## License

Apache License 2.0, see [LICENSE](LICENSE) and [NOTICE](NOTICE). Redistributions must keep the license and the NOTICE attribution. Firmware images are Sena's property and are not included in this repository.
