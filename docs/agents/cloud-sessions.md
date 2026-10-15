# Working on tickets in Claude Code cloud sessions

Cloud sessions (claude.ai/code) clone `Abdalrahman-py/sadad` from GitHub. They do not have `.scratch/`, the `.scratch/ticket` runner, OpenCode, a phone, or the human's local memory. This page replaces those for cloud work.

## One-time environment setup (human, on claude.ai/code)

- **Repository:** `Abdalrahman-py/sadad`, with `main` pushed. Install the Claude GitHub App on it for Auto-fix.
- **Network access:** Custom. Keep the Trusted defaults and add `dl.google.com` (Google Maven and the Android SDK).
- **Environment variables:** `ANDROID_HOME=/opt/android-sdk`.
- **Setup script:** the contents of `tools/cloud-setup.sh`. It installs Deno and the Android SDK; the environment cache keeps them.

## The ticket is a GitHub issue

- Each open ticket is an issue on `Abdalrahman-py/sadad`, labelled with its status (`ready-for-agent`, `ready-for-human`, `needs-triage`, `claimed`). The issue body is the ticket.
- Start only an issue labelled `ready-for-agent` whose `Blocked by` tickets are closed or merged. Change its label to `claimed` when you start.
- Work on a branch named `ticket-NN`, and keep to the ticket's "Files you may edit".
- Deliver a pull request into `main` of `Abdalrahman-py/sadad` that says `Closes #<issue>`. Put the ticket's proofs (red and green output, `./tools/check.sh` result, sizes) in the PR body.
- Where a ticket says to write in its Comments or Status, comment on the issue instead.
- Paths like `/home/jade/...`, `.scratch/...` and `ticket start/merge` in a ticket are local-only; ignore them.

## Rules that also apply locally

- `./tools/check.sh` is the gate. Run it before every PR.
- Never run `supabase db push`, `supabase functions deploy`, or anything that changes project `vhftjmiltqfwmrszdtgf`. The human deploys. Cloud migration versions differ from local file names, so `db push` would re-run old migrations.
- All stores, users and rows are test data. Never add upgrade paths, fallbacks, compatibility shims or legacy handling (`docs/ARCHITECTURE.md` Part 3, the clean-cut rule). Delete legacy handling you touch.
- Device checks (`app/src/androidTest`) need the human's phone; list them in the PR as "not run".
- Versions are decided in `docs/versions.md`; do not pick newer ones.
