<!-- workflow-sha: eb984cba63bd557fb3c2b32156d85bf1a72e82b4 -->
# Track 4: Orchestrator tactical routing, severity backstop, and per-dimension addressing

## Purpose / Big Picture
After this track, a tactical code-review fan-out (Phase B `risk:high` step
review, Phase C track review, gate-checks) keeps every finding body off the
orchestrator: it buckets on the manifest index alone, hands the in-scope anchors
to the per-iteration implementer, and never reads a tactical body.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

This is the routing track — the one that realizes the design's primary win.
It implements the router model (D1), drops the synthesis `M<n>` minting and the
`M<n>`-to-dimension un-map in favor of per-dimension IDs as the sole addressing
(D5, orchestrator side), and adds the upgrade-only `basis` severity backstop
(D4). It is the last track because it consumes what Tracks 2 and 3 produce: the
manifest schema and the reviewer-assigned IDs. At ~8 in-scope files it sits below
the ~12 merge floor, but it is not folded into a neighbor: it is terminal (no
forward track to merge into), and back-folding into Track 3 would mix this track's
orchestrator- and implementer-side tactical routing with Track 3's reviewer-side
agent-definition edits across two different staging prefixes
(`.claude/workflow/**` here vs `.claude/agents/**` there).

## Progress
- [x] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review
- [ ] Track completion
- [x] 2026-06-08T07:50Z [ctx=info] Review + decomposition complete (3 steps: 2 high, 1 medium; 0 failed)

## Surprises & Discoveries
<!-- Continuous-log. Empty at Phase 1. -->

## Decision Log
<!-- Continuous-log. -->

<!-- Reserved for Move 1 — per-track inlined Decision Records. -->

## Outcomes & Retrospective
<!-- Continuous-log. -->

- [x] Technical: PASS at iteration 2 (4 findings, 4 accepted). T1 reattributed the
  `BC3`-override edit to `finding-synthesis-recipe.md §Step 3` (not `review-mode.md`);
  T2 restated the gate-check prompt + `code-review-protocol.md` as verify-only
  (Track 3 already made the gate-check prompt per-dimension); T3 named the recipe's
  five `M<n>` coupling sites; T4 made `what_was_skipped` verify-not-rewrite.
- [x] Risk: PASS at iteration 2 (5 findings, 5 accepted). R1 pinned where the
  recipe's surviving bucketing / in-scope-classification / pre-spawn-budget
  functions land (vs the duplicate inline budget check in `track-code-review.md`);
  R2 extended the gate-check + verdict-map `M<n>` reconciliation; R3 keyed S3 off
  the gate-check REGRESSION verdict; R4 named the gate-check re-fan-out as the
  silently-skipped-finding backstop; R5 added the `cert: n/a` drill-down fallback.
- [x] Adversarial: PASS at iteration 2 (3 findings, 3 accepted). A1 required the
  §2.5 S4/S6 count-validation + `CONTRACT_VIOLATION`-to-implementer fallback in
  step 1 (S1 depends on it); A2 guarded the `level=track` four-outcome RESULT enum
  against narrowing; A3 noted the highest-frequency D4 blind spot.
- Phase 4 flag (from A2): `design-final.md` must reconcile the design body's
  two-outcome "`SUCCESS` or `DESIGN_DECISION_NEEDED`" phrasing with the
  implementer's actual four-outcome `level=track` RESULT contract (happy-path
  narrative vs the full enum).

## Context and Orientation

Today the synthesis recipe (`finding-synthesis-recipe.md`) has the orchestrator
read every finding, dedup in-context, re-judge severity (the OVERRIDE step),
bucket, mint merged `M<n>` IDs, and hand a merged list to the implementer; it
also maps each `M<n>` back to contributing dimensions to compose the
gate-check's `{findings_under_recheck}`, recording contributing dimensions in an
audit trail. That read loop is the orchestrator's dominant context filler.

This track replaces that with manifest-only routing for the tactical class:

- **Bucket on the index alone.** Collapse duplicate `loc` across dimensions
  (non-destructively — every row stays individually addressable so a Review-mode
  override naming `BC3` matches the manifest `id` directly), drop out-of-track
  findings to plan corrections by `loc`, keep blockers and in-scope should-fixes.
  REGRESSION-flagged rows are excluded from `loc`-collapse so a regression's
  `revert-or-repair` guidance reaches the implementer unmerged (S3).
