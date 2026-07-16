<!-- MANIFEST
findings: 2   severity: {blocker: 0, should-fix: 1, suggestion: 1}
index:
  - {id: BG1, sev: should-fix, loc: GremlinPredicateAdapter.java:282, anchor: "### BG1 ", cert: C1, basis: "startingWith path throws IllegalArgumentException on all-max-code-point prefix, violating the never-throws contract; mitigated by the strategy RuntimeException net to a native decline"}
  - {id: BG2, sev: suggestion, loc: GremlinPredicateAdapter.java:274, anchor: "### BG2 ", cert: C2, basis: "positive Text predicates translate on a non-String field via the live EdgeHopRecogniser path; native errors, translator returns rows; Step-3 schema decline must cover this seam"}
evidence_base: {section: "## Evidence base", certs: 9, matches: 7}
cert_index:
  - {id: C1, verdict: WRONG,   anchor: "#### C1 "}
  - {id: C2, verdict: WRONG,   anchor: "#### C2 "}
  - {id: C3, verdict: MATCHES, anchor: "#### C3 "}
  - {id: C4, verdict: MATCHES, anchor: "#### C4 "}
  - {id: C5, verdict: MATCHES, anchor: "#### C5 "}
  - {id: C6, verdict: MATCHES, anchor: "#### C6 "}
  - {id: C7, verdict: MATCHES, anchor: "#### C7 "}
  - {id: C8, verdict: MATCHES, anchor: "#### C8 "}
  - {id: C9, verdict: MATCHES, anchor: "#### C9 "}
flags: [CONTRACT_OK]
-->

## Findings

### BG1 [should-fix] `translateText` throws on a pathological prefix, breaking the adapter's "never throws" contract

The `startingWith` / `notStartingWith` cases guard the empty-prefix throw but not the max-code-point throw, so a valid (if pathological) `TextP.startingWith` predicate makes `toFilter` throw instead of returning `null`.

**File:** `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/GremlinPredicateAdapter.java` (lines 282-284)

**Issue:** Both `startingWith` and `notStartingWith` guard only the empty case (`string.isEmpty() ? null : …`) and then call `WHERE.startsWith(key, string)` for every non-empty prefix. `MatchWhereBuilder.startsWith` throws `IllegalArgumentException` for a second reason too: a prefix with no finite exclusive upper bound — one whose trailing code points are all `Character.MAX_CODE_POINT` (`U+10FFFF`). `has(key, TextP.startingWith("􏿿"))` (a single max code point) reaches `incrementLastCodePoint`, strips the trailing code point, finds an empty head, and throws. The class Javadoc and the `toFilter` Javadoc both state "Never throws," and every other un-buildable input in this class returns `null`.

**Evidence:** `MatchWhereBuilder.startsWith` → `incrementLastCodePoint` (MatchWhereBuilder.java:354-369): for a lone max code point `head.isEmpty()` → `throw new IllegalArgumentException(...)`. `translateText` (lines 279-288) has no `try/catch` around `WHERE.startsWith`, unlike the scalar path, which already wraps `MatchLiteralBuilder.toLiteral` in `catch (IllegalArgumentException)` (lines 227-232). The throw propagates through `translate` → `toFilter` and out of `EdgeHopRecogniser.java:111` (the live production caller, no local catch).

**Refutation considered:** `GremlinToMatchStrategy.apply()` catches `RuntimeException` and declines to native (GremlinToMatchStrategy.java:217-224, verified), so in the current wiring the throw degrades to a whole-traversal native decline — the same net outcome as the intended `null` decline, with no crash and no wrong rows. The finding stands for three reasons: recognisers rely on the documented "never throws" contract and only null-check the return; the empty-prefix sibling case is explicitly guarded to avoid exactly this throw, so the max-code-point gap is an internal inconsistency; and any future `toFilter` caller outside the `RuntimeException` net would take a hard failure.

**Suggestion:** Wrap the two `WHERE.startsWith` calls in `try { … } catch (IllegalArgumentException e) { return null; }`, mirroring the `toLiteral` handling already present in `translateCompare` / `translateContains`. Alternatively have `startsWith` signal un-buildability by return value rather than by exception.

### BG2 [suggestion] Positive Text predicates translate on a non-String field via the live edge-filter path

The adapter gates Text predicates on the comparand type only, never the field type, and it is already reachable in production through Track 3's edge-filter recogniser, so the Step-3 schema-aware decline is needed on that seam too.

**File:** `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/GremlinPredicateAdapter.java` (lines 274-303), reached from `EdgeHopRecogniser.java:111`

**Issue:** `translateText` / `translateRegex` check only `value instanceof String` (the comparand), not the field type, so `has(prop, TextP.containing("5"))` on a numeric property translates to `prop CONTAINSTEXT "5"`. Native `Text.containing` is a `PBiPredicate<String,String>` and throws `ClassCastException` on a non-String property value, so translator-on returns a silent (possibly non-empty) multiset where native errors — a behavior divergence. The schema-aware non-String Text decline is deferred to Step 3, which is in-scope-as-designed, but the adapter is already live through Track 3's `EdgeHopRecogniser`, which routes every edge `HasContainer` through `toFilter` with no type gate. The exposure therefore exists now for edge properties, not only after the Step-3 `HasStepRecogniser` lands.

**Evidence:** `EdgeHopRecogniser.java:106-117` translates every `has(...)` container on an `outE(L).has(...).inV()` chain via `GremlinPredicateAdapter.INSTANCE.toFilter`, declining only on a `null` return. Step 2 adds the Text/TextP branch to the adapter, so a Text predicate on a non-String edge property now translates instead of declining.

