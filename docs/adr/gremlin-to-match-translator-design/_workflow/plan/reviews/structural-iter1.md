<!-- MANIFEST
findings: 2   severity: {blocker: 0, should-fix: 1, suggestion: 1}
index:
  - {id: S1, sev: should-fix, loc: "implementation-plan.md § Checklist (Track 6 entry — Strategy refresh line)", anchor: "### S1 ", cert: "", basis: "Track 6 strategy-refresh line asserts a MultiPlanMatchStep-inherits-YTDBMatchPlanStep-ctor premise and three order-less post-process flags that the D8-revised split overturned"}
  - {id: S2, sev: suggestion, loc: "implementation-plan.md § Architecture Notes / Invariants (idempotency bullet)", anchor: "### S2 ", cert: "", basis: "idempotency invariant names only YTDBMatchPlanStep; post-Track-8 the scan also detects MultiPlanMatchStep per D7 rationale"}
evidence_base: {section: "## Evidence base", certs: 0, matches: 0}
cert_index: []
flags: [CONTRACT_OK]
-->

## Findings

### S1 [should-fix]
**Location**: `implementation-plan.md` § Checklist, Track 6 entry — the `**Strategy refresh:** ADJUST` line (plan lines ~506–511).
**Issue**: The strategy-refresh line was written when Track 6 completed, *before* the 2026-07-27 split (D8 revised), and it still asserts two premises the split overturned, so it now contradicts the current canonical Track 7/8 descriptions:
1. "`MultiPlanMatchStep` inherits the post-refactor single-`ResultShaping` `YTDBMatchPlanStep` constructor." — D8 revised explicitly drops the `extends YTDBMatchPlanStep` premise: `YTDBMatchPlanStep` is `final` with private machinery, constructors are not inherited, and `MultiPlanMatchStep` now **extends the Track 7 boundary base** (Track 7 `## Interfaces and Dependencies`, Track 8 Purpose, plan D8 "Revised decision"). There is no inherited `YTDBMatchPlanStep` constructor.
2. "Track 7's three list-shaping post-process flags (`unfoldOutput` / `reverseOutput` / `tailLimit`)." — Track 7's `## Decision Log` and `## Context and Orientation` now carry an **ordered `List` of list-shaping ops applied in declared order**, explicitly rejecting order-less booleans because they "cannot encode `reverse().unfold()` vs `unfold().reverse()`." The three-flag model the refresh line names no longer exists.

Both halves mislead an execution agent that reads the plan checklist (loaded every `/execute-tracks` session) at Track 7/8 startup: the line points at a superseded base-inheritance shape and a superseded flag carrier.
**Proposed fix**: Reconcile the Track 6 strategy-refresh line to the post-split design — remove the "`MultiPlanMatchStep` inherits the … `YTDBMatchPlanStep` constructor" clause (state that `MultiPlanMatchStep` extends the Track 7 boundary base, landed in Track 8), and replace "three list-shaping post-process flags" with the ordered-`List` post-process carrier (Track 7). Alternatively, mark the line superseded by the 2026-07-27 split with a pointer to D8. Preserve only the still-live guidance about carrying shaping state on `ResultShaping` rather than individual `WalkerContext` fields (Track 7 lists the `ResultShaping`-field-vs-adjacent-type choice as an open realization decision).
**Classification**: design-decision
**Justification**: §`design-decision` "Track contradictions — Track 1 assumes X, Track 3 assumes not-X … a design call"; how much of the refresh line's `ResultShaping`-extension hint survives the split is a planner judgment, so escalate per the "when in doubt … choose `design-decision`" rule.

### S2 [suggestion]
**Location**: `implementation-plan.md` § Architecture Notes → `### Invariants`, the idempotency bullet (plan lines ~344–346: "The strategy is idempotent: re-applying on a traversal already containing `YTDBMatchPlanStep` is a no-op.").
**Issue**: The idempotency invariant names only `YTDBMatchPlanStep`, but Track 8 broadens the D7 idempotency scan to the Track 7 boundary base so a re-applied strategy also detects a `MultiPlanMatchStep` union boundary (D7 Rationale: "detects both `YTDBMatchPlanStep` and the `union` `MultiPlanMatchStep`"; Track 8 `## Interfaces and Dependencies` lists the scan broadening). Invariant #1 in the same section already covers both boundary steps, so this narrower bullet is a split-introduced staleness rather than a design gap.
**Proposed fix**: Broaden the bullet to "re-applying on a traversal already containing any boundary step (`YTDBMatchPlanStep` or `MultiPlanMatchStep`) is a no-op," aligning it with D7 and Invariant #1.
**Classification**: mechanical
**Justification**: §`mechanical` "a single unambiguous edit that doesn't change plan intent" — the broadened scan is already decided in D7; only the summary invariant text lags.

## Evidence base

No certificates — this is a plan-internal structural review (no codebase read, per §Workflow Context). `certs: 0`.
