#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

run_format() {
  echo "== format =="
  ./gradlew spotlessCheck --console=plain
  (cd Supabase-Setup/supabase/functions && deno fmt --check && deno lint)
  (cd tools && deno fmt --check && deno lint)
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
  old_backend=$(git grep -nE "sadad-api|sadad-auth|sadid-ledger|x-sadad-session|X-Sadad-Admin|sadad_auth_bindings|store_devices" -- . ':!docs/CHANGELOG.md' ':!docs/ARCHITECTURE.md' ':!docs/CONTINUE_HERE.md' ':!docs/phase-3' ':!docs/adr' ':!tools/check.sh' ':!Supabase-Setup/scripts/test-baseline.mjs' || true)
  if [[ -n "$old_backend" ]]; then
    echo "Old backend still referenced:" >&2
    echo "$old_backend" >&2
    return 1
  fi
  # Codex reads .agents/skills, Claude Code reads .claude/skills: the shared skills are copies
  # (symlinks break on Windows clones), so the copies must stay identical.
  local s
  for s in tdd diagnosing-bugs code-review LICENSE-mattpocock-skills; do
    if ! diff -r ".claude/skills/$s" ".agents/skills/$s" >&2; then
      echo ".agents/skills/$s differs from .claude/skills/$s; copy the edited one over the other" >&2
      return 1
    fi
  done
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
  python3 -m unittest tools/test_apk_budget.py
  ./gradlew :ledger-core:test testDebugUnitTest assembleDebug assembleRelease :benchmark:assemble --console=plain
  # Signed when the release key is set, unsigned otherwise: either way the only APK in the folder.
  local apks=(app/build/outputs/apk/release/*.apk)
  if [[ ${#apks[@]} -ne 1 || ! -f ${apks[0]} ]]; then
    echo "Expected exactly one release APK in app/build/outputs/apk/release, found: ${apks[*]}" >&2
    return 1
  fi
  python3 tools/apk_budget.py "${apks[0]}"
}

run_edge() {
  echo "== edge =="
  (cd Supabase-Setup/supabase/functions/api && deno test -A)
  (cd tools && deno test -A)
}

run_database() {
  echo "== database =="
  if [[ ! -f .local-tools/node_modules/@electric-sql/pglite/dist/index.js ]]; then
    npm install --prefix .local-tools --no-save @electric-sql/pglite@0.5.8
  fi
  node Supabase-Setup/scripts/test-baseline.mjs
}

all_sections=(docs format dashboard android edge database)

# Sections that the files changed since $1 (committed, staged, unstaged or untracked) can affect.
changed_sections() {
  local f s
  local -A want=([docs]=1)
  while IFS= read -r f; do
    case "$f" in
      '') ;;
      tools/check.sh) for s in "${all_sections[@]}"; do want[$s]=1; done ;;
      tools/*.ts) want[format]=1 want[edge]=1 ;;
      tools/apk_budget.py | tools/test_apk_budget.py | tools/apk-budget.txt) want[android]=1 ;;
      # Only the docs gate reads these.
      *.md | docs/* | .claude/* | .agents/* | .opencode/* | .github/* | tools/* | opencode.json | .gitignore | .gitattributes | .editorconfig | Supabase-Setup/supabase/config.toml) ;;
      contract/*) want[android]=1 want[dashboard]=1 want[edge]=1 ;;
      server/public/*) want[dashboard]=1 ;;
      Supabase-Setup/supabase/functions/*) want[format]=1 want[edge]=1 ;;
      Supabase-Setup/supabase/schemas/* | Supabase-Setup/supabase/migrations/* | Supabase-Setup/scripts/*) want[database]=1 ;;
      app/* | ledger-core/* | gradle/* | *.kts | gradle.properties | gradlew*) want[format]=1 want[android]=1 ;;
      *) for s in "${all_sections[@]}"; do want[$s]=1; done ;;
    esac
  done < <({ git diff --name-only "$1"; git ls-files --others --exclude-standard; } | sort -u)
  for s in "${all_sections[@]}"; do
    if [[ -n ${want[$s]:-} ]]; then echo "$s"; fi
  done
}

if [[ $# -eq 0 ]]; then
  sections=("${all_sections[@]}")
elif [[ $1 == --changed ]]; then
  [[ $# -eq 2 || ($# -eq 3 && $3 == --list) ]] || { echo "usage: tools/check.sh --changed <base> [--list]" >&2; exit 1; }
  git rev-parse --verify -q "$2^{commit}" >/dev/null || { echo "Unknown base: $2" >&2; exit 1; }
  mapfile -t sections < <(changed_sections "$2")
  # --list prints the sections and runs nothing.
  if [[ ${3:-} == --list ]]; then echo "${sections[*]}"; exit 0; fi
  echo "Sections for changes since $2: ${sections[*]}"
else
  sections=("$@")
fi

# One check at a time across every worktree: parallel runs froze the laptop.
if command -v flock >/dev/null; then
  exec 9>"${TMPDIR:-/tmp}/sadad-check.lock"
  if ! flock -n 9; then
    echo "Another check.sh is running; waiting for it to finish..." >&2
    flock 9
  fi
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
