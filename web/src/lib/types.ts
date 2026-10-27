import type { components } from "./api-types";
import type { Theme } from "./theme-catalog";
export type Note = Omit<
  components["schemas"]["Note"],
  "suggestions" | "annotations"
> & {
  suggestions: Suggestion[];
  annotations: Annotation[];
  match?: "keyword" | "meaning" | "both";
};
export type NotePage = { notes: Note[]; next_cursor: string | null };
export type SearchPage = {
  hits: Note[];
  next_cursor: string | null;
  semantic: "off" | "on" | "unavailable";
};
export type Json =
  null | boolean | number | string | Json[] | { [key: string]: Json };
export type Args = Record<string, Json>;
export interface Label {
  key?: string;
  path: string;
  count: number;
  own_count?: number;
}
export interface Revision {
  version: number;
  body: string;
  change_kind: string;
  changed_by: string;
  changed_at: string;
}
export interface Meta {
  version: string;
  api_version: number;
  public_url: string;
  unencrypted: boolean;
  setup_pending: boolean;
  sync_epoch: string;
  mcp_url: string | null;
  ai_capabilities: string[];
  plugins_rev: number;
  min_android_build: number;
  max_upload_bytes?: number;
}
export interface Session {
  owner_id: string;
  credential_id?: string;
  password_managed?: boolean;
  user_id?: string;
  workspace_id?: string;
  workspace_role?: WorkspaceRole;
  auth_method?: "local" | "password" | "oidc";
}
export interface AuthOptions {
  mode: "local" | "oidc" | "hosted";
  password_enabled?: boolean;
  registration_enabled?: boolean;
  oidc_enabled?: boolean;
  login_url?: string;
  label?: string;
}
export interface AccountSecurity {
  local_username: string | null;
  local_password_set: boolean;
  oidc_linked: boolean;
  reauthenticated: boolean;
  reauth_url: string | null;
}
export interface Suggestion {
  id: string;
  plugin: string;
  note_id: string | null;
  note_version: number | null;
  action: string;
  payload: Args;
  confidence: number | null;
  reason: string;
  status: string;
  change_seq: number;
  created_at: string;
  decided_at: string | null;
}
export type Component =
  | { type: "text"; text: string }
  | { type: "markdown"; text?: string; markdown?: string; html: string }
  | {
      type: "notes";
      ids?: string[];
      items?: (string | { id: string; reason?: string })[];
    }
  | { type: "pill"; suggestion_id: string; label: string; action: string }
  | { type: "command"; id: string; title: string; args?: Args }
  | { type: "form"; schema: Schema }
  | {
      type: "diff";
      base_version: number;
      text?: string;
      hunks: {
        old_start: number;
        new_start: number;
        old_lines?: string[];
        new_lines?: string[];
        lines?: string[];
      }[];
    };
