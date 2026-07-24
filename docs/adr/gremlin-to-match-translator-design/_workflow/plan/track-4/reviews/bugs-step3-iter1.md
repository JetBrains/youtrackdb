<!-- MANIFEST
findings: 1   severity: {blocker: 0, should-fix: 1, suggestion: 0}
index:
  - {id: BG1, sev: should-fix, loc: WalkerContext.java:220-234, anchor: "### BG1 ", cert: C1, basis: "Polymorphic hasLabel(L) + Text/regex predicate on a non-String property declared only on an included subclass escapes the type gate: translator returns a result where native throws — confirmed empirically"}
evidence_base: {section: "## Evidence base", certs: 4, matches: 1}
cert_index:
  - {id: C1, verdict: CONFIRMED, anchor: "#### C1 "}
  - {id: C2, verdict: REFUTED, anchor: "#### C2 "}
  - {id: C3, verdict: REFUTED, anchor: "#### C3 "}
  - {id: C4, verdict: NOTE, anchor: "#### C4 "}
flags: [CONTRACT_OK]
-->

## Findings

### BG1 [should-fix] Non-String Text type gate misses polymorphic-included subclasses

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/WalkerContext.java` (line 220-234, `isNonStringProperty`); reached from `HasStepRecogniser.java` (line 434-436, the `typeGate` lambda) and `EdgeHopRecogniser.java` (line 111-112).

**Issue**: The non-String `Text`/regex decline (this step's BG2 focal point) resolves the property type only against the *named* class and its superclasses, but polymorphic `hasLabel(L)` matches `L` **and its subclasses**. A non-String property declared on an included subclass — and not on `L` — escapes the gate, so the adapter translates the string predicate instead of declining. The translated filter returns rows/empty where native throws, diverging.

Concrete failure: `Person` (parent), `Employee extends Person`, with `age` declared `INTEGER` on `Employee` only. In the default polymorphic mode:

```
g.V().hasLabel("Person").has("age", TextP.containing("3"))
```

- `isNonStringProperty("Person", "age")` calls `schema.getClass("Person").getProperty("age")`. `getProperty` walks superclasses only (per the method's own comment, line 224-225), so `age` — declared on the `Employee` *subclass* — is not found; the gate returns `false` and the adapter emits `age CONTAINSTEXT '3'`.
- Native matches `Person` + `Employee` polymorphically. On the `Employee` row `age=30` (an `Integer`), native's `Text.containing` casts to `String` → `ClassCastException`; native **errors**.
- Translated: `SQLContainsTextCondition.evaluate` returns `false` on a non-String left operand (line 47-48, no throw), so the `Employee` row is excluded and the query **returns without error**.

This contradicts the step's stated design intent (GremlinPredicateAdapter class Javadoc: "Declining keeps the traversal on the native pipeline, which errors as native does"). The same gap exists on the `EdgeHopRecogniser` edge path (line 111-112 keys the gate on the resolved edge class only) — narrower in practice since edge-class hierarchies are uncommon, but the mechanism is identical.

Non-polymorphic mode is **not** affected: `hasLabel(L)` there adds an exact `@class = 'L'` leaf filter (`HasStepRecogniser.java` line 466-468), so subclass rows never reach the predicate.

**Evidence**: Empirically reproduced against a `Person`/`Employee`(`age` INTEGER on `Employee` only) hierarchy in default polymorphic mode (scratch equivalence test, now removed):
```
boundarySteps(translated)=1        // translator engaged — gate did NOT fire
translated(ON)=RETURNED size=0     // no error
native(OFF)=THREW ClassCastException
```
Code trace: `WalkerContext.isNonStringProperty` → `SchemaClass.getProperty` (superclass walk, not subclass) confirmed; `SQLContainsTextCondition.evaluate` returns `false` (not throw) on non-String (line 47-51). `SchemaClass.getAllSubclasses()` exists (`SchemaClass.java` line 135).

**Refutation considered**: (1) Could `CONTAINSTEXT` also throw on a non-String, making both pipelines error (no divergence)? No — verified it returns `false`. (2) Could `getProperty` already resolve subclass declarations? No — its comment and behaviour walk superclasses only. (3) Could a higher guard decline this shape? No — the empirical run shows the translator engages (1 boundary step) and returns. (4) Is it the accepted schema-less best-effort case? No — the schema *does* declare the type (on the subclass); the gate looks at the wrong class. Confirmed a real divergence, distinct from the documented schema-less best-effort limitation.

**Suggestion**: In polymorphic mode, extend `isNonStringProperty` to also inspect subclasses — decline when `className` **or any of its subclasses** (`clazz.getAllSubclasses()`) declares `propertyKey` with a non-String type. Non-polymorphic mode can keep the current named-class-only check (the `@class` leaf filter already excludes subclass rows). Add a polymorphic `hasLabel(parent).has(subclassOnlyNonStringProp, TextP...)` equivalence/decline case to `PredicateTraversalEquivalenceTest`, and a companion case on the edge path in `EdgeHopRecogniserTest`.

## Evidence base

#### C1 CONFIRMED — polymorphic subclass type-gate gap (BG1)
Survived refutation: gate resolves only `className` + superclasses via `getProperty`; polymorphic `hasLabel(L)` includes subclasses; `CONTAINSTEXT` returns `false` (not throw) on non-String; reproduced empirically (native throws, translator returns size=0, boundary engaged). Distinct from the accepted schema-less best-effort case because the schema declares the type on the subclass.

#### C2 REFUTED — hasId(a, a) duplicate would emit the vertex twice
Claim: `translateHasId` calls `StartStepRecogniser.toRecordIds` with no duplicate decline (`HasStepRecogniser.java` line 519), so `hasId(a, a)` builds `@rid IN [ridA, ridA]`; `toPromotedSqlRidList` (`MatchExecutionPlanner.java` line 4831-4858) does not dedup, `SQLCollection.execute` returns a plain `ArrayList` (no dedup, `SQLCollection.java` line 64-70), and `FetchFromRidsStep` iterates the RID list one-to-one (`FetchFromRidsStep.java` line 46) — so the promoted pinned-RID list `[ridA, ridA]` would fetch and emit vertex `a` twice, diverging from native `hasId` set-membership (once).

Refuted empirically: `PredicateTraversalEquivalenceTest` (15/15 green), including `hasIdDuplicate_isSetMembership_matchesNative`, shows translator-on and native both return the vertex once. The MATCH engine collapses the duplicate root fetch to a single row per distinct alias binding, so the pinned-RID duplicate does not surface. R5 (the `~id` seam not inheriting the seek-only duplicate decline) is correct as implemented. Not a bug.

#### C3 REFUTED — hasLabel(L) re-type + pinned RID drops the class in polymorphic mode
Claim: `HasStepRecogniser` re-types the boundary node to `L` (`ctx.addNode`, line 465) but in polymorphic mode adds no `@class` WHERE filter; a co-located pinned RID (from `hasId`, or from a `g.V(ids)` start step AND-composed onto the same alias) is promoted by `promoteStaticRidsFromFilters`, after which `createSelectStatement` prefers the RID list and drops the class (`MatchExecutionPlanner.java` line 4644-4648), and `MatchFirstStep` performs no class re-check (`MatchFirstStep.java` line 90-117) — so `g.V(ids).hasLabel(L)` / `hasLabel(L).hasId(x)` would return an out-of-class vertex that native excludes.

Refuted empirically: scratch test `g.V(personId, companyId).hasLabel("Person")` in default polymorphic mode returned only the `Person` vertex under both translator-on and translator-off (`offIds=[#19:0]`, `onIds=[#19:0]`, boundary engaged). The MATCH engine enforces the node class even with pinned RIDs through a path not visible in `createSelectStatement` alone; the sequential trace of the RID-fetch scan was incomplete. Not a bug.

#### C4 NOTE — scope notes (non-findings, out-of-dimension)
- **Stale field comment (code-quality dimension, not a runtime bug).** `WalkerContext.aliasFilters` Javadoc (line 37-39) still says "entries here override builder entries on the same alias", but this step changed both `putAliasFilter` (line 293-306) and `GremlinStepWalker.buildResult` (diff line 288-290) to AND-compose. The comment now contradicts the code. No execution impact; flagged for `review-code-quality`.
- **Edge-path coverage gap (test-quality dimension).** The non-String `Text` decline is wired into `EdgeHopRecogniser` (typeGate, line 111-112) per the BG2 focal point, but this diff adds no test exercising a non-String edge-property decline; the new tests cover only the `HasStep`/adapter path. Flagged for `review-test-quality`.
- **Concurrency backstop.** No triage gap: all state added or touched by this step is per-walk (`WalkerContext`) or stateless shared singletons (`MatchWhereBuilder WHERE`, recogniser `INSTANCE`s). No shared mutable state or locks introduced. `review-concurrency` is not needed for this diff.
