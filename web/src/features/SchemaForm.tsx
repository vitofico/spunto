import { t, useLanguage } from "../lib/i18n";
import { useId, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { api } from "../lib/api";
import type { Args, Json, Schema } from "../lib/types";
import { choiceLabel, humanize } from "./format";
import styles from "./Feature.module.css";
import { validSchema } from "./validSchema";
export { validSchema } from "./validSchema";
export function validateSettings(schema: Schema, values: Args): string | null {
  if (!validSchema(schema))
    return t("schemaform.this_settings_schema_is_unavailable");
  const properties = schema.properties ?? {};
  if (Object.keys(values).some((key) => !Object.hasOwn(properties, key)))
    return t("schemaform.unknown_setting");
  for (const [key, prop] of Object.entries(properties)) {
    if (prop["x-secret"]) continue;
    const value = values[key];
    if (value === undefined) {
      if (schema.required?.includes(key))
        return t("schemaform.value1_is_required", {
          value1: prop.title ?? humanize(key),
        });
      continue;
    }
    const type = prop.type;
    const valid =
      type === "string"
        ? typeof value === "string"
        : type === "boolean"
          ? typeof value === "boolean"
          : type === "array"
            ? Array.isArray(value) && value.every((x) => typeof x === "string")
            : typeof value === "number" &&
              Number.isFinite(value) &&
              (type !== "integer" || Number.isInteger(value));
    if (!valid || (prop.enum && !prop.enum.includes(value)))
      return t("schemaform.value1_has_an_invalid_value", {
        value1: prop.title ?? humanize(key),
      });
    if (
      typeof value === "number" &&
      (value < (prop.minimum ?? -Infinity) ||
        value > (prop.maximum ?? Infinity))
    )
      return t("schemaform.value1_is_outside_its_range", {
        value1: prop.title ?? humanize(key),
      });
    if (
      typeof value === "string" &&
      ([...value].length < (prop.minLength ?? 0) ||
        [...value].length > (prop.maxLength ?? 100000))
    )
      return t("schemaform.value1_has_an_invalid_length", {
        value1: prop.title ?? humanize(key),
      });
    if (
      typeof value === "string" &&
      (prop as Record<string, unknown>).format === "time" &&
      !/^([01]\d|2[0-3]):[0-5]\d$/.test(value)
    )
      return t("schemaform.value1_must_be_a_time", {
        value1: prop.title ?? humanize(key),
      });
  }
  return null;
}
function StringList({
  value,
  onChange,
  label,
  disabled,
  labels = false,
}: {
  value: string[];
  onChange: (value: string[]) => void;
  label: string;
  disabled: boolean;
  labels?: boolean;
}) {
  useLanguage();
  const options = useQuery({
    queryKey: ["labels"],
    queryFn: api.labels,
    enabled: labels,
  });
  const listId = useId();
  const [entry, setEntry] = useState("");
  return (
    <div>
      <div className={styles.chips}>
        {value.map((text, i) => (
          <span className={styles.chip} key={`${text}-${i}`}>
            {text}{" "}
            <button
              type="button"
              aria-label={t("schemaform.remove_value1", { value1: text })}
              disabled={disabled}
              onClick={() => onChange(value.filter((_, index) => index !== i))}
            >
              ×
            </button>
          </span>
        ))}
      </div>
      {labels && (
        <datalist id={listId}>
          {options.data?.labels.map((item) => (
            <option key={item.path} value={item.path} />
          ))}
        </datalist>
      )}
      <div className={styles.row}>
        <input
          className="field"
          aria-label={t("schemaform.add_value1", { value1: label })}
          list={labels ? listId : undefined}
          value={entry}
          disabled={disabled}
          onChange={(e) => setEntry(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Enter") {
              e.preventDefault();
              if (entry.trim() && !value.includes(entry.trim()))
                onChange([...value, entry.trim()]);
              setEntry("");
            }
          }}
        />
        <button
          type="button"
          disabled={disabled || !entry.trim()}
          onClick={() => {
            if (!value.includes(entry.trim()))
              onChange([...value, entry.trim()]);
            setEntry("");
          }}
        >
          {t("schemaform.add")}
        </button>
      </div>
    </div>
  );
}
export function SchemaForm({
  schema,
  initial = {},
  pluginId = "",
  secrets = {},
  onSave,
  disabled = false,
  busy = false,
  submitLabel = t("schemaform.save_settings"),
  hiddenFields = [],
}: {
  schema: Schema;
  initial?: Args;
  pluginId?: string;
  secrets?: Record<string, { set: boolean; source: string }>;
  onSave: (values: Args) => void;
  disabled?: boolean;
  busy?: boolean;
  submitLabel?: string;
  hiddenFields?: string[];
}) {
  useLanguage();
  const id = useId();
  const [values, setValues] = useState<Args>(
    () =>
      Object.fromEntries(
        Object.entries(schema.properties ?? {})
          .filter(([, prop]) => !prop["x-secret"])
          .map(([key, prop]) => [
            key,
            initial[key] ??
              prop.default ??
              (prop.type === "boolean"
                ? false
                : prop.type === "array"
                  ? []
                  : prop.type === "number" || prop.type === "integer"
                    ? (prop.minimum ?? 0)
                    : ""),
          ]),
      ) as Args,
  );
  const [error, setError] = useState<string | null>(null);
  if (!validSchema(schema))
    return (
      <p className="error" role="alert">
        {t("schemaform.this_settings_schema_is_unavailable")}
      </p>
    );
  return (
    <form
      onSubmit={(e) => {
        e.preventDefault();
        const issue = validateSettings(schema, values);
        setError(issue);
        if (!issue) onSave(values);
      }}
    >
      {Object.entries(schema.properties ?? {}).map(([key, prop]) => {
        if (hiddenFields.includes(key)) return null;
        const label = prop.title ?? humanize(key);
        const fieldId = `${id}-${key}`;
        const value = values[key];
        const extra = prop as Record<string, unknown>;
        const update = (next: Json) =>
          setValues((prior) => ({ ...prior, [key]: next }));
        if (prop["x-secret"])
          return (
            <div className={styles.formField} key={key}>
              <span>{label}</span>
              <span className={styles.secret}>
                {t("schemaform.set_in_the_environment")}
                {secrets[key]?.set
                  ? t("schemaform.set")
                  : t("schemaform.not_set")}
                <br />
                <code>
                  SPUNTO_PLUGIN_{pluginId.replaceAll("-", "_").toUpperCase()}_
                  {key.toUpperCase()}
                </code>
              </span>
              {prop.description && <small>{prop.description}</small>}
            </div>
          );
        return (
          <div className={styles.formField} key={key}>
            <label htmlFor={fieldId}>{label}</label>
            {prop.enum ? (
              prop.enum.length <= 3 ? (
                <div
                  className={styles.segments}
                  role="group"
                  aria-label={label}
                >
                  {prop.enum.map((choice, i) => (
                    <button
                      type="button"
                      key={i}
                      disabled={disabled || busy}
                      aria-pressed={value === choice}
                      onClick={() => update(choice)}
                    >
                      {choiceLabel(choice)}
                    </button>
                  ))}
                </div>
              ) : (
                <select
                  className="field"
                  id={fieldId}
                  value={prop.enum.findIndex((x) => x === value)}
                  disabled={disabled || busy}
                  onChange={(e) => update(prop.enum![Number(e.target.value)])}
                >
                  {prop.enum.map((choice, i) => (
                    <option key={i} value={i}>
                      {choiceLabel(choice)}
                    </option>
                  ))}
                </select>
              )
            ) : prop.type === "boolean" ? (
              <input
                id={fieldId}
                type="checkbox"
                className={styles.switch}
                role="switch"
                checked={value === true}
                disabled={disabled || busy}
                onChange={(e) => update(e.target.checked)}
              />
            ) : prop.type === "array" ? (
              <StringList
                label={label}
                labels={
                  (prop.items as Record<string, unknown>)?.format === "label" ||
                  extra.format === "label"
                }
                value={
                  Array.isArray(value)
                    ? value.filter((x): x is string => typeof x === "string")
                    : []
                }
                onChange={update}
                disabled={disabled || busy}
              />
            ) : prop.type === "string" &&
              (prop.maxLength ?? 0) > 280 &&
              !prop.format ? (
              <textarea
                className="field"
                id={fieldId}
                value={typeof value === "string" ? value : ""}
                rows={4}
                required={schema.required?.includes(key)}
                disabled={disabled || busy}
                onChange={(e) => update(e.target.value)}
              />
            ) : (
              <input
                className="field"
                id={fieldId}
                type={
                  extra.format === "time"
                    ? "time"
                    : prop.type === "number" || prop.type === "integer"
                      ? "number"
                      : "text"
                }
                value={
                  typeof value === "string" || typeof value === "number"
                    ? value
                    : ""
                }
                step={prop.type === "integer" ? 1 : "any"}
                min={prop.minimum}
                max={prop.maximum}
                required={schema.required?.includes(key)}
                disabled={disabled || busy}
                onChange={(e) =>
                  update(
                    prop.type === "number" || prop.type === "integer"
                      ? e.target.valueAsNumber
                      : e.target.value,
                  )
                }
              />
            )}
            {extra["x-scope"] === "device" && (
              <small>{t("schemaform.this_browser_only")}</small>
            )}
            {prop.description && <small>{prop.description}</small>}
          </div>
        );
      })}
      {error && (
        <p className="error" role="alert">
          {error}
        </p>
      )}
      {Object.values(schema.properties ?? {}).some(
        (prop) => !prop["x-secret"],
      ) && (
        <button className="primary" disabled={disabled || busy}>
          {busy ? t("app.saving") : submitLabel}
        </button>
      )}
    </form>
  );
}
