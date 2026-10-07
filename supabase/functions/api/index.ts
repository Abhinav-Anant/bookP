// APA cloud API. Speaks the subset of Komga's REST API that the Android
// app's Komga client uses, plus /apa/* routes for discovery and the
// reader's library flags. Authenticates with per-device keys (X-API-Key)
// minted by POST /apa/devices from a Supabase user session.
//
// Deploy: supabase functions deploy api --no-verify-jwt
import { createClient, type SupabaseClient } from "jsr:@supabase/supabase-js@2";
import { type BookRow, isStale, newDeviceKey, toKomgaBook, toKomgaPage } from "./komga.ts";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL")!;
const db: SupabaseClient = createClient(SUPABASE_URL, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!, {
  auth: { persistSession: false },
});

const SIGNED_URL_SECONDS = 300;
const FORMAT_PREFERENCE = ["epub", "pdf", "cbz"];
const BOOK_COLUMNS =
  "id,title,subtitle,publisher,description,genres,language,pages,published_on,isbn,cover_path," +
  "price_inr,is_free,updated_at,author_id,authors(id,name,bio),book_files(format,size_bytes,storage_path)";

class HttpError extends Error {
  constructor(readonly status: number, message: string) {
    super(message);
  }
}

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

// deno-lint-ignore no-explicit-any
type Book = any;

function must<T>(result: { data: T; error: { message: string } | null }): T {
  if (result.error) throw new HttpError(500, result.error.message);
  return result.data;
}

function coverUrl(path: string | null): string | null {
  return path ? `${SUPABASE_URL}/storage/v1/object/public/covers/${path}` : null;
}

function preferredFile(b: Book, format?: string | null) {
  const files: { format: string; size_bytes: number | null; storage_path: string }[] = b.book_files ?? [];
  if (format) return files.find((f) => f.format === format) ?? null;
  return FORMAT_PREFERENCE.map((f) => files.find((x) => x.format === f)).find(Boolean) ?? null;
}

function card(b: Book) {
  return {
    id: b.id,
    title: b.title,
    author: b.authors?.name ?? null,
    coverUrl: coverUrl(b.cover_path),
    isFree: b.is_free,
    priceInr: Number(b.price_inr ?? 0),
    language: b.language,
    genres: b.genres ?? [],
  };
}

function bookRow(b: Book, state?: BookRow["reading_state"]): BookRow {
  const file = preferredFile(b);
  return {
    id: b.id,
    title: b.title,
    author_name: b.authors?.name ?? null,
    pages: b.pages,
    updated_at: b.updated_at,
    size_bytes: file?.size_bytes ?? null,
    format: file?.format ?? null,
    reading_state: state ?? null,
  };
}

// --- Auth -------------------------------------------------------------------

async function deviceUser(req: Request): Promise<string> {
  const key = req.headers.get("x-api-key");
  if (!key) throw new HttpError(401, "missing device key");
  const uid = must(await db.rpc("device_user", { key })) as string | null;
  if (!uid) throw new HttpError(401, "unknown or revoked device key");
  return uid;
}

async function keyHash(key: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(key));
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

async function mintDevice(req: Request): Promise<Response> {
  const token = req.headers.get("authorization")?.replace(/^Bearer\s+/i, "");
  if (!token) throw new HttpError(401, "missing session");
  const { data, error } = await db.auth.getUser(token);
  if (error || !data.user) throw new HttpError(401, "invalid session");
  const profile = must(await db.from("profiles").select("blocked").eq("id", data.user.id).maybeSingle());
  if (profile?.blocked) throw new HttpError(403, "account blocked");
  const body = await req.json().catch(() => ({}));
  const key = newDeviceKey();
  must(await db.from("devices").insert({
    user_id: data.user.id,
    name: String(body.name ?? "Android").slice(0, 80),
    key_hash: await keyHash(key),
  }));
  return json({ key });
}

/** The caller's signed-in devices; `current` marks the one asking. */
async function listDevices(req: Request, uid: string): Promise<Response> {
  const mine = await keyHash(req.headers.get("x-api-key")!);
  const rows = must(
    await db.from("devices").select("id,name,created_at,last_seen,key_hash")
      .eq("user_id", uid).eq("revoked", false).order("last_seen", { ascending: false, nullsFirst: false }),
  ) as { id: string; name: string | null; created_at: string; last_seen: string | null; key_hash: string }[];
  return json({
    devices: rows.map((d) => ({
      id: d.id,
      name: d.name,
      createdAt: d.created_at,
      lastSeen: d.last_seen,
      current: d.key_hash === mine,
    })),
  });
}

