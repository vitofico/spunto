"""Four-capability provider fixture; no keys, owner data or external network."""

import asyncio
import json
from collections import deque

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse


class FakeProvider:
    def __init__(self, output=None, modes=()):
        self.output = output or {"ok": True}
        self.modes = deque(modes)
        self.requests = []
        self.started = asyncio.Event()
        self.on_embed = None
        self.app = FastAPI()
        self.app.post("/v1/chat/completions")(self.chat)
        self.app.post("/v1/embeddings")(self.embed)
        self.app.post("/v1/audio/transcriptions")(self.transcribe)
        self.app.post("/v1/messages")(self.anthropic)

    async def capture(self, request, json_body=True):
        body = await request.json() if json_body else {}
        self.requests.append(
            {"path": request.url.path, "headers": dict(request.headers), "body": body}
        )
        self.started.set()
        mode = self.modes.popleft() if self.modes else "valid"
        if mode == "slow":
            await asyncio.sleep(0.1)
        return body, mode

    def value(self, mode):
        if mode == "bad_fence":
            return "``````"
        if mode == "valid_fence":
            return "```json\n" + json.dumps(self.output) + "\n```"
        if mode == "prose":
            return "I refuse to output JSON."
        if mode == "wrong_types":
            return '{"ok":"yes"}'
        if mode == "missing":
            return "{}"
        if mode == "invented_id":
            return '{"note_ids":["invented"],"labels":["idea"]}'
        if mode == "bad_label":
            return '{"note_ids":[],"labels":["bad label"]}'
        if mode == "extra":
            return '{"ok":true,"extra":"hidden"}'
        return json.dumps(self.output)

    async def chat(self, request: Request):
        body, mode = await self.capture(request)
        if mode == "reject_schema" and body["response_format"]["type"] == "json_schema":
            return JSONResponse({"error": "fixture provider body must never escape"}, 400)
        if mode in {"500", "401", "404"}:
            return JSONResponse({"error": "fixture provider body must never escape"}, int(mode))
        return {
            "choices": [
                {
                    "finish_reason": "length" if mode == "max_tokens" else "stop",
                    "message": {
                        "content": self.value(mode),
                        "refusal": "No" if mode == "refusal" else None,
                    },
                }
            ]
        }

    async def embed(self, request: Request):
        body, mode = await self.capture(request)
        if mode == "500":
            return JSONResponse({"error": "fixture provider body must never escape"}, 500)
        data = [{"index": i, "embedding": [1.0, 0.2, 0.3]} for i, _ in enumerate(body["input"])]
        if mode == "oversized":
            data[0]["embedding"] = [1.0] * 8193
        if mode == "bad_dimension":
            data[-1]["embedding"] = [1.0]
        if mode == "wrong_types":
            data[0]["embedding"] = ["bad"]
        if mode == "nonfinite":
            data[0]["embedding"] = [1e999]
            return JSONResponse({"data": data})  # Not JSON encodable: useful transport failure.
        if self.on_embed:
            await self.on_embed()
        return {"data": data}

    async def transcribe(self, request: Request):
        _, mode = await self.capture(request, False)
        if mode in {"500", "401", "404"}:
            return JSONResponse({"error": "fixture provider body must never escape"}, int(mode))
        return {"text": "test\u0000" if mode == "valid" else "loop " * 30}

    async def anthropic(self, request: Request):
        _, mode = await self.capture(request)
        return {
            "id": "msg_fixture",
            "type": "message",
            "role": "assistant",
            "model": "claude-fixture",
            "content": [{"type": "text", "text": self.value(mode)}],
            "stop_reason": mode if mode in {"refusal", "max_tokens"} else "end_turn",
            "stop_sequence": None,
            "usage": {"input_tokens": 10, "output_tokens": 10},
        }
