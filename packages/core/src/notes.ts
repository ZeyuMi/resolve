import type { DecryptedItem, DecryptedNote, ItemPayload } from "./types";

export function slugifyNoteTitle(value: string) {
  return value
    .trim()
    .toLowerCase()
    .replace(/[^\p{L}\p{N}]+/gu, "-")
    .replace(/^-+|-+$/g, "")
    .slice(0, 60) || "note";
}

export function noteContentHash(value: string) {
  let hash = 0;
  for (let index = 0; index < value.length; index += 1) {
    hash = Math.imul(31, hash) + value.charCodeAt(index) | 0;
  }
  return Math.abs(hash).toString(36);
}

export function notePathFor(noteId: string, title: string, createdAt = new Date().toISOString()) {
  const date = new Date(createdAt);
  const year = String(date.getFullYear());
  const month = String(date.getMonth() + 1).padStart(2, "0");
  return `Notes/${year}/${month}/${noteId}-${slugifyNoteTitle(title)}.md`;
}

export function titleForNote(note: DecryptedNote) {
  return note.meta.title || note.payload.title || "Untitled Note";
}

export function noteFrontmatter(note: DecryptedNote) {
  const lines = [
    "---",
    `note_id: ${note.meta.id}`,
    `title: ${JSON.stringify(note.meta.title)}`,
    `status: ${note.meta.status}`,
    `created_at: ${note.meta.createdAt}`,
    `updated_at: ${note.meta.updatedAt}`
  ];
  if (note.meta.taskId) lines.push(`task_id: ${note.meta.taskId}`);
  if (note.meta.strategyThreadId) lines.push(`strategy_thread_id: ${note.meta.strategyThreadId}`);
  if (note.meta.parentNoteId) lines.push(`parent_note_id: ${note.meta.parentNoteId}`);
  lines.push("---", "");
  return lines.join("\n");
}

export function stripNoteFrontmatter(markdown = "") {
  return markdown.replace(/^---\r?\n[\s\S]*?\r?\n---\r?\n?/, "");
}

export function stripGeneratedNoteTitle(markdown: string, title: string) {
  const body = stripNoteFrontmatter(markdown).trimStart();
  const lines = body.split(/\r?\n/);
  if (lines[0]?.trim() === `# ${title}`) {
    return lines.slice(1).join("\n").trimStart();
  }
  return body;
}

export function noteMarkdownDocument(note: DecryptedNote, body = "") {
  const title = titleForNote(note);
  const content = stripGeneratedNoteTitle(body, title).trim();
  return `${noteFrontmatter(note)}${content}`;
}

export function noteWithTitle(note: DecryptedNote, title: string, updatedAt = note.meta.updatedAt): DecryptedNote {
  const canonicalPath = notePathFor(note.meta.id, title, note.meta.createdAt);
  const nextNote = {
    ...note,
    meta: {
      ...note.meta,
      title,
      canonicalPath,
      updatedAt
    },
    payload: {
      ...note.payload,
      title
    }
  };
  return {
    ...nextNote,
    meta: {
      ...nextNote.meta,
      frontmatterHash: noteContentHash(noteFrontmatter(nextNote))
    }
  };
}

export function activeNoteForTodo(todo: DecryptedItem, notes: DecryptedNote[]) {
  const noteId = (todo.payload as ItemPayload).noteId ?? todo.meta.noteId;
  if (!noteId) return undefined;
  return notes.find((note) => note.meta.id === noteId && note.meta.status !== "archived");
}
