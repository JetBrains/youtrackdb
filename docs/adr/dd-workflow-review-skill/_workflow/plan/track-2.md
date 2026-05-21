# Track 2: DR-audit sub-agent and PR submission

## Purpose / Big Picture

A reviewer can audit the Decision Records via a focused sub-agent and submit
the accumulated observations to the PR as a single line-anchored review
(approve or request-changes).

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

This track adds the new DR-audit sub-agent prompt and the `gh api`
submission machinery. The DR audit walks each Decision Record in
`implementation-plan.md` and surfaces gaps in alternatives, rationale, risks,
and track references. The submission step composes a JSON payload for the
`pulls/{N}/reviews` endpoint, asks the reviewer to confirm once, and POSTs
the review (approve when the observation list is empty, request-changes
otherwise).

## Progress
- [x] Review + decomposition
- [x] Step implementation
- [ ] Track-level code review
- [ ] Track completion
- [x] 2026-05-21T12:53Z [ctx=info] Review + decomposition complete
- [x] 2026-05-21T13:16Z [ctx=safe] Step 1 complete (commit 828700b0672d26d422ed827f618da5397fd94981)
- [x] 2026-05-21T13:23Z [ctx=info] Step 2 complete (commit 84806a16a101a802b4b1a1712e2169e1395ecd8d)
- [x] 2026-05-21T13:28Z [ctx=info] Step 3 complete (commit b3c217ee1539301dfabb5ddca8471ac0fdafbe76)
- [x] 2026-05-21T13:33Z [ctx=info] Step 4 complete (commit ba73287e9c9f967e14602eb3c173728191c54b3e)
- [x] 2026-05-21T13:33Z [ctx=info] Step implementation complete
- [x] 2026-05-21T13:58Z [ctx=info] Track-level code review iteration 1 complete (1/3 iterations)

## Surprises & Discoveries
<!-- Continuous-log. Empty at Phase 1. -->

- 2026-05-21T13:16Z The ephemeral-identifier pre-commit gate fires on quoted-placeholder backtick usage (the `ephemeral-identifier-rule.md` §"How to rewrite a forbidden reference" pattern). Matches that resolve to teaching-by-example forms (literal `Track N`/`Step N` quotation, DR `D<n>` self-identification, output-schema token declarations) are allowed exceptions. Steps 2-4 of this track and any Track 3 skill-prose authoring should expect the gate to fire and resolve by inspection rather than rewrite. See Episodes §Step 1.
- 2026-05-21T13:23Z The ephemeral-identifier pre-commit gate regex `\b[A-Z]{1,3}-?[0-9]+\b` matches inside ISO-8601 instants (`T13` in `2026-05-21T13:45Z`). Resolved by inspection as an allowed date/time literal exception. Steps 3, 4 and Track 3 will hit this when documenting timestamps; standing resolution is the same. Candidate for an explicit allowed-exception bullet in `ephemeral-identifier-rule.md` if the pattern keeps recurring. See Episodes §Step 2.
- 2026-05-21T13:28Z Forward-pointer discipline for prose-injection steps that own half of a two-step section: anchor forward-pointers by named subsection or by behavior, never by step number. The initial Step 3 draft cited `Step 4` twice as a forward-pointer and would have tripped the pre-commit ephemeral-identifier regex; the rewrite cost on multi-hunk patches is non-trivial. Track 3's handoff-writer authoring will hit the same shape. See Episodes §Step 3.
- 2026-05-21T13:33Z Wrap-up section adds two state semantics Track 3's resume must preserve: abort-on-head-SHA-mismatch retains the observation list (re-runnable after `gh pr checkout <ref>`); a negative or unclear confirmation answer cancels and returns to prune mode with the list intact. Track 3's checkpoint-then-resume round trip should treat both as resumable wrap-up states, not fresh research-mode states, and should serialise observation lists eagerly enough to preserve `[STALE: verify line]` flags. See Episodes §Step 4.

