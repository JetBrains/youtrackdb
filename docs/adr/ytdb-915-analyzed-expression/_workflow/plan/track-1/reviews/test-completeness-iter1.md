<!-- MANIFEST
findings: 3   severity: {blocker: 0, should-fix: 1, suggestion: 2}
index:
  - {id: TC1, sev: should-fix, loc: "AnalyzedExprTest.java:489-526; AnalyzedExpr.java:116-136", anchor: "### TC1 ", cert: C1, basis: "transformChildren FuncCall empty-args boundary (zero-iteration loop) is untested — every FuncCall test uses 1-3 args; the size()==0 branch of the only loop in the file goes unexercised while line coverage stays green"}
  - {id: TC2, sev: suggestion, loc: "AnalyzedExprTest.java:378-395,408-414; AnalyzedExpr.java:48,99", anchor: "### TC2 ", cert: C2, basis: "Const(null) value never flows through dispatch/transformChildren; Const(Object) permits null and ReplaceConstTransform rebuilds new Const(null) — correct-by-construction (value is opaque) but the null leaf is a cheap parameterizable add"}
  - {id: TC3, sev: suggestion, loc: "AnalyzedExprTest.java:504-526,554-573; AnalyzedExpr.java:119-131", anchor: "### TC3 ", cert: C3, basis: "single-element FuncCall arg list changed (new list of size 1) is untested — all changed-arg FuncCall tests use >=3 args and always share a trailing element; the minimal rebuilt list is never asserted"}
evidence_base: {section: "## Evidence base", certs: 3, matches: 3}
cert_index:
  - {id: C1, verdict: CONFIRMED, anchor: "#### C1 "}
  - {id: C2, verdict: LOW-VALUE, anchor: "#### C2 "}
  - {id: C3, verdict: LOW-VALUE, anchor: "#### C3 "}
flags: [CONTRACT_OK]
-->

## Findings

### TC1 [should-fix]
**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/query/analyzed/AnalyzedExprTest.java`
**Production code**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/query/analyzed/AnalyzedExpr.java:116-136` (the `FuncCall` arm of `transformChildren`)
**Missing scenario**: A `FuncCall` whose argument list is empty (`new FuncCall("f", List.of())`) passed through `transformChildren`. Every `FuncCall` test in the suite uses one to three arguments (`funcCallWithNoArgumentChangedReturnsSameNodeAndSameArgList` uses 2, `funcCallWithMiddleArgumentChangedRebuildsListAndSharesLeadingArgs` and `funcCallWithTwoArgumentsChangedAccumulatesIntoOneNewList` use 3, the dispatch test uses 1). No test exercises the zero-element list.

**Why it matters**: Line 119 is `for (int i = 0; i < args.size(); i++)`. It is the only loop in the file and the loop-entry condition is the one place a collection's size drives control flow. With `args.size() == 0` the body never runs, `newArgs` stays `null`, and line 132's `if (newArgs == null)` yields `f` (the input node) by reference. Coverage metrics report this code as covered — line 133 (`yield f`) is already hit by `funcCallWithNoArgumentChangedReturnsSameNodeAndSameArgList`, and the loop body is hit by the changed-arg tests — so the green coverage number hides the fact that the empty-collection boundary (loop guard false on first evaluation, no body execution) is never distinctly exercised. This is precisely the corner case the track's own `## Validation and Acceptance` gap checklist names ("empty FuncCall args"). The bug class it would catch: a future refactor that, for example, swaps the lazy-copy guard to `args.isEmpty()`-based logic, special-cases zero args, or changes the loop to a `do/while`, would alter empty-list behavior with no test to fail. Empty-argument calls are not hypothetical — niladic SQL functions (`now()`, and `count(*)` after lowering strips the star) lower to a zero-arg `FuncCall`, so the IR substrate will carry them in later slices.

**Evidence**: Input domain table for `transformChildren` `FuncCall` arm:

| State | Boundary | Currently tested? | Evidence |
|-------|----------|-------------------|----------|
| `args.size()` | 0 (loop never enters) | NO | no test constructs an empty-arg `FuncCall` |
| `args.size()` | 1 | partial | only via the 1-arg dispatch routing test (`AnalyzedExprTest.java:394`), never through a *changing* transform |
| `args.size()` | 2, no change | YES | `AnalyzedExprTest.java:489-499` |
| `args.size()` | 3, middle changed | YES | `AnalyzedExprTest.java:504-526` |
| `args.size()` | 3, two changed | YES | `AnalyzedExprTest.java:554-573` |