export interface Annotation {
  id: string;
  plugin: string;
  kind: string;
  payload:
    | Component
    | Component[]
    | { ui?: number; items?: Component[]; components?: Component[] };
  change_seq?: number;
}
export interface Schema {
  type?: string;
  additionalProperties?: boolean;
  format?: string;
  properties?: Record<string, Schema>;
  required?: string[];
  enum?: Json[];
  default?: Json;
  title?: string;
  description?: string;
  minimum?: number;
  maximum?: number;
  minLength?: number;
  maxLength?: number;
  items?: Schema;
  "x-secret"?: boolean;
  "x-scope"?: string;
}
export interface Command {
  id: string;
  title: string;
  where: string[];
  surfaces?: string[];
  args?: Schema;
  route?: string;
}
export interface Plugin {
  id: string;
  name: string;
  version: string;
  description: string;
  category: string;
  kind: string;
  permissions: string[];
  /** What the plugin's own new notes are; plugins that create notes save their own results. */
  creates?: "machine" | "import" | "external" | "owner";
  ai_route?: Partial<Record<AICapability, string>> | null;
  ai_destinations?: Partial<
    Record<
      AICapability,
      {
        profile: string;
        host: string | null;
        model: string;
        leaves_network: boolean;
      }
    >
  >;
  settings?: Schema | Args;
  settings_values?: Args;
  position: number;
  switch?: string;
  state?: string;
  pinned_by_env?: boolean | 0 | 1;
  origin?: "bundled" | "operator" | "unknown";
  source_kind?: "bundled" | "folder" | "entrypoint" | "unknown";
  can_enable?: boolean;
  availability_reason?: string | null;
  can_delete_data?: boolean;
  granted?: string[];
  plugins_rev?: number;
  last_run_at?: string | number | null;
  last_error?: string | null;
  data_count?: number;
  data_counts?: Record<string, number>;
  data_size_bytes?: number;
  permission_sentences?: string[];
  secrets?: Record<string, { set: boolean; source: string }>;
  contributes?: {
    views?: { id: string; title: string; filter: Args }[];
    commands?: Command[];
    mcp_tools?: { name: string; description: string }[];
    [key: string]: unknown;
  };
}
export type AICapability = "chat" | "embed" | "transcribe" | "vision";
export interface AIProfile {
  name: string;
  provider: string;
  base_url: string;
  chat_model?: string | null;
  embed_model?: string | null;
  transcribe_model?: string | null;
  vision_model?: string | null;
  leaves_network?: Partial<Record<AICapability, boolean>>;
}
export interface AIModelChoice {
  profile: string;
  model: string;
}
export type AIModelSelections = Partial<Record<AICapability, AIModelChoice>>;
export interface AIModelSelection {
  revision: string;
  selections: AIModelSelections;
  defaults: AIModelSelections;
}
export interface AIModelInventory {
  profiles: {
    profile: string;
    models: {
      id: string;
      capabilities: AICapability[] | null;
      leaves_network: boolean;
    }[];
    error: string | null;
  }[];
}
export interface AIHealth {
  capability: AICapability;
  profile: string;
  model?: string;
  ok: boolean;
  dimension?: number;
  code?: string | null;
  fix_hint?: string | null;
}
export interface VectorStatus {
  available: boolean;
  enabled: boolean;
  chunks: number;
  routes: {
    profile: string;
    active_key: string | null;
    target_key: string;
    indexed: number;
    total: number;
  }[];
}
export type AIAllowanceAction =
  "labels" | "ask" | "chat" | "vision" | "transcribe" | "embed";
/** A hosted account's monthly AI allowance; a null limit is unlimited. */
export interface AIQuota {
  period: string;
  resets_at: string;
  actions: { action: AIAllowanceAction; used: number; limit: number | null }[];
}
export interface AIStatus {
  capabilities: string[];
  profiles: AIProfile[];
  bindings: Partial<Record<AICapability, string>>;
  health: AIHealth[];
  can_manage?: boolean;
  model_selection?: AIModelSelection;
  indexing?: VectorStatus;
  quota?: AIQuota | null;
  stats?: {
    plugin_id: string;
    capability: string;
    outcome: string;
    n: number;
  }[];
}
export interface Manifest {
  ui: number;
  plugins_rev: number;
  plugins: Plugin[];
  theme_catalog?: ThemeCard[];
}
export type ThemeCard = {
  id: string;
  name: string;
  description?: string;
  status: "available" | "failed";
  reason?: string;
  tokens: number;
  light?: Record<string, string>;
  dark?: Record<string, string>;
  accents: {
    id: string;
    name: string;
    light: Record<string, string>;
    dark: Record<string, string>;
  }[];
};
export interface Preferences {
  owner_display_name?: string;
  theme: Theme;
  theme_accent?: string | null;
  plugin_order: string[];
  hidden_mcp_tools: string[];
  hidden_views: string[];
}
export interface Credential {
  id: string;
  kind: string;
  name: string;
  client: string | null;
  scope: string;
  private_ok: boolean;
  external_ok: boolean;
  created_at: string;
  last_used_at: string | null;
  expires_at: string | null;
  current: boolean;
}
export interface Attachment {
  id: string;
  url: string;
  snippet: string;
  mime: string;
  size: number;
}
export interface NoteFilters {
  view?: string;
  archived?: boolean;
  trashed?: boolean;
  label?: string;
  from?: string;
  to?: string;
  limit?: number;
  cursor?: string;
}
export interface SearchFilters {
  labels?: string[];
  from?: string;
  to?: string;
  include_archived?: boolean;
  sort?: "relevance" | "recent";
  mode?: "hybrid" | "keyword";
  limit?: number;
  cursor?: string;
}
export type Flags = components["schemas"]["Flags"];
export type TokenInput = components["schemas"]["TokenBody"];

export type WorkspaceRole = "owner" | "editor" | "viewer";
export interface Workspace {
  id: string;
  name: string;
  role: WorkspaceRole;
  personal: boolean;
}
export interface AccessGrant {
  user_id: string;
  email: string | null;
  username?: string | null;
  display_name: string;
  role: WorkspaceRole;
}
export type SharedNote = Note & {
  share_role: "editor" | "viewer";
  workspace_id: string;
  shared_by: string;
};
