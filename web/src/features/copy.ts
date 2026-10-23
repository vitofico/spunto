import { t } from "../lib/i18n";
/** Copy only on an owner action; the temporary field also works on plain-HTTP LANs. */
export async function copyText(value: string): Promise<boolean> {
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(value);
      return true;
    }
  } catch {
    /* A denied clipboard can still use the explicit copy gesture. */
  }
  const previous = document.activeElement;
  const field = document.createElement("textarea");
  field.className = "copy-helper";
  field.value = value;
  field.setAttribute("aria-label", t("copy.text_to_copy"));
  document.body.append(field);
  field.select();
  try {
    return document.execCommand("copy");
  } catch {
    return false;
  } finally {
    field.value = "";
    field.remove();
    if (previous instanceof HTMLElement) previous.focus();
  }
}
