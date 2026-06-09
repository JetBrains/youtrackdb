<!-- workflow-sha: e9377f7f133f5cd6ec3028936f28be2819e4ae96 -->
# Research Log — Complexity-Adaptive Workflow Tiering

> Anchor (initial user request) plus continuous-log capture of Phase 0
> (research) decisions, discoveries, and open questions. Entries are
> durable across `/clear`, `/compact`, and Phase 0 → Phase 1 handoff.
> Phase 1 (planning) reads this file as the primary input to Decision
> Records and Architecture Notes.

## Initial request
<!-- Written once by `create-plan` Step 2, immediately after the user
provides the aim. Plan-at-start section, not a continuous log; no
timestamp or `[ctx=<level>]` field. Captures the user's framing of
the goal in the user's own words. Phase 1 reads this as the
authoritative aim, replacing the "ask the user for the aim" step that
would otherwise repeat across a session boundary. Format:

**User's words:** <verbatim from the user's first message after the
Step 2 prompt; quoted exactly to preserve the user's framing>
-->

**User's words:** OK you see the idea is that currenlty our workflow is rigid
and bulky and as result overkill and token's cost in case of small and
mid-level of changes, so what I propose is to dynamically cut parts of
workflow depending on issue complexity What I see righ now (that is not final
alghorithm) we can apply design document only for changes with high risk and
high complexity. As for mid complexity we can use only implemetation plan and
tracks, for low complexity we can use only tracks. So how to discern mid and
low complexity. Mid compelxity changes require several tracks, but low
complexity only single one. As for high risk changes those ones those are more
tricky. I think performance optimizations can be those ones, changes related
to different aspects of ACID of transactions. New queiry engine performance
optimziations also go into the category of high complexity so require design
document. The same is related to design of new query component and pipeline,
while for example fix of YQL function unlikely can be treated as complex.If we
remove design document for some changes that means that cold read on Phase 1
should be also applied to initial parts of impelementation plan and tracks,
also we need to implement YTDB-815 and YTDB-814 and move this content to the
track files, while impelmentation plan will become agregator. Each track file
should be treated independent of each other and contain all decision records
it needes not references on them. Implementation plan becomes not independant
part but agregation proxy between tracks.

## Decision Log
<!-- One entry per decision made during research. Format:
- <ISO timestamp> [ctx=<level>] <one-line decision>
  - **Why:** <rationale in one sentence>
  - **Alternatives rejected:** <X (reason); Y (reason)>
-->

- 2026-06-09T05:05Z [ctx=safe] Low-tier mechanics = Route (a): a content-free,
  auto-generated aggregator-stub `implementation-plan.md` exists in every tier;
  the resume state machine is never touched.
  - **Why:** Move 4 already made the plan a thin checklist, so a stub is ~10
    lines; it preserves the startup precheck, drift gate, and Phase 2 routing at
    near-zero token cost while strategic content moves into tracks.
  - **Alternatives rejected:** Route (b) rewire `workflow-startup-precheck.sh`
    to derive state from track-file status when the plan is absent (invasive,
    destabilizes the resume engine, ripples into the drift gate and
    `/execute-tracks`).
- 2026-06-09T05:05Z [ctx=safe] The complexity tier is agent-proposed and
  user-confirmed, not user-declared or fully autonomous.
  - **Why:** balances token savings against the risk of silently skipping a
    needed design on a high-stakes change; the human gate stays on the
    artifact-shedding decision.
  - **Alternatives rejected:** user-declared up front (loses the agent's scope
    assessment); fully autonomous (no human check on the shortcut).
- 2026-06-09T05:05Z [ctx=safe] Tracks are fully self-contained: each track
  carries every Decision Record it needs in full, duplicated when a decision
  spans tracks; no cross-track DR references.
  - **Why:** independence — a reviewer landing cold on one track follows the
    whole argument without fetching another file.
  - **Alternatives rejected:** YTDB-814's 1-DR-per-track + trailing backlink log
    (breaks when a decision spans tracks); cross-references (force the reader
    out of the file, the anti-pattern the epic targets).
- 2026-06-09T05:05Z [ctx=safe] When `design.md` is absent (mid/low tiers),
  cold-read covers only the plan-at-start sections (track Purpose / Context /
  Plan of Work / Interfaces + root per-track BLUF/triad), not the continuous logs.
  - **Why:** matches the user's "initial parts" wording; continuous-log
    cold-read is the 15-20× cost blow-up YTDB-832 explicitly rejected.
  - **Alternatives rejected:** full per-append continuous-log cold-read (token
    blow-up); no cold-read at all (loses the load-bearing-argument audit that
    `design.md` cold-read provided in the high tier).
