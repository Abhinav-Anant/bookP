// Author pages: my books and the book editor (admins use the editor too).
import { sb, route, h, raw, show, must, act, go, toast, current, isAdmin, isAuthor } from "./app.js";
import { formatOf, parseGenres, sha256Hex, fmtBytes } from "./lib.js";
import { SUPABASE_URL } from "./config.js";

export const LANGUAGES = {
  en: "English", hi: "Hindi", mr: "Marathi", bn: "Bengali", gu: "Gujarati",
  ta: "Tamil", te: "Telugu", kn: "Kannada", ml: "Malayalam", pa: "Punjabi",
};

export const coverUrl = (path) => path ? `${SUPABASE_URL}/storage/v1/object/public/covers/${path}` : null;
export const chip = (status) => h`<span class="chip ${status}">${status}</span>`;
export const coverImg = (path, big = false) => path
  ? h`<img class="cover${big ? " big" : ""}" src="${coverUrl(path)}" alt="">`
  : h`<span class="cover${big ? " big" : ""}" style="display:inline-block"></span>`;

// My books --------------------------------------------------------------------

route(/^\/books$/, async (view) => {
  const [books, stats] = await Promise.all([
    sb.from("books").select("id,title,status,rejection_reason,cover_path,price_inr,is_free,updated_at")
      .eq("owner_id", current().user.id).order("updated_at", { ascending: false }).then(must),
    sb.rpc("my_book_stats").then(must),
  ]);
  const byBook = Object.fromEntries(stats.map((s) => [s.book_id, s]));
  show(view, h`<section class="card">
    <div class="actions" style="justify-content:space-between">
      <h1>My books</h1><a class="button primary" href="#/book/new">Add a book</a>
    </div>
    ${books.length === 0 ? h`<p class="muted">No books yet.</p>` : h`<div class="scroll"><table>
      <tr><th></th><th>Title</th><th>Status</th><th>Price</th><th>Readers</th><th>Downloads</th><th>Finished</th></tr>
      ${books.map((b) => {
        const s = byBook[b.id] ?? {};
        return h`<tr>
          <td>${coverImg(b.cover_path)}</td>
          <td><a href="#/book/${b.id}">${b.title}</a>
            ${b.status === "rejected" && h`<div class="error">${b.rejection_reason ?? ""}</div>`}</td>
          <td>${chip(b.status)}</td>
          <td>${b.is_free ? "Free" : `₹${b.price_inr}`}</td>
          <td>${s.readers ?? 0}</td><td>${s.downloads ?? 0}</td><td>${s.finished ?? 0}</td>
        </tr>`;
      })}
    </table></div>`}
  </section>`);
}, isAuthor);

// Book editor -----------------------------------------------------------------

route(/^\/book\/(new|[\w-]+)$/, async (view, id) => {
  const book = id === "new" ? null : must(await sb.from("books")
    .select("*, book_files(format,storage_path,size_bytes)").eq("id", id).single());
  const admin = isAdmin();
  const editable = admin || !book || ["draft", "rejected"].includes(book.status);
  const authors = admin ? must(await sb.from("authors").select("id,name").order("name")) : [];
  const v = book ?? { language: "en", is_free: true, price_inr: 0, genres: [], status: "draft" };

  show(view, h`<section class="card">
    <div class="actions" style="justify-content:space-between">
      <h1>${book ? book.title : "New book"}</h1>${book && chip(book.status)}
    </div>
    ${book?.status === "rejected" && h`<p class="error">Not approved: ${book.rejection_reason ?? "no reason given"}.
      Make your changes and submit again.</p>`}
    ${!editable && h`<p class="muted">This book is ${book.status} and can't be edited.</p>`}
    <form id="book">
      <fieldset ${raw(editable ? "" : "disabled")} style="border:0;padding:0;margin:0;display:grid;gap:.75rem">
        <div class="grid2">
          <label>Title <input name="title" required maxlength="300" value="${v.title ?? ""}"></label>
          <label>Subtitle <input name="subtitle" maxlength="300" value="${v.subtitle ?? ""}"></label>
          <label>Publisher <input name="publisher" maxlength="200" value="${v.publisher ?? ""}"></label>
          <label>ISBN <input name="isbn" maxlength="20" value="${v.isbn ?? ""}"></label>
          <label>Language <select name="language">
            ${Object.entries(LANGUAGES).map(([code, name]) =>
              h`<option value="${code}" ${raw(code === v.language ? "selected" : "")}>${name}</option>`)}
          </select></label>
          <label>Genres <input name="genres" placeholder="Poetry, Mythology" value="${v.genres.join(", ")}"></label>
          <label>Pages <input name="pages" type="number" min="1" value="${v.pages ?? ""}"></label>
          <label>Publication date <input name="published_on" type="date" value="${v.published_on ?? ""}"></label>
          ${admin && h`<label>Author <select name="author_id"><option value="">—</option>
            ${authors.map((a) => h`<option value="${a.id}" ${raw(a.id === v.author_id ? "selected" : "")}>${a.name}</option>`)}
          </select></label>`}
        </div>
        <label>Description <textarea name="description" rows="6">${v.description ?? ""}</textarea></label>
        <div class="grid2">
          <label class="check"><input type="checkbox" name="is_free" ${raw(v.is_free ? "checked" : "")}> Free book</label>
          <label>Price (₹) <input name="price_inr" type="number" min="0" step="1" value="${v.price_inr}"></label>
        </div>
        <div class="grid2">
          <label>Cover (JPG, PNG or WebP)
            <input name="cover" type="file" accept="image/jpeg,image/png,image/webp"></label>
          <div>${coverImg(v.cover_path, true)}</div>
        </div>
        <label>Book file (EPUB, PDF or CBZ)
          <input name="file" type="file" accept=".epub,.pdf,.cbz"></label>
        <p class="muted">${(book?.book_files ?? []).map((f) =>
          h`Uploaded: ${f.format.toUpperCase()}${f.size_bytes ? `, ${fmtBytes(f.size_bytes)}` : ""}. `)}</p>
        ${admin && h`<div class="grid2">
          <label class="check"><input type="checkbox" name="featured" ${raw(v.featured ? "checked" : "")}> Featured</label>
          <label class="check"><input type="checkbox" name="editors_pick" ${raw(v.editors_pick ? "checked" : "")}> Editor's pick</label>
          <label>Status <select name="status">
            ${["draft", "pending", "approved", "rejected"].map((s) =>
              h`<option ${raw(s === v.status ? "selected" : "")}>${s}</option>`)}
          </select></label>
        </div>`}
        <div class="actions">
          <button class="primary" name="save" value="save">Save${admin ? "" : " draft"}</button>
          ${!admin && h`<button name="save" value="submit">Submit for approval</button>`}
          ${book && (admin || book.status === "draft") && h`<button type="button" class="danger" id="delete">Delete</button>`}
        </div>
      </fieldset>
    </form>
  </section>`);

  const form = view.querySelector("#book");
  form.cover.addEventListener("change", () => {
    const f = form.cover.files[0];
    if (f) view.querySelector(".cover.big").outerHTML = `<img class="cover big" src="${URL.createObjectURL(f)}" alt="">`;
  });
  form.addEventListener("submit", (e) => {
    e.preventDefault();
    act(e.submitter, async () => {
      const saved = await saveBook(form, book);
      if (e.submitter.value === "submit") {
        const hasFile = (book?.book_files?.length ?? 0) > 0 || form.file.files.length > 0;
        if (!hasFile) throw new Error("Add the book file before submitting.");
        must(await sb.from("books").update({ status: "pending" }).eq("id", saved));
        toast("Submitted for approval");
      } else {
        toast("Saved");
      }
      go(`#/book/${saved}`);
    });
  });
  view.querySelector("#delete")?.addEventListener("click", (e) => act(e.target, async () => {
    if (!confirm(`Delete "${book.title}"? This can't be undone.`)) return;
    await deleteBook(book);
    toast("Deleted");
    go(admin ? "#/admin/books" : "#/books");
  }));
}, isAuthor);

