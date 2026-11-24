# TOD Astro Suite 1.0 Engineering RC

TOD Astro Photography uses the Android Camera2 API. TOD Astro Suite is a Windows x64 imaging console for the phone's live feed and capture controls.

## Open a capture session

1. Download the APK and Windows executable from the artifacts of a successful Build TOD Astro Suite run.
2. Install and open TOD Astro Photography on the phone. Grant camera permission and leave the app open.
3. Open TOD Astro Suite on a Windows PC on the same local network. Discovery and reconnection run automatically.
4. Select a target and mode. The camera tab shows the capabilities reported by the phone. Use the capture run tab for still sequences and recording.

Windows opens the dashboard in an Edge app window or the Windows default browser. The Android artifact uses debug signing and the Windows executable is unsigned. The current discovery and capture protocol is intended for a trusted local network.

See the [feature matrix](docs/FEATURE_MATRIX.md) and [validation record](docs/VALIDATION_REPORT.md) for implementation and test status.
