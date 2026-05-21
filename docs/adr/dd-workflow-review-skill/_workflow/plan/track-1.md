# Track 1: Skill scaffolding and research-mode runtime

## Purpose / Big Picture

A reviewer can invoke `/review-workflow-pr <N>` and land in research-mode Q&A
against a verified PR checkout, with observations auto-recorded into an
in-conversation list.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

This track delivers a usable skill stub. When invoked it accepts a PR
identifier, verifies the local checkout matches the PR head SHA, loads the
workflow review context, discovers the workflow artifacts under
`docs/adr/<dir>/_workflow/`, and enters research-mode Q&A. Observations the
skill detects during analysis are auto-recorded into an in-conversation list.
The end-of-session submission step is stubbed for this track and the reviewer
is shown the observation list as plain text without PR posting; Track 2 adds
the DR-audit sub-agent and the `gh api` submission machinery.

## Progress
- [x] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review
- [ ] Track completion
- [x] 2026-05-21T09:48Z [ctx=safe] Review + decomposition complete
- [x] 2026-05-21T10:03Z [ctx=safe] Step 1 complete (commit 079bdd761a6c192e91bfff30c981cde0533d3ed0)
- [x] 2026-05-21T10:06Z [ctx=info] Step 2 complete (commit 1f7e4acac244028532e0323d71f4bbffb11db1a7)
- [x] 2026-05-21T10:09Z [ctx=info] Step 3 complete (commit de2a801295cd7fd994afd1d33da73155b2d2143e)

## Surprises & Discoveries
<!-- Continuous-log. Promoted by the orchestrator from per-step "What was
discovered" when the finding affects future steps or other tracks. Empty
at Phase 1. -->

- 2026-05-21T10:03Z `.claude/skills/**` is durable content outside the ephemeral-identifier rule's exclude list (the rule excludes only `docs/adr/*/_workflow/**` and `.claude/workflow/**`). Tracks 2 and 3 must avoid Track/Step/finding-ID labels in skill bodies, comments, and the `dr-audit.md` prompt; cite by file path, class/method, or stable workflow-doc anchor. See Episodes §Step 1.

## Decision Log
<!-- Continuous-log. Execution-time decisions: inline-replan choices,
scope-downs, dependency reveals, gate-override reasons. -->

<!-- Reserved for Move 1 — per-track inlined Decision Records. -->

## Outcomes & Retrospective
<!-- Continuous-log. Review iteration outcomes and the track-completion
summary at Phase C. -->

- [x] Technical: PASS at iteration 1 (7 findings; 6 accepted and applied as track-file edits, 1 negative finding confirmed). T1–T3 were `should-fix` (missing-PR error path, `design-mutations.md` enumeration, wrap-up trigger and empty-list rendering); T4, T5, T7 were `suggestion`-tier polish (in-memory warning, code-file question scope, workflow-doc trigger conditions); T6 was a negative finding (Non-Goals respected).

## Context and Orientation

The skill lives under `.claude/skills/review-workflow-pr/`. At Phase 1 that
directory does not exist; this track creates it and the `SKILL.md` file
inside.

The reviewer pre-runs `gh pr checkout <N>` so the working tree is on the PR
head. Workflow artifacts under review live at `docs/adr/<dir>/_workflow/`:

- `implementation-plan.md` (required)
- `design.md` (required)
- `design-mechanics.md` (optional, length-triggered per `conventions.md` §1.2)
- `plan/track-*.md` (one per planned track)

Companion files the workflow may also write under the same directory:
`design-mutations.md` (append-only mutation log, present whenever
`design.md` has been mutated) and optional `handoff-*.md` (transient
pause state). The skill enumerates these for visibility but does not
load them into the review context unless the reviewer asks.

`<dir>` defaults to the branch name (matching the `/create-plan` default).
When the branch-name directory does not exist, the skill lists
`docs/adr/*/_workflow/` directories present in the local checkout that
contain an `implementation-plan.md` and asks the reviewer to pick.

