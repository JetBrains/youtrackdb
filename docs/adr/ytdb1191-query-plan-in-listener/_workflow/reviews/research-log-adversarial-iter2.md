<!-- MANIFEST
findings: 6   severity: {blocker: 0, should-fix: 0, suggestion: 1, verified: 5}
verdict: PASS
index:
  - {id: A1, sev: should-fix, verdict: VERIFIED, loc: "research-log.md Decision Log (Adversarial gate revisions / A1)", anchor: "### A1 ", cert: C1, basis: "documented-limitation + required cached-query test; per-transaction cache confirmed, so first execution of a shape is always a populating run that captures a live plan"}
  - {id: A2, sev: should-fix, verdict: VERIFIED, loc: "research-log.md Decision Log (Adversarial gate revisions / A2)", anchor: "### A2 ", cert: C2, basis: "root-source-plan contract documented, recursive-broadening scoped out, limitation recorded in accessor javadoc + track"}
  - {id: A3, sev: suggestion, verdict: VERIFIED, loc: "research-log.md Decision Log (A3)", anchor: "### A3 ", cert: C3, basis: "mechanism corrected: plan captured whenever source step's query executes; downstream short-circuit yields correct null"}
  - {id: A4, sev: suggestion, verdict: VERIFIED, loc: "research-log.md Decision Log (A4)", anchor: "### A4 ", cert: C4, basis: "reset() clear + by-id null promoted from optional to required fix"}
  - {id: A5, sev: suggestion, verdict: VERIFIED, loc: "research-log.md Decision Log (A5)", anchor: "### A5 ", cert: C5, basis: "Decision 1 survives; internal coupling equals usedIndexes(), embedded-only invocation, no change needed"}
  - {id: A6, sev: suggestion, verdict: NEW, loc: "research-log.md Decision 2 (Capture the executed query's plan)", anchor: "### A6 ", cert: C6, basis: "Decision 2's standalone 'available immediately, at zero extra cost' phrasing does not back-reference the A1 replay-null limitation; a reader of Decision 2 alone is not warned"}
evidence_base: {section: "## Evidence base", certs: 6, matches: 6}
cert_index:
  - {id: C1, verdict: RESOLVED, anchor: "#### C1 "}
  - {id: C2, verdict: RESOLVED, anchor: "#### C2 "}
  - {id: C3, verdict: RESOLVED, anchor: "#### C3 "}
  - {id: C4, verdict: RESOLVED, anchor: "#### C4 "}
  - {id: C5, verdict: RESOLVED, anchor: "#### C5 "}
  - {id: C6, verdict: WEAK, anchor: "#### C6 "}
flags: [CONTRACT_OK]
-->

# Adversarial review — research log YTDB-1191 (iter 2)

Verdict: **PASS**. The revised `## Decision Log` "Adversarial gate revisions"
entry resolves all five iteration-1 findings. Both should-fix findings (A1,
A2) clear as documented, rationalized, test-covered limitations — legitimate
clears under the gate semantics, not scope expansion. A1's resolution is now
code-grounded on a fact iteration 1 never established: the result cache is
per-transaction, so the first execution of any query shape in a transaction
is always a cache-miss populating run that captures a live plan, and DNQ's
per-query-shape scan detection is observable there. The three suggestions are
addressed (A3 corrected, A4 promoted to required, A5 recorded). One new
low-value suggestion (A6) on prose cross-reference; it does not gate.

Reference-accuracy caveat: mcp-steroid was unreachable this session, so symbol
claims rest on ripgrep, not PSI. The per-transaction cache scope (A1) was
confirmed by reading `FrontendTransactionImpl` and `QueryResultCache` directly
(field ownership + class javadoc), which does not depend on find-usages
accuracy. The child-traversal rebuild leg (A2) remains grep-level, as in
iteration 1 — but A2 now resolves by documenting the limitation, so the
residual grep uncertainty no longer changes the verdict.

## Findings

