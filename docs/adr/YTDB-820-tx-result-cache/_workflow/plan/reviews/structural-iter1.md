<!-- MANIFEST
findings: 1   severity: {blocker: 0, should-fix: 0, suggestion: 1}
index:
  - {id: S1, sev: suggestion, loc: "implementation-plan.md §Component Map (FrontendTransactionImpl bullet, lines 73-78)", anchor: "### S1 ", cert: "", basis: "component-intent bullet runs 6 lines vs ~5-line budget; marginal over-budget"}
evidence_base: {section: "## Evidence base", certs: 0, matches: 0}
flags: [CONTRACT_OK]
-->

## Findings

### S1 [suggestion]
**Location**: `implementation-plan.md` → Architecture Notes → Component Map → annotated bullet for `**`FrontendTransactionImpl`**` (modified), lines 73-78.
**Issue**: The component-intent bullet spans 6 continuation lines, one over the ~5-line per-bullet budget. It packs four distinct facts (owns `queryResultCache`; the `mutationVersion` counter; the `cacheCodeDepth` re-entrancy depth counter with its UDF-in-WHERE rationale; the two `clear()` call sites) into one bullet. The `cacheCodeDepth` clause carries a parenthetical rationale ("incremented around the whole cache code path (lookup plus view iteration, where a UDF in a WHERE may re-enter `query()`)") that is design-level behavioral detail rather than a one-line statement of what changes in the component. Every other Component-Map bullet is 3-4 lines.
**Proposed fix**: Trim the `cacheCodeDepth` clause to a bare statement of the change ("the new `cacheCodeDepth` re-entrancy depth counter") and drop the inline UDF-in-WHERE rationale, which is already stated in full in the track-1 `## Context and Orientation` section and the `QueryResultCache` Component-Map bullet's "two guards" note. That returns the bullet to ~5 lines without losing any fact the execution agent needs from the map.
**Classification**: mechanical
**Justification**: Component-intent length bloat ("does any component's intent bullet exceed ~5 lines"); all BLOAT findings are `mechanical` by construction (§`mechanical` — orchestrator applies the fix without asking).

## Evidence base
