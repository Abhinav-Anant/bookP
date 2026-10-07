// Run with `node --test portal/lib.test.mjs`.
import { test } from "node:test";
import assert from "node:assert/strict";
import { formatOf, parseGenres, sha256Hex, escapeHtml, fmtBytes } from "./lib.js";

test("formatOf reads the extension, case-insensitively", () => {
  assert.equal(formatOf("Gita.EPUB"), "epub");
  assert.equal(formatOf("scan.pdf"), "pdf");
  assert.equal(formatOf("issue-1.cbz"), "cbz");
  assert.equal(formatOf("issue-1.cbr"), null);
  assert.equal(formatOf("noext"), null);
});

test("parseGenres trims and drops blanks", () => {
  assert.deepEqual(parseGenres(" Poetry, ,Myth "), ["Poetry", "Myth"]);
  assert.deepEqual(parseGenres(""), []);
});

test("sha256Hex matches the known digest of abc", async () => {
  const hex = await sha256Hex(new TextEncoder().encode("abc").buffer);
  assert.equal(hex, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
});

test("escapeHtml escapes markup", () => {
  assert.equal(escapeHtml(`<a href="x">&'`), "&lt;a href=&quot;x&quot;&gt;&amp;&#39;");
  assert.equal(escapeHtml(null), "");
});

test("fmtBytes", () => {
  assert.equal(fmtBytes(512), "512 B");
  assert.equal(fmtBytes(2048), "2.0 KB");
  assert.equal(fmtBytes(5 * 1024 * 1024), "5.0 MB");
});
