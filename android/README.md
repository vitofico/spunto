# Native Android app

The release application ID is `io.theficos.spunto`, with Android 8 (API 26) as the minimum. Debug builds use `io.theficos.spunto.debug`, the launcher label **Spunto Debug** and a `-debug` version-name suffix. They install alongside the owner's release app with separate notes, drafts, credentials, preferences and scheduled work. The runtime namespace and component class names remain `io.theficos.spunto`; capture shortcuts resolve to the current variant's package. Benchmark/profile variants retain the release package identity and must run on an explicitly disposable emulator, because their debug signing key cannot upgrade an owner-signed release.

Kotlin and Compose screens read the Room replica. Saving a note writes the note, derived labels, search index and outbox in one local transaction; sign-in and network access are optional for capture, editing and search.

## Install Android

Download [**2026.10.19.3**, version code **14**](https://github.com/vitofico/spunto/releases/tag/android/2026.10.19.3). The release includes the signed `spunto-2026.10.19.3.apk`, its checksum and `android-version.json`. Open the APK with Android’s package installer. Future updates must retain the same signing certificate and a higher version code.

Capture, editing and local search work without an account or server. The first-run guide explains the basics; **Settings → Show guide** replays it. When you want sync, use your server’s HTTPS address. Multi-account installations offer browser username/password sign-in or optional SSO, followed by QR/code pairing from web **Settings → Devices**. Local SQLite installations also support owner-password sign-in. Pairing can use the camera or a pasted one-use code.

With **App lock** enabled, opening or returning to the locked app starts system biometric authentication immediately. **Use screen lock** offers the phone's PIN, pattern or password; it opens directly when a strong biometric is unavailable. Back closes the locked activity, including from either system authentication prompt. Authentication errors leave a retry button. Canceling authentication for an App lock setting change keeps the current setting.

[Obtainium setup](../docs/android-distribution.md#obtainium) tracks stable Android releases directly from GitHub. F-Droid submission and external review are pending; Spunto is not listed there. Read the [privacy policy](../PRIVACY.md) for local storage, optional permissions and server/AI boundaries.

## Build locally

Run these commands from the repository root with Docker Desktop running:

```sh
android/scripts/dgradle --no-watch-fs :core:model:test :data:local:testDebugUnitTest :data:sync:testDebugUnitTest :core:plugins:testDebugUnitTest :plugins:dictation:testDebugUnitTest
android/scripts/dgradle --no-watch-fs :app:assembleDebug :app:assembleDebugAndroidTest
```

The runner builds or reuses the `spunto-build:latest` local image (JDK 21, Android SDK 35, build tools 34), persists Gradle and the debug signing key in Docker volumes, and mounts the repository's shared conformance fixtures read-only. The host does not need Java. A local JDK 17 or newer and SDK 35 can alternatively run the same tasks through `android/gradlew` from `android/`.

Artifacts are `android/app/build/outputs/apk/debug/app-debug.apk` and `android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`. Installing them with `adb` is a separate device action; always select the intended emulator with `adb -s <serial>`. Debug instrumentation targets `io.theficos.spunto.debug` through `io.theficos.spunto.debug.test/androidx.test.runner.AndroidJUnitRunner`. Debug/test APKs use the local debug key; the release variant is unsigned until the explicit owner-signing step. No release is published by assembly. Never clear data, uninstall the owner's app or install a debug-signed APK over the release identity to make a test pass.

Build a release reference from clean app outputs with the build cache disabled:

```sh
android/scripts/dgradle --no-daemon --no-build-cache :app:clean :app:assembleRelease
```

The [distribution guide's reproducibility check](../docs/android-distribution.md#reproducibility-check) documents the pinned Python 3.12 signature-copy tools and the original-profile/R8-mapping workaround. Baseline profiles and startup DEX optimization remain enabled; phone performance still requires the owner checks.

## CalVer

`android/gradle.properties` is the source of truth: `spuntoVersionName` uses **YYYY.MM.DD.<run>**, with a positive sequence for each release day, and `spuntoVersionCode` is an independent, strictly increasing integer for Android upgrades. The public release is `2026.10.19.3`, code `14`. Earlier deliveries retain their historical [acceptance records](../docs/development/android-distribution.md); rebuilding one uses its original version. Builds never derive versions from today's clock or a CI run number.

Inspect the version or advance it for a chosen release date from the repository root:

```sh
python3 scripts/android-version.py
# Only when preparing the next APK; edits the two source properties:
python3 scripts/android-version.py --bump YYYY-MM-DD
```

For another release on the same day, increment the final CalVer sequence and the code. On a new day the CalVer sequence starts at `1` while the code keeps increasing. Real dates are validated, backdating is refused, and unrelated Gradle properties are preserved. Keep the same code for byte-identical rebuilds of the same APK; increase it before delivering a changed APK. Do not clear the emulator's data or downgrade its package to resolve an older version code.

Android publication uses `android/<CalVer>` tags and `spunto-<CalVer>.apk`, with a checksum and `android-version.json`. Version checks compare the source identity with fetched Android tags and the configured local delivery floor; signed-APK verification checks the actual package, version and owner certificate. Duplicates and lower versions are refused. Server images retain their separate monthly CalVer `YYYY.MM.MICRO`. Disable **Publish Android** in the Release workflow for a server-only release. Local publication can keep the signing key outside CI and upload the verified stable APK separately.

The checked-in Obtainium configuration selects stable Android releases and excludes prereleases and server images. English and Italian store descriptions are under [`fastlane/metadata/android`](../fastlane/README.md). The interface supports English, Spanish, Italian, French and German. **Settings → Language** opens Android's per-app language picker on Android 13 and later; older versions follow the device language. See the [translation guide](../docs/translating.md) to improve a translation or contribute another language. The F-Droid build recipe is preparation for external review, not an accepted listing. Release builds omit checkout-specific VCS metadata and embedded SDK dependency reports; the external receipt records source identity. Release optimization and baseline profiles remain enabled.

The pure model tests read `spec/fixtures` for fold, labels, label rewrite and titles. Room tests run on Robolectric API 28 and verify unicode61 apostrophe tokenization, prefix and accent search, label descendants, drafts, history and immutable operations. Sync tests use synthetic MockWebServer payloads and real-server golden DTOs, covering 401 preservation, edits during an in-flight push, edits and flags during restore, tombstones, per-flag clocks, machine provenance, idempotent duplicate replies and cancellation of a held long-poll.

`SyncEngine` pushes immutable operation batches, pulls pages transactionally, and reconciles a changed epoch in manifest chunks of 1,000 notes. When restores are queued, pending suggestion decisions run first, followed by restores and ordinary note operations; this avoids repeating a suggestion already embodied in the local snapshot. A restore snapshot freezes the exact pending note operations it represents; acknowledgement retires those IDs, while later typing and flag changes stay queued against a confirmed body ancestor or conflicted copy. A rejected operation remains stored and visible as a sync error. The device token is stored in DataStore encrypted with an AES-GCM key from Android Keystore.

Room migrations preserve retained version 1 and 2 databases, including the early version 1 schema without a server binding column. Version 3 persists the latest agent actor separately from note origin. Multi-instance invalidation keeps foreground flows current when a background worker writes to the same file.

The optional real-server regression uses a separately prepared synthetic server and unique synthetic note IDs:

```sh
android/scripts/dgradle --no-watch-fs :data:sync:testDebugUnitTest -PspuntoIntegrationUrl=http://host.docker.internal:18215 -PspuntoIntegrationPassword='browser acceptance password'
```

The test-only Docker transport overrides the Host header to `localhost:18215`; ordinary app traffic remains pinned to its configured server. Configure that disposable server before enabling this test.

Build the baseline profile producer and optimized benchmark variants with:

```sh
android/scripts/dgradle --no-watch-fs :app:assembleNonMinifiedRelease :benchmark:assembleNonMinifiedRelease :app:assembleBenchmarkRelease :benchmark:assembleBenchmarkRelease
```

The producer generates real startup and local capture profiles; `StartupBenchmarks` requires an installed generated profile. Device profile generation and timing are separate device actions. The app's assembly tasks enforce the 15,000,000-byte APK budget, and `:app:checkApkSize` checks the debug artifact explicitly.

The modules follow approved spec §11.1. `:core:model` contains the shared domain functions; `:data:local` owns persistence; `:data:sync` owns network, authentication and scheduled work. Appearance and dictation preferences stay on the device. Plugin configuration and AI status use the same credential restrictions as the server API.

The pairing QR scanner opens in a cancellable native sheet and decodes frames locally with the existing ZXing library. It prefers the rear camera, requests a supported continuous autofocus mode (or periodically retries ordinary autofocus), supports tap focus and a light when the camera offers them, and releases the camera and light when paused or dismissed. Preview frames are bounded to 1080p, with one decoder job at a time and at most five attempts per second, including camera switches and retries. Frames and pairing payloads are neither saved nor logged; a result from a paused, switched or dismissed camera cannot be delivered. Malformed JSON fields are rejected before either server or code changes. Camera errors offer retry and the existing paste-code fallback.

The scanner uses the existing Camera1 platform API without new dependencies. Its camera menu lists only cameras Android exposes to that API; manufacturer-hidden physical lenses are unavailable. Emulator and synthetic tests cover control policy, rotation/focus mapping, bounded decoding and stale-result rejection. Autofocus and lens behavior still require validation on the actual phone, including Motorola Edge 40, in portrait/landscape and normal/low light.
