# Publisher and admin portal: implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** a static web portal where authors apply, upload and submit
books, and admins approve books and run users and analytics. It runs on
the existing Supabase project.

**Architecture:** one new migration adds author ownership, the review
workflow, blocking, and admin and author stats functions. Every rule is
enforced in Postgres (RLS, triggers and security-definer functions). The
portal in `portal/` is plain ES-module JavaScript that calls Supabase
directly through supabase-js, with no build step.

**Tech Stack:** Postgres/Supabase (RLS, plpgsql, pgTAP), Deno Edge
Function (one small change), HTML/CSS/ES modules, supabase-js v2 from
jsdelivr, and Node's built-in test runner for the one pure-logic module.

**Spec:** `docs/superpowers/specs/2026-10-07-publisher-admin-portal-design.md`

## Global Constraints

- **Repo rules:** no AI co-author trailers in commits. Commit only with the
  user's approval (granted for this plan's task commits on 2026-10-07).
- **Where rules live:** security is enforced in the database, and the
  portal's hiding of buttons is cosmetic only.
- **Guard bypass:** triggers skip their checks when
  `current_user not in ('authenticated', 'anon')`. That covers the SQL
  editor, service_role (the Edge Function) and security-definer
  functions.
- **Storage paths for author uploads:**
  - books bucket: `{uid}/{bookId}.{ext}`
  - covers bucket: `{uid}/{bookId}.{jpg|png|webp}`
- **Book file formats:** only `epub`, `pdf` and `cbz` (the existing
  `book_files` check).
- **Brand:** purple `#6B3FA0`, with light `#EBDDFF` and dark `#3F1D6B`.
- **No Docker on this machine:** pgTAP runs against the linked project
  inside `begin … rollback`.

  `npx supabase db query --linked -f supabase/tests/portal.sql`

---

### Task 1: Migration and database tests

**Files:**
- Create: `supabase/migrations/20261007000000_portal.sql`
- Create: `supabase/tests/portal.sql`

**Interfaces (produced, used by later tasks):**
- `profiles.blocked`, `authors.profile_id`, `books.owner_id`,
  `books.rejection_reason`, `books.submitted_at`
- table `author_applications(user_id, display_name, bio, status, rejection_reason, created_at)`
- `is_author() → boolean`
- `review_author_application(applicant uuid, approve boolean, reason text default null) → void`
- `admin_users(q text default '') → table(id, email, display_name, role, blocked, created_at, devices)`
- `set_blocked(target uuid, value boolean) → void`
- `my_book_stats() → table(book_id, readers, downloads, finished)`
- `admin_stats(days int default 30) → jsonb {users,new_users,active_users,books,top_books,top_authors,daily}`

- [ ] **Step 1: Write the pgTAP tests (`supabase/tests/portal.sql`).** Fixtures:
  - an admin, author A, author B and a reader;
  - A's draft, B's draft, and A's approved book.

  Assertions:
  - A sees own drafts, but not B's draft;
  - A can't update B's book (0 rows);
  - A can't set status `approved` (42501);
  - A's update keeps `featured = false`;
  - A can't edit their approved book (42501);
  - submitting stamps `submitted_at`;
  - the reader can't insert a book (RLS 42501);
  - a non-admin calling `admin_users` / `set_blocked` /
    `review_author_application` gets 42501;
  - a reader can't set `blocked` on themselves (42501);
  - approving an application makes the user an author with an
    `authors` row;
  - `set_blocked` revokes devices, and `device_user` returns null for a
    blocked user's live key.
- [ ] **Step 2: Run the tests and confirm they fail.** The columns and
  functions don't exist yet.
- [ ] **Step 3: Write the migration** (the SQL is in the migration file:
  columns, `author_applications` with its RLS, `profiles_guard`,
  `books_guard`, the author book, file and storage policies,
  `device_user` with the blocked check, and the five functions).
- [ ] **Step 4: Apply it** with `npx supabase db push --linked`. If the CLI asks
  for the DB password, the user runs it.
- [ ] **Step 5: Run the tests, both suites green:** `portal.sql` and `rls.sql`.
- [ ] **Step 6: Commit.**

  `git commit -m "feat(db): author ownership, review workflow, blocking and stats"`

### Task 2: Refuse device keys for blocked users

**Files:** Modify `supabase/functions/api/index.ts`, in `mintDevice`.

- [ ] Before inserting, read `profiles.blocked` for the user and throw
  `HttpError(403, "account blocked")` if it is set.
- [ ] Deploy with `npx supabase functions deploy api --no-verify-jwt`.
  Then check that `curl` of `/api/v2/users/me` with no key still
  answers 401.
- [ ] Commit: `fix(api): blocked accounts cannot mint device keys`.

### Task 3: Portal shell, sign-in and applying to publish

**Files:**
- Create: `portal/index.html`, `portal/style.css`, `portal/app.js`
- Create: `portal/lib.js`, `portal/lib.test.mjs`
- Create: `portal/config.example.js`
- Create: `portal/.gitignore` (`config.js`)

