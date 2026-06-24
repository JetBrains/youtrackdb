<!--
MANIFEST
producer: review-workflow-instruction-completeness
target: Track 2, Step 1 (commit 8609dbd4b4)
findings: 1
evidence_base: 5
cert_index: C1
flags: []
index:
  - id: WI1
    sev: Recommended
    anchor: "#wi1-recommended--single-step-track-claim-false-for-mediumlow-risk"
    loc: "docs/adr/mid-track-resume/_workflow/staged-workflow/.claude/workflow/track-code-review.md:876-882"
    cert: C1
    basis: judgment
-->

## Findings

### WI1 [Recommended] — single-step-track claim false for medium/low-risk single-step tracks

- **File:** `docs/adr/mid-track-resume/_workflow/staged-workflow/.claude/workflow/track-code-review.md` (lines 876-882)
- **Axis:** phase output → next-phase input (single-step-track reconciliation with the terminal `substate`)
- **Cost:** the new prose gives a future maintainer a wrong mental model of which terminal `substate` a single-step track lands in — it claims *every* single-step track terminates at `steps-done-review-pending`, but a `risk: medium`/`low` single-step track terminates at `review-done-track-open`. No routing breakage (both slugs route to completion), but the instruction is inaccurate.
- **Issue:** The delta adds, at step 6 of the review loop, the claim:

  > "A single-step track never reaches step 6 (it skips the review loop), so it gets no `review-done-track-open` append; it stays at `steps-done-review-pending` ..."

  This is only true for a **`risk: high`** single-step track. The same file's §Single-Step Track section (lines 105-124) skips the review loop *only* when the sole step is `risk: high`; when the single step is `risk: medium` or `risk: low`, step-level review was skipped in Phase B, so "track-level review **must** run" and the track "Proceed[s] to Multi-Step Tracks below; the single step is treated as the entire diff" (lines 121-124). Such a single-step track **does** run the review loop, **does** reach step 6, and on the all-reviews-pass path **does** fire the new pre-approval commit appending `review-done-track-open`. The unconditional "A single-step track never reaches step 6" contradicts that path. The track file's `## Plan of Work` boundary 3 (lines 256-259) and edge-case bullet (lines 298-303) carry the same overbroad claim, but the changed/staged surface is the track-code-review.md prose at 876-882.
- **Suggestion:** Scope the claim to the high-risk single-step case, mirroring the section it depends on. For example: "A **`risk: high`** single-step track skips the review loop, never reaches step 6, and so gets no `review-done-track-open` append — it stays at `steps-done-review-pending` and is carried past review by the track-completion append below. (A `risk: medium`/`low` single-step track runs the full review loop per §Single-Step Track, reaches step 6, and terminates at `review-done-track-open` like any multi-step track.) Both terminal slugs route correctly to completion because the resume checks whether review applies." The same scoping should be reconciled into the track-file `## Plan of Work` and edge-case bullet at Phase 4 (these are tactical context, not the reviewed surface).

## Evidence base

#### C1 — single-step claim vs the risk-gated skip and the new step-6 commit

Refuted the prose's universal quantifier; confirms the WI1 gap.

- **Claim under review (delta, track-code-review.md:876-882):** "A single-step track never reaches step 6 (it skips the review loop), so it gets no `review-done-track-open` append; it stays at `steps-done-review-pending`."
- **Counter-path (same file, 105-124):** the review-loop skip is gated on `risk: high` (line 105: "exactly **1 step** AND that step is tagged `risk: high`"). Lines 121-124: "If the single step is `risk: medium` or `risk: low`, step-level review was skipped ... so track-level review **must** run. Proceed to **Multi-Step Tracks** below; the single step is treated as the entire diff." A medium/low single-step track therefore enters the Multi-Step review loop and reaches step 6.
- **Step-6 guard (delta, 842-882):** the new pre-approval commit fires "only on the all-reviews-pass path." A medium/low single-step track that passes review hits the guard and appends `review-done-track-open` — exactly the append the prose says never happens for a single-step track.
- **Routing not broken:** both terminal slugs resolve to track completion. `workflow.md` §Startup Protocol step 5 maps `steps-done-review-pending` → "Run Phase C from the current iteration (single-step tracks skip code review but still run track completion)" and `review-done-track-open` → "Resume track completion." `determine_state_from_ledger` (staged `workflow-startup-precheck.sh:1981-1986`) emits whichever slug the ledger last carried, so the resume action is correct in both single-step sub-cases. The defect is the inaccurate instruction, not a stranded state — hence Recommended, not Critical.

Findings checked and **not** raised (survived; one line each):

- **Phase B→C completion-commit guard complement (concern 1).** Confirmed handled. The new commit's "Guard: normal completion path only" (step-implementation.md:1112-1120) names the context-warning / two-failure early-exit; structurally the session-end gate (step-implementation.md:950-960) pauses *before* spawning the next step and never reaches §Phase B Completion, so an early-exit roster stays `steps-partial`. No gap.
- **Pre-approval review-pass commit's blockers-persist complement (concern 2).** Confirmed handled. Delta lines 848-854 explicitly skip the commit on the blockers-persist exit and state the `Track complete` Progress entry rides the post-approval track-completion commit "as before," matching pre-change behavior. No gap.
- **Replan-revert append vs `--phase 0` reset ordering (concern 4).** Confirmed handled. `determine_state_from_ledger` resolves `phase=0` in its `0 | A | D | Done` arm and returns `{phase:"0",substate:null}` (staged script:1952-1959) *before* the `C` arm reads `substate`; the delta (inline-replanning.md:259-271) states the `--phase 0` reset is the routing signal and the `--substate steps-partial` is forward-hygiene, never read on the replan resume. No gap.
- **Both new commits registered in resume-side orphan/scaffolding detection (concern 5).** Confirmed handled. step-implementation-recovery.md entry 5 (delta lines 317-321) now enumerates *both* the Phase B completion commit (`steps-done-review-pending` append) and the Phase C review-pass commit (`review-done-track-open` append) as scaffolding. No gap.
- **A→C `steps-partial` append on both the normal path (track-review.md:600) and the recovery path (track-review.md:1052).** Confirmed: the recovery-path append carries `--substate steps-partial` identically, so a skipped/corrupted A→C boundary re-appended in a dedicated commit lands the same slug. No gap.
- **Track-advance `decomposition-pending` append vs last-track `--phase D` (concern: missing complement).** Confirmed handled. track-code-review.md:1447-1460 sets `--substate decomposition-pending` on the `--track <N+1>` arm and explicitly notes the `--phase D` last-track append carries no `--substate` (phase D has no within-track sub-state); `determine_state_from_ledger` emits `substate:null` for phase D. No gap.
- **Slug byte-identity to the four canonical slugs the Track 1 reader resolves.** Confirmed: every appended slug (`steps-partial`, `steps-done-review-pending`, `review-done-track-open`, `decomposition-pending`) matches the enum in the staged script comments (1059-1065) and the `workflow.md` step-5 routing rows verbatim. No gap.
