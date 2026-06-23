# Adversarial gate — research log (Phase 0→1), iteration 1

**Scope:** research-log-scoped (phase 1). Target = `_workflow/research-log.md`
`## Decision Log` (D1/D2/D3), `## Surprises & Discoveries`, `## Open Questions`.
**Lens:** `Workflow machinery` — rule coherence, instruction completeness,
context-budget impact applied to the log's decisions.
**Code-grounding note:** the change is bash + markdown only (no Java symbols),
so PSI is not applicable; challenges are grounded by grep + Read against
`workflow-startup-precheck.sh` and the resume-protocol docs. No symbol-reference
claim below depends on find-usages accuracy, so the grep fallback carries no
reference-accuracy caveat here.

## Findings

### A1 [should-fix]
**Certificate**: Assumption test — "step-failure is a ledger milestone-flip append site whose `substate` survives resume"
**Target**: Decision D1 (`## Decision Log`) — Risks/Caveats line "new append sites required (all-steps-complete, code-review-complete, **step-failure**)"; and the `## Open Questions` 2026-06-23T16:24Z append-cadence entry listing `step-implementation.md ... failed-step`.
**Challenge**: D1 lists `step-failure` among the new ledger append sites that "carry the enum" at a milestone flip. But a step failure is NOT a committed milestone — it is uncommitted, in-session, crash-revertible state, so a `substate=failed-step` ledger append placed at the `[!]` write either (a) never commits before the immediate in-session respawn, or (b) is reverted by the implementer's `git reset --hard HEAD` on the next failure. Tracing the failure path: `step-implementation-recovery.md:880-920` writes the failed episode + roster `[!]` flip + retry/split `[ ]` rows and then respawns the implementer **in the same session** (the failed-episode write is never committed/pushed on its own — only sub-step 8 of the *success* path commits, `step-implementation.md:929-948`). The `failed-step` resume sub-state is reachable only when a crash interrupts that uncommitted sequence, and the recovery Detection (`step-implementation-recovery.md:160-205`) already reconciles it from the four working-tree artifacts (Episodes `### Step N — FAILED` block, roster `[!]`, Progress `[!]` entry, retry/split row), not from any committed signal. A ledger `substate=failed-step` append at the `[!]` write therefore (i) cannot be staged into an atomic commit the way D2's atomicity argument requires (there is no track-file commit at that point to co-stage with — contrast `track-review.md:600-608`), and (ii) is reverted with everything else by `git reset --hard HEAD`. So either the append is a no-op for the very state it claims to own, or it survives uncommitted into the next session and contradicts the post-revert track-file truth.
**Evidence**: `step-implementation-recovery.md:880-920` (failed-episode write + retry insert, no commit); `:160-205` (crash reconciliation from working-tree artifacts); `step-implementation.md:929-948` (commit only on success path, sub-step 8); `track-review.md:600-608` (the co-staged atomic commit pattern D2 relies on, which has no analog at the `[!]` write).
**Proposed fix**: Either drop `step-failure` from the ledger append-site set in D1 (resolve `failed-step` purely via the working-tree Detection reconciliation + the fallback `roster_scan`, which already reads `ROSTER_HAS_FAIL`), OR add an explicit rationale to D1 stating WHY a `failed-step` append is wanted despite being uncommitted-and-revertible, and pin in the Phase-1 open question exactly which committed boundary carries it (e.g. only after the retry row lands and is committed). Without this, Phase 1 will wire an append that the resume path cannot trust.