## Decision Log
<!-- Continuous-log. -->

<!-- Reserved for Move 1 — per-track inlined Decision Records. -->

## Outcomes & Retrospective
<!-- Continuous-log. -->

- [x] Technical: PASS at iteration 2 (11 findings; all 11 accepted and applied as track-file edits to Plan of Work steps 1-4 plus the Inter-track dependencies pointer). T1 added the `gh pr view --json files` 100-entry pagination fallback. T2 routed the mid-session head-SHA-moved case through `design.md` §"HEAD-SHA verification" with abort as the default. T3 added the `dispatchLog` producer Track 3 will consume. T4 standardized prune commands on `drop` and pinned bare numeric arguments to the index column. T5 reordered line validation to put diff-hunk membership first. T6 named the section rename (`## End-of-session stub` → `## Wrap-up and submission`). T7 placed the DR-audit dispatch wiring under `## Research mode` as a new `### Sub-agent dispatch — DR audit` subsection. T8–T11 (suggestion-tier) tightened the DR bullet-key form, translation-rule citation, 50-entry pre-flight warning, and the `dr-audit.md` house-style + ephemeral-identifier discipline. No new findings, no regressions.

## Context and Orientation

Track 1 has landed the skill scaffolding. The `SKILL.md` already accumulates
observations into an in-conversation list. The end-of-session step is stubbed
(prints the list to stdout).

This track creates one new file and extends one existing file:

- New: `.claude/skills/review-workflow-pr/dr-audit.md`. Sub-agent prompt
  instructing a fresh agent to parse Decision Records from a given
  `implementation-plan.md`, check the four-bullet form, verify
  `Implemented in:` references, optionally verify `Full design:` link
  targets, and return structured findings.
- Modified: `.claude/skills/review-workflow-pr/SKILL.md`. Adds the DR-audit
  dispatch wiring and replaces the end-of-session stub with the real
  prune-confirm-submit flow.

GitHub REST endpoint used for submission:

- `POST /repos/{owner}/{repo}/pulls/{N}/reviews`
- Body: `{ commit_id, body, event, comments[] }`
- `event` is one of `APPROVE`, `REQUEST_CHANGES`, `COMMENT`.
- Each `comments[]` entry: `{ path, line, side: "RIGHT", body }`.

The reviewer's GitHub auth comes from the existing `gh` CLI session
(verifiable by `gh api /user` if needed).

## Plan of Work

Roughly (Phase A decomposes the exact step boundaries):

1. Author `dr-audit.md`. The prompt instructs the sub-agent to read
   `implementation-plan.md`, enumerate `#### D<n>:` blocks, verify the
   four-bullet shape (canonical bolded keys `**Alternatives considered**`,
   `**Rationale**`, `**Risks/Caveats**`, `**Implemented in**` per
   `.claude/workflow/planning.md` §Decision Records; optional
   `**Full design**`), verify `**Implemented in**: Track X` matches an
   existing track in the checklist, verify the optional
   `**Full design**: design.md §...` link resolves, and return findings in
   a structured Markdown format the orchestrator can parse. The prompt
   opens with the same `> **House style …**` blockquote `SKILL.md`
   carries (per `.claude/output-styles/house-style.md`) and instructs the
   sub-agent to cite findings by file path and heading anchor, never by
   ephemeral identifiers (Track / Step / finding numbers), per the
   `.claude/skills/**`-is-durable-content discovery recorded in Track 1's
   Surprises & Discoveries (track-1.md:40).
2. Extend `SKILL.md` with a `### Sub-agent dispatch — DR audit`
   subsection under `## Research mode` covering (a) the reviewer trigger
   phrases ("audit the DRs", "check the decision records"), (b) the
   spawn call with `plan_path` argument, (c) the finding-to-observation
   translation per `design.md` §"DR-audit sub-agent and findings
   translation" (Translation paragraph plus the three anchoring edge-case
   bullets), and (d) an in-conversation `dispatchLog` structure (a list
   of `{sub-agent name, timestamp, summary}` entries appended whenever
   the orchestrator spawns a sub-agent). The dispatch log is the slot
   Track 3 reads on resume to skip re-spending on the same audit;
   producing it here keeps the inter-track boundary honest.
