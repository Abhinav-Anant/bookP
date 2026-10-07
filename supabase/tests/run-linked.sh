#!/usr/bin/env bash
# Runs a pgTAP file against the linked project (no Docker needed). The
# Management API returns only the last result set, so every TAP line is
# collected into a temp table and selected just before the rollback.
set -euo pipefail
f="${1:?usage: run-linked.sh supabase/tests/<file>.sql}"
tmp=supabase/.temp/tap.sql
mkdir -p supabase/.temp
sed -E \
  -e 's/^select plan\(/create temp table tap (n serial, line text); grant all on tap, tap_n_seq to public; insert into tap (line) select plan(/' \
  -e 's/^select (is|isnt|ok|throws_ok|lives_ok)\(/insert into tap (line) select \1(/' \
  -e 's/^select \* from finish\(\);/insert into tap (line) select * from finish(); select line from tap order by n;/' "$f" > "$tmp"
npx supabase db query --linked -f "$tmp"
