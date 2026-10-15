#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

run_format() {
  echo "== format =="
  ./gradlew spotlessCheck --console=plain
  (cd Supabase-Setup/supabase/functions && deno fmt --check && deno lint)
}

run_docs() {
  echo "== docs =="
  local continue_lines agents_lines pruned_files
  continue_lines=$(wc -l < docs/CONTINUE_HERE.md)
  if (( continue_lines > 80 )); then
    echo "docs/CONTINUE_HERE.md has $continue_lines lines (max 80)" >&2
    return 1
  fi
  agents_lines=$(wc -l < AGENTS.md)
  if (( agents_lines > 150 )); then
    echo "AGENTS.md has $agents_lines lines (max 150)" >&2
    return 1
  fi
  pruned_files=$(git ls-files delivery docs/archive docs/plans)
  if [[ -n "$pruned_files" ]]; then
    echo "delivery/, docs/archive/ or docs/plans/ still tracked:" >&2
    echo "$pruned_files" >&2
    return 1
  fi
}

run_dashboard() {
  echo "== dashboard =="
  node --check server/public/admin.js
  node tools/test-admin-recovery.cjs
  node tools/admin-dashboard.test.cjs
  node tools/build-admin-pages.cjs
}

run_android() {
  echo "== android =="
  ./gradlew testServerDebugUnitTest assembleServerDebug assembleStandaloneDebug assembleServerDebugAndroidTest --console=plain
}

run_edge() {
  echo "== edge =="
  for dir in api sadad-api sadad-auth sadid-ledger; do
    echo "=== $dir ==="
    (cd "Supabase-Setup/supabase/functions/$dir" && deno test -A)
  done
}

run_database() {
  echo "== database =="
  if [[ ! -f .local-tools/node_modules/@electric-sql/pglite/dist/index.js ]]; then
    npm install --prefix .local-tools --no-save @electric-sql/pglite@0.5.8
  fi
  node Supabase-Setup/scripts/test-v3-ledger.mjs
  node Supabase-Setup/scripts/test-v3-authorization.mjs
  node Supabase-Setup/scripts/test-admin-dashboard.mjs
  node Supabase-Setup/scripts/test-baseline.mjs
}

if [[ $# -eq 0 ]]; then
  sections=(docs format dashboard android edge database)
else
  sections=("$@")
fi

for section in "${sections[@]}"; do
  case "$section" in
    docs) run_docs ;;
    format) run_format ;;
    dashboard) run_dashboard ;;
    android) run_android ;;
    edge) run_edge ;;
    database) run_database ;;
    *)
      echo "Unknown section: $section (expected: docs, format, dashboard, android, edge, database)" >&2
      exit 1
      ;;
  esac
done
