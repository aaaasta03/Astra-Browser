# Astra Browser: technical plan

## Current milestone — buildable browser shell

The first milestone deliberately focuses on a small, testable slice:

- GeckoView embedded in a native Android Activity.
- Address-bar handling for HTTP(S) URLs and search terms.
- ARM64-only APK packaging.
- A small JVM unit-test suite.
- GitHub Actions build and APK artifact upload.

The CI run is the source of truth for whether this commit builds. Source files being present is not evidence that an APK has been produced.

## Key choices

### Engine: GeckoView

GeckoView embeds Mozilla's Gecko engine into an Android application. It is preferable to attempting to convert Opera Mini's bundled 32-bit native library into ARM64: native machine code has to be built for the target ABI, not relabeled.

The current dependency is pinned to `org.mozilla.geckoview:geckoview:158.0.20261007201254`, available through Mozilla's Maven repository at the time this starter was prepared. Review current stable releases and security fixes before updates or public releases.

### Android targets

- `minSdk = 26` — Android 8.0 or later.
- `compileSdk = 37` — Android API 37, required by the selected AndroidX Core dependency.
- `targetSdk = 36` — Android 16 target; this remains deliberately separate from compile SDK.
- The app's ABI filter is `arm64-v8a`.
- Java source/target compatibility uses Java 17. AGP 9.1 provides built-in Kotlin support, so the separate Kotlin Android plugin is not applied.

A target SDK is not a maximum supported OS version. Avoid `maxSdkVersion` unless there is a well-justified compatibility reason.

### Build system

The repository uses Android Gradle Plugin 9.1.1 with Gradle 9.3.1 and Java 17. AGP 9's built-in Kotlin support replaces the separate `org.jetbrains.kotlin.android` plugin; the build files intentionally avoid applying both. The CI job installs the Android 37 platform and runs JVM tests before assembling the APK. A Gradle Wrapper should be generated and committed once a build environment is available.

## Privacy and security backlog

1. Add web permission handling with explicit per-site decisions and a way to revoke grants.
2. Design private browsing around a separate/temporary Gecko session and clearly document which data is removed when it closes.
3. Harden certificate-error handling; never offer a casual one-tap bypass for invalid certificates.
4. Add download handling with file type checks and user-confirmed destinations.
5. Review external intent handling, deep links, file chooser handling, and navigation policy.
6. Document any telemetry explicitly; do not add analytics or account services by default.
7. Add tests for navigation, unsupported schemes, error states, and lifecycle behavior.
8. Keep GeckoView patched and review Mozilla security advisories during each dependency update.

## WebExtensions plan and constraints

GeckoView exposes WebExtension APIs, but full extension support is not automatic. Astra must add a UI and policy for installation, permissions, enable/disable, updates, and uninstalling; compatibility must be tested per extension. Mozilla's WebExtensionController distinguishes ordinary installs from built-in extensions, and ordinary installs may require Mozilla signing. Bundled extensions should only be added after validating the API contract for the pinned GeckoView version.

Extension APIs and store distribution policies can change. Do not advertise a desktop-extension catalog as compatible without testing each add-on.

## Acceptance checklist

- [ ] GitHub Actions workflow completes successfully.
- [ ] Debug APK artifact exists and installs on an ARM64 Android device.
- [ ] APK only packages `arm64-v8a` native libraries.
- [ ] Launches and loads HTTPS pages on Android 8+.
- [ ] Address bar submits URLs and search terms correctly.
- [ ] Unit tests pass.
- [ ] Site permissions, private browsing, downloads, and extensions are implemented and tested before those features are advertised.
