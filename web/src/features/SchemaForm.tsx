import { useId, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { api } from "../lib/api";
import type { Args, Json, Schema } from "../lib/types";
import { choiceLabel, humanize } from "./format";
import styles from "./Feature.module.css";
const keys = new Set([
  "type",
  "properties",
  "required",
  "additionalProperties",
  "title",
  "description",
  "enum",
  "default",
  "minimum",
  "maximum",
  "minLength",
  "maxLength",
  "items",
  "format",
  "x-secret",
  "x-scope",
]);
export function validSchema(value: unknown, root = true): value is Schema {
  if (!value || typeof value !== "object" || Array.isArray(value)) return false;
  const schema = value as Record<string, unknown>;
  if (Object.keys(schema).some((key) => !keys.has(key))) return false;
  if (
    schema["x-secret"] !== undefined &&
    typeof schema["x-secret"] !== "boolean"
  )
    return false;
  if (schema["x-secret"] && (schema.type !== "string" || "default" in schema))
    return false;
  if (schema["x-scope"] !== undefined && schema["x-scope"] !== "device")
    return false;
  if (
    schema.format !== undefined &&
    !["time", "label"].includes(String(schema.format))
  )
    return false;
  if (root) {
    if (schema.type !== "object" || schema.additionalProperties === true)
      return false;
    const props = schema.properties ?? {};
    if (!props || typeof props !== "object" || Array.isArray(props))
      return false;
    if (
      Object.keys(props).some((key) =>
        ["__proto__", "prototype", "constructor"].includes(key),
      )
    )
      return false;
    if (Object.values(props).some((prop) => !validSchema(prop, false)))
      return false;
    if (
      schema.required !== undefined &&
      (!Array.isArray(schema.required) ||
        schema.required.some(
          (key) => typeof key !== "string" || !(key in props),
        ))
    )
      return false;
  } else if (
    !["string", "boolean", "integer", "number", "array"].includes(
      String(schema.type),
    )
  )
    return false;
  if (
    schema.type === "array" &&
    (!validSchema(schema.items, false) ||
      (schema.items as Schema).type !== "string")
  )
    return false;
  if (
    schema.enum !== undefined &&
    (!Array.isArray(schema.enum) ||
      !schema.enum.length ||
      schema.enum.some(
        (x) => !["string", "number", "boolean"].includes(typeof x),
      ))
  )
    return false;
  for (const key of ["minimum", "maximum", "minLength", "maxLength"])
    if (
      schema[key] !== undefined &&
      (typeof schema[key] !== "number" || !Number.isFinite(schema[key]))
    )
      return false;
  return true;
}
export function validateSettings(schema: Schema, values: Args): string | null {
  if (!validSchema(schema)) return "This settings schema is unavailable.";
  const properties = schema.properties ?? {};
  if (Object.keys(values).some((key) => !Object.hasOwn(properties, key)))
    return "Unknown setting.";
  for (const [key, prop] of Object.entries(properties)) {
    if (prop["x-secret"]) continue;
    const value = values[key];
    if (value === undefined) {
      if (schema.required?.includes(key))
        return `${prop.title ?? humanize(key)} is required.`;
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
      return `${prop.title ?? humanize(key)} has an invalid value.`;
    if (
      typeof value === "number" &&
      (value < (prop.minimum ?? -Infinity) ||
        value > (prop.maximum ?? Infinity))
    )
      return `${prop.title ?? humanize(key)} is outside its range.`;
    if (
      typeof value === "string" &&
      ([...value].length < (prop.minLength ?? 0) ||
        [...value].length > (prop.maxLength ?? 100000))
    )
      return `${prop.title ?? humanize(key)} has an invalid length.`;
    if (
      typeof value === "string" &&
      (prop as Record<string, unknown>).format === "time" &&
      !/^([01]\d|2[0-3]):[0-5]\d$/.test(value)
    )
      return `${prop.title ?? humanize(key)} must be a time.`;
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
              aria-label={`Remove ${text}`}
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
          aria-label={`Add ${label}`}
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
          Add
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
  submitLabel = "Save settings",
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
        This settings schema is unavailable.
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
                Set in the environment · {secrets[key]?.set ? "Set" : "Not set"}
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
            {extra["x-scope"] === "device" && <small>This browser only</small>}
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
          {busy ? "Saving…" : submitLabel}
        </button>
      )}
    </form>
  );
}
