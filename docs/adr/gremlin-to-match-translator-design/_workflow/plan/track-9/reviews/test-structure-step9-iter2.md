<!-- workflow-sha: d2dfcc2d44fabd3ac76c5fd7620f1e6013675ad9 -->
<!-- MANIFEST
kind: gate-check
findings: 0   severity: {blocker: 0, should-fix: 0, suggestion: 0}
verdicts:
  - {id: TS1, verdict: VERIFIED, anchor: "### TS1 "}
  - {id: TS2, verdict: VERIFIED, anchor: "### TS2 "}
  - {id: TS3, verdict: VERIFIED, anchor: "### TS3 "}
  - {id: TS4, verdict: VERIFIED, anchor: "### TS4 "}
  - {id: TS6, verdict: VERIFIED, anchor: "### TS6 "}
index: []
flags: [CONTRACT_OK]
-->
# test-structure Review (gate check) — step 9, iteration 2

Diff under check: `c252146ba5~1..a5f38f1cc9`, restricted to `core`.

## Verdicts

- **TS1: VERIFIED** — the sub-walk arm is now `subWalkPropertiesForm_stillTranslatesAndKeepsThePresenceFilter` over `and(values(age), values(name))`, which reaches the escape: `AndStepRecogniser` routes every child through `ConnectiveStepSupport.walkAcceptedChildren` → `ctx.walkChild` with no presence shortcut, because the only two shortcuts (`TraversalFilterStepRecogniser.presenceKey`, `NotStepRecogniser.hasNotPresenceKey`) each require a single `PropertiesStep` child and neither matches. Dropping the `requireProjectedProperty` conjunct makes the AND commit an empty filter map and return all three seeded vertices against native's Alice; disabling the escape flips it to `boundaryOn 0`. The `assertRewrittenToElementForm` premise closes the fork-upgrade hole TS1 named. The old `where(values(age))` case survives, retitled `whereValuesPresence_matchesNativeThroughHasKeyDesugar` with the desugar named in its Javadoc. (grep-only)
- **TS2: VERIFIED** — `ByModulatorTranslatorTest` gained both cases, built through `postStrategyModulator`, which runs `applyStrategies()` with the translator off and pulls the group child, so the bodies are what production delivers; each asserts its `PropertyType` premise up front. Widening `classifyKey`'s line-124 `isSingleValueProperty` to `isSingleKeyProperty` makes the key-side element form resolve and reddens the `isEmpty()` assertion, and the value-side count case reddens under the pre-fix VALUE-only predicate at line 187. (grep-only)
- **TS3: VERIFIED** — one method per escape (`countConsumedPropertiesForm_stillTranslates`, `subWalkPropertiesForm_…`), so the two branches of `elementFormIsUnobserved` report independently; the duplication with `countAfterValues_countsOnlyKeyBearers` is resolved by a Javadoc pointer.
- **TS4: VERIFIED** — all three inline toggles now go through `withTranslatorOn` / `withTranslatorOff`, delegating to `withTranslator(boolean, Runnable)`, which captures `translatorEnabled()` and restores it in `finally`. The only remaining raw `setTranslatorEnabled` calls sit inside those helpers and inside `assertEquivalentInternal`, which was already capture-and-restore.
- **TS6: VERIFIED** — renamed to `metaPropertyFilterThroughProperties_declinesToNative`, and `seedMetaPropertyGraph` carries the third vertex TS6 asked for (peter: top-level `acl=private`, no `friendWeight`) plus a josh with `acl=public` on the property, so the two placements of the filter select different rows. The same fixture is what makes `metaPropertyFilterInSubWalk_declinesInEveryCombinator`'s `containsExactly("marko")` a real discriminator.

## Summary

PASS
