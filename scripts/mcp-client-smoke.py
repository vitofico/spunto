#!/usr/bin/env python3
"""Run real Codex capture/recall against an isolated local Spunto server.

From the repository root: uv run --project server python scripts/mcp-client-smoke.py
Requires an already authenticated codex CLI. Never changes its global configuration.
"""

import argparse
import json
import os
import re
import secrets
import shutil
import signal
import socket
import sqlite3
import subprocess
import sys
import tempfile
import time
import uuid
from contextlib import ExitStack
from pathlib import Path

import httpx


def stop(process: subprocess.Popen) -> None:
    if process.poll() is None:
        os.killpg(process.pid, signal.SIGTERM)
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL)
            process.wait(timeout=5)


def redact(directory: Path, values: list[str]) -> None:
    for path in directory.iterdir():
        if path.is_file():
            text = path.read_text(errors="replace")
            for value in values:
                text = text.replace(value, "[REDACTED]")
            path.write_text(text)


def run(output: Path, timeout: int) -> dict:
    cli = shutil.which("codex")
    if not cli:
        raise RuntimeError("Install and authenticate the Codex CLI before running this check.")
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        port = listener.getsockname()[1]
    url = f"http://127.0.0.1:{port}"
    output.mkdir(parents=True, exist_ok=True)
    output.chmod(0o700)
    secret_values = [secrets.token_urlsafe(32)]
    marker = uuid.uuid4().hex
    body = f"Synthetic Spunto capture and recall verification {marker} #verification"
    request_id = str(uuid.uuid4())
    result = {
        "client": "Codex CLI",
        "transport": "real loopback HTTP",
        "synthetic_data_only": True,
        "temporary_server": True,
        "global_configuration_changed": False,
        "claude_model_check": "pending: CLI not logged in",
        "status": "failed",
        "timeout_seconds": timeout,
    }
    server = client_process = None
    try:
        with (
            tempfile.TemporaryDirectory(prefix="spunto-client-e2e-") as temporary,
            ExitStack() as processes,
        ):
            directory = Path(temporary)
            data = directory / "data"
            empty = directory / "workspace"
            empty.mkdir()
            environment = {
                key: value for key, value in os.environ.items() if not key.startswith("SPUNTO_")
            }
            environment.update(
                SPUNTO_PUBLIC_URL=url,
                SPUNTO_PORT=str(port),
                SPUNTO_DATA_DIR=str(data),
                SPUNTO_OWNER_PASSWORD=secret_values[0],
                SPUNTO_LOG_FORMAT="json",
                SPUNTO_MCP_ENABLED="true",
            )
            with (output / "server.log").open("w") as server_log:
                server = subprocess.Popen(
                    [
                        sys.executable,
                        "-c",
                        "from spunto.cli import main; raise SystemExit(main(['serve']))",
                    ],
                    env=environment,
                    stdout=server_log,
                    stderr=subprocess.STDOUT,
                    start_new_session=True,
                )
                processes.callback(stop, server)
                with httpx.Client(base_url=url, timeout=3) as http:
                    for _ in range(200):
                        if server.poll() is not None:
                            raise RuntimeError(
                                "The temporary server stopped before becoming ready."
                            )
                        try:
                            if http.get("/readyz").status_code == 200:
                                break
                        except httpx.TransportError:
                            pass
                        time.sleep(0.1)
                    else:
                        raise RuntimeError(
                            "The temporary server did not become ready within 20 seconds."
                        )
                    login = http.post("/api/v1/auth/login", json={"password": secret_values[0]})
                    if login.status_code != 204:
                        raise RuntimeError("The temporary owner's login failed.")
                    secret_values.extend(http.cookies.values())
                    minted = http.post(
                        "/api/v1/tokens",
                        headers={"Origin": url},
                        json={
                            "name": "Codex local verification",
                            "client": "codex",
                            "scope": "write",
                        },
                    )
                    if minted.status_code != 201:
                        raise RuntimeError("Creating the temporary scoped token failed.")
                    credential = minted.json()
                    secret_values.append(credential["token"])
                prompt = (
                    "Use only the Spunto MCP tools; do not use shell or other tools. "
                    "All data here is "
                    "synthetic verification data. In this ONE session, first call create_note with "
                    f"body {json.dumps(body)} and request_id {json.dumps(request_id)}. "
                    f"Then call search_notes with query {json.dumps(marker)} and mode keyword. "
                    "Then call get_notes with the returned ID, and verify the full saved body is "
                    "exactly the original body. Do not create a second note. Finish with exactly "
                    "SPUNTO_CAPTURE_RECALL_OK followed by the saved note ID."
                )
                arguments = [
                    cli,
                    "exec",
                    "--ephemeral",
                    "--skip-git-repo-check",
                    "--ignore-user-config",
                    "-s",
                    "read-only",
                    "-C",
                    str(empty),
                    "--json",
                    "--color",
                    "never",
                    "-o",
                    str(output / "final.txt"),
                    "-c",
                    "mcp_servers.spunto.url=" + json.dumps(url + "/mcp"),
                    "-c",
                    'mcp_servers.spunto.bearer_token_env_var="SPUNTO_TOKEN"',
                    "-c",
                    'model_reasoning_effort="low"',
                    prompt,
                ]
                client_environment = dict(os.environ)
                client_environment["SPUNTO_TOKEN"] = credential["token"]
                print(
                    "Temporary server ready; running real Codex capture and recall",
                    flush=True,
                )
                with (
                    (output / "codex.jsonl").open("w") as stdout,
                    (output / "codex.log").open("w") as stderr,
                ):
                    # The PATH executable and fixed arguments run without a shell or user code.
                    client_process = subprocess.Popen(  # noqa: S603
                        arguments,
                        env=client_environment,
                        stdin=subprocess.DEVNULL,
                        stdout=stdout,
                        stderr=stderr,
                        start_new_session=True,
                    )
                    processes.callback(stop, client_process)
                    try:
                        return_code = client_process.wait(timeout=timeout)
                    except subprocess.TimeoutExpired:
                        stop(client_process)
                        result.update(
                            status="timeout",
                            reason="Codex exceeded the bounded timeout.",
                        )
                        return result
                result["client_exit_code"] = return_code
                if return_code:
                    result.update(
                        status="client_failed",
                        reason="The real Codex invocation failed; inspect redacted CLI logs.",
                    )
                    return result
                with sqlite3.connect(data / "spunto.db") as connection:
                    notes = connection.execute(
                        "SELECT id,body,source_kind,source_ref,origin,created_by FROM note"
                    ).fetchall()
                    client = connection.execute(
                        "SELECT client FROM credential WHERE id=?", (credential["id"],)
                    ).fetchone()[0]
                if len(notes) != 1 or notes[0][1] != body:
                    raise RuntimeError("Codex did not leave exactly one complete synthetic note.")
                note_id, _, kind, source, origin, created_by = notes[0]
                if (kind, source, origin, created_by, client) != (
                    "agent",
                    "Codex local verification",
                    "agent",
                    credential["id"],
                    "codex",
                ):
                    raise RuntimeError("The note's agent/token provenance is incorrect.")
                stop(server)
                server_log.flush()
                logged = (output / "server.log").read_text()
                calls = {
                    name: len(re.findall(r"mcp_tool tool=" + name + r"\b", logged))
                    for name in ("create_note", "search_notes", "get_notes")
                }
                if not all(calls.values()):
                    raise RuntimeError(
                        "Server logs do not confirm capture, search and get in this run."
                    )
                if marker in logged or any(value in logged for value in secret_values):
                    raise RuntimeError("Server logs contain synthetic note content or a secret.")
                final = (output / "final.txt").read_text().strip()
                if final != "SPUNTO_CAPTURE_RECALL_OK " + note_id:
                    raise RuntimeError("Codex did not confirm exact saved-body recall.")
                result.update(
                    status="passed",
                    note_count=1,
                    source_kind=kind,
                    origin=origin,
                    token_client=client,
                    server_tool_calls=calls,
                    server_logs_contain_content_or_secrets=False,
                )
                return result
    finally:
        if client_process is not None:
            stop(client_process)
        if server is not None:
            stop(server)
        redact(output, secret_values)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--timeout", type=int, default=180)
    args = parser.parse_args()
    if not 10 <= args.timeout <= 180:
        parser.error("Timeout must be 10 to 180 seconds.")
    output = Path(tempfile.mkdtemp(prefix="spunto-client-e2e-"))
    try:
        result = run(output, args.timeout)
    except (OSError, RuntimeError, httpx.HTTPError) as exc:
        result = {"status": "failed", "reason": str(exc)}
    output.mkdir(parents=True, exist_ok=True)
    (output / "results.json").write_text(json.dumps(result, indent=2) + "\n")
    print(f"{result['status'].upper()}; redacted logs and results: {output}", flush=True)
    return 0 if result["status"] == "passed" else 1


if __name__ == "__main__":
    raise SystemExit(main())
