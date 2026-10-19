#!/usr/bin/env bash
# Prove a built image works the way a self-hoster runs it (02 §10, §14): it starts
# hardened as in the compose file, answers its probes, takes the owner through setup
# and login, backs up while running, refuses to restore while running, and restores
# with the server stopped. Notes, labels, search, bodies and sessions survive a
# restore, and changes made after the snapshot disappear.
#
#   docker buildx build --load -t spunto:dev -f server/Dockerfile .
#   scripts/smoke-selfhost.sh spunto:dev          # SMOKE_PORT picks the host port (18080)
#
# Needs docker, curl and jq. Removes its container and volume on exit.
set -euo pipefail

image=${1:?usage: smoke-selfhost.sh <image>}
port=${SMOKE_PORT:-18080}
url=http://127.0.0.1:$port
name=spunto-smoke-$$
password="smoke test password"
jars=$(mktemp -d)
# The compose file's hardening, so the smoke test meets the same limits.
run_args=(--read-only --tmpfs /tmp --cap-drop ALL --security-opt no-new-privileges
  -v "$name:/data" -e SPUNTO_PUBLIC_URL="$url")

cleanup() {
  docker rm -f "$name" >/dev/null 2>&1 || true
  docker volume rm "$name" >/dev/null 2>&1 || true
  rm -rf "$jars"
}
trap cleanup EXIT

fail() {
  echo "smoke: FAIL: $*" >&2
  docker logs --tail 20 "$name" >&2 || true
  exit 1
}

# status METHOD PATH [curl args...]: print only the HTTP status.
status() { curl -s -o /dev/null -w '%{http_code}' -X "$1" "$url$2" "${@:3}" || true; }

expect() { # expect WANT WHAT METHOD PATH [curl args...]
  local got
  got=$(status "${@:3}")
  [[ $got == "$1" ]] || fail "$2: expected $1, got $got"
  echo "smoke: ok: $2 ($got)"
}

wait_ready() {
  for _ in $(seq 60); do
    [[ $(status GET /readyz) == 200 ]] && return
    sleep 1
  done
  fail "the server did not become ready within a minute"
}

# sign_in JAR PATH BODY: run setup or login, keeping the session cookie in JAR.
sign_in() {
  expect 204 "POST $2, session $1" POST "$2" -c "$jars/$1" -H 'Content-Type: application/json' -d "$3"
}

sessions() {
  curl -sf -b "$jars/$1" "$url/api/v1/credentials" \
    | jq '[.[] | select(.kind == "session")] | length' || fail "listing sessions"
}
epoch() { curl -sf "$url/api/v1/meta" | jq -r .sync_epoch || fail "reading /api/v1/meta"; }
notes() { curl -sf -b "$jars/first" "$url/api/v1/notes" | jq '.notes | length' || fail "listing notes"; }
create_note() {
  curl -sf -b "$jars/first" -H "Origin: $url" -H 'Content-Type: application/json' \
    -d "$(jq -nc --arg body "$1" '$ARGS.named')" "$url/api/v1/notes" || fail "creating a note"
}
note_body() {
  curl -sf -b "$jars/first" "$url/api/v1/notes/$1" | jq -r .body || fail "reading a note"
}
search_count() {
  curl -sf -G -b "$jars/first" --data-urlencode "q=$1" "$url/api/v1/search" \
    | jq '.hits | length' || fail "searching notes"
}
task_progress() {
  curl -sf -b "$jars/first" "$url/api/v1/notes/$original_id" \
    | jq -r '.annotations[] | select(.plugin_id == "tasks" and .kind == "meta") | .payload.text'
}
wait_task_progress() {
  for _ in $(seq 15); do
    [[ $(task_progress) == "$1" ]] && return
    sleep 1
  done
  fail "tasks did not derive expected progress within 15 seconds"
}
task_view_count() {
  curl -sf -b "$jars/first" "$url/api/v1/notes?view=tasks/tasks" \
    | jq '.notes | length' || fail "reading tasks view"
}
edit_original() {
  local version
  version=$(curl -sf -b "$jars/first" "$url/api/v1/notes/$original_id" | jq -r .version)
  expect 200 "edit checkbox fixture" PATCH "/api/v1/notes/$original_id" \
    -b "$jars/first" -H "Origin: $url" -H 'Content-Type: application/json' \
    -H "If-Match: \"$version\"" -d "$(jq -nc --arg body "$1" '$ARGS.named')"
}

