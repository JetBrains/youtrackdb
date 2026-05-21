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

The orchestrator also maintains a `dispatchLog` (defined in `### Sub-agent dispatch — DR audit` below): an in-conversation append-only list of `{sub-agent name, ISO-8601 UTC timestamp, one-line summary}` entries, one per spawn. The observation list and the `dispatchLog` are the two conversation-state structures the skill carries forward across turns; both live in this conversation only and a mid-session `/clear` discards them.

**Workflow-doc trigger conditions.** Load lazily on the named trigger; do not preload.

- `.claude/workflow/conventions.md` when the reviewer asks about plan file structure, scope indicators, or naming conventions.
- `.claude/workflow/research.md` when the reviewer asks about research-mode conventions or wants the canonical rubric.
- `.claude/workflow/design-document-rules.md` when the reviewer asks whether a design section has the right shape (TL;DR, mechanism overview, edge cases, References footer).
- `.claude/workflow/planning.md` when the reviewer asks about Decision Record format expectations.

**Scope rule for code-file questions.** When the reviewer asks about code files in the PR (paths not under `docs/adr/<dir>/_workflow/`), the skill answers using `Read` and `Grep` but does not record observations against those files. For Java symbol-reference questions (callers, overrides, find-usages, "is X still used?"), use mcp-steroid PSI find-usages when reachable; fall back to `Grep` only when mcp-steroid is unreachable and flag the result as grep-only in the answer. The observation list scope stays workflow-artifact-only.

### Sub-agent dispatch — DR audit

The skill spawns the DR-audit sub-agent on the reviewer's request to audit the Decision Records in the loaded `implementation-plan.md`. The sub-agent returns a structured findings block; the orchestrator translates each finding into one observation and appends an entry to the in-conversation `dispatchLog`.

**Trigger phrases.** Treat any of the following from the reviewer as the cue to spawn DR audit: `audit the DRs`, `audit the decision records`, `check the decision records`, `check the DRs`, `run the DR audit`. Match case-insensitively against the reviewer's full message, allowing surrounding whitespace and punctuation. Substring matches inside longer prose do not fire; if uncertain, ask one clarifying question.

**Spawn call.** Dispatch via the Agent tool with `subagent_type: "dr-audit"`. The agent's prompt body lives at `.claude/agents/dr-audit.md` (registered as a project-scoped sub-agent like every other in-skill dispatch target; the agent's frontmatter declares its model, so do not override `model` from the dispatch call). Pass `plan_path` as the first line of the prompt in the form `plan_path: <value>` so the sub-agent can parse it before any other input. The sub-agent reads `design.md` on its own when a Decision Record cites `**Full design**: design.md §...`; the orchestrator does not pre-pass `design.md`. The literal call shape, with `plan_path` interpolated:

```
Agent({
  subagent_type: "dr-audit",
  prompt: "plan_path: docs/adr/<dir>/_workflow/implementation-plan.md"
})
```

The sub-agent's inputs are Markdown only (`implementation-plan.md` and optionally `design.md`); no PSI / Java reference-accuracy questions arise inside this audit, so the grep-vs-PSI rule (`.claude/workflow/conventions.md` §1.4) does not apply.

**Sub-agent dispatch failure.** Every outcome other than the sub-agent returning a well-formed `## Summary` plus optional `## Findings` block is a dispatch failure, not a translation failure. Apply the following rules in order, before the **Finding-to-observation translation** block runs:

- The sub-agent returns no parsable `## Summary` block, or returns prose without the structured schema: do not append a `dispatchLog` entry, do not translate any findings, surface a one-line error to the reviewer naming the failure shape (for example `dr-audit returned no Summary block; nothing recorded`), and offer to retry.
- `## Summary` is present but `decisions_audited` or `findings_count` is missing or non-numeric: record as a soft failure. Surface the malformation to the reviewer, do not translate findings, and do not append a `dispatchLog` entry.
- An individual `### F<i>` block is missing one of the required fields (`decision`, `category`, `plan_line`, `quote`, `body`): drop that finding only, append a `dispatchLog` entry covering the remaining well-formed findings, and warn the reviewer with the dropped finding's available fields (or its raw text when every field is absent).
- A `### F<i>` block carries a non-integer or zero `plan_line`: fall through to the existing **No explicit file citation** anchoring rule below.
- The spawn itself errors (network, tool error, sub-agent crashes mid-stream): record as a dispatch failure, do not append a `dispatchLog` entry, and offer to retry.

