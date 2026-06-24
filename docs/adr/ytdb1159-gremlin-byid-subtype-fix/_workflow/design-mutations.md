# Design mutation log

## Mutation 1 — 2026-06-24 — phase1-creation (design.md)

**Diff summary**: Initial creation of `design.md` for YTDB-1159 and the adjacent
count id-drop bug. Covers the by-id `hasLabel` polymorphism fix (shared
`YTDBLabelMatcher` helper, key-based label-container partition in
`YTDBGraphStep.elements()`), the `YTDBGraphCountStrategy` id-guard fix, and the
test strategy (edge, multi-arg, and count-honors-id coverage plus the four committed
methods). Sections: Overview, Core Concepts, Class Design, Workflow, Bug 1, Bug 2,
Test strategy.

**Mechanical checks** (target=design): PASS (0 findings)
**Cold-read** (scope: whole-doc): PASS (0 blockers, 0 should-fix, 2 suggestions)

**Adversarial** (phase1-creation): 0 blockers, 2 should-fix (A1, A2), 2 suggestions
(A3, A4) — all addressed and re-verified RESOLVED.

**Findings**:
- should-fix (A1): by-id partition discriminator was unstated; the natural
  `addCondition == LABEL` reuse would break the by-id ↔ `YTDBHasLabelStep` invariant
  for non-eq/within label predicates. Fixed: design now mandates the label-key
  discriminator and rescopes the invariant to every label predicate shape.
- should-fix (A2): count fall-through is correct only after Bug 1; no fix-order
  constraint was stated. Fixed: added a Fix-order constraint paragraph (both fixes in
  one track, Bug 2 guard never before Bug 1 matcher) and a multi-vertex polymorphic
  count test.
- suggestion (A3): noted the matcher else-branch is unreachable on the by-id path,
  kept for `YTDBHasLabelStep` reuse.
- suggestion (A4): helper takes the predicate list for a single superclass walk.
- suggestion (cold-read): reworded the Overview roadmap to a verbed sentence.
- suggestion (cold-read): optional inline glosses for TinkerPop identifiers — not
  applied (context-clear).

**Iterations**: 1 of 3 (PASS)
