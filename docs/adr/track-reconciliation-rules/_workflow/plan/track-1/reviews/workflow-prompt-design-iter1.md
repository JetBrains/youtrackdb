<!--
MANIFEST
dimension: workflow-prompt-design
phase: 3C
level: medium
output_path: docs/adr/track-reconciliation-rules/_workflow/plan/track-1/reviews/workflow-prompt-design-iter1.md
evidence_base: { certs: 0 }
cert_index: []
flags: { evidence_trail_exempt: true, exempt_reason: "no refutation or certificate phase to persist" }
index:
  - id: WP1
    sev: Recommended
    anchor: "### WP1 [Recommended] \"new fixable finding\" is an undefined term in the no-progress termination predicate"
    loc: .claude/workflow/track-code-review.md:718
    cert: n/a
    basis: deterministic decision rules
  - id: WP2
    sev: Minor
    anchor: "### WP2 [Minor] Threshold conjunct (a) and the REGRESSION carve-out point opposite directions for the same iteration"
    loc: .claude/workflow/track-code-review.md:716
    cert: n/a
    basis: deterministic decision rules
-->

## Findings

### WP1 [Recommended] "new fixable finding" is an undefined term in the no-progress termination predicate

**File**: `.claude/workflow/track-code-review.md` (line 716-722)
**Axis**: deterministic decision rules
**Cost**: non-reproducible escalate-vs-continue decision when a gate-check returns a new low-severity finding

**Issue**: The no-progress threshold turns on three conjuncts, the third being "surfaces no new **fixable** finding," with the inverse "one new fixable finding ... is progress and the loop continues." The word "fixable" is load-bearing — it is the difference between escalating to the user and spinning another iteration — but it is never defined anywhere in the staged files (grep finds it only in these two lines). The dimensional gate-check template (`review-iteration.md` §Dimensional-review gate-check budget) lets a re-spawned agent surface up to three **new** findings of *any* severity, including `suggestion`. A fresh orchestrator reading "fixable" with no definition can resolve it two ways:

- Narrow: "fixable" = blocker or should-fix (the severities this loop actually acts on). A new `suggestion` is then not progress, and a stuck loop that only emits fresh suggestions correctly escalates.
- Broad: "fixable" = any finding I could in principle fix (includes `suggestion`). A new `suggestion` then counts as progress and the loop continues — even at `low`/`medium`, where `suggestion` (and at `low`, even `should-fix`) never drives iteration.

The broad reading lets an essentially-stuck track dodge no-progress escalation indefinitely on a drip of new suggestions, which contradicts the section's own intent ("escalate the moment an iteration clears nothing"). Because the predicate is the sole termination control on every uncapped loop, the ambiguity is not cosmetic — it changes whether the orchestrator escalates.

**Suggestion**: Pin the term to the severities the loop iterates on. Replace "no new fixable finding" / "one new fixable finding" with an explicit gloss, e.g. *"surfaces no new finding at a severity this loop iterates on (a new `blocker` at any level, or a new `should-fix` at `medium`/`high`); a new `suggestion`, or a new `should-fix` at `low`, is not progress."* Stating the severity set inline removes the two-way reading without adding machinery.

### WP2 [Minor] Threshold conjunct (a) and the REGRESSION carve-out point opposite directions for the same iteration

**File**: `.claude/workflow/track-code-review.md` (line 716-722)
**Axis**: deterministic decision rules
**Cost**: a reader must hold two rules that superficially conflict before resolving them, raising the chance of a mis-execution on a REGRESSION iteration

**Issue**: The threshold's first conjunct requires `STILL OPEN` "for every finding carried into it." A `REGRESSION` verdict on a carried finding is neither `STILL OPEN` nor a clear, so on a REGRESSION iteration conjunct (a) is false → by the literal threshold the iteration is *not* no-progress → "the loop continues." The immediately following sentence then says a `REGRESSION` "is always progress-negative and escalates immediately." The two statements point opposite ways for the same iteration; the text resolves it (the carve-out wins), but only after the reader notices the conflict and applies the override. The conjunctive structure makes a fresh orchestrator do extra disambiguation work on the one verdict where getting it wrong (continuing instead of escalating) is most costly.

**Suggestion**: Lift `REGRESSION` out of the conjunction as a guard that runs first, e.g. *"A `REGRESSION` on any carried finding escalates immediately (it forces a `FAIL` under existing verdict handling), short-circuiting the no-progress test below. Otherwise, an iteration makes no progress when ..."* — so the three-conjunct test only ever sees the no-REGRESSION case it was written for.

## Evidence base
