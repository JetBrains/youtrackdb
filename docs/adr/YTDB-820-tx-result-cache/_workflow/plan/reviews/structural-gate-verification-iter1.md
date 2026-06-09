<!-- MANIFEST
findings: 0   severity: {blocker: 0, should-fix: 0, suggestion: 0}
index: []
evidence_base: {section: "## Evidence base", certs: 0, matches: 0}
verdicts:
  - {id: S1, prior_sev: suggestion, verdict: VERIFIED, cert: "#### Verify S1: FrontendTransactionImpl component-intent bullet over budget"}
overall: PASS
flags: [CONTRACT_OK]
-->

## Verification certificates

#### Verify S1: FrontendTransactionImpl component-intent bullet over budget
- **Original issue**: The `FrontendTransactionImpl` Component-Map bullet spanned 6 continuation lines (one over the ~5-line budget) because the `cacheCodeDepth` clause carried an inline UDF-in-WHERE rationale parenthetical that is design-level behavioral detail, not a one-line statement of what the component changes.
- **Fix applied**: Removed the inline UDF-in-WHERE parenthetical from the `cacheCodeDepth` clause; the bullet now states the depth counter as a bare change ("the new `cacheCodeDepth` re-entrancy depth counter").
- **Re-check**:
  - Plan location: `implementation-plan.md` → Architecture Notes → Component Map → `**FrontendTransactionImpl**` bullet, lines 73-77.
  - Current state: bullet is 5 lines and names all four facts the execution agent needs — owns `queryResultCache` (lazy, enabled-gated); the monotonic `mutationVersion` counter bumped in `addRecordOperation`; the new `cacheCodeDepth` re-entrancy depth counter; the `clear()` calls in `beginInternal` and `clearUnfinishedChanges` (the single tx-end sink). The UDF-in-WHERE rationale is gone.
  - Criteria met: per-bullet length now within the ~5-line budget; no fact lost (counter name, both `clear()` call sites, and tx-end-sink note all retained).
- **Regression check**: The trimmed rationale was the only place the parenthetical lived in this bullet, but the `cacheCodeDepth` guard semantics are still carried independently elsewhere, so the trim orphaned nothing. Checked: the `QueryResultCache` Component-Map bullet (lines 78-81) still pairs the lookup-level `inFlightLookup` flag with "the tx-level `cacheCodeDepth` depth counter per CR1 — two guards"; track-1 `## Context and Orientation` (line 53) still introduces the `cacheCodeDepth` depth counter as the CR1 resolution; track-1 `## Interfaces and Dependencies` (line 207) still lists `cacheCodeDepth` re-entrancy depth counter alongside `getQueryResultCache` and the `clear` hooks. Clean.
- **Verdict**: VERIFIED

## Findings

## Evidence base
