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

The current pinned dependency is `org.mozilla.geckoview:geckoview:153.0.20260810162159`. It is chosen to avoid the dependency on AndroidX Core 1.19.0 that caused the CI AAR metadata check to require Android API 37 and AGP 9.1.0. This is a build-compatibility decision, not a claim that this is the newest GeckoView release. Review Mozilla's current stable releases and security fixes before any public release.

### Android targets

- `minSdk = 26` — Android 8.0 or later.
- `compileSdk = 36` and `targetSdk = 36` — Android 16 target.
- The app's ABI filter is `arm64-v8a`.
- Java source/target and Kotlin bytecode target use Java 17.

A target SDK is not a maximum supported OS version. Avoid `maxSdkVersion` unless there is a well-justified compatibility reason.

### Build system

The repository uses Android Gradle Plugin 8.13.2, Kotlin Gradle Plugin 2.2.20, and Gradle 8.13 with Java 17. The CI job installs Android SDK Platform 36 and Build Tools 36.0.0, then runs JVM tests before assembling the APK. A Gradle Wrapper should be generated and committed once a build environment is available.

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
