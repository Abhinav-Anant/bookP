// Pure mapping from APA rows to the Komga JSON shapes the Android app's
// Komga client parses (see app/.../data/komga/KomgaBooks.kt). No Deno or
// Supabase imports here, so it runs under `node --test` as well.

export type ReadingStateRow = {
  completed: boolean;
  updated_at: string;
  device_id: string | null;
};

export type BookRow = {
  id: string;
  title: string;
  author_name: string | null;
  pages: number | null;
  updated_at: string;
  size_bytes: number | null;
  format: string | null;
  reading_state?: ReadingStateRow | null;
};

// Komga's media profiles; the app's reader picks a navigator from these.
const PROFILE: Record<string, string> = { epub: "EPUB", pdf: "PDF", cbz: "DIVINA" };

export function toKomgaBook(r: BookRow) {
  return {
    id: r.id,
    name: r.title,
    // Every APA book is its own "series" of one until series exist server-side.
    oneshot: true,
    seriesId: null,
    seriesTitle: null,
    sizeBytes: r.size_bytes ?? 0,
    lastModified: r.updated_at,
    metadata: {
      title: r.title,
      authors: r.author_name ? [{ name: r.author_name, role: "writer" }] : [],
    },
    media: {
      pagesCount: r.pages ?? 0,
      mediaProfile: PROFILE[r.format ?? "epub"] ?? "EPUB",
      status: "READY",
    },
    readProgress: r.reading_state
      ? {
        page: 0,
        completed: r.reading_state.completed,
        // The app compares this with the `modified` it pushed, so it must be
        // echoed back unchanged (see KomgaSyncRepository).
        readDate: r.reading_state.updated_at,
        deviceId: r.reading_state.device_id,
      }
      : null,
  };
}

export function toKomgaPage(rows: BookRow[], size: number) {
  return { content: rows.map(toKomgaBook), last: rows.length < size };
}

/** Whether a pushed position is older than the stored one (Komga answers 409). */
export function isStale(storedUpdatedAt: string | null | undefined, pushedModified: string): boolean {
  if (!storedUpdatedAt) return false;
  return Date.parse(storedUpdatedAt) > Date.parse(pushedModified);
}

/** A 32-byte random device key, base64url without padding. */
export function newDeviceKey(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(32));
  let s = "";
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s).replaceAll("+", "-").replaceAll("/", "_").replaceAll("=", "");
}
