<!--MANIFEST
review: test-completeness
target: "Track 3 Step 1 — AnalyzedExprLowerer + AnalyzedAstAccess + AnalyzedExprLowererTest"
commit_range: 772dd697c6faf2effeb1fa30a0912ecc616b78ee~1..772dd697c6faf2effeb1fa30a0912ecc616b78ee
iteration: 1
verdict: PASS_WITH_FINDINGS
counts: { blocker: 0, should_fix: 2, suggestion: 4 }
evidence_base: present
cert_index: [C1, C2, C3, C4, C5, C6]
flags:
  psi_used: true
  psi_caveat: none
index:
  - { id: TC1, sev: should-fix, anchor: "#tc1-floating-point-literal-family-entirely-untested", loc: "AnalyzedExprLowererTest.java leaf-shapes block (lines 599-651); AnalyzedExprLowerer.java:210-216", cert: C1, basis: "PSI: SQLFloatingPoint.getValue() branches F/D/no-suffix + null return" }
  - { id: TC2, sev: should-fix, anchor: "#tc2-integer-long-boundary-untested-const-value-type-not-pinned", loc: "AnalyzedExprLowererTest.java:626-635; AnalyzedExprLowerer.java:210-216", cert: C2, basis: "PSI: SQLInteger.setValue produces Integer vs Long by L-suffix/overflow" }
  - { id: TC3, sev: suggestion, anchor: "#tc3-null_coalescing-the-interleaving-priority-out-of-subset-arithmetic-op-not-sampled", loc: "AnalyzedExprLowererTest.java:743-765; AnalyzedExprLowerer.java:178-186", cert: C3, basis: "PSI: Operator NULL_COALESCING priority 25 interleaves in-subset 20/out-of-subset 30" }
  - { id: TC4, sev: suggestion, anchor: "#tc4-out-of-subset-comparison-operators-sampled-2-of-8", loc: "AnalyzedExprLowererTest.java:767-809; AnalyzedExprLowerer.java:334-354", cert: C4, basis: "PSI: 15 SQLBinaryCompareOperator subtypes, 8 out-of-subset; 2 sampled" }
  - { id: TC5, sev: suggestion, anchor: "#tc5-string-escape-decoding-and-double-quote-path-untested", loc: "AnalyzedExprLowererTest.java:637-642; AnalyzedExprLowerer.java:222-226", cert: C5, basis: "PSI: getStringLiteralValue calls StringSerializerHelper.decode + accepts both quote chars" }
  - { id: TC6, sev: suggestion, anchor: "#tc6-precedence-fold-left-associativity-within-the-tight-priority-group-untested", loc: "AnalyzedExprLowererTest.java:538-597; AnalyzedExprLowerer.java:148-168", cert: C6, basis: "PSI: STAR/SLASH share priority 10; climb's priority-1 left-assoc bound only exercised at priority 20" }
-->

## Findings

### TC1 [should-fix] Floating-point literal family entirely untested

- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/query/analyzed/AnalyzedExprLowererTest.java` (leaf-shapes block, lines 599-651)
- **Production code**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/query/analyzed/AnalyzedExprLowerer.java:210-216` (`lowerBase` number branch → `new AnalyzedExpr.Const(number.getValue())`)
- **Missing scenario**: No test lowers any floating-point literal. The numeric coverage is `42` (int), `-5` (negative int), and string `'hello'`. The `SQLFloatingPoint.getValue()` path — reached for `3.14`, `1.5f`, `2.0d`, and the Float/Double downcast boundary — is never lowered.
- **Why it matters**: `number.getValue()` is the value the `Const` carries, and `Const` uses structural `equals`, so the runtime numeric *type* is load-bearing for every downstream consumer (Track 4's round-trip parity matches on `Const` value equality). `SQLFloatingPoint.getValue()` is not a trivial accessor — it has three branches that produce three different boxed types (C1): `F`/`f` suffix → `Float`; `D`/`d` suffix → `Double`; no suffix → `Double.parseDouble` then **downcast to `(float)` when `|v| < Float.MAX_VALUE`, kept `Double` otherwise**. A bug or assumption in lowering that, say, only ever round-trips correctly for `Double` would pass the round-trip suite for `2.0d` and silently corrupt `1.5` (which lowers to a `Float`). The lowering pass itself is a pass-through here, but this is exactly the test-data-avoids-the-edge-case situation: the chosen literals (`42`, `-5`) all land in the `Integer` lane and never touch the floating type-selection logic the IR will carry forward.
- **Evidence**: Input domain table row `SQLNumber subclass / numeric type` — boundary values `Integer`, `Long`, `Float`, `Double`; currently tested: only `Integer` (lines 626, 633). Cert C1.
- **Refutation considered**: Could the float path be unreachable from `parser(sql).Expression()`? No — `3.14` parses to a `SQLBaseExpression.number` of concrete type `SQLFloatingPoint`, the same leaf path the integer test already proves reachable. Could Track 4's matrix cover it indirectly? Track 4 is two tracks away and the track file explicitly states the value parity "must not have to wait two tracks to surface" (Validation, R1) — a type-selection regression in the float lane is precisely the class of bug this in-track shape assertion is meant to catch early. Not correct-by-construction: three distinct boxed types are produced.
- **Suggested test**:
  ```java
  /// WHEN floating-point literals across the three SQLFloatingPoint type-selection branches are
  /// lowered, THE Const carries the exact boxed numeric the parser produced — a small no-suffix
  /// literal as Float, an explicit D-suffix as Double, an F-suffix as Float — so a value-type
  /// regression in the IR's numeric lane surfaces in-track rather than in Track 4's parity matrix.
  @Test
  public void floatingPointLiteralsLowerToConstWithParserNumericType() {
    assertEquals(new Const(1.5f), lower("1.5"));     // no suffix, |v| < Float.MAX_VALUE -> Float
    assertEquals(new Const(2.0d), lower("2.0d"));    // D suffix -> Double
    assertEquals(new Const(3.5f), lower("3.5f"));    // F suffix -> Float
  }
  ```

### TC2 [should-fix] Integer Long boundary untested — Const value type not pinned

- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/query/analyzed/AnalyzedExprLowererTest.java:626-635` (`integerLiteralLowersToConst`, `negativeIntegerLiteralLowersToOneNegativeConst`)
- **Production code**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/query/analyzed/AnalyzedExprLowerer.java:210-216`
- **Missing scenario**: Only `42` and `-5` are lowered, both of which `SQLInteger.setValue` boxes as `Integer`. The `Long` lane — an `L`-suffixed literal (`9L`) and an out-of-`int`-range literal (`> Integer.MAX_VALUE`, e.g. `9999999999`) — is never lowered.
- **Why it matters**: `SQLInteger.setValue` (C2) boxes the value as `Integer` only when the parsed long fits `int`; an `L`/`l` suffix or a magnitude over `Integer.MAX_VALUE`/under `Integer.MIN_VALUE` boxes it as `Long`. `Const`'s structural `equals` distinguishes `Const(42)` (Integer) from `Const(42L)` (Long) — `Integer.valueOf(42).equals(Long.valueOf(42L))` is `false`. The current `new Const(42)` assertion pins the Integer lane only; nothing pins that a long literal round-trips its `Long` type through lowering. Track 4's parity suite will only catch this if it happens to use long literals, and the comment on the integer test ("the parsed numeric value") does not state which lane is covered.
- **Evidence**: Input domain table row `SQLInteger value type` — boundary values `Integer` (in-`int`-range, no suffix), `Long` (`L` suffix OR overflow); currently tested: only `Integer`. Cert C2.
- **Refutation considered**: Reachable? Yes — `9999999999` and `9L` both parse to `SQLBaseExpression.number` (`SQLInteger`), the proven leaf path. Correct-by-construction? No — the `Integer`/`Long` boxing split is exactly the off-by-type the test data sidesteps. Lower value than TC1 only because the integer overflow boundary is less likely to surface a real lowering bug than the three-way float split; bumped to should-fix because it is one extra assertion on an existing test and the `Const` type equality is genuinely load-bearing downstream.
- **Suggested test** (extend the existing integer test, no new method needed):
  ```java
  // append to integerLiteralLowersToConst, or a sibling:
  assertEquals(new Const(9L), lower("9L"));            // L suffix -> Long
  assertEquals(new Const(9999999999L), lower("9999999999")); // overflow int -> Long
  ```

### TC3 [suggestion] NULL_COALESCING — the interleaving-priority out-of-subset arithmetic op — not sampled

- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/query/analyzed/AnalyzedExprLowererTest.java:743-765` (out-of-subset arithmetic throw block: `%`, `<<`, `&`)
- **Production code**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/query/analyzed/AnalyzedExprLowerer.java:178-186` (`toArithmeticOperator` default throw)
- **Missing scenario**: The eight out-of-subset arithmetic operators are sampled by three: `%` (REM, priority 10), `<<` (LSHIFT, priority 30), `&` (BIT_AND, priority 40). The track's R4 checklist asks for `%` plus "one shift/bitwise" — satisfied. But `NULL_COALESCING` (`??`) is the one out-of-subset operator whose priority (25, C3) falls *between* the in-subset PLUS/MINUS (20) and the out-of-subset shifts (30). It is the only out-of-subset operator that can sit at a precedence level interleaved with an in-subset operator in a mixed expression like `a + b ?? c`.
- **Why it matters**: A mixed expression `a + b ?? c` builds a fold where `climb` first folds `a + b` (priority 20), then encounters `??` at priority 25 (looser, so it would be handled at an outer climb level) and routes it through `toArithmeticOperator`, which must throw. This is the one arithmetic operator whose throw is reached *after* a partial fold has already built a sub-tree — a different control-flow position than `a % b` (throw on the first and only operator). If the throw were ever weakened to a skip or a default-map, the interleaving case is where a partial-tree leak would hide.
- **Evidence**: Input domain table row `out-of-subset arithmetic Operator` — 8 constants, priority bands {10:REM, 25:NULL_COALESCING, 30:shifts, 40-60:bitwise/xor}; sampled: REM, LSHIFT, BIT_AND; NULL_COALESCING (the sole interleaving-priority case) not sampled. Cert C3.
- **Refutation considered**: Is the representative sample adequate? The track documents representative sampling as acceptable, and `%`/`<<`/`&` cover three priority bands. But none of the three is at a priority that interleaves with an in-subset operator, so the "throw reached after a partial fold" position is genuinely uncovered. Low severity because `toArithmeticOperator`'s `default -> throw` is structurally uniform across all eight operators — the interleaving distinction is about fold position, not the operator map, and the existing `%` test already proves the map throws.
- **Suggested test**:
  ```java
  /// WHEN a null-coalescing operator sits at a precedence interleaved with an in-subset operator
  /// (`a + b ?? c`, `??` priority 25 between PLUS 20 and the shifts 30), THE pass throws — the
  /// out-of-subset operator is rejected even when reached after a partial arithmetic fold.
  @Test
  public void nullCoalescingOperatorThrows() {
    assertThrows(UnsupportedAnalyzedNodeException.class, () -> lower("a + b ?? c"));
  }
  ```

### TC4 [suggestion] Out-of-subset comparison operators sampled 2 of 8

- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/query/analyzed/AnalyzedExprLowererTest.java:767-809`
- **Production code**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/query/analyzed/AnalyzedExprLowerer.java:334-354` (`toComparisonOperator` final throw)
- **Missing scenario**: Of the eight out-of-subset `SQLBinaryCompareOperator` subtypes (`SQLContainsKeyOperator`, `SQLContainsValueOperator`, `SQLInOperator`, `SQLLikeOperator`, `SQLLuceneOperator`, `SQLNearOperator`, `SQLScAndOperator`, `SQLWithinOperator` — C4), only two reach the *operator-mapping* throw path via `SQLBinaryCondition`: `LIKE` and `CONTAINSKEY`. (`IN` is tested but parses to a distinct `SQLInCondition` boolean subtype, so it exercises the boolean throw-default, not the operator map — the test comment correctly notes this.) The remaining `SQLScAndOperator` (`&&`), `SQLNearOperator`, `SQLWithinOperator`, `SQLLuceneOperator`, `SQLContainsValueOperator` are not exercised through the operator map.
- **Why it matters**: `toComparisonOperator` is a sequence of seven `instanceof` checks falling through to a throw. The two sampled operators confirm the fall-through fires. A future edit that accidentally added a wrong `instanceof` (e.g. mapped `SQLScAndOperator` to `BinaryOperator.NE` by a copy-paste) would not be caught by the `LIKE`/`CONTAINSKEY` tests. The risk is low because all eight share the identical "no `instanceof` matched → throw" structure.
- **Evidence**: Input domain table row `out-of-subset SQLBinaryCompareOperator` — 8 subtypes; sampled through operator map: 2 (`SQLLikeOperator`, `SQLContainsKeyOperator`). Cert C4.
- **Refutation considered**: The track's R4 checklist names exactly `SQLInOperator`/`SQLLikeOperator` as the required comparison-operator throw cases, and the implementer satisfied it plus added `CONTAINSKEY`. Documented representative sampling is acceptable per the agent guidelines, so this is a suggestion, not a should-fix. The highest-value addition would be `SQLScAndOperator` (`&&`), the only one whose token (`&&`) could plausibly be confused with the bitwise `&` arithmetic path during a future refactor.
- **Suggested test** (one extra assertion alongside the existing comparison-throw tests):
  ```java
  /// WHEN a `&&` (SQLScAndOperator) comparison is lowered, THE operator mapping throws — covering
  /// the out-of-subset comparison operator whose token most resembles an arithmetic operator.
  @Test
  public void scAndComparisonOperatorThrows() {
    assertThrows(
        UnsupportedAnalyzedNodeException.class,
        () -> AnalyzedExprLowerer.lowerBoolean(parseComparison("a && b")));
  }
  ```

### TC5 [suggestion] String escape-decoding and double-quote path untested

- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/query/analyzed/AnalyzedExprLowererTest.java:637-642` (`stringLiteralLowersToConst`)
- **Production code**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/query/analyzed/AnalyzedExprLowerer.java:222-226`
- **Missing scenario**: The only string literal lowered is `'hello'` — a single-quoted, escape-free ASCII string. `SQLBaseExpression.getStringLiteralValue` (C5) accepts both `'` and `"` delimiters and runs the body through `StringSerializerHelper.decode(...)`, which processes escape sequences. Neither a double-quoted string nor any escape sequence (`'a\tb'`, an embedded quote, a unicode escape) is lowered.
- **Why it matters**: The lowered `Const` carries the *decoded* string. A test with `'hello'` exercises neither the escape decoder nor a multi-character/non-ASCII payload. If the lowering ever read the raw `string` field instead of `getStringLiteralValue()`, the `'hello'` test would still pass (no quotes/escapes to strip) while a real escaped string would carry the quotes and escapes verbatim. The decode call is the substantive behavior here, and the test data sidesteps it entirely.
- **Evidence**: Input domain table row `string literal payload` — boundaries {single-quote, double-quote, escape sequence, non-ASCII}; tested: single-quote ASCII no-escape only. Cert C5.
- **Refutation considered**: The lowering pass does call `getStringLiteralValue()` (line 222), so the decoded value is what is asserted — but the `'hello'` input makes decode a no-op, so the test does not actually distinguish raw-field-read from decoded-read. Low severity: the decode path lives in `getStringLiteralValue`, which is pre-existing AST code outside this track's scope; the gap is only that this track's `Const` payload assertion does not prove the decoded value is what flows through.
- **Suggested test**:
  ```java
  /// WHEN a string literal with an escape sequence and a double-quoted string are lowered, THE
  /// Const carries the unquoted, escape-decoded value — pinning that lowering reads the decoded
  /// string, not the raw quoted field.
  @Test
  public void stringLiteralLowersDecodedValueForEscapesAndDoubleQuotes() {
    assertEquals(new Const("a\tb"), lower("'a\\tb'"));   // escape decoded
    assertEquals(new Const("hi"), lower("\"hi\""));       // double-quoted, same path
  }
  ```

### TC6 [suggestion] Precedence-fold left-associativity within the tight priority group untested

- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/query/analyzed/AnalyzedExprLowererTest.java:538-597` (arithmetic precedence fold block)
- **Production code**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/query/analyzed/AnalyzedExprLowerer.java:148-168` (`climb`, the `priority - 1` left-associative bound)
- **Missing scenario**: The fold tests are `a + b * c` (mixed, two levels), `a - b - c` (left-assoc at priority 20), `a * b + c * d` (two tighter runs), and `(a + b) * c` (paren). Left-associativity is pinned only at the *looser* priority band (PLUS/MINUS = 20). The tight band (STAR/SLASH/REM = priority 10, C6) is never tested for left-associativity: there is no `a * b / c` (which must fold `(a * b) / c`, not `a * (b / c)` — different value), and no `a / b / c`.
- **Why it matters**: `climb` enforces left-associativity by recursing with `priority - 1` (line 164), so an equal-priority following operator reduces with the current result on its left. This logic runs independently at each priority level. The priority-20 test (`a - b - c`) proves it at one level; SLASH is the operator where right-vs-left associativity changes the value most visibly (`8 / 4 / 2` is `1` left-assoc vs `4` right-assoc). A regression that broke the `priority - 1` bound only for the tighter band — or a SLASH-specific fold bug — would pass every current test.
- **Evidence**: Input domain table row `same-priority left-assoc fold` — bands {priority 10 (STAR/SLASH), priority 20 (PLUS/MINUS)}; tested: priority 20 only (`a - b - c`). Division left-associativity (the value-sensitive case) untested. Cert C6.
- **Refutation considered**: The `eachArithmeticOperatorMapsToItsIrOperator` test covers `a / b` (single SLASH), proving SLASH maps. And `a - b - c` proves the `priority - 1` left-assoc mechanism. The mechanism is priority-parameterized, so the priority-20 proof is strong evidence it holds at priority 10 too — hence suggestion, not should-fix. Still a genuine gap: no test pins associativity at the tight band, and SLASH is the operator whose mis-association is most dangerous.
- **Suggested test**:
  ```java
  /// WHEN same-priority tight-binding operators chain (`a * b / c`), THE fold is left-associative
  /// at priority 10 — `STAR(STAR? ` no: `SLASH(STAR(a,b), c)` — matching the AST's `<=` reduction.
  /// Division is the value-sensitive case (a/b/c left-assoc differs from right-assoc).
  @Test
  public void samePriorityTightGroupFoldsLeftAssociative() {
    AnalyzedExpr expected =
        new BinaryOp(BinaryOperator.SLASH,
            new BinaryOp(BinaryOperator.STAR, var("a"), var("b")), var("c"));
    assertEquals(expected, lower("a * b / c"));
  }
  ```

## Evidence base

#### C1 — SQLFloatingPoint.getValue() produces three boxed types and a reachable null
PSI read of `SQLFloatingPoint.getValue()` (`core/.../sql/parser/SQLFloatingPoint.java`): `F`/`f` suffix → `Float.parseFloat(...) * sign`; `D`/`d` suffix → `Double.parseDouble(...) * sign`; no suffix → `Double.parseDouble`, then `finalValue = (float) returnValue` when `Math.abs(returnValue) < Float.MAX_VALUE` else keeps the `double`. All three catch blocks `return null` ("TODO NaN?"). Return type declared `Number`. So a no-suffix small literal lowers to a `Float`-boxed `Const`, a `D`-suffix to a `Double`-boxed `Const` — distinct under `Const`'s structural `equals`.

#### C2 — SQLInteger.setValue boxes Integer vs Long by suffix/range
PSI read of `SQLInteger.setValue(int, String)`: `L`/`l` suffix → `Long.parseLong(...)`; otherwise parse to `long`, then `value = longValue` (Long) when `longValue > Integer.MAX_VALUE || longValue < Integer.MIN_VALUE`, else `value = (int) longValue` (Integer). Field `value` is `Number`; `equals` is `Objects.equals(value, other.value)`, so `Integer(42)` and `Long(42L)` are unequal. Confirms `42` → Integer (covered), `9L`/overflow → Long (uncovered).

#### C3 — Operator enum priorities; NULL_COALESCING interleaves
PSI enum dump of `SQLMathExpression.Operator`: `STAR(10) SLASH(10) REM(10) PLUS(20) MINUS(20) LSHIFT(30) RSHIFT(30) RUNSIGNEDSHIFT(30) BIT_AND(40) XOR(50) BIT_OR(60) NULL_COALESCING(25)`. In-subset = {PLUS,MINUS,STAR,SLASH} spanning priorities 10 and 20. `NULL_COALESCING(25)` is the only out-of-subset operator whose priority sits between an in-subset band (20) and the next out-of-subset band (30) — the sole operator reachable in a mixed expression after a partial in-subset fold. Sampled out-of-subset ops in the test: REM(10), LSHIFT(30), BIT_AND(40).

#### C4 — 15 SQLBinaryCompareOperator subtypes; 8 out of subset
`ClassInheritorsSearch` on `SQLBinaryCompareOperator` (interface), 15 subtypes: in-subset 7 = `SQLEqualsOperator, SQLNeqOperator, SQLNeOperator, SQLLtOperator, SQLLeOperator, SQLGtOperator, SQLGeOperator`; out-of-subset 8 = `SQLContainsKeyOperator, SQLContainsValueOperator, SQLInOperator, SQLLikeOperator, SQLLuceneOperator, SQLNearOperator, SQLScAndOperator, SQLWithinOperator`. The test reaches the operator-map throw for `SQLLikeOperator` and `SQLContainsKeyOperator` only; `SQLInOperator` is tested but via the boolean throw-default (parses to `SQLInCondition`, not `SQLBinaryCondition`), as the test comment states.

#### C5 — getStringLiteralValue accepts both quotes and decodes escapes
PSI read of `SQLBaseExpression.getStringLiteralValue()`: returns `null` if `string` is null or `length() < 2`; accepts first char `'` or `"` with `first == last`; returns `StringSerializerHelper.decode(string.substring(1, length-1))`. So both quote styles route through the same field, and the payload is escape-decoded. Test input `'hello'` exercises neither a double quote nor a decode-affecting escape.

#### C6 — climb enforces left-associativity per priority level via priority-1 bound
PSI/diff read of `climb` (`AnalyzedExprLowerer.java:148-168`): consumes operators while `priority <= minPriority`; the right operand is built by `climb(..., priority - 1)`, making equal-priority operators left-associative at every level independently. Tests pin this at priority 20 (`a - b - c`) but not priority 10; no `a * b / c` or `a / b / c` exists. SLASH (priority 10) is the value-sensitive associativity case.
