#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

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
  for dir in sadad-api sadad-auth sadid-ledger; do
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
}

if [[ $# -eq 0 ]]; then
  sections=(dashboard android edge database)
else
  sections=("$@")
fi

for section in "${sections[@]}"; do
  case "$section" in
    dashboard) run_dashboard ;;
    android) run_android ;;
    edge) run_edge ;;
    database) run_database ;;
    *)
      echo "Unknown section: $section (expected: dashboard, android, edge, database)" >&2
      exit 1
      ;;
  esac
done
