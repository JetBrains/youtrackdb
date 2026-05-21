---
name: review-workflow-pr
description: "Review a workflow-style PR's design, plan, and track files in research-mode Q&A; auto-records observations and submits a line-anchored review via gh api. TRIGGER when: user asks to review a workflow PR or run /review-workflow-pr. SKIP: non-workflow PRs without docs/adr/<dir>/_workflow/."
argument-hint: "[pr-number-or-url-or-ref]"
user-invocable: true
---

A reviewer invokes `/review-workflow-pr <PR>` against a PR they have already
checked out, lands in research-mode Q&A against the verified workflow
artifacts, and at wrap-up submits a single line-anchored review back to the
PR.

> **House style for chat-scale prose.** User-facing prose produced from this
> file (status updates, observation entries, prune-table rendering, the final
> stub message) follows the AI-tell subset of
> `.claude/output-styles/house-style.md`: `## Banned vocabulary`,
> `## Banned sentence patterns`, `## Banned analysis patterns`, and
> `### Em-dash discipline`. Structural rules (`§ BLUF lead`, the ≤200-word
> section cap, `§ Document-shape rules`) do not apply to chat-scale prose.
> See [conventions.md §1.5 Writing style for Markdown and prose artifacts](../../workflow/conventions.md)
> for the workflow-level anchor and tier mapping.

## Invocation contract

`/review-workflow-pr $ARGUMENTS` accepts three argument shapes (a PR number such as `42`, a PR URL like `https://github.com/owner/repo/pull/42`, or a branch name such as `feature/foo`) and defaults to the current branch's PR when `$ARGUMENTS` is empty. The shape is resolved by `## Preflight`; this section is the contract the reviewer sees at invocation time.

The handshake is single-turn. On the same turn that runs preflight and artifact discovery, the skill emits a one-line greeting naming the PR number, head SHA, and resolved `<dir>`, then asks what to investigate. The reviewer's next message is the first research-mode question; no separate acknowledgment is required. When preflight or discovery fails, the skill emits the error and exits without entering research mode.

See `## Preflight` for the mechanical resolution.

## Preflight

The skill resolves the PR, fetches its head SHA and changed files, and confirms the local checkout matches before loading any artifact.

**Resolve `$ARGUMENTS`.** Accepts a PR number (`42`), a PR URL, or a branch name. When empty, the skill targets the current branch's PR.

**Fetch PR metadata.** Run `gh pr view <ref> --json headRefOid,number,files` to read the head SHA, PR number, and the changed-files array (each element carries `path`, `additions`, `deletions`, `changeType`; the skill reads `.path`). Run `gh repo view --json nameWithOwner` separately to resolve owner/repo for the API path.

**Verify local HEAD.** Run `git rev-parse HEAD` and compare against `headRefOid`. On match, proceed to artifact discovery.

**HEAD-SHA mismatch.** Abort and print both the expected and local SHAs along with the remediation: `gh pr checkout <ref>`. The command typically creates a named local branch tracking the PR head; only `--detach` produces a detached HEAD, and `git rev-parse HEAD` returns the head SHA in either case.

**Non-zero `gh pr view` exit.** When no PR exists for the current branch or the ref does not resolve, surface the command's stderr and tell the reviewer to either pass an explicit PR number or URL as `$ARGUMENTS` or open a PR first.

## Artifact discovery

The skill resolves `<dir>`, enumerates the canonical workflow artifacts under `docs/adr/<dir>/_workflow/`, acknowledges any companion files, and aborts when a required file is missing. The skill is read-only against everything it finds: it never edits the artifacts under review.

**Resolve `<dir>`.** Default to the current branch name from `git branch --show-current`, matching the `/create-plan` default. When `docs/adr/<branch>/_workflow/` does not exist in the local checkout, fall back to the list-and-pick path: enumerate every `docs/adr/*/_workflow/` directory that contains an `implementation-plan.md`, present the names, and ask the reviewer to pick one. Use the picked name as `<dir>`.

**Enumerate canonical artifacts.** Required under `docs/adr/<dir>/_workflow/`:

- `implementation-plan.md`
- `design.md`

Optional, load only when present:

- `design-mechanics.md` (length-triggered per `.claude/workflow/conventions.md` §1.2)
- `plan/track-*.md` (one file per planned track)

**Acknowledge companion files.** List `design-mutations.md` (present whenever `design.md` has been mutated) and any transient `handoff-*.md` for visibility. Do not load them into the review context unless the reviewer asks.