async function revokeDevice(id: string, uid: string): Promise<Response> {
  if (!UUID.test(id)) throw new HttpError(404, "no such device");
  must(await db.from("devices").update({ revoked: true }).eq("id", id).eq("user_id", uid));
  return new Response(null, { status: 204 });
}

// --- Library helpers --------------------------------------------------------

async function entitledIds(uid: string): Promise<string[]> {
  const rows = must(await db.from("entitlements").select("book_id").eq("user_id", uid)) as { book_id: string }[];
  return rows.map((r) => r.book_id);
}

async function readingStates(uid: string, ids: string[]) {
  if (ids.length === 0) return new Map();
  const rows = must(
    await db.from("reading_state").select("book_id,completed,updated_at,device_id").eq("user_id", uid).in(
      "book_id",
      ids,
    ),
  ) as { book_id: string; completed: boolean; updated_at: string; device_id: string | null }[];
  return new Map(rows.map((r) => [r.book_id, r]));
}

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

async function approvedBook(id: string): Promise<Book> {
  if (!UUID.test(id)) throw new HttpError(404, "no such book");
  const b = must(await db.from("books").select(BOOK_COLUMNS).eq("id", id).eq("status", "approved").maybeSingle());
  if (!b) throw new HttpError(404, "no such book");
  return b;
}

/** Grants a free book on first use; true when the user may read it. */
async function ensureEntitled(uid: string, b: Book): Promise<boolean> {
  const has = must(
    await db.from("entitlements").select("book_id").eq("user_id", uid).eq("book_id", b.id).maybeSingle(),
  );
  if (has) return true;
  if (!b.is_free) return false;
  must(await db.from("entitlements").upsert({ user_id: uid, book_id: b.id, source: "free" }));
  return true;
}

// --- Komga subset -----------------------------------------------------------

async function listLibrary(req: Request, url: URL, uid: string): Promise<Response> {
  const page = Math.max(0, Number(url.searchParams.get("page") ?? 0));
  const size = Math.min(500, Math.max(1, Number(url.searchParams.get("size") ?? 200)));
  const body = await req.json().catch(() => ({}));
  const ids = await entitledIds(uid);
  if (ids.length === 0) return json(toKomgaPage([], size));
  let q = db.from("books").select(BOOK_COLUMNS).in("id", ids).eq("status", "approved");
  const search = typeof body.fullTextSearch === "string" ? body.fullTextSearch.trim() : "";
  if (search) q = q.ilike("title", `%${search.replace(/[%_]/g, "")}%`);
  const books = must(await q.order("title").range(page * size, page * size + size - 1)) as Book[];
  const states = await readingStates(uid, books.map((b) => b.id));
  return json(toKomgaPage(books.map((b) => bookRow(b, states.get(b.id))), size));
}

async function oneBook(id: string, uid: string): Promise<Response> {
  const b = await approvedBook(id);
  const states = await readingStates(uid, [id]);
  return json(toKomgaBook(bookRow(b, states.get(id))));
}

async function bookFile(id: string, url: URL, uid: string): Promise<Response> {
  const b = await approvedBook(id);
  if (!(await ensureEntitled(uid, b))) throw new HttpError(403, "not in your library");
  const file = preferredFile(b, url.searchParams.get("format"));
  if (!file) throw new HttpError(404, "no file for this book");
  const signed = must(await db.storage.from("books").createSignedUrl(file.storage_path, SIGNED_URL_SECONDS)) as {
    signedUrl: string;
  };
  return Response.redirect(signed.signedUrl, 302);
}

async function getProgression(id: string, uid: string): Promise<Response> {
  if (!UUID.test(id)) throw new HttpError(404, "no progression");
  const row = must(
    await db.from("reading_state").select("locator,updated_at,device_id,device_name")
      .eq("user_id", uid).eq("book_id", id).maybeSingle(),
  ) as Book;
  if (!row?.locator) throw new HttpError(404, "no progression");
  return json({
    locator: row.locator,
    modified: row.updated_at,
    device: { id: row.device_id, name: row.device_name },
  });
}

async function putProgression(req: Request, id: string, uid: string): Promise<Response> {
  const body = await req.json();
  if (!body?.locator || typeof body.modified !== "string") throw new HttpError(400, "locator and modified required");
  await approvedBook(id);
  const stored = must(
    await db.from("reading_state").select("updated_at").eq("user_id", uid).eq("book_id", id).maybeSingle(),
  ) as { updated_at: string } | null;
  if (isStale(stored?.updated_at, body.modified)) throw new HttpError(409, "a newer position is stored");
  must(
    await db.from("reading_state").upsert({
      user_id: uid,
      book_id: id,
      locator: body.locator,
      updated_at: body.modified,
      device_id: body.device?.id ?? null,
      device_name: body.device?.name ?? null,
    }),
  );
  return new Response(null, { status: 204 });
}