### A2 [should-fix]
**Certificate**: Open-question challenge — "exact append cadence and edits per site (load-bearing, deferred to Phase 1)"
**Target**: `## Open Questions` 2026-06-23T16:24Z entry ("For Phase 1 to resolve … exact append cadence and edits per site"); bears on Decisions D1 + D2.
**Challenge**: The append cadence is filed as a Phase-1 *planning detail*, but one part of it is load-bearing for the gate's own decisions and is under-specified: the split between `substate=steps-done-review-pending` and `substate=review-done-track-open`. Today these two are distinguished by the track-file routing reading the `code review` Progress entry (`determine_c_substate:1761-1767`), and the gap between them is **deliberate and load-bearing**: `track-code-review.md:1471-1487` defers the track-completion ledger boundary until *after user approval*, precisely so a session that ends between code-review-`[x]` and approval re-enters Track Completion rather than skipping user review. Critically, the code-review-`[x]` Progress entry IS committed/pushed on its own (`track-code-review.md:743`) BEFORE that approval. So for the ledger scheme to preserve behavior, the "code-review-complete" append must write `substate=review-done-track-open` and be co-committed with the line-743 Progress commit — NOT deferred to the completion commit at `:1401`. If Phase 1 places it at the completion commit (the intuitive "milestone"), the ledger keeps reading `steps-done-review-pending` across the approval wait, and a crash there re-runs the entire code-review fan-out — a regression versus today. D1's own caveat ("the milestone flips carry the enum") is ambiguous about which milestone for this transition, and the open-question entry lists "`track-code-review.md` review-complete" without pinning the value or the commit it rides.
**Evidence**: `determine_c_substate:1761-1767` (today's review-pending vs review-done split from the Progress `code review` entry); `track-code-review.md:1471-1487` (deferred-completion rationale — the gap is intentional); `track-code-review.md:743` (code-review-`[x]` Progress entry committed before approval); `track-code-review.md:1401-1424` (completion ledger append only after approval).
**Proposed fix**: Resolve into the `## Decision Log` (or an explicit Phase-1 binding line in the open question) the exact value and commit for each of the milestone appends: name `review-done-track-open` as the value the code-review-complete append writes, and bind it to the line-743 commit (the pre-approval one), not the completion commit. This is a not-yet-made decision that an artifact (the Phase-1 plan) will derive from, so per the gate rule it must be pinned to at least should-fix before the gate clears.

### A3 [suggestion]
**Certificate**: Challenge — Decision D2 (keep + fix `roster_scan` as fallback) vs the rejected "fully retire `roster_scan`"
**Target**: Decision D2 (`## Decision Log`).
**Challenge**: D2 keeps two sub-state computations coexisting (ledger-authoritative primary + roster+Progress fallback) and flags the parity risk in its own Risks/Caveats. The decision survives: the fallback is genuinely needed for the non-ledger `determine_state` walk (`workflow-startup-precheck.sh:1832-1962` still calls `determine_c_substate` on the legacy path) and for pre-ledger in-flight `lite`/`full` plans, and the wrap-fix is cheap and satisfies YTDB-1134's literal acceptance criteria for free. The residual weakness is that "the planner must keep them behaviorally aligned and test both paths" is asserted as a caveat but not yet a binding test mandate — and the two paths can drift silently because they share no code (the primary reads a ledger key; the fallback walks the roster). The current open-question test surface does mention "both ledger and fallback paths," so the gap is small.
**Evidence**: `workflow-startup-precheck.sh:1839-1841` (ledger-first), `:1939-1944` (legacy walk still calls `determine_c_substate`); D2 Risks/Caveats; the open-question test-surface entry.
**Proposed fix**: Strengthen D2 (or the test-surface open question) from "must keep them aligned" to a concrete parity-test mandate: a table-driven test that feeds the same track-file fixture through both the ledger path (with the matching `substate` key) and the fallback path (empty `substate`) and asserts identical `state.substate` output for every sub-state slug, so drift fails CI rather than mis-routing a future resume.

### A4 [suggestion]
**Certificate**: Assumption test — Decision D3 ("empty `substate` on a `phase=C` track" ⇒ pre-this-change ledger)
**Target**: Decision D3 (`## Decision Log`).
**Challenge**: D3's invariant is "on a current-scheme ledger every `phase=C` track always carries an explicit `substate`, so empty-substate means exactly one thing — a pre-this-change ledger — the unambiguous fall-back trigger." The decision survives and the loud-explicit posture matches `conventions.md §1.6(e)`. One stress case worth a survival note: a `track-scoped` read means the read keys on "the last `substate` on a line whose `track=` equals the active track" (D1 scoping rule). The A→C append (`track-review.md:596-597`) currently emits `--phase C --track <N>` on ONE line — D3 adds `substate=decomposition-pending` to the *advance* append (`track-code-review.md:1409`, which emits only `--track <N+1>` with phase carried). But Track 1's first `phase=C track=1` line is written by the A→C append, which D1 says "gains `substate=steps-partial`," NOT `decomposition-pending`. So Track 1 at `phase=C` carries `substate=steps-partial` from its A→C line and never has an empty substate — consistent with D3's "Track 1 never hits this state." The only `phase=C` track that legitimately sits with an empty same-track `substate` is an advanced track N+1 between its advance append and its own A→C append; D3 fixes that by putting `decomposition-pending` on the advance line. This holds, but the chain (A→C sets steps-partial for the active track; advance sets decomposition-pending for the next) is split across D1 and D3 and is easy to mis-wire in Phase 1.
**Evidence**: `track-review.md:596-597` (A→C append, one line, gains `substate=steps-partial` per D1); `track-code-review.md:1409` (advance append, gains `substate=decomposition-pending` per D3); D1 scoping rule (track-scoped last-value); D3 ("Track 1 never hits this state").
**Proposed fix**: Add one sentence to D3 (or D1's Risks/Caveats) making the two-append invariant explicit as a single statement: "every `phase=C track=N` line is emitted with a `substate=` field — the A→C line for the active track carries `steps-partial`, the advance line for track N+1 carries `decomposition-pending` — so empty-substate-for-the-active-track is reachable only on a pre-this-change ledger." Stating the closure on one line de-risks the split-across-decisions wiring in Phase 1.

## Evidence base

### Challenge: Decision D1 — Source the State-C sub-state from a track-scoped `substate` ledger key
- **Chosen approach**: Add a `substate=<slug>` ledger key, track-scoped read, as the primary State-C sub-state source; append at milestone flips including step-failure; no per-step appends.
- **Best rejected alternative**: (within D1's frame) NOT adding a step-failure append — let `failed-step` resolve from the working-tree Detection + the wrap-fixed `roster_scan` fallback (D2's path), since the failure write is uncommitted and the recovery already reconciles it from artifacts.
- **Counterargument trace**:
  1. In the failure scenario, the orchestrator writes the failed episode + roster `[!]` + retry rows and respawns in-session (`step-implementation-recovery.md:880-920`) — none of it committed until the retry succeeds via the success-path sub-step 8 (`step-implementation.md:929-948`).
  2. A ledger `substate=failed-step` append placed at the `[!]` write has no co-staged track-file commit to ride (the atomic-commit pattern D2 leans on, `track-review.md:600-608`, has no analog here) and is reverted by the next-failure `git reset --hard HEAD`.
  3. So the append is either a no-op for its own state or survives uncommitted and contradicts post-revert track-file truth; the working-tree Detection (`step-implementation-recovery.md:160-205`) is the only reliable resolver for `failed-step` regardless.
- **Codebase evidence**: `step-implementation-recovery.md:880-920`, `:160-205`; `step-implementation.md:929-948`; `track-review.md:600-608`.
- **Survival test**: WEAK — D1's core (ledger `substate` for the *committed* milestones: steps-complete, review-complete, decomposition-pending) survives; the `step-failure` append-site claim does not survive without a committed-boundary rationale.

### Assumption test: the append cadence is a pure Phase-1 planning detail
- **Claim**: "exact append cadence and edits per site" is a planning detail, not a research blocker (`## Open Questions` 2026-06-23T16:24Z).
- **Stress scenario**: The `steps-done-review-pending` → `review-done-track-open` transition straddles the deliberate deferred-completion gap; code-review-`[x]` is committed before user approval (`track-code-review.md:743`) but the completion ledger boundary lands only after approval (`:1401`). If the review-complete append rides the wrong commit, a crash during the approval wait re-runs the code-review fan-out.
- **Code evidence**: `determine_c_substate:1761-1767`; `track-code-review.md:1471-1487`, `:743`, `:1401-1424`.
- **Verdict**: FRAGILE — the cadence is mostly a planning detail, but this one split is load-bearing and under-pinned; deriving the Phase-1 plan over it risks a behavioral regression.

### Challenge: Decision D2 — Drop `section-discrepancy` from routing; keep + fix `roster_scan` as the fallback
- **Chosen approach**: Ledger is the single routing source; `determine_c_substate` falls back to roster+Progress (with the wrap-fixed `roster_scan`, keeping `section-discrepancy`) only when the track-scoped `substate` read is empty.
- **Best rejected alternative**: fully retire `roster_scan` + the joint read.
- **Counterargument trace**:
  1. The legacy `determine_state` walk still calls `determine_c_substate` (`workflow-startup-precheck.sh:1939-1944`) and pre-ledger in-flight plans have no `substate` key, so retiring the fallback breaks their resume.
  2. The wrap-fix is cheap and satisfies YTDB-1134's literal acceptance criteria for free.
  3. The residual risk is silent drift between two code-disjoint computations; D2 names it as a caveat but not yet a binding parity test.
- **Codebase evidence**: `workflow-startup-precheck.sh:1839-1841`, `:1939-1944`; D2 Risks/Caveats.
- **Survival test**: YES — decision holds; the suggestion only hardens the parity guarantee into a test mandate.

### Assumption test: empty `substate` on a `phase=C` track is an unambiguous pre-change-ledger signal (D3)
- **Claim**: On a current-scheme ledger every `phase=C` track carries an explicit `substate`, so empty ⇒ pre-this-change ledger ⇒ fall back to `roster_scan`.
- **Stress scenario**: The active track's `phase=C` line is the A→C append (gains `steps-partial`, D1); the next track's `phase=C` line is the advance append (gains `decomposition-pending`, D3). The closure depends on both appends being wired, split across D1 and D3.
- **Code evidence**: `track-review.md:596-597`; `track-code-review.md:1409`; D1 scoping rule; D3.
- **Verdict**: HOLDS — the invariant is sound; the suggestion only consolidates the split-across-decisions wiring into one stated closure so Phase 1 cannot wire half of it.

---

## §2.5 manifest

```yaml
review_type: adversarial
scope: research-log
phase: 1
iteration: 1
target: docs/adr/mid-track-resume/_workflow/research-log.md
matched_categories: ["Workflow machinery"]
verdict: should-fix
findings: 4
blockers: 0
should_fix: 2
suggestions: 2
index:
  - id: A1
    sev: should-fix
    anchor: "### A1"
    loc: "research-log.md §Decision Log D1; §Open Questions 2026-06-23T16:24Z"
    cert: "Assumption test — step-failure as a surviving ledger milestone append site"
    basis: "step-implementation-recovery.md:880-920,160-205; step-implementation.md:929-948; track-review.md:600-608"
  - id: A2
    sev: should-fix
    anchor: "### A2"
    loc: "research-log.md §Open Questions 2026-06-23T16:24Z (append cadence)"
    cert: "Open-question challenge — review-done vs review-pending append cadence is load-bearing"
    basis: "determine_c_substate:1761-1767; track-code-review.md:1471-1487,743,1401-1424"
  - id: A3
    sev: suggestion
    anchor: "### A3"
    loc: "research-log.md §Decision Log D2"
    cert: "Challenge — D2 fallback parity not yet a test mandate"
    basis: "workflow-startup-precheck.sh:1839-1841,1939-1944; D2 Risks/Caveats"
  - id: A4
    sev: suggestion
    anchor: "### A4"
    loc: "research-log.md §Decision Log D3"
    cert: "Assumption test — empty-substate closure split across D1/D3"
    basis: "track-review.md:596-597; track-code-review.md:1409; D1 scoping rule; D3"
evidence_base:
  certificates: 4
  summary: "1 decision challenge (D1, WEAK), 1 assumption test on the cadence open question (FRAGILE), 1 decision challenge (D2, YES), 1 assumption test (D3, HOLDS). Two should-fix findings gate: A1 (step-failure append site is uncommitted/revertible, no atomic-commit analog) and A2 (review-done-track-open append must ride the pre-approval commit or a crash re-runs code review). Two suggestions harden D2 parity and D3 closure."
```