- **Spawn the implementer with file paths + in-scope anchors.** The implementer
  reads bodies by anchor, reconciles cross-dimension framings at the code level
  (same concern at one `loc` → one edit; distinct concerns at one `loc` → separate
  edits), applies fixes, runs test + Spotless + coverage, and returns `SUCCESS`
  or `DESIGN_DECISION_NEEDED`. No orchestrator-side dedup pass.
- **Per-dimension IDs are the sole addressing (D5).** Remove the `M<n>` minting
  and the `M<n>`-to-dimension un-map; the orchestrator passes each dimension's
  prior high-water-mark to the reviewer at spawn (reusing the gate-check
  hand-back, applied at initial review too). Per-dimension gate-check is more
  precise: each dimension verifies its own concern against the one code-level fix.
- **Upgrade-only severity backstop (D4).** Drop the lenient-direction OVERRIDE;
  keep a manifest-only scan for any `suggestion`/`should-fix` whose `basis`
  describes a correctness/crash/CI-hang/data-loss impact and upgrade it. Never
  second-guess a `blocker`. Severity at a shared `loc` is routed per finding, not
  per merged row.

Tactical vs. strategic split keys on the consumer, not the phase name: a Phase
B/C code gate-check is tactical (the implementer re-fixes); a Phase 2/3A plan
gate-verification is strategic (Track 2 — the orchestrator applies plan fixes
itself). The one place the orchestrator touches a tactical body is a
contested-finding drill-down: it pulls one `cert`/evidence block by anchor and
drops it after the decision (transient, never retained across the next teardown,
the bounded exception in S1). When the contested finding comes from one of the 6
evidence-trail-exempt dimensions (`cert: n/a`, no `## Evidence base` entry), the
orchestrator pulls the single `### <ID>` finding body by anchor instead, still
one transient block, so S1's bound holds (R5).

## Plan of Work

1. **Router model (D1).** Rewrite the tactical-review orchestration in
   `step-implementation.md` (Phase B `risk:high` step review) and
   `track-code-review.md` (Phase C track review) so the orchestrator buckets on
   the manifest index and spawns the implementer with file paths + in-scope
   anchors. Inject the per-spawn output path at these dispatch sites (the switch
   that turns on Track 3's file output), not in `review-agent-selection.md`.
   The rewrite MUST run the §2.5 S4/S6 count-validation grep before bucketing
   and, on a `CONTRACT_VIOLATION`, route the whole-section fallback to the
   implementer rather than reading the body. Omitting that branch breaks S1 the
   first time a manifest is malformed (A1).
2. **Per-dimension addressing (D5, orchestrator side).** Treat
   `finding-synthesis-recipe.md` as a near-complete rewrite, not a targeted
   delete. The `M<n>`/merged-row coupling sits in five places: Step 1
   body-reading dedup, the Step 2 severity OVERRIDE (replaced by step 3's `basis`
   scan), the Step 3 Review-mode bucketing walk, the Step 5 output format plus
   cumulative `M<n>` numbering, and the §Gate-check synthesis verdict map that
   forwards `STILL OPEN` with "the original `M<n>` ID" (T3). Remove the minting,
   the un-map, and the contributing-dimensions audit trail; collapse to
   manifest-only routing keyed on `loc`/`sev`/`basis`. State where the recipe's
   surviving functions land: the `loc`-collapse bucketing, the in-scope
   classification, and the pre-spawn budget guard (the `~15`-finding inflation cap
   that `review-iteration.md §Gate-check synthesis routing` cites) move into the
   step-1 orchestration prose or a slimmed recipe, reconciled against the
   duplicate inline budget check already in `track-code-review.md` so neither home
   is orphaned (R1). The `BC3`-style Review-mode override resolves today by
   walking the recipe's Step 3 contributing-dimensions list; rewrite that walk so
   the override matches the manifest `id` directly (S2). This recipe site is the
   load-bearing one for the `BC3` match, not `review-mode.md` (T1). Reconcile
   `review-iteration.md`'s `### Gate-check synthesis routing` reference and its
   `§Verdict handling` `REJECTED-VERDICT` audit-trail pointer with the removed
   merge layer (the `§Finding ID prefixes` table is already per-dimension, so no
   finding-ID format converts there). Pass the per-dimension high-water-mark to
   the reviewer at spawn.
3. **Severity backstop (D4).** Replace the body-dependent OVERRIDE with the
   manifest-only upgrade scan over the `basis` field; never downgrade, never
   second-guess a `blocker`. State the accepted blind spot (a `basis` that
   under-describes impact) and the emergent-severity blind spot
   (individually-benign findings that combine). Note the highest-frequency miss:
   a real correctness or CI-hang issue self-labelled `suggestion` with a
   performance-framed `basis` (the polling loop the recipe's worked example flags
   five ways). The backstop is a net by design, not a guarantee (A3).
4. **Implementer-side reconciliation.** Update `implementer-rules.md` so the
   per-iteration implementer reads bodies by anchor (never the evidence base),
   reconciles cross-dimension framings at the code level, and carries the
   `DESIGN_DECISION_NEEDED` context forward (the orchestrator needs no body read
   for escalation). The `findings:` input field and `FIX_NOTES` reconciliation is
   exactly two edits and MUST NOT narrow the `level=track` four-outcome RESULT
   enum (`SUCCESS | DESIGN_DECISION_NEEDED | RISK_UPGRADE_REQUESTED | FAILED`):
   both step-1 orchestration files dispatch all four, and the design body's
   "`SUCCESS` or `DESIGN_DECISION_NEEDED`" phrasing is happy-path narrative, not
   the contract (A2). (a) `what_was_fixed`: flip the `M<n>` citation and the
   "per-dimension IDs are orchestrator-internal, not visible to the implementer"
   note, both of which invert under D5 now that the implementer is handed
   per-dimension anchors (`BC3`, `CQ7`) directly; this is the only site carrying
   that claim. (b) `what_was_skipped`: the staged wording already matches (an
   in-`loc` finding whose fix would over-expand track scope, folded into a plan
   correction by the Phase C orchestrator), so verify rather than rewrite. The
   gate-check re-fan-out re-raises any still-open finding on the unchanged code,
   so the channel is an audit convenience, not the sole safety net (T4, R4); it
   carries summary text, not a finding body, so S1 holds.
5. **Review-mode + gate-check.** Step 2 owns the `BC3`-override-matches-`id`
   reconciliation (it lives in `finding-synthesis-recipe.md`); here, confirm
   `review-mode.md`'s `FIX_FINDING`/`SKIP_TRACK` references stay coherent (its
   payload is a `{location, issue, fix}` triple with no `M<n>` to remove). The
   staged tactical gate-check prompt (`dimensional-review-gate-check.md`) is
   already per-dimension and already emits the full verdict-flag set (VERIFIED /
   STILL OPEN / REGRESSION / REJECTED / MOOT) from Track 3, so verify that holds
   and reconcile only the residual "synthesised finding list" / "synthesis
   severity scale" language that still assumes the `M<n>` layer (T2, R2).
   `code-review-protocol.md` carries no `M<n>` text; confirm its by-file pointer
   to `finding-synthesis-recipe.md` still resolves after the rewrite (verify-only,
   T2).