### A1 [should-fix]
**Verdict**: VERIFIED
**Certificate**: C1
**Target**: iter-1 A1 — tx-result cache nulls the plan on replay; Decision 2 + Surprises item 2
**Resolution assessment**: The log folds A1 into the Decision Log as an accepted, documented limitation plus a **required** cached-query test (populating run surfaces the plan; replay surfaces null). The rationale is sound and now code-grounded: a cache-hit replay does not re-execute a plan, it replays cached rows, so a null plan on replay is semantically defensible. I verified the load-bearing premise iteration 1 left implicit — the cache is **per-transaction** (`FrontendTransactionImpl.queryResultCache` is a per-instance field cleared on commit/rollback, `FrontendTransactionImpl.java:142,229,1038-1039,1439-1447`; `QueryResultCache` javadoc: "The per-transaction store of cached query results. One instance lives on a single FrontendTransactionImpl"). Consequence: the **first** execution of any query shape within a transaction is always a cache miss → populating run → `buildView` constructs the view with a live `entry.getPlan()` (`DatabaseSessionEmbedded.java:1039,1056,1408`) → synchronous capture gets a real plan → DNQ sees the scan. Only in-transaction replays yield null. DNQ's full-scan detection is a per-query-shape property observable on that first execution, so the feature's purpose is not defeated. This is exactly the "known, rationalized, test-covered limitation" the gate accepts as a clear.
**Residual**: none that gates. The required test must assert the populating-run plan is non-null and the replay is null; the log names it explicitly.

### A2 [should-fix]
**Verdict**: VERIFIED
**Certificate**: C2
**Target**: iter-1 A2 — only the root source step's plan is captured; Surprises item 1 + draft `capturedExecutionPlan`
**Resolution assessment**: The log now documents the captured contract explicitly — the feature exposes the **primary (root source) query's** execution plan, matching the ticket's detect-the-main-query's-full-scan use case. Nested/child-traversal (`where`/`union`/`local`) scans and non-`V()`/`E()`-rooted root traversals are scoped out; the recursive-broadening path (`getStepsOfAssignableClassRecursively`) is named and deferred with a stated reason (raises which-plan / merge-plans questions). The limitation is required to be recorded in the accessor javadoc and the track. The iter-1 should-fix concern was that the limitation was undocumented and would regress silently; documenting it as a scoped contract is the correct resolution for a should-fix. The unverified child-rebuild leg (grep-level, PSI unavailable) no longer affects the verdict because the resolution documents rather than relies on nested capture.
**Residual**: confirming DNQ's actual query shapes are flat root lookups is a downstream integration check, not a research-log blocker. Worth carrying into the track's test plan.

### A3 [suggestion]
**Verdict**: VERIFIED
**Certificate**: C3
**Target**: iter-1 A3 — zero-row-via-short-circuit mechanism wording
**Resolution assessment**: Corrected as requested. The log now states the plan is captured whenever the source step's query actually executes, and that a downstream short-circuit (`limit(0)`) that never pulls the source yields a null plan, which is correct. The inaccurate "supplier always runs even for zero rows" claim is retracted.

### A4 [suggestion]
**Verdict**: VERIFIED
**Certificate**: C4
**Target**: iter-1 A4 — reset()-clear + by-id null
**Resolution assessment**: Promoted from optional refinement to a **required** fix: clear `lastExecutionPlan = null` in `YTDBGraphStep.reset()`, and ensure the by-id branch leaves it null. This removes the stale-plan hazard on traversal reuse with a branch switch — the correctness issue iteration 1 raised — and keeps the step ignorant of monitoring.

### A5 [suggestion]
**Verdict**: VERIFIED
**Certificate**: C5
**Target**: iter-1 A5 — Decision 1 survives
**Resolution assessment**: Recorded as no-change with the rationale on the log: internal `ExecutionPlan`/`ExecutionStep` coupling equals the existing `usedIndexes()` coupling, and invocation is embedded-only (no wire serialization). The challenge was a suggestion that the decision holds; recording the survival rationale closes it.

### A6 [suggestion]
**Verdict**: NEW
**Certificate**: C6
**Target**: Decision 2 — "Capture the executed query's plan, not a second EXPLAIN"
**Challenge**: Decision 2's body still reads "the plan is available immediately, before streaming, at zero extra cost" with no back-reference to the A1 replay-null limitation recorded later in the same Decision Log. A reader who consults Decision 2 in isolation (e.g., when deriving the track's contract) is not warned that "available immediately" holds only for the populating/uncached run. The two entries are consistent when read together, but the qualification lives only in the A1 entry.
**Evidence**: Decision 2 entry (`research-log.md`, "Capture the executed query's plan") vs. the A1 sub-entry under "Adversarial gate revisions"; the former carries no pointer to the latter. Low severity — no decision is wrong, only the in-place phrasing is unqualified.
**Proposed fix**: Add a one-clause back-reference in Decision 2 ("…available immediately on the executed/populating run; see the A1 limitation for cache-hit replay") so the decision reads correctly standalone. Optional; does not gate.

