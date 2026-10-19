import { ApiError, safeError } from "./errors";
import type { Note } from "./types";
export interface PendingSave {
  revision: number;
  body: string;
  key: string;
  noteId?: string;
  version?: number;
}
export interface Draft {
  body: string;
  revision: number;
  noteId?: string;
  version?: number;
  pending?: PendingSave;
  conflict?: string;
  submitted?: boolean;
  retryBlocked?: boolean;
}
export interface DraftStorage {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
  removeItem(key: string): void;
}
export interface DraftTransport {
  create(body: string, key: string): Promise<Note>;
  update(id: string, body: string, version: number): Promise<Note>;
}
export interface DraftSnapshot {
  body: string;
  dirty: boolean;
  state: "saved" | "unsaved" | "saving" | "error" | "conflict";
  error: ApiError | null;
  conflict: string | null;
  storageFailed: boolean;
  submitted: boolean;
  retryBlocked: boolean;
}
export function createIdempotencyKey() {
  // getRandomValues is available on the explicitly supported plain-HTTP LAN setup too.
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  bytes[6] = (bytes[6]! & 15) | 64;
  bytes[8] = (bytes[8]! & 63) | 128;
  const hex = [...bytes]
    .map((value) => value.toString(16).padStart(2, "0"))
    .join("");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}