Workflow docs the skill reaches for on demand (no preload), each tagged
with the trigger that loads it:

- `.claude/workflow/conventions.md` — when the reviewer asks about plan
  file structure, scope indicators, or naming conventions.
- `.claude/workflow/research.md` — defines the skill's own research-mode
  behavior; loaded once at session start.
- `.claude/workflow/design-document-rules.md` — when the reviewer asks
  whether a design section has the right shape (TL;DR, mechanism
  overview, edge cases, References footer).
- `.claude/workflow/planning.md` — when the reviewer asks about Decision
  Record format expectations (alternatives, rationale, risks, track ref).
  Track 2's DR-audit sub-agent reads this independently; the orchestrator
  may still need it for free-form questions outside that audit.

Concrete deliverables of this track:

- `.claude/skills/review-workflow-pr/SKILL.md` with frontmatter (`name`,
  `description`, `argument-hint`, `user-invocable: true`) and instructions
  covering: argument resolution, PR detection, HEAD-SHA verification,
  workflow-doc loading on demand, artifact discovery, research-mode entry,
  observation list management, and an end-of-session stub that prints the
  observation list.
- A working end-to-end flow that ends with the observation list printed to
  stdout (no PR submission yet; that lands in Track 2).

## Plan of Work

The track lands the skill in roughly the following sequence (Phase A will
decompose the precise step boundaries):

1. Create `.claude/skills/review-workflow-pr/` and seed `SKILL.md` with
   frontmatter and the top-level outline. Document the invocation contract:
   PR identifier as `$ARGUMENTS`, default to the current branch's PR.
2. Write the preflight instructions: parse `$ARGUMENTS` (PR number, URL, or
   branch), resolve to a PR number via `gh pr view <ref>`, fetch head SHA
   and changed files via `gh pr view --json headRefOid,number,files`,
   resolve owner/repo via `gh repo view --json nameWithOwner`, verify local
   HEAD matches with `git rev-parse HEAD`, error out with a clear
   `gh pr checkout` command on mismatch. If `gh pr view` exits non-zero
   (no PR for the current branch, or the ref does not resolve), surface
   its stderr and tell the reviewer to either pass an explicit PR
   number/URL as `$ARGUMENTS` or open a PR first.
3. Write the artifact discovery instructions: resolve `<dir>` (branch-name
   default, list-and-pick fallback when missing), enumerate the workflow
   artifacts under `docs/adr/<dir>/_workflow/`, fail clearly if the
   canonical files are missing.
4. Write the research-mode section: how the skill enters Q&A, how it
   presents itself to the reviewer at session start, how observations are
   recorded mid-conversation, how the skill behaves when the reviewer asks
   artifact-specific questions vs broad audits. The session-start prelude
   includes a one-line warning that observations live in-conversation and
   are lost on `/clear` until Track 3's checkpoint mechanism lands. When
   the reviewer asks about code files in the PR (not under `_workflow/`),
   the skill answers the question using `Read`/`Grep` but does not record
   observations against those files — the observation list scope stays
   workflow-artifact-only.
5. Write the end-of-session stub: when the reviewer signals wrap-up
   (`wrap up`, `done`, `submit`, or `finish`), show the observation list
   as a numbered table (index, `path:line`, source, body), note that
   Track 2 will add the real submission, and exit cleanly. If the list
   is empty, replace the table with a single line: `No observations
   recorded. Track 2 will add the submission step.`

Ordering: step 1 first (the file must exist before later steps add to it).
Steps 2-3 are independent of 4 within the same file but flow naturally in
the order above. Step 5 closes the file.

Invariants this track preserves:

- The skill is read-only relative to the workflow artifacts on the PR
  branch. No `Edit`, `Write`, or `git commit` against them.
- Workflow docs load lazily, only when the skill reaches for a rule it
  needs at that moment.
- House-style applies to the skill's own Markdown.

## Concrete Steps

