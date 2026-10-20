# Local operation and recovery

This guide's setup, password and SQLite snapshot commands apply to local single-owner mode. For multiple accounts, username/password with optional OIDC, shared spaces and PostgreSQL, use [Hosted operation](hosted.md). PostgreSQL needs operator-managed database backups paired with the workspace files; SQLite backup/restore commands refuse a hosted configuration.

Run one server process against one writable local data directory. The startup lock refuses a second process on the same directory; SQLite WAL storage must never use NFS. The database is `/data/spunto.db` by default. The container runs as UID/GID 10001 with a read-only root; only `/data` and `/tmp` need writes in the minimal setup. Backup and attachment paths may be separate writable mounts when configured.

## Build and start

```sh
docker build -t spunto:dev-local -f server/Dockerfile .
docker run -d --name spunto -p 127.0.0.1:8080:8080 \
  --read-only --tmpfs /tmp -v spunto-data:/data \
  -e SPUNTO_PUBLIC_URL=http://localhost:8080 spunto:dev-local
```

Complete one-time setup with the code printed in the container log. For an established installation use `SPUNTO_OWNER_PASSWORD_HASH_FILE` with a mounted file containing `spunto owner hash-password` output, instead of putting a password in configuration. Files must be readable by UID 10001. `SPUNTO_PUBLIC_URL` is the actual scheme, host and optional port clients use. Plain HTTP is allowed and identified as unencrypted; use a TLS proxy for a lasting installation. Configure `SPUNTO_TRUSTED_PROXIES` only for the proxy networks the operator controls.

`/healthz` is liveness; `/readyz` confirms readiness. Startup applies numbered migrations after a pre-migration snapshot. A database newer than the running code refuses startup. Run `spunto --help` and subcommand help for the installed version. `GET /api/v1/status` accepts an owner session or a dedicated status token, reports database counts/size and backup age, and exposes no note bodies. Alert on `backup.age_hours` over 30 and on a missing backup. Status includes plugin state, queued/running/dead jobs, destination-host counts, AI health, daily call counts and vector-index progress. Provider problems do not block core startup or capture.

Core retention and backups use the same sleeping, persistent worker as plugins. Retention runs daily at 04:00 in `TZ`; a missed slot runs once after restart. By default it permanently removes notes trashed for 30 days, prunes revisions older than 180 days while retaining the newest 20 per note, and expires applied-operation retry records after 30 days. `SPUNTO_TRASH_DAYS` and `SPUNTO_REVISION_DAYS` configure the first two periods.

## Published container images

