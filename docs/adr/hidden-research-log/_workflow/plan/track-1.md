<!-- workflow-sha: e8a35443b0a639ff87f1c4d63e2c82b2d5010393 -->
# Track 1: Make the research log agent-internal during Phase 0

## Purpose / Big Picture
After this track, a Phase-0 research conversation never exposes the research log's structure: the agent keeps `research-log.md` as silent durable memory and surfaces findings, trade-offs, and decisions to the user as plain prose.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

YTDB-1124. `research.md` tells the agent to append decisions to the research log but never says the log stays out of the research conversation, so the agent leaks log bookkeeping at the user ("recorded as D3", section names, quoted `**Why:**`/`**Alternatives rejected:**` fields). This track adds a user-facing opacity rule to `research.md` §Rules, rewords the two leak-inviting passages (the "Record decisions" bullet and the §Transition "confirms the log captures…" line), and reflects the rule in `create-plan` SKILL.md's Phase-0 narration. The log stays the agent's silent durable memory; findings reach the user as plain prose.

## Progress
- [x] Review + decomposition
- [x] Step implementation
- [x] Track-level code review
- [ ] Track completion
- [x] 2026-06-15T05:44Z [ctx=safe] Review + decomposition complete
- [x] 2026-06-15T06:05Z [ctx=safe] Step 1 complete (commit 69c673e359)
- [x] 2026-06-15T06:54Z [ctx=safe] Track-level code review iteration 1 complete (1/3 iterations)
- [x] 2026-06-15T07:00Z [ctx=safe] Track-level code review iteration 2 complete (2/3 iterations)
- [x] 2026-06-15T07:02Z [ctx=safe] Track complete

## Surprises & Discoveries
<!-- Continuous-log. Promoted by the orchestrator from per-step "What was
discovered" when the finding affects future steps or other tracks. Empty
at Phase 1. -->

## Decision Log
<!-- The track-canonical live decision carrier (D7). Seeded from the research
log; the track file is the live authority. -->

