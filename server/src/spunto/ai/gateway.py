"""One validated, privacy-checked and accounted boundary for every AI call."""

import asyncio
import base64
import hashlib
import html
import json
import logging
import math
from datetime import UTC, datetime
from pathlib import Path

from pydantic import ValidationError

from spunto.ai.adapters import Anthropic, OpenAI
from spunto.ai.errors import AIError
from spunto.ai.output import Output, Transcript, closed_schema, parse_output
from spunto.ai.passages import evidence, source_passages
from spunto.ai.profiles import CAPABILITIES, Profile
from spunto.ai.queue import ProfileQueue
from spunto.ai.quota import QuotaExceeded, Usage, action


class Probe(Output):
    ok: bool


class Gateway:
    def __init__(
        self, store, settings, *, transport=None, anthropic_client_factory=None, quota=None
    ):
        self.store, self.settings, self.quota = store, settings, quota
        self.transport, self.anthropic_client_factory = transport, anthropic_client_factory
        self.queues = {name: ProfileQueue(p) for name, p in settings.ai_profiles.items()}
        self.adapters = {}
        self.health = []
        self.generation = 0
        from spunto.ai.selection import Selection

        self.selection = Selection(self)

    @property
    def capabilities(self):
        return ["ai." + cap for cap in self.settings.ai_bindings]

    def for_context(self, ctx):
        return ScopedGateway(self, ctx)

    def profile_for(self, capability, plugin_route=None, profile=None):
        capability = capability.removeprefix("ai.")
        if isinstance(profile, Profile):
            configured = self.settings.ai_profiles.get(profile.name)
            if configured is None or profile.base_url != configured.base_url:
                raise AIError("unavailable", "ai_profile_endpoint_changed")
            return profile
        route = plugin_route or {}
        if isinstance(route, str):
            route = json.loads(route)
        name = (
            profile
            or route.get(capability)
            or route.get("ai." + capability)
            or self.settings.ai_bindings.get(capability)
        )
        result = self.settings.ai_profiles.get(name)
        if result is None or not getattr(result, capability + "_model", None):
            raise AIError("unavailable", "ai_capability_unbound")
        return result

    def embedding_profile(self, profile=None):
        return self.profile_for("embed", profile=profile)

    def public_profiles(self):
        return [
            {
                **p.public(),
                "leaves_network": {
                    cap: p.leaves_network(cap) for cap in CAPABILITIES if getattr(p, cap + "_model")
                },
            }
            for p in self.settings.ai_profiles.values()
        ]

    def _adapter(self, profile):
        key = (profile.name, profile.base_url, profile.provider)
        if key not in self.adapters:
            self.adapters[key] = (
                Anthropic(profile, self.anthropic_client_factory)
                if profile.provider == "anthropic"
                else OpenAI(profile, self.transport)
            )
        return self.adapters[key]

    async def close(self):
        for adapter in self.adapters.values():
            await adapter.close()

    async def _notes(
        self, notes, *, external_ok=True, include_evidence=False, source_fingerprints=None
    ):
        ids = [item if isinstance(item, str) else item["id"] for item in notes]
        result = []
        async with self.store.read() as conn:
            for note_id in ids:
                rows = await conn.execute_fetchall(
                    "SELECT id,body,private,machine,origin,trashed_at,created_at,updated_at "
                    "FROM note WHERE id=?",
                    (note_id,),
                )
                if not rows or rows[0]["private"] or rows[0]["machine"] or rows[0]["trashed_at"]:
                    raise AIError("refused", "ai_note_refused")
                if not external_ok and rows[0]["origin"] == "external":
                    raise AIError("refused", "ai_note_refused")
                note = dict(rows[0])
                if include_evidence:
                    parts, fingerprint = await evidence(conn, note)
                    if (
                        source_fingerprints is not None
                        and source_fingerprints[note_id] != fingerprint
                    ):
                        raise AIError("refused", "ai_source_changed")
                    note["evidence_parts"] = parts
                    note["evidence_fingerprint"] = fingerprint
                result.append(note)
        return result

    @staticmethod
    def _fences(notes):
        return "\n".join(
            '<note id="'
            + html.escape(n["id"], quote=True)
            + '" origin="'
            + html.escape(n["origin"], quote=True)
            + '" created_at="'
            + datetime.fromtimestamp(n["created_at"] / 1000, UTC).isoformat()
            + '" updated_at="'
            + datetime.fromtimestamp(n["updated_at"] / 1000, UTC).isoformat()
            + '">'
            + html.escape(n["body"])
            + "</note>"
            for n in notes
        )

    async def _count(self, plugin, capability, outcome, usage):
        day = datetime.now(UTC).date().isoformat()
        async with self.store.write() as conn:
            await conn.execute(
                "INSERT INTO ai_stat(day,plugin_id,capability,outcome,n,input_tokens,"
                "output_tokens) VALUES(?,?,?,?,1,?,?) "
                "ON CONFLICT(day,plugin_id,capability,outcome) DO UPDATE SET "
                "n=ai_stat.n+1,input_tokens=ai_stat.input_tokens+excluded.input_tokens,"
                "output_tokens=ai_stat.output_tokens+excluded.output_tokens",
                (day, plugin, capability, outcome, usage.input_tokens, usage.output_tokens),
            )

    async def _call(
        self, capability, fn, *, plugin_id, priority, profile=None, check=None, quota_seconds=0
    ):
        generation = self.generation
        accounted = False
        usage, reserved = Usage(), None

        def current():
            configured = self.settings.ai_profiles.get(p.name)
            return (
                generation == self.generation
                and configured is not None
                and getattr(configured, capability + "_model") == getattr(p, capability + "_model")
            )

        def unchanged():
            if generation != self.generation:
                raise AIError("unavailable", "ai_configuration_changed")

        try:
            p = self.profile_for(capability, profile=profile)
            queue = self.queues[p.name]
            if check:
                check()
            # Charge the space owner's monthly allowance before the call queues.
            if self.quota and (name := action(capability, plugin_id)):
                reserved = await self.quota.reserve(name, seconds=quota_seconds)
            async with queue.slot(plugin_id, priority):
                unchanged()
                async with asyncio.timeout(p.timeout_s):
                    with usage:
                        result, retried = await fn(p, self._adapter(p))
                unchanged()
                if check:
                    check()
                if current():
                    queue.success()
            await self._count(plugin_id, capability, "retried_ok" if retried else "ok", usage)
            accounted = True
            unchanged()
            return result
        except TimeoutError:
            if current():
                queue.pause()
            await self._count(plugin_id, capability, "provider_error", usage)
            raise AIError("provider", "ai_provider_timeout", retry_after=30) from None
        except AIError as exc:
            if exc.category == "provider":
                if current() and p.name in self.queues:
                    self.queues[p.name].pause()
                    exc.retry_after = max(
                        0, self.queues[p.name].paused_until - self.queues[p.name].clock()
                    )
            if not accounted:
                await self._count(
                    plugin_id,
                    capability,
                    "quota"
                    if isinstance(exc, QuotaExceeded)
                    else {
                        "validation": "invalid",
                        "provider": "provider_error",
                        "refused": "refused",
                        "unavailable": "refused",
                    }[exc.category],
                    usage,
                )
            raise
        finally:
            if reserved and not usage.sent:
                await self.quota.release(*reserved)

    async def _quirk(self, profile, model, value=None):
        key = "ai:quirk:" + hashlib.sha256((profile.base_url + "\0" + model).encode()).hexdigest()
        if value is not None:
            async with self.store.write() as conn:
                await conn.execute(
                    "INSERT INTO meta(key,value) VALUES(?,?) "
                    "ON CONFLICT(key) DO UPDATE SET value=excluded.value",
                    (key, json.dumps(value)),
                )
            return value
        async with self.store.read() as conn:
            rows = await conn.execute_fetchall("SELECT value FROM meta WHERE key=?", (key,))
        return json.loads(rows[0][0]) if rows else {}

    async def _structured(
        self,
        capability,
        output,
        prompt,
        *,
        notes=(),
        purpose="structured",
        plugin_id="core",
        priority=0,
        profile=None,
        check=None,
        image=None,
        mime=None,
        max_note_chars=20000,
        note_char_limits=None,
        context_query=None,
        source_fingerprints=None,
        source_chunks=None,
        conversation_context="",
        review_check=None,
        review_guard=None,
        external_ok=True,
        external_text="",
    ):
        if (
            not isinstance(prompt, str)
            or len(prompt) > 20000
            or not 1 <= max_note_chars <= 100000
            or not isinstance(external_text, str)
            or len(external_text) > 20000
        ):
            raise AIError("validation", "ai_prompt_invalid")
        if external_text and not external_ok:
            raise AIError("refused", "ai_external_refused")
        if (
            not isinstance(conversation_context, str)
            or len(conversation_context) > 6000
            or len(html.escape(conversation_context)) > 6000
            or (conversation_context and (context_query is None or source_fingerprints is None))
        ):
            raise AIError("validation", "ai_context_invalid")
        if (
            context_query is not None
            or source_fingerprints is not None
            or source_chunks is not None
        ):
            notes = tuple(notes)
            note_ids = {item.get("id") if isinstance(item, dict) else item for item in notes}
            if (
                not isinstance(context_query, str)
                or not 1 <= len(context_query) <= 20000
                or not (0 if review_check is not None else 1) <= len(notes) <= 8
                or len(note_ids) != len(notes)
                or (
                    source_fingerprints is not None
                    and (
                        not isinstance(source_fingerprints, dict)
                        or set(source_fingerprints) != note_ids
                        or any(
                            not isinstance(value, str)
                            or len(value) != 64
                            or any(c not in "0123456789abcdef" for c in value)
                            for value in source_fingerprints.values()
                        )
                    )
                )
            ):
                raise AIError("validation", "ai_context_invalid")
            source_fingerprints = (
                dict(source_fingerprints) if source_fingerprints is not None else None
            )
            if source_chunks is not None and (
                not isinstance(source_chunks, dict)
                or not set(source_chunks) <= note_ids
                or any(
                    not isinstance(hints, list)
                    or len(hints) > 3
                    or any(
                        not isinstance(hint, dict)
                        or set(hint) != {"ordinal", "text_hash"}
                        or type(hint["ordinal"]) is not int
                        or hint["ordinal"] < 0
                        or not isinstance(hint["text_hash"], str)
                        or len(hint["text_hash"]) != 64
                        or any(c not in "0123456789abcdef" for c in hint["text_hash"])
                        for hint in hints
                    )
                    for hints in source_chunks.values()
                )
            ):
                raise AIError("validation", "ai_context_invalid")
            source_chunks = {
                note_id: [dict(hint) for hint in hints]
                for note_id, hints in (source_chunks or {}).items()
            }
        if note_char_limits is not None:
            notes = tuple(notes)
            note_ids = {item.get("id") if isinstance(item, dict) else item for item in notes}
            if not isinstance(note_char_limits, dict) or any(
                not isinstance(note_id, str)
                or note_id not in note_ids
                or type(limit) is not int
                or not 1 <= limit <= max_note_chars
                for note_id, limit in note_char_limits.items()
            ):
                raise AIError("validation", "ai_note_budget_invalid")
            note_char_limits = dict(note_char_limits)
        if not isinstance(output, type) or not issubclass(output, Output):
            raise TypeError("AI output models must inherit Output")
        closed_schema(output)

        async def invoke(p, adapter):
            generation = self.generation
            model = getattr(p, capability + "_model")
            quirk = await self._quirk(p, model)
            fallback = False
            invalid = set()
            for attempt in range(2):
                while True:
                    if check:
                        check()
                    actual = await self._notes(
                        notes,
                        external_ok=external_ok,
                        include_evidence=context_query is not None,
                        source_fingerprints=source_fingerprints,
                    )
                    remaining = max_note_chars
                    bounded = []
                    for note in actual:
                        if remaining <= 0:
                            break
                        # Only fresh database evidence supplies note text.
                        limit = (
                            note_char_limits.get(note["id"], remaining)
                            if note_char_limits is not None
                            else remaining
                        )
                        limit = min(remaining, limit)
                        note["body"] = (
                            await asyncio.to_thread(
                                source_passages,
                                note.pop("evidence_parts"),
                                context_query,
                                limit,
                                source_chunks.get(note["id"], ()),
                            )
                            if context_query is not None
                            else note["body"][:limit]
                        )
                        remaining -= len(note["body"])
                        bounded.append(note)
                    actual = bounded
                    authoring = await review_check() if review_check is not None else ""
                    if not isinstance(authoring, str) or len(html.escape(authoring)) > 6000:
                        raise AIError("validation", "ai_context_invalid")
                    if context_query is not None and actual:
                        # Passage selection runs off-loop; reauthorize after it
                        # yields before any previously read text may leave.
                        await self._notes(
                            [note["id"] for note in actual],
                            external_ok=external_ok,
                            include_evidence=True,
                            source_fingerprints={
                                note["id"]: note["evidence_fingerprint"] for note in actual
                            },
                        )
                    if generation != self.generation:
                        raise AIError("unavailable", "ai_configuration_changed")
                    if review_guard:
                        review_guard()
                    if check:
                        check()
                    text = prompt + ("\n" + self._fences(actual) if actual else "")
                    if authoring:
                        text += (
                            '\n<authoring-data untrusted="true">'
                            + html.escape(authoring)
                            + "</authoring-data>"
                            "\nThis is a receipt-bound draft to revise, not factual note evidence."
                        )
                    if conversation_context:
                        text += (
                            "\nConversation data explains follow-up intent only; "
                            'it is not note evidence.\n<conversation-data untrusted="true">'
                            + html.escape(conversation_context)
                            + "</conversation-data>"
                        )
                    if external_text:
                        text += (
                            '\n<external-data origin="external">'
                            + html.escape(external_text)
                            + "</external-data>"
                        )
                    try:
                        raw = await adapter.structured(
                            capability,
                            model,
                            text,
                            output,
                            purpose=purpose,
                            mode=quirk.get("mode", "json_schema"),
                            schema_prompt=bool(quirk) or attempt > 0,
                            retry=attempt > 0,
                            image=image,
                            mime=mime,
                        )
                        value = parse_output(output, raw, {n["id"] for n in actual})
                        return value, attempt > 0 or fallback
                    except AIError as exc:
                        if (
                            exc.status == 400
                            and p.provider == "openai"
                            and not fallback
                            and quirk.get("mode") != "json_object"
                        ):
                            fallback = True
                            quirk = await self._quirk(
                                p, model, {"mode": "json_object", "schema_prompt": True}
                            )
                            continue
                        if exc.category != "validation":
                            raise
                        invalid.add("provider_response")
                    except (ValidationError, ValueError, TypeError) as exc:
                        if isinstance(exc, ValidationError):
                            types = {
                                error["type"]
                                for error in exc.errors(
                                    include_input=False, include_context=False, include_url=False
                                )
                            }
                            invalid.update(
                                {
                                    "json_invalid": "json",
                                    "missing": "missing_fields",
                                    "extra_forbidden": "extra_fields",
                                    "value_error": "meaning",
                                }.get(kind, "wrong_types" if kind.endswith("_type") else "schema")
                                for kind in types
                            )
                        else:
                            invalid.add("meaning")
                        if isinstance(exc, ValidationError) and "missing" in types:
                            quirk = await self._quirk(p, model, {**quirk, "schema_prompt": True})
                    break
            logging.getLogger("spunto.ai").warning(
                "plugin=%s capability=%s code=ai_invalid_output validation=%s",
                plugin_id,
                capability,
                ",".join(sorted(invalid)),
            )
            raise AIError("validation", "ai_invalid_output")

        return await self._call(
            capability, invoke, plugin_id=plugin_id, priority=priority, profile=profile, check=check
        )

    async def chat(self, output, prompt, **kwargs):
        return await self._structured("chat", output, prompt, **kwargs)

    async def vision(self, output, image, mime, *, prompt="Describe the image.", **kwargs):
        if (
            mime not in {"image/png", "image/jpeg", "image/webp", "image/gif"}
            or len(image) > self.settings.max_upload_bytes
        ):
            raise AIError("refused", "ai_image_refused")
        return await self._structured("vision", output, prompt, image=image, mime=mime, **kwargs)

    async def embed(
        self,
        texts,
        *,
        kind="query",
        notes=(),
        plugin_id="core",
        priority=0,
        profile=None,
        check=None,
        external_ok=True,
    ):
        if (
            kind not in {"query", "document"}
            or not 1 <= len(texts) <= 32
            or any(not isinstance(t, str) or len(t) > 100000 for t in texts)
        ):
            raise AIError("validation", "ai_embedding_input_invalid")
        if kind == "document" and not notes:
            raise AIError("refused", "ai_document_notes_required")

        async def invoke(p, adapter):
            if check:
                check()
            await self._notes(notes, external_ok=external_ok)
            prefix = p.embed_query_prefix if kind == "query" else p.embed_document_prefix
            result = await adapter.embed(p.embed_model, [prefix + t for t in texts])
            try:
                if (
                    not result
                    or not 1 <= len(result[0]) <= 8192
                    or any(
                        len(v) != len(result[0])
                        or any(type(x) not in {int, float} or not math.isfinite(x) for x in v)
                        for v in result
                    )
                ):
                    raise ValueError
            except TypeError, ValueError:
                raise AIError("validation", "ai_invalid_embedding") from None
            return result, False

        return await self._call(
            "embed", invoke, plugin_id=plugin_id, priority=priority, profile=profile, check=check
        )

    async def dimension(self, profile=None):
        vector = (await self.embed(["dimension probe"], profile=profile))[0]
        norm = sum(float(value) * float(value) for value in vector)
        if not norm or not math.isfinite(norm):
            raise AIError("validation", "ai_invalid_embedding")
        return len(vector)

    async def transcribe(
        self,
        audio,
        mime,
        *,
        duration_s,
        language=None,
        notes=(),
        plugin_id="core",
        priority=0,
        profile=None,
        check=None,
        external_ok=True,
    ):
        if len(audio) > self.settings.max_upload_bytes or not 0 < duration_s <= 3600:
            raise AIError("refused", "ai_audio_refused")
        measured = 0
        if self.quota and self.quota.policy:
            import io
            import wave

            try:
                with wave.open(io.BytesIO(audio)) as recording:
                    duration_s = recording.getnframes() / recording.getframerate()
                    measured = math.ceil(duration_s)
                    if not 0 < measured <= 3600:
                        raise ValueError
            except wave.Error, ValueError, EOFError:
                raise AIError("refused", "ai_audio_duration_unverified") from None

        async def invoke(p, adapter):
            for attempt in range(2):
                if check:
                    check()
                await self._notes(notes, external_ok=external_ok)
                try:
                    result = await adapter.transcribe(p.transcribe_model, audio, mime, language)
                    value = parse_output(Transcript, result, set())
                    value.text = "".join(
                        c for c in value.text if c in "\n\t" or ord(c) >= 32 and ord(c) != 127
                    )
                    if len(value.text) > max(40, duration_s * 40):
                        raise ValueError
                    words = value.text.casefold().split()
                    for size in range(1, 9):
                        for i in range(max(0, len(words) - size * 4 + 1)):
                            if words[i : i + size] * 4 == words[i : i + size * 4]:
                                raise ValueError
                    return value, attempt > 0
                except AIError as exc:
                    if exc.category != "validation":
                        raise
                except ValidationError, ValueError, TypeError:
                    pass
            raise AIError("validation", "ai_invalid_transcript")

        return await self._call(
            "transcribe",
            invoke,
            plugin_id=plugin_id,
            priority=priority,
            profile=profile,
            check=check,
            quota_seconds=measured,
        )

    async def status(self):
        async with self.store.read() as conn:
            rows = await conn.execute_fetchall("SELECT * FROM ai_stat ORDER BY day DESC")
            quirks = await conn.execute_fetchall(
                "SELECT key,value FROM meta WHERE key LIKE 'ai:quirk:%'"
            )
        return {
            "capabilities": self.capabilities,
            "profiles": self.public_profiles(),
            "bindings": self.settings.ai_bindings,
            "health": self.health,
            "stats": [dict(r) for r in rows],
            "quota": await self.quota.status() if self.quota else None,
            "quirks": [json.loads(r["value"]) for r in quirks],
            "model_selection": self.selection.status(),
        }

    async def health_check(self):
        generation = self.generation
        settings = self.settings
        results = []
        for cap, name in settings.ai_bindings.items():
            if generation != self.generation:
                return self.health
            p = settings.ai_profiles[name]
            if generation == self.generation:
                self.queues[name].paused_until = 0
            row = {
                "capability": cap,
                "profile": name,
                "model": getattr(p, cap + "_model"),
                "leaves_network": p.leaves_network(cap),
                "checked_at": datetime.now(UTC)
                .isoformat(timespec="milliseconds")
                .replace("+00:00", "Z"),
            }
            try:
                if cap == "chat":
                    await self.chat(Probe, 'Return {"ok":true}.', purpose="health", profile=p)
                elif cap == "embed":
                    row["dimension"] = await self.dimension(profile=p)
                elif cap == "vision":
                    image = base64.b64decode(
                        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jiskAAAAASUVORK5CYII="
                    )
                    await self.vision(
                        Probe,
                        image,
                        "image/png",
                        prompt='Return {"ok":true}.',
                        purpose="health",
                        profile=p,
                    )
                else:
                    await self.transcribe(
                        Path(__file__).with_name("health.wav").read_bytes(),
                        "audio/wav",
                        duration_s=1,
                        profile=p,
                    )
                row.update(ok=True, code=None, fix_hint=None)
                quirk = await self._quirk(p, row["model"])
                if quirk:
                    row["fix_hint"] = (
                        "This provider needs the schema in the prompt; answers are still validated."
                    )
            except AIError as exc:
                hint = "Check the configured model and provider endpoint."
                if exc.status in {401, 403}:
                    hint = "Check SPUNTO_AI_" + name.upper() + "_API_KEY."
                elif exc.status == 404:
                    hint = (
                        "Model or endpoint not found; install the configured model "
                        "and check BASE_URL."
                    )
                elif exc.code == "ai_profile_paused":
                    hint = "Provider calls are paused after an outage; retry after the backoff."
                row.update(ok=False, code=exc.code, fix_hint=hint)
            results.append(row)
        if generation == self.generation:
            self.health = results
        return self.health