async function readProgress(req: Request, id: string, uid: string): Promise<Response> {
  await approvedBook(id);
  if (req.method === "DELETE") {
    must(await db.from("reading_state").delete().eq("user_id", uid).eq("book_id", id));
  } else {
    const body = await req.json().catch(() => ({}));
    must(
      await db.from("reading_state").upsert({
        user_id: uid,
        book_id: id,
        completed: body.completed === true,
        updated_at: new Date().toISOString(),
      }),
    );
  }
  return new Response(null, { status: 204 });
}

async function thumbnail(id: string): Promise<Response> {
  if (!UUID.test(id)) throw new HttpError(404, "no cover");
  const b = must(await db.from("books").select("cover_path").eq("id", id).eq("status", "approved").maybeSingle()) as
    | { cover_path: string | null }
    | null;
  const cover = coverUrl(b?.cover_path ?? null);
  if (!cover) throw new HttpError(404, "no cover");
  return Response.redirect(cover, 302);
}

async function me(uid: string): Promise<Response> {
  const { data } = await db.auth.admin.getUserById(uid);
  return json({ id: uid, email: data.user?.email ?? null, roles: ["FILE_DOWNLOAD"] });
}

// --- APA discovery ----------------------------------------------------------

async function flags(uid: string) {
  const rows = must(await db.from("library_items").select("book_id,favorite,wishlist").eq("user_id", uid)) as {
    book_id: string;
    favorite: boolean;
    wishlist: boolean;
  }[];
  return rows;
}

async function home(uid: string): Promise<Response> {
  const base = () => db.from("books").select(BOOK_COLUMNS).eq("status", "approved");
  const trending = must(await db.rpc("trending_books", { days: 7, lim: 20 })) as { book_id: string }[];
  const userFlags = await flags(uid);
  const wishIds = userFlags.filter((f) => f.wishlist).map((f) => f.book_id);
  const [featured, picks, fresh, free, hot, wish] = await Promise.all([
    base().eq("featured", true).limit(20),
    base().eq("editors_pick", true).limit(20),
    base().order("published_on", { ascending: false, nullsFirst: false }).limit(20),
    base().eq("is_free", true).order("updated_at", { ascending: false }).limit(20),
    trending.length ? base().in("id", trending.map((t) => t.book_id)) : Promise.resolve({ data: [], error: null }),
    wishIds.length ? base().in("id", wishIds) : Promise.resolve({ data: [], error: null }),
  ]);
  const order = new Map(trending.map((t, i) => [t.book_id, i]));
  const hotSorted = (must(hot) as Book[]).sort((a, b) => (order.get(a.id) ?? 0) - (order.get(b.id) ?? 0));
  const rows = [
    { key: "featured", books: must(featured) },
    { key: "editors_pick", books: must(picks) },
    { key: "trending", books: hotSorted },
    { key: "new", books: must(fresh) },
    { key: "free", books: must(free) },
    { key: "wishlist", books: must(wish) },
  ].map((r) => ({ key: r.key, books: (r.books as Book[]).map(card) })).filter((r) => r.books.length > 0);
  return json({ rows, favoriteIds: userFlags.filter((f) => f.favorite).map((f) => f.book_id) });
}

async function detail(id: string, uid: string): Promise<Response> {
  const b = await approvedBook(id);
  const [ent, item, more] = await Promise.all([
    db.from("entitlements").select("book_id").eq("user_id", uid).eq("book_id", id).maybeSingle(),
    db.from("library_items").select("favorite,wishlist").eq("user_id", uid).eq("book_id", id).maybeSingle(),
    b.author_id
      ? db.from("books").select(BOOK_COLUMNS).eq("status", "approved").eq("author_id", b.author_id).neq("id", id)
        .limit(10)
      : Promise.resolve({ data: [], error: null }),
  ]);
  const flag = must(item) as { favorite: boolean; wishlist: boolean } | null;
  return json({
    ...card(b),
    subtitle: b.subtitle,
    publisher: b.publisher,
    description: b.description,
    pages: b.pages,
    publishedOn: b.published_on,
    isbn: b.isbn,
    formats: (b.book_files ?? []).map((f: { format: string }) => f.format),
    authorBio: b.authors?.bio ?? null,
    inLibrary: !!must(ent),
    favorite: flag?.favorite ?? false,
    wishlist: flag?.wishlist ?? false,
    moreByAuthor: (must(more) as Book[]).map(card),
  });
}