echo "smoke: starting $image on $url"
docker run -d --name "$name" -p "127.0.0.1:$port:8080" "${run_args[@]}" "$image" >/dev/null
wait_ready
expect 200 "liveness" GET /healthz
expect 200 "packaged web client" GET /
page=$(curl -sf "$url/")
[[ $page == *'data-theme="carta"'* ]] || fail "web client did not receive its initial theme"
asset=$(sed -n 's/.*src="\([^"]*\/assets\/[^" ]*\.js\)".*/\1/p' <<< "$page")
[[ -n $asset ]] || fail "web entry asset missing"
expect 200 "packaged JavaScript entry" GET "$asset"
expect 200 "client route fallback" GET /settings/plugins
expect 404 "missing JavaScript remains a 404" GET /assets/missing.js

code=$(docker logs "$name" 2>&1 | grep -o 'setup code: [A-Z2-7-]*' | cut -d' ' -f3)
[[ -n $code ]] || fail "no setup code in the log"
sign_in first /api/v1/setup "$(jq -nc --arg code "$code" --arg password "$password" '$ARGS.named')"
login=$(jq -nc --arg password "$password" '$ARGS.named')
sign_in second /api/v1/auth/login "$login"
before=$(sessions first)
[[ $before == 2 ]] || fail "expected 2 sessions after setup and login, got $before"
epoch_before=$(epoch)
catalog=$(curl -sf -b "$jars/first" "$url/api/v1/plugins")
[[ $(jq -c '[.plugins[].id] | sort' <<< "$catalog") == \
  '["agent-inbox","ask","context-packs","decision-journal","develop","dictation","digest","duplicates","email-in","image-text","label-gardener","label-suggestions","link-enrichment","links","markdown-import","quick-capture","related","resurface","semantic-search","tasks","telegram","tidy","title-summary","translate","voice-memos","webhooks"]' ]] \
  || fail "image plugin catalog differs"
[[ $(jq '[.plugins[] | select(.default == "off" and .switch != "off")] | length' \
  <<< "$catalog") == 0 ]] || fail "non-AI plugins did not default off"
[[ $(jq '[.plugins[] | select(.default == "on-with-ai" and (.switch != "on" or .state != "waiting"))] | length' \
  <<< "$catalog") == 0 ]] || fail "unconfigured AI plugins did not wait"
[[ $(jq '[.plugins[] | select(.kind == "device" and .switch == "on" and .state == "enabled")] | length' \
  <<< "$catalog") == 2 ]] || fail "device plugins did not default on"
[[ $(curl -sf -b "$jars/first" "$url/api/v1/plugins/manifest" | jq '[.plugins[] | select(.kind == "server")] | length') == 0 ]] \
  || fail "fresh instance has enabled server plugin contributions"
expect 200 "enable tasks with exact permissions" POST /api/v1/plugins/tasks/enable \
  -b "$jars/first" -H "Origin: $url" -H 'Content-Type: application/json' \
  -d '{"permissions":["notes.read","annotate"]}'
code_literal=$'\n\n`multiline code\n[ ] literal\n`'
original_body=$'Backup baseline #project/smoke #idea\n\n- [ ] Smoke task\n- [x] Done task'"$code_literal"
original=$(create_note "$original_body")
original_id=$(jq -r .id <<< "$original")
[[ $(jq -r '.labels | index("project/smoke") != null' <<< "$original") == true ]] \
  || fail "inline labels were not derived"
create_note 'Another saved thought #project/smoke' >/dev/null
note_count_before=$(notes)
[[ $note_count_before == 2 ]] || fail "expected 2 saved notes, got $note_count_before"
[[ $(search_count 'baseline #project') == 1 ]] || fail "keyword and nested label search failed"
echo "smoke: ok: notes, inline labels and keyword search"
wait_task_progress '1 of 2 done'
[[ $(task_view_count) == 1 ]] || fail "tasks view omitted the checkbox fixture"
edit_original $'Backup baseline #project/smoke #idea\n\n- [x] Smoke task\n- [x] Done task'"$code_literal"
wait_task_progress '2 of 2 done'
[[ $(task_view_count) == 0 ]] || fail "tasks view retained a completed fixture"
edit_original "$original_body"
wait_task_progress '1 of 2 done'
[[ $(task_view_count) == 1 ]] || fail "tasks view did not restore the reopened fixture"
echo "smoke: ok: all four plugins packaged off; tasks hooks, checkbox edits and view"

