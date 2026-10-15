# Issue tracker: Local Markdown

Issues and specs for this repo live as markdown files in `.scratch/`.

## Conventions

- One feature per directory: `.scratch/<feature-slug>/`
- The spec is `.scratch/<feature-slug>/spec.md`
- Implementation issues are one file per ticket at `.scratch/<feature-slug>/issues/<NN>-<slug>.md`, numbered from `01`, never a single combined tickets file
- Triage state is recorded as a `Status:` line near the top of each issue file (see `triage-labels.md` for the role strings)
- Comments and conversation history append to the bottom of the file under a `## Comments` heading

## When a skill says "publish to the issue tracker"

Create a new file under `.scratch/<feature-slug>/` (creating the directory if needed).

## When a skill says "fetch the relevant ticket"

Read the file at the referenced path. The user will normally pass the path or the issue number directly.

## Implementation tickets

Tickets in `.scratch/<feature-slug>/issues/` that OpenCode implements, one git worktree per ticket.

- **Status flow**: `ready-for-agent` → `claimed` → `done (commit <sha>, branch ticket-NN, not merged)`, or `blocked: <reason>` / `review-pending (worktree <path>)`.
- **Blocking**: a ticket is unblocked when every `Blocked by` ticket has a Status starting with `done`. The human picks the ticket number; agents never pick the next ticket themselves.
- **Files section**: every repo path that Test first or Steps edits must appear under `Files you may edit`.
- **Runner**: `.scratch/ticket` (`ready`, `lint NN`, `start NN`, `merge NN`). Run `ticket lint NN` after writing or changing a ticket. `merge` refuses a branch that changes files outside the ticket's Files section. Merge reviewed branches with `ticket merge NN`, not plain `git merge`, so the worktree and branch are removed too.

## Wayfinding operations

Used by `/wayfinder` for exploration efforts that have a `map.md`; the implementation-ticket rules above do not apply to them. The **map** is a file with one **child** file per ticket.

- **Map**: `.scratch/<effort>/map.md` (the Notes / Decisions-so-far / Fog body).
- **Child ticket**: `.scratch/<effort>/issues/NN-<slug>.md`, numbered from `01`, with the question in the body. A `Type:` line records the ticket type (`research`/`prototype`/`grilling`/`task`); a `Status:` line records `claimed`/`resolved`.
- **Blocking**: a `Blocked by: NN, NN` line near the top. A ticket is unblocked when every file it lists is `resolved`.
- **Frontier**: scan `.scratch/<effort>/issues/` for files that are open, unblocked, and unclaimed; first by number wins.
- **Claim**: set `Status: claimed` and save before any work.
- **Resolve**: append the answer under an `## Answer` heading, set `Status: resolved`, then append a context pointer (gist + link) to the map's Decisions-so-far in `map.md`.
