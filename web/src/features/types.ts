export type SchemaProperty = {
  type: "string" | "boolean" | "integer" | "number" | "array";
  title?: string;
  description?: string;
  enum?: (string | number | boolean)[];
  default?: unknown;
  minimum?: number;
  maximum?: number;
  minLength?: number;
  maxLength?: number;
  format?: "time" | "label";
  items?: SchemaProperty;
  "x-secret"?: boolean;
  "x-scope"?: "device";
};
export type SettingsSchema = {
  type: "object";
  properties?: Record<string, SchemaProperty>;
  required?: string[];
  additionalProperties?: false;
};
export type Vocabulary = {
  type: string;
  fallback?: Vocabulary | "drop" | "Open on the web";
  [key: string]: unknown;
};
export type Output =
  | Vocabulary
  | Vocabulary[]
  | { ui?: number; items?: Vocabulary[]; components?: Vocabulary[] };
