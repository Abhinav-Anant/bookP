// Runs under both `deno test` and `node --test` (Node 22.18+ strips types).
import { test } from "node:test";
import assert from "node:assert/strict";
import { isStale, newDeviceKey, toKomgaBook, toKomgaPage } from "./komga.ts";

const row = {
  id: "b1",
  title: "Gita",
  author_name: "Vyasa",
  pages: 300,
  updated_at: "2026-09-01T00:00:00Z",
  size_bytes: 1024,
  format: "epub",
  reading_state: { completed: false, updated_at: "2026-09-02T00:00:00.123Z", device_id: "d1" },
};

test("maps a row to Komga's book shape", () => {
  const b = toKomgaBook(row);
  assert.equal(b.id, "b1");
  assert.equal(b.metadata.title, "Gita");
  assert.deepEqual(b.metadata.authors, [{ name: "Vyasa", role: "writer" }]);
  assert.equal(b.media.pagesCount, 300);
  assert.equal(b.media.mediaProfile, "EPUB");
  assert.equal(b.oneshot, true);
  assert.equal(b.readProgress?.deviceId, "d1");
  assert.equal(b.readProgress?.readDate, "2026-09-02T00:00:00.123Z");
});

test("maps formats to media profiles and tolerates missing data", () => {
  const b = toKomgaBook({ ...row, format: "cbz", author_name: null, pages: null, reading_state: null });
  assert.equal(b.media.mediaProfile, "DIVINA");
  assert.deepEqual(b.metadata.authors, []);
  assert.equal(b.media.pagesCount, 0);
  assert.equal(b.readProgress, null);
  assert.equal(toKomgaBook({ ...row, format: "pdf" }).media.mediaProfile, "PDF");
});

test("page is last when fewer rows than size", () => {
  assert.equal(toKomgaPage([row], 200).last, true);
  assert.equal(toKomgaPage(Array(200).fill(row), 200).last, false);
});

test("an older push is stale, a newer or first push is not", () => {
  assert.equal(isStale("2026-09-02T00:00:00Z", "2026-09-01T00:00:00Z"), true);
  assert.equal(isStale("2026-09-02T00:00:00Z", "2026-09-03T00:00:00Z"), false);
  assert.equal(isStale(null, "2026-09-01T00:00:00Z"), false);
});

test("device keys are 43-char base64url and unique", () => {
  const a = newDeviceKey();
  assert.match(a, /^[A-Za-z0-9_-]{43}$/);
  assert.notEqual(a, newDeviceKey());
});
