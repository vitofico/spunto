-- Provider-reported tokens per call, and monthly AI allowances that every space an
-- account owns draws from. Counters hold no note content.
ALTER TABLE spunto.ai_stat ADD COLUMN input_tokens BIGINT NOT NULL DEFAULT 0;
ALTER TABLE spunto.ai_stat ADD COLUMN output_tokens BIGINT NOT NULL DEFAULT 0;
CREATE TABLE public.spunto_ai_usage (
 user_id TEXT NOT NULL REFERENCES public.spunto_account(id) ON DELETE CASCADE,
 period TEXT NOT NULL,
 action TEXT NOT NULL,
 calls BIGINT NOT NULL CHECK (calls >= 0),
 PRIMARY KEY (user_id,period,action)
);
REVOKE ALL ON public.spunto_ai_usage FROM PUBLIC;