3. Implement the submission payload composer in `SKILL.md`. Compose
   `body` (auto-generated summary), pick `event` (zero observations →
   `APPROVE`, otherwise `REQUEST_CHANGES`), build `comments[]` from the
   observation list. Validate each comment's `path` against
   `.files[].path` from `gh pr view --json files`; when `files.length
   == 100`, the gh CLI has silently truncated the array (upstream cap,
   `cli/cli#5368`), so re-fetch the full list via
   `gh api repos/{owner}/{repo}/pulls/{N}/files --paginate -q '.[] |
   {path: .filename, changeType: .status}'` and use that. Validate each
   comment's `line` against the file's diff hunks parsed from
   `gh pr diff`: for `ADDED` files every line of current content is
   valid; for `MODIFIED` / `RENAMED` / `COPIED` / `CHANGED` files only
   the added or modified lines are valid comment targets. A `line`
   outside both the diff hunks and the current file content marks the
   observation as stale; the skill surfaces it to the reviewer at prune
   time rather than silently dropping.
4. Implement the wrap-up flow in `SKILL.md`. Rename the existing
   `## End-of-session stub` section to `## Wrap-up and submission` when
   the body lands (the four wrap-up trigger words from Track 1 carry
   forward unchanged). Present the observation list as a numbered table
   and accept prune commands using the verb `drop` (matching Track 1's
   mid-session `drop 3` / `drop reviewer` shape in `SKILL.md`):
   `drop 3, 7`, `drop all from dr-audit`, `keep all`. Bare numeric
   arguments always reference the displayed index column, never a
   source-tag — this resolves the drop-by-source-tag numeric-name
   ambiguity Track 1 deferred. When the pruned list exceeds 50 entries,
   print a warning naming the count and ask the reviewer to confirm
   before composing the JSON payload. Re-fetch the head SHA via
   `gh pr view --json headRefOid`; when it differs from the cached value,
   follow `design.md` §"HEAD-SHA verification" and ask the reviewer to
   choose between aborting (the default safe path) or accepting the new
   SHA after re-verifying observation line numbers against the refreshed
   content. Build the JSON payload, show a one-line confirmation prompt
   (for example `REQUEST_CHANGES with 12 comments to PR <N>?`), and on
   confirmation POST via
   `gh api -X POST /repos/{owner}/{repo}/pulls/{N}/reviews --input -`.
   Print the resulting review URL.
5. Handle the empty-list path: `event=APPROVE`, body is a one-line "All
   workflow artifacts review clean.", no `comments[]` in the payload.

Ordering: step 1 first (so step 2 has a target to spawn). Steps 3-4 share
file scope with step 2 and depend on the observation translation working;
they land after step 2. Step 5 is a small branch inside step 4 and can land
together with it.

Invariants this track preserves:

- No observation reaches the PR without the reviewer's explicit
  confirmation.
- Each comment's `path` is in the PR's changed file list at submission
  time.
- Each comment's `line` falls within the file's current content and within
  the PR diff (added or modified lines).

## Concrete Steps