1. Seed `.claude/skills/review-workflow-pr/SKILL.md` with frontmatter (`name`, `description`, `argument-hint`, `user-invocable: true`) and the section-header outline (Invocation contract, Preflight, Artifact discovery, Research mode, End-of-session stub). — risk: low (default: Markdown-only skill scaffold; no production code)  [x] commit: 079bdd761a6c192e91bfff30c981cde0533d3ed0
2. Write the Preflight section: `$ARGUMENTS` resolution, `gh pr view --json headRefOid,number,files`, `gh repo view --json nameWithOwner`, `git rev-parse HEAD`, HEAD-SHA mismatch remediation, and the non-zero-`gh pr view` exit fallback (no PR for the current branch or unresolved ref). — risk: low (default: Markdown instruction prose)  [x] commit: 1f7e4acac244028532e0323d71f4bbffb11db1a7
3. Write the Artifact discovery section: `<dir>` resolution and list-and-pick fallback, canonical artifact enumeration, companion-file acknowledgment (`design-mutations.md`, optional `handoff-*.md`), and the missing-canonical-file error. — risk: low (default: Markdown instruction prose)  [x] commit: de2a801295cd7fd994afd1d33da73155b2d2143e
4. Write the Research mode section: session-start prelude with the in-memory observation warning, free-form Q&A behavior, observation auto-recording, the four workflow-doc trigger conditions, and the scope rule for code-file questions (answer but do not record). — risk: low (default: Markdown instruction prose)  [ ]
5. Write the End-of-session stub section: the four wrap-up trigger words (`wrap up`, `done`, `submit`, `finish`), the numbered-table rendering (index, `path:line`, source, body), the empty-list one-line fallback, and the deferred-submission note pointing to Track 2. — risk: low (default: Markdown instruction prose; Track 2 replaces this section)  [ ]

## Episodes

### Step 1 — commit 079bdd761a6c192e91bfff30c981cde0533d3ed0, 2026-05-21T10:03Z [ctx=safe]
**What was done:** Seeded `.claude/skills/review-workflow-pr/SKILL.md` with YAML frontmatter (`name`, `description`, `argument-hint`, `user-invocable: true`) and the five `##` section-header outline (Invocation contract, Preflight, Artifact discovery, Research mode, End-of-session stub). Each section carries a placeholder HTML comment naming what the section-fill steps will land. Added a house-style blockquote pointing chat-scale prose at the AI-tell-subset section list in `house-style.md`, mirroring the convention from `create-plan/SKILL.md` and `review-plan/SKILL.md`.

**What was discovered:** Skills under `.claude/skills/` are durable content outside the ephemeral-identifier rule's exclude list (the rule excludes only `docs/adr/*/_workflow/**` and `.claude/workflow/**`). The first placeholder comments cited Track/Step labels and were rewritten before staging. Tracks 2 and 3 will land more skill content (`dr-audit.md`, the submission-section rewrite, handoff prose) and must follow the same discipline: cite by file path, class/method name, or stable workflow-doc anchor instead of Track/Step/finding-ID labels.

**What changed from the plan:** none

**Key files:**
- `.claude/skills/review-workflow-pr/SKILL.md` (new)

**Critical context:** none

### Step 2 — commit 1f7e4acac244028532e0323d71f4bbffb11db1a7, 2026-05-21T10:06Z [ctx=info]
**What was done:** Filled the `## Preflight` section in `.claude/skills/review-workflow-pr/SKILL.md`. The body opens with a BLUF lead naming the three preflight steps (resolve PR, fetch head SHA and changed files, confirm local checkout matches), then walks five labelled phases: `$ARGUMENTS` resolution (PR number, URL, branch, empty default to current PR), `gh pr view <ref> --json headRefOid,number,files` plus the separate `gh repo view --json nameWithOwner` call, `git rev-parse HEAD` verification against `headRefOid`, HEAD-SHA mismatch remediation citing `gh pr checkout <ref>` with the detached-HEAD note, and the non-zero `gh pr view` exit fallback. Section body sits at 199 words, inside the 200-word cap.