snapshot=$(docker exec "$name" spunto backup)
echo "smoke: ok: backup while running wrote $snapshot"
snapshot_count=$(docker exec "$name" python -c \
  'import sqlite3, sys; print(sqlite3.connect(sys.argv[1]).execute("SELECT count(*) FROM note").fetchone()[0])' \
  "$snapshot")
[[ $snapshot_count == "$note_count_before" ]] || fail "snapshot note count differs from the live notes"
snapshot_kv=$(docker exec "$name" python -c \
  'import sqlite3, sys; row=sqlite3.connect(sys.argv[1]).execute("SELECT value FROM plugin_kv WHERE plugin_id=? AND key=?", ("tasks", "host:consecutive_failures")).fetchone(); print(row[0] if row else "missing")' \
  "$snapshot")
[[ $snapshot_kv == 0 ]] || fail "snapshot omitted tasks plugin KV"
expect 200 "disable tasks" POST /api/v1/plugins/tasks/disable \
  -b "$jars/first" -H "Origin: $url"
expect 404 "disabled tasks view" GET '/api/v1/notes?view=tasks/tasks' -b "$jars/first"
[[ $(curl -sf -b "$jars/first" "$url/api/v1/notes/$original_id" \
  | jq '[.annotations[] | select(.plugin_id == "tasks")] | length') == 0 ]] \
  || fail "disabled task annotations remained visible"
[[ $(curl -sf -b "$jars/first" "$url/api/v1/plugins/manifest" | jq '[.plugins[] | select(.kind == "server")] | length') == 0 ]] \
  || fail "disabled tasks contributions remained visible"
create_note 'A note created after backup #later' >/dev/null
expect 200 "append after backup" POST "/api/v1/notes/$original_id/append" \
  -b "$jars/first" -H "Origin: $url" -H 'Content-Type: application/json' -d '{"text":"Added after backup"}'
[[ $(notes) == 3 ]] || fail "the post-backup note was not saved"
sign_in after-backup /api/v1/auth/login "$login"
if docker exec "$name" spunto restore "$snapshot" >/dev/null 2>&1; then
  fail "restore ran while the server was running"
fi
echo "smoke: ok: restore refused while the server runs"

docker stop "$name" >/dev/null
docker run --rm "${run_args[@]}" "$image" spunto restore "$snapshot"
docker start "$name" >/dev/null
wait_ready

after=$(sessions first)
[[ $after == "$before" ]] || fail "expected $before sessions after the restore, got $after"
echo "smoke: ok: the restore brought back $after sessions"
[[ $(notes) == "$note_count_before" ]] || fail "the restore did not bring back the original note count"
[[ $(note_body "$original_id") == "$original_body" ]] || fail "the restore did not bring back the original body"
[[ $(search_count 'baseline #project') == 1 ]] || fail "restored search or labels drifted"
[[ $(search_count '#later') == 0 ]] || fail "a post-backup note survived the restore"
echo "smoke: ok: the restore brought back $note_count_before notes, original text and search"
[[ $(curl -sf -b "$jars/first" "$url/api/v1/plugins/manifest" \
  | jq -c '[.plugins[] | select(.kind == "server") | .id]') == '["tasks"]' ]] || fail "restore did not re-enable tasks"
wait_task_progress '1 of 2 done'
[[ $(task_view_count) == 1 ]] || fail "restored tasks view differs"
restored_kv=$(docker exec "$name" python -c \
  'import sqlite3; row=sqlite3.connect("/data/spunto.db").execute("SELECT value FROM plugin_kv WHERE plugin_id=? AND key=?", ("tasks", "host:consecutive_failures")).fetchone(); print(row[0] if row else "missing")')
[[ $restored_kv == "$snapshot_kv" ]] || fail "restore changed tasks plugin KV"
echo "smoke: ok: restore brought back tasks switch, annotation, view and plugin KV"
expect 401 "a session made after the snapshot" GET /api/v1/credentials -b "$jars/after-backup"
[[ $(epoch) != "$epoch_before" ]] || fail "the restore kept the old sync epoch"
echo "smoke: ok: the restore assigned a new sync epoch"
echo "smoke: PASS"
