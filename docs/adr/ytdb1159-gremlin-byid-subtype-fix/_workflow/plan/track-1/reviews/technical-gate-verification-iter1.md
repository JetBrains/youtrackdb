<!-- MANIFEST
findings: 0   severity: {blocker: 0, should-fix: 0, suggestion: 0}
index: []
verdicts:
  - {id: T1, verdict: VERIFIED}
  - {id: T2, verdict: VERIFIED}
  - {id: T3, verdict: VERIFIED}
overall: PASS
flags: [CONTRACT_OK]
-->

# Technical gate-verification — Track 1, iteration 1

Phase A technical re-check of the three iteration-1 findings (T1 should-fix,
T2/T3 suggestions). All three were accepted and folded into the track file as
implementer-guidance prose; none were rejected. This pass confirms the guidance
is present and technically correct against the codebase, and that the
single-step decomposition introduced no new issue. Overall: PASS.

## Verdicts

#### Verify T1: by-id branch needs an unchecked cast into the matcher's predicate list
- **Original issue**: `HasContainer.getPredicate()` returns `P<?>`, so the by-id
  branch cannot pass a label container's predicate straight into the matcher's
  `List<P<? super String>>` parameter — it needs an unchecked cast, mirroring the
  existing fold site.
- **Fix applied**: track-1.md `## Plan of Work` → "Implementer notes from Phase A
  technical review", bullet **T1 (should-fix) — predicate cast**. The note states
  the return type is `P<?>`, instructs the by-id branch to cast each predicate via
  `(P<? super String>) hc.getPredicate()` exactly as `YTDBGraphStepStrategy.java:137`
  does, to expect-and-suppress the unchecked warning the same way, and not to change
  the matcher signature to dodge it.
- **Re-check**:
  - Track-file location: `track-1.md:118-125` (the T1 bullet).
  - Cited code location: `YTDBGraphStepStrategy.java:136-137` reads
    `//noinspection unchecked` then `labelPredicates.add((P<? super String>) hc.getPredicate());`
    — the cast pattern the note tells the implementer to copy is present verbatim at
    the cited line. The iter1 technical review already established
    `public final P<?> getPredicate()` for the forked `HasContainer`, so the
    return-type premise holds.
  - The matcher signature the note must not change is `YTDBLabelMatcher.matches(Element,
    List<P<? super String>>, boolean)`, recorded in `## Interfaces and Dependencies`
    (`track-1.md:204`) — consistent with the cast target.
  - Criteria met: technical correctness (the cast is required and the cited reference
    is accurate) and actionability (the note gives the exact expression, the warning-
    suppression treatment, and the anti-pattern to avoid).
- **Regression check**: checked the matcher signature in `## Interfaces and
  Dependencies` and the by-id partition step (`## Plan of Work` step 3) for
  consistency with the cast guidance — clean. The note does not contradict step 3,
  which keys the partition on `T.label.getAccessor().equals(container.getKey())`; the
  cast is applied to the predicate of an already-identified label container.
- **Verdict**: VERIFIED

#### Verify T2: call the matcher once per label container and AND results
- **Original issue**: collapsing multiple label containers into one OR-list would
  turn `hasLabel("A").hasLabel("B")` (AND across two folded containers) into a union,
  inverting the semantics.
- **Fix applied**: track-1.md `## Plan of Work` → "Implementer notes", bullet **T2
  (suggestion) — AND across containers** (`track-1.md:126-130`): call the matcher once
  per label container and AND the results (`labelContainers.stream().allMatch(...)`);
  do not collapse into a single OR-list; OR semantics live only within one
  container's predicate list (a single multi-arg `hasLabel("A","B")`).
- **Re-check**:
  - Current state: the T2 note states the AND-across / OR-within rule explicitly and
    matches `## Plan of Work` step 3 ("ANDing across label containers") and design.md
    D2 (per-container call, AND the results; OR within a one-element-or-multi-arg
    list). The semantics are internally consistent across track step 3, the T2 note,
    and decision record D2.
  - Criteria met: the guidance is correct (AND across folded `hasLabel`s is the
    TinkerPop contract) and is captured as the implementer note the orchestrator was
    asked to add.
- **Regression check**: checked `## Plan of Work` step 3 and plan D2 — both already
  carry the AND-across / OR-within distinction, so the note reinforces rather than
  contradicts them. Clean.
- **Verdict**: VERIFIED

#### Verify T3: leave `createClassIterator` alone
- **Original issue**: `YTDBGraphStep.createClassIterator` also reads `~label`
  containers; a naive by-id partition keyed on the label accessor could be thought to
  overlap with it, but `createClassIterator` discriminates on the
  `YTDBSchemaClass.LABEL` sentinel value and serves the schema-class meta path, so it
  is out of scope and must stay unchanged.
- **Fix applied**: track-1.md `## Plan of Work` → "Implementer notes", bullet **T3
  (suggestion) — leave `createClassIterator` alone** (`track-1.md:131-135`): states it
  discriminates on the `YTDBSchemaClass.LABEL` sentinel value (not the key), serves
  the schema-class meta path, is out of scope, and that the by-id partition keys on the
  label accessor without interfering with it.
- **Re-check**:
  - Cited code location: `YTDBGraphStep.java:139-149` — `createClassIterator` guards
    on `T.label.getAccessor().equals(hasContainer.getKey()) &&
    YTDBSchemaClass.LABEL.equals(hasContainer.getValue())`, i.e. key AND the LABEL
    sentinel *value*, then iterates schema classes. This confirms the note's claim that
    the discriminator is the LABEL value and the path is the schema-class meta path.
  - The by-id partition (step 3) keys on `T.label.getAccessor().equals(container.getKey())`
    alone; it does not touch `createClassIterator`, which is reached only when the value
    also equals the LABEL sentinel. No overlap, as the note asserts.
  - `createClassIterator` is also listed nowhere in the in-scope file edits beyond the
    one `YTDBGraphStep` file, and the note explicitly scopes it out — consistent.
  - Criteria met: the out-of-scope rationale is technically accurate and recorded.
- **Regression check**: confirmed the by-id partition guidance (step 3 / T2) keys on
  the accessor only and never on the LABEL value, so the two `~label` readers stay
  disjoint. Clean.
- **Verdict**: VERIFIED

## Decomposition regression check

The track decomposes to a single step (`## Concrete Steps:138`), which folds all
five sub-actions (matcher, two call sites, count guard, tests) into one commit and
references the T1/T2/T3 notes ("See `## Plan of Work` for the T1/T2/T3 implementer
notes"). The roster line carries the cross-reference, so an implementer reading only
the step still reaches the cast/AND/out-of-scope guidance. The fix-order constraint
(matcher before count guard) holds by construction in a single commit, as the
single-step rationale comment (`:140-144`) notes. No new issue introduced by the
decomposition.

## Findings

(none — pure-verdict pass)

## Summary

PASS. T1, T2, and T3 are all VERIFIED: the cast guidance is present and matches the
real fold site at `YTDBGraphStepStrategy.java:137`, the AND-across-containers rule is
captured and consistent with step 3 and decision record D2, and the
`createClassIterator` out-of-scope rationale is accurate against the code
(`YTDBGraphStep.java:139-149`, LABEL-value sentinel). The single-step decomposition
cross-references the notes and preserves the fix-order constraint; no new findings.
