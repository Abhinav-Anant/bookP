// Admin pages: review queue, books, authors, users, analytics.
import { sb, route, h, show, must, act, render, toast, isAdmin, raw } from "./app.js";
import { chip, coverImg, deleteBook } from "./author.js";

const fmtDate = (s) => s ? new Date(s).toLocaleDateString(undefined, { dateStyle: "medium" }) : "—";
const ask = (message) => {
  const reason = prompt(message);
  return reason === null ? null : reason.trim() || null;
};

/** Wires every `[data-act]` button in `root` to `handlers[name](button, dataset)`. */
function wire(root, handlers) {
  root.querySelectorAll("[data-act]").forEach((b) =>
    b.addEventListener("click", () => act(b, () => handlers[b.dataset.act](b, b.dataset))));
}

// Review queue ----------------------------------------------------------------

route(/^\/admin$/, async (view) => {
  const [apps, books] = await Promise.all([
    sb.from("author_applications").select("*").eq("status", "pending").order("created_at").then(must),
    sb.from("books").select("id,title,cover_path,submitted_at,is_free,price_inr,authors(name),book_files(format)")
      .eq("status", "pending").order("submitted_at").then(must),
  ]);
  show(view, h`
    <section class="card"><h1>Author applications</h1>
      ${apps.length === 0 ? h`<p class="muted">Nothing waiting.</p>` : h`<div class="scroll"><table>
        <tr><th>Name</th><th>About</th><th>Applied</th><th></th></tr>
        ${apps.map((a) => h`<tr><td>${a.display_name}</td><td>${a.bio ?? ""}</td><td>${fmtDate(a.created_at)}</td>
          <td class="actions"><button class="primary" data-act="approveApp" data-id="${a.user_id}">Approve</button>
            <button class="danger" data-act="rejectApp" data-id="${a.user_id}">Reject</button></td></tr>`)}
      </table></div>`}
    </section>
    <section class="card"><h1>Books waiting for approval</h1>
      ${books.length === 0 ? h`<p class="muted">Nothing waiting.</p>` : h`<div class="scroll"><table>
        <tr><th></th><th>Title</th><th>Author</th><th>Files</th><th>Price</th><th>Submitted</th><th></th></tr>
        ${books.map((b) => h`<tr><td>${coverImg(b.cover_path)}</td>
          <td><a href="#/book/${b.id}">${b.title}</a></td><td>${b.authors?.name ?? "—"}</td>
          <td>${b.book_files.map((f) => f.format.toUpperCase()).join(", ") || h`<span class="error">none</span>`}</td>
          <td>${b.is_free ? "Free" : `₹${b.price_inr}`}</td><td>${fmtDate(b.submitted_at)}</td>
          <td class="actions"><button class="primary" data-act="approveBook" data-id="${b.id}">Approve</button>
            <button class="danger" data-act="rejectBook" data-id="${b.id}">Reject</button></td></tr>`)}
      </table></div>`}
    </section>`);
  wire(view, {
    async approveApp(_, d) {
      must(await sb.rpc("review_author_application", { applicant: d.id, approve: true }));
      toast("Author approved");
      render();
    },
    async rejectApp(_, d) {
      const reason = ask("Why is this application not approved? The applicant sees this.");
      if (reason === null) return;
      must(await sb.rpc("review_author_application", { applicant: d.id, approve: false, reason }));
      render();
    },
    async approveBook(_, d) {
      const book = books.find((b) => b.id === d.id);
      if (!book.book_files.length) throw new Error("This book has no file yet; reject it or add one first.");
      must(await sb.from("books").update({ status: "approved", rejection_reason: null }).eq("id", d.id));
      toast("Published. It reaches the app at its next refresh.");
      render();
    },
    async rejectBook(_, d) {
      const reason = ask("Why is this book not approved? The author sees this.");
      if (reason === null) return;
      must(await sb.from("books").update({ status: "rejected", rejection_reason: reason }).eq("id", d.id));
      render();
    },
  });
}, isAdmin);

// All books -------------------------------------------------------------------