/**
 * Uploads any chosen files, then writes the book and file rows, so a failed
 * upload leaves the book as it was. Returns the book id.
 */
async function saveBook(form, book) {
  const uid = current().user.id;
  const id = book?.id ?? crypto.randomUUID();
  const isFree = form.is_free.checked;
  const price = Number(form.price_inr.value || 0);
  if (!isFree && !(price > 0)) throw new Error("Set a price, or mark the book free.");

  const row = {
    title: form.title.value.trim(),
    subtitle: form.subtitle.value.trim() || null,
    publisher: form.publisher.value.trim() || null,
    isbn: form.isbn.value.trim() || null,
    language: form.language.value,
    genres: parseGenres(form.genres.value),
    pages: form.pages.value ? Number(form.pages.value) : null,
    published_on: form.published_on.value || null,
    description: form.description.value.trim() || null,
    is_free: isFree,
    price_inr: isFree ? 0 : price,
  };
  if (isAdmin()) {
    Object.assign(row, {
      author_id: form.author_id.value || null,
      featured: form.featured.checked,
      editors_pick: form.editors_pick.checked,
      status: form.status.value,
    });
    if (row.status !== "rejected") row.rejection_reason = null;
  }

  const cover = form.cover.files[0];
  if (cover) {
    const ext = cover.type.split("/")[1].replace("jpeg", "jpg");
    // A fresh name each time, so the public CDN never serves a stale cover.
    const path = `${uid}/${id}-${Date.now()}.${ext}`;
    must(await sb.storage.from("covers").upload(path, cover, { contentType: cover.type }));
    row.cover_path = path;
  }

  let fileRow = null;
  const file = form.file.files[0];
  if (file) {
    const format = formatOf(file.name);
    if (!format) throw new Error("The book file must be an EPUB, PDF or CBZ.");
    const path = `${uid}/${id}.${format}`;
    toast(`Uploading ${fmtBytes(file.size)}…`);
    must(await sb.storage.from("books").upload(path, file, { upsert: true, contentType: file.type || undefined }));
    fileRow = { book_id: id, format, storage_path: path, size_bytes: file.size,
      sha256: await sha256Hex(await file.arrayBuffer()) };
  }

  must(book
    ? await sb.from("books").update(row).eq("id", id)
    : await sb.from("books").insert({ id, owner_id: isAdmin() ? null : uid, ...row }));
  if (fileRow) must(await sb.from("book_files").upsert(fileRow, { onConflict: "book_id,format" }));
  return id;
}

export async function deleteBook(book) {
  const files = book.book_files ?? must(await sb.from("book_files").select("storage_path").eq("book_id", book.id));
  must(await sb.from("books").delete().eq("id", book.id));
  // Rows go first: an orphaned file is harmless, a row pointing at nothing is not.
  if (files.length) await sb.storage.from("books").remove(files.map((f) => f.storage_path));
  if (book.cover_path) await sb.storage.from("covers").remove([book.cover_path]);
}
