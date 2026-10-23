import { t, useLanguage } from "../lib/i18n";
import { useEffect, useMemo, useRef, useState } from "react";
import qrcode from "qrcode-generator";
import { api, type PairingCode } from "../lib/api";
import { Dialog } from "./Dialog";
import { copyText } from "./copy";
import styles from "./PairDevice.module.css";

export function pairingPayload(pairing: PairingCode): string {
  // ASCII JSON also round-trips international URLs through QR readers.
  return JSON.stringify({
    server: pairing.public_url,
    code: pairing.code,
  }).replace(
    /[^\x20-\x7e]/g,
    (character) =>
      `\\u${character.charCodeAt(0).toString(16).padStart(4, "0")}`,
  );
}

function PairingQR({ payload }: { payload: string }) {
  useLanguage();
  const { path, size } = useMemo(() => {
    const qr = qrcode(0, "M");
    qr.addData(payload);
    qr.make();
    const count = qr.getModuleCount();
    let path = "";
    for (let row = 0; row < count; row++)
      for (let column = 0; column < count; column++)
        if (qr.isDark(row, column)) path += `M${column + 4} ${row + 4}h1v1h-1z`;
    return { path, size: count + 8 };
  }, [payload]);
  return (
    <svg
      className={styles.qr}
      role="img"
      aria-label={t("pairdevice.android_pairing_code")}
      viewBox={`0 0 ${size} ${size}`}
      shapeRendering="crispEdges"
    >
      <rect width={size} height={size} fill="white" />
      <path d={path} fill="black" />
    </svg>
  );
}

export function PairDevice() {
  useLanguage();
  const [pairing, setPairing] = useState<PairingCode | null>(null);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [copyStatus, setCopyStatus] = useState("");
  const [now, setNow] = useState(Date.now);
  const generation = useRef(0);
  const trigger = useRef<HTMLButtonElement>(null);
  const expires = pairing ? Date.parse(pairing.expires_at) : 0;
  const expired = !!pairing && (!Number.isFinite(expires) || expires <= now);
  useEffect(() => {
    return () => {
      generation.current++;
    };
  }, []);
  useEffect(() => {
    if (!pairing) return;
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, [pairing]);
  async function create() {
    const current = ++generation.current;
    setPending(true);
    setError(null);
    setCopyStatus("");
    try {
      const code = await api.createPairing();
      if (current !== generation.current) return;
      setNow(Date.now());
      setPairing(code);
    } catch (error) {
      if (current !== generation.current) return;
      setError(
        error instanceof Error
          ? error.message
          : t("pairdevice.could_not_create_a_pairing_code"),
      );
    } finally {
      if (current === generation.current) setPending(false);
    }
  }
  function close() {
    generation.current++;
    setPending(false);
    setPairing(null);
    setError(null);
  }
  const minutes = Math.max(1, Math.ceil((expires - now) / 60000));
  const payload = pairing ? pairingPayload(pairing) : "";
  return (
    <>
      <button ref={trigger} onClick={() => void create()} disabled={pending}>
        {pending
          ? t("pairdevice.creating_code")
          : t("pairdevice.pair_an_android_device")}
      </button>
      {error && <p role="alert">{error}</p>}
      {pairing && (
        <Dialog
          title={t("pairdevice.pair_an_android_device")}
          opener={trigger.current}
          onClose={close}
          footer={<button onClick={close}>{t("app.close")}</button>}
        >
          {expired ? (
            <>
              <p>{t("pairdevice.this_pairing_code_expired")}</p>
              <button onClick={() => void create()} disabled={pending}>
                {t("pairdevice.create_a_new_code")}
              </button>
            </>
          ) : (
            <>
              <p>{t("pairdevice.open_spunto_on_your_phone_and_choose_scan")}</p>
              <PairingQR payload={payload} />
              <p>{t("pairdevice.connection", { url: pairing.public_url })}</p>
              <p>{t("pairdevice.expiry", { count: minutes })}</p>
              <button
                onClick={async () => {
                  if (Date.parse(pairing.expires_at) <= Date.now()) {
                    setNow(Date.now());
                    return;
                  }
                  setCopyStatus(
                    (await copyText(payload))
                      ? t("pairdevice.copied")
                      : t("pairdevice.copy_failed_scan_the_code_instead"),
                  );
                }}
              >
                {t("pairdevice.copy_pairing_code")}
              </button>
              <p role="status">{copyStatus}</p>
            </>
          )}
        </Dialog>
      )}
    </>
  );
}
