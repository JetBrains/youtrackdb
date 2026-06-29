<!--MANIFEST
dimension: bugs-concurrency
target: Track 3 Step 1 — AnalyzedExprLowerer (AST→IR lowering pass) + lowering unit test
commit_range: 772dd697c6faf2effeb1fa30a0912ecc616b78ee~1..772dd697c6faf2effeb1fa30a0912ecc616b78ee
verdict: PASS
blocker_count: 0
should_fix_count: 1
suggestion_count: 2
index:
  - id: BC1
    sev: should-fix
    anchor: "#bc1-lowerboolean-npes-on-a-null-sub-sqlnotblock-when-lower-reaches-it-via-recursion"
    loc: AnalyzedExprLowerer.java:307
    cert: C1
    basis: psi+source-trace
  - id: BC2
    sev: suggestion
    anchor: "#bc2-foldarithmetic-reads-childrengetcursor0-with-cursor0--0-redundant-but-relies-on-the-size--2-guard-staying-upstream"
    loc: AnalyzedExprLowerer.java:135
    cert: C2
    basis: source-trace
  - id: BC3
    sev: suggestion
    anchor: "#bc3-lowerwithoptionalmodifier-rejects-a-chained-method-call-via-getnext-only-an-array-or-suffix-next-segment-is-implicitly-covered-confirm-no-bare-getnext-null-method-chain-shape"
    loc: AnalyzedExprLowerer.java:270
    cert: C3
    basis: psi+grammar-trace
evidence_base: "## Evidence base — 3 certs (C1 full refutation; C2/C3 confirmed-survived one-line)"
cert_index:
  - C1
  - C2
  - C3
flags: []
-->

## Findings

### BC1 [should-fix] `lowerBoolean` NPEs on a null-`sub` `SQLNotBlock` when `lower` reaches it via recursion

**File:** `core/src/main/java/com/jetbrains/youtrackdb/internal/core/query/analyzed/AnalyzedExprLowerer.java` (line 300-313, specifically 307)

**Issue.** `lowerBoolean` dispatches a `SQLNotBlock` by calling `lowerBoolean(notBlock.getSub())` unconditionally. `SQLNotBlock.sub` is a nullable field — `SQLNotBlock.evaluate` guards `if (sub == null) { return true; }` (SQLNotBlock.java:37, :49), i.e. the AST class itself treats a null sub as a real, handled state. If a `SQLNotBlock` with `sub == null` reaches this branch, `lowerBoolean(null)` throws `NullPointerException` instead of the contract's `UnsupportedAnalyzedNodeException`. That is an I2 violation in spirit (the no-silent-fallback contract promises a typed lowering failure, not an NPE) and a latent crash if any path delivers such a node.

**Evidence.** Code path trace:
- `lowerBoolean(SQLBooleanExpression)` → `instanceof SQLNotBlock notBlock` → `AnalyzedExpr sub = lowerBoolean(notBlock.getSub());` (line 307). No null check on `getSub()`.
- `SQLNotBlock.getSub()` (SQLNotBlock.java:59) returns the bare field; the field is nullable per the class's own `evaluate` guards.
- On the **parser path** the `NotBlock()` production (YouTrackDBSql.jjt:2581) always assigns `jjtThis.sub` from `ConditionBlock()` or `ParenthesisBlock()` in every alternative, so a parser-produced `SQLNotBlock` has a non-null sub — the current tests (which parse real SQL) never hit the NPE. The exposure is for a non-parser-constructed node: `SQLNotBlock` has a public no-arg-ish `SQLNotBlock(int id)` constructor and a public `setSub`/`setNegate`, and `lowerBoolean` is package-visible specifically so same-package callers can lower a directly-held boolean node (the stated design intent in the method Javadoc, lines 297-299). A same-package caller that builds or holds a `SQLNotBlock` whose sub was never set gets an NPE rather than the documented `UnsupportedAnalyzedNodeException`.

