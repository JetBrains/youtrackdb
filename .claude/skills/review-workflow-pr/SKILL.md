---
name: review-workflow-pr
description: "Review a workflow-style PR's design document, implementation plan, and track files in research-mode Q&A. Auto-records observations and submits a bulk line-anchored approve-or-request-changes review via gh api. TRIGGER when: user asks to review a workflow PR or run /review-workflow-pr. SKIP: non-workflow PRs without docs/adr/<dir>/_workflow/."
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

<!-- Placeholder. To be filled with: argument shapes accepted as
`$ARGUMENTS`, the default-to-current-branch-PR behavior, and the
one-turn handshake into research mode. -->

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

**Session-start prelude.** After preflight and artifact discovery, the skill greets the reviewer with a one-line summary naming the PR number, the head SHA, and the resolved `<dir>`, then asks what to investigate. The prelude carries one warning: `Observations live in this conversation only. A /clear mid-session loses them unless you ask the skill to checkpoint` (the checkpoint mechanism lands in a follow-up track).

**Free-form Q&A.** No fixed walkthrough order. The reviewer asks questions about any loaded artifact and the skill answers using `Read`, `Grep`, and `Bash`. The skill loads `.claude/workflow/research.md` once at session start because it defines the research-mode behavior in use here.

**Observation auto-recording.** When the skill's own analysis surfaces an issue mid-conversation, it records a structured observation with `path` (an artifact path under `_workflow/`), `line` (or a start/end range), `body` (one paragraph naming the gap and grounding it in the cited file or section), and `source` (`skill-analysis`). When a sub-agent returns findings, the skill translates each finding into one observation tagged with the sub-agent's name. When the reviewer asks the skill to record something directly, `source` is `reviewer`. After each new observation the skill prints a one-line confirmation: index, `path:line`, source, and the first ~80 chars of the body.

**Workflow-doc trigger conditions.** Load lazily on the named trigger; do not preload.

- `.claude/workflow/conventions.md` when the reviewer asks about plan file structure, scope indicators, or naming conventions.
- `.claude/workflow/research.md` once at session start (defines this skill's own research-mode behavior).
- `.claude/workflow/design-document-rules.md` when the reviewer asks whether a design section has the right shape (TL;DR, mechanism overview, edge cases, References footer).
- `.claude/workflow/planning.md` when the reviewer asks about Decision Record format expectations.

**Scope rule for code-file questions.** When the reviewer asks about code files in the PR (paths not under `docs/adr/<dir>/_workflow/`), the skill answers using `Read` and `Grep` but does not record observations against those files. The observation list scope stays workflow-artifact-only.

## End-of-session stub

<!-- Placeholder. To be filled with: the four wrap-up trigger words
(`wrap up`, `done`, `submit`, `finish`), the numbered-table rendering
(index, `path:line`, source, body), the empty-list one-line fallback,
and a deferred-submission note pointing readers at the eventual real
`gh api` submission flow. -->
