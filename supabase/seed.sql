-- Sample catalog. Upload the matching files to the `books` bucket
-- (seed/sample.epub, seed/sample.pdf, seed/sample.cbz) and covers to
-- the `covers` bucket (seed/*.jpg); see docs/APA_SETUP.md.
insert into public.authors (id, name, bio) values
  ('00000000-0000-0000-0000-00000000a001', 'Anant Sample Author', 'Writes the sample books that ship with APA.');

insert into public.books
  (id, title, author_id, publisher, description, genres, language, pages, published_on, isbn,
   cover_path, is_free, status, featured, editors_pick)
values
  ('00000000-0000-0000-0000-00000000b001', 'Sample EPUB', '00000000-0000-0000-0000-00000000a001',
   'Anant Publication', 'A reflowable sample book.', '{fiction}', 'en', 120, '2026-09-01', '9780000000001',
   'seed/epub.jpg', true, 'approved', true, true),
  ('00000000-0000-0000-0000-00000000b002', 'नमूना पीडीएफ', '00000000-0000-0000-0000-00000000a001',
   'Anant Publication', 'एक नमूना पीडीएफ पुस्तक।', '{non-fiction}', 'hi', 40, '2026-08-15', '9780000000002',
   'seed/pdf.jpg', true, 'approved', false, true),
  ('00000000-0000-0000-0000-00000000b003', 'Sample Comic', '00000000-0000-0000-0000-00000000a001',
   'Anant Publication', 'A CBZ comic sample.', '{comics}', 'en', 24, '2026-07-01', null,
   'seed/cbz.jpg', true, 'approved', true, false);

insert into public.book_files (book_id, format, storage_path) values
  ('00000000-0000-0000-0000-00000000b001', 'epub', 'seed/sample.epub'),
  ('00000000-0000-0000-0000-00000000b002', 'pdf', 'seed/sample.pdf'),
  ('00000000-0000-0000-0000-00000000b003', 'cbz', 'seed/sample.cbz');
