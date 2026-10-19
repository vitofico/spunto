"""Workspace-scoped model choices; connections and credentials remain operator-owned."""

import asyncio
import hashlib
import json
import uuid

from spunto.ai.errors import AIError
from spunto.ai.models import inventory
from spunto.ai.profiles import CAPABILITIES

KEY = "ai_model_selection"


class Selection:
    def __init__(self, gateway):
        self.gateway = gateway
        self.environment = gateway.settings
        self.raw = None
        self.selections = {}
        self.revision = hashlib.sha256(
            json.dumps(
                {"profiles": gateway.public_profiles(), "bindings": gateway.settings.ai_bindings},
                sort_keys=True,
            ).encode()
        ).hexdigest()
        self.environment_revision = self.revision
        self.lock = asyncio.Lock()
        self.on_change = None

    def status(self):
        return {
            "revision": self.revision,
            "selections": self.selections,
            "defaults": {
                cap: {
                    "profile": name,
                    "model": getattr(self.environment.ai_profiles[name], cap + "_model"),
                }
                for cap, name in self.environment.ai_bindings.items()
            },
        }

    def effective(self, selections):
        profiles = dict(self.environment.ai_profiles)
        bindings = dict(self.environment.ai_bindings)
        for cap, choice in selections.items():
            name = choice["profile"]
            if name not in profiles:
                bindings.pop(cap, None)  # Removed operator endpoint: fail only this binding closed.
                continue
            profiles[name] = profiles[name].with_model(cap, choice["model"])
            bindings[cap] = name
        return self.environment.model_copy(
            update={
                "ai_profiles": profiles,
                **{"ai_" + cap + "_profile": bindings.get(cap) for cap in CAPABILITIES},
            }
        )

    def _apply(self, raw):
        if raw == self.raw:
            return False
        value = json.loads(raw) if raw is not None else {}
        selections = value.get("selections", {})
        settings = self.effective(selections)
        self.raw, self.selections = raw, selections
        self.revision = (
            hashlib.sha256(
                (self.environment_revision + "\0" + value["revision"]).encode()
            ).hexdigest()
            if raw is not None
            else self.environment_revision
        )
        self.gateway.settings = settings
        self.gateway.generation += 1
        self.gateway.health = []
        for queue in self.gateway.queues.values():
            queue.success()
        if self.on_change:
            self.on_change()
        return True

    async def _load(self):
        async with self.gateway.store.read() as conn:
            rows = await conn.execute_fetchall("SELECT value FROM setting WHERE key=?", (KEY,))
        return self._apply(rows[0][0] if rows else None)

    async def load(self):
        async with self.lock:
            return await self._load()

    async def models(self, profile=None):
        names = [profile] if profile is not None else sorted(self.environment.ai_profiles)
        if any(name not in self.environment.ai_profiles for name in names):
            raise AIError("validation", "ai_model_invalid")

        async def one(name):
            try:
                models = await inventory(
                    self.environment.ai_profiles[name], transport=self.gateway.transport
                )
                return {"profile": name, "models": models, "error": None}
            except AIError:
                return {"profile": name, "models": [], "error": "ai_inventory_unavailable"}

        return {"profiles": await asyncio.gather(*(one(name) for name in names))}

    async def save(self, revision, selections):
        async with self.lock:
            await self._load()
            if revision != self.revision:
                raise AIError("refused", "ai_selection_changed")
            changed = {
                cap: choice
                for cap, choice in selections.items()
                if self.selections.get(cap) != choice
            }
            for name in sorted({choice["profile"] for choice in changed.values()}):
                result = (await self.models(name))["profiles"][0]
                if result["error"]:
                    raise AIError("provider", "ai_inventory_unavailable")
                available = {row["id"]: row for row in result["models"]}
                for cap, choice in changed.items():
                    if choice["profile"] != name:
                        continue
                    model = available.get(choice["model"])
                    if model is None or (
                        model["capabilities"] is not None and cap not in model["capabilities"]
                    ):
                        raise AIError("validation", "ai_model_invalid")
                    if self.environment.ai_profiles[name].provider == "anthropic" and cap not in {
                        "chat",
                        "vision",
                    }:
                        raise AIError("validation", "ai_model_invalid")
            # An embedding selection must produce a valid dimension before persistence.
            if "embed" in changed:
                from spunto.ai.gateway import Gateway

                candidate = Gateway(
                    self.gateway.store,
                    self.effective(selections),
                    transport=self.gateway.transport,
                    anthropic_client_factory=self.gateway.anthropic_client_factory,
                )
                try:
                    await candidate.dimension()
                finally:
                    await candidate.close()
            value = json.dumps(
                {"revision": uuid.uuid4().hex, "selections": selections}, sort_keys=True
            )
            async with self.gateway.store.write() as conn:
                if self.raw is None:
                    rows = await conn.execute_fetchall(
                        "INSERT INTO setting(key,value) VALUES(?,?) ON CONFLICT(key) "
                        "DO NOTHING RETURNING value",
                        (KEY, value),
                    )
                else:
                    rows = await conn.execute_fetchall(
                        "UPDATE setting SET value=? WHERE key=? AND value=? RETURNING value",
                        (value, KEY, self.raw),
                    )
                if not rows:
                    raise AIError("refused", "ai_selection_changed")
                if self.on_change:
                    # Fence a queued old activation before this writer releases
                    # the committed choice. No settings/index bytes change here.
                    self.on_change()
            return self._apply(value)