**Refutation considered**: (a) Unreachable? No — `FuncCall(String, List<AnalyzedExpr>)` is a public record with no caller-side validation barring an empty list; the lowerer (Track 3) is the first producer and will build niladic calls. (b) No-op so trivially correct? The empty path is short, but it is a distinct control-flow boundary (the loop guard) and the design's lazy-copy contract ("an unchanged argument list is never reallocated", `AnalyzedExpr.java:90-91`) must hold for the zero-arg list specifically — an empty `FuncCall` must come back by reference, not as a rebuilt empty-list node. (c) Covered indirectly? No FuncCall test passes an empty list, and the all-unchanged 2-arg test reaches `yield f` through a non-empty list, so the empty-list reference-return property is asserted nowhere. Verdict: CONFIRMED as a meaningful gap.

**Suggested test**:
```java
/// (g) WHEN a FuncCall has no arguments at all, THE helper returns the same node by
/// reference — the lazy-copy loop never enters and never allocates a new empty list.
@Test
public void funcCallWithEmptyArgListReturnsSameNodeByReference() {
  IdentityTransform t = new IdentityTransform();
  FuncCall call = new FuncCall("now", List.of());

  AnalyzedExpr result = AnalyzedExpr.transformChildren(call, t);

  assertSame(call, result);
  assertSame(call.args(), ((FuncCall) result).args());
}
```

### TC2 [suggestion]
**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/query/analyzed/AnalyzedExprTest.java`
**Production code**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/query/analyzed/AnalyzedExpr.java:48` (`record Const(Object value)`), `:99` (the `Const` arm of `transformChildren`)
**Missing scenario**: A `Const` carrying a `null` value flowing through `dispatch` and `transformChildren`. The dispatch test uses `Const(42)`, `Const(true)`, and `Const(1)`; `ReplaceConstTransform` rebuilds via `new Const(constant.value())`. No test passes `Const(null)`, so `new Const(null)` is never constructed and a null-valued leaf is never routed or transformed.

**Why it matters**: `Const(Object value)` explicitly permits `null` — the Javadoc at `AnalyzedExpr.java:46-49` describes literals (integer, string, boolean, folded-negative number), and SQL `NULL` literals are a real lowering input the substrate will eventually carry. A null leaf is the canonical null-value boundary for the one variant whose payload is an opaque `Object`.

**Evidence**: Input domain table for `Const.value`:

| Boundary | Currently tested? | Evidence |
|----------|-------------------|----------|
| integer | YES | `Const(42)` `AnalyzedExprTest.java:383`, `Const(1)` `:394` |
| boolean | YES | `Const(true)` `AnalyzedExprTest.java:391` |
| null | NO | no test constructs `Const(null)` |
| String / multi-byte | NO | no string-valued `Const` |

**Refutation considered**: Does any method under test branch on `Const.value()`? No — `dispatch` (`AnalyzedExpr.java:74`) routes on the variant type only, and `transformChildren`'s `Const` arm (`:99`) returns the node by reference without reading the value. The value is opaque to every helper this track ships. So a null (or String, or any object) value is correct-by-construction for the substrate machinery: nothing can branch on it. Verdict: LOW-VALUE — the gap is real (the null leaf is untested) but the code under review cannot misbehave on it, because no path inspects the payload. Worth adding only as a cheap robustness assertion (one extra `assertEquals` row in the dispatch test), not a coverage requirement.

**Suggested test** (fold a row into `dispatchRoutesEachVariantToItsVisitMethod`):
```java
// A null-valued Const still routes to visitConst — the dispatcher branches on the
// variant type, never the opaque payload.
assertEquals("const", AnalyzedExpr.dispatch(new Const(null), visitor));
```

### TC3 [suggestion]
**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/query/analyzed/AnalyzedExprTest.java`
**Production code**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/query/analyzed/AnalyzedExpr.java:119-131` (the lazy-copy loop)
**Missing scenario**: A single-element `FuncCall` whose one argument changes (`new FuncCall("f", List.of(changingConst))`), producing a rebuilt argument list of size 1. The changed-arg FuncCall tests all use lists of length 3 and always keep a trailing element shared (`funcCallWithMiddleArgumentChangedRebuildsListAndSharesLeadingArgs` `:504`, `funcCallWithTwoArgumentsChangedAccumulatesIntoOneNewList` `:554`). The minimal rebuilt list — one element, no shared sibling — is never asserted.

**Why it matters**: The single-element-changed case is the smallest list that still exercises the rebuild path (`subList(0, 0)` empty prefix at `i=0`, then one append, then `List.copyOf` of a one-element list at line 135). It is the boundary between "no change → share by reference" (size-0/all-unchanged) and the multi-element rebuilds the suite covers. It would catch an off-by-one in the prefix copy or an over-eager `List.copyOf` that mishandles a singleton.

