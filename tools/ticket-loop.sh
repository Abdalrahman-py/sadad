#!/usr/bin/env bash
# Runs local tickets one by one, each in a fresh OpenCode session, until none is ready.
#
#   tools/ticket-loop.sh             loop until no ticket is ready
#   tools/ticket-loop.sh --once      run one ticket, then stop
#   tools/ticket-loop.sh --dry-run   print the next ticket and exit
#
# Env: OPENCODE_MODEL (e.g. opencode-go/glm-5.3-flash), TICKET_DIR, TICKET_TIMEOUT (default 2h).
#
# Each ticket runs on branch ticket/NN. Low-risk tickets that end "done" are fast-forwarded into
# main. High-risk ones stay on their branch as "review-pending" until a human merges them and sets
# the ticket to "done". Anything the agent leaves uncommitted is saved as a WIP commit on the
# branch, so main is always clean when the next ticket starts.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

TICKET_DIR=${TICKET_DIR:-.scratch/architecture-phase-0-1/issues}
OPENCODE=${OPENCODE:-$HOME/.opencode/bin/opencode}
TIMEOUT=${TICKET_TIMEOUT:-2h}
LOG_DIR=$TICKET_DIR/../logs
MODE=${1:-loop}

field() { sed -n "s/^\*\*$2:\*\* *//p" "$1" | head -1; }
ticket_file() { ls "$TICKET_DIR"/"$1"-*.md 2>/dev/null | head -1; }

# Lowest-numbered ticket that is ready-for-agent and whose blockers are all done.
next_ticket() {
  local f num blocker bf
  for f in "$TICKET_DIR"/[0-9][0-9]-*.md; do
    [[ $(field "$f" Status) == ready-for-agent ]] || continue
    for blocker in $(field "$f" "Blocked by" | grep -o '\b[0-9][0-9]\b' || true); do
      bf=$(ticket_file "$blocker")
      [[ -n $bf && $(field "$bf" Status) == done* ]] || continue 2
    done
    basename "$f" | cut -c1-2
    return
  done
}

set_status() {
  STATUS=$2 awk '!done && /^\*\*Status:\*\*/ { print "**Status:** " ENVIRON["STATUS"]; done = 1; next } 1' "$1" >"$1.tmp"
  mv "$1.tmp" "$1"
}

prompt_for() {
  cat <<EOF
You are implementing ticket file $1. It is already claimed for you; do not pick another ticket.
You are on git branch ticket/$2. Do not switch, create, rebase or delete branches. Never push.

1. Read AGENTS.md and follow it. Use only the skills it lists under "Implementation skills".
2. Read the whole ticket. Read every file under "Read first" before editing. Line anchors may have drifted;
   find the named symbol. If it is missing, stop (step 7).
3. Work test-first: write the "Test first" test, run it, show it failing. Then do "Steps" in order, one change
   per step, running each step's proof command.
4. Edit only files under "Files you may edit". Never hand-edit code to fix style.
5. Device tests that need a phone: write the test, build the APKs, write "pending human" in the commit body.
   Never fake a pass.
6. Run the full check from "Done when" (./tools/check.sh if it exists, else the ticket's baseline commands).
   Run the code-review skill on your diff and fix what it finds. Then make one commit with the exact message
   from the ticket's "Commit" section, on this branch.
   - Risk low: set the ticket's Status line to "done (commit <sha>)".
   - Risk high: set the ticket's Status line to "review-pending (commit <sha>)".
7. If any "Stop and ask the human if" condition is hit, or a "Done when" box cannot be made true: do not
   commit, do not work around it. Set the Status line to "blocked: <one-line reason>", append the details
   under a "## Comments" heading at the end of the ticket, and end.
8. End with a short report: what changed, test results, commit sha or block reason, anything left for a human.
EOF
}

run_ticket() {
  local num=$1 file risk branch log status
  file=$(ticket_file "$num")
  risk=$(field "$file" Risk | cut -d' ' -f1)
  branch=ticket/$num
  log=$LOG_DIR/$num-$(date +%Y%m%d-%H%M%S).jsonl
  mkdir -p "$LOG_DIR"

  [[ -z $(git status --porcelain) ]] || { echo "Working tree is dirty; commit or stash first." >&2; exit 1; }
  git switch -q main
  git switch -q "$branch" 2>/dev/null || git switch -q -c "$branch"
  set_status "$file" claimed
  echo "== ticket $num ($risk) on $branch, log $log"

  timeout "$TIMEOUT" "$OPENCODE" run --format json --title "ticket $num" \
    ${OPENCODE_MODEL:+-m "$OPENCODE_MODEL"} "$(prompt_for "$file" "$num")" >"$log" 2>&1 || true

  status=$(field "$file" Status)
  case $status in
    done* | review-pending* | blocked*) ;;
    *) set_status "$file" "blocked: session ended without a final status (see $log)"; status=$(field "$file" Status) ;;
  esac
  if [[ -n $(git status --porcelain) ]]; then
    git add -A && git commit -q -m "wip(ticket $num): uncommitted agent work" && echo "   saved leftover changes as WIP on $branch"
  fi
  git switch -q main

  if [[ $status == done* && $risk == low ]]; then
    if git merge -q --ff-only "$branch"; then git branch -q -d "$branch"; echo "   done, merged into main"
    else echo "   done, but fast-forward failed; merge $branch by hand"; exit 1; fi
  elif [[ $status == done* ]]; then
    # A high-risk ticket must go through review even if the agent wrote "done".
    set_status "$file" "review-pending (agent wrote: $status)"
    echo "   high risk: left on $branch for review"
  else
    echo "   $status (branch $branch kept)"
  fi
}

case $MODE in
  --dry-run) n=$(next_ticket); echo "${n:-none ready}"; exit 0 ;;
  --once) n=$(next_ticket); [[ -n $n ]] && run_ticket "$n" || echo "No ticket ready." ;;
  loop | *)
    while n=$(next_ticket) && [[ -n $n ]]; do run_ticket "$n"; done
    echo "No ticket ready."
    ;;
esac

echo "== status"
for f in "$TICKET_DIR"/[0-9][0-9]-*.md; do printf '%s  %s\n' "$(basename "$f" .md)" "$(field "$f" Status)"; done
