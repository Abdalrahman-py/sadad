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
  local continue_lines agents_lines pruned_files extra_functions
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
  # The deploy workflow ships only `api`; any other function table means config drifted from it.
  extra_functions=$(grep -E '^\[functions\.' Supabase-Setup/supabase/config.toml | grep -vE '^\[functions\.api(\..+)?\]' || true)
  if [[ -n "$extra_functions" ]]; then
    echo "Supabase-Setup/supabase/config.toml declares functions other than api:" >&2
    echo "$extra_functions" >&2
    return 1
  fi
  # The old gateways and their tables are gone; only history, still-deployed state and the
  # baseline test's check that those tables are absent may name them.
  local old_backend migrations
  old_backend=$(git grep -nE "sadad-api|sadad-auth|sadid-ledger|x-sadad-session|X-Sadad-Admin|sadad_auth_bindings|store_devices" -- . ':!app' ':!docs/CHANGELOG.md' ':!docs/ARCHITECTURE.md' ':!docs/CONTINUE_HERE.md' ':!docs/adr' ':!tools/check.sh' ':!.github/workflows/android-release.yml' ':!Supabase-Setup/scripts/test-baseline.mjs' || true)
  if [[ -n "$old_backend" ]]; then
    echo "Old backend still referenced:" >&2
    echo "$old_backend" >&2
    return 1
  fi
  # The cutover deploy runs `supabase db push`, which must apply exactly the baseline.
  migrations=$(git ls-files Supabase-Setup/supabase/migrations)
  if [[ $(wc -l <<< "$migrations") -ne 1 || "$migrations" != *_baseline.sql ]]; then
    echo "Supabase-Setup/supabase/migrations/ must hold only one *_baseline.sql:" >&2
    echo "$migrations" >&2
    return 1
  fi
}

run_dashboard() {
  echo "== dashboard =="
  (
    cd server/public
    npm ci --no-audit --no-fund
    npm run typecheck
    npm run lint
    npm test
    npm run build # -> _site/
    # A no-op when the pinned Chromium is already installed.
    npx playwright install chromium
    npm run e2e
  )
}

run_android() {
  echo "== android =="
  ./gradlew :ledger-core:test testServerDebugUnitTest assembleServerDebug assembleStandaloneDebug assembleServerDebugAndroidTest --console=plain
}

run_edge() {
  echo "== edge =="
  (cd Supabase-Setup/supabase/functions/api && deno test -A)
}

run_database() {
  echo "== database =="
  if [[ ! -f .local-tools/node_modules/@electric-sql/pglite/dist/index.js ]]; then
    npm install --prefix .local-tools --no-save @electric-sql/pglite@0.5.8
  fi
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