**Refutation considered.** I checked whether the parser can ever produce a null sub (it cannot — every `NotBlock()` alternative assigns sub; verified in the grammar). I checked whether `lower()`'s recursion can deliver a null-sub NotBlock from real `Expression()` input (it cannot — the boolean path is reached only via `lowerComparison`'s `lower(left/right)` and method-call args, both of which carry parser-built nodes). So this is **not** a defect on any current production or test input. It survives as a should-fix rather than dropping to suggestion because (a) `lowerBoolean` is deliberately a package-visible entry point for same-package callers holding raw boolean nodes — the exact callers most likely to pass a partially-built node — and (b) the method's whole contract is "throw `UnsupportedAnalyzedNodeException`, never anything else," which an NPE breaks. The fix is one guard and keeps the contract total.

**Suggestion.** Guard the sub before recursing, mirroring the field-walk's throw-default:
```java
if (booleanExpression instanceof SQLNotBlock notBlock) {
  SQLBooleanExpression notSub = notBlock.getSub();
  if (notSub == null) {
    throw new UnsupportedAnalyzedNodeException(notBlock.getClass());
  }
  AnalyzedExpr sub = lowerBoolean(notSub);
  ...
}
```
Optionally add a unit test that lowers a hand-built `new SQLNotBlock(-1)` (sub unset) and asserts `UnsupportedAnalyzedNodeException`, pinning the totality of the boolean entry.

### BC2 [suggestion] `foldArithmetic` reads `children.get(cursor[0])` with `cursor[0] == 0`; redundant but relies on the `size() < 2` guard staying upstream

**File:** `core/src/main/java/com/jetbrains/youtrackdb/internal/core/query/analyzed/AnalyzedExprLowerer.java` (line 129-137)

**Issue.** `foldArithmetic` does `lowerMath(children.get(cursor[0]))` with `cursor[0]` initialized to `0`, then hands off to `climb`. It is only ever called from `lowerMath` after the guard `children == null || ... || children.size() < 2 || operators.size() != children.size() - 1` (lines 112-113) has thrown for any malformed list. So `children` is guaranteed non-null and size ≥ 2 at the `get(0)` call. This is correct today, but the invariant lives in a different method than the access. If a future refactor adds another `foldArithmetic` caller without re-establishing the guard, `children.get(0)` becomes an `IndexOutOfBoundsException` (empty list) or NPE (null list). Pure robustness; no current bug.

**Evidence.** `lowerMath` (line 102-119) is the sole caller of `foldArithmetic` (PSI find-usages: `foldArithmetic` ← `lowerMath` only). The guard and the access are separated by the call boundary, so the safety is non-local.

**Refutation considered.** Confirmed via PSI that `lowerMath` is the only caller and that its guard fully covers the `get(0)`/`get(cursor[0])` accesses and the `operators.get(cursor[0])` reads inside `climb` (the `cursor[0] < operators.size()` loop bound plus `operators.size() == children.size() - 1` keeps `cursor[0]+1 < children.size()`). No reachable failure. Suggestion only.