export function draftStorageKey(owner: string, draftKey: string) {
  return `spunto:draft:v1:${encodeURIComponent(owner)}:${encodeURIComponent(draftKey)}`;
}
export function carryRotatedDrafts(
  storage: Storage,
  previous: string,
  next: string,
) {
  if (previous === next) return;
  const before: unknown = JSON.parse(previous);
  const after: unknown = JSON.parse(next);
  if (
    !Array.isArray(before) ||
    !Array.isArray(after) ||
    before.length !== 3 ||
    after.length !== 3 ||
    !before.every((value) => typeof value === "string") ||
    !after.every((value) => typeof value === "string") ||
    before[0] !== after[0] ||
    before[1] !== after[1]
  )
    throw new ApiError(409, "identity_changed");
  const source = `spunto:draft:v1:${encodeURIComponent(previous)}:`;
  const target = `spunto:draft:v1:${encodeURIComponent(next)}:`;
  const copies: [string, string][] = [];
  for (let index = 0; index < storage.length; index++) {
    const key = storage.key(index);
    if (!key?.startsWith(source)) continue;
    const value = storage.getItem(key);
    if (value === null) continue;
    const destination = target + key.slice(source.length);
    const existing = storage.getItem(destination);
    if (existing !== null && existing !== value)
      throw new ApiError(409, "draft_recovery_required");
    if (existing === null) copies.push([destination, value]);
  }
  const written: string[] = [];
  try {
    for (const [key, value] of copies) {
      storage.setItem(key, value);
      written.push(key);
    }
  } catch {
    for (const key of written) storage.removeItem(key);
    throw new ApiError(409, "draft_recovery_required");
  }
}
export function eraseOwnerDrafts(storage: Storage, owner: string) {
  const prefix = `spunto:draft:v1:${encodeURIComponent(owner)}:`;
  let account: string | undefined;
  try {
    const identity: unknown = JSON.parse(owner);
    if (Array.isArray(identity) && typeof identity[0] === "string")
      account = identity[0];
  } catch {}
  for (let i = storage.length - 1; i >= 0; i--) {
    const key = storage.key(i);
    if (!key?.startsWith("spunto:draft:v1:")) continue;
    if (key.startsWith(prefix)) {
      storage.removeItem(key);
      continue;
    }
    if (account) {
      try {
        const identity: unknown = JSON.parse(
          decodeURIComponent(key.split(":")[3] ?? ""),
        );
        if (Array.isArray(identity) && identity[0] === account)
          storage.removeItem(key);
      } catch {}
    }
  }
}
function load(storage: DraftStorage, key: string): Draft | undefined {
  try {
    const value: unknown = JSON.parse(storage.getItem(key) ?? "null");
    if (!value || typeof value !== "object") return;
    const d = value as Draft;
    if (
      typeof d.body !== "string" ||
      !Number.isSafeInteger(d.revision) ||
      d.revision < 0
    )
      return;
    if (d.noteId !== undefined && typeof d.noteId !== "string") return;
    if (
      d.version !== undefined &&
      (!Number.isSafeInteger(d.version) || d.version < 1)
    )
      return;
    if (
      d.pending &&
      (typeof d.pending.body !== "string" ||
        !Number.isSafeInteger(d.pending.revision) ||
        !/^[a-f0-9-]{36}$/i.test(d.pending.key))
    )
      return;
    return d;
  } catch {
    return;
  }
}
/** A revision ACK can remove only the exact draft sent; pending creates keep their original payload/key. */
export class DraftController {
  private draft: Draft;
  private snapshot: DraftSnapshot;
  private listeners = new Set<() => void>();
  private flight: Promise<Note | undefined> | undefined;
  private allowed = true;
  private composer: boolean;
  constructor(
    private storage: DraftStorage,
    private key: string,
    private transport: DraftTransport,
    note?: Pick<Note, "id" | "body" | "version">,
    private uuid = createIdempotencyKey,
    private onSaved?: (note: Note) => void,
  ) {
    this.composer = !note;
    const restored = load(storage, key);
    this.draft = restored ?? {
      body: note?.body ?? "",
      revision: 0,
      noteId: note?.id,
      version: note?.version,
    };
    this.snapshot = {
      body: this.draft.body,
      dirty: !!restored,
      state: this.draft.conflict ? "conflict" : restored ? "unsaved" : "saved",
      error: null,
      conflict: this.draft.conflict ?? null,
      storageFailed: false,
      submitted:
        this.draft.submitted === true ||
        !!this.draft.pending ||
        (this.composer &&
          this.draft.submitted === undefined &&
          !!this.draft.noteId),
      retryBlocked: this.draft.retryBlocked === true,
    };
  }
  getSnapshot = () => this.snapshot;
  subscribe = (listener: () => void) => {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  };
  private emit(patch: Partial<DraftSnapshot>) {
    this.snapshot = { ...this.snapshot, ...patch };
    for (const listener of this.listeners) listener();
  }
  persist() {
    if (!this.allowed || !this.snapshot.dirty) return;
    try {
      this.storage.setItem(this.key, JSON.stringify(this.draft));
      if (this.snapshot.storageFailed) this.emit({ storageFailed: false });
    } catch {
      this.emit({ storageFailed: true });
    }
  }
  setBody = (body: string) => {
    if (body === this.draft.body || !this.allowed) return;
    this.draft = { ...this.draft, body, revision: this.draft.revision + 1 };
    this.emit({
      body,
      dirty: true,
      state: this.snapshot.conflict ? "conflict" : "unsaved",
      error: null,
    });
    // Persist synchronously too: a closed tab never depends on the idle timer firing.
    this.persist();
  };
  deactivate() {
    this.persist();
    this.allowed = false;
  }
  activate() {
    this.allowed = true;
  }
  syncNote(note: Pick<Note, "id" | "body" | "version">) {
    if (
      !this.snapshot.dirty &&
      note.id === this.draft.noteId &&
      note.version !== this.draft.version
    ) {
      this.draft = {
        body: note.body,
        revision: this.draft.revision,
        noteId: note.id,
        version: note.version,
      };
      this.emit({ body: note.body });
    }
  }
  resumeReviewed = (note: Pick<Note, "id" | "body" | "version">): boolean => {
    if (
      !this.allowed ||
      this.flight ||
      !this.snapshot.conflict ||
      note.id !== this.draft.noteId
    )
      return false;
    // The owner explicitly reviewed the latest saved text alongside this draft.
    // Keep every local keystroke; only the version used by the next save changes.
    this.draft = {
      ...this.draft,
      version: note.version,
      pending: undefined,
      conflict: undefined,
      retryBlocked: false,
      revision: this.draft.revision + 1,
    };
    this.emit({
      dirty: true,
      state: "unsaved",
      error: null,
      conflict: null,
      retryBlocked: false,
    });
    this.persist();
    return true;
  };
  canSaveAutomatically = (): boolean =>
    this.allowed &&
    this.snapshot.dirty &&
    !this.snapshot.conflict &&
    !this.snapshot.retryBlocked &&
    (!this.composer || this.snapshot.submitted) &&
    (!this.snapshot.error || this.snapshot.error.retryable);
  saveAutomatically = (): Promise<Note | undefined> =>
    this.canSaveAutomatically() ? this.save() : Promise.resolve(undefined);
  save = (): Promise<Note | undefined> => {
    if (this.flight) return this.flight;
    if (
      !this.allowed ||
      !this.snapshot.dirty ||
      this.snapshot.conflict ||
      (!this.draft.noteId && !this.draft.body.trim())
    )
      return Promise.resolve(undefined);
    const sent = this.draft.pending ?? {
      body: this.draft.body,
      revision: this.draft.revision,
      key: this.uuid(),
      noteId: this.draft.noteId,
      version: this.draft.version,
    };
    this.draft = {
      ...this.draft,
      pending: sent,
      submitted: true,
      retryBlocked: false,
    };
    this.persist();
    this.emit({
      state: "saving",
      error: null,
      submitted: true,
      retryBlocked: false,
    });
    const operation = sent.noteId
      ? this.transport.update(sent.noteId, sent.body, sent.version!)
      : this.transport.create(sent.body, sent.key);
    this.flight = operation
      .then((note) => {
        if (!this.allowed) return note;
        const newer = this.draft.revision !== sent.revision;
        this.draft = {
          ...this.draft,
          noteId: note.id,
          version: note.version,
          pending: undefined,
        };
        if (!newer) {
          this.draft.submitted = false;
          try {
            this.storage.removeItem(this.key);
          } catch {
            /* A recovered ACKed draft remains safe to re-open. */
          }
          this.draft.body = this.composer ? "" : note.body;
          if (this.composer) {
            this.draft.noteId = undefined;
            this.draft.version = undefined;
          }
          this.emit({
            body: this.draft.body,
            dirty: false,
            state: "saved",
            error: null,
            submitted: false,
          });
        } else if (note.body !== sent.body) {
          // A merged ACK plus newer keystrokes requires review; never overwrite either text.
          this.draft.conflict = note.id;
          this.persist();
          this.emit({
            state: "conflict",
            conflict: note.id,
            error: new ApiError(409, "version_conflict", note.id),
          });
        } else {
          this.persist();
          this.emit({ state: "unsaved", error: null });
        }
        this.onSaved?.(note);
        return note;
      })
      .catch((error) => {
        const safe = safeError(error);
        if (this.allowed) {
          if (!safe.retryable) {
            this.draft.pending = undefined;
            this.draft.retryBlocked = true;
          }
          if (safe.conflictId) this.draft.conflict = safe.conflictId;
          this.persist();
          this.emit({
            state: safe.conflictId ? "conflict" : "error",
            error: safe,
            conflict: safe.conflictId ?? null,
            retryBlocked: this.draft.retryBlocked === true,
          });
        }
        return undefined;
      })
      .finally(() => {
        this.flight = undefined;
      });
    return this.flight;
  };
}
