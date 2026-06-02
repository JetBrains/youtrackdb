<!-- workflow-sha: f97512c02f4dbaaf66c7382397907580fd54391b -->
# Staging-aware review machinery

## Design Document
[design.md](design.md)

## High-level plan

### Goals

The workflow reviews its own machinery at three points: a plan-level review
before execution (Phase 2), a track-level review before each track is
decomposed (Phase A), and a dimensional code review of each track's diff
(Phase B/C). Every part addresses workflow files by their live `.claude/...`
path. The `§1.7` staging convention broke that assumption: on a plan that
edits `.claude/workflow/**` or `.claude/skills/**`, the authored edits live
under `docs/adr/<dir>/_workflow/staged-workflow/.claude/...` and the live
files stay at develop's state until one Phase 4 promotion. The review
machinery never learned this and goes stale on such plans in three ways.

This plan closes all three. The reading gap turns out to have two facets, so
the read-side issue YTDB-1038 spans two tracks:

- **YTDB-1032 (selection).** The per-agent triggers match the live
  `.claude/...` path, but a staged path begins with `docs/adr/...`, so three
  workflow reviewers never match and fail to launch. Fix: strip the staged
  prefix before matching triggers.
- **YTDB-1038 (reading), two facets.** First, every review and gate prompt
  hands its agent the live path, so an agent checking a change against a rule
  the branch already rewrote reads develop's version and reports a phantom
  mismatch. Fix (Track 2): a marker-gated caveat in every prompt that routes
  reads through `§1.7(d)` precedence (staged copy when present, else live).
  Second, when the changed file under review is itself a freshly-created staged
  copy, the cumulative diff shows a whole-file add that hides the real delta
  against the live counterpart. Fix (Track 4): the orchestrator pre-stages that
  delta and the reviewer context block scopes findings to it.
- **YTDB-1046 (Phase A criteria).** The Phase A technical, risk, and
  adversarial reviewers apply Java criteria (find-class on named symbols, WAL
  and crash edge cases, data migrations) that have nothing to bind to on a
  track that edits prose. Fix: a marker-gated addendum re-pointing those
  criteria to prose.

### Constraints