**Suggestion.** Either inline the fold into `lowerMath` after the guard, or add a one-line `assert children != null && children.size() >= 2 && operators.size() == children.size() - 1;` at the top of `foldArithmetic` to co-locate the invariant with the access (assert lines are excluded from coverage by the project's coverage gate, so this is free).

### BC3 [suggestion] `lowerWithOptionalModifier` rejects a chained method call via `getNext()`; only an array-or-suffix `next` segment is implicitly covered — confirm no bare `getNext()==null` method-chain shape

**File:** `core/src/main/java/com/jetbrains/youtrackdb/internal/core/query/analyzed/AnalyzedExprLowerer.java` (line 263-285)

**Issue.** `lowerWithOptionalModifier` accepts a modifier only when `modifier.getMethodCall() != null && modifier.getNext() == null`, then lowers the method arguments. The intent (per Javadoc and D18/D6-R) is that only a single, terminal method-call modifier on a `Var`/string base is in subset; any chain (`a.m().n()`, `a.b.c`) throws. The `getNext() != null` test catches the chain. This is correct for the parser shape — `Modifier()` (YouTrackDBSql.jjt:2169) nests further segments under `jjtThis.next`. The note is a verification ask, not a defect: confirm there is no in-subset method-call shape where a second segment lands somewhere other than `next` (e.g. a method whose own arguments are fine but which is itself followed by a suffix). The grammar trace says no — a trailing `.suffix` or `[...]` becomes a `next` modifier — so `getNext() != null` is the complete chain gate.

**Evidence.** `Modifier()` grammar (jjt:2169-2204): each segment (`[...]` bracket selector, `MethodCall()`, or `.suffix`) is followed by an optional `jjtThis.next = Modifier()`. So a method call followed by anything is a `methodCall`-bearing modifier with non-null `next`; the code's `getNext() != null` throw covers it. A method call with no following segment has `next == null` and is lowered. No shape escapes the gate.

**Refutation considered.** Traced the grammar to confirm every chained shape produces a non-null `next` on the first modifier (so the throw fires) and that a lone method call produces `next == null` (so it lowers). The test `methodCallWithOutOfSubsetArgumentThrows` (`name.f(a = b)`) and `multiSegmentColumnPathThrows` (`p.name`) cover the two interesting chain rejections. No defect found; flagged only so the reviewer/author can confirm the grammar reasoning holds and consider a `a.m().n()` throw-case test to pin the method-chain (as opposed to method-then-suffix) rejection explicitly, since the current throw-tests cover method-then-boolean-arg and suffix-chain but not method-then-method.

**Suggestion.** Add one throw-case test for a chained method call `name.m().n()` to pin the `getNext() != null` gate against the method-then-method shape, complementing the existing method-then-suffix (`p.name`) and out-of-subset-arg (`name.f(a = b)`) cases.

## Evidence base

Refutation reasoning per the YTDB-1069 roster rendering: a CONFIRMED-as-issue claim that survived its refutation check compresses to one line; a claim whose verdict is anything else is rendered in full. All three findings below survived as real (BC1 should-fix, BC2/BC3 suggestion); none were refuted into non-findings. The fuller refutation prose for each lives in the finding body above; these cert entries record the reference-accuracy basis.

#### C1 — BC1 null-`sub` NotBlock NPE (basis: psi+source-trace)
SURVIVED (should-fix). `SQLNotBlock.sub` nullability confirmed from the class's own `evaluate` null-guards (SQLNotBlock.java:37/49); parser cannot produce a null sub (NotBlock() grammar assigns sub in every alternative, jjt:2581); `lowerBoolean` is a deliberately package-visible entry (Javadoc lines 297-299) so a same-package caller holding a hand-built node is the realistic trigger; PSI find-usages of `lowerBoolean` shows callers = internal `lower()` dispatch + internal `lowerBoolean` recursion + 8 test sites, confirming the package-visible entry is exercised directly and the contract is "throw `UnsupportedAnalyzedNodeException`, never NPE."

#### C2 — BC2 fold guard non-locality (basis: source-trace)
SURVIVED (suggestion). PSI find-usages: `foldArithmetic` has exactly one caller, `lowerMath`, whose guard (lines 112-113) covers every list access in `foldArithmetic`/`climb`; no reachable failure today, flagged for invariant co-location only.

#### C3 — BC3 method-chain gate completeness (basis: psi+grammar-trace)
SURVIVED (suggestion). `Modifier()` grammar (jjt:2169-2204) nests every following segment under `next`, so `getNext() != null` is the complete chain gate; PSI confirms `lowerWithOptionalModifier` callers are `lowerBase` + `lowerIdentifier` only; no escaping shape; flagged only to add an explicit method-then-method throw-case test.

### Reviewer notes — what was checked and cleared (no finding)

The four orchestrator open questions and the highest-risk correctness surfaces were traced to ground and cleared:

- **(a) Precedence-climbing fold (D12) correctness.** Over the admitted operator set (`PLUS`/`MINUS`/`STAR`/`SLASH`, priorities 20/20/10/10), the implementer's classic precedence-climbing (`climb` participates when `priority <= minPriority`; right operand bounded by `priority - 1` for left-associativity) is equivalent to the AST's stack reduction in `calculateWithOpPriority` + `iterateOnPriorities` (reduce when `peek.getPriority() <= next.getPriority()` — `<=` gives left-associativity for equal priorities). For two priority levels with left-associative reduction the two algorithms produce identical trees. The fold builds structure only and computes no value. Tests pin `a + b * c` → `PLUS(a, STAR(b,c))`, `a - b - c` → `MINUS(MINUS(a,b),c)`, `a * b + c * d`, and `(a+b)*c`. No mis-nesting. CLEARED.
- **(b) I2 exhaustiveness at field AND operator level.** Field walk (`lower`) ends in `throw` as its default after dispatching the recognized typed fields. Operator-level gates `toArithmeticOperator` (default-throw `switch`) and `toComparisonOperator` (instanceof-chain ending in throw) reject the eight out-of-subset arithmetic operators and eight out-of-subset comparison operators that arrive on the in-subset `mathExpression`/`booleanExpression` fields. `climb` with the outer `Integer.MAX_VALUE` bound walks the *entire* flat operator list (no operator is skipped — the nested `priority-1` sub-climb only partitions which operators each recursion consumes; the outer loop picks up the rest), so every operator reaches `toArithmeticOperator`. The boolean dispatch ends in throw. No partial/null tree can be returned. Tests are exhaustive over the out-of-subset kinds (rid/arrayConcat/json fields; `%`/`<<`/`&` operators; LIKE/CONTAINSKEY/IN/AND boolean shapes; subquery/CASE; levelZero/`any()`/`@this`/inline-collection; multi-segment Var; bind param). CLEARED.
- **(c) `lowerBoolean` package-visibility / `booleanExpression` dead-branch.** The implementer's reachability claim holds. PSI + grammar: the public `Expression()` production (jjt:2207) has no alternative that assigns `booleanExpression` — only `FunctionParam()` (jjt:2104) does, wrapping an `OrBlock()` into a fresh `SQLExpression`. So no top-level `Expression()` parse carries a bare `booleanExpression`. The `booleanExpression` branch in `lower()` is therefore **not** reachable from a top-level parse, but it **is** live via recursion: `lowerComparison` calls `lower(left)`/`lower(right)`, and `lowerWithOptionalModifier` calls `lower(param)` over method-call args, either of which can be a `booleanExpression`-bearing `SQLExpression` (the test `methodCallWithOutOfSubsetArgumentThrows` exercises exactly this). The branch is not dead code, and the package-visibility (test enters via `lowerBoolean` because it parses bare `BinaryCondition()`/`NotBlock()`) is sound. CLEARED.
- **(d) Null-safety on AST reads.** `SQLNumber.getValue()` returns null only on the base class; the `Number()` production (jjt) always returns `SQLInteger`/`SQLFloatingPoint`, whose `getValue()` is non-null for parsed input, so `Const(number.getValue())` carries a real value (the negative-sign-folding claim T3 holds — `SQLInteger.setValue(sign, ...)` folds the sign). `Const(null)` is intentional only on the `isNull` path. `SQLMethodCall.getMethodNameString()` is null-safe (returns null → throw) and `getParams()` returns the field initialized to a non-null empty `ArrayList`. `getStringValue()` on a parsed suffix identifier is non-null. The `Var`/`FuncCall`/`Const`/`BinaryOp`/`UnaryOp` records do no constructor validation, so they accept the lowerer's inputs. Only the `SQLNotBlock.getSub()` read is unguarded against null (BC1). CLEARED except BC1.

Concurrency/resource-leak/RID/lifecycle dimensions: not applicable. `AnalyzedExprLowerer` is a stateless final class with a private constructor and only static methods; it holds no mutable state, acquires no locks, opens no resources, and constructs/parses no RIDs. The `int[] cursor` is a per-invocation stack-local passed by reference for the fold — thread-confined by construction. No shared state, no publication, no TOCTOU surface. `lower` has no production caller on this branch (PSI: callers = test only), so S0 ships behind no consumer — a latent defect here cannot affect production today, which informs the should-fix (not blocker) severity on BC1.

Reference-accuracy: all caller/usage claims above are PSI-backed (mcp-steroid `MethodReferencesSearch` against `analyzed-expression-5p7llp6k`, which matches the working tree). Grammar-shape claims are read directly from `core/src/main/grammar/YouTrackDBSql.jjt`.
