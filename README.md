# WhiteAestherMobile

Native Android client for the Aether encrypted route engine. The production
application is Jetpack Compose and uses Android `VpnService`. The design
reference and the notes from building it live in `design/`.

## Connection modes

- **Whole device (TUN):** captures default IPv4 and IPv6 routes plus DNS through
  a non-blocking Android TUN interface. Every Aether transport socket is passed
  through `VpnService.protect(fd)` before use.
- **SOCKS5 proxy:** exposes Aether's SOCKS5 service only on
  `127.0.0.1:<selected-port>`. The allowed port range is 1024–65535.

The modes are mutually exclusive and share one foreground service. MASQUE over
HTTP/3 is the default transport; HTTP/2 is available for networks that block
UDP. A route is not reported connected until Aether completes its data-plane
validation.

## Endpoint discovery

- **Automatic:** scans the bundled Cloudflare MASQUE ingress ranges and selects
  a cryptographically authenticated route.
- **Custom first:** validates a user-supplied IPv4 endpoint or bracketed IPv6
  endpoint, then falls back to automatic discovery if it fails.
- **Custom only:** fails closed when the supplied endpoint does not complete the
  authenticated MASQUE check.

The Network screen can run a cancellable scan, rank up to six validated results
by round-trip time, test a custom endpoint, and select a result. Hostnames and
generic open-port scanning are intentionally not supported; custom values use
`IP:port` or `[IPv6]:port` syntax.

## Stack

- Kotlin 2.4.10, Jetpack Compose 1.11.4, Material 3 1.4.0
- Android Gradle Plugin 9.2.0, Gradle 9.4.1, JDK 21
- minSdk 26, target/compileSdk 36, NDK r29
- Rust 1.88 and cargo-ndk 4.1.2
- Aether 1.5.0, vendored from revision
  `21b9872d080bd185600b93ff20b87f3f3e1e7307`

## Build and test

Set `ANDROID_HOME`, install Android platform/build-tools 36, NDK
`29.0.14206865`, CMake 3.22.1, Rust 1.88, all three Android Rust targets, and
`cargo-ndk` 4.1.2. On Windows, CMake 4.x must also be available at
`C:\Program Files\CMake\bin\cmake.exe` for the BoringSSL build.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
cargo +1.88.0 test --manifest-path native\android-bridge\Cargo.toml --locked
./gradlew testPreviewDebugUnitTest lintPreviewDebug assemblePreviewDebug
./gradlew assemblePreviewDebugAndroidTest
```

Preview APKs:

- `app/build/outputs/apk/preview/debug/app-preview-armeabi-v7a-debug.apk`
- `app/build/outputs/apk/preview/debug/app-preview-arm64-v8a-debug.apk`
- `app/build/outputs/apk/preview/debug/app-preview-x86_64-debug.apk`
- `app/build/outputs/apk/preview/debug/app-preview-universal-debug.apk`

The repository does not claim physical-device completion until the checks in
[`docs/DEVICE_TEST_PLAN.md`](docs/DEVICE_TEST_PLAN.md) pass on supported Android
versions. An emulator/physical device was not available during the local
foundation build.

## Release automation

`.github/workflows/build-release.yml` runs Rust tests/clippy, Android unit tests,
lint, 32-bit ARM, 64-bit ARM, x86_64 and universal APK builds, instrumentation
APK compilation, design prototype checks, and preview artifact upload. Main/tag
releases require these repository secrets:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

The workflow signs and verifies every APK plus the Android App Bundle, checks
the packaged native ABIs, generates SHA-256 checksums, and publishes tagged or
replaceable `continuous` releases. See
[`docs/RELEASE.md`](docs/RELEASE.md).

## Source and license

WhiteAestherMobile and its Aether integration are licensed under AGPL-3.0.
The corresponding source, pinned upstream revision, local build compatibility
patch, and notices are included in this repository. See `LICENSE`,
`THIRD_PARTY_NOTICES.md`, and `native/aether/UPSTREAM.md`.
