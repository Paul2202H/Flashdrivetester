# USB Flash Drive Tester

A native Android app for non-destructive, file-level USB/OTG storage testing.

## What is implemented
- USB host attach/detach detection and USB VID/PID/manufacturer/product/interface information.
- SAF folder selection, with persisted access to the selected test folder.
- Quick, Standard, and Full-capacity test modes.
- Streaming pseudorandom test data (bounded memory), block-by-block writes, SHA-256 verification, progress, cancellation, and error reporting.
- Sequential write/read throughput measurement during the verification run.
- Explicit user-confirmed cleanup. Test files are never silently removed.
- Test result classification: PASS / WARNING / FAIL.

## Important Android limitation
This application performs **filesystem-level verification** through Android's Storage Access Framework. It does not have raw block-device access, cannot inspect inaccessible sectors, cannot prove the NAND's physical geometry, and cannot bypass a controller that lies consistently. USB VID/PID and manufacturer strings are informational only. A PASS means all bytes in the selected test size were written and read back correctly during this run; it is not a guarantee that the drive is genuine or will remain reliable.

SAF does not provide a portable, trustworthy free-space API for every USB filesystem/provider. Therefore Full Capacity asks the user to enter the amount to test after checking the drive's available space in a file manager. Do not enter more than the free space. Leave extra room for filesystem overhead and existing files.

## Build
1. Install Android Studio (Giraffe or newer) and Android SDK Platform 35.
2. Open this folder in Android Studio.
3. Allow Gradle sync.
4. Connect an Android phone, enable USB OTG if required, and connect the drive.
5. Run the `app` configuration.

The project targets Android 8.0+ (minSdk 26) and compiles against API 35.

## Use
1. Connect the USB drive.
2. Tap **Choose test folder** and select a folder on the USB drive in Android's system picker. Prefer an empty folder named `USBTester`.
3. Choose a mode and test size. Quick defaults to 64 MiB; Standard defaults to 1 GiB; Full uses the entered size.
4. Read the warning and start the test.
5. Keep the drive connected and do not use it while testing.
6. Review the result. Test data remains in the selected folder. Use **Delete test files** only after reviewing the report and confirming.

## Scope / safety
- The app never formats a drive or deletes existing files.
- It creates uniquely named test files inside the folder selected by the user.
- It will only delete files it created and tracks in the current app session; cleanup requires explicit confirmation.
- If the app or device is interrupted, partial files may remain. Inspect the selected folder before deleting anything.
- A disconnect or I/O exception stops the run and produces a warning/failure as appropriate.

## Current limitations / next engineering steps
- The Android USB Host API can enumerate USB devices, but filesystem reads/writes are intentionally performed through SAF. Direct USB Mass Storage BOT/UAS command handling is not implemented.
- Random I/O benchmark and reliable negotiated link speed are not portable through SAF; the current build reports sequential streaming throughput only.
- Test history persistence (Room), CSV/PDF export, and background WorkManager recovery are not included in this first project baseline.
- The generated project has not been built on a physical Android device in this environment. Build and validate it in Android Studio with the target phone and several known-good / known-fake drives before release.
