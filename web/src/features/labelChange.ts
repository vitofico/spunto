export function labelChangeImpact(payload: Record<string, unknown> = {}) {
  return Array.isArray(payload.note_ids)
    ? { new: payload.new, note_ids: payload.note_ids }
    : { old: payload.old, new: payload.new };
}
