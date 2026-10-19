-- Local credentials supplement, rather than replace, an account's optional OIDC identity.
ALTER TABLE public.spunto_account ALTER COLUMN issuer DROP NOT NULL;
ALTER TABLE public.spunto_account ALTER COLUMN subject DROP NOT NULL;
ALTER TABLE public.spunto_account ADD CONSTRAINT spunto_account_identity_pair
 CHECK ((issuer IS NULL) = (subject IS NULL));
CREATE TABLE public.spunto_local_credential (
 user_id TEXT PRIMARY KEY REFERENCES public.spunto_account(id) ON DELETE CASCADE,
 username TEXT UNIQUE NOT NULL CHECK (username ~ '^[a-z0-9][a-z0-9._-]{2,63}$'),
 password_hash TEXT NOT NULL, updated_at BIGINT NOT NULL
);
ALTER TABLE public.spunto_login_session ADD COLUMN auth_method TEXT NOT NULL DEFAULT 'oidc'
 CHECK (auth_method IN ('oidc','password'));
CREATE TABLE public.spunto_account_reauthentication (
 session_hash TEXT PRIMARY KEY REFERENCES public.spunto_login_session(token_hash) ON DELETE CASCADE,
 user_id TEXT NOT NULL REFERENCES public.spunto_account(id) ON DELETE CASCADE,
 purpose TEXT NOT NULL CHECK (purpose IN ('local-password','oidc-link')),
 expires_at BIGINT NOT NULL
);
ALTER TABLE public.spunto_oidc_transaction ADD COLUMN purpose TEXT;
ALTER TABLE public.spunto_oidc_transaction ADD COLUMN user_id TEXT;
ALTER TABLE public.spunto_oidc_transaction ADD COLUMN session_hash TEXT;
ALTER TABLE public.spunto_oidc_transaction ADD COLUMN requested_at DOUBLE PRECISION;
CREATE TABLE public.spunto_auth_throttle (
 key TEXT PRIMARY KEY, failures INTEGER NOT NULL, last_failure BIGINT NOT NULL,
 blocked_until BIGINT NOT NULL
);
REVOKE ALL ON public.spunto_local_credential, public.spunto_account_reauthentication,
 public.spunto_auth_throttle FROM PUBLIC;
