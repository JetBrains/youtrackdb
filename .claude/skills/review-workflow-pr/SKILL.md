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

<!-- Placeholder. To be filled with: `<dir>` resolution (branch-name
default, list-and-pick fallback), enumeration of canonical artifacts
under `docs/adr/<dir>/_workflow/`, companion-file acknowledgment
(`design-mutations.md`, optional `handoff-*.md`), and the
missing-canonical-file error. -->

## Research mode

<!-- Placeholder. To be filled with: session-start prelude with the
in-memory observation warning, free-form Q&A behavior, observation
auto-recording, the four workflow-doc trigger conditions, and the scope
rule for code-file questions (answer but do not record). -->

## End-of-session stub

<!-- Placeholder. To be filled with: the four wrap-up trigger words
(`wrap up`, `done`, `submit`, `finish`), the numbered-table rendering
(index, `path:line`, source, body), the empty-list one-line fallback,
and a deferred-submission note pointing readers at the eventual real
`gh api` submission flow. -->