**Interfaces:**
- **`lib.js` (pure):**
  - `formatOf(fileName) → 'epub'|'pdf'|'cbz'|null`
  - `parseGenres(text) → string[]`
  - `sha256Hex(arrayBuffer) → Promise<string>`
  - `escapeHtml(s) → string`
  - `fmtBytes(n) → string`
- **`app.js`:**
  - exports `sb` (the client), `session()`, `profile()`, `route(path, render)`,
    `h(strings, ...values)` (an HTML template helper that escapes values),
    `toast(msg, isError)`, `go(hash)`;
  - imports `author.js` and `admin.js`, which register their routes.

- [ ] **Step 1:** write `lib.test.mjs` with `node:test` and `assert`.
  - `formatOf('A.EPUB') === 'epub'`; `'x.cbr'` gives null.
  - `parseGenres(' Poetry, ,Myth ')` gives `['Poetry','Myth']`.
  - `sha256Hex` of `"abc"` equals `ba7816bf…15ad`.
  - `escapeHtml('<a&">')`.
- [ ] **Step 2:** `node --test portal/lib.test.mjs` fails, because there is no module.
- [ ] **Step 3:** implement `lib.js`, then run `node --test portal/lib.test.mjs`: it passes.
- [ ] **Step 4:** add the shell.
  - Nav shows Home and My books, plus Admin when the user is an admin.
  - Sign-in is an email OTP form (code step), with Google through
    `signInWithOAuth({provider:'google', options:{redirectTo: location.origin + location.pathname}})`.
  - A blocked account sees a notice.
  - The reader landing shows the application form, or its status and
    rejection reason with "Apply again".
- [ ] **Step 5:** serve locally with `npx serve portal -l 5500`. Sign in with
  email OTP in the built-in browser and submit an application.
- [ ] **Step 6:** commit: `feat(portal): shell, sign-in and author applications`.

### Task 4: Author: my books and the book editor

**Files:** Create `portal/author.js`.

- [ ] **`#/books`:**
  - a list of the caller's books (`books` where `owner_id = uid`)
    joined with `my_book_stats()`;
  - status chips and the rejection reason;
  - a "New book" button.
- [ ] **`#/book/{id|new}`:** a form with every field in the spec.
  - The cover input shows a preview.
  - The file input reads the format with `formatOf`, and size and hash
    with `sha256Hex`.
  - **Save, in this order:**
    1. Upsert the book row as draft, to get its id.
    2. Upload the files (`upsert: true`) to the constrained paths.
    3. Update `cover_path` on the book, and upsert the `book_files`
       row on `book_id,format`.
  - "Submit for approval" saves, then sets `status = 'pending'`.
  - A failed upload leaves the book's rows untouched (the files go up
    before the rows change).
  - Admins also see the Featured and Editor's pick toggles and a status
    select.
- [ ] **Manual check:** create a draft with an EPUB, submit it, and see it
  read-only as pending.
- [ ] Commit: `feat(portal): author book list and editor`.

### Task 5: Admin: review queue, books and authors

**Files:** Create `portal/admin.js`.

- [ ] **`#/admin` (review queue):**
  - pending applications get Approve, or Reject with a prompted reason
    (through `review_author_application`);
  - pending books get Open (the editor), Approve (`status = 'approved'`,
    reason cleared), or Reject (`status = 'rejected'`,
    `rejection_reason`).
- [ ] **`#/admin/books`:**
  - search by title and filter by status;
  - Edit (the editor), Unpublish (back to draft) and Delete (with a
    confirm; also removes the storage objects).
- [ ] **`#/admin/authors`:** edit the name, bio and photo URL inline.
- [ ] **Manual check:** approve the Task 3 application and the Task 4 book.
  The book appears on the emulator's Home after a refresh.
- [ ] Commit: `feat(portal): admin review queue, books and authors`.

### Task 6: Admin: users and analytics

**Files:** Modify `portal/admin.js`.

- [ ] **`#/admin/users`:**
  - search with `admin_users(q)`;
  - a role select (direct `profiles` update);
  - Block/Unblock with `set_blocked`;
  - "Grant book": pick an approved paid book, then insert an
    entitlement with source `grant`;
  - Devices: a list from `devices` with Revoke.
- [ ] **`#/admin/stats`:** a 7/30/90 day picker over `admin_stats(days)`.
  - Numbers for users, new users and active users.
  - Books by status.
  - Top books and top authors, as CSS bar lists.
  - Daily active readers.
- [ ] **Manual check:** block and unblock a test user, and see their devices
  revoked.
- [ ] Commit: `feat(portal): admin users and analytics`.

### Task 7: Docs

**Files:** Modify `docs/APA_SETUP.md`.

- [ ] Replace "Publish a book (until the admin portal exists)" with a
  portal section:
  - running it locally;
  - hosting it on Netlify;
  - making the first admin;
  - adding the redirect URL;
  - adding the Google web callback.

  Keep the SQL route as a fallback note.
- [ ] Commit: `docs: publisher and admin portal setup`.
