"""Four-capability provider fixture; no keys, owner data or external network."""

import asyncio
import json
import re
from collections import deque

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse


class FakeProvider:
    def __init__(self, output=None, modes=()):
        self.output = output or {"ok": True}
        self.modes = deque(modes)
        self.mode = "valid"
        self.response_format = "json_schema"
        self.requests = []
        self.started = asyncio.Event()
        self.on_embed = None
        self.on_chat = None
        self.label_answer = {"labels": [{"path": "project/Spunto", "confidence": 0.95}]}
        self.ask_answer = None
        self.action_plan = None
        self.app = FastAPI()
        self.app.post("/v1/chat/completions")(self.chat)
        self.app.post("/v1/embeddings")(self.embed)
        self.app.post("/v1/audio/transcriptions")(self.transcribe)
        self.app.post("/v1/messages")(self.anthropic)

    @property
    def chat_bodies(self):
        return [
            request["body"]
            for request in self.requests
            if request["path"] == "/v1/chat/completions"
        ]

    async def capture(self, request, json_body=True):
        body = await request.json() if json_body else {}
        self.requests.append(
            {"path": request.url.path, "headers": dict(request.headers), "body": body}
        )
        self.started.set()
        mode = self.modes.popleft() if self.modes else self.mode
        if mode == "slow":
            await asyncio.sleep(0.2)
        return body, mode

    def value(self, mode, output):
        if mode == "bad_fence":
            return "``````"
        if mode == "valid_fence":
            return "```json\n" + json.dumps(output) + "\n```"
        if mode in {"prose", "reject_schema"}:
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
        if mode == "ask_wrong_types":
            return '{"found":"yes","answer":42,"citations":"wrong"}'
        if mode == "ask_invented_ids":
            return '{"found":true,"answer":"Invented","citations":["invented"]}'
        if mode == "label_spaces":
            return '{"labels":[{"path":"has spaces","confidence":0.95}]}'
        return json.dumps(output)

    def answer(self, prompt):
        if isinstance(prompt, str):
            if "Plan a note action" in prompt:
                return self.action_plan or {
                    "kind": "question",
                    "target_ids": [],
                    "selector": "",
                    "body": "",
                    "before": "",
                    "after": "",
                }
            if "Suggest up to three" in prompt:
                return self.label_answer
            if "Question:" in prompt:
                return self.ask_answer or {
                    "found": True,
                    "answer": "Spunto uses SQLite.",
                    "citations": re.findall(r'<note id="([^"]+)"', prompt)[:1],
                }
        return self.output

    async def chat(self, request: Request):
        body, mode = await self.capture(request)
        fmt = body.get("response_format", {})
        if (
            "temperature" in body
            or fmt.get("type") != self.response_format
            or (
                self.response_format == "json_schema"
                and fmt.get("json_schema", {}).get("strict") is not True
            )
        ):
            return JSONResponse(
                {"error": "fixture provider does not support this request format"}, 400
            )
        if mode in {"400", "500", "401", "404"}:
            return JSONResponse({"error": "fixture provider body must never escape"}, int(mode))
        if self.on_chat:
            await self.on_chat()
        return {
            "choices": [
                {
                    "finish_reason": "length" if mode == "max_tokens" else "stop",
                    "message": {
                        "content": self.value(mode, self.answer(body["messages"][1]["content"])),
                        "refusal": "No" if mode == "refusal" else None,
                    },
                }
            ],
            "usage": {"prompt_tokens": "many", "completion_tokens": -1}
            if mode == "bad_usage"
            else {"prompt_tokens": 120, "completion_tokens": 8},
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
        if mode in {
            "wrong_types",
            "prose",
            "ask_wrong_types",
            "ask_invented_ids",
            "label_spaces",
            "reject_schema",
        }:
            data[0]["embedding"] = ["bad"]
        if mode == "nonfinite":
            data[0]["embedding"] = [1e999]
            return JSONResponse({"data": data})  # Not JSON encodable: useful transport failure.
        if self.on_embed:
            await self.on_embed()
        return {"data": data, "usage": {"prompt_tokens": 4 * len(data), "total_tokens": 4}}

    async def transcribe(self, request: Request):
        _, mode = await self.capture(request, False)
        if mode in {"500", "401", "404"}:
            return JSONResponse({"error": "fixture provider body must never escape"}, int(mode))
        return {
            "text": "test\u0000" if mode == "valid" else "loop " * 30,
            "usage": {"type": "tokens", "input_tokens": 30, "output_tokens": 5},
        }

    async def anthropic(self, request: Request):
        body, mode = await self.capture(request)
        if (
            mode == "400"
            or body.get("output_config", {}).get("format", {}).get("type") != "json_schema"
        ):
            return JSONResponse(
                {
                    "type": "error",
                    "error": {
                        "type": "invalid_request_error",
                        "message": "fixture schema rejected",
                    },
                },
                400,
            )
        return {
            "id": "msg_fixture",
            "type": "message",
            "role": "assistant",
            "model": "claude-fixture",
            "content": [{"type": "text", "text": self.value(mode, self.output)}],
            "stop_reason": mode if mode in {"refusal", "max_tokens"} else "end_turn",
            "stop_sequence": None,
            "usage": {"input_tokens": 10, "output_tokens": 10},
        }
