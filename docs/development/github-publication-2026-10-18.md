# Private GitHub publication — 2026-10-18

All three artifacts are published. GitHub reports both repositories and both container packages as private. Android downloads require repository access; private container pulls require authorized registry access.

| Artifact | Version | Published location |
|---|---|---|
| Android preview | `2026.10.18.2`, code `10` | [APK, checksum and source identity](https://github.com/vitofico/spunto/releases/tag/android/2026.10.18.2) |
| Core server and web | `2026.10.0` and `stable` | [Package](https://github.com/users/vitofico/packages/container/package/spunto) · [Release](https://github.com/vitofico/spunto/releases/tag/2026.10.0) |
| Cloud account pages | `2026.10.0` and `stable` | [Package](https://github.com/users/vitofico/packages/container/package/spunto-cloud) · [Release](https://github.com/vitofico/spunto-cloud/releases/tag/2026.10.0) |

## Source and immutable images

- Core source: `84170d72f9e7254f514d21259d1b9b7a2ff973ed`.
- Cloud source: `066679de487e8679307d886ff523aa8fa5b9635d`.
- Android source: `d67864ed822c5fa3c75cf3d61fca318b20670c25`. This differs from the core release source only in four Markdown guides; Android runtime inputs and its static version are identical.

Both image indices were inspected remotely by exact digest before their source tags were created. Linux amd64 and arm64 runnable manifests are present; the version and `stable` tags resolve to the same index for each package.

```text
ghcr.io/vitofico/spunto@sha256:c8909e0229f9a5d771a46403984c1f5778ff062e4b7639b330ed467fa683531b
ghcr.io/vitofico/spunto-cloud@sha256:f37bceba6900efe409b6e662d887ea17b2a76b89c87e693783bc4bfc86bd7955
```

The exclusive GitHub ref creation steps bind the image release tags to the frozen tested commits. The Android tag points to its verified source commit. No existing release assets were replaced.

## Verification

- [Core Release workflow](https://github.com/vitofico/spunto/actions/runs/39145042876): passed Python checks, web checks, PostgreSQL hosted authentication/isolation/recovery, both image platforms, hardened smoke/security checks, Chromium/Firefox/WebKit acceptance, Android tests and native capture/Room integration before publishing.
- [Core Security workflow](https://github.com/vitofico/spunto/actions/runs/39145043045): passed for the same frozen source commit.
- [Cloud Release workflow](https://github.com/vitofico/spunto-cloud/actions/runs/39144886617): passed lint, all 137 tests, secret scanning and hardened image/vulnerability checks before publishing both platforms with SBOM/provenance.
- Release tooling: 41 hermetic tests passed. Independent reviews covered authenticated tag absence, exclusive source refs, image-before-tag ordering, image index verification, immutable repairs, explicit preview state and recovery reproduction. Pre-commit checks passed before the source push.
- Android: two clean uncached release builds; pinned Python 3.12/apksigcopier reproduced the complete owner-signed APK byte for byte. Actual package, CalVer/code, owner certificate, v2/v3 signatures, non-debuggable state, baseline assets and size budget passed verification.
- The exact signed APK fresh-installed and launched offline on a disposable emulator with no fatal crash. No account or production connection was used. The original emulator's app and AVD metadata were preserved.
- Authenticated GitHub downloads of the APK, checksum and identity JSON matched all three local originals exactly, including GitHub's asset digests. Downloaded identity JSON binds the package/version/certificate to the source commit above.

APK: `spunto-2026.10.18.2.apk`, **4,459,585 bytes** (15,000,000-byte budget).

```text
SHA-256: 3272c3acbabb0e07f5e6723828000d177250a6a06cb73e7991a0c334f859f824
Owner certificate SHA-256: 27799072ba669055e4451cb07af03548658505a9399743a51b62c221bb45cc86
```

The prior local APK `2026.10.18.1` failed independent clean reproduction. It was preserved privately and not uploaded; its replacement uses a new CalVer and code. Owner signing keys/passwords remain local and were not uploaded to GitHub or Actions. Publication used the server-only workflow option, followed by the explicit local `--prerelease --latest false` APK path.

## Remaining acceptance

This APK is a private preview. Physical-phone performance, offline English/Italian speech and accessibility acceptance remain incomplete; emulator launch is no phone-performance result. The stable Obtainium import excludes prereleases, and the F-Droid recipe stays disabled. Public store distribution and offsite backup recovery remain unverified.