**Evidence**: Input domain table for the lazy-copy rebuild:

| List shape on rebuild | Currently tested? | Evidence |
|-----------------------|-------------------|----------|
| size 1, sole arg changed | NO | no test |
| size 3, middle changed (prefix size 1) | YES | `AnalyzedExprTest.java:504-526` |
| size 3, first+second changed (prefix size 0) | YES | `AnalyzedExprTest.java:554-573` |

**Refutation considered**: Covered indirectly? The empty-prefix branch (`subList(0, 0)` at `i=0`) is already exercised by `funcCallWithTwoArgumentsChangedAccumulatesIntoOneNewList` (first arg changes at index 0), and the non-empty-prefix branch (`subList(0, i>0)`) by the middle-arg test. So both `subList` arms and `List.copyOf` are already branch-covered; the size-1 case differs only in the list length, not in which branch executes. Verdict: LOW-VALUE — no new branch is reached. The track's `## Validation and Acceptance` lists "first/last arg changed" as a gap to check, and this rounds out that enumeration, but it is a completeness-of-shapes addition rather than a coverage-driven one.

**Suggested test**:
```java
/// (h) WHEN a single-argument FuncCall's only argument changes, THE helper returns a new
/// node whose rebuilt arg list holds exactly the one replaced argument.
@Test
public void funcCallWithSingleArgumentChangedRebuildsSingletonList() {
  ReplaceConstTransform t = new ReplaceConstTransform();
  Const only = new Const(7);
  FuncCall call = new FuncCall("f", List.of(only));

  AnalyzedExpr result = AnalyzedExpr.transformChildren(call, t);

  assertNotSame(call, result);
  FuncCall rebuilt = (FuncCall) result;
  assertNotSame(call.args(), rebuilt.args());
  assertEquals(1, rebuilt.args().size());
  assertNotSame(only, rebuilt.args().get(0));
  assertEquals(only, rebuilt.args().get(0));
}
```

## Evidence base

#### C1 transformChildren FuncCall empty-args boundary
- **Status**: CONFIRMED as a meaningful gap (survived the refutation check).
- **Production path**: `AnalyzedExpr.java:119` loop guard `i < args.size()`; with `args.size()==0` the body is skipped and `:132` yields `f` by reference. Line 133 is *line*-covered (the no-change 2-arg test reaches it), so the empty-collection control-flow boundary is masked behind a green coverage figure.
- **Test inventory**: every `FuncCall` test uses 1-3 args (`AnalyzedExprTest.java:394,492,511,560`); none constructs `List.of()`. The track's `## Validation and Acceptance` itself names "empty FuncCall args" as a gap.
- **Reachability**: `FuncCall(String, List)` is a public record, no caller validation; niladic SQL calls (`now()`, lowered `count(*)`) produce zero-arg calls in later slices. Contract under test — "an unchanged argument list is never reallocated" (`AnalyzedExpr.java:90-91`) — must hold for the empty list specifically.

#### C2 Const(null) opaque-value handling
- **Refutation outcome**: LOW-VALUE — the gap is real but the machinery is correct-by-construction.
- **Production path**: `dispatch` (`AnalyzedExpr.java:71-79`) switches on the variant type alone; `transformChildren`'s `Const` arm (`:99`) returns the leaf by reference. Neither reads `Const.value()`. No path branches on the payload, so a null (or String, or arbitrary object) value cannot change control flow.
- **Test inventory**: dispatch test covers integer and boolean `Const` (`AnalyzedExprTest.java:383,391,394`); no null- or String-valued `Const`. `record Const(Object value)` (`:48`) explicitly permits null.
- **Why kept as suggestion**: `Const` is the one variant with an opaque `Object` payload and SQL `NULL` is a genuine future lowering input; a single `assertEquals` row pins that the dispatcher ignores the payload. Not a coverage requirement.

#### C3 single-element FuncCall arg list changed
- **Refutation outcome**: LOW-VALUE — no new branch reached.
- **Production path**: `AnalyzedExpr.java:119-135` lazy copy. Both `subList` arms are already covered: empty prefix (`subList(0,0)`) by the two-args-changed test (change at index 0, `AnalyzedExprTest.java:554-573`), non-empty prefix (`subList(0,1)`) by the middle-arg test (`:504-526`). `List.copyOf` is hit by both. A size-1 changed list differs only in list length, executing the same branches.
- **Test inventory**: all changed-arg FuncCall tests use length-3 lists with a shared trailing element; the minimal singleton rebuild is never asserted.
- **Why kept as suggestion**: rounds out the track's "first/last arg changed" enumeration in `## Validation and Acceptance` and guards the singleton `List.copyOf`; a shape-completeness add, not coverage-driven.