**Finding-to-observation translation.** Parse the sub-agent's `## Findings` blocks. For each `### F<i>` block, extract `decision`, `category`, `plan_line`, `quote`, and `body`. Build one observation `{path, line, body, source}` where `path` is the `plan_path` echoed in the sub-agent's `## Summary`, `line` is the integer `plan_line` from the finding, `body` is the finding's `body` paragraph (verbatim, no rewrap), and `source` is the literal string `dr-audit`. Append each observation to the running list via the auto-recording rules in `## Research mode` above; the one-line confirmation prints the same way it does for `skill-analysis` observations.

Three anchoring edge cases override the verbatim mapping:

- **No explicit file citation.** When a finding's `plan_line` is absent or non-positive, anchor the observation at the artifact the sub-agent was reviewing (the `plan_path` echoed in `## Summary`) at the nearest `##` heading line at or above the implied location. The source tag stays `dr-audit`.
- **Quoted prose edited since the sub-agent's read.** When the finding's `quote` does not match the current content at `plan_line`, search the artifact for the literal `quote` string and use the matched line if exactly one match is found. When zero or multiple matches are found, record the observation at the sub-agent's reported `plan_line` and prepend `[STALE: verify line]` to the body so the reviewer sees the flag at prune time.
- **Reviewer wants a broader cold-read.** A request for design cold-read (as opposed to DR audit) is out of scope here. Tell the reviewer to invoke the existing cold-read flow as a separate skill in the same session; that skill's output does not flow back into this observation list automatically.

**`dispatchLog` structure.** The orchestrator maintains a single in-conversation `dispatchLog`: an ordered list of `{sub-agent name, timestamp, summary}` entries. `sub-agent name` is the literal sub-agent identifier (`dr-audit`); `timestamp` is the ISO-8601 UTC instant at spawn time (e.g., `2026-05-21T13:45Z`); `summary` echoes the sub-agent's `## Summary` block as a one-line digest (`decisions_audited=<N>, findings_count=<N>`). Append one entry on every sub-agent spawn, including spawns that return zero findings. The follow-up handoff-writer track reads `dispatchLog` on resume to skip re-spending on the same audit unless the reviewer asks; producing it here keeps the inter-track boundary honest. Repeated spawns append a second entry; the follow-up handoff-writer track reads the latest entry per `sub-agent name` as the authoritative "last run". Second-spawn findings are appended verbatim to the observation list and may duplicate first-spawn observations; the reviewer is expected to prune duplicates at wrap-up.

## Wrap-up and submission

The skill renders the observation list, accepts prune commands, composes the JSON payload for the `pulls/{N}/reviews` endpoint, asks for one final confirmation, and POSTs the bulk line-anchored review back to the PR. This section documents the wrap-up trigger, the table render and prune commands, the 50-entry pre-flight warning, the head-SHA re-fetch, the one-line confirmation prompt, the POST and review-URL print, the empty observation list branch, the payload composer, and the path / line validation that runs before the POST. On the next wrap-up trigger word the head-SHA re-fetch, 50-entry warning, and confirmation prompt all run again from scratch; cached state from a cancelled attempt is discarded.

**Wrap-up trigger words.** Treat any one of `wrap up`, `done`, `submit`, or `finish` from the reviewer as the wrap-up cue. The reviewer's last message must consist of only the trigger word or phrase plus optional surrounding whitespace and punctuation (e.g., `wrap up`, `Done.`, `submit!`); match case-insensitively. Substring matches inside longer prose do not fire wrap-up; if uncertain, ask the reviewer to confirm wrap-up before rendering. The trigger word must appear exactly once; multiple trigger words in the same message (e.g., `Done. Submit!`) trigger the "ask the reviewer to confirm wrap-up" clarification path rather than firing immediately.