Invariants to preserve: S1 (no-bodies) keeps the orchestrator's steady-state
context holding only the manifest; the drill-down exception is transient, and S1
survives a malformed manifest only via the step-1 count-validation plus
`CONTRACT_VIOLATION`-to-implementer fallback. S4/S6 (count-validation) gate every
tactical bucket. S2 keeps the reviewer `id` prefix as the match key, never
renumbered. S3 keeps REGRESSION rows unmerged; the exclusion keys off the
gate-check REGRESSION verdict entering the re-synthesis `loc`-collapse, since no
REGRESSION row exists at initial review (R3).

## Concrete Steps

1. Router model, orchestrator side (D1+D4+D5): rewrite the tactical-review orchestration in `step-implementation.md` + `track-code-review.md` to bucket on the manifest index, inject the per-spawn output path, run the §2.5 S4/S6 count-validation with the `CONTRACT_VIOLATION`-to-implementer fallback (A1), and host the relocated bucketing / in-scope-classification / pre-spawn-budget functions reconciled against the existing inline budget check in `track-code-review.md` (R1); rewrite `finding-synthesis-recipe.md` to manifest-only routing: drop the five `M<n>` coupling sites, replace the OVERRIDE with the upgrade-only `basis` scan, move the `BC3` override to the manifest `id` (T1, S2); reconcile `review-iteration.md`'s `§Gate-check synthesis routing` + `§Verdict handling` REJECTED-VERDICT references — risk: high (workflow machinery: load-bearing review-iteration + tactical-routing control-flow protocol)  [ ]
2. Implementer contract reconciliation, D1 implementer side, in `implementer-rules.md`: the per-iteration implementer reads bodies by anchor (never the evidence base), reconciles cross-dimension framings at the code level, and carries `DESIGN_DECISION_NEEDED` context forward; the `findings:`/`FIX_NOTES` reconciliation is exactly two edits (flip `what_was_fixed` to per-dimension IDs, verify `what_was_skipped`) and must not narrow the four-outcome `level=track` RESULT enum (A2) — risk: high (workflow machinery: implementer read/return contract, S1-load-bearing)  [ ]
3. Consumer reconciliation + residual cleanup: confirm `review-mode.md`'s `FIX_FINDING`/`SKIP_TRACK` references stay coherent post-`M<n>`-removal (T1), reconcile the residual "synthesised finding list" / "synthesis severity scale" language in the already-per-dimension `dimensional-review-gate-check.md` (T2, R2), and verify `code-review-protocol.md`'s by-file pointer to `finding-synthesis-recipe.md` still resolves after the rewrite (T2) — risk: medium (workflow machinery, bounded: gate-check prompt residual-language reconcile, borderline prose-only) — size: ~3 files; no mergeable low/medium work (rest of track is high)  [ ]