route(/^\/admin\/books(?:\?(.*))?$/, async (view, query = "") => {
  const params = new URLSearchParams(query);
  const q = params.get("q") ?? "";
  const status = params.get("status") ?? "";
  let req = sb.from("books")
    .select("id,title,status,cover_path,featured,editors_pick,cover_path,authors(name),book_files(storage_path)")
    .order("updated_at", { ascending: false }).limit(200);
  if (q) req = req.ilike("title", `%${q}%`);
  if (status) req = req.eq("status", status);
  const books = must(await req);
  show(view, h`<section class="card">
    <div class="actions" style="justify-content:space-between"><h1>All books</h1>
      <a class="button primary" href="#/book/new">Add a book</a></div>
    <form id="filter" class="actions">
      <input name="q" placeholder="Search titles" value="${q}" style="max-width:280px">
      <select name="status" style="max-width:180px"><option value="">Any status</option>
        ${["draft", "pending", "approved", "rejected"].map((s) =>
          h`<option ${raw(s === status ? "selected" : "")}>${s}</option>`)}</select>
      <button>Filter</button>
    </form>
    <div class="scroll"><table>
      <tr><th></th><th>Title</th><th>Author</th><th>Status</th><th></th></tr>
      ${books.map((b) => h`<tr><td>${coverImg(b.cover_path)}</td>
        <td><a href="#/book/${b.id}">${b.title}</a>
          ${b.featured && h` <span class="chip">featured</span>`}${b.editors_pick && h` <span class="chip">editor's pick</span>`}</td>
        <td>${b.authors?.name ?? "—"}</td><td>${chip(b.status)}</td>
        <td class="actions">${b.status === "approved" && h`<button data-act="unpublish" data-id="${b.id}">Unpublish</button>`}
          <button class="danger" data-act="remove" data-id="${b.id}">Delete</button></td></tr>`)}
    </table></div>
    ${books.length === 0 && h`<p class="muted">No books match.</p>`}
  </section>`);
  view.querySelector("#filter").addEventListener("submit", (e) => {
    e.preventDefault();
    const f = new URLSearchParams(new FormData(e.target));
    location.hash = `#/admin/books?${f}`;
  });
  wire(view, {
    async unpublish(_, d) {
      if (!confirm("Take this book off the app? Readers who have it keep it in their library.")) return;
      must(await sb.from("books").update({ status: "draft" }).eq("id", d.id));
      render();
    },
    async remove(_, d) {
      const book = books.find((b) => b.id === d.id);
      if (!confirm(`Delete "${book.title}" for everyone? This can't be undone.`)) return;
      await deleteBook(book);
      toast("Deleted");
      render();
    },
  });
}, isAdmin);

// Authors ---------------------------------------------------------------------

route(/^\/admin\/authors$/, async (view) => {
  const authors = must(await sb.from("authors").select("*").order("name"));
  const row = (a) => h`<tr data-id="${a.id ?? ""}">
    <td><input name="name" value="${a.name ?? ""}" required></td>
    <td><textarea name="bio" rows="2">${a.bio ?? ""}</textarea></td>
    <td><input name="photo_url" type="url" value="${a.photo_url ?? ""}"></td>
    <td>${a.profile_id ? "Portal account" : "—"}</td>
    <td><button data-act="save">Save</button></td></tr>`;
  show(view, h`<section class="card"><h1>Authors</h1>
    <p class="muted">The public author pages readers see. Authors approved through the portal are created automatically.</p>
    <div class="scroll"><table>
      <tr><th>Name</th><th>Bio</th><th>Photo URL</th><th>Account</th><th></th></tr>
      ${authors.map(row)}${row({})}
    </table></div></section>`);
  wire(view, {
    async save(button) {
      const tr = button.closest("tr");
      const value = (n) => tr.querySelector(`[name=${n}]`).value.trim() || null;
      const fields = { name: value("name"), bio: value("bio"), photo_url: value("photo_url") };
      if (!fields.name) throw new Error("An author needs a name.");
      must(tr.dataset.id
        ? await sb.from("authors").update(fields).eq("id", tr.dataset.id)
        : await sb.from("authors").insert(fields));
      toast("Saved");
      if (!tr.dataset.id) render();
    },
  });
}, isAdmin);

// Users -----------------------------------------------------------------------