1. Create `.claude/skills/review-workflow-pr/dr-audit.md` with the DR-audit prompt: frontmatter (no `user-invocable`, since the prompt is sub-agent-only), the canonical bolded-key parse rules (`**Alternatives considered**`, `**Rationale**`, `**Risks/Caveats**`, `**Implemented in**`, optional `**Full design**` per `.claude/workflow/planning.md` §Decision Records), the `**Implemented in**: Track X` resolution against the plan's `## Checklist`, the optional `**Full design**: design.md §...` heading-resolution, the structured Markdown output format the orchestrator parses, and the opening `> **House style …**` blockquote plus the ephemeral-identifier discipline (cite by file path / heading anchor, never Track/Step/finding numbers). — risk: low (default: new Markdown sub-agent prompt; no production code, no concurrency, no API surface)  [x] commit: 828700b0672d26d422ed827f618da5397fd94981
2. Modify `.claude/skills/review-workflow-pr/SKILL.md`: insert a new `### Sub-agent dispatch — DR audit` subsection under `## Research mode` covering the reviewer trigger phrases (`audit the DRs`, `check the decision records`), the spawn call with the `plan_path` argument, the finding-to-observation translation per `design.md` §"DR-audit sub-agent and findings translation" (Translation paragraph plus the three anchoring edge-case bullets), and an in-conversation `dispatchLog` structure (a list of `{sub-agent name, timestamp, summary}` entries appended whenever the orchestrator spawns a sub-agent; consumed by Track 3 on resume). — risk: low (default: Markdown instruction prose; no production code)  [x] commit: 84806a16a101a802b4b1a1712e2169e1395ecd8d
3. Modify `.claude/skills/review-workflow-pr/SKILL.md`: rename `## End-of-session stub` to `## Wrap-up and submission` and rewrite the section body's submission-payload half. Compose `body` (auto-generated summary), pick `event` (zero observations → `APPROVE`; otherwise `REQUEST_CHANGES`), build `comments[]` from the observation list, validate each comment's `path` against `.files[].path` from `gh pr view --json files` with the `gh api repos/{owner}/{repo}/pulls/{N}/files --paginate -q '.[] | {path: .filename, changeType: .status}'` fallback when `files.length == 100` (gh CLI upstream cap `cli/cli#5368`), and validate each comment's `line` against the file's diff hunks parsed from `gh pr diff` (every line of current content valid for `ADDED` files; only added or modified lines for `MODIFIED` / `RENAMED` / `COPIED` / `CHANGED` files; a `line` outside both the diff hunks and the current file content is surfaced to the reviewer at prune time rather than silently dropped). — risk: low (default: Markdown instruction prose; the heading rename is a single-section edit)  [x] commit: b3c217ee1539301dfabb5ddca8471ac0fdafbe76
4. Modify `.claude/skills/review-workflow-pr/SKILL.md`: complete the `## Wrap-up and submission` section body's user-facing flow. Present the observation list as a numbered table; accept `drop`-verb prune commands (`drop 3, 7`, `drop all from dr-audit`, `keep all`) with bare numeric arguments pinned to the displayed index column (resolves the drop-by-source-tag numeric-name ambiguity Track 1 deferred); print a warning and ask the reviewer to confirm when the pruned list exceeds 50 entries; re-fetch the head SHA via `gh pr view --json headRefOid` and follow `design.md` §"HEAD-SHA verification" when it differs from the cached value (abort is the default safe path); show a one-line confirmation prompt (for example `REQUEST_CHANGES with 12 comments to PR <N>?`); on confirmation POST via `gh api -X POST /repos/{owner}/{repo}/pulls/{N}/reviews --input -` and print the resulting review URL; and document the empty-list APPROVE branch (`event=APPROVE`, one-line body "All workflow artifacts review clean.", no `comments[]` in the payload). — risk: low (default: Markdown instruction prose; user-facing flow documentation only)  [x] commit: ba73287e9c9f967e14602eb3c173728191c54b3e

## Episodes

### Step 1 — commit 828700b0672d26d422ed827f618da5397fd94981, 2026-05-21T13:16Z [ctx=safe]
**What was done:** Added `.claude/skills/review-workflow-pr/dr-audit.md`, the focused DR-audit sub-agent prompt. Frontmatter carries `name` plus `description` only (no `user-invocable`, sub-agent-only). The body opens with the house-style blockquote (AI-tell subset per `conventions.md` §1.5) and pins ephemeral-identifier discipline in a dedicated section: cite by file path plus heading anchor or line number, never workflow-internal labels. Parse rules enumerate `#### D<n>:` blocks, check the four canonical bolded-key bullets plus the optional `**Full design**` bullet per `.claude/workflow/planning.md` §Decision Records, resolve `**Implemented in**: Track X` against the plan's `## Checklist`, and resolve the optional `**Full design**: design.md §...` heading against `## ` headings in `design.md`. Output format declares `## Summary` plus `## Findings` for the orchestrator parse, with four finding categories: `missing-key`, `stub-content`, `unresolved-track`, `unresolved-full-design`.

