# Release procedure

1. Complete `docs/DEVICE_TEST_PLAN.md` and archive the evidence.
2. Confirm the vendored Aether revision and AGPL notices match the APK source.
3. Run the local Rust, Gradle test, lint, preview, and stable release tasks.
   Confirm the generated `armeabi-v7a`, `arm64-v8a`, `x86_64`, and universal
   APKs contain the intended native libraries.
4. Configure the four Android signing secrets documented in `README.md`.
5. Push a signed `vX.Y.Z` tag. GitHub Actions builds and verifies all split APKs,
   the universal APK, and the signed Android App Bundle.
6. Download the correct ABI APK (or universal APK), verify `SHA256SUMS` and
   `apksigner verify`, install it over the tested release candidate, and repeat
   a short smoke test.
7. Confirm the GitHub release exposes the corresponding source at the same tag.

Continuous main releases are prereleases and are replaced after each successful
main build. Tagged releases are immutable except for re-uploading assets for the
same verified tag.
