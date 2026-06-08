<!--
MANIFEST
dimension: workflow-instruction-completeness
target: Track 1 — Overlap-aware packing as an advisory tie-breaker
range: 98c5dd4719..HEAD
verdict: PASS
counts: { blocker: 0, should_fix: 0, suggestion: 1, total: 1 }
evidence_base: 1
cert_index: [C1]
flags: []
index:
  - id: WI1
    sev: suggestion
    anchor: "#wi1-suggestion"
    loc: ".claude/workflow/prompts/structural-review.md:213-222"
    cert: C1
    basis: judgment
-->

## Findings

### WI1 [suggestion] Overlap-split trigger absent from the `design-decision` triage enumeration

- File: `.claude/workflow/prompts/structural-review.md` (line 213-222)
- Axis: phase output → next-phase input
- Cost: a reviewer classifying strictly from the `design-decision` trigger list could fail to escalate an undocumented overlap-split, weakening the backstop the criterion promises
- Issue: the new TRACK SIZING criterion routes an undocumented non-adjacent
  overlap-split to a `design-decision` finding, "the same class and severity as
  an undocumented out-of-bounds track." But the `design-decision` triage
  enumeration the reviewer consults when classifying (`### design-decision`,
  lines 429-460) lists only the three out-of-bounds *footprint* cases under its
  **Track sizing** bullet (over the ceiling, under the floor and folding,
  stopped below the ceiling with a mergeable unit unpacked). The overlap-split
  case is not named in that enumeration. The two halves of the spec are not
  fully dovetailed: the criterion asserts membership in a class whose
  authoritative trigger list does not yet enumerate it.
- Suggestion: add the overlap-split case to the **Track sizing** trigger bullet
  in `### design-decision` (or add a sibling bullet), e.g. "...or two
  non-adjacent tracks sharing in-scope files with no written reason for the
  split," so the triage list a reviewer reads to classify matches the criterion
  that produces the finding. Severity is suggestion, not should-fix, because the
  criterion text itself states the full disposition inline ("a `design-decision`
  finding ... A documented split passes"), so a reviewer reading the criterion
  in place still gets a complete instruction; the gap is only in the secondary
  cross-reference.

## Evidence base

#### C1 — overlap-split disposition vs the `design-decision` trigger list

Both branches of the new criterion are individually well-formed:
documented split passes, undocumented escalates (structural-review.md
lines 219-222). The criterion's escalation target (`design-decision`)
is a real, defined triage path with a complete handling rule
(escalate to user, lines 426-460). What is missing is the
forward-link: the `design-decision` enumeration's **Track sizing**
bullet (lines 435-441) enumerates exactly three footprint conditions
and closes with "A documented out-of-bounds track is not a finding" —
it does not mention overlap. The criterion at lines 213-222 claims the
new finding is "the same class and severity as an undocumented
out-of-bounds track," which is true of the *handling* (escalate to
user) but not reflected in the *trigger enumeration*. A reviewer who
classifies by matching against the trigger list rather than by reading
the criterion in place would not find the overlap-split case there.
This is a dovetail gap between the producer (the TRACK SIZING
criterion) and the consumer (the classification enumeration), not a
stranding gap — the inline criterion text is self-complete — hence
suggestion severity.

Confirmed complete, no finding (one line each):
- Packing-order rule (planning.md 451-461): overlap branch and no-overlap fallback ("pack and maximize anyway ... exactly as *Maximize first* says") both stated; subordination explicit.
- Cut-seam rule (planning.md 463-472): dependency-boundary-primary, least-shared-seam, and cannot-co-locate→adjacent branches all stated; "wins any disagreement" resolves the contention case.
- Overlap-split justification (planning.md 474-481): documented-passes / undocumented-escalates dispositions both stated and routed to the named structural-review check.
- Step merge ordering (track-review.md 787-796): no-overlap case absorbed by the existing "merge available work related or not"; explicitly scoped as a which-unit-first tie-breaker that "does not relax the fill target or the closed under-fill set."
- Adjacency-is-not-a-merge caveat (track-review.md 797-805): terminal statement, no actor/follow-through owed; it is a clarifying invariant, not an instruction.
- "non-adjacent" reviewer input: track ordering is loaded from the plan checklist (structural-review.md line 168), so adjacency is determinable by the reviewer — the criterion's input has a producer.
