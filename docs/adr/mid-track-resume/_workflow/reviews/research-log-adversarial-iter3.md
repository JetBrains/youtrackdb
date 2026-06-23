# Adversarial gate — research log (Phase 0→1), iteration 3 (verdict-producer)

**Scope:** research-log-scoped (phase 1). Target = `_workflow/research-log.md`
`## Decision Log` (D1/D2/D3), `## Surprises & Discoveries`, `## Open Questions`.
**Lens:** `Workflow machinery` — rule coherence, instruction completeness,
context-budget impact applied to the log's decisions.
**Code-grounding note:** the change is bash + markdown only (no Java symbols),
so PSI is not applicable; verdicts are grounded by grep + Read against
`workflow-startup-precheck.sh`, `step-implementation.md`, `track-review.md`,
and `track-code-review.md`. No symbol-reference claim below depends on
find-usages accuracy, so the grep fallback carries no reference-accuracy
caveat here.

**Verdict: gate CLEARS.** All four iter-1 findings (A1–A4) re-confirmed
VERIFIED with no regression under the iter-2 revision; the one iter-2 finding
(NA1) is VERIFIED resolved by the revised D1 cadence row. No new blocker or
should-fix introduced. Manifest verdict `pass`.

## Prior-finding verdicts

- **A1 (iter-1 should-fix) — VERIFIED (no regression).** D1's "Append cadence"
  table (research-log.md:47-51) still carries no `step-failure` row, and
  lines 52-59 still state `failed-step` is **NOT** a ledger sub-state
  (uncommitted failure writes, reverted by `git reset --hard HEAD`, a crashed-
  during-failure session resumes as `steps-partial` where the Phase B
  Detection finds the `[!]`/retry rows, `step-implementation-recovery.md:166-203`).
  The iter-2 NA1 absorption added a row to the *same* table but did not
  reintroduce a `step-failure` row — verified the table now has four rows
  (steps-partial / steps-done-review-pending / review-done-track-open /
  decomposition-pending) and none of them is the failure state. No regression.
- **A2 (iter-1 should-fix) — VERIFIED (no regression).** The `review-done-track-open`
  row (research-log.md:50) still pins to `track-code-review.md:743` (the
  pre-approval code-review-complete Workflow-update commit) and explicitly NOT
  the post-approval completion commit, with the crash-during-approval-wait
  regression as the stated reason. Re-confirmed against current
  `track-code-review.md:732-746` (Progress `[x]` committed/pushed as a Workflow
  update **before** the approval wait) — the pin still lands on the right
  commit after the iter-2 edit.
- **A3 (iter-1 suggestion) — VERIFIED (no regression).** D2 Risks/Caveats
  (research-log.md:84-93) still mandates the concrete dual-path parity test in
  `test_workflow_startup_precheck.py`. Unchanged by the iter-2 revision.
- **A4 (iter-1 suggestion) — VERIFIED (no regression).** D3's "Closure
  invariant" bullet (research-log.md:110-118) still states the two-append
  closure on one statement (A→C line carries `steps-partial`, advance line
  carries `decomposition-pending`, both halves must land together).
  Re-confirmed against `track-code-review.md:1409` (advance append) and
  `:1411` (last-track `phase=D`) — the boundaries D3 references are unchanged.
- **NA1 (iter-2 should-fix) — VERIFIED resolved.** See the assumption test
  below. The revised `steps-done-review-pending` row (research-log.md:49) now
  rides "a **NEW** Phase-B-complete Workflow-update commit added to
  `step-implementation.md` §Phase B Completion (stage the `Step implementation
  [x]` flip + the append), symmetric with the A→C boundary" — replacing the
  iter-2 abstract "the Phase B→C boundary commit" with a concrete, named new
  commit at a named section. This is the same concreteness A2 gave
  `review-done-track-open`, applied at the site NA1 flagged.

## Findings

(none — gate clears)

## Evidence base

### Assumption test: the NA1 fix's new Phase-B-complete commit is a sound, non-conflicting boundary for the `steps-done-review-pending` append
- **Claim** (revised D1, research-log.md:49): the `steps-done-review-pending`
  append rides a new Workflow-update commit added to `step-implementation.md`
  §Phase B Completion, staging the `Step implementation [x]` Progress flip plus
  the ledger append, symmetric with the A→C boundary commit.
- **Stress scenario — is there a committed boundary to ride, and does the new
  commit collide with the existing §Phase B Completion steps (Inform-user,
  reflection, End-session)?** §Phase B Completion today is four ordered steps
  (`step-implementation.md:1070-1100`): step 1 flips `Step implementation` to
  `[x]` (no commit instruction today); step 2 informs the user; step 3 runs
  self-improvement reflection; step 4 ends the session. The new commit slots
  after step 1 (it needs the `[x]` flip on disk to stage). Two collision
  vectors checked:
  1. **Reflection artifacts.** Step 3 explicitly "produces no commit"
     (`step-implementation.md:1097`) — reflection creates YouTrack issues, not
     git changes — so a commit staging only the `Step implementation [x]`
     Progress flip + the ledger line cannot accidentally sweep in reflection
     output regardless of whether it lands before or after step 3.
  2. **`git reset --hard HEAD` durability.** This is the exact failure class
     A1/A2 and the A→C boundary guard against. The append must survive a revert
     to be a trustworthy resume signal, which requires a commit.
