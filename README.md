# Astra Browser

Astra Browser is an experimental Android browser built around Mozilla GeckoView, with an ARM64-first build and privacy-conscious defaults.

> **Status:** early prototype. The current milestone is a reproducible debug build with basic URL navigation. This is not yet a production-ready browser and does not yet include tabs, sync, an extension manager, or a complete private-browsing implementation.

## Goals

- Android 8.0 (API 26) minimum; target Android 16 (API 36).
- Compile against Android API 37 to satisfy the selected GeckoView dependency while keeping target SDK at 36.
- Build the first APK for `arm64-v8a`.
- Use a pinned GeckoView release rather than the legacy Opera Mini native library.
- Keep app permissions minimal and avoid adding analytics or account services by default.
- Build on GitHub Actions and publish the debug APK as a workflow artifact.

## Current prototype

- GeckoView rendering engine.
- Address/search bar with Go and keyboard-submit support.
- HTTP(S) address navigation; ordinary text is searched with DuckDuckGo.
- GitHub Actions build workflow.
- JVM unit tests for address normalization.

## Build

The GitHub Actions workflow installs JDK 17, Android SDK API 37/build tools, and Gradle 9.3.1, then runs:

```sh
gradle --no-daemon clean testDebugUnitTest assembleDebug
```

The resulting APK is written to `app/build/outputs/apk/debug/app-debug.apk`. The app module restricts packaging to `arm64-v8a`.

For local builds, install JDK 17, Android SDK Platform 37, Android Build Tools 36.0.0, and Gradle 9.3.1:

```sh
gradle --version
gradle --no-daemon testDebugUnitTest assembleDebug
```

A Gradle Wrapper has not been added yet. CI installs a pinned Gradle distribution; generating and committing the wrapper remains a follow-up for reproducible local builds.

## Engine and dependency

- GeckoView: `org.mozilla.geckoview:geckoview:158.0.20261007201254`
- Mozilla Maven repository: `https://maven.mozilla.org/maven2/`
- Java compatibility: 17
- Android Gradle Plugin: 9.1.1
- Gradle: 9.3.1
- Kotlin: Android Gradle Plugin's built-in Kotlin support

AGP 9 enables built-in Kotlin, so this project intentionally does not apply the separate `org.jetbrains.kotlin.android` plugin. Recheck Mozilla's stable GeckoView releases and security advisories before shipping updates.

## Security notes

- Only the Internet permission is declared.
- Cleartext HTTP traffic is disabled at the Android manifest level; users should use HTTPS.
- Only HTTP(S) navigation is loaded directly by the address bar. Other schemes are sent to the search provider instead of being passed through to the engine.
- This prototype does **not** yet implement hardened certificate-error handling, site permission UI, download controls, private-session isolation, or extension permission management. Do not present it as a security-audited product.

## Next milestones

See [docs/PROJECT_PLAN.md](docs/PROJECT_PLAN.md) for the implementation plan, engine notes, verification checklist, and extension-support constraints.

## License

No project-wide license has been selected yet. GeckoView is distributed under Mozilla's license terms; review the relevant notices and obligations before redistribution.