**Non-empty observation list.** Render the list as a numbered Markdown table with four columns: index (1-based), `path:line` (or `path:start-end` for range observations), `source` (the tag set during auto-recording: `skill-analysis`, `reviewer`, or the sub-agent name), and `body` (first 120 chars). Escape `|`, `\`, and backticks in cell bodies; replace literal newlines with `<br>` so the cell stays valid Markdown. Truncate at 120 characters with a trailing ellipsis; do not let a cell exceed one visual line. Observations carrying the `[STALE: verify line]` prefix are rendered with the prefix visible in the body column; the 120-char truncation preserves the leading prefix. After the table, prompt the reviewer to prune the list or confirm submission.

**Prune commands.** Accept `drop`-verb commands operating on the numbered table: `drop 3, 7` (drop the entries at the named indices), `drop all from dr-audit` (drop every entry whose `source` matches the named tag), and `keep all` (skip pruning and proceed straight to confirmation). Bare numeric arguments always reference the displayed index column, never a source tag. A sub-agent named with a numeric-looking identifier still resolves through the `drop all from <tag>` form, not the bare-number form. Reject unrecognized verbs by re-printing the table and reminding the reviewer of the three accepted shapes. After each successful `drop`, re-render the table with the new indices so subsequent commands refer to the visible list.

Edge-case handling for malformed `drop` input: out-of-range indices report the offending number and the rest of the `drop` list still processes; unknown source tags drop zero entries and print a one-line warning naming the unknown tag and the known tags; `drop` with no arguments is rejected like an unrecognized verb; comma and whitespace separators in the index list are both accepted (`drop 3, 7`, `drop 3 7`, and `drop 3,7` all parse the same); negative indices are rejected as out-of-range; `drop all from <known-tag>` when zero observations carry that tag is a no-op with a confirming one-line message.

**50-entry pre-flight warning.** When the pruned list has more than 50 entries at the point the reviewer signals they are done pruning (any wrap-up trigger word repeated, or an explicit `submit`), print a warning naming the count (for example `52 observations will be posted as inline comments. Confirm to proceed.`) and ask the reviewer to confirm before composing the JSON payload. A negative or unclear answer returns the reviewer to prune mode without re-rendering the table; an affirmative answer proceeds.

**Head-SHA re-fetch.** Re-fetch the head SHA via `gh pr view <ref> --json headRefOid -q .headRefOid` immediately before composing the payload. When the re-fetched SHA matches the value cached during `## Preflight`, proceed to the confirmation prompt below. When it differs, follow `design.md` §"HEAD-SHA verification": print the cached SHA and the new SHA and ask the reviewer to choose between (a) refresh and re-verify line numbers against the new content, or (b) abort the submission and re-checkout. The default safe path is abort; on abort, the observation list survives so the reviewer can re-run wrap-up after `gh pr checkout <ref>` brings the local tree back to the PR head. On option (a), run the refresh procedure step-by-step: re-fetch `gh pr view <ref> --json files,headRefOid` and the `.files[].path` cache (re-applying the 100-entry pagination fallback from **Path validation** below when the cached list has exactly 100 entries); re-run **Path validation** and **Line validation** against the new diff for every surviving observation; mark every newly-stale observation by prepending `[STALE: verify line]` to its body field; update the cached SHA to the re-fetched value; re-render the prune table when any observation flipped to stale; then return to the **Confirmation prompt** below.

**Confirmation prompt.** After the head-SHA check clears and the JSON payload has been composed per the **Submission payload composer** sub-block below, print one line summarizing the submission and asking for confirmation: `REQUEST_CHANGES with 12 comments to PR <N>?` for the non-empty branch, or `APPROVE PR <N> with no inline comments?` for the empty branch. A negative or unclear answer cancels and returns the reviewer to prune mode with the observation list intact. An affirmative answer proceeds to POST.

