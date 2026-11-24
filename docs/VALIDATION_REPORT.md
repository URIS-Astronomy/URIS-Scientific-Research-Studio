# Engineering RC validation

GitHub Actions runs an Android Gradle build and lint job and a native Windows compile, Go test, Go vet, and JavaScript syntax job. Each successful job uploads its installable artifact.

Go regression coverage exercises the embedded dashboard entry points and verifies that a still sequence counts only matching successful captures. The Android Gradle unit-test task has no test sources. Camera2 stream combinations, installation and capture on a physical Vivo T3 Ultra, native Windows UI interaction, and Siril processing require runtime verification.