route(/^\/admin\/users(?:\?q=(.*))?$/, async (view, q = "") => {
  q = decodeURIComponent(q);
  const users = must(await sb.rpc("admin_users", { q }));
  show(view, h`<section class="card"><h1>Users</h1>
    <form id="search" class="actions">
      <input name="q" placeholder="Search email or name" value="${q}" style="max-width:320px"><button>Search</button>
    </form>
    <div class="scroll"><table>
      <tr><th>Email</th><th>Name</th><th>Role</th><th>Joined</th><th>Devices</th><th></th></tr>
      ${users.map((u) => h`<tr>
        <td>${u.email}${u.blocked && h` <span class="chip rejected">blocked</span>`}</td>
        <td>${u.display_name ?? ""}</td>
        <td><select data-role="${u.id}">${["reader", "author", "admin"].map((r) =>
          h`<option ${raw(r === u.role ? "selected" : "")}>${r}</option>`)}</select></td>
        <td>${fmtDate(u.created_at)}</td>
        <td><button class="link" data-act="devices" data-id="${u.id}">${u.devices} active</button></td>
        <td class="actions">
          <button data-act="grant" data-id="${u.id}">Grant book</button>
          <button class="${u.blocked ? "" : "danger"}" data-act="block" data-id="${u.id}" data-blocked="${u.blocked}">
            ${u.blocked ? "Unblock" : "Block"}</button></td>
      </tr><tr hidden id="dev-${u.id}"><td colspan="6"></td></tr>`)}
    </table></div>
    ${users.length === 0 && h`<p class="muted">No users match.</p>`}
  </section>`);
  view.querySelector("#search").addEventListener("submit", (e) => {
    e.preventDefault();
    location.hash = `#/admin/users?q=${encodeURIComponent(e.target.q.value.trim())}`;
  });
  view.querySelectorAll("[data-role]").forEach((sel) => sel.addEventListener("change", () => act(sel, async () => {
    must(await sb.from("profiles").update({ role: sel.value }).eq("id", sel.dataset.role));
    toast(`Role changed to ${sel.value}`);
  })));
  wire(view, {
    async block(_, d) {
      const blocking = d.blocked !== "true";
      if (blocking && !confirm("Block this user? Every device they're signed in on is signed out.")) return;
      must(await sb.rpc("set_blocked", { target: d.id, value: blocking }));
      render();
    },
    async grant(_, d) {
      const title = prompt("Title of the paid book to give this user (as published):");
      if (!title) return;
      const matches = must(await sb.from("books").select("id,title")
        .eq("status", "approved").ilike("title", `%${title.trim()}%`).limit(5));
      if (matches.length !== 1) {
        throw new Error(matches.length ? `Several books match: ${matches.map((b) => b.title).join(", ")}` : "No published book matches.");
      }
      must(await sb.from("entitlements").upsert(
        { user_id: d.id, book_id: matches[0].id, source: "grant" }, { onConflict: "user_id,book_id" }));
      toast(`Granted "${matches[0].title}"`);
    },
    async devices(_, d) {
      const row = view.querySelector(`#dev-${d.id}`);
      row.hidden = !row.hidden;
      if (row.hidden) return;
      const devices = must(await sb.from("devices").select("id,name,created_at,last_seen,revoked")
        .eq("user_id", d.id).order("created_at", { ascending: false }));
      show(row.firstElementChild, devices.length === 0 ? h`<span class="muted">No devices.</span>` : h`<table>
        ${devices.map((x) => h`<tr><td>${x.name ?? "Device"}</td><td>signed in ${fmtDate(x.created_at)}</td>
          <td>last seen ${fmtDate(x.last_seen)}</td>
          <td>${x.revoked ? h`<span class="chip rejected">signed out</span>`
            : h`<button class="danger" data-act="revoke" data-id="${x.id}">Sign out</button>`}</td></tr>`)}
      </table>`);
      wire(row, {
        async revoke(_, dd) {
          must(await sb.from("devices").update({ revoked: true }).eq("id", dd.id));
          toast("Device signed out");
          render();
        },
      });
    },
  });
}, isAdmin);

// Analytics -------------------------------------------------------------------

route(/^\/admin\/stats(?:\?days=(\d+))?$/, async (view, days = "30") => {
  const s = must(await sb.rpc("admin_stats", { days: Number(days) }));
  const bars = (rows, label) => {
    const max = Math.max(1, ...rows.map((r) => r.readers));
    return rows.length === 0 ? h`<p class="muted">No reading yet in this period.</p>` : h`<div class="bars">
      ${rows.map((r) => h`<div><span>${r[label]}</span>
        <span class="fill" style="width:${Math.max(2, (100 * r.readers) / max)}%"></span><span>${r.readers}</span></div>`)}
    </div>`;
  };
  show(view, h`<section class="card">
    <div class="actions" style="justify-content:space-between"><h1>Analytics</h1>
      <span class="actions">${[7, 30, 90].map((d) =>
        h`<a class="button${String(d) === days ? " primary" : ""}" href="#/admin/stats?days=${d}">${d} days</a>`)}</span>
    </div>
    <div class="stats">
      <div class="stat"><b>${s.users}</b>accounts</div>
      <div class="stat"><b>${s.new_users}</b>new in ${days} days</div>
      <div class="stat"><b>${s.active_users}</b>active readers</div>
      ${["approved", "pending", "draft", "rejected"].map((st) =>
        h`<div class="stat"><b>${s.books[st] ?? 0}</b>${st} books</div>`)}
    </div>
    <h2>Most-read books</h2>${bars(s.top_books, "title")}
    <h2>Most-read authors</h2>${bars(s.top_authors, "name")}
    <h2>Active readers by day</h2>${bars(s.daily, "day")}
    <p class="muted">A reader counts as active on the day they last read a book. Reading time arrives with stats sync.</p>
  </section>`);
}, isAdmin);
