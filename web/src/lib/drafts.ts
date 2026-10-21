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
  /** Composer captures already saved by the owner, oldest first, until each create is acknowledged. */
  outbox?: PendingSave[];
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
const validSave = (p: PendingSave) =>
  !!p &&
  typeof p.body === "string" &&
  Number.isSafeInteger(p.revision) &&
  /^[a-f0-9-]{36}$/i.test(p.key);
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
    if (d.pending && !validSave(d.pending)) return;
    if (
      d.outbox !== undefined &&
      (!Array.isArray(d.outbox) || !d.outbox.every(validSave))
    )
      return;
    return d;
  } catch {
    return;
  }
}
/**
 * A revision ACK can remove only the exact draft sent; pending creates keep their original payload/key.
 * The composer hands each capture to its outbox and starts a fresh draft at once, so typing during a
 * save always becomes the next note.
 */
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
    private onQueued?: (capture: PendingSave, queued: boolean) => void,
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
        !!this.draft.outbox?.length ||
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
  /** Composer captures queue in the outbox; edits, including a legacy capture tied to its note, are sent directly. */
  private queued() {
    return this.composer && !this.draft.pending && !this.draft.noteId;
  }
  private clear() {
    try {
      this.storage.removeItem(this.key);
    } catch {
      /* A recovered ACKed draft remains safe to re-open. */
    }
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
  /** The view closed mid-save: settle only the stored copy of what was sent, never newer typing. */
  private settleStored(sent: PendingSave, note: Note) {
    const stored = load(this.storage, this.key);
    let next: Draft | undefined;
    if (stored?.outbox?.some((p) => p.key === sent.key))
      next = {
        ...stored,
        outbox: stored.outbox.filter((p) => p.key !== sent.key),
      };
    else if (stored?.pending?.key === sent.key)
      next =
        stored.revision === sent.revision
          ? undefined
          : {
              ...stored,
              pending: undefined,
              noteId: note.id,
              version: note.version,
              conflict: note.body !== sent.body ? note.id : stored.conflict,
            };
    else return;
    try {
      if (next && (next.body || next.outbox?.length || next.noteId))
        this.storage.setItem(this.key, JSON.stringify(next));
      else this.storage.removeItem(this.key);
    } catch {
      /* Storage that cannot be written keeps the earlier, still-recoverable draft. */
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
    if (note.id !== this.draft.noteId) return;
    if (!this.snapshot.dirty) {
      if (note.version === this.draft.version) return;
      this.draft = {
        body: note.body,
        revision: this.draft.revision,
        noteId: note.id,
        version: note.version,
      };
      this.emit({ body: note.body });
    } else if (
      this.allowed &&
      !this.flight &&
      !this.draft.conflict &&
      note.body === this.draft.body &&
      note.version >= (this.draft.version ?? 0)
    ) {
      // The server already holds exactly this text (saved by a view that has since closed).
      this.draft = {
        body: note.body,
        revision: this.draft.revision + 1,
        noteId: note.id,
        version: note.version,
      };
      this.clear();
      this.emit({
        dirty: false,
        state: "saved",
        error: null,
        submitted: false,
        retryBlocked: false,
      });
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
  /** The owner chose the saved text over this draft: forget the local copy and follow the note again. */
  discard = (note: Pick<Note, "id" | "body" | "version">): boolean => {
    if (!this.allowed || this.flight || note.id !== this.draft.noteId)
      return false;
    this.draft = {
      body: note.body,
      revision: this.draft.revision + 1,
      noteId: note.id,
      version: note.version,
    };
    this.clear();
    this.emit({
      body: note.body,
      dirty: false,
      state: "saved",
      error: null,
      conflict: null,
      submitted: false,
      retryBlocked: false,
    });
    return true;
  };
  canSaveAutomatically = (): boolean =>
    this.allowed &&
    (!this.snapshot.error || this.snapshot.error.retryable) &&
    (this.queued()
      ? !!this.draft.outbox?.length
      : this.snapshot.dirty &&
        !this.snapshot.conflict &&
        !this.snapshot.retryBlocked &&
        (!this.composer || this.snapshot.submitted));
  saveAutomatically = (): Promise<Note | undefined> =>
    !this.canSaveAutomatically()
      ? Promise.resolve(undefined)
      : this.queued()
        ? this.flush()
        : this.send();
  /** Captures saved by the owner that the server has not acknowledged yet, oldest first. */
  waiting = (): PendingSave[] => this.draft.outbox ?? [];
  save = (): Promise<Note | undefined> =>
    this.queued() ? this.submit() : this.send();
  private submit(): Promise<Note | undefined> {
    if (!this.allowed) return Promise.resolve(undefined);
    if (this.draft.body.trim()) {
      const sent = {
        body: this.draft.body,
        revision: this.draft.revision,
        key: this.uuid(),
      };
      this.draft = {
        body: "",
        revision: this.draft.revision + 1,
        outbox: [...(this.draft.outbox ?? []), sent],
      };
      this.emit({
        body: "",
        dirty: true,
        error: null,
        submitted: true,
        retryBlocked: false,
      });
      this.persist();
      this.onQueued?.(sent, true);
    }
    return this.flush();
  }
  private flush(): Promise<Note | undefined> {
    const sent = this.draft.outbox?.[0];
    if (this.flight || !this.allowed || !sent)
      return this.flight ?? Promise.resolve(undefined);
    this.emit({ state: "saving", error: null });
    this.flight = this.transport.create(sent.body, sent.key).then(
      (note) => {
        this.flight = undefined;
        if (!this.allowed) {
          this.settleStored(sent, note);
          return note;
        }
        const outbox = this.draft.outbox?.filter((p) => p.key !== sent.key);
        this.draft = { ...this.draft, outbox };
        const dirty = !!this.draft.body || !!outbox?.length;
        if (dirty) this.persist();
        else this.clear();
        this.emit({
          dirty,
          state: this.draft.body ? "unsaved" : "saved",
          error: null,
          submitted: !!outbox?.length,
        });
        this.onSaved?.(note);
        if (outbox?.length) void this.flush();
        return note;
      },
      (error) => {
        this.flight = undefined;
        const safe = safeError(error);
        if (!this.allowed) return undefined;
        if (!safe.retryable) {
          // The server refused this capture: its text returns to the composer instead of retrying forever.
          const body = this.draft.body.trim()
            ? sent.body + "\n\n" + this.draft.body
            : sent.body;
          this.draft = {
            ...this.draft,
            body,
            revision: this.draft.revision + 1,
            outbox: this.draft.outbox?.filter((p) => p.key !== sent.key),
          };
          this.onQueued?.(sent, false);
        }
        this.persist();
        this.emit({
          body: this.draft.body,
          state: "error",
          error: safe,
          submitted: !!this.draft.outbox?.length,
        });
        return undefined;
      },
    );
    return this.flight;
  }
  private send(): Promise<Note | undefined> {
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
        if (!this.allowed) {
          this.settleStored(sent, note);
          return note;
        }
        const newer = this.draft.revision !== sent.revision;
        this.draft = {
          ...this.draft,
          noteId: note.id,
          version: note.version,
          pending: undefined,
        };
        if (!newer) {
          this.draft.submitted = false;
          this.clear();
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
  }
}
/**
 * Writing this owner keeps only on this device: `waiting` counts saves the server has not
 * confirmed yet, queued captures included ("2 notes waiting"); `unsent` adds drafts never
 * saved, which is everything signing out erases.
 */
export function keptDrafts(storage: Storage, owner: string) {
  const prefix = draftStorageKey(owner, "");
  let waiting = 0,
    unsent = 0;
  for (let index = 0; index < storage.length; index++) {
    const key = storage.key(index);
    const draft = key?.startsWith(prefix) ? load(storage, key) : undefined;
    if (!draft) continue;
    const queued = draft.outbox?.length ?? 0;
    waiting += queued + (draft.pending ? 1 : 0);
    unsent += queued + (draft.pending || draft.body.trim() ? 1 : 0);
  }
  return { waiting, unsent };
}