**What was discovered:** The ephemeral-identifier pre-commit gate fires on quoted-placeholder backtick usage (the `ephemeral-identifier-rule.md` §"How to rewrite a forbidden reference" pattern). Three matches resolved to allowed exceptions: the literal forbidden-form citation, the DR self-identification format inside `implementation-plan.md`, and the literal output-schema token declaration. Future skill-prose authoring hits this gate shape repeatedly; resolved-by-inspection is the rule, not rewrite. Cross-track for Track 3 `dispatchLog` consumer: `dr-audit.md` does not produce dispatch-log entries — the spawning `SKILL.md` owns that (lands in this track's Step 2). The finding `quote` field is the re-anchoring lever for the prose-edited-since-sub-agent-read edge case.

**Key files:**
- `.claude/skills/review-workflow-pr/dr-audit.md` (new)

### Step 2 — commit 84806a16a101a802b4b1a1712e2169e1395ecd8d, 2026-05-21T13:23Z [ctx=info]
**What was done:** Added the `### Sub-agent dispatch — DR audit` subsection at the tail of `## Research mode` in `.claude/skills/review-workflow-pr/SKILL.md` (between the code-file scope rule and the `## End-of-session stub` heading). Four named sub-blocks: trigger phrases (case-insensitive reviewer cues, ask one clarifying question when ambiguous), spawn call (target `dr-audit.md`, single `plan_path` argument, `design.md` read-on-demand contract owned by the sub-agent), finding-to-observation translation (per-`### F<i>`-block extraction of `decision` / `category` / `plan_line` / `quote` / `body` into observations tagged `source=dr-audit`, body verbatim, same one-line confirmation as `skill-analysis` observations), and the `dispatchLog` structure (ordered append-only list of `{sub-agent name, timestamp, summary}` appended on every spawn including zero-finding spawns; ISO-8601 UTC timestamps; one-line summary echoing `decisions_audited` / `findings_count` from the sub-agent's `## Summary`). The three anchoring edge cases from `design.md` §"DR-audit sub-agent and findings translation" render as bullets between the translation paragraph and the dispatchLog block.

**What was discovered:** The pre-commit ephemeral-identifier regex matches inside ISO-8601 instants (`T13` in `2026-05-21T13:45Z`). Resolved as an allowed timestamp-literal exception, matching the Step 1 inspection-not-rewrite pattern. Tool routing: `steroid_execute_code`'s suspend body must return `Unit`, not a string, which makes the script awkward for early-exit diagnostic returns on short literal-text edits; `steroid_apply_patch` ran the same single-hunk insertion atomically with no kotlinc compile cycle and is the better fit for this shape.

**Critical context:** The `dispatchLog` structure is defined here as an ordered append-only list with ISO-8601 UTC timestamps and one-line digests of the form `decisions_audited=<N>, findings_count=<N>`. Track 3's handoff-writer resume must reload these and present them as the "which sub-agents have run" panel without re-spending. Track 3's handoff file's `Sub-agent dispatch log` section per `design.md` §"Handoff and resume" step 4 is the on-disk projection of this in-conversation structure; the writer should serialise the three fields verbatim.

**Key files:**
- `.claude/skills/review-workflow-pr/SKILL.md` (modified)

### Step 3 — commit b3c217ee1539301dfabb5ddca8471ac0fdafbe76, 2026-05-21T13:28Z [ctx=info]
**What was done:** Renamed `## End-of-session stub` to `## Wrap-up and submission` in `.claude/skills/review-workflow-pr/SKILL.md` and rewrote the section lead-in plus the submission-payload half. Three named sub-blocks landed: **Submission payload composer** documenting the `commit_id` / `body` / `event` / `comments[]` shape and the `APPROVE` versus `REQUEST_CHANGES` branching with empty-list handling; **Path validation** documenting the cached `.files[].path` check and the `gh api repos/{owner}/{repo}/pulls/{N}/files --paginate -q '.[] | {path: .filename, changeType: .status}'` fallback for the 100-entry silent truncation (upstream `cli/cli#5368`); and **Line validation** documenting the `ADDED` versus `MODIFIED` / `RENAMED` / `COPIED` / `CHANGED` rule against `gh pr diff` hunks plus the stale-observation surface-not-drop policy. The user-facing flow paragraphs (trigger words, observation rendering) stay untouched per the track file's Idempotence note.

**What was discovered:** Forward-pointer discipline matters during drafting even when the pre-commit gate would catch the leak. The initial draft cited `Step 4` twice as a forward-pointer (a natural mental anchor while writing the first half of a two-step section). Rewrote both forward-pointers contract-first: the `commit_id` pointer now cites `design.md` §"HEAD-SHA verification"; the prune-table pointer now describes the behavior ("when the numbered observation table is rendered for `drop` commands") rather than naming the next step. Promoted to Surprises & Discoveries.

**Critical context:** The composer documents the empty-list `APPROVE` branch inline (one-line body, no `comments[]`); the next step's user-facing flow can render the empty-list user prompt consistently without redefining composer behavior. The `gh api ... --paginate` fallback's jq projection `{path: .filename, changeType: .status}` produces a shape Track 3's resume revalidation can reuse without re-shaping.

**Key files:**
- `.claude/skills/review-workflow-pr/SKILL.md` (modified)

### Step 4 — commit ba73287e9c9f967e14602eb3c173728191c54b3e, 2026-05-21T13:33Z [ctx=info]
**What was done:** Completed the user-facing flow half of `## Wrap-up and submission` in `.claude/skills/review-workflow-pr/SKILL.md`. Replaced the Track 1 stub-table-only render with seven named sub-blocks: numbered-table render for the non-empty observation list, `drop`-verb prune commands with bare-numeric arguments pinned to the displayed index column plus `drop all from <tag>` for sources with numeric-looking names, the 50-entry pre-flight warning, head-SHA re-fetch routed through `design.md` §"HEAD-SHA verification" with abort as the default safe path, one-line confirmation prompt (e.g. `REQUEST_CHANGES with 12 comments to PR <N>?`), `gh api -X POST /repos/{owner}/{repo}/pulls/{N}/reviews --input -` with `.html_url` print, and the empty-list APPROVE branch that runs the head-SHA re-fetch, prompts `APPROVE PR <N> with no inline comments?`, POSTs the one-line `All workflow artifacts review clean.` body with no `comments[]`, and prints the review URL. The section lead-in now enumerates every sub-block present so the table-of-contents read matches the surface.

**What was discovered:** Em-dash discipline at chat-scale tier-B authoring caps each blank-line-bounded paragraph at one em dash. The rule "never where a period works" is the lever that flips most of them. Three em dashes in the initial Step 4 draft swapped cleanly to periods without losing meaning, including one inside an authored example string. The mechanical check is paragraph-scoped, so the safer posture during authoring is "use a period unless it actively breaks the cadence" rather than "fit under the cap by counting at the end."

**Critical context:** The lead-in paragraph of `## Wrap-up and submission` enumerates every sub-block present in the section (trigger, table render, prune, 50-entry warning, head-SHA re-fetch, confirmation prompt, POST and URL, payload composer, path validation, line validation). Future edits should keep the enumeration in sync. A divergence between the lead and the actual sub-block order is a house-style finding waiting to happen. Track 3's handoff machinery consumes the cancel-and-retain-list behavior documented in the Confirmation prompt and Head-SHA re-fetch blocks: aborting the submission leaves the observation list intact for re-run after `gh pr checkout <ref>`.

**Key files:**
- `.claude/skills/review-workflow-pr/SKILL.md` (modified)

## Validation and Acceptance

After this track lands, a reviewer can:

- Ask the skill to audit the Decision Records and see one observation per
  identified gap, anchored at the citation line in the plan.
- Wrap up with an empty observation list, confirm once, and see the PR
  receive an `APPROVE` review with a one-line body.
- Wrap up with a non-empty observation list, prune as desired, confirm
  once, and see the PR receive a `REQUEST_CHANGES` review whose inline
  comments are anchored to the cited lines in `design.md`,
  `implementation-plan.md`, or the relevant track files.
- Cancel the submission at the confirmation prompt and return to prune
  mode without losing the observation list.

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3. -->

## Idempotence and Recovery

Step 1 creates one new file (`.claude/skills/review-workflow-pr/dr-audit.md`). Steps 2-4 modify `.claude/skills/review-workflow-pr/SKILL.md`. Step 2 owns the new `### Sub-agent dispatch — DR audit` subsection under `## Research mode`. Steps 3 and 4 share ownership of the renamed `## Wrap-up and submission` section: step 3 rewrites the submission-payload half (composer + path/line validation); step 4 rewrites the user-facing-flow half (table render + prune + pre-flight + head-SHA re-fetch + confirmation + POST + empty-list branch).

- **Idempotence.** Re-running step 1 rewrites `dr-audit.md` in full. Re-running step 2 replaces the `### Sub-agent dispatch — DR audit` subsection. Re-running step 3 rewrites the section heading and the payload-half body. Re-running step 4 rewrites the user-facing-flow half of the renamed section. Cross-section state is untouched.
- **Recovery.** Roll back a failed step with `git checkout -- .claude/skills/review-workflow-pr/SKILL.md` (steps 2-4) or `git clean -fd .claude/skills/review-workflow-pr/dr-audit.md` plus `git checkout -- .claude/skills/review-workflow-pr/SKILL.md` (step 1, in case the SKILL.md scaffolding is touched). Then re-run the failed step from `mode=INITIAL`.

## Artifacts and Notes
<!-- Continuous-log (rare). -->

## Interfaces and Dependencies

In-scope files:

- `.claude/skills/review-workflow-pr/dr-audit.md` (new)
- `.claude/skills/review-workflow-pr/SKILL.md` (extended from Track 1)

External tools the skill calls during this track:

- `gh pr view --json headRefOid,files` (re-fetch at submission)
- `gh api -X POST /repos/{owner}/{repo}/pulls/{N}/reviews --input -`

Inter-track dependencies:

- Depends on Track 1's `SKILL.md` scaffolding (preflight, artifact
  discovery, research-mode entry, observation list).
- Track 3's handoff machinery consumes the `dispatchLog` produced by
  step 2's `### Sub-agent dispatch — DR audit` subsection in `SKILL.md`.

GitHub REST API contract (binding for the JSON payload):

- Endpoint: `POST /repos/{owner}/{repo}/pulls/{N}/reviews`
- Required by GitHub: each `comments[]` element's `path` and `body`. Other
  fields are optional in the API and acquire defaults when omitted
  (`event` omitted yields a PENDING draft; `commit_id` omitted defaults
  to the current PR head; `body` is required only when `event` is
  `REQUEST_CHANGES` or `COMMENT`).
- The skill sends all of `event` (`APPROVE` | `REQUEST_CHANGES`),
  `body` (string), `commit_id` (SHA verified against the current PR
  head), and each comment's `line` plus `side: "RIGHT"` so the review
  is anchored, line-pinned, and never lands as a PENDING draft.

## Base commit

fbfbdd8a137cc10ba7234b2bcd3b2b0016267fbb
