# Android builds and distribution

The signed [Android release **2026.10.19.3**](https://github.com/vitofico/spunto/releases/tag/android/2026.10.19.3), version code **14**, is distributed through GitHub Releases. Obtainium can follow those stable Android releases directly; there is no separate Obtainium store. Server images have a separate [Packages page](https://github.com/vitofico/spunto/pkgs/container/spunto-server).

F-Droid submission and external review are pending. Spunto is not listed in F-Droid. The source metadata and recipe prepare that review; they do not establish acceptance or an independently verified F-Droid build.

The signing key stays outside the repository. Physical-phone speech, scanner behavior, performance and accessibility results remain limited to the APKs and devices recorded in the [phone checklist](development/phone-checklist.md). Earlier [preview publication evidence](development/github-publication-2026-10-18.md) describes that earlier binary, not this release.

## Download and sign in

Open the Android release and download `spunto-<CalVer>.apk`, its `.sha256` and `android-version.json`. Verify the checksum, then open the APK with Android's package installer. Permit installation from the app used for the download if Android requests it. The installed package must retain the pinned owner signing certificate for future upgrades.

Enter your server's HTTPS URL. Hosted accounts use browser username/password sign-in or an enabled OIDC provider such as Authentik; create a one-use pairing grant from web **Settings → Devices** for the phone. Local SQLite installations keep their single-owner password flow. Capture, editing, drafts and keyword search also work without signing in or pairing. Pairing adds synchronization with the selected server space. Read the [privacy policy](../PRIVACY.md) for storage, permissions and optional services.

## Debug and owner installations

The release package is `io.theficos.spunto`. Debug builds use `io.theficos.spunto.debug`, the name **Spunto Debug**, and a `-debug` version suffix. They install alongside the owner app, with independent credentials, notes and drafts. Installing a debug build does not migrate the owner's existing replica into it. Keep the existing emulator installation and its data; do not uninstall it or clear storage to work around signing or version differences.

Build from the repository root:

```sh
android/scripts/dgradle :app:assembleDebug :app:assembleDebugAndroidTest
android/scripts/dgradle --no-daemon --no-build-cache :app:clean :app:assembleRelease
```

The release APK remains unsigned until [owner signing](operations.md#android-signing-and-future-distribution). Produce the release reference from clean app outputs; incremental Kotlin/R8 output can differ from a clean build even for the same source. The app keeps its baseline profiles and its 15,000,000-byte APK budget. Benchmark/profile producer variants continue to target the release package.

## CalVer and signing identity

`android/gradle.properties` contains the Android CalVer `YYYY.MM.DD.<run>` and an independent increasing Android version code. Inspect the version with `python3 scripts/android-version.py`; advance it for an explicit release date with `python3 scripts/android-version.py --bump YYYY-MM-DD`. Historical local rebuilds retain their original version. Future publication must advance beyond every previous Android tag and the existing local delivery.

The public owner certificate SHA-256 is pinned in `spec/android-release-certificate.sha256`. It is public verification material; the private key and passwords remain outside the repository. After SDK 34 `apksigner verify --verbose --print-certs` and `aapt dump badging` succeed, `scripts/android-release.py` checks the actual APK's package, CalVer, code, signer, non-debuggable state, baseline profile assets and size. It creates a deterministic checksum and `android-version.json`, including the tested source commit in CI. Existing sidecars with different bytes are refused.

## Normal publication and recovery

The **Release** workflow checks the server and Android before publishing. Server images keep monthly CalVer `YYYY.MM.MICRO`. Android releases use separate `android/YYYY.MM.DD.<run>` tags with:

- `spunto-YYYY.MM.DD.<run>.apk`
- the matching `.apk.sha256`
- `android-version.json`

Disable **Publish Android** for a server-only release. No Android bump is needed then. A locally signed Android release can be published separately without uploading the signing key or password to Actions. The APK must pass its metadata, pinned certificate, profile, size and clean-reproduction checks before the local publisher uploads it and the sidecars. Stable publication omits `--prerelease`; historical previews use `--prerelease --latest false`.

For combined server/Android publication through **Publish Android**, the workflow requires the named Android signing secrets, rejects a reused or non-increasing Android version, signs the APK and verifies independent reproduction before image publication. Enabling that path is a separate operator choice; signing secrets are not configured by a server-only release.

After the image push, the workflow fetches the immutable image index and verifies Linux amd64 and arm64 before creating tags. Both tags name the frozen tested source commit. In the combined publication path, the verified Android tag is created before the server release, so it already anchors recovery if Android publication is interrupted.

The **Repair Android release** workflow repairs stable Android tags. It verifies the peeled source commit and CalVer, without running the new-version check against itself, and loads current reviewed release tools separately for historical tags. If the APK already exists, it verifies those exact bytes before deriving missing sidecars. A missing APK is built, signed and independently reproduced before upload. Every existing asset is compared before any missing asset is uploaded; mismatches fail and bytes are never replaced.

Recovery shares the normal publication lock, never creates a new version or server image, and never changes an existing release's Latest badge. A recreated historical release is not marked Latest. Tag-only, empty-release and missing-sidecar states are supported. Authentication and network errors are failures, not evidence that an asset is absent.

Preview repair uses the local publisher with explicit `--prerelease --latest false`; the stable recovery workflow refuses a preview rather than promoting it. Keep the original APK and tag, verify the actual bytes and source identity, and provide only the missing sidecars. Publication requires the owner's authorization.

## Obtainium

[The import configuration](../distribution/obtainium.json) selects stable Android releases by their daily CalVer title, uses the title as the installed version, and selects only `spunto-<CalVer>.apk`. Monthly server releases, checksums, JSON and debug APKs do not match. Numeric version sorting avoids treating an older repaired release as a new version. The configuration uses Obtainium's documented [GitHub filters](https://wiki.obtainium.imranr.dev/sources/) and [import links](https://wiki.obtainium.imranr.dev/deep_links/).

To add Spunto:

1. Install [Obtainium](https://github.com/ImranR98/Obtainium) on Android.
2. Import the [Spunto configuration](../distribution/obtainium.json), or copy and open the [generated import URI](../distribution/obtainium-link.txt) with Obtainium. Confirm the GitHub source is `https://github.com/vitofico/spunto` and the app ID is `io.theficos.spunto`.
3. Keep prereleases disabled for stable updates. The included release-title and APK filters select Android releases rather than monthly server releases.

Public APK downloads do not require repository access. The import URI contains no credentials; keep any optional Obtainium GitHub token out of shared links. Installation and updates through Obtainium remain unverified on a physical phone; local checks cover the selection rules.

## F-Droid preparation

English and Italian store descriptions, icon, screenshots and version-code changelogs live in `fastlane/metadata/android/`. F-Droid reads that structure; installing the Fastlane tool is unnecessary. Screenshots use synthetic notes only.

[The F-Droid recipe](../distribution/fdroid/metadata/io.theficos.spunto.yml) describes the Android Gradle build, source version extraction, Android release tags and pinned developer certificate. Publication of source and a signed reference APK precedes submission. F-Droid must still review the recipe and scanner results and independently build and verify the APK. For submission, freeze the exact source commit and confirm the reference binary URL against the published release. The fields follow the [official metadata reference](https://f-droid.org/docs/Build_Metadata_Reference/).

Follow the [official submission guide](https://f-droid.org/docs/Submitting_to_F-Droid_Quick_Start_Guide/): prepare the source and metadata, lint/build a copy of the recipe in fdroiddata, resolve scanner/build findings, and submit a merge request only after publication is authorized. Keep the developer signature so installations from GitHub, Obtainium and F-Droid can share the same upgrade identity.

## Reproducibility check

Use SDK build-tools 34 for signing and comparison, as documented by [F-Droid](https://f-droid.org/docs/Reproducible_Builds/). Preserve the baseline profiles. Comparing unpacked ZIP contents alone is insufficient for v2/v3 APK signatures.

With the current Android build tools, the app uses the supported original-profile-plus-obfuscation-mapping compiler (`android.experimental.art-profile-r8-rewriting=false`). The default R8 residual-profile rewriter produced varying rules for a synthesized constructor despite identical DEX files. The configured path retains `baseline.prof`, `baseline.profm` and startup DEX optimization. It can cover fewer optimized or synthetic methods than rewriting mode, including methods whose argument types R8 has strengthened; identical coverage or performance between the modes is not claimed. Phone performance remains part of the owner checklist. Revisit this workaround when upgrading the optimizer, with independent clean builds and profile coverage checks.

Coverage validation distinguishes addressable rules from unmatched ones. The recorded `Composer` signature predates the current function, and R8 strengthens the continuation type of `saveLocal`; those direct rules cannot be counted as covered by the mapping compiler. Historical release 6 evidence describes that binary, not the changed release 7 or every rule in the source profiles.

The release-only, 303-byte [`proguard-rules.pro`](../android/app/proguard-rules.pro) uses `-keep,allowobfuscation` for `NotesRepository$save$3` and its `invokeSuspend(Object)` method. Without this narrow boundary, R8 merged the durable-save state machine into an unprofiled coroutine and the original-profile mapping compiler dropped its post-startup `P` flag. The rule preserves that addressable coroutine while allowing renaming; broader R8 optimization and the original baseline/startup rules remain unchanged. See the [official keep-rule semantics](https://developer.android.com/topic/performance/app-optimization/add-keep-rules).

Strict validation of the corrected signed `2026.10.15.7` delivery covers 332/332 addressable baseline DEX methods and 127/127 classes, plus 192/192 startup methods and 127/127 classes. Profile/DEX integrity and the application/activity `SP` and durable-save coroutine `P` anchors pass. These counts describe the addressable subset of the unchanged 1,542 baseline and 1,108 startup source rules; they do not assert full source-rule or synthetic-expansion coverage. Cryptographic reproduction and emulator acceptance passed separately for this local delivery; see the [acceptance record](development/android-distribution.md). Regenerate profiles and run the phone Macrobenchmark before claiming release latency; this compiler fix establishes no phone performance result.

Install the isolated, pinned comparison tools with Python 3.12, then compare the signed reference with an independently rebuilt unsigned APK. The pinned apksigcopier version changes v1 ZIP signature flags with newer Python `zipfile` implementations; the CLI rejects other runtimes instead of misreporting that tool incompatibility as a build difference.

```sh
python3.12 -m venv /tmp/spunto-apk-repro
/tmp/spunto-apk-repro/bin/pip install -r scripts/reproducibility-requirements.txt
/tmp/spunto-apk-repro/bin/python scripts/check-apk-reproducibility.py \
  /absolute/path/spunto-YYYY.MM.DD.run.apk /absolute/path/rebuilt-unsigned.apk \
  --apksigner "$ANDROID_HOME/build-tools/34.0.0/apksigner"
```

The script copies only the reference signature, verifies the resulting APK cryptographically and requires the resulting full file hash to equal the reference. It fails on differing bytes, invalid signatures or missing tools. A local successful rebuild demonstrates reproduction for those inputs and that toolchain; official F-Droid acceptance still requires its own source recipe, scanner and isolated build environment.