- 2026-06-09T05:18Z [ctx=safe] Fold the research log (YTDB-965) into THIS branch
  (Option ii); the in-flight `ytdb-965-dd-decision-log` branch is out of date and
  is superseded.
  - **Why:** the classifier shapes what the log must capture, so co-designing
    the log with the tiering is cleaner than depending on a stale branch; the
    log implementation is small (template + `create-plan` wiring + `research.md`
    section), and folding in keeps this branch self-contained.
  - **Alternatives rejected:** (i) depend on `ytdb-965-dd-decision-log` landing
    first (it's out of date, blocks this branch, drags in YTDB-842/975 bundling);
    (iii) graceful-degrade classifier with optional log (weaker evidence base,
    and the log is wanted universally anyway).
  - **Scope note:** this branch now subsumes YTDB-965 (research log) + the new
    complexity-tiering feature (unticketed) + YTDB-814 (inline DRs) + YTDB-815
    (BLUF/triad) + a revived YTDB-832 (cold-read on plan-at-start sections) +
    plan-as-aggregator. The stale branch's frozen design may hold reusable
    research-log prose worth lifting, but the design re-derives here.
- 2026-06-09T05:30Z [ctx=safe] The three tiers factor into two orthogonal gates,
  with friendly tier names **complex / moderate / simple** (distinct from the
  per-step `risk` tag's low/medium/high — no value collision).
  - **Why:** the structural changes (research log, self-contained tracks,
    plan-as-aggregator) apply to every tier, so the tier only controls Gate 1
    (author `design.md`? complex=yes, moderate/simple=no) and Gate 2 (multi-track
    plan with aggregation content vs single-track stub). The 2-gate model also
    cleanly expresses "high-risk but small" = (design=yes, single-track), which
    the flat 3-tier framing left ambiguous.
  - **Alternatives rejected:** flat 3-way classifier with bundled artifact sets
    (can't express design-required-but-small); reusing low/medium/high for the
    tier (collides with the per-step risk tag).
- 2026-06-09T05:30Z [ctx=safe] Design-gate criteria reuse `risk-tagging.md`'s
  HIGH-risk category vocabulary, read at change level from the research log; the
  cold-read for moderate/simple folds into Phase 2 plan review as a new
  dimension; the folded-in research log includes the `## Baseline and
  re-validation` 5th section; Move 3 (YTDB-816, EARS/Gherkin) stays out of scope.
  - **Why:** one source of truth for "high-stakes" (Phase-0 design gate +
    Phase-A per-step tagging); Phase 2 already reads plan+tracks in a fresh
    session, the natural cold-reader stance; this branch is workflow-modifying so
    the baseline/re-validation anchor applies; Move 3 is independent of tiering.
  - **Alternatives rejected:** new high-stakes list (duplicates risk-tagging.md);
    cold-read as a new Step-4b authoring review (Phase 2 is the cleaner home);
    4-section log (loses the rebase-drift anchor on a workflow branch).
- 2026-06-09T05:40Z [ctx=safe] The adversarial review's TARGET moves from
  `design.md` to `research-log.md`. It runs at the Phase 0→1 boundary
  (`create-plan` Step 4) as a gate: loop on blockers, gate on should-fix. Reuses
  `prompts/adversarial-review.md` retargeted to the log's Decision Log +
  Surprises (the load-bearing decisions/findings). `edit-design phase1-creation`
  drops its Step 3.5 adversarial pass; `design.md` then gets cold-read only.
  - **Why:** the adversarial pass challenges *decisions and hidden assumptions*,
    which is exactly what the research log captures explicitly (Decision Log
    `Why`/`Alternatives rejected`, Surprises `Implication`). Attaching it to the
    log makes it UNIVERSAL across tiers — the only artifact present in all three
    — so moderate/simple (no design) gain adversarial coverage they would
    otherwise lose entirely. It also shifts-left: flawed decisions are caught
    before design authoring or track derivation, not after.
  - **Alternatives rejected:** keep adversarial on `design.md` (no coverage for
    moderate/simple tiers); run adversarial on BOTH log and design (double cost,
    redundant since the log is the decision source).
  - **Phase 4 unaffected:** `phase4-creation` already has no adversarial pass.
- 2026-06-09T05:46Z [ctx=safe] The research log is the single Phase-0/1 decision
  ledger (Option A). Load-bearing decisions surfaced during complex-tier design
  authoring are appended to the log's Decision Log and re-trigger the adversarial
  loop on the new entry; `design.md` and tracks render from the vetted ledger.
  - **Why:** keeps one adversarial mechanism and one decision-capture surface;
    design authoring can surface decisions without a heavyweight "reopen
    research" ceremony, yet nothing load-bearing escapes the adversarial gate.
  - **Alternatives rejected:** Option B (design authoring only renders
    research-vetted decisions; a new strategic decision forces a loop back to
    research mode) — stricter Phase 0/1 boundary but adds ceremony for what is
    a routine occurrence during design authoring.
  - **Implication:** the log's lifetime extends through Phase 1 for the complex
    tier (not Phase-0-only as YTDB-965 framed it); it is still consumed (not
    referenced) by design/tracks and removed at Phase 4 cleanup. The
    "consumed not referenced" story holds — only the append window widens.
- 2026-06-09T05:54Z [ctx=safe] Reuse the existing `reviewer-adversarial`
  (`prompts/adversarial-review.md`); add a third scoped section
  `## Research-log-scoped review (Phase 0→1)` following the proven design-scope
  retargeting pattern. No new reviewer.
  - **Why:** the reviewer already works at the decision/assumption level, not
    coupled to design-document shape; the file already demonstrates retargeting
    (its `## Design-scoped review (Phase 1)` section retargets the Phase-3A
    track machinery onto `design.md` with a small delta block); the log's
    Decision Log entries map 1:1 onto the Challenge certificate template
    (`decision / Why / Alternatives rejected` → `Chosen approach / Best rejected
    alternative / Counterargument trace`), better than mining design prose; the
    code-grounding (PSI/file:line) and the workflow-modifying criteria block
    (lines 184-187) transfer unchanged.
  - **Alternatives rejected:** a new dedicated research-log reviewer (duplicates
    the certificate/reasoning/output machinery for zero mechanical gain).
  - **Design work:** add an `### Inputs` block (`research_log_path`) and a
    "what changes" delta — target = Decision Log + Surprises + Open Questions;
    DECISION challenges on Decision Log, ASSUMPTION/INVARIANT on Surprises,
    scope/simplification mostly N/A pre-decomposition; outcome = blocker loops
    (re-decide in research), should-fix gates, no `skip` (raise to blocker).
- 2026-06-09T06:00Z [ctx=safe] Domain-specific review of the research log =
  Option (a): domain-PRIMED adversarial review, not separate dimensional agents.
  The tier classification's matched `risk-tagging.md` categories are passed to
  the single research-log adversarial reviewer as emphasis lenses (e.g.
  "apply WAL-ordering + race scrutiny to the decisions"). Priming is automatic
  from the CONFIRMED tier classification — confirming the tier confirms the
  categories that generate the lenses, so no second prompt; the user may
  drop/add a lens at tier-confirmation time.
  - **Why:** captures most of the domain depth at near-zero added cost, reuses
    the free triage signal (the design-gate already identified the domains),
    and stays inside the one reviewer being extended; the adversarial certificate
    machinery is already code-grounded, so a domain-primed challenge is just a
    sharpened existing certificate.
  - **Alternatives rejected:** (b) spawn the matched dimensional code-reviewers
    on the log (each needs a decision-review mode, heaviest option, undercuts the
    token-savings goal — reserve at most for an explicitly-flagged highest-stakes
    decision); (c) no domain review on the log (loses the shift-left on
    domain-flawed decisions for moderate/simple tiers, which never reach a
    dedicated design review).
  - **Category→lens map:** Concurrency→bugs-concurrency lens; Crash-safety/
    Durability→crash-safety lens; Performance hot path→performance lens;
    Security→security lens; Workflow machinery→the review-workflow-* prose
    lenses; Public API/Architecture→no dedicated lens (adversarial+design cover).
- 2026-06-09T06:16Z [ctx=safe] Tier-gated review opt-outs folded in as the tier
  behavior. The `tier` DRIVES review selection; it does not sit beside the
  existing complexity notions.
  - **Full duplicate removed:** Phase 1 design adversarial → relocated to the log
    (already decided); `design.md` keeps cold-read only.
  - **Partial duplicate narrowed:** Phase 3A adversarial treats a track's inline
    decisions as already log-vetted and focuses on track realization (scope/
    sizing, cross-track-episode reality, invariant violation scenarios). It is
    OPTED OUT for the simple tier (single track = the whole change the log
    already vetted; no cross-track episodes) and SKIPS THE FIRST TRACK for
    moderate (no prior episode yet), running narrowed from track 2 onward.
  - **Simple-tier opt-outs:** Phase 2 structural review (stub plan: one checklist
    entry, no DRs, no ordering — nothing to check) and Phase 3A adversarial.
    Phase 2 consistency lightens to track-vs-code (stub plan has no content to
    cross-check).
  - **Unchanged:** Phase 3B (`risk:high`-gated), Phase 3C (triage-gated) run as
    today in every tier — they review actual code/diff and are not duplicated by
    the pre-code log adversarial. Phase 4 design-final cold-read is absent for
    moderate/simple (no design).
  - **Why:** the simple-tier structural and Phase-3A-adversarial passes become
    near-empty or fully log-duplicative; cutting them is where the token savings
    compound without losing a real check.
  - **Alternatives rejected:** keep all passes in all tiers (defeats the
    slimming aim); remove Phase 3A adversarial entirely (loses track-realization
    challenges for moderate/complex multi-track plans).
  - **Reconciliation DR (for the design):** the `tier` must drive the existing
    Phase-3A step-count review selection and the 3B/3C gating, replacing the
    step-count complexity notion at Phase 3A so two complexity inputs do not
    stack. Per-step `risk` tag stays as the 3B/3C gate; `tier` is the
    change-level driver.
- 2026-06-09T06:40Z [ctx=info] Adversarial-review resolutions (A1/A2/A4 ratified;
  A3 still open).
  - **A1 (blocker) RESOLVED:** the simple-tier plan is "minimal but
    shape-complete" (NOT content-free) — it carries valid `## Plan Review` +
    glyph-valid `## Checklist` track entry + `## Final Artifacts`; a required DR
    specifies the stub template. `create-plan` Step 1c gains a tier-aware branch
    distinguishing "moderate/simple in progress, no `design.md` by design" from
    "fresh start." Route (a) and the tier gates survive; only the framing
    tightens.
  - **A2 (blocker) RESOLVED:** (a) the design's cold-read is gated behind the
    log-adversarial re-run clearing — a design draft cannot reach cold-read while
    a log-adversarial entry is open, preserving the edit-design adversarial→
    cold-read→freeze order; (b) a Phase-4 / `adr.md`-authoring step folds the
    log's resolved adversarial verdicts into `adr.md` for no-design tiers, so the
    audit trail survives the `_workflow/` cleanup at merge.
  - **A4 (should-fix) RESOLVED → A4(i):** the plan-at-start cold-read moves to
    Step 4b WRITE-TIME (when the author still has context), NOT Phase 2.
    **This SUPERSEDES the 2026-06-09T05:30Z decision** ("cold-read folds into
    Phase 2 as a new dimension") — that rationale conflated Phase 2's comparison
    review with a comprehension review, and write-time matches YTDB-832's
    original intent and avoids a costly State-0 re-entry on a blocker.
  - **A5/A6 (should-fix):** folded as required design DRs — A5: enumerate the
    no-design re-routing for every design-bound finding/fix (no `design-final.md`
    target; `structural-review.md:70-75` bloat fixes uninstantiable) + add a
    duplicate-DR consistency check / canonical-copy marker for cross-track DR
    drift; A6: soften "resolves the circularity" to "decides at the cleanest
    point on the best estimate", promote the mid-flight tier-upgrade handler to a
    required DR, add a change-level aggregation rule to the design-gate criteria.
  - **A7/A8 (suggestion) ACCEPTED:** sharpen rationale only — domain priming is
    deliberately lighter-than-specialist (depth lands at 3B/3C on real code); add
    a scope-risk note (large multi-concern branch; watch for a natural split
    between research-log infra and the tiering driver).
  - **A3 (should-fix) RESOLVED → A3(i):** the change-tier is renamed
    **`full` / `lite` / `minimal`** (workflow-ceremony level), avoiding the
    collision with `track-review.md:609-611`'s step-count Simple/Moderate/Complex
    (which stays as-is). **This SUPERSEDES the `complex`/`moderate`/`simple`
    naming from the 2026-06-09T05:30Z decision.** Tier→gate mapping: `full` =
    design + aggregator plan + tracks (Gate 1 design=yes); `lite` = aggregator
    plan + tracks, no design (Gate 1=no, Gate 2 multi-track); `minimal` =
    shape-complete stub plan + one self-contained track, no design (Gate 1=no,
    Gate 2 single-track). Earlier log entries using complex/moderate/simple for
    the TIER refer to full/lite/minimal respectively; the design uses
    full/lite/minimal throughout.
- 2026-06-09T06:55Z [ctx=info] Wrap YTDB-1083 (Show-stopper) into this branch and
  reconcile it with the research-log model.
  - **Why:** YTDB-1083's mechanism (inline decision records per complex-topic
    section + introduce-once-reference-thereafter + rename the per-section footer
    `References`→`Decisions & invariants` + a "decision cited without rationale
    or cross-reference" check) is the DESIGN-side analog of YTDB-814's track-side
    inline DRs. Both express one principle: every artifact is self-contained at
    its tier's canonical carrier (`design.md` for `full`; tracks for
    `lite`/`minimal`), seeded by the research log. Shipping our model without
    1083 would leave a Show-stopper issue whose stated log lifecycle contradicts
    ours.
  - **Reconciliation (lifecycle override CONFIRMED):** YTDB-965's model wins over
    YTDB-1083 point 4. The log is a DURABLE Phase-0/1 ledger READ during Step
    4a/4b and removed at Phase 4 cleanup — NOT a "bridge folded into the design
    and deleted before freeze," and NOT "an artifact the plan does not read."
    1083's inline-record MECHANISM is adopted; its log-as-transient-bridge
    FRAMING is overridden.
  - **Single-source-of-truth (self-identified adversarial challenge + resolution):**
    decisions then exist in both the log AND the carrier's inline records — risks
    a dual source. Resolution: the log→carrier seed is ONE-WAY. After the carrier
    (design/tracks) absorbs a decision as an inline record, the CARRIER is
    authoritative; the log is historical (a cross-check the Phase-2 consistency
    review may read per YTDB-965, never an authority). This keeps 1083's
    "introduce once" intact at the carrier and avoids log-vs-carrier drift.
  - **Scope:** accepted as a larger branch; adds `design-document-rules.md`,
    `house-style.md`, `design-mechanical-checks.py` (footer rename) to the
    surface. Sharpens A8 — the planner weighs a natural split at decomposition
    (research-log infra ∣ tiering driver ∣ self-containment incl. 814/815/1083).
  - **Gate note:** these are NEW decisions; the real Step-4a log-adversarial gate
    run (the genuine Phase 0→1 boundary, still ahead) challenges the full ledger
    incl. this reconciliation before the design freezes. The 06:30Z run was the
    dogfood preview.
  - **Alternatives rejected:** honor YTDB-1083's bridge framing (log deleted
    before freeze, not read by plan) — contradicts YTDB-965's durable-ledger
    spec we folded in, and loses the Phase-2 consistency cross-check; defer
    YTDB-1083 to its own branch (leaves a Show-stopper contradicting our shipped
    log model).
- 2026-06-09T07:10Z [ctx=info] Adversarial gate ITERATION 2 (loop on blockers):
  A1-A8 all VERIFIED (resolutions held, zero regressions), but the
  resolution-stage decisions introduced new findings. GATE: NEEDS REVISION —
  2 open blockers (A9, A12) + 2 should-fix (A10, A11). The loop continues.
  - **A9 [BLOCKER]:** A4(i) mis-frames a NEW mechanism as a "relocation." Step 4b
    has NO review pass today (the only authoring-time review is edit-design's
    adversarial+cold-read in Step 4a, design-only; the plan's next review is the
    autonomous Phase 2). "Move cold-read to Step 4b write-time" = ADD a new
    `/create-plan` review mechanism — the exact cost the Phase-2 fork avoided.
    Same understatement pattern as A1.
  - **A10 [should-fix]:** "carrier authoritative, log historical" is asserted, not
    enforced — a Step-4b author could seed a decision from the log that was never
    absorbed into the carrier, so the carrier silently fails self-containment yet
    all gates pass.
  - **A11 [should-fix]:** the lifecycle override leaves YTDB-1083 acceptance #4
    ("Step 4b derives from `design.md` ALONE") stale — if the log is readable at
    Step 4b, "from the design alone" is contradicted; ambiguity on whether Step 4b
    seeds from the carrier's inline records OR the log.
  - **A12 [BLOCKER]:** for lite/minimal, 1083's mechanism has no track-side home —
    all three named files are `design.md`-side; track files have no `### References`
    footer (they have `## Decision Log` + the Move-1 slot), so the footer rename
    and `section_has_references` check cannot apply to tracks. The no-design
    tiers' carrier mechanism is unspecified.
  - A10+A11+A12-enforcement share ONE root: the log↔carrier self-containment
    question. Proposed convergent resolution (pending user): the log is NOT a
    Step-4b seeding input (Step 4b seeds from the carrier's inline records only;
    log read-scope = Step 4a authoring + Phase-2 consistency cross-check); add an
    absorption-completeness check (every load-bearing log decision in a track's
    scope must appear as an inline record in that track's `## Decision Log`) — the
    no-design analogue of 1083's design-side "decision cited without rationale"
    check — making "carrier authoritative" enforceable; rewrite 1083 acceptance #4
    accordingly; scope the footer-rename + mechanical check to `design.md` only
    (full tier), track-side inline records live in `## Decision Log`.
  - A9 resolution is a USER FORK (keep A4(i) as a new, fully-specified Step-4b
    cold-read mechanism vs revert to A4(ii) Phase-2 now that the new-mechanism cost
    is explicit).
- 2026-06-09T07:25Z [ctx=info] Iteration-2 resolutions (A9→A4(i), A10, A11, A12
  confirmed).
  - **A9 RESOLVED → A4(i):** the Step-4b cold-read is a NEW spawn point in
    Step 4b that REUSES the existing cold-read sub-agent (write-time cold-read
    applied to a second artifact, not a new reviewer). Structure: `full` tier
    runs the design cold-read in Step 4a and the plan/tracks cold-read separately
    in Step 4b (two write-time cold-reads across the session boundary);
    `lite`/`minimal` have no Step 4a, so the plan/tracks cold-read runs in the
    single Phase-1 session and is their sole comprehension audit. The DR must
    specify the Step-4b spawn point (after track-file write, before Step 5
    commit), iterate-loop/blocker semantics (a blocker re-opens Step-4b
    derivation in the same session, mirroring edit-design's loop), and tier scope.
  - **A10 RESOLVED:** absorption-completeness is a CRITERION of the Step-4b
    cold-read (A4(i)), NOT a standalone mechanism. The cold-reader reviews the
    records in their plan-at-start (initial) state and cross-checks both ways:
    every load-bearing research-log decision must appear as an inline record in
    the appropriate carrier; a log decision with no carrier home is a finding.
    Semantic (LLM review, not `design-mechanical-checks.py`); per-carrier-in-scope
    (a cross-track decision must appear in each relevant carrier per DL-3's
    full-duplication rule). This makes "carrier authoritative" enforceable.
    [A17 tightening, per the 08:00Z A13 narrowing: "appear in each relevant
    carrier" means a full record only in the no-design tiers, where tracks are
    canonical; in `full` it is satisfied by the resolvable `**Full design**`
    pointer in the track plus the canonical record in `design.md`.]
  - **A11 RESOLVED:** the log is NOT a Step-4b seeding input. Step 4b seeds DRs
    from the carrier's inline records ONLY; log read-scope = Step 4a authoring +
    Phase-2 consistency cross-check. Rewrite YTDB-1083 acceptance #4 to this
    965-compatible form ("seeds from the frozen carrier's inline records; the
    durable log is a Phase-2 consistency cross-check, never a Step-4b seeding
    input").
  - **A12 RESOLVED:** split 1083's two bundled mechanisms. (1) Inline-record
    CONTENT adopts for tracks via the existing `## Decision Log` Move-1 slot —
    that section IS the canonical inline-record home for no-design tiers (no
    footer). (2) The footer rename (`References`→`Decisions & invariants`) +
    `section_has_references` mechanical check are scoped to `design.md` ONLY
    (full tier). The no-design tiers' self-containment guarantee comes from the
    A10 cold-read absorption-completeness criterion, not from the mechanical
    check.
  - **Full-tier two-level carrier (sub-point):** in `full`, both `design.md`
    (1083 canonical records) and tracks (814 inline DRs) exist, so the cold-read
    verifies each log decision is absorbed into `design.md` AND surfaced in each
    relevant track's inline DR. `lite`/`minimal` have one level (tracks only).
- 2026-06-09T07:45Z [ctx=info] Adversarial gate ITERATION 3: A9-A12 all VERIFIED.
  New: A13 [BLOCKER] + A14/A15/A16 [should-fix]. GATE: NEEDS REVISION — 1 open
  blocker (A13). Loop converging (2→1 blocker).
  - **A13 [BLOCKER]:** the full-tier two-level carrier papers over a live
    DL-3-vs-1083 contradiction. DL-3 (05:05Z) mandates tracks carry FULL DRs
    duplicated, NO out-of-file refs (universal as written); YTDB-1083 +
    `create-plan/SKILL.md:349-353` mandate the full-tier track DR be a gist +
    `**Full design**: design.md §…` pointer (introduce-once, out-of-file). The
    full-tier track-DR shape is unspecified, so A10's just-verified absorption
    criterion is ungrounded for `full`.
    - **Proposed RESOLUTION:** make self-containment TIER-RELATIVE. `full` tier:
      `design.md` is canonical (1083 introduce-once); the full-tier track DR is a
      gist + `**Full design**` pointer. DL-3's full-duplication / no-out-of-file-
      refs rule is SCOPED to the no-design tiers (`lite`/`minimal`), where tracks
      ARE canonical and self-containment requires the full duplicate. Aligns with
      06:55Z "self-contained at its tier's canonical carrier." **Narrows DL-3's
      scope (was universal) — USER RATIFY.**
  - **A14 [should-fix]:** "load-bearing" + "track's scope" undefined → cold-read
    trigger-set is a free judgment. RESOLUTION: load-bearing = Decision Log entry
    with a non-empty `**Alternatives rejected:**` field (a real fork); in-scope =
    decision constrains a file/interface the track touches (bind to the track's
    `## Interfaces and Dependencies`).
  - **A15 [should-fix]:** no-design tiers + full-tier tracks get ONLY the semantic
    cold-read for self-containment (no mechanical backstop; `section_has_references`
    is `design.md`-only). The cheapest tiers have the thinnest net. RESOLUTION
    (minor fork): either (i) accept cold-read-only with an explicit residual-risk
    statement (consistent with the tier philosophy; A14 makes the cold-read
    checkable), or (ii) add a lightweight mechanical presence check (every log
    Decision Log entry referenced in ≥1 track's `## Decision Log`).
  - **A16 [should-fix]:** A11 keeps the log readable at Step-4a authoring but
    attaches absorption-completeness only to the Step-4b cold-read — a full-tier
    design author could absorb a log rationale into prose without a `design.md`
    D-record. RESOLUTION: extend the absorption-completeness criterion to the
    Step-4a design cold-read too (log → `design.md` D-records), so every carrier
    that may read the log meets the same bar.
- 2026-06-09T08:00Z [ctx=info] Iteration-3 resolutions (A13/A14/A16 ratified, A15→(i)).
  - **A13 RESOLVED — tier-relative self-containment:** "self-contained at its
    tier's canonical carrier." `full` tier: `design.md` is canonical (1083
    introduce-once), the full-tier track DR is a gist + `**Full design**:
    design.md §…` pointer. `lite`/`minimal`: tracks are canonical, so they carry
    the FULL inline DR (DL-3 full duplication, no out-of-file refs). **DL-3
    (05:05Z) narrows from universal to the no-design tiers only** — ratified.
    A10's absorption check is now grounded per tier: `full` → log decision in
    `design.md` + a resolvable pointer in each relevant track; `lite`/`minimal`
    → full record in each relevant track's `## Decision Log`.
  - **A14 RESOLVED:** load-bearing = a research-log Decision Log entry with a
    non-empty `**Alternatives rejected:**` field; in-scope = the decision
    constrains a file/interface the track touches (bound to the track's
    `## Interfaces and Dependencies`). Gives the cold-read a checkable trigger.
    [A18 tightening: on a workflow-modifying plan, "a file/interface the track
    touches" includes the workflow-prose files and `§`-anchors in the track's
    `## Interfaces and Dependencies` per the `§1.7(b)` workflow-machinery lens —
    the trigger binds to prose dependencies, not only Java symbols.]
  - **A15 RESOLVED → (i):** no-design tiers + full-tier tracks rely on the
    semantic Step-4b cold-read for self-containment, with NO mechanical backstop;
    the design DR states this residual risk explicitly (a missed absorption in a
    `lite`/`minimal` plan has no automated catch — accepted, consistent with the
    cheaper-tier-lighter-guarantee philosophy; A14 makes the cold-read checkable).
  - **A16 RESOLVED:** the absorption-completeness criterion applies at BOTH the
    Step-4a design cold-read (log → `design.md` D-records) and the Step-4b track
    cold-read (carrier inline records), so every carrier that may read the log
    meets the same self-containment bar.
- 2026-06-09T08:10Z [ctx=info] Adversarial gate ITERATION 4: **GATE: PASS.**
  A13/A14/A15/A16 all VERIFIED, zero open blockers — the loop closes. Two new
  should-fix (A17, A18) are prose-reconciliation tightenings that do not reopen
  any decision; both applied inline (A17 → the 07:25Z A10 entry; A18 → the
  08:00Z A14 entry). The research-log adversarial gate is now PASSED: the
  decision ledger is internally consistent and vetted across 4 iterations
  (8 initial findings → 4 → 4 → 2, all resolved or accepted). Dogfood verdict:
  the loop-on-blockers + gate-on-should-fix behaviour works as designed — each
  iteration's fixes were re-challenged, and resolution-stage decisions that
  introduced new blockers (A9, A12, A13) were caught before the design froze.
## Surprises & Discoveries
<!-- Code-research and external-research findings that shape the plan.
Format:
- <ISO timestamp> [ctx=<level>] <one-line finding>
  - **Source:** <PSI find-usages of Foo#bar | paper title | library docs URL>
  - **Implication:** <how this affects the plan>
-->

- 2026-06-09T04:54Z [ctx=safe] Epic YTDB-813 ("dual readability") spawned four
  Moves; the track-file template already carries `<!-- Reserved for Move 1/2/3 -->`
  placeholders, so the self-contained-track infrastructure is pre-staged.
  - **Source:** YouTrack YTDB-813 + subtask search; `create-plan/SKILL.md` track template.
  - **Implication:** Move 1 (YTDB-814, inline decisions) and Move 2 (YTDB-815,
    BLUF+triad) drop into existing slots. Move 4 (YTDB-817, split into
    `plan/track-N.md` with thin root index) is **already FIXED** — the root
    plan is already a thin checklist, so "plan becomes aggregator" is partly
    in place. Move 3 (YTDB-816, EARS/Gherkin acceptance) still Submitted.
- 2026-06-09T04:54Z [ctx=safe] YTDB-832 ("extend cold-read to root plan + per-track
  files") was marked **Won't fix** on 2026-05-21, no comment.
  - **Source:** YouTrack YTDB-832 (full spec: per-track Purpose/Context/Plan of
    Work at write time + root per-track BLUF/triad, diff-only batched log review,
    ~3-5× token budget).
  - **Implication:** Likely won't-fixed because the design-first split made
    `design.md` mandatory on every branch, so cold-read on `design.md` covered
    the load-bearing argument. Removing `design.md` for mid/low tiers
    **re-activates this exact motivation** — its "initial parts" scope (the
    plan-at-start sections) is what the user wants cold-read to cover. The
    spec is reusable nearly verbatim for the no-design tiers.
- 2026-06-09T04:58Z [ctx=safe] The single hard blocker for the low/mid tiers is
  the state machine in `.claude/scripts/workflow-startup-precheck.sh`, not the
  prose docs.
  - **Source:** Explore sweep of `.claude/{workflow,skills,agents,scripts}`.
    `workflow-startup-precheck.sh:1452` does `[ ! -f "$plan_file" ]` → returns
    `{phase:0, substate:null}`; lines 1456/1476-1528/1553 read `## Plan Review`,
    the `## Checklist` track walk, and `## Final Artifacts` to derive every
    post-Phase-0 state. Drift Skip #2, Phase 2 consistency/structural review,
    and `/execute-tracks` track enumeration also read the plan.
  - **Implication:** "low = tracks only, no plan" cannot be reached by simply
    not writing the file — the resume state machine has no tracks-only path and
    collapses to phase 0. Two routes: (a) keep a near-empty auto-generated
    aggregator-stub plan in every tier so the state machine, drift gate, and
    Phase 2 routing stay intact (cheap — Move 4 already made the plan a thin
    checklist), and push all strategic content into tracks; or (b) rewire the
    state machine to derive state from track-file presence/status when the plan
    is absent (invasive). Route (a) matches the user's "plan = aggregation proxy"
    framing and is far lower-risk.
- 2026-06-09T04:58Z [ctx=safe] `design.md` hard-deps are softer than the plan's:
  Phase 2 consistency review reads it, structural-review duplication check (DR
  >50 lines vs matching `design.md` section) reads it, and the Phase-2/Phase-4
  design-frozen findings-routing rule assumes it exists.
  - **Source:** `implementation-review.md` (Consistency Review reads design+plan+code;
    design-frozen defer-to-Phase-4 rule), `structural-review.md` (duplication rule).
  - **Implication:** Mid/low tiers (no design) need Phase 2 to skip the
    design-half of consistency review and the duplication check, and the
    design-frozen findings-routing collapses to "all corrections are
    plan/track-scoped" (no defer-to-Phase-4-design). Tractable — conditional
    branches keyed on design presence, not a rewrite.
- 2026-06-09T05:12Z [ctx=safe] The research log (YTDB-965, Priority Critical) is
  the natural complexity-classifier input, but it has NOT landed on develop.
  - **Source:** YouTrack YTDB-965 (template is verbatim what the user seeded
    this session); `grep` finds zero research-log references in the live
    workflow; `git branch` shows in-flight `ytdb-965-dd-decision-log` (frozen
    design, Phase 1b pending per session memory, bundles YTDB-842 + YTDB-975).
    YTDB-965 `is required for` YTDB-975 (design-first). No complexity-tiering
    issue exists yet — this feature is unticketed. YTDB-1083 ("Step 4b loses the
    research conversation") is solved for free by the research log.
  - **Implication:** Making the tier classifier read the research log creates a
    hard dependency on YTDB-965. Resolution forks: (i) land YTDB-965's branch
    first, rebase on top; (ii) fold YTDB-965 into this branch (it's small:
    template + create-plan wiring + research.md doc); (iii) classifier reads the
    log if present, else falls back to conversation-context + user confirm
    (graceful degrade, no hard dep). Choice hinges on the fate of
    `ytdb-965-dd-decision-log`.
- 2026-06-09T05:12Z [ctx=safe] The research log resolves the tier-timing
  circularity: the classifier runs at the Phase 0 → Phase 1 boundary
  (`create-plan` Step 4), reading the now-rich log, proposing a tier, user
  confirms, THEN only the tier's artifacts are authored.
  - **Source:** YTDB-965 Step-4 transition spec (Phase 1 reads the log
    end-to-end as the planning input); the "consumed not referenced" seeding
    model the user stated.
  - **Implication:** Tier is decided once, at end of Phase 0, before any Phase 1
    artifact exists — no chicken-and-egg with track count, and the design-skip
    gate has a clean home (Step 4, before Step 4a design authoring).
- 2026-06-09T05:24Z [ctx=safe] The design-gate's high-tier criteria already exist
  as `risk-tagging.md`'s HIGH-risk category vocabulary; the user's list maps
  nearly 1:1.
  - **Source:** `risk-tagging.md` HIGH-risk triggers (Concurrency, Crash-safety/
    Durability, Public API, Security, Architecture/cross-component, Performance
    hot path, Workflow machinery). User's list: perf→hot path; ACID→durability+
    concurrency; query-engine perf→hot path+architecture; new query pipeline→
    architecture.
  - **Implication:** The design gate can reuse this category vocabulary at
    CHANGE level (read at Phase 0→1 from the research log: "is this change
    fundamentally about one of these categories?"), distinct from the per-step
    assignment those triggers drive at Phase A. One source of truth for "what's
    high-stakes," two consumers. Note: "Workflow machinery" is itself a HIGH
    category, so THIS branch is high-tier and requires its own `design.md` —
    internally consistent. Caveat: change-level ≠ "contains one high-risk step";
    a mostly-trivial change with a single risky step need not be high-tier.
- 2026-06-09T06:10Z [ctx=safe] Full review-surface inventory (per Explore sweep;
  Phase-3A step-count gating detail to be verified in design). The workflow
  already gates reviews heavily.
  - **Source:** Explore of `.claude/workflow/{track-review,implementation-review,
    structural-review}.md`, `prompts/*`, `agents/*`, `review-agent-selection.md`.
  - **Inventory:** Phase 1 = adversarial (phase1-creation only) + cold-read on
    design. Phase 2 = consistency + structural (both always, reviewer-plan).
    Phase 3A = technical (always) + risk + adversarial (reportedly already gated
    by track step-count: simple → technical only). Phase 3B = dimensional, gated
    to `risk:high` steps only. Phase 3C = baseline-4 + triage-selected
    conditional + workflow-* (consistency/context-budget always when workflow
    present, rest file-pattern-gated); single high-step track skips 3C. Phase 4
    = cold-read on design-final only.
  - **Implication:** existing gating precedents (risk:high for 3B, triage for
    3C, workflow-only baseline-skip, single-step-track 3C skip, possibly
    step-count for 3A) give the tiering plenty to lean on. But the new
    change-`tier` is a FOURTH complexity notion alongside per-step `risk` tag,
    Phase-3A step-count selection, and 3C triage — it must DRIVE these, not
    collide. Reconciling tier vs the existing Phase-3A step-count review
    selection is a design DR.
## Open Questions
<!-- Items flagged during research but not yet resolved. Carry into
Phase 1 as Decision Records to write or as Architecture Notes to fill.
Format:
- <ISO timestamp> [ctx=<level>] <one-line question>
  - **Blocking:** <what plan element this blocks>
-->

- 2026-06-09T04:54Z [ctx=safe] Tier-vs-track-count circularity: "mid = several
  tracks, low = single track" but track count is a *planning output*, not a
  pre-planning input. What does the classifier key on before decomposition?
  - **Blocking:** The complexity-classification algorithm (the core DR).
- 2026-06-09T04:54Z [ctx=safe] What reads `implementation-plan.md` as a hard
  dependency? Low tier removes it — the startup precheck script, drift gate
  (Skip #2), `/execute-tracks` startup, and Phase 2 plan review all read it.
  - **Blocking:** Feasibility of the "low = tracks only, no plan" tier.
- 2026-06-09T04:54Z [ctx=safe] Where/when is the tier decided, and who can
  override it (Phase 0 classifier? Phase 1? user-declared?).
  - **Blocking:** Workflow entry point and the design-skip gate.
  - **RESOLVED 2026-06-09T05:30Z:** agent-proposed at the Phase 0→1 boundary
    (`create-plan` Step 4), reading the research log; user-confirmed; override in
    either direction allowed.
- 2026-06-09T05:30Z [ctx=safe] Exact contents of the simple-tier stub plan — it
  must still carry the sections the state machine reads (`## Checklist` with one
  track, `## Plan Review`, `## Final Artifacts`); is the `## Design Document`
  pointer omitted for moderate/simple?
  - **Blocking:** The stub-plan auto-generation template (a DR in the design).
- 2026-06-09T05:30Z [ctx=safe] Do moderate/simple tiers collapse Phase 1 into a
  single session (no design = no 4a/4b session boundary), and how does Step 1c
  auto-resume branch on tier when `design.md` never exists?
  - **Blocking:** The `create-plan` Step 4 control-flow rewrite and Step 1c
    resume routing.
- 2026-06-09T05:30Z [ctx=safe] Can the tier be re-evaluated mid-flight (e.g.
  simple→moderate when a single track balloons during Phase A/B)? Does the
  existing inline-replan ESCALATE path need a tier-upgrade handler?
  - **Blocking:** Mid-execution tier-upgrade semantics (a DR in the design).
### Adversarial review of this log (2026-06-09T06:30Z, dogfood) — NEEDS REVISION: 2 blockers, 4 should-fix, 2 suggestions

- 2026-06-09T06:30Z [ctx=info] A1 [BLOCKER]: stub plan is NOT "content-free" /
  state machine NOT "never touched", and Step 1c has no tier-aware branch.
  - **Blocking:** Stub-template DR must specify the shape-complete minimum the
    machinery reads (`## Plan Review` + glyph-valid `## Checklist` track line +
    `## Final Artifacts`; precheck `:1452-1560`, drift Skip #2). And `create-plan`
    Step 1c must distinguish "moderate/simple tier in progress, no `design.md` by
    design" from "fresh start" — else a no-design tier on `/clear` mid-planning
    routes to Step 4a design authoring (the artifact the tier skips).
- 2026-06-09T06:30Z [ctx=info] A2 [BLOCKER]: (a) re-triggering log-adversarial
  during design authoring inverts the edit-design freeze ordering; (b) for
  moderate/simple the adversarial evidence base lives only in the log, which
  Phase-4 cleanup deletes, and no `design-final.md` survives → trail vanishes.
  - **Blocking:** (a) need an ordering rule — the design's cold-read is gated
    behind the log-adversarial re-run clearing (no cold-read while a log
    adversarial is open). (b) need a Phase-4 / `adr.md`-authoring step that folds
    resolved adversarial verdicts into `adr.md` for no-design tiers.
- 2026-06-09T06:30Z [ctx=info] A3 [should-fix]: tier names complex/moderate/simple
  COLLIDE with `track-review.md:609-611`, which already uses Simple/Moderate/
  Complex as the Phase-3A step-count vocabulary — the table the Reconciliation
  DR says `tier` must replace.
  - **Blocking:** rename the change-tier (e.g. `design-tier: full/lite/minimal`)
    OR rename the step-count axis, and reconcile the `track-review.md` table. USER FORK.
- 2026-06-09T06:30Z [ctx=info] A4 [should-fix]: "cold-read folds into Phase 2 as
  the natural cold-reader stance" conflates fresh-session reading with the
  cold-read review TYPE (Phase 2 consistency is a comparison review), and
  YTDB-832 wanted write-time (Phase 1) cold-read.
  - **Blocking:** either declare Phase 2 cold-read a NEW review type (accept the
    later, separate-session timing with rationale) OR move it to Step 4b
    write-time (the originally-rejected alternative, stronger but adds a Phase-1
    pass). USER FORK.
- 2026-06-09T06:30Z [ctx=info] A5 [should-fix]: no-design tiers have nowhere to
  defer design-bound findings (no `design-final.md`); `structural-review.md:70-75`
  bloat fixes ("move to `design.md`") uninstantiable; full-DR duplication (DL-3)
  has no drift detector once `design.md` is gone.
  - **Blocking:** enumerate the re-routing for every design-bound finding/fix in
    no-design tiers; add a duplicate-DR consistency check or canonical-copy marker.
- 2026-06-09T06:30Z [ctx=info] A6 [should-fix]: "resolves the circularity"
  overclaims (Gate 2 stays an estimate); mid-flight tier-upgrade unresolved yet
  simple sheds reviews irreversibly in a prior session; change-level reuse of
  per-step risk categories lacks an aggregation rule.
  - **Blocking:** soften the claim; promote the mid-flight upgrade handler to a
    required DR; add a change-level aggregation rule to the design-gate criteria.
- 2026-06-09T06:30Z [ctx=info] A7/A8 [suggestion]: domain-primed reviewer SURVIVES
  (sharpen rationale: priming deliberately lighter-than-specialist, depth lands at
  3B/3C); fold-in scope breadth SURVIVES (add a scope-risk note; watch for a
  natural split between research-log infra and the tiering driver).
  - **Blocking:** rationale polish only; no fork.

- 2026-06-09T05:40Z [ctx=safe] Coverage gap from moving adversarial off
  `design.md`: load-bearing decisions surfaced DURING complex-tier design
  authoring (new mechanisms not in the research log) would escape the
  adversarial gate, since the design then gets cold-read only.
  - **Blocking:** Whether the research log absorbs Phase-1 design-authoring
    decisions (re-triggering the adversarial loop on the new entry, making the
    log the single Phase-0/1 decision ledger), or design authoring is
    disciplined to only render research-vetted decisions and a genuinely new
    strategic decision loops back to research. Proposed resolution: the former.
  - **RESOLVED 2026-06-09T05:46Z:** Option A. The research log is the single
    Phase-0/1 decision ledger; design-authoring decisions are appended to it and
    re-trigger the adversarial loop. See Decision Log entry below.
