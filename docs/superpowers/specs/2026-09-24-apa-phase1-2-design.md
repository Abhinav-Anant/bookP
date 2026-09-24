# Anant Publication App (APA): Phase 1 + 2 design

Date: 2026-09-24
Base: fork of [chmouel/liseur](https://github.com/chmouel/liseur) (MIT), commit `1d1011c`.

## Goal

A rebranded Android reader, **APA**, backed by a Supabase "cloud library".
Anant Publication (or an admin) adds a book to Supabase; signed-in readers
see it on the Home screen, open the detail page, download it, and read it
offline, with position, bookmarks, highlights and notes following them
across devices. No APK release is needed to publish a new book.

## Out of scope for this cycle (later phases)

| Phase | Item |
|---|---|
| 3 | Publisher/author portal, admin web panel, analytics, moderation |
| 4 | Payments (Razorpay / Play Billing), subscriptions, encrypted files, watermarking, screenshot blocking |
| 5 | AI features, reviews/social, book clubs, gamification, push notifications |

Also not supported: CBR (RAR, which Readium cannot open), Apple sign-in, phone OTP.

The schema reserves `price`, `role`, `entitlements` and `status` so these
phases slot in without migrations that break existing data.

## Phase 1: app changes

1. **Rebrand**
   - applicationId `in.anant.apa`, with `dev` build `in.anant.apa.dev`.
   - App name "APA — Anant Publication", launcher icon, and accent colour.
   - The Kotlin package `com.chmouel.liseur` stays, to keep the diff and
     the upstream merges small. Only the applicationId and resources change.
2. **Policy change**
   - Drop the F-Droid/FOSS-only constraints in `AGENTS.md`, since APA is a
     commercial app on Play.
   - Keep the reproducible-build settings; they cost nothing.
3. **Languages (UI)**
   - Add `values-hi`, `mr`, `bn`, `gu`, `ta`, `te`, `kn`, `ml` and `pa`.
   - These are machine-translated first drafts, flagged for native review.
   - The per-app language picker works through `locales_config.xml`.
   - Books keep their own `language` metadata, so books can be in any language.
4. **Formats**
   - PDF: add `readium-adapter-pdfium-navigator` and wire up the reader's
     `pdfFactory` (currently `null` in `AppContainer.kt:106`).
   - CBZ: Readium's image-archive parser and `ImageNavigatorFragment`.
   - The EPUB-only reader chrome (typography sheet, reflow settings) is
     hidden for fixed-layout formats.
5. **Library**
   - `favorite` and `wishlist` flags, plus user-defined **shelves**
     (a many-to-many table), as a Room migration on the existing `books` table.
   - Library filter chips: Continue reading · Favorites · Downloaded ·
     Completed · Wishlist · Shelves.
6. **Home / Discover tab**
   - Rows: Continue reading, Featured / Editor's picks, New releases,
     Trending, Free, and By category.
   - Search across title, author, genre and publisher.
   - Data comes from Supabase (Phase 2). Without a network the Home tab
     shows cached rows.
7. **Book detail page**
   - Cover, title, author, publisher, description, genre, language, pages,
     publication date and ISBN.
   - Buttons: Download/Read, Wishlist, Favorite, and a "More by this
     author" row.
   - Rating and reviews are placeholders hidden until Phase 5.

## Phase 2: Supabase backend

### Auth

- Email + password, with **email OTP verification** on sign-up and for
  passwordless login.
- Google sign-in through Supabase's OAuth, using Android Credential Manager.
- The session is stored in the app (encrypted DataStore) and refreshed by
  `supabase-kt`.
- A **Device** row is recorded on each login, which is the basis for device
  management later.

### Schema (Postgres, `supabase/migrations/0001_init.sql`)

```
profiles(id uuid pk → auth.users, display_name, avatar_url, role text default 'reader' check in ('reader','author','admin'), ui_language, created_at)
authors(id, name, bio, photo_url)
books(id, title, subtitle, author_id → authors, publisher, description, genres text[], language, pages int,
      published_on date, isbn, cover_path, price_inr numeric default 0, is_free bool default true,
      free_chapters int default 0, status text check in ('draft','pending','approved','rejected') default 'draft',
      featured bool, editors_pick bool, created_at, updated_at, search tsvector generated)
book_files(id, book_id → books, format text check in ('epub','pdf','cbz'), storage_path, size_bytes, sha256)
entitlements(user_id, book_id, source text ('free','purchase','subscription','grant'), granted_at, pk(user_id, book_id))
library_items(user_id, book_id, favorite bool, wishlist bool, added_at, pk(user_id, book_id))
shelves(id, user_id, name) ; shelf_books(shelf_id, book_id)
reading_state(user_id, book_id, locator jsonb, progression real, updated_at, device_id, pk(user_id, book_id))
annotations(id uuid, user_id, book_id, kind ('bookmark','highlight','note'), locator jsonb, text, note, color,
            created_at, updated_at, deleted bool)
devices(id, user_id, name, model, last_seen)
book_stats view: trending = reading_state rows updated in last 7 days, per book
```

### Row-level security (RLS)

- **`books`, `authors`, `book_files`:** anyone can select rows where
  `status='approved'`. Admins can do anything.
- **User tables:** all user-owned tables use `user_id = auth.uid()`.
- **`entitlements`:** users can select their own rows. Inserts happen only
  in the `get_download_url` function (for free books) or through an admin.

### Storage

- **`covers`:** a public bucket.
- **`books`:** a private bucket.
- **Edge Function `get-download-url(book_id, format)`:**
  1. Checks the JWT.
  2. Checks the book is approved.
  3. If the book is free, upserts an entitlement.
  4. Refuses with a 403 if there is no entitlement.
  5. Returns a 300-second signed URL.

### Sync (last-write-wins by `updated_at`)

- **Positions:** pushed on the same trigger points Liseur already uses in
  `PositionSync`, and pulled on book open and app foreground.
- **Annotations:** upserted with a soft-delete flag, merged with the same
  rules as `LiseurSyncAnnotations`.
- **Library flags and shelves:** a two-way sync on foreground.
- **Reader settings:** out of scope this cycle.

## App architecture

The new package `data/apa/` mirrors `data/liseursync/`:

- `ApaClient`: holds the `supabase-kt` `SupabaseClient` (Auth, Postgrest,
  Storage, Functions), created in `AppContainer`.
- `ApaCatalogClient`: Home rows, search and book detail. It maps rows to
  the existing `RemoteBook`.
- `ApaFileSource`: fetches the signed URL, then downloads through the
  existing `BookDownloader`/`BookDownloadWorker`, so downloads are offline
  and resumable, and a Wi-Fi-only setting is available.
- `ApaPositionSync` and `ApaAnnotationSync` implement the existing
  `PositionSync` and annotation-sync contracts.
- `ServerKind.APA("apa")`: registered in `RemoteRouter`, so callers stay
  provider-agnostic. APA is a built-in server that is always present once
  the user signs in. Other server kinds stay available.
- UI:
  - `ui/auth/` for sign-in, sign-up, OTP and Google sign-in.
  - `ui/home/` for the Discover tab.
  - `ui/book/` for the detail page.
  - Bottom navigation: Home · Library · Profile. Profile reuses the
    existing stats screen, settings and devices, plus sign-out.

The Supabase URL and anon key are set through `BuildConfig` from
`local.properties` (`apa.supabaseUrl`, `apa.supabaseAnonKey`), so no
secrets go in git.

## Error handling

- **No network:** the Home tab shows cached rows with an offline banner.
  Downloaded books always open. Sync queues through WorkManager, as
  Liseur already does.
- **Expired session:** `supabase-kt` refreshes it automatically. If the
  refresh fails, the user is sent to sign-in without losing local data.
- **Download 403:** the detail page shows "Not available for your account".
- **Signed URL expired mid-download:** the worker requests a new URL and
  retries once.

## Testing

- **JVM unit tests** (the existing style) cover:
  - mapping rows to `RemoteBook`
  - the last-write-wins merge
  - Home row assembly
  - the shelf and flag Room migration, using the existing migration-test
    pattern
- **SQL checks** (`supabase/tests/rls.sql`, run with the Supabase CLI
  `supabase test db`) confirm that:
  - users can't read another user's rows
  - unapproved books are hidden
- **Build gate:** `./gradlew testDebugUnitTest assembleDebug` passes.
- **Manual check:** with a Supabase project, seed one free EPUB, one PDF
  and one CBZ. Sign in, then read each offline, and check that the
  position syncs to a second emulator.

## Prerequisites from the user

- A Supabase project (free tier is fine): its URL and anon key.
- For Google sign-in: a Google Cloud OAuth client (Android + Web client
  IDs) configured in Supabase Auth.
- Brand assets: a logo/icon and a primary colour (a placeholder is used
  until then).
