# Engineering RC validation

The Android workflow compiles and lints the APK. Android JVM tests exercise encoder timestamp matching and exclusion of camera-reported output loss. The Windows workflow builds the executable and runs Go tests, Go vet, and syntax checks for the dashboard and analysis worker.

Go tests cover capture correlation, reconnect between exposures, interrupted journal recovery, synthetic stellar HFR/FWHM, dark/flat correction, and uncompressed Bayer DNG decoding. The local dashboard smoke test uses a mock phone and exercises discovery, preview, video mode selection, still sequence delivery, and live-stack PNG export.

Hardware qualification still requires the Vivo T3 Ultra and a Windows PC for the live capture, high-speed recording, focus, DNG and thermal soak. The Android APK is debug-signed and the Windows executable is unsigned. Siril CLI 1.4.5 ran the generated script against synthetic JPEG bias, dark, flat and shifted light frames. It produced a registered 32-bit RGB FITS stack. The Windows launcher and physical Vivo RAW data require final runtime checks.

A mock-phone restart test resumed the original session after terminating the PC process. A second test terminated the PC with a capture in flight and verified the resumed process replayed the same capture ID, preserved the other exposures, and produced one file for each requested exposure.

USB tests cover no data device, detected phone without a debugger interface, unauthorized and authorized states, USB disconnect/reconnect, LAN fallback and USB upgrade, transfer over forwarded ports, loss during an exposure, and LAN IP changes while USB stays active. Windows CI verifies the pinned platform-tools archive and invokes the bundled USB helper on the Windows runner. Physical USB cable detection, Windows driver behavior, the Vivo RSA prompt and a Wi-Fi-off capture remain hardware checks.

Camera policy tests exercise physical-lens ranking using synthetic Camera2 metadata, bounded zoom, coordinate-correct crop fallback, full-frame stills with display zoom, native JPEG and RAW size selection, control ranges, stabilization availability, and valid video frame periods. Host tests select an off-axis stellar focus area. Physical enumeration and selection, maximum sensor mode, RAW combination availability, measured exposures, stabilization drift and sustained FPS still require the Vivo T3 Ultra. No nominal sensor resolution or exposure is assumed.

Validation regressions cover quarter-turn rotation and NV21 chroma order, tap coordinates mapped through the display fit and zoom, AF status, brightness-based manual startup limits, separate preview and still selection, and serving exact saved JPEG bytes. The Vivo stock JPEG result must be compared on the same handset and scene. Camera2 advertises stream and processing controls, while proprietary Vivo processing, frame fusion, sharpening and lens tuning can differ.

The browser smoke test used a portrait mock stream and checked the applied rotation diagnostics, auto defaults after leaving a 1-second Deep Sky preset, the High quality request, and the native JPEG review dialog.