class ScopedGateway:
    def __init__(self, gateway, ctx):
        self.gateway, self.ctx = gateway, ctx

    def available(self, capability):
        try:
            self._options(capability.removeprefix("ai."), {})
            return True
        except AIError, PermissionError:
            return False

    def _options(self, capability, kwargs):
        ctx = self.ctx
        ctx.check("ai." + capability)
        if "review_check" in kwargs or "review_guard" in kwargs:
            raise AIError("refused", "ai_authoring_refused")
        review_conversation = kwargs.pop("review_conversation", None)
        authoring_target = kwargs.pop("authoring_target", False)
        if review_conversation is not None:
            if ctx.plugin_id != "ask" or capability != "chat" or type(authoring_target) is not bool:
                raise AIError("refused", "ai_authoring_refused")
            ctx.check("notes.review")
            from spunto.plugins.ask.writes import review_checker

            kwargs["review_check"], kwargs["review_guard"] = review_checker(
                ctx, review_conversation, authoring_target
            )
        elif authoring_target:
            raise AIError("refused", "ai_authoring_refused")
        prompt = kwargs.get("prompt", "")
        note_ids = {
            note.get("id") if isinstance(note, dict) else note for note in kwargs.get("notes", ())
        }
        for note_id, body in getattr(ctx, "_borrowed_notes", {}).items():
            if isinstance(body, dict):
                body = body.get("body", "")
            if body and body in prompt:
                raise AIError("refused", "ai_unfenced_note")
            if body and body in kwargs.get("external_text", "") and note_id not in note_ids:
                raise AIError("refused", "ai_unfenced_note")
            if body and body in kwargs.get("conversation_context", "") and note_id not in note_ids:
                raise AIError("refused", "ai_unfenced_note")
        route = ctx._host.states[ctx.plugin_id].get("ai_route") or {}
        profile = self.gateway.profile_for(capability, route)
        return {
            **kwargs,
            "profile": profile,
            "plugin_id": ctx.plugin_id,
            "priority": kwargs.get("priority", 2 if ctx.unattended else 0),
            "check": lambda: ctx.check("ai." + capability),
            "external_ok": getattr(ctx, "principal", None) is None or ctx.principal.external_ok,
        }

    async def chat(self, output, prompt, **kwargs):
        options = self._options("chat", {**kwargs, "prompt": prompt})
        return await self.gateway.chat(output, **options)

    async def embed(self, texts, **kwargs):
        options = self._options("embed", kwargs)
        for body in getattr(self.ctx, "_borrowed_notes", {}).values():
            body = body.get("body", "") if isinstance(body, dict) else body
            if (
                kwargs.get("kind", "query") == "query"
                and body
                and any(body in text for text in texts)
            ):
                raise AIError("refused", "ai_unfenced_note")
        return await self.gateway.embed(texts, **options)

    async def vision(self, output, image, mime, **kwargs):
        return await self.gateway.vision(output, image, mime, **self._options("vision", kwargs))

    async def transcribe(self, audio, mime, **kwargs):
        return await self.gateway.transcribe(audio, mime, **self._options("transcribe", kwargs))
