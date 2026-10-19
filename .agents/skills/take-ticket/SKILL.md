---
name: take-ticket
description: Take one Sadad ticket (a GitHub issue on Abdalrahman-py/sadad) end to end — claim, implement test-first, open the PR, get it reviewed, merge it. Use when asked to take, start or do the next ticket, or ticket/issue N.
---

# Take a ticket

One ticket per run, from claim to merge. The issue body is the ticket. The rules in [`docs/agents/cloud-sessions.md`](../../../docs/agents/cloud-sessions.md) (claiming, stacking, "Rules that also apply locally") bind every step; read that file first.

**CI is the test runner.** This machine has only Git, `gh` and Codex: no JDK, Android SDK, Deno or Node. Every test and check runs in GitHub Actions on the PR, and its results are the ticket's proofs. Every command below works in PowerShell and in bash.

In the commands, replace `<N>` with the issue number, `<NN>` with the ticket number (issue title `NN: ...`), `<pr>` with the PR number, and `<base>` with `main`, or with `ticket-MM` when stacking on your own open PR for ticket MM.

## 1. Pick and claim

- The issue the human named; otherwise the `ready-for-agent` issue with the lowest ticket number: `gh issue list -R Abdalrahman-py/sadad --label ready-for-agent --json number,title`.
- Claim before any edit: `gh issue edit <N> -R Abdalrahman-py/sadad --add-assignee "@me" --remove-label ready-for-agent --add-label claimed`.
- Re-read the issue. If someone else is also assigned, the winner is the current assignee with the earliest `assigned` event (`gh api repos/Abdalrahman-py/sadad/issues/<N>/events`). If that is not you, remove only yourself (`gh issue edit <N> -R Abdalrahman-py/sadad --remove-assignee "@me"`), leave the labels to the winner, and pick again.

Done when the issue is assigned only to you and labelled `claimed`.

## 2. Red: tests first, on a draft PR

- `git fetch origin`, then `git switch -c ticket-<NN> origin/<base>` (stacking rules: cloud-sessions.md).
- Read every file under the ticket's "Read first". Write the ticket's "Test first" tests with the `tdd` skill.
- Commit the tests, `git push -u origin ticket-<NN>`, then open a draft PR:
  `gh pr create -R Abdalrahman-py/sadad --draft --base <base> --title "<NN>: <issue title>" --body "Closes #<N>"`.
- Wait for CI: `gh pr checks <pr> -R Abdalrahman-py/sadad --watch`. Read the failing job's log: `gh run view <run-id> -R Abdalrahman-py/sadad --log-failed`.

Done when CI fails on the new tests for the reason the ticket expects (the ticket's "Red:" line). Keep the run link and the failing lines.

## 3. Green: implement

- Implement the ticket's "Steps". Edit only paths under "Files you may edit"; the `scope` check on the PR fails on any other path.
- Push, wait for CI, read failures as in step 2. Use `diagnosing-bugs` when a failure's cause is not visible in the log.

Done when `ci-ok` and `scope` pass and every "Steps" item is done. Keep the green run link.

## 4. Finish the PR

Edit the body (`gh pr edit <pr> -R Abdalrahman-py/sadad --body-file <file>`; write the file outside the repo or delete it after) so it holds:

- `Closes #<N>` (the merge into `main` closes the issue). Stacked: also "Stacked on #<pr>".
- The red and green run links with the key lines, plus any sizes or other proofs the ticket asks for.
- `./tools/check.sh`: "run by CI (`ci-ok`)".
- Device checks (`app/src/androidTest`): "not run, needs the maintainer's phone".

## 5. Review

The reviewer is never the context that wrote the code.

- `gh pr ready <pr> -R Abdalrahman-py/sadad`. Marking the PR ready starts the Codex review on GitHub, in a fresh context. No review after 20 minutes: `gh pr comment <pr> -R Abdalrahman-py/sadad --body "@codex review"`.
- If the issue says `**Risk:** high`, also run a second review in a fresh process:
  `codex exec "Use the code-review skill. Fixed point: origin/<base>. Spec: $(gh issue view <N> -R Abdalrahman-py/sadad). Report findings; edit nothing."`
- Read the review: `gh pr view <pr> -R Abdalrahman-py/sadad --comments`, and `gh api repos/Abdalrahman-py/sadad/pulls/<pr>/comments` for line comments.
- For each finding: fix it, or reply on the PR with the evidence that it is wrong. Push the fixes and comment `@codex review` once more.

Done when every finding is fixed or answered and the last review raised nothing new.

## 6. Merge

- Wait for the required checks: `gh pr checks <pr> -R Abdalrahman-py/sadad --watch --required`. Both `ci-ok` and `scope` must pass.
- `scope` red: a file outside "Files you may edit" changed. Remove that change. If the ticket cannot be done without it, stop: comment on the issue naming the file and why, and leave the PR open for the maintainer.
- Green: `gh pr merge <pr> -R Abdalrahman-py/sadad --merge --delete-branch`. Merge stacked PRs bottom first: deleting the merged base branch moves the next PR onto `main`, where its checks run again before you merge it.

Done when the PR is merged and issue #<N> is closed. The `Unblock tickets` workflow then labels every issue whose blockers are all closed `ready-for-agent`.

## Report

Tell the human: the PR link, the merge commit, the review findings and what you did with each, the checks that were not run, and the issues now `ready-for-agent` (`gh issue list -R Abdalrahman-py/sadad --label ready-for-agent`).

## Guardrails

Change `main` only through a merged PR, and merge only your own ticket PR. Deploys and every Supabase cloud command belong to the maintainer.