## Evidence base

#### C1 Challenge: iter-1 A1 resolution — documented-limitation + per-transaction cache scope
- **Chosen resolution**: accept the replay-null as a documented limitation; require a test pinning populating-run-plan-present / replay-null.
- **Best counter (why it might still gate)**: if the cache were cross-transaction, a shape's first execution in an observed transaction could be a cache hit populated elsewhere → null plan → DNQ never sees a populating run → scan missed entirely.
- **Counterargument trace**:
  1. The cache is a per-instance field on the transaction (`FrontendTransactionImpl.java:142`), resolved lazily (`1439-1447`) and cleared on commit/rollback (`229`, `1038-1039`).
  2. `QueryResultCache` javadoc confirms "per-transaction store … one instance lives on a single FrontendTransactionImpl."
  3. Therefore the first execution of any shape within a transaction is a cache miss → populating run → `buildView` uses a live `entry.getPlan()` (`DatabaseSessionEmbedded.java:1039,1056,1408`) → synchronous capture sees a real plan. The cross-transaction failure mode does not exist.
- **Codebase evidence**: `FrontendTransactionImpl.java:142,229,1038-1039,1439-1447`; `QueryResultCache.java:16-19`; `DatabaseSessionEmbedded.java:762,834,1039,1056,1408`.
- **Survival test**: RESOLVED — the documented limitation is real, bounded to in-transaction replay, and the primary purpose (first-execution scan detection) is preserved; required test locks it.

#### C2 Challenge: iter-1 A2 resolution — root-source-plan contract documented
- **Chosen resolution**: document the captured plan as the root source step's plan only; scope nested/non-root out; note recursive broadening deferred.
- **Best counter**: a future DNQ shape with a nested scan regresses silently.
- **Counterargument trace**:
  1. The limitation is now a written contract in the Decision Log + required in the accessor javadoc and track, so a nested-shape gap is a known, documented non-goal, not a silent regression.
  2. The ticket's use case is the main query's full scan, which is the root source step — the documented contract matches it.
- **Codebase evidence**: `YTDBQueryMetricsStrategy.java:25,44` (root-only wrap); grep-level on child-traversal rebuild (PSI unavailable), but no longer verdict-changing since the resolution documents rather than relies on nested capture.
- **Survival test**: RESOLVED — should-fix cleared by explicit scoping + javadoc/track record.

#### C3 Assumption test: iter-1 A3 correction
- **Claim**: the log's item-2 "supplier always runs" wording was inaccurate for downstream short-circuits.
- **Resolution**: reworded to "captured whenever the source step's query executes; downstream short-circuit → correct null."
- **Verdict**: RESOLVED — mechanism now stated correctly.

#### C4 Assumption test: iter-1 A4 promotion
- **Claim**: no `reset()` clear + by-id branch never nulling `lastExecutionPlan` → stale plan on reuse.
- **Resolution**: promoted to a required fix (reset() clear + by-id null).
- **Verdict**: RESOLVED — correctness hazard closed with a required change.

#### C5 Challenge: iter-1 A5 — Decision 1 survives
- **Claim**: internal `QueryDetails` surface is defensible.
- **Resolution**: recorded no-change with the coupling-equals-`usedIndexes()` + embedded-only rationale.
- **Verdict**: RESOLVED — survival rationale on the record.

#### C6 Challenge: Decision 2 standalone phrasing vs. A1 limitation
- **Chosen approach**: Decision 2 states "available immediately, before streaming, at zero extra cost" without an in-place qualifier.
- **Best rejected alternative**: add a back-reference to the A1 replay-null limitation.
- **Counterargument trace**:
  1. A reader consulting Decision 2 alone (deriving the track contract) sees an unqualified "always available" reading.
  2. The qualification lives only in the later A1 sub-entry.
  3. Consequence: mild — no wrong decision, only unqualified prose; cross-reference would fix it.
- **Codebase evidence**: n/a (prose-consistency finding within the log).
- **Survival test**: WEAK — the decision holds; a one-clause back-reference strengthens standalone readability. Non-gating.