- **Code evidence — the symmetry is established, not invented:**
  - `track-review.md:596-608` — the A→C boundary: `--append-ledger
    --phase C --track <N>` followed by a `git add` of *both* the track file and
    the ledger in one atomic Workflow-update commit. The revised row says the
    new Phase-B-complete commit is "symmetric with the A→C boundary" — this is
    the literal template it mirrors (flip + append, co-staged, one commit).
  - `step-implementation.md:88-92` — the *start*-of-Phase-B precedent:
    §Phase B already opens with a dedicated Workflow-update commit
    ("Record Phase B base commit for <track>") that stages a single track-file
    edit (the `## Base commit` write) **specifically because** the implementer's
    `git reset --hard HEAD` would otherwise discard an uncommitted track-file
    change (`step-implementation.md:83-85`). A close-of-Phase-B commit staging
    the `[x]` flip + ledger append is the bookend of this same pattern; the
    machinery already commits single track-file edits at Phase-B boundaries for
    exactly this durability reason, so the new commit introduces no novel
    mechanism.
  - `grep -n 'append-ledger' step-implementation.md step-implementation-recovery.md`
    → empty (exit 1). Confirms NA1's premise that Phase B has no existing
    `--append-ledger` site, so the row genuinely needs a *new* one — which the
    revision now names — rather than attaching to a pre-existing call. The other
    three rows ride pre-existing appends (`track-review.md:596` A→C;
    `track-code-review.md:743` pre-approval Progress commit A2 binds to;
    `track-code-review.md:1409`/`:1411` advance/last); this row uniquely needed
    a created boundary, and the revision creates one concretely.
- **Verdict:** HOLDS. The revised row names a concrete commit at a named
  section, the commit is the established A→C / base-commit pattern applied at
  Phase-B close, reflection produces no commit so there is no staging
  collision, step 4 (session end) follows the new commit cleanly, and the new
  commit also retires the latent uncommitted-`Step implementation [x]`-flip
  wart the revision flags. The "rides an already-committed boundary" guarantee
  D1's header asserts is now met for all four rows. NA1 is resolved; no new
  defect introduced.

### Re-confirmation sweep (A1–A4, no regression)
- **A1**: D1 cadence table four rows, no `step-failure`; lines 52-59 unchanged.
  `step-implementation-recovery.md:166-203` working-tree reconciliation owns the
  failed-step resume. HOLDS.
- **A2**: `review-done-track-open` → `track-code-review.md:743` (pre-approval),
  confirmed `:732-746` Progress `[x]` committed before approval wait, `:1471-1487`
  deferred-completion rationale unchanged. HOLDS.
- **A3**: D2 parity-test mandate (research-log.md:84-93) intact. HOLDS.
- **A4**: D3 closure invariant (research-log.md:110-118) intact; advance/last
  boundaries `track-code-review.md:1409`/`:1411` unchanged. HOLDS.

---

## §2.5 manifest

```yaml
review_type: adversarial
scope: research-log
phase: 1
iteration: 3
target: docs/adr/mid-track-resume/_workflow/research-log.md
matched_categories: ["Workflow machinery"]
verdict: pass
findings: 0
blockers: 0
should_fix: 0
suggestions: 0
prior_findings_verdicts:
  - id: A1
    verdict: VERIFIED
    basis: "D1 cadence table (research-log.md:47-51) still omits step-failure; lines 52-59 keep failed-step as NOT a ledger sub-state, resolved via working-tree Detection (step-implementation-recovery.md:166-203). NA1 absorption added a row to the same table without reintroducing a failure row. No regression."
  - id: A2
    verdict: VERIFIED
    basis: "review-done-track-open still pinned to track-code-review.md:743 (pre-approval); re-confirmed :732-746 Progress [x] committed before approval wait, :1471-1487 deferred-completion rationale. No regression."
  - id: A3
    verdict: VERIFIED
    basis: "D2 Risks/Caveats (research-log.md:84-93) still mandates the concrete dual-path parity test in test_workflow_startup_precheck.py. Unchanged by iter-2 revision."
  - id: A4
    verdict: VERIFIED
    basis: "D3 Closure invariant (research-log.md:110-118) still states the two-append closure; advance/last boundaries track-code-review.md:1409/:1411 unchanged."
  - id: NA1
    verdict: VERIFIED
    basis: "Revised D1 steps-done-review-pending row (research-log.md:49) now rides a NEW named Phase-B-complete Workflow-update commit at step-implementation.md §Phase B Completion (stage [x] flip + append), symmetric with the A→C boundary (track-review.md:596-608) and the existing start-of-Phase-B base-commit pattern (step-implementation.md:88-92). Reflection produces no commit (:1097) so no staging collision; grep append-ledger over Phase B files empty confirms a new site was genuinely needed and is now concretely named. Resolved."
index: []
evidence_base:
  certificates: 2
  summary: "Verdict-producer iteration 3. All four iter-1 findings (A1-A4) re-confirmed VERIFIED with no regression under the iter-2 revision. The iter-2 NA1 should-fix is VERIFIED resolved: the steps-done-review-pending cadence row now names a concrete NEW Phase-B-complete Workflow-update commit (stage the Step implementation [x] flip + the ledger append) at step-implementation.md §Phase B Completion, symmetric with the A→C boundary commit (track-review.md:600-608) and bookending the established start-of-Phase-B base-commit pattern (step-implementation.md:88-92). Reflection produces no commit (:1097) so the new commit has no staging collision; session-end (step 4) follows it cleanly; the new commit also retires the latent uncommitted-[x]-flip wart. grep -n append-ledger over the two Phase B files returns empty, confirming the row uniquely needed a created boundary, which the revision now supplies concretely. No new blocker or should-fix. Gate CLEARS."
```
