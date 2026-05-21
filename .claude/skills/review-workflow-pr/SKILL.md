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

<!-- Placeholder. To be filled with: `$ARGUMENTS` resolution, PR
detection via `gh pr view --json headRefOid,number,files`, owner/repo
via `gh repo view --json nameWithOwner`, local HEAD verification via
`git rev-parse HEAD`, HEAD-SHA mismatch remediation, and the non-zero
`gh pr view` exit fallback. -->

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
