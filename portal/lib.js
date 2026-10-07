// Pure helpers, kept free of the DOM and Supabase so Node can test them.

const FORMATS = ["epub", "pdf", "cbz"];

/** The book_files format for a file name, or null if APA cannot read it. */
export function formatOf(fileName) {
  const ext = String(fileName).split(".").pop().toLowerCase();
  return fileName.includes(".") && FORMATS.includes(ext) ? ext : null;
}

export function parseGenres(text) {
  return String(text ?? "").split(",").map((g) => g.trim()).filter(Boolean);
}

export async function sha256Hex(buffer) {
  const digest = await crypto.subtle.digest("SHA-256", buffer);
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

export function escapeHtml(s) {
  return String(s ?? "").replace(/[&<>"']/g, (c) =>
    ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);
}

export function fmtBytes(n) {
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
  return `${(n / 1024 / 1024).toFixed(1)} MB`;
}
