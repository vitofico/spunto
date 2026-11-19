import { requestGeneration } from "./identity";
import type { Attachment, NoteDetail } from "./types";

type Inputs = NonNullable<NoteDetail["note_tool_inputs"]>;
const uploaded = new Map<string, string>();
let generation = -1;

export function rememberAttachmentMime(attachment: Attachment) {
  const current = requestGeneration();
  if (generation !== current) uploaded.clear();
  generation = current;
  uploaded.set(attachment.id, attachment.mime);
  if (uploaded.size > 128) uploaded.delete(uploaded.keys().next().value!);
  return attachment;
}

function hasHttpSource(note: NoteDetail) {
  const value =
    note.source?.url ||
    note.body.match(/https?:\/\/[^\p{White_Space}\u001c-\u001f<>\])]+/u)?.[0];
  if (!value || value.length > 2048 || /[\x00-\x20\x7f\\]/.test(value))
    return false;
  const authority = /^https?:\/\/([^/?#]+)/i.exec(value)?.[1];
  if (!authority || /[@%]/.test(authority)) return false;
  return !!URL.canParse(value);
}

const attachmentInputs = {
  image: ["image/png", "image/jpeg", "image/webp", "image/gif"],
  audio: ["audio/ogg", "audio/opus", "audio/wav", "audio/x-wav"],
};

/** Older servers lack a detail input envelope. Media requires a verified upload MIME. */
export function hasNoteToolInput(note: NoteDetail, input: keyof Inputs) {
  if (note.note_tool_inputs != null) return note.note_tool_inputs[input];
  if (note.private || note.machine || note.trashed) return false;
  if (input === "link") return hasHttpSource(note);
  if (input === "image" || input === "audio") {
    if (generation !== requestGeneration() || !uploaded.size) return false;
    for (const [, id] of note.body.matchAll(
      /\/a\/([\da-fA-F-]{36})(?![\da-fA-F-])/g,
    ))
      if (attachmentInputs[input].includes(uploaded.get(id) ?? "")) return true;
    return false;
  }
  if (input === "short_text")
    return !!note.body.trim() && [...note.body].length <= 20_000;
  const threshold = input === "labels" ? 3 : 300;
  return (
    note.body.split(/[\p{White_Space}\u001c-\u001f]+/u).filter(Boolean)
      .length >= threshold
  );
}
