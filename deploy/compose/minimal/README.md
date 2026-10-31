# Minimal HTTPS Compose installation

This is a single-owner SQLite deployment example. For multiple accounts and shared spaces, use the [hosted setup](../../../docs/hosted.md#operator-setup). The optional **Thinking desk** plugin is off by default and needs only note read/create permissions when enabled by the connected owner; it requires no AI provider, network integration or MCP tools. Its History and Collision workflows, guarded private snapshots and client connection limits are described in the [plugin guide](../../../docs/plugins.md#thinking-desk).

This prepares an installation for `https://spunto.example.org`; replace that hostname in `Caddyfile` and `compose.yaml` with your own. Use it instead of the Kubernetes example, with DNS pointing to this machine and TCP ports 80/443 reachable. Caddy obtains and renews the certificate and redirects HTTP to HTTPS through [automatic HTTPS](https://caddyserver.com/docs/automatic-https). Its official `2.11.7-alpine` multi-platform image is pinned to the registry index digest verified on 2026-10-14, including an amd64 manifest. Preserve the `caddy-data` volume across restarts to retain certificates.

Build and import the reviewed Spunto image locally. Set `SPUNTO_IMAGE` to its immutable digest reference or a unique content-fingerprinted tag; `pull_policy: never` prevents a registry pull. Obtain an owner Argon2id hash using the interactive `spunto owner hash-password` command, redirect its output into a protected file outside this checkout, and set `SPUNTO_OWNER_HASH_FILE` to that file's absolute path. Do not put the password in shell arguments, Compose environment variables or this repository. The file must be readable by the container's UID 10001; for example use a protected parent directory and a read-only file readable through the Docker secret mount. Missing, unreadable or malformed hash files stop startup, so a public visitor cannot claim setup.

Render first, then start when deployment is authorized:

```sh
docker compose -p spunto -f deploy/compose/minimal/compose.yaml -f deploy/compose/minimal/compose.https.yaml config --quiet
docker compose -p spunto -f deploy/compose/minimal/compose.yaml -f deploy/compose/minimal/compose.https.yaml up -d
```

The new `spunto_spunto-data` named volume starts empty. Never copy a development database, acceptance library, or synthetic fixture into it. SQLite, attachments and default backups stay in this volume; arrange independent backups before treating it as durable production storage. Keep a single server instance. The loopback-only port 8080 is available for local diagnosis, while public traffic uses Caddy on 80/443. HTTPS is fixed as the application's public URL, which enables secure session cookies and correct pairing payloads. Caddy forwards the original host; do not rewrite it to the internal service name.

Verify a valid certificate, HTTP redirect, `/readyz`, password login with a `Secure` session cookie, and an authenticated one-use device pairing exchange. Confirm unauthenticated setup cannot be opened. Caddy's admin API and HTTP access logging are disabled; Spunto's application logging excludes credentials and note bodies. AI is optional; configure its profiles and secrets separately if needed.

Stop the server before a restore and keep the same reviewed image:

```sh
docker compose -p spunto -f deploy/compose/minimal/compose.yaml -f deploy/compose/minimal/compose.https.yaml stop spunto
docker compose -p spunto -f deploy/compose/minimal/compose.yaml -f deploy/compose/minimal/compose.https.yaml run --rm spunto spunto restore /data/backups/OPERATOR_SELECTED_SNAPSHOT.db
docker compose -p spunto -f deploy/compose/minimal/compose.yaml -f deploy/compose/minimal/compose.https.yaml start spunto
```