The public core includes both the server and web app, including optional PostgreSQL accounts and collaboration. It can be [built from source](#build-and-start) without access to any other repository. Check [Releases](https://github.com/vitofico/spunto/releases) and [Core Packages](https://github.com/vitofico/spunto/pkgs/container/spunto-server) for available prebuilt core images. Android APKs use their own daily CalVer; server images use monthly `YYYY.MM.MICRO` versions.

Public core releases use the `spunto-server` package. Source builds remain available if a suitable prebuilt image has not yet been published.

```sh
# Choose a version present in a server release:
docker pull ghcr.io/vitofico/spunto-server:YYYY.MM.MICRO
# Pin the immutable digest recorded in that release for deployment:
docker pull ghcr.io/vitofico/spunto-server@sha256:DIGEST_FROM_THE_RELEASE
```

Check the release’s supported architectures and verify its immutable digest. Verify backups and recovery before upgrading a persistent installation. The standalone core needs no separate Cloud image or account-pages service.

Relevant pushes to `main` run **Release** for both the server and Android; documentation-only pushes do not publish. Manual **Actions → Release → Run workflow** also prepares the next Android CalVer by default. Set `publish_android=false` for a manual server-only release. The [distribution guide](android-distribution.md#normal-publication-and-recovery) describes the shared checks and signing prerequisites.

After CI, every release prepares a deterministic bot commit as a single child of the checked base. For a server-only release, this commit is empty: the files, tested tree and Android identity stay unchanged, while advancing `main` keeps its expected-head check part of the atomic push. The workflow allocates a monthly version using both Git and registry inventories, pushes and verifies the immutable image, then atomically promotes the bot commit and release tags. It updates `stable` only after Git promotion. A newer `main` queues fresh validation instead of rebasing the candidate. Before promotion, verified release assets and `server-release.json` are saved in `release-candidate-<run-id>-<attempt>` with 30-day retention.

After promotion, use **Repair server release** with the existing monthly tag to finish interrupted publication. It verifies the immutable index, both architectures and their source/version labels, preserves existing assets, metadata and Latest status, and updates `stable` only for the newest server tag. It neither allocates a version nor rebuilds the image. Legacy `2026.10.1` lacks the required revision label and is refused by automatic repair; retain its original recorded release instructions. Android tags have a separate **Repair Android release** workflow.

## Sign in, name your instance owner and take notes out

In local SQLite mode, the login page says **Unlock your Spunto** and explains the single-owner password; there is no username to enter. Hosted PostgreSQL mode offers username/password sign-in and optional OIDC, including Authentik. Existing SSO accounts can add local credentials through **Settings → Account** without creating another library; see [hosted accounts](hosted.md). After signing in, **Settings → Profile** saves an optional private display name.

For a complete portable server export, open **Settings → Data → Export notes** in the web app. This downloads a Markdown ZIP from the server. Notes that exist only on an offline phone must sync before they appear in that server export.

On Android, open a saved note and choose **Share** for the system Sharesheet, or **Save Markdown** to choose a document destination. Both use the saved local Markdown and work offline. Finish editing first; private notes require confirmation. Attachment files are not bundled, so attachment references alone will not make a portable copy of those files. Saving confirms again after the file picker and refuses a changed note, account or replica. Android Settings links to the server's web **Profile** and **Data** pages; those pages require their own web sign-in.

## AI profiles and index recovery

AI provider connections, credentials and limits are configured in the environment. Space owners can save model/profile choices in Settings → AI, overriding the environment defaults for their space. A local embedding profile can be configured as follows (use the Ollama URL reachable from the server):

```sh
SPUNTO_AI_PROFILES=local
SPUNTO_AI_LOCAL_BASE_URL=http://127.0.0.1:11434/v1
SPUNTO_AI_LOCAL_EMBED_MODEL=embeddinggemma
SPUNTO_AI_EMBED_PROFILE=local
```

Each profile has independent `PROVIDER` (`openai` or `anthropic`), `BASE_URL`, `API_KEY` or `API_KEY_FILE`, `CHAT_MODEL`, `EMBED_MODEL`, `TRANSCRIBE_MODEL`, `VISION_MODEL`, `RATE_PER_MIN`, `CONCURRENCY` and `TIMEOUT_S` variables under `SPUNTO_AI_<NAME>_`. Bind each capability separately with `SPUNTO_AI_CHAT_PROFILE`, `SPUNTO_AI_EMBED_PROFILE`, `SPUNTO_AI_TRANSCRIBE_PROFILE` and `SPUNTO_AI_VISION_PROFILE`; an unset binding stays unavailable. Anthropic uses its native Messages SDK for chat and vision and requires a key. Set `EMBED_QUERY_PREFIX` and `EMBED_DOCUMENT_PREFIX` to override model-family defaults. EmbeddingGemma and Nomic receive their required retrieval prefixes automatically.

Settings → AI shows the configured profiles, models and destinations. Its check sends fixed synthetic content only; it never uses library notes. Settings → Plugins can override a declared capability's profile. A fresh instance starts the four AI plugin switches on; missing capabilities leave them Waiting with no active contributions. Newly discovered plugins on an upgrade start off, and saved owner switches always win.

Cloud chat and local embeddings are independent capability choices. For example, the `ollama` profile can use `gpt-oss:20b-cloud` for chat and `embeddinggemma-2:270m` for local embeddings. Configure `SPUNTO_AI_OLLAMA_BASE_URL` for the reachable Ollama OpenAI-compatible `/v1` endpoint; a local proxy URL does not make a cloud model local. Settings → AI lists available models, shows their network locality and lets the space owner save a choice per capability. Choices persist for the current space; **Use environment defaults** stages a reset, applied only with **Save models**. Credentials and provider endpoints remain under operator control. Models appear only when available from the configured provider; the selector never downloads them. A chat/embed configuration does not supply transcription or vision until their respective models are selected and checked.

Vectors share the SQLite database and its backups. Private, trashed and machine-written notes are excluded. Changing an embedding model or prefix backfills a separate coherent index before switching queries. Disabling the last embedding consumer stops updates and keeps the index on disk. Reindex from Settings → AI, or stop the server and run `spunto ai reindex`; add `--drop` to discard retained vectors before rebuilding. Profile outages pause jobs without spending their retry budget; malformed output fails closed. Retry a failed plugin through its plugin settings.

## Backup and restore

Daily backups default to 03:30 in the configured `TZ` (UTC when unset), with seven daily and four weekly retained. `SPUNTO_BACKUP_DIR` defaults to `/data/backups`; pre-migration snapshots have separate retention. Snapshots use `VACUUM INTO` and include a `.sha256` sidecar. Back up the attachment volume separately; a database snapshot is not an attachment-volume backup.

```sh
docker exec spunto spunto backup
```

Copy verified snapshots off the node and periodically restore one into a separate local volume. A successful snapshot command alone does not prove recoverability. To restore an existing volume, stop its server first, choose an actual snapshot pathname, and run the same image with the same data mount and configuration:

```sh
docker stop spunto
docker run --rm --read-only --tmpfs /tmp -v spunto-data:/data \
  -e SPUNTO_PUBLIC_URL=http://localhost:8080 spunto:dev-local \
  spunto restore /data/backups/<actual-snapshot>.db
docker start spunto
```

Restore checks the snapshot checksum, integrity and schema compatibility before replacing the database. It moves the previous database aside and changes `sync_epoch`. Review its printed summary and retain the old file until recovery is verified. Never copy a live SQLite main file alone and assume its WAL changes are included.

A snapshot restores credential and plugin state from its backup time. Review restored credentials and reapply any revocations made after that snapshot. Outstanding pairing grants are deleted during restore; create a fresh grant when pairing is needed. Existing phones should keep their local replica/drafts/outbox and reconnect to the same server URL: the new epoch drives reconciliation rather than a phone data reset. A device that receives 401 must sign in or pair again to that same instance before queued work resumes.

Keep protected backups of environment/configuration, secret files, operator plugin/theme folders and the Android release signing key separately. SQLite snapshots include plugin settings and retained records, but not those external files or attachment bytes. Restore the corresponding trusted code/configuration and attachment tree, verify a separate recovery volume, and retain the previous database until confirmed.

## Historical SQLite deployment evidence

Earlier single-owner deployment checks are retained in the [M5 record](development/M5.md).
Those versions, counts and results describe that historical installation; they are
not a current inventory or evidence of PostgreSQL/SSO cutover.

The [Kubernetes reference](../deploy/k8s/README.md) explains image, TLS and storage
conventions. Adapt its hostnames, image digest, storage and secret references to
your own installation. Verify a separate restore before upgrading a persistent
library; a successful snapshot alone does not establish recoverability.

## Plugin operation

Enable plugins in Settings → Plugins after reviewing their permissions, or through authenticated REST. Hooks are queued after note commits and coalesce for three seconds. Jobs lease for five minutes, retry with bounded backoff, stop after five attempts, and pause on disable. Old contexts remain invalid after re-enable. The host enforces output budgets and five unattended note creations per plugin per day; owner commands are uncapped.

`SPUNTO_PLUGINS` takes comma-separated `+id`/`-id` switches. Unknown IDs fail startup. Plugin settings persist in the configured database; secrets use their named environment variable or `_FILE`, never API forms or command arguments.

Import from Blinko has been removed. Previously imported notes, attachments, source identifiers and edit history remain available. Its retained state is inactive; the bundled importer no longer appears in Settings. Remove any explicit `blinko-import` switch from operator configuration. See the [removal decision](decisions/2026-10-19-remove-blinko.md).

## Local Markdown import

Enable Import Markdown in the web plugin manager, upload a ZIP, review its dry-run report, then explicitly start the import. The staged preview expires after 24 hours if not queued; an already queued durable import retains consent across restart. A running/queued import owns its staged archive, so wait for it to finish before replacing the preview. The importer preserves own-export metadata and linked assets; reruns skip owner-edited notes rather than overwriting them. Archive/expanded/asset budgets remain independent of the ordinary upload limit. See [Markdown contracts](development/M7-markdown.md).

The operator CLI also accepts a ZIP or folder. Stop the server using that data directory first; then use the same configuration and data lock:

```sh
uv run --project server spunto plugins run markdown-import --path /absolute/synthetic-export.zip --dry-run
uv run --project server spunto plugins run markdown-import --path /absolute/synthetic-export.zip
```

These are instructions for an explicitly chosen import, not evidence that an owner library was accessed.

## Android signing and future distribution

The persistent debug key supports emulator upgrades without wiping drafts or the replica. Keep the owner release key and its protected password file outside the repository, with a secure backup of both for signing future updates with the same certificate. The owner key is currently local only and neither Actions signing secret is configured. Local publication uploads only the verified APK and public sidecars.

Normal **Release** runs prepare the Android CalVer and matching F-Droid recipe automatically. For deliberate local preparation, use `python3 scripts/prepare-android-release.py --date YYYY-MM-DD` with complete fetched Android tags. Download the [latest signed Android release](https://github.com/vitofico/spunto/releases/latest); earlier [preview publication checks](development/github-publication-2026-10-18.md) and device acceptance remain historical and do not verify a newer APK. For local publication, produce two clean uncached release builds. After the first, run `android/scripts/sign-release /absolute/private/signing-directory`; the directory contains `spunto-release.p12` (alias `spunto`) and `keystore-password.txt`. The script checks the signature and 15 MB budget. Compare the signed reference with the second unsigned build using the pinned Python 3.12 [reproduction check](android-distribution.md#reproducibility-check), then verify package, version, certificate, profiles and source identity before uploading. See [Android CalVer](../android/README.md#calver) for upgrade identity.

Download the chosen signed APK and checksum from [Android Releases](https://github.com/vitofico/spunto/releases) and open the APK with Android's package installer. Permit installation from the download app when Android requests it. Enter the server's HTTPS URL, such as `https://notes.example.org`. Hosted accounts sign in in the browser with username/password or an enabled SSO provider, then pair through web **Settings → Devices**; local SQLite mode retains owner-password sign-in. Notes, edits, drafts and keyword search remain available offline; queued changes synchronize after reconnection.

For pairing, open web Settings → Devices, create a grant, then scan its QR or paste its `{server, code}` JSON on the phone. The grant expires in five minutes and works once; camera permission is optional when pasting. Device tokens/codes never belong in URL queries. Revoking a phone stops synchronization but keeps its replica, drafts and queued edits; reauthenticate to the same root to resume without clearing app data.

In Spunto's Android app, Settings → Devices includes **Unlock with biometrics or screen lock**, off by default and specific to that phone. Set a secure Android PIN, pattern or password first. Enabling or disabling the switch requires Android system authentication; subsequent unlocks accept a strong enrolled biometric or **Use screen lock**. Cancelling or failing an unlock keeps protected content locked; cancelling a switch change retains the previous setting. Returning from the background, switching the screen off or restarting the process requires another unlock. Share and text-selection capture also pass through the lock, and enabling it suppresses note previews in widgets even during an unlocked session.

This is a local UI lock, not encryption of the notes database. The existing device token stays encrypted in Android's app storage; no owner password is saved. Background synchronization continues with that token, and existing drafts and offline notes remain on the phone. A local unlock cannot restore an expired or revoked server session: sign in or pair again to resume synchronization. The [Motorola record](development/phone-2026-10-15.md) provides partial APK6/APK7 evidence, including the 47 historical debug APK6 cases and later signed APK7 update, synthetic-state retention and app-lock checks. Its owner-reported states and incomplete cleanup verification remain qualified there. Keep the [phone checklist](development/phone-checklist.md) for the remaining authentication, speech, accessibility, quality and performance gates; this does not complete physical-phone acceptance.

Ordinary Dictate defaults to on-device/offline recognition where available. On a phone without an on-device recognizer, provider online fallback requires explicit consent in that phone's Dictation settings. Ordinary Dictate saves text only; the app retains a recording only through the separate Voice memos Record action. Record is an explicit Voice memos action retaining an app-private Ogg Opus clip; full plugin permission consent is required even when transcription is Waiting. Stop finalizes the clip, Save queues the note/attachment, and recovery never starts the microphone or uploads without owner Save. Cached/local recordings remain playable offline; an uncached remote attachment needs an online authenticated download first. Recordings remain after Voice memos is switched off. The server's byte cap is advertised by `/meta`; older servers without that fact use the client's conservative 5 MB fallback. The current web link downloads the authenticated file rather than presenting an inline audio player.

Automatic Android signing requires the owner's approval to configure both `ANDROID_RELEASE_KEYSTORE_BASE64` and `ANDROID_RELEASE_KEYSTORE_PASSWORD` in Actions, using the existing owner key. Missing secrets stop a combined Release run; the workflow never substitutes a debug key. Manual `publish_android=false` remains available for server-only publication without uploading either signing secret. Keep the keystore and password out of Git, logs and release artifacts.

A locally signed Android APK can be published separately with `scripts/publish-release.py`; stable publication omits `--prerelease`. For interrupted publication after Git promotion, use **Repair Android release** with the existing Android tag rather than cutting a new version. It preserves existing bytes, release metadata and Latest status. Private-preview repair uses the local helper with explicit `--prerelease`, preserving its prerelease status. Record exact-APK validation separately, and keep outstanding phone-specific checks visible in the release evidence.

The [Android distribution guide](android-distribution.md) covers the separate debug installation, pinned public certificate, stable Obtainium configuration, pending F-Droid review, reproducibility checks and immutable recovery. Historical device receipts remain limited to their recorded APK bytes; installing a newer preview does not extend those results.
