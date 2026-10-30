"""Only stable, content-free codes cross the provider boundary."""


class AIError(Exception):
    def __init__(self, category, code=None, *, retry_after=None, status=None):
        self.category = category
        self.code = code or "ai_" + category
        self.retry_after = retry_after
        self.status = status
        super().__init__(self.code)

    @property
    def detail(self):
        """Useful owner guidance without provider response bodies or note content."""
        if self.code == "ai_source_changed":
            return "A source changed while the answer was being prepared. Ask again."
        if self.code == "ai_note_refused":
            return "AI cannot use a private, trashed or machine-written note."
        if self.code == "ai_configuration_changed":
            return "AI models changed while this action was running. Run it again."
        if self.code == "ai_capability_unbound":
            return "Choose a model for this capability in Settings → AI."
        if self.code == "ai_profile_paused":
            return "The AI provider is recovering from an outage. Wait briefly and try again."
        if self.code == "ai_provider_timeout":
            return (
                "The AI provider took too long. "
                "Try again or choose a faster model in Settings → AI."
            )
        if self.status in {401, 403}:
            return (
                "The AI provider rejected its credentials. "
                "Ask the server operator to check the provider key."
            )
        if self.status == 404:
            return "The AI model or endpoint was not found. Check the model in Settings → AI."
        if self.status == 429:
            return "The AI provider reached its usage limit. Wait before trying again."
        if self.category == "validation":
            return (
                "The model returned an invalid answer. "
                "Try again or choose another model in Settings → AI."
            )
        if self.category == "refused":
            return "The model could not answer this request. Try another model in Settings → AI."
        return (
            "The AI provider is unavailable. Check its connection in Settings → AI and try again."
        )
