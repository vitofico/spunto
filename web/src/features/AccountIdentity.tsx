import { useQuery } from "@tanstack/react-query";
import { Link } from "wouter";
import { Icon } from "../components/core/Icon";
import { api } from "../lib/api";
import { t, useLanguage } from "../lib/i18n";
import { useAuth } from "../lib/query";
import type { SignedInAccount } from "../lib/types";

export function accountName(account: SignedInAccount | null | undefined) {
  return [account?.display_name, account?.username, account?.email]
    .map((value) => value?.trim())
    .find(Boolean);
}

export function useSignedInAccount() {
  const { session } = useAuth();
  const user = session?.user_id;
  const identity = useQuery({
    queryKey: ["account-identity", user, session?.credential_id],
    queryFn: api.accountIdentity,
    enabled: !!user,
  });
  const account = identity.data?.account;
  return {
    account: user && account?.id === user ? account : null,
    hosted: !!user,
  };
}

export function AccountIdentityLink({ close }: { close: () => void }) {
  useLanguage();
  const { account, hosted } = useSignedInAccount();
  const name =
    accountName(account) ?? t(hosted ? "identity.account" : "identity.owner");
  const label =
    hosted && !accountName(account)
      ? t("identity.account")
      : t("identity.account_named", { name });
  return (
    <Link
      href={hosted ? "/settings/account" : "/settings/profile"}
      onClick={close}
      title={label}
      aria-label={label}
    >
      <Icon name="person" />
      <span className="rail-label account-rail-label">
        <strong>{name}</strong>
        <small>{t("identity.account")}</small>
      </span>
    </Link>
  );
}

export function AccountIdentityDetails() {
  useLanguage();
  const { account, hosted } = useSignedInAccount();
  const name =
    accountName(account) ?? t(hosted ? "identity.account" : "identity.owner");
  return (
    <section className="account-identity" aria-label={t("identity.account")}>
      <p className="muted">
        {hosted && !accountName(account)
          ? t("identity.signed_in")
          : t("identity.signed_in_as", { name })}
      </p>
      {account?.username?.trim() && account.username.trim() !== name && (
        <p>{t("identity.username", { username: account.username.trim() })}</p>
      )}
      <p className="muted">
        {t("identity.server", { server: window.location.origin })}
      </p>
    </section>
  );
}
