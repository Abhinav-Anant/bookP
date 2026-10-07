# APA setup: Supabase backend + Android build

APA is a fork of Liseur. The Android app talks to Supabase through one
Edge Function (`supabase/functions/api`) that answers like a Komga
server. The app's existing, tested catalogue, download and sync code
therefore works unchanged. Each phone signs in once (email OTP or
Google) and receives its own **device key**, which you can revoke from
the database.

```
Admin (Supabase Studio) ──> Postgres + Storage ──> Edge Function "api" ──> APA app
                                                     (Komga subset + /apa/*)
```

## 1. Create the Supabase project

1. Create a project at https://supabase.com (the free tier is fine to start).
2. Install the Supabase CLI and link the repository. Run these from the
   repository root:

   ```bash
   supabase login
   supabase link --project-ref <your-project-ref>
   supabase db push
   ```

   This applies `supabase/migrations/20260924000000_init.sql`, which creates:
   - the tables
   - row-level security
   - the `covers` bucket (public) and the `books` bucket (private)

3. Optional sample data: run `supabase/seed.sql` in the SQL editor.
4. Deploy the API:

   ```bash
   supabase functions deploy api --no-verify-jwt
   ```

   `--no-verify-jwt` is required. The function checks device keys itself,
   and the app does not send a Supabase JWT on catalogue calls.

5. Check the database with `supabase test db`, which runs
   `supabase/tests/rls.sql` and needs Docker.

## 2. Auth settings (Supabase dashboard → Authentication)

- **Email:** enable the Email provider and "Confirm email". For
  sign-in by code, edit both the **Confirm signup** template (a first
  sign-in gets this one) and the **Magic Link** template so they contain
  `{{ .Token }}`. The app asks for the 6-digit code; it never uses the
  link.
- **SMTP:** set up custom SMTP (for example Amazon SES, Resend or
  Zoho). The built-in sender is heavily rate-limited.
- **Google:**
  1. In Google Cloud Console, create an OAuth **Web** client.
  2. Put its client ID and secret into Supabase → Auth → Providers → Google.
  3. Create an OAuth **Android** client with package `in.anant.apa` and
     your signing SHA-1. On this build machine the debug SHA-1 is
     `AA:C6:28:0B:F6:99:BA:DF:45:94:88:C2:75:AF:54:8F:5B:C5:12:D1`. Add
     the release or Play App Signing SHA-1 too.

## 3. Build the app

Add to `local.properties` (never committed):

```properties
apa.supabaseUrl=https://<project-ref>.supabase.co
apa.supabaseAnonKey=<anon public key>
apa.googleWebClientId=<web client id>.apps.googleusercontent.com
```

Then build:

```bash
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleDev          # installs beside the real app as in.anant.apa.dev
```

When `apa.supabaseUrl` is empty, the app behaves like plain Liseur: a
local reader with no sign-in screen.

## 4. Publisher and admin portal

`portal/` is a static web app: plain HTML and JavaScript, with no build
step. Authors use it to apply, upload books and submit them. Admins use
it to approve books, manage users and see analytics. All of its rules
are enforced in the database (`supabase/migrations/20261007000000_portal.sql`).

1. **Configure it.** Copy `portal/config.example.js` to `portal/config.js`,
   which is gitignored. Fill in the project URL and the anon key.
2. **Run it locally:** `python -m http.server 5500 --directory portal`, then
   open http://localhost:5500.
3. **Host it.** Drag the `portal/` folder (including `config.js`) onto
   Netlify Drop, or use any other static host.
4. **Make the first admin:**
   `update profiles set role = 'admin' where id = (select id from auth.users where email = '…');`
5. **Add the portal's address** to Supabase → Authentication → URL
   Configuration → Redirect URLs, for example `http://localhost:5500`
   and the hosted URL.
6. **Google sign-in on the web.**
   - In Google Cloud, add
     `https://<project-ref>.supabase.co/auth/v1/callback` to the **Web**
     client's authorized redirect URIs.
   - Make sure the Supabase Google provider has the client secret.
   - Email sign-in works without either step.

**How a book gets published:**
1. A reader signs in and applies.
2. An admin approves the application under **Review**.
3. The author adds a book with its cover and EPUB, PDF or CBZ file, then
   submits it.
4. The admin approves it, and it appears in every app at the next
   refresh.

Authors can edit only their own drafts and rejected books. Only admins
can approve a book or feature it.

**Blocking a user** (Users → Block) signs out all of their devices at
once. They can't sign in a new device until they're unblocked.

Run the database tests against the linked project (no Docker needed)
with `bash supabase/tests/run-linked.sh supabase/tests/portal.sql`.

### Publishing with SQL (fallback)

1. **Author:** `insert into authors (name, bio) values (...)`.
2. **Files:** upload the book file to the `books` bucket, for example
   `2026/gita.epub`. Upload the cover image to `covers`, for example
   `2026/gita.jpg`.
3. **Book row:** insert it into `books` with `status = 'approved'`.
   - Set `is_free` and `price_inr`.
   - Optionally set `featured` and `editors_pick`, which drive Home rows.
4. **File row:** `insert into book_files (book_id, format, storage_path)
   values (..., 'epub', '2026/gita.epub')`. The format is `epub`, `pdf` or
   `cbz`.

The book appears on Home in every app at the next refresh. No new APK
is needed.

**To give a user a paid book** (until payments exist), use Users → Grant book, or insert a row into
`entitlements (user_id, book_id, source) values (..., ..., 'grant')`.

**To sign a device out** (for example, a lost phone): use Users → devices in the portal, or set
`devices.revoked = true`. It gets a 401 on its next request.

## How books stay inside the app

- **Access:** files are served only through 5-minute signed URLs, and
  only to a device key whose user is entitled to the book. Free books
  are granted on first use.
- **Encryption on the phone:** after download, each book is encrypted
  (Tink AES-256-GCM streaming, see `data/apa/BookVault.kt`) with a key
  wrapped by the Android Keystore. Readium decrypts byte ranges in
  memory while reading, and nothing plain is left on disk. A copied
  file cannot be opened on any other device, or on this one outside
  APA.
- **No way out:** the app never offers share, export or "open with" for
  books, and books are left out of Android backups.
- **Not yet built (Phase 4):** watermarking, blocking screenshots
  (FLAG_SECURE), and the payment flows.

## API reference (the `api` function)

| Route | Purpose |
|---|---|
| `POST /apa/devices` | Mints a device key. Needs `Authorization: Bearer <user JWT>`. |
| `GET /api/v2/users/me` | Komga probe; the app calls it when connecting. |
| `POST /api/v1/books/list` | The user's library (entitled books), in pages. |
| `GET /api/v1/books/{id}` | One library book. |
| `GET /api/v1/books/{id}/file` | Checks entitlement, then redirects (302) to a signed URL. |
| `GET/PUT /api/v1/books/{id}/progression` | Reading position sync. A stale PUT answers 409. |
| `PATCH/DELETE /api/v1/books/{id}/read-progress` | Marks a book finished, or clears its progress. |
| `GET /apa/home` | Home rows plus favourite ids. |
| `GET /apa/books/{id}` | Book detail page. |
| `GET /apa/search?q=` | Searches title, publisher, ISBN, genre and author. |
| `PUT /apa/library/{id}` | Sets `{favorite, wishlist, add}`. `add` on a paid book answers 402. |
| `GET /apa/devices` | The account's signed-in devices; `current` marks the caller. |
| `DELETE /apa/devices/{id}` | Revokes that device's key (Settings → Signed-in devices). |

Every route except `POST /apa/devices` and thumbnails needs the `X-API-Key: <device key>` header.
