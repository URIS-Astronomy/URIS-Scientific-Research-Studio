# TOD Astro Suite 1.0 Engineering RC

TOD Astro Photography uses Android Camera2. TOD Astro Suite is the Windows x64 imaging console for the phone's preview, camera controls, and file transfer.

## Connect and capture

1. Download both artifacts from a successful Build TOD Astro Suite run. Install the Android APK and extract the complete Windows artifact to a folder.
2. Connect the phone using a USB data cable. Enable **USB debugging** in Android Developer Options, open TOD Astro Photography, and grant camera permission.
3. Open **TOD_Astro_Suite.exe**. If Android asks whether to allow USB debugging, unlock the phone and approve its RSA prompt. The Windows app opens the connection automatically. The phone and PC can be on different networks.
4. Select a target and mode and start capture. The **USB**, **LAN**, **Reconnecting**, or **Offline** badge shows the active connection.

The Windows download includes the USB support binaries in the adjacent `platform-tools` folder. Extract the entire download before opening the EXE. Windows must be able to enumerate the phone's USB data and debugger interfaces. A charging-only cable or port that never establishes data can be indistinguishable from a disconnected phone. The dashboard uses Android’s USB power report when a LAN connection is available.

USB has priority. When it is unavailable, the app tries LAN discovery, then the last working LAN address, then the optional manual LAN pairing address. An active LAN session upgrades to USB between exposures or after recording. If USB drops, the app tries USB again and can fall back to the same phone over LAN. Sequence requests keep their original capture ID during recovery.

Windows opens the dashboard in an Edge app window or the Windows default browser. The Android APK uses debug signing and the Windows executable is unsigned.

See the [feature matrix](docs/FEATURE_MATRIX.md) and [validation record](docs/VALIDATION_REPORT.md).