## Episodes
<!-- Continuous-log. -->

## Validation and Acceptance

- A tactical fan-out leaves the orchestrator's steady-state context holding the
  manifest index only — no `## Findings` body (S1, verifiable against the
  committed review files and the orchestration prose).
- On a malformed manifest the tactical orchestrator routes the `CONTRACT_VIOLATION`
  whole-section fallback to the implementer, never reading the body, so S1 holds
  through validation (S4/S6, A1).
- The orchestrator buckets by `loc`-collapse (non-destructive), keeps blockers +
  in-scope should-fixes, excludes REGRESSION rows from the collapse (S3), and
  spawns the implementer with file paths + in-scope anchors.
- The synthesis `M<n>` layer is gone: findings stay per-dimension end to end, the
  gate-check addresses per dimension, and there is no `M<n>`-to-dimension un-map.
- An under-severed `suggestion`/`should-fix` whose `basis` names a correctness/
  crash/CI-hang/data-loss impact is upgraded manifest-only; a `blocker` is never
  downgraded (D4).
- A `BC3`-style Review-mode override matches the manifest `id` directly (S2).

Per-step acceptance maps to the whole-track criteria above: step 1 satisfies the
S1 no-bodies, `loc`-collapse, `M<n>`-gone, D4 backstop, and `CONTRACT_VIOLATION`
fallback criteria; step 2 satisfies the implementer anchor-read with the
four-outcome RESULT enum preserved; step 3 verifies the consumer prompts stay
coherent with the `BC3`-matches-`id` change (S2).

<!-- Reserved for Move 3. -->

## Idempotence and Recovery
Each step is one commit and re-runnable: a failed or reverted step is reset via
`git revert` / `git reset` and respawned from `mode=INITIAL`. Every edit routes to
the staged mirror under `_workflow/staged-workflow/.claude/` per §1.7(e)
copy-then-edit, so re-running a step overwrites the staged copy idempotently while
the live tree stays at develop-state until the Phase 4 promotion (I6). No external
state and no migrations are involved.

## Artifacts and Notes
<!-- Continuous-log (rare). -->

## Interfaces and Dependencies

**In scope:**
- `.claude/workflow/step-implementation.md` — Phase B `risk:high` tactical
  routing + per-spawn output-path injection
- `.claude/workflow/track-code-review.md` — Phase C tactical routing + path injection
- `.claude/workflow/finding-synthesis-recipe.md` — `M<n>` minting + un-map +
  audit-trail removal; collapse to manifest-only routing
- `.claude/workflow/review-iteration.md` — per-dimension finding-ID format
- `.claude/workflow/implementer-rules.md` — anchor-read bodies, cross-dimension
  reconciliation, `findings:`/`FIX_NOTES` references
- `.claude/workflow/review-mode.md` — `BC3` override matches manifest `id`
- `.claude/workflow/prompts/dimensional-review-gate-check.md` — per-dimension
  addressing + verdict flags
- `.claude/workflow/code-review-protocol.md` — reference reconciliation

**Out of scope:** the strategic-side routing and gate-verifications (Track 2,
the orchestrator keeps its partial-fetch there); the reviewer-side agent edits
(Track 3); the staging plumbing (Track 1). This track is orchestrator + implementer
behavior for the tactical class only.

**Inter-track dependencies:** depends on **Track 2** (the manifest schema and the
`basis`/`cert` index fields) and **Track 3** (the reviewers must self-assign IDs
and write manifests before the orchestrator can route on them and drop `M<n>`).
No downstream tracks. The path injection here is what activates Track 3's
path-conditional file output for the workflow caller.

## Base commit

199b2801b021029e069dcf94118054e87f497f32
