# Publisher and admin portal: design

Date: 2026-10-07. Status: approved in chat; awaiting spec review.

## Goal

Authors and publishers get books into the APA cloud library, and the
admin approves them and runs the platform, without writing SQL. Books
reach every app at its next refresh, as they do today.

## Decisions

- **The site:** a plain HTML, CSS and JavaScript web app in `portal/`, with no
  build step and no server of its own. It talks to Supabase directly
  with `@supabase/supabase-js` v2, loaded from jsdelivr at a pinned version.
- **Hosting:** any static host (Netlify, Vercel or GitHub Pages), or
  opening the folder locally.
- **Becoming an author:** a signed-in user applies, and an admin approves
  the application. Books still go through approval.
- **Accounts:** the same as the app's. Email OTP works now. Google
  works once the web OAuth redirect is configured (see Setup).
- **Security:** enforced by Postgres (RLS, triggers and security-definer
  functions), never by which buttons the page shows.

## Out of scope (until the feature they depend on exists)

- Revenue and subscription management (needs payments).
- Review moderation and report management (needs reviews).
- Copyright complaints (needs a complaint intake).
- Per-chapter editing: an uploaded EPUB already carries its chapters.

## Database (migration `20261007000000_portal.sql`)

### Schema

- `profiles.blocked boolean not null default false`.
- `authors.profile_id uuid unique references profiles`: the public author
  page that belongs to a portal account.
- `books.owner_id uuid references profiles` (null for admin-entered books),
  `books.rejection_reason text`, `books.submitted_at timestamptz`.
- `author_applications`:
  - columns: `user_id` (pk, references profiles), `display_name`, `bio`,
    `status` (`pending`, `approved` or `rejected`), `rejection_reason`,
    `created_at`;
  - the owner inserts and reads their own row, and can re-apply after a
    rejection;
  - admins read all rows.

### Guards

- **Profile trigger `profiles_guard`:** a non-admin can't change `role` or
  `blocked`.
  - This replaces the current `with check (role = 'reader')`, which would
    stop an author from editing their own display name.
- **Book trigger `books_guard`,** for non-admins:
  - `owner_id` must be the caller, and `author_id` is filled from the
    caller's `authors` row;
  - an update is allowed only while the old status is `draft` or `rejected`;
  - the new status may only be `draft` or `pending`, and moving to
    `pending` stamps `submitted_at`;
  - `featured` and `editors_pick` can't be changed.
- **`device_user(key)`** also returns null when the owner is `blocked`, so a
  blocked user's every request fails at the one gate all API calls pass.
  - The `POST /apa/devices` function refuses to mint a key for a blocked user.

### RLS additions

- **Books:**
  - an author selects their own books in any status;
  - an author inserts and updates their own books (the trigger narrows
    which changes are allowed);
  - an author deletes only their own drafts.
- **`book_files`:** an author manages the files of their own books
  while each book is `draft` or `rejected`.
- **Storage:** an author writes to `books/{uid}/…` and `covers/{uid}/…`
  only. Admins keep full access.

### Functions

- `review_author_application(user_id, approve, reason)`, admin only:
  - approving sets `role = 'author'` and creates the `authors` row;
  - rejecting stores the reason.
- `admin_users(q text)`, admin only: returns id, email, display name, role,
  blocked, created_at and active device count. It reads `auth.users`
  for the email, which the browser can't.
- `set_blocked(user_id, blocked)`, admin only: blocking also revokes all
  of the user's devices.
- `my_book_stats()`: for the caller's own books, readers
  (`reading_state` rows), downloads (`entitlements` rows) and finished
  count.
- `admin_stats(days int)`, admin only, returns:
  - users, active users and new users in the window;
  - books by status;
  - top books and top authors by readers;
  - daily active readers.

  "Active" means a `reading_state` write in the window.

### What existing tables can't measure

Reading time is not on the server yet: it lives in the app's local
stats. The admin page shows readers and finishes instead, and reading
time can be added when stats sync exists.

## Portal pages

Files:
- `portal/index.html` and `portal/app.js`, with hash routing;
- `portal/style.css`, in the purple APA brand;
- `portal/config.js`, holding the Supabase URL and anon key. It is
  gitignored, and `config.example.js` is committed.

Pages:

1. **Sign in.**
   - Email, then the 6-digit code, via `signInWithOtp` and `verifyOtp`.
   - "Continue with Google" via `signInWithOAuth`.
   - A blocked account sees a notice and nothing else.
2. **Reader landing.** A signed-in reader sees "Apply to publish" (a form
   with display name and bio) or their application's status.
3. **My books** (author).
   - A list with status chips and the rejection reason.
   - Per-book readers, downloads and finished counts.
4. **Book editor** (author or admin).
   - Fields: title, subtitle, description, genres (comma separated),
     language, pages, published date, ISBN, publisher, free or paid,
     and price in ₹.
   - The cover is uploaded with a preview.
   - The book file is EPUB, PDF or CBZ, with the format taken from the
     file extension; its size and sha256 are computed in the browser.
   - Buttons: "Save draft" and "Submit for approval".
   - Admins also get "Featured" and "Editor's pick" toggles.
5. **Admin: review queue.** Pending author applications and pending books,
   each with Approve, or Reject with a reason.
6. **Admin: books.** All books with search and status filters, edit, delete,
   and unpublish (back to draft).
7. **Admin: authors.** Edit the public name, bio and photo.
8. **Admin: users.**
   - Search by email.
   - Change role.
   - Block or unblock.
   - Grant a paid book (insert an entitlement with source `grant`).
   - List and revoke devices.
9. **Admin: analytics.** Numbers and a plain HTML or CSS bar list for the
   last 7, 30 or 90 days, from `admin_stats`.

Errors from Supabase show inline next to the action that failed. A
failed upload leaves the book unchanged: files go up first, and the
rows are written only after the upload succeeds.

## Testing

- **pgTAP tests:** extend `supabase/tests/rls.sql`, or add `portal.sql`.
  - An author can't read, edit or delete another author's book.
  - An author can't set `approved`, `featured` or `editors_pick`.
  - An author can't edit a `pending` or `approved` book.
  - A reader can't insert books or upload to storage.
  - A non-admin can't call the admin functions or change `role` or
    `blocked`.
  - `device_user` refuses a blocked user's key.
- **Manual check:** go through the portal in the built-in browser against
  the live project.
  1. Apply.
  2. Approve from the admin account.
  3. Upload a book and submit it.
  4. Approve the book.
  5. Confirm it appears on the emulator's Home.

## Setup the owner does once

1. Make the owner account an admin:
   `update profiles set role = 'admin' where id = (select id from auth.users where email = '…')`.
2. Supabase → Authentication → URL Configuration: add the portal's URL,
   and `http://localhost:5500` for local use, to the Redirect URLs.
3. For Google sign-in on the web, do both of these:
   - in Google Cloud, add
     `https://rtkkycpwnianreslufrd.supabase.co/auth/v1/callback` to the
     Web client's authorized redirect URIs;
   - make sure the Google provider in Supabase has the client secret.
4. Deploy with `supabase db push` and
   `supabase functions deploy api --no-verify-jwt`.
