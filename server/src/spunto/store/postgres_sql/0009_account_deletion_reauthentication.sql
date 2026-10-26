ALTER TABLE public.spunto_account_reauthentication
 DROP CONSTRAINT spunto_account_reauthentication_purpose_check;
ALTER TABLE public.spunto_account_reauthentication ADD CONSTRAINT
 spunto_account_reauthentication_purpose_check CHECK (purpose IN ('local-password','oidc-link','account-delete'));
