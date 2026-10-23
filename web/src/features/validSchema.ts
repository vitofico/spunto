import type { Schema } from "../lib/types";
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