#### D1: Use §1.7 staging, not the §1.7(k) prose-rule opt-out
- **Alternatives considered**: (a) §1.7(k) prose-rule self-application opt-out, editing both files live — the conceptually appealing dogfooding path; (b) §1.7(k) opt-out confined to `research.md` only, dropping the `create-plan` SKILL.md edit; (c) §1.7 staging (chosen).
- **Rationale**: The Phase-0→1 adversarial gate raised a blocker (iter1 A1): §1.7(k) criterion (2) is a **file-level consumer-class** test, explicitly "consumer class, not author intent" (`conventions.md:1231,1243`). `create-plan/SKILL.md` is read by the agent as the `/create-plan` orchestration procedure (an execution-procedure file), so it must stage; the edit-level "it's just narration prose" reading is not permitted. Because the §1.7(b) and §1.7(k) markers are mutually exclusive per plan, editing any execution-procedure file makes the opt-out unavailable, not merely weaker. Alternative (b) keeps the opt-out but narrows the change below the issue's "reflect it in the create-plan Phase 0 narration" scope. Staging honors the full issue scope and is unambiguously rule-compliant. User-confirmed.
- **Risks/Caveats**: No self-application this session. Live `research.md` stays at develop until the Phase-4 promotion, so this `/create-plan` run is not held to the new rule (the correct trade per §1.7's reasoning, since `create-plan/SKILL.md` is running machinery). This branch exercises §1.7(l)'s review-criteria re-point through the (b) workflow-modifying trigger: the prose-lens switch fired on the Phase-A technical review. Conventions §1.7(l):1320 reserves the separate first-via-(k) opt-out milestone for a later branch, so this note claims no first-via-(k) status. The minimal stub template has no native `### Constraints`, so the workflow-modifying marker is hand-rolled into a one-line section — tracked by YTDB-1125 (broadened to cover both §1.7 markers).
- **Implemented in**: this track (the §1.7(b) marker lives in the plan's `### Constraints`; staged edits under `_workflow/staged-workflow/.claude/`).

#### D2: Opacity rule placement — canonical in §Rules, cross-referenced elsewhere
- **Alternatives considered**: Put the rule only in `research.md` §The research log (the section that defines the log); fully rewrite the affected sections; reword in place (chosen). Touch §Goal/§Overview for symmetry.
- **Rationale**: §Rules is the behavioral-rule home the issue names, so the canonical opacity rule lands there as a new bullet. §How it works and §Transition get small cross-referencing rewords, and §The research log gets a one-line discoverability note so a reader landing on the log's definition learns it is agent-internal. Rewording in place (not rewriting) keeps the change minimal and issue-faithful; §Goal/§Overview stay untouched to avoid scope creep.
- **Risks/Caveats**: The §The research log one-liner is one section beyond the three the issue names; included for rule coherence (the structural review checks coherence) and kept to a single cross-referencing sentence. If the Phase-A review judges it scope creep, it is the first thing to drop.
- **Implemented in**: this track.

#### D3: Preserve the two sanctioned user-facing recaps as explicit carve-outs
- **Alternatives considered**: State the opacity rule unconditionally; carve out the exceptions explicitly (chosen).
- **Rationale**: Two existing user-facing recaps are not leaks and must survive: (1) the Phase-1 transition plain-language findings summary (`research.md` §Transition), already named by the issue; (2) the `create-plan` Step-4 adversarial-gate verdict recital and tier proposal, which surface findings and blockers, not log structure. The opacity rule names these as the sanctioned recaps so it does not muzzle them. An unconditional rule would read as forbidding the transition summary, contradicting §Transition.
- **Risks/Caveats**: None material; this is a precision clause that prevents a self-contradiction in `research.md`.
- **Implemented in**: this track.

#### D4: Tier = minimal (single-track prose change)
- **Alternatives considered**: `minimal` (chosen); `lite` (no design, multi-track).
- **Rationale**: Gate 1 is no (no central HIGH-risk category — a `.claude/**` edit that matches none of the `Workflow machinery` HIGH criteria: no hook/script/settings, no load-bearing gate or control-flow protocol, no shared schema, no always-loaded surface). Gate 2 is single (two small prose files, one logical change). `lite` would imply multi-track and misrepresent the change shape, and would write a fuller aggregator plan than this edit warrants.
- **Risks/Caveats**: The `minimal` stub template carries no native `### Constraints`, so the §1.7(b) workflow-modifying marker is hand-rolled into a one-line section (see D1; tracked by YTDB-1125).
- **Implemented in**: this track (the plan header's `**Change tier:** minimal` line; the one-line `### Constraints` in the plan).

## Outcomes & Retrospective
<!-- Continuous-log. Review iteration outcomes and the track-completion
summary at Phase C. -->
- [x] Technical: PASS at iteration 1 (1 finding, 1 accepted) — minimal-tier
  prose-only track, §1.7(l) prose lenses. All 11 certificates CONFIRMED; no
  blocker/should-fix. T1 (suggestion, accepted): tightened D1's §1.7(l)
  "first to exercise" caveat to name the (b) trigger and disclaim the
  first-via-(k) milestone.
- [x] Phase C track-level review: PASS in 2 of 3 iterations. The diff was
  workflow-only, so the five §1.7(l) prose-lens reviewers ran (consistency,
  prompt-design, instruction-completeness, context-budget, writing-style) and
  the four code baselines were skipped. Iteration 1 raised six findings.
  WI1 and WP1 collapsed to one opacity-rule ambiguity: the "one sanctioned
  recap" wording read as exclusive and dropped Decision-Log D3's second
  carve-out (the create-plan Step-4 gate-verdict recital). WP2 flagged a
  cross-reference handle that did not match the §Rules bullet title, and WS1
  through WS3 flagged em-dash overuse in the track file's own prose. The
  implementer fixed the two staged-file findings (scoping the recap clause to
  the log itself and naming the Step-4 verdict as a permitted non-recap;
  aligning the handle); the orchestrator fixed the track-file em-dashes. The
  iteration-1 reword introduced one regression (WS4, a fresh em-dash pair in
  the opacity bullet) that the gate-check caught and iteration 2 fixed. All
  final gate-checks returned VERIFIED. No deferred findings, no plan
  corrections.

## Context and Orientation
This track edits two workflow-prose files. Their current leak-inviting text:

- **`.claude/workflow/research.md` §Rules — the "Record decisions in the
  research log" bullet** (develop `:183`): *"…acknowledge it clearly and
  append it to the log's `## Decision Log` with its `**Why:**` and
  `**Alternatives rejected:**` fields."* The "acknowledge it clearly" plus
  the field list reads as license to echo the log structure at the user.
- **`research.md` §Transition to Phase 1, step 1** (develop `:151`):
  *"The agent confirms the research log captures the key findings…"* — can
  turn into asking the user to review or reference the log.
- **`research.md` §How it works, step 4** (develop `:138`): the *"Appends
  decisions, surprises, and open questions to the research log as they
  settle"* bullet — the natural place for a silent-logging cross-reference.
- **`.claude/skills/create-plan/SKILL.md` Step 3 (Phase-0 research-mode
  list)** (develop `:270-274`): the *"Append decisions, surprises, and open
  questions to the research log"* bullet. This text is agent-facing (it says
  what to write *in* the log), so it does not itself narrate to the user;
  the reflection here is a cross-reference to the new §Rules opacity rule.
  Step 2 (`:237`, seed the log) is likewise agent-facing.

Two existing user-facing recaps are **not** leaks and must be preserved
(D3): the Phase-1 transition plain-language findings summary (§Transition),
and the Step-4 adversarial-gate verdict recital plus tier proposal in
`create-plan` SKILL.md.

Staging note: live `research.md` and `create-plan/SKILL.md` stay at develop
for the branch (I6, §1.7(g)); all edits go to the staged mirror under
`_workflow/staged-workflow/.claude/`, copied-then-edited on first touch
(§1.7(e)).

## Plan of Work
The two files are independent; no inter-edit ordering constraint. For each,
copy the live develop-state file into its staged path verbatim on first
touch, then edit the staged copy.

1. **`research.md` (staged copy):**
   a. §Rules — add a new opacity bullet: maintain `research-log.md`
      silently (no write narration, no section names, no D-numbers, no
      quoted entries to the user); surface findings, trade-offs, and
      decisions as plain conversational prose; the one sanctioned
      structured recap is the Phase-1 transition summary (plain language,
      not log quotes).
   b. §Rules — reword the "Record decisions" bullet so the acknowledgment
      is conversational and the field-shaped logging is silent ("the
      acknowledgment is conversational; the logging is the agent's private
      bookkeeping"). Keep the "log carries decisions across a `/clear`"
      point.
   c. §How it works, step 4 — append a short "(silently — the log is
      agent-internal; see §Rules)" cross-reference to the append bullet.
   d. §Transition to Phase 1, step 1 — reword "confirms the research log
      captures…" into an internal completeness check the agent runs for its
      own use, and state the user-facing output is the plain-language
      findings summary, not a log review (D3 carve-out).
   e. §The research log — add a one-line discoverability note that the log
      is the agent's internal memory, never surfaced to the user during
      research (cross-ref §Rules). (D2 — drop first if Phase-A review judges
      it scope creep.)
2. **`create-plan/SKILL.md` (staged copy):**
   a. Step 3 research-mode list — reword the "Append decisions…" bullet to
      add the silent/agent-internal clause and a cross-reference to
      `research.md` §Rules.
   b. Step 2 — optional light cross-reference that the log is agent-internal
      (keep minimal; agent-facing file).

**Invariants to preserve:** do not change the research log's six-section
structure or the append cadence — the opacity rule governs user-facing
narration, not the log's internal shape; do not weaken the S2 read-scope
discipline (`research.md` §The research log); keep all existing
cross-references resolving. **No new `##`/`###` headings** are added in
either file, so the TOC region and per-section annotations are unchanged;
confirm with `workflow-reindex.py --check` against the staged copies.

## Concrete Steps

1. Apply the research-log opacity rule across both staged Phase-0 surfaces: the five `research.md` edits (§Rules opacity bullet plus the reworded "Record decisions" bullet, the §How it works step-4 silent-logging cross-ref, the §Transition step-1 internal-completeness reword, and the §The research log discoverability note) and the two `create-plan/SKILL.md` edits (Step-3 "Append decisions…" reword plus the Step-2 agent-internal cross-ref), per `## Plan of Work` steps 1a–1e and 2a–2b. Copy each live develop-state file into its `_workflow/staged-workflow/.claude/` mirror path verbatim on first touch (§1.7(e)), then edit the staged copy; preserve the two D3 carve-outs and add no new `##`/`###` headings. — risk: medium (workflow machinery: multi-file prose that changes agent-observable behavior; bounded: drives no gate, runs nothing automatically) — size: ~2 files; (a) no mergeable low/medium work fits: the two staged files are the entire single-track change  [x] commit: 69c673e359

## Episodes
<!-- Continuous-log. Phase B sub-step 7 appends one block per completed
step. Empty at Phase 1. -->

### Step 1 — commit 69c673e359, 2026-06-15T06:05Z [ctx=safe]
**What was done:** Applied the research-log opacity rule to both staged
Phase-0 surfaces. Copied the live develop-state `research.md` and
`create-plan/SKILL.md` into the `_workflow/staged-workflow/.claude/` mirror
(§1.7(e) copy-then-edit on first touch), then made seven edits on the staged
copies. In `research.md`: a new §Rules bullet that keeps `research-log.md`
agent-internal (no write narration, section names, D-numbers, or quoted
`**Why:**`/`**Alternatives rejected:**` fields to the user); a reworded
§Rules "Record decisions" bullet that makes the acknowledgment conversational
and the field logging private; a silent-logging cross-reference on the §How
it works step-4 append bullet; an internal-completeness reword of the
§Transition step-1 line that names the plain-language findings summary as the
user-facing output; and a one-line discoverability note in §The research log.
In `create-plan/SKILL.md`: matching agent-internal cross-references in the
Step-2 seed sentence and the Step-3 append bullet. Both D3 carve-outs survive
un-muzzled: the §Transition summary is named as the one sanctioned recap, and
the Step-4 adversarial-gate verdict recital is untouched.

**What was discovered:** `workflow-reindex.py` globs the staged subtree
directly (`…/_workflow/staged-workflow/.claude/workflow/**/*.md` and
`…/skills/**/SKILL.md`), so `--check` validated the staged copies in-scope and
passed at exit 0. The staged-aware reindex works as the plan assumed; there is
no live-path validation gap.

**Key files:**
- `docs/adr/hidden-research-log/_workflow/staged-workflow/.claude/workflow/research.md` (new)
- `docs/adr/hidden-research-log/_workflow/staged-workflow/.claude/skills/create-plan/SKILL.md` (new)

## Validation and Acceptance
This is a workflow-prose change; the "test" surrogates are doc-consistency
checks and the reviewer passes, not Java/unit tests.

- A fresh `/create-plan` Phase-0 session, reading the updated `research.md`
  §Rules, maintains the log without narrating its structure to the user (no
  D-numbers, no section names, no quoted `**Why:**`/`**Alternatives
  rejected:**` fields, no "I've logged this as D3").
- The Phase-1 transition still produces a plain-language findings summary
  (D3 carve-out preserved); the reworded `research.md` does not read as
  forbidding it.
- The reworded "Record decisions" bullet no longer reads as "echo the log
  structure."
- No new `##`/`###` headings; `workflow-reindex.py --check` passes on the
  staged copies; TOC and section annotations unchanged.
- Both edited files satisfy the full house style (`conventions.md` §1.5;
  workflow Markdown tier).

**Per-step acceptance (Step 1).** This is a prose-only workflow change with
no Java/unit tests, so the step has no EARS/Gherkin acceptance lines used as
test method names. Step 1's acceptance is the five track-level doc-consistency
checks above, evaluated against the staged copies: the opacity rule lands in
`research.md` §Rules; the two reworded passages no longer read as license to
echo the log; both D3 carve-outs survive un-muzzled; no new `##`/`###`
headings (`workflow-reindex.py --check` passes on the staged copies); and both
files satisfy the workflow Markdown house-style tier.

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines used verbatim
as test method names. Empty until Move 3 lands. -->

## Idempotence and Recovery
**Step 1.** Idempotent by construction. The §1.7(e) copy-then-edit routing
keys off whether a staged copy already exists: re-running the step on a fresh
tree copies each live develop-state file into its staged mirror and applies
the seven edits; re-running with the staged copies already present edits in
place. Either way the end state is the same staged content. Recovery on a
failed or abandoned attempt is the implementer's standard `git reset --hard
HEAD`, which discards the uncommitted staged subtree; the next attempt
re-copies from the still-untouched live files and redoes the edits. No live
`.claude/**` file is mutated before the Phase-4 promotion, so a mid-step abort
leaves the running workflow machinery untouched (the I6 invariant).

## Artifacts and Notes
<!-- Continuous-log (rare). Often empty. -->

## Interfaces and Dependencies
**In-scope (edited, staged):**
- `_workflow/staged-workflow/.claude/workflow/research.md` — staged mirror
  of `.claude/workflow/research.md`.
- `_workflow/staged-workflow/.claude/skills/create-plan/SKILL.md` — staged
  mirror of `.claude/skills/create-plan/SKILL.md`.

**Out-of-scope:**
- The live `.claude/workflow/research.md` and
  `.claude/skills/create-plan/SKILL.md` — they stay at develop state until
  the Phase-4 §1.7(f) promotion commit copies staged → live.
- Every other workflow doc, prompt, and agent file — a grep over
  `.claude/{workflow,skills,agents}` confirmed no third Phase-0 surface
  narrates the log to the user (the other hits are Phase-1 read-the-log
  derivation references and review bookkeeping).
- `workflow-reindex.py`, the §1.6 stamp scheme, and the §1.7 staging
  machinery — used, not modified.

**Dependencies:** none (single track).

**Activation:** the §1.7(b) workflow-modifying marker in the plan's
`### Constraints` activates the staging write-routing and the §1.7(l)
Phase-3A review-criteria re-point onto the prose lenses (rule coherence,
instruction completeness, prompt-design soundness, context-budget impact,
breakage of dependent prompts/agents).

## Base commit
1ac9ec14510d54b69f5e2bd1e2b19048041034b7
