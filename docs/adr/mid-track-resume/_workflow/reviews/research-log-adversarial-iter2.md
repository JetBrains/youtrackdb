# Adversarial gate — research log (Phase 0→1), iteration 2 (verdict-producer)

**Scope:** research-log-scoped (phase 1). Target = `_workflow/research-log.md`
`## Decision Log` (D1/D2/D3), `## Surprises & Discoveries`, `## Open Questions`.
**Lens:** `Workflow machinery` — rule coherence, instruction completeness,
context-budget impact applied to the log's decisions.
**Code-grounding note:** the change is bash + markdown only (no Java symbols),
so PSI is not applicable; verdicts are grounded by grep + Read against
`workflow-startup-precheck.sh`, `step-implementation.md`,
`step-implementation-recovery.md`, `track-review.md`, `track-code-review.md`,
and `workflow.md`. No symbol-reference claim below depends on find-usages
accuracy, so the grep fallback carries no reference-accuracy caveat here.

## Prior-finding verdicts (iter-1 A1–A4)

- **A1 (was should-fix) — VERIFIED.** The revised D1 "Append cadence" table
  (research-log.md:47-51) carries no `step-failure` row, and lines 52-59 state
  `failed-step` is **NOT** a ledger sub-state — the failure writes are
  uncommitted, reverted by `git reset --hard HEAD`, and a crashed-during-failure
  session resumes as `steps-partial` where the Phase B Detection finds the `[!]`
  / retry rows. Confirmed against `step-implementation-recovery.md:166-203`
  (working-tree reconciliation) — the `[!]` reconstruction path exists and owns
  this resume, so dropping the ledger append is correct.
- **A2 (was should-fix) — VERIFIED.** The revised table row
  (research-log.md:50) pins `review-done-track-open` to
  `track-code-review.md:743` (the pre-approval code-review-complete Workflow-update
  commit) and explicitly NOT the post-approval completion commit, with the
  crash-during-approval-wait regression as the stated reason. Confirmed against
  `track-code-review.md:735-746` (Progress `[x]` committed as a pre-approval
  Workflow update) and `:1471-1487` ("Why deferred write" — completion is
  deferred to post-approval so an interrupted approval wait re-enters Track
  Completion). The pin lands on the right commit.
- **A3 (was suggestion) — VERIFIED.** The revised D2 Risks/Caveats
  (research-log.md:88-93) now mandates a dual-path parity test in
  `test_workflow_startup_precheck.py` that asserts the ledger path and the
  ledger-stripped fallback resolve to the identical sub-state for a fixture whose
  roster/Progress imply the same slug. The mandate is concrete (named file,
  named assertion) rather than the prior soft "must stay aligned."
- **A4 (was suggestion) — VERIFIED.** D3 now carries an explicit "Closure
  invariant" bullet (research-log.md:110-118) stating the two-append closure on
  one statement — the A→C line carries `steps-partial`, the advance line carries
  `decomposition-pending`, both halves must land together, and a
  half-implementation leaves a `phase=C` track with no `substate`. The
  split-across-decisions wiring is now consolidated into a single readable
  invariant.

All four prior findings are resolved. One NEW finding the revision's reframing
left open gates the iteration: NA1.

## Findings

### NA1 [should-fix]
**Certificate**: Open-question challenge — "the `steps-done-review-pending` append rides a 'Phase B→C boundary commit' that exists as a committed artifact"
**Target**: Decision D1 (`## Decision Log`) — the Append-cadence table row "All steps complete (Phase B→C) | `steps-done-review-pending` | the Phase B→C boundary commit" (research-log.md:49).
**Challenge**: The iter-1 A2 fix pinned `review-done-track-open` to a concrete pre-approval commit (`track-code-review.md:743`). The revision then generalized the cadence into a four-row table, but the `steps-done-review-pending` row names only an abstract "the Phase B→C boundary commit" — and **no such committed boundary, and no `--append-ledger` call, exists in the Phase B files this row would have to edit.** Tracing the Phase B exit: `step-implementation.md` §Phase B Completion step 1 marks `Step implementation` as `[x]` in `## Progress`, but the section gives that flip **no commit/push instruction** and step 4 then ends the session (`step-implementation.md:1076,1099-1100`). The only committed boundary at the end of Phase B is the last *per-step* episode commit (`step-implementation.md` sub-step 8, "Record episode for <step description>"). Critically, `grep -n 'append-ledger' step-implementation.md step-implementation-recovery.md` returns **nothing** — Phase B never touches the ledger, so there is no existing append site for `steps-done-review-pending` to attach to, unlike the other three rows which ride pre-existing `--append-ledger` calls (`track-review.md:596` A→C; `track-code-review.md:1409`/`:1411` advance/last; `track-code-review.md:743` is the pre-approval Progress commit A2 binds to). This is the same defect A2 closed for `review-done-track-open`, recurring at the `steps-done-review-pending` site that the table reintroduced without an equivalent concrete pin.
**Consequence if unpinned**: Phase 1 will derive an append from "the Phase B→C boundary commit" with no boundary to ride. The plausible mis-wirings are (a) attach the append to the last per-step episode commit — but that commit fires for *every* step, so it would stamp `steps-done-review-pending` after step 1 of an N-step track and mis-route a mid-track resume as review-pending; or (b) add a brand-new `--append-ledger` + commit to Phase B Completion step 1 — a real change Phase 1 must specify (does the `Step implementation [x]` flip now get committed? with the ledger co-staged? in which session, given step 4 ends it?). Either way the "rides an already-committed boundary" guarantee D1's own header asserts (research-log.md:43-46) is unmet for this one row.
**Evidence**: `step-implementation.md:1070-1101` (§Phase B Completion — step-1 `[x]` flip has no commit, step-4 ends session); `step-implementation.md` sub-step 8 (the per-step episode commit, the only committed Phase-B boundary, fires per step not per track); `grep -n 'append-ledger' step-implementation.md step-implementation-recovery.md` → empty (no ledger site in Phase B); contrast `track-code-review.md:743` (the concrete pre-approval commit A2 pinned `review-done-track-open` to) and `track-review.md:596-608` (the A→C append+commit `steps-partial` rides).
**Proposed fix**: Resolve the `steps-done-review-pending` row to the same concreteness A2 gave `review-done-track-open`: name the exact commit and append site. Most likely this means adding to D1 (or a Phase-1 binding line) that Phase B Completion step 1 gains a `--append-ledger --substate steps-done-review-pending` call **co-staged with a new commit of the `Step implementation [x]` Progress flip** (which Phase B Completion does not commit today), and stating which actor/session owns it given the deliberate session-end at step 4. Without this pin the only honest current-boundary alternative is to drop the dedicated `steps-done-review-pending` append and let the empty-substate fallback + the `Step implementation [x]` Progress marker resolve it — which contradicts D3's "every `phase=C` track carries an explicit `substate`" closure invariant and so must be decided in research, not Phase 1.

