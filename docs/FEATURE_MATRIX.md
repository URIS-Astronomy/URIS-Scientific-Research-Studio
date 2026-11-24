# Engineering RC feature matrix

| Area | Current implementation |
|---|---|
| Camera | Camera2 rear camera selection by sensor area. Runtime reports logical and physical IDs, sensor size, stream sizes, ISO, exposure, focus, FPS ranges, stabilization, and white balance. The selected Vivo lens and stream combinations require device verification. |
| Phone | Full-height live preview, local controls and capture dialogs, screen-on flag, wake lock, confirmed exit, and battery temperature and free-space telemetry. |
| Windows | Edge app-window dashboard with live preview, histogram, clipping, focus peaking, manual controls, capability report, target profiles, and case-specific capture presets. |
| Stills | Maximum standard JPEG stream size, DNG and combined capture when the configured camera session permits it, timed still sequences, and centered sensor crop. Still sequences are sequential captures. |
| Video | Capability-gated MediaRecorder H.264. Constrained high-speed mode uses automatic camera settings and phone preview while recording. PC live view resumes in the standard camera session. |
| Deep sky | Light, dark, flat and bias labels, interval and frame count, manual light integration estimate, and per-frame journal entries. Interruption preserves received files and the journal. Sequence resume is pending. |
| Live stack | JPEG light sequence preview, bounded translation registration against a reference frame, correlation rejection, running average, accepted/rejected counts, integration estimate, preview stretch and PNG export. Calibrated RAW live stacking, rotation correction, and a stack-specific histogram are pending. |
| Focus and quality | Relative Laplacian sharpness, contrast overlay and live clipping measurements. Stellar HFR/FWHM, quality history and recorded-video best-frame scoring are pending. |
| Files | Windows stores captures under Pictures / TOD Astro Photography / date / target / session / frame type. Temporary files are renamed after transfer. Session journals record sequence progress, camera controls and capabilities, telemetry, file checksum and link failure. |
| Siril | Optional CLI detection and a generated registration/stacking script for captured lights. Calibration master generation and Siril runtime verification are pending. |
| Telemetry | Phone battery and battery temperature, storage, thermal status, and preview FPS. Camera- and encoder-level dropped-frame measurement is pending. |
| Interoperability | The phone transport and live-stack processing are separate from the dashboard assets. ASCOM Alpaca, mount control, guiding, and plate solving remain future integrations. |