**What was discovered:** none

**What changed from the plan:** none

**Key files:**
- `.claude/skills/review-workflow-pr/SKILL.md` (modified)

**Critical context:** none

### Step 3 — commit de2a801295cd7fd994afd1d33da73155b2d2143e, 2026-05-21T10:09Z [ctx=info]
**What was done:** Filled the `## Artifact discovery` section in `.claude/skills/review-workflow-pr/SKILL.md`. The body opens with a BLUF lead naming the four moves (resolve `<dir>`, enumerate canonical artifacts, acknowledge companion files, abort on missing canonical file) and restates the read-only invariant. Four labelled phases follow: `<dir>` resolution from `git branch --show-current` with the list-and-pick fallback over `docs/adr/*/_workflow/` directories containing an `implementation-plan.md`; canonical enumeration of required `implementation-plan.md` plus `design.md` and optional `design-mechanics.md` plus `plan/track-*.md`; companion-file acknowledgment for `design-mutations.md` and any transient `handoff-*.md`; and the missing-canonical-file abort. Section body sits at 182 words.

**What was discovered:** none

**What changed from the plan:** none

**Key files:**
- `.claude/skills/review-workflow-pr/SKILL.md` (modified)

**Critical context:** none

## Validation and Acceptance

After this track lands, a reviewer can:

- Run `/review-workflow-pr <N>` against a workflow PR they have checked out
  and reach the research-mode prompt within one turn.
- Get a clear error message and `gh pr checkout` remediation when the local
  HEAD does not match the PR head.
- Get a clear error message naming the failing `gh pr view` invocation
  when no PR exists for the current branch and no explicit ref was passed.
- Get a clear error message when the canonical workflow artifacts are
  missing under `docs/adr/<dir>/_workflow/`.
- Ask to wrap up (`wrap up`, `done`, `submit`, `finish`) and see the
  full observation list printed as a numbered table — or a single
  `No observations recorded.` line when the list is empty; the skill
  notes that submission is not yet implemented.

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines used verbatim as test method names. Empty until Move 3 lands. -->

## Idempotence and Recovery

All five steps write to `.claude/skills/review-workflow-pr/SKILL.md`
(step 1 also creates the parent directory). Each step owns one named
`##` section in that file; section boundaries are stable enough that
re-running a step replaces only its own section.

- **Idempotence.** Re-running a step rewrites its target section with
  the same content. Intermediate state from an interrupted write is
  overwritten on retry; cross-section state is untouched.
- **Recovery.** Roll back a failed step with
  `git checkout -- .claude/skills/review-workflow-pr/SKILL.md`. If
  step 1 failed after creating the directory, also run
  `git clean -fd .claude/skills/review-workflow-pr/`. Then re-run the
  step from `mode=INITIAL`.

## Artifacts and Notes
<!-- Continuous-log (rare). Often empty. -->

## Interfaces and Dependencies

In-scope files for this track:

- `.claude/skills/review-workflow-pr/SKILL.md` (new)

Out-of-scope for this track (handled in Track 2):

- `.claude/skills/review-workflow-pr/dr-audit.md`
- The submission section of `SKILL.md` (stubbed in Track 1, replaced in
  Track 2)

Existing files referenced but not modified:

- `.claude/workflow/conventions.md`, `research.md`,
  `design-document-rules.md`, `planning.md`: read on demand for rule
  lookups during the research-mode conversation.

External tools the skill calls during this track:

- `gh pr view --json headRefOid,files,number`
- `gh repo view --json nameWithOwner`
- `git rev-parse HEAD`

Inter-track dependencies:

- Track 2 consumes the SKILL.md scaffolding from this track and extends the
  end-of-session section to replace the stub with the real submission flow.

No library or function signatures to declare; this is a Markdown skill.

## Base commit

a3ef497332359f1a7f5a3b8f44e1fbf22460d6fb