**POST and URL.** On confirmation, POST the composed payload via `gh api -X POST /repos/{owner}/{repo}/pulls/{N}/reviews --input -` (the JSON body is fed on stdin). On a successful response, parse `.html_url` from the response and print it on one line so the reviewer can open the submitted review in a browser. On a non-zero exit, surface the `gh api` stderr and keep the observation list intact so the reviewer can retry without re-running the audit. The cached head SHA reverts to the session-start value so the next wrap-up trigger re-runs the head-SHA re-fetch from scratch. On a `422 Unprocessable Entity` response from GitHub, parse the response body for the offending comment index and prepend `[REJECTED: <reason from GitHub>]` to that observation's body before returning to prune mode. On other non-zero exits (network errors, 5xx, rate limits, auth lapses), return to prune mode unchanged.

**Empty observation list.** Skip the table render, the prune step, and the 50-entry warning. Still run the head-SHA re-fetch above before composing the payload. A mid-session push invalidates the cached SHA whether or not observations were recorded. Show the empty-branch confirmation prompt (`APPROVE PR <N> with no inline comments?`) and on confirmation POST the `APPROVE` payload composed per the **Submission payload composer** sub-block below (one-line body `All workflow artifacts review clean.`, no `comments[]` field on the JSON object). Print the resulting `.html_url` on success.

**Submission payload composer.** Build the JSON body the `pulls/{N}/reviews` endpoint expects: `commit_id`, `body`, `event`, and `comments[]`. `commit_id` is the head SHA verified at session start; the wrap-up flow re-fetches it via `gh pr view --json headRefOid` and revalidates against the cached value immediately before composing the payload, per `design.md` §"HEAD-SHA verification". `event` is `APPROVE` when the pruned observation list is empty and `REQUEST_CHANGES` when any observation remains; the auto-generated `body` is a one-paragraph summary (for the non-empty case, the observation count and source mix, for example `8 observations recorded from DR audit. See inline comments below.`; for the empty case, the one-line `All workflow artifacts review clean.`). Each surviving observation becomes one `comments[]` element shaped `{path, line, side: "RIGHT", body}`: `path` and `line` are the observation's anchored values, `body` is the observation body verbatim. Omit the `comments[]` array entirely on the empty-list `APPROVE` branch.

**Path validation.** For every comment in `comments[]`, confirm `path` is in the PR's changed file set. The skill already fetched `.files[].path` via `gh pr view --json files` during `## Preflight`; use the cached list. When the cached list has exactly 100 entries, the gh CLI has silently truncated the array (upstream cap, `cli/cli#5368`); re-fetch the full list via `gh api repos/{owner}/{repo}/pulls/{N}/files --paginate -q '.[] | {path: .filename, changeType: .status}'` and use that result. Each entry from the fallback carries `path` (the filename) and `changeType` (one of `ADDED`, `MODIFIED`, `RENAMED`, `COPIED`, `CHANGED`, `REMOVED`); preserve both fields for the line-validation step below.

**Line validation.** For every comment, confirm `line` falls within the file's PR diff or current content. Parse the file's diff hunks from `gh pr diff`. When `gh pr diff` errors or returns empty, fall back to the per-file `patch` field returned by `gh api repos/{owner}/{repo}/pulls/{N}/files --paginate` as a backup hunk source. When both fail, abort submission with a one-line error naming the failing command; the observation list survives and the reviewer can retry after `gh auth status` / network recovery. With a usable hunk source in hand, apply the validation rules in this order: (1) when the file's `changeType` is `ADDED`, every line of the current file content is a valid `line` target; (2) when the file's `changeType` is `MODIFIED`, `RENAMED`, `COPIED`, or `CHANGED`, only the added or modified lines reported by the diff hunks are valid targets; (3) a `line` outside both the diff hunks and the current file content marks the observation as stale; (4) when the file's `changeType` is `REMOVED`, no `line` is a valid comment target with `side=RIGHT`, so mark every observation against a removed file as stale so the reviewer can drop or re-anchor it at prune time (do not add `side=LEFT` support; that broadens scope beyond this track). Marking an observation as stale means prepending the literal string `[STALE: verify line]` to its body field; this is the same mechanism used in the `### Sub-agent dispatch — DR audit` anchoring edge case for sub-agent findings that lost their citation line. Stale observations are surfaced to the reviewer at prune time (when the numbered observation table is rendered for `drop` commands) rather than silently dropped; the reviewer chooses to drop them or re-anchor to a valid line.