async function setLibrary(req: Request, id: string, uid: string): Promise<Response> {
  const b = await approvedBook(id);
  const body = await req.json().catch(() => ({}));
  if (body.add === true && !(await ensureEntitled(uid, b))) {
    throw new HttpError(402, "purchase required");
  }
  const patch: Record<string, boolean> = {};
  if (typeof body.favorite === "boolean") patch.favorite = body.favorite;
  if (typeof body.wishlist === "boolean") patch.wishlist = body.wishlist;
  if (Object.keys(patch).length > 0) {
    must(await db.from("library_items").upsert({ user_id: uid, book_id: id, ...patch }));
  }
  return new Response(null, { status: 204 });
}

async function search(url: URL): Promise<Response> {
  const q = (url.searchParams.get("q") ?? "").trim();
  if (!q) return json({ books: [] });
  const byText = must(
    await db.from("books").select(BOOK_COLUMNS).eq("status", "approved")
      .textSearch("search", q, { type: "websearch", config: "simple" }).limit(40),
  ) as Book[];
  // Author names live in another table; match them separately.
  const authors = must(await db.from("authors").select("id").ilike("name", `%${q.replace(/[%_]/g, "")}%`)) as {
    id: string;
  }[];
  const byAuthor = authors.length
    ? must(
      await db.from("books").select(BOOK_COLUMNS).eq("status", "approved").in("author_id", authors.map((a) => a.id))
        .limit(40),
    ) as Book[]
    : [];
  const seen = new Set<string>();
  const books = [...byText, ...byAuthor].filter((b) => !seen.has(b.id) && seen.add(b.id));
  return json({ books: books.map(card) });
}

// --- Router -----------------------------------------------------------------

async function route(req: Request): Promise<Response> {
  const url = new URL(req.url);
  // The gateway may or may not keep the /functions/v1 prefix; the function name always leads.
  const path = url.pathname.replace(/^\/functions\/v1/, "").replace(/^\/api(?=\/)/, "");
  const m = req.method;
  let g: RegExpMatchArray | null;

  if (m === "POST" && path === "/apa/devices") return mintDevice(req);
  if (m === "GET" && (g = path.match(/^\/api\/v1\/books\/([\w-]+)\/thumbnail$/))) return thumbnail(g[1]);

  const uid = await deviceUser(req);

  if (m === "GET" && path === "/api/v2/users/me") return me(uid);
  if (m === "POST" && path === "/api/v1/books/list") return listLibrary(req, url, uid);
  if (m === "GET" && (g = path.match(/^\/api\/v1\/books\/([\w-]+)$/))) return oneBook(g[1], uid);
  if (m === "GET" && (g = path.match(/^\/api\/v1\/books\/([\w-]+)\/file$/))) return bookFile(g[1], url, uid);
  if ((g = path.match(/^\/api\/v1\/books\/([\w-]+)\/progression$/))) {
    if (m === "GET") return getProgression(g[1], uid);
    if (m === "PUT") return putProgression(req, g[1], uid);
  }
  if ((g = path.match(/^\/api\/v1\/books\/([\w-]+)\/read-progress$/)) && (m === "PATCH" || m === "DELETE")) {
    return readProgress(req, g[1], uid);
  }
  if (m === "GET" && path.startsWith("/api/v1/series/")) throw new HttpError(404, "no series");

  if (m === "GET" && path === "/apa/home") return home(uid);
  if (m === "GET" && path === "/apa/search") return search(url);
  if (m === "GET" && (g = path.match(/^\/apa\/books\/([\w-]+)$/))) return detail(g[1], uid);
  if (m === "PUT" && (g = path.match(/^\/apa\/library\/([\w-]+)$/))) return setLibrary(req, g[1], uid);
  if (m === "GET" && path === "/apa/devices") return listDevices(req, uid);
  if (m === "DELETE" && (g = path.match(/^\/apa\/devices\/([\w-]+)$/))) return revokeDevice(g[1], uid);

  throw new HttpError(404, `no route for ${m} ${path}`);
}

Deno.serve(async (req) => {
  try {
    return await route(req);
  } catch (e) {
    if (e instanceof HttpError) return json({ error: e.message }, e.status);
    console.error(e);
    return json({ error: "internal error" }, 500);
  }
});