## Evidence base

### Open-question challenge: the `steps-done-review-pending` append rides a committed Phase B→C boundary
- **Claim**: D1's cadence table (research-log.md:43-51) asserts every `substate` append "rides an already-committed boundary," and the `steps-done-review-pending` row rides "the Phase B→C boundary commit."
- **Stress scenario**: Phase 1 reads the table to wire the append. It opens `step-implementation.md` §Phase B Completion (the Phase B→C boundary) and finds: step 1 flips `Step implementation` to `[x]` with no commit instruction; step 4 ends the session; no `--append-ledger` call exists anywhere in the file. There is no committed "Phase B→C boundary commit" to ride — the last committed boundary is the per-step episode commit, which fires once per step. Attaching the append there stamps `steps-done-review-pending` after the first step and mis-routes a genuine mid-track `steps-partial` resume; attaching it to a new commit requires Phase 1 to invent the commit (and decide the session ownership) the research log was supposed to settle.
- **Code evidence**: `step-implementation.md:1070-1101` (no commit on the `[x]` flip, session ends at step 4); `step-implementation.md` sub-step 8 (per-step episode commit — the only committed Phase-B boundary); `grep -n 'append-ledger' step-implementation.md step-implementation-recovery.md` → empty; `track-code-review.md:743` and `track-review.md:596-608` (the concrete committed boundaries the other three rows ride).
- **Verdict**: FRAGILE — the cadence row is under-pinned exactly where A2 demanded concreteness; deriving the Phase-1 wiring over "the Phase B→C boundary commit" risks either a per-step mis-stamp or an unspecified new commit. The defect is narrow (one of four rows) and the fix is a single binding sentence, so should-fix, not blocker.

---

## §2.5 manifest

```yaml
review_type: adversarial
scope: research-log
phase: 1
iteration: 2
target: docs/adr/mid-track-resume/_workflow/research-log.md
matched_categories: ["Workflow machinery"]
verdict: should-fix
findings: 1
blockers: 0
should_fix: 1
suggestions: 0
prior_findings_verdicts:
  - id: A1
    verdict: VERIFIED
    basis: "D1 cadence table omits step-failure; research-log.md:52-59 states failed-step is NOT a ledger sub-state, resolved via working-tree Detection (step-implementation-recovery.md:166-203)."
  - id: A2
    verdict: VERIFIED
    basis: "D1 row pins review-done-track-open to track-code-review.md:743 (pre-approval), NOT post-approval; confirmed :735-746 pre-approval commit + :1471-1487 deferred-completion rationale."
  - id: A3
    verdict: VERIFIED
    basis: "D2 Risks/Caveats (research-log.md:88-93) mandates a concrete dual-path parity test in test_workflow_startup_precheck.py."
  - id: A4
    verdict: VERIFIED
    basis: "D3 carries a 'Closure invariant' bullet (research-log.md:110-118) consolidating the two-append closure into one statement."
index:
  - id: NA1
    sev: should-fix
    anchor: "### NA1"
    loc: "research-log.md §Decision Log D1 (Append-cadence table, steps-done-review-pending row, line 49)"
    cert: "Open-question challenge — steps-done-review-pending append has no committed Phase B→C boundary / no append site"
    basis: "step-implementation.md:1070-1101 (no commit on [x] flip, session ends); grep append-ledger step-implementation.md → empty; contrast track-code-review.md:743, track-review.md:596-608"
evidence_base:
  certificates: 1
  summary: "All four prior findings VERIFIED (A1 step-failure dropped; A2 review-done pinned to :743 pre-approval; A3 parity test mandated; A4 closure invariant stated). One NEW should-fix (NA1): the steps-done-review-pending cadence row rides 'the Phase B→C boundary commit', but step-implementation.md §Phase B Completion neither commits the Step-implementation [x] flip nor calls --append-ledger anywhere — the same under-pinning A2 closed for review-done-track-open, recurring at this site. Gate does NOT clear; needs the row pinned to a concrete commit + append, or the append dropped and the empty-substate fallback used (which would contradict D3's closure invariant — a research-level decision)."
```
