"""Only stable, content-free codes cross the provider boundary."""


class AIError(Exception):
    def __init__(self, category, code=None, *, retry_after=None, status=None):
        self.category = category
        self.code = code or "ai_" + category
        self.retry_after = retry_after
        self.status = status
        super().__init__(self.code)
