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
- [ ] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review
- [ ] Track completion

## Surprises & Discoveries
<!-- Continuous-log. Empty at Phase 1. -->

## Decision Log
<!-- Continuous-log. -->

<!-- Reserved for Move 1 — per-track inlined Decision Records. -->

## Outcomes & Retrospective
<!-- Continuous-log. -->

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
drops it after the decision — transient, never retained across the next teardown
(the bounded exception in S1).

## Plan of Work

1. **Router model (D1).** Rewrite the tactical-review orchestration in
   `step-implementation.md` (Phase B `risk:high` step review) and
   `track-code-review.md` (Phase C track review) so the orchestrator buckets on
   the manifest index and spawns the implementer with file paths + in-scope
   anchors. Inject the per-spawn output path at these dispatch sites (the switch
   that turns on Track 3's file output), not in `review-agent-selection.md`.
2. **Per-dimension addressing (D5, orchestrator side).** Remove the `M<n>`
   minting, the `M<n>`-to-dimension un-map, and the contributing-dimensions audit
   trail from `finding-synthesis-recipe.md`; collapse synthesis to manifest-only
   routing. Reconcile `review-iteration.md`'s `### Gate-check synthesis routing`
   reference with the removed `M<n>` merge layer (its `§Finding ID prefixes` table
   is already per-dimension, so there is no finding-ID format to convert there).
   Pass the per-dimension high-water-mark to the reviewer at spawn.
3. **Severity backstop (D4).** Replace the body-dependent OVERRIDE with the
   manifest-only upgrade scan over the `basis` field. State the accepted blind
   spot (a `basis` that under-describes impact) and the emergent-severity blind
   spot (individually-benign findings that combine).
4. **Implementer-side reconciliation.** Update `implementer-rules.md` so the
   per-iteration implementer reads bodies by anchor (never the evidence base),
   reconciles cross-dimension framings at the code level, and carries the
   `DESIGN_DECISION_NEEDED` context forward (the orchestrator needs no body read
   for escalation). Update the `findings:` field / `FIX_NOTES` references.
5. **Review-mode + gate-check.** Update `review-mode.md` so a `BC3`-style
   override matches the manifest `id` directly. Update the tactical gate-check
   prompt (`dimensional-review-gate-check.md`) to per-dimension addressing and
   verdict flags (VERIFIED / STILL OPEN / REGRESSION) the orchestrator routes on.
   Reconcile `code-review-protocol.md` references.

Invariants to preserve: S1 (no-bodies) — the orchestrator's steady-state context
holds no tactical body, only the manifest; the drill-down exception is transient.
S3 — REGRESSION rows stay unmerged. S2 — the reviewer `id` prefix is the match
key and is never renumbered.

## Concrete Steps
<!-- Phase A placeholder. -->

## Episodes
<!-- Continuous-log. -->

## Validation and Acceptance

- A tactical fan-out leaves the orchestrator's steady-state context holding the
  manifest index only — no `## Findings` body (S1, verifiable against the
  committed review files and the orchestration prose).
- The orchestrator buckets by `loc`-collapse (non-destructive), keeps blockers +
  in-scope should-fixes, excludes REGRESSION rows from the collapse (S3), and
  spawns the implementer with file paths + in-scope anchors.
- The synthesis `M<n>` layer is gone: findings stay per-dimension end to end, the
  gate-check addresses per dimension, and there is no `M<n>`-to-dimension un-map.
- An under-severed `suggestion`/`should-fix` whose `basis` names a correctness/
  crash/CI-hang/data-loss impact is upgraded manifest-only; a `blocker` is never
  downgraded (D4).
- A `BC3`-style Review-mode override matches the manifest `id` directly (S2).

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3. -->

## Idempotence and Recovery
<!-- Phase A placeholder. -->

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
