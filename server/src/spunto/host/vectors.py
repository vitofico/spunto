"""Embedding access bound to the plugin generation, route and caller scope."""

from spunto.ai.errors import AIError
from spunto.host.plugin import ContextError


class ScopedVectors:
    def __init__(self, ctx):
        self.ctx = ctx

    async def nearest(self, *, note_id=None, text=None, k=50):
        ctx, host = self.ctx, self.ctx._host
        ctx.check("ai.embed")
        if text is not None and any(body and body in text for body in ctx._borrowed_notes.values()):
            raise AIError("refused", "ai_unfenced_note")
        if host.vectors is None or host.gateway is None:
            raise ContextError("ai_unavailable")
        profile = host.gateway.profile_for("embed", host.states[ctx.plugin_id].get("ai_route"))
        result = await host.vectors.nearest(
            note_id=note_id,
            text=text,
            k=k,
            profile=profile.name,
            plugin_id=ctx.plugin_id,
            external_ok=ctx.principal is None or ctx.principal.external_ok,
            guard=lambda: ctx.check("ai.embed"),
        )
        ctx.check("ai.embed")
        return result