This plan is workflow-modifying: it edits .claude/workflow/** or .claude/skills/**.

- All Phase B edits route through `docs/adr/<dir>/_workflow/staged-workflow/`
  per `§1.7`; the live `.claude/...` tree stays at develop's state until the
  Phase 4 promotion (the I6 invariant, `§1.7(g)`).
- **Self-application carve-out (`§1.7(h)`).** This branch stages its own
  edits, so its own Phase A and Phase B/C reviews run against the unfixed
  live machinery. The orchestrator hand-injects the staging and
  prose-criteria guidance during this branch's execution — the same manual
  steps these fixes remove for later plans. The fixes take effect for the
  first workflow-modifying plan opened after this branch promotes.
- The selection mirror must stay in lockstep (S1): `review-agent-selection.md`
  and the matching steps of `code-review/SKILL.md` change in one commit with
  the `<!-- Last sync-checked … -->` date bumped.
- The read caveat and the Phase A addendum must read uniformly across the
  prompts that carry them (S3); all three fixes key off the single `§1.7(b)`
  marker.
- Promotion is additive: the Phase 4 `cp -r` carries additions and edits, not
  deletions. These fixes only add text, so promotion is safe.
- House style (`house-style.md`) applies to every edited Markdown surface.

### Architecture Notes

#### Component Map

This change adds no Java types. The components are workflow documents, the
rules inside them, and the two cross-file mirrors. The diagram models that
topology; the full version with per-edge prose is in design.md §"Class
Design".

```mermaid
flowchart TB
    subgraph selection["Agent selection (mirror pair)"]
        RAS["review-agent-selection.md"]
        CRS["code-review/SKILL.md"]
        RAS <-->|"mirror + sync-date"| CRS
    end
    subgraph prompts["Review and gate-check prompts"]
        DIM["Phase B/C: step-implementation 4(a),<br/>track-code-review, dimensional gate-check"]
        PR["Phase 2: consistency-review,<br/>structural-review"]
        PA["Phase A: technical, risk, adversarial,<br/>gate-verification"]
    end
    NORM(["staged-path normalization"])
    CAVEAT(["1.7(d) read caveat"])
    DELTA(["staged-copy review delta pre-staging"])
    ADD(["workflow-machinery criteria addendum"])
    MARKER{{"1.7(b) marker in plan Constraints"}}

    NORM --> RAS
    NORM --> CRS
    MARKER -->|gates| CAVEAT
    MARKER -->|gates| ADD
    CAVEAT --> DIM
    CAVEAT --> PR
    CAVEAT --> PA
    DELTA -->|"two context blocks"| DIM
    ADD --> PA
```

- **Selection mirror pair** (`review-agent-selection.md` ↔
  `code-review/SKILL.md`): the normalization rule (NORM) lands in both, bound
  by the S1 sync-date constraint. Track 1.
- **Prompt layers** (Phase B/C dimensional, Phase 2 plan, Phase A track): the
  read caveat (CAVEAT) reaches all three; the addendum (ADD) reaches the
  Phase A criteria reviewers only; the delta pre-staging (DELTA) reaches the
  two Phase B/C dimensional context blocks only. Tracks 2, 3, and 4.
- **Marker** (`§1.7(b)` sentence in the plan's `### Constraints`): the single
  gating signal for CAVEAT and ADD, surfaced to review agents through the
  slim plan snapshot. NORM and DELTA key off the staged prefix and need no
  marker — staged paths exist only on plans that carry the marker anyway.

#### D1: Selection — staged-path normalization over per-glob editing

- **Alternatives considered**: extend each literal trigger glob in both mirror
  files to also match the staged `docs/adr/.../staged-workflow/.claude/...`
  prefix; vs one prefix-strip normalization rule applied before the globs run.
- **Rationale**: one normalization rule is DRY — a single rule per mirror file
  instead of editing three reviewers' globs across both mirrors — and a staged
  file then evaluates exactly as its live counterpart would. This is the
  issue's "cheaper" path.
- **Risks/Caveats**: normalization is scoped to the exact two-level
  `…/_workflow/staged-workflow/.claude/` prefix; a path that merely contains
  `.claude/` lower down must not normalize.
- **Implemented in**: Track 1
- **Full design**: design.md §"Selection-side staging awareness"

#### D2: Read caveat self-gates on the marker, not orchestrator injection

- **Alternatives considered**: the orchestrator hand-injects the staged-read
  caveat into each review prompt per review; vs a static caveat embedded in
  the prompt templates that self-gates on the `§1.7(b)` marker.
- **Rationale**: self-gating removes the per-review manual step, which is a
  YTDB-1038 acceptance criterion. The agent detects the marker from the slim
  plan snapshot, which retains `### Constraints` verbatim.
- **Risks/Caveats**: relies on the slim plan retaining `### Constraints`.
  Verified this session — `render-slim-plan.py` copies the strategic header
  `pre` block unchanged and filters only the track checklist. The caveat
  invokes `§1.7(d)`, which as written scopes precedence to the implementer and
  excludes reviewers; Track 2 therefore amends `§1.7(d)` to bring review agents
  on a workflow-modifying plan into that precedence scope. The alternative —
  wording the caveat to override `§1.7(d)` while leaving its text stale — was
  rejected for leaving a self-contradiction in the conventions source.
- **Implemented in**: Track 2
- **Full design**: design.md §"Read-side staging awareness"

#### D3: Caveat rides in the fenced prompt body, not a document section

- **Alternatives considered**: add the caveat as a new `##` document section
  in each host file; vs a short block inside the fenced prompt body.
- **Rationale**: the prompt body keeps the caveat out of the host file's
  section structure, so no TOC row or per-section annotation churns across the
  nine host files.
- **Risks/Caveats**: the two Phase B/C context blocks are parallel copies, not
  a shared include, so the block lands in both with matching meaning (S2).
- **Implemented in**: Track 2
- **Full design**: design.md §"Read-side staging awareness"

#### D4: Phase A criteria via marker-gated addendum

- **Alternatives considered**: new workflow-aware Phase A prompt files; a
  complexity-assessment dispatch swap in `track-review.md`; vs a marker-gated
  addendum inside the existing technical/risk/adversarial prompts.
- **Rationale**: the addendum adds no new files and no dispatch change. The
  same three reviewers self-adapt by reading the marker, mirroring how the
  read caveat self-gates, so a track mixing prose and code gets one reviewer
  applying both lenses.
- **Risks/Caveats**: `review-gate-verification.md` re-checks prior findings
  rather than generating criteria, so it is criteria-agnostic and takes the
  read caveat alone, no addendum.
- **Implemented in**: Track 3
- **Full design**: design.md §"Phase A criteria for workflow-machinery tracks"

#### D5: Review-target delta-scoping — orchestrator pre-stages the delta

- **Alternatives considered**: reviewer-side self-diffing (each review agent
  diffs the staged copy against its live counterpart on its own); vs the
  orchestrator pre-staging a `diff <live> <staged>` delta file and pointing
  reviewers at it through the context block.
- **Rationale**: orchestrator pre-staging is deterministic across the review
  fan-out: every reviewer sees the same scoped delta and the same out-of-scope
  note, where self-diffing repeats the work per agent and varies with each
  agent's interpretation. The orchestrator already stages the diff for review,
  so the delta rides alongside it.
- **Risks/Caveats**: the trigger must be precise: a freshly-created staged copy
  that matches the anchored `…/_workflow/staged-workflow/.claude/…` prefix, is a
  new-file add in the reviewed range, and has a live counterpart. A later edit
  to an already-restaged file is an ordinary diff and stages no delta. The delta
  note rides in the two parallel context blocks only (S2), not the gate-check
  prompt.
- **Implemented in**: Track 4
- **Full design**: design.md §"Read-side staging awareness"

#### Invariants

- **S1 (selection mirror).** `review-agent-selection.md` (§Workflow-machinery
  file set, §Per-agent file-pattern triggers, §Workflow-machinery override)
  and `code-review/SKILL.md` Steps 5a/5b/5d/6 change in one commit with the
  `<!-- Last sync-checked … -->` date bumped. Enforced by
  `review-workflow-consistency` at Phase C; no script checks it. (Track 1)
- **S2 (parallel-block).** The canonical context block in
  `step-implementation.md` sub-step 4(a) and its parallel copy in
  `track-code-review.md` carry the same caveat and delta note, or a Phase C
  review behaves differently from its Phase B counterpart. (Tracks 2 + 4)
- **S3 (uniformity).** The read caveat reads the same across all nine prompts,
  and the Phase A addendum the same across the three criteria prompts; all
  three fixes key off the single `§1.7(b)` marker. (Tracks 2 + 3)

#### Integration Points

- The `§1.7(b)` marker in the plan's `### Constraints` is the gating signal
  for the read caveat and the Phase A addendum, surfaced to review agents via
  the slim plan snapshot (`render-slim-plan.py` retains `### Constraints`).
- Selection normalization plugs into `review-agent-selection.md`
  §Workflow-machinery override and the mirrored `code-review/SKILL.md` Step 5d,
  ahead of the per-agent glob match.
- Delta pre-staging plugs into `track-code-review.md` (the Phase C diff-staging
  step) and `step-implementation.md` (the high-risk Phase B step-review setup),
  with the scope note carried in the two dimensional context blocks (S2).

#### Non-Goals

- This branch does not fix its own Phase A/C review (self-application
  carve-out, `§1.7(h)`); the orchestrator hand-injects during execution.
- No new Phase A prompt files and no change to the `track-review.md`
  complexity-assessment dispatch (D4).
- `workflow-reindex.py --check` gains no mirror check and no staged-copy
  awareness (adjacent gap, noted in design.md §"Consistency invariants and
  self-application" edge cases).
- Does not fix the `create-plan` SKILL design-vs-plan ordering the user
  flagged (separate PR).

## Checklist
- [x] Track 1: Selection-side staging awareness (YTDB-1032)
  > On a workflow-modifying plan the per-agent triggers match the live
  > `.claude/...` path while the change lives under the staged prefix, so
  > `review-workflow-prompt-design`, `-instruction-completeness`, and
  > `-hook-safety` never match and fail to launch (consistency and
  > context-budget always run; writing-style already fires via
  > `docs/adr/**/*.md`). A staged-path normalization rule strips the prefix
  > before the globs run. Detailed description in plan/track-1.md.
  >
  > **Track episode:**
  > Added the staged-path normalization rule so the review-selection globs
  > see a staged workflow edit as its live counterpart. The preamble strips
  > the anchored `docs/adr/<dir>/_workflow/staged-workflow/` prefix from a
  > changed path before the per-agent globs run. It landed in both mirror
  > copies (`review-agent-selection.md §Workflow-machinery override` and
  > `code-review/SKILL.md §5d`) in one commit with the single canonical
  > sync-date bumped 2026-05-15 → 2026-06-01 (S1), plus positive and
  > negative staged-path worked examples in `review-agent-selection.md
  > §Examples`. All edits stage under `_workflow/staged-workflow/` per §1.7;
  > the live tree promotes at Phase 4. No single changed file can match all
  > three glob-gated reviewers at once (the SKILL/agents/prompts globs and
  > the hooks/scripts/settings globs are disjoint), so a worked example
  > shows two reviewers firing, not three; the Validation criterion's
  > "three glob-gated reviewers" is a superset framing of the rule.
  > Track-level review passed at iteration 1 (5 workflow reviewers; 0
  > blockers, 0 should-fix); three suggestions (regex-dot precision, a
  > writing-style clarification, a negative worked example) were applied at
  > completion via Review fix `3c36592`. Independent of Tracks 2 and 3; no
  > cross-track impact.
  >
  > **Track file:** `plan/track-1.md` (2 steps, 0 failed)
  >
  > **Strategy refresh:** CONTINUE — no downstream impact detected. Track 1
  > was independent of the remaining tracks, and its one cross-track
  > discovery (review-target delta-scoping) was already folded into Track 4
  > via the post-Track-1 inline replan.

- [ ] Track 2: Read-side staged-read caveat (YTDB-1038)
  > Every review and gate prompt names the live `.claude/...` path, so on a
  > workflow-modifying plan an agent reads develop's version of a rule the
  > branch already rewrote and reports a phantom mismatch. A marker-gated
  > caveat in all nine prompts routes reads through `§1.7(d)` precedence
  > (which this track first amends to cover review agents, not just the
  > implementer).
  > Detailed description in plan/track-2.md.
  > **Scope:** ~4 steps covering the `§1.7(d)` amendment plus the caveat across nine review/gate prompts.

- [ ] Track 3: Phase A criteria addendum (YTDB-1046)
  > The Phase A technical, risk, and adversarial reviewers apply Java criteria
  > that misfire on a prose track and raise phantom `NOT FOUND` blockers. A
  > marker-gated addendum re-points the criteria to prose; the same three
  > reviewers self-adapt. Detailed description in plan/track-3.md.
  > **Scope:** ~2 steps covering the workflow-machinery criteria addendum in technical/risk/adversarial.
  > **Depends on:** Track 2

- [ ] Track 4: Review-target delta-scoping for staged copies (YTDB-1038)
  > On a workflow-modifying plan a track's deliverable is a staged copy; when
  > that copy is first created in a reviewed commit range, the cumulative diff
  > shows it as a whole-file add with no signal that only the delta against the
  > live counterpart is the real target, so reviewers spend effort on
  > already-promoted content and risk phantom findings or scope creep. The
  > orchestrator pre-stages a `diff <live> <staged>` delta in the Phase C
  > diff-staging step and the high-risk Phase B step-review setup, and the
  > reviewer context block scopes findings to it. Folded under YTDB-1038 (no
  > separate issue). Detailed description in plan/track-4.md.
  > **Scope:** ~2-3 steps covering the delta pre-staging in `track-code-review.md`
  > and `step-implementation.md`, with the scope note in the two context blocks.
  > **Depends on:** Track 2

## Plan Review

- [x] Plan review (consistency + structural) — passed at iteration 2 (re-run after the Track 4 inline replan)

**Auto-fixed (mechanical)**: CR1 — `plan/track-2.md` §Context wrapped a non-verbatim conflation of the live `§1.7(d)` reviewer-exclusion clause in quotation marks; replaced with the verbatim span "reviewers loading a workflow file from the worktree" plus paraphrase.

**Reviewed and rejected (non-issues)**: CR2 — design.md S1 ("mirror … verbatim … date bumped") faithfully paraphrases the authoritative `review-agent-selection.md §Maintenance` text, no inconsistency. CR3 — the seven `prompts/` files' bare-filename rendering is already disambiguated in `track-2.md §Interfaces`, and adding directory prose to the plan would risk the structural bloat gate. The consistency gate confirmed both rejections sound.

**Escalated (design decisions)**: none.

**Structural review**: PASS at iteration 1, zero findings — the Track 4 / D5 additions are structurally sound (D5 DR within budget, S2 extended to "Tracks 2 + 4", DELTA Component-Map node annotated, ordering and dependencies clean) and all bloat budgets hold. The new material matched cleanly across plan ↔ design.md ↔ track files.

## Final Artifacts
- [ ] Phase 4: Final artifacts (`design-final.md`, `adr.md`)