**Refutation considered:** Native itself throws on Text-over-non-String, so this is not a clean correct-rows-versus-wrong-rows case, and a Text predicate on a numeric property is unusual. It remains a divergence (silent result versus error) that the `EdgeTraversalEquivalenceTest` multiset invariant would not tolerate if it exercised the case.

**Suggestion:** When Step 3 adds the schema-aware non-String Text decline, apply it at the shared `toFilter` seam (so `EdgeHopRecogniser` benefits), not only in the new `HasStepRecogniser`. Until then, confirm no equivalence test drives a Text predicate on a non-String edge property, or add a decline here.

## Evidence base

#### C1 BG1 — startsWith max-code-point throw — WRONG
Confirmed: `translateText` (lines 282-284) calls `WHERE.startsWith` without catching `IllegalArgumentException`, which `incrementLastCodePoint` throws for an all-max-code-point prefix; the adapter's "never throws" contract is violated. Full trace and mitigation in the finding body.

#### C2 BG2 — non-String Text translates on the live edge path — WRONG
Confirmed: `translateText`/`translateRegex` gate on the comparand type only, and `EdgeHopRecogniser.java:111` routes edge containers through `toFilter` with no field-type gate, so a non-String-field Text predicate translates rather than declining. Full detail in the finding body.

#### C3 Absent-property guard composes through AndP / OrP / NotP — MATCHES
Claim checked: a composite predicate could leave the translated SQL true on an absent property (a double-guard or a missing guard). Refuted by structural induction over `translate`. Every leaf the adapter emits is false on an absent property: the positive scalar / `IN` / `CONTAINSTEXT` / `ENDSWITH` / `MATCHES(find)` nodes return false when the left operand is not a non-null String (SQLContainsTextCondition.java:47-48, SQLEndsWithCondition.java:67-68, SQLMatchesCondition.java:80-88 return `false`); `eq(null)` → `key IS DEFINED AND key IS NULL` is false on absent; `neq(null)` → `NOT(key IS NULL)` is false on absent; every negated form is `guarded` (`key IS DEFINED AND …`, false on absent). AND / OR of all-false-on-absent operands is false on absent, and `guarded(NOT(...))` is false on absent, so the false-on-absent property holds at every node. Native `HasContainer.test` also excludes an absent property for every predicate, so the two pipelines agree. A redundant `IS DEFINED` from a guarded child under a guarded connective is harmless (it reduces to the same truth value), and the guard never alters present-row semantics because `key IS DEFINED` is true for a present row. This is the step's subtlest risk and it holds.

#### C4 NotP inner-predicate recovery and double negation — MATCHES
`translate`'s NotP branch recovers the wrapped predicate via `notP.negate()`. Confirmed against the fork jar (gremlin-core 3.8.1-af9db90 bytecode): `NotP.negate()` returns the stored `originalP` field, and `NotP` is constructed only by `P.negate()` (`new NotP(this)`), which is never invoked on an existing NotP because `NotP.negate()` unwraps rather than re-wrapping. So `originalP` is never itself a NotP — no double-negation mishandling and no unbounded recursion, and `P.not(P.not(x))` collapses to `x` at construction time before the adapter sees it. `NotP`'s own bi-predicate is a `NotPBiPredicate`, not Compare / Contains / Text, which is why dispatching NotP by type before inspecting the bi-predicate is required and correct.

#### C5 between / inside / outside decompositions — MATCHES
Confirmed against the fork jar bytecode: `P.between(a,b)` = `AndP[gte a, lt b]` (right-exclusive `[a,b)`), `P.inside(a,b)` = `AndP[gt a, lt b]` (open both ends), `P.outside(a,b)` = `OrP[lt a, gt b]`. The adapter routes these through `combine`, emitting AND / OR of scalar comparisons and never an `SQLBetweenCondition` (the closed range). Matches the track's boundary-semantics spec and the three range tests.

#### C6 eq(null) / neq(null) rewrites — MATCHES
`eq(null)` → `guarded(isNull)` = `key IS DEFINED AND key IS NULL`; `neq(null)` → `NOT(key IS NULL)`. Truth tables over {absent, present-null, present-nonnull} match native `Compare.eq` / `Compare.neq` (with TinkerPop null-equality semantics) on all three rows. A null comparand on the four range comparisons declines (`default -> null`), matching the spec and the `ltNull_declines` test.

#### C7 Singleton-collection decline — MATCHES
`(eq || neq) && value instanceof Collection && size() == 1` → decline; size 0 and size ≥2 fall through and translate. Matches the D3 rule and the four collection tests (`eqSingletonCollection_declines`, `neqSingletonCollection_declines`, `eqMultiElementCollection_translates`, `eqEmptyCollection_translates`). `eq(null)` is handled by the earlier `value == null` branch, so it never reaches the singleton check.

#### C8 Text / Contains enum exhaustiveness and dispatch order — MATCHES
Confirmed against the fork jar: `Text` has exactly {containing, notContaining, startingWith, notStartingWith, endingWith, notEndingWith} and `Contains` exactly {within, without}; both `switch` expressions are exhaustive with no `default`. `Text.RegexPredicate` is a distinct class (not the `Text` enum), so `instanceof Text` does not intercept it and the regex branch is reachable. Empty-string Text comparands (`containing("")`, `endingWith("")`, `regex("")`) evaluate the same in both pipelines; `startingWith("")` declines and falls back to native, which matches all present strings.

#### C9 Custom-BiPredicate decline — MATCHES
A bi-predicate that is not Compare / Contains / Text / `Text.RegexPredicate` falls through `translate`'s leaf dispatch to `return null`. Verified by the `EdgeHopRecogniserTest` and `GremlinPredicateAdapterTest#customBiPredicate_declines` swaps to `new P<>(custom, …)`, and by the connective all-or-nothing path (`and_withDecliningChild_declines`).