**Missing canonical file.** When `implementation-plan.md` or `design.md` is missing under the resolved `<dir>`, abort with an error naming both expected paths and pointing the reviewer at the list-and-pick fallback.

## Research mode

The skill enters research-mode Q&A driven by the reviewer once preflight and artifact discovery succeed. The reviewer drives the conversation; the skill answers questions about the loaded artifacts, auto-records observations when its own analysis surfaces a gap, and loads workflow rule files on demand.

**Session-start prelude.** After preflight and artifact discovery, the skill greets the reviewer with a one-line summary naming the PR number, the head SHA, and the resolved `<dir>`, then asks what to investigate. The prelude carries one warning: `Observations live in this conversation only. A /clear mid-session loses them unless you ask the skill to checkpoint` (the checkpoint mechanism lands in a follow-up track). If you ask to checkpoint before the persistent mechanism lands, the skill will print the current observation list as plain text so you can save it manually.

**Free-form Q&A.** No fixed walkthrough order. The reviewer drives; the skill answers questions about any loaded artifact using `Read`, `Grep`, and `Bash`. The skill asks clarifying questions when a question is ambiguous and answers from artifacts already loaded rather than re-fetching the workflow rubric for routine Q&A.

**Observation auto-recording.** When the skill's own analysis surfaces an issue mid-conversation, it records a structured observation with `path` (an artifact path under `_workflow/`), `line` (or a start/end range), `body` (one paragraph naming the gap and grounding it in the cited file or section), and `source` (`skill-analysis`). When a sub-agent returns findings, the skill translates each finding into one observation tagged with the sub-agent's name. When the reviewer asks the skill to record something directly, `source` is `reviewer`. After each new observation the skill prints a one-line confirmation: index, `path:line`, source, and the first 80 chars of the body.

**Observation list operations.** The reviewer can drop an observation by index (`drop 3`) or by source tag (`drop reviewer`); the skill confirms the drop with the surviving list size. Observations are immutable in place — to revise one, drop the old entry and record a new one.

**Workflow-doc trigger conditions.** Load lazily on the named trigger; do not preload.

- `.claude/workflow/conventions.md` when the reviewer asks about plan file structure, scope indicators, or naming conventions.
- `.claude/workflow/research.md` when the reviewer asks about research-mode conventions or wants the canonical rubric.
- `.claude/workflow/design-document-rules.md` when the reviewer asks whether a design section has the right shape (TL;DR, mechanism overview, edge cases, References footer).
- `.claude/workflow/planning.md` when the reviewer asks about Decision Record format expectations.

**Scope rule for code-file questions.** When the reviewer asks about code files in the PR (paths not under `docs/adr/<dir>/_workflow/`), the skill answers using `Read` and `Grep` but does not record observations against those files. For Java symbol-reference questions (callers, overrides, find-usages, "is X still used?"), use mcp-steroid PSI find-usages when reachable; fall back to `Grep` only when mcp-steroid is unreachable and flag the result as grep-only in the answer. The observation list scope stays workflow-artifact-only.

## End-of-session stub

The skill prints the recorded observations and exits without posting anything to the PR. The real `gh api` submission lands in a follow-up track; this section is the placeholder until then.

**Wrap-up trigger words.** Treat any one of `wrap up`, `done`, `submit`, or `finish` from the reviewer as the wrap-up cue. The reviewer's last message must consist of only the trigger word or phrase plus optional surrounding whitespace and punctuation (e.g., `wrap up`, `Done.`, `submit!`); match case-insensitively. Substring matches inside longer prose do not fire wrap-up; if uncertain, ask the reviewer to confirm wrap-up before rendering.

**Non-empty observation list.** Render the list as a numbered Markdown table with four columns: index (1-based), `path:line` (or `path:start-end` for range observations), `source` (the tag set during auto-recording: `skill-analysis`, `reviewer`, or the sub-agent name), and `body` (first 120 chars). Escape `|`, `\`, and backticks in cell bodies; replace literal newlines with `<br>` so the cell stays valid Markdown. Truncate at 120 characters with a trailing ellipsis; do not let a cell exceed one visual line. After the table, print one line noting that the follow-up track replaces this stub with the real submission flow; this stub does not call `gh api`.

**Empty observation list.** Replace the table with one line: `No observations recorded. The submission step lands in a follow-up track.` Then exit cleanly.

**No submission.** This stub posts nothing to the PR. The submission machinery (JSON payload composition, `gh api -X POST /repos/{owner}/{repo}/pulls/{N}/reviews`, approve-vs-request-changes branching keyed on the observation list) lands in the follow-up track.
