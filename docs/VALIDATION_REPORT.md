# Engineering RC validation

The Android workflow compiles and lints the APK. Its Gradle unit-test task currently has no test sources. The Windows workflow builds the executable and runs Go tests, Go vet, and syntax checks for the dashboard and analysis worker.

Go tests cover capture correlation, reconnect between exposures, interrupted journal recovery, synthetic stellar HFR/FWHM, dark/flat correction, and uncompressed Bayer DNG decoding. The local dashboard smoke test uses a mock phone and exercises discovery, preview, video mode selection, still sequence delivery, and live-stack PNG export.

Hardware qualification still requires the Vivo T3 Ultra and a Windows PC for the live capture, high-speed recording, focus, DNG and thermal soak. The Android APK is debug-signed and the Windows executable is unsigned. The optional Siril CLI integration needs an end-to-end test with a supported installation.
