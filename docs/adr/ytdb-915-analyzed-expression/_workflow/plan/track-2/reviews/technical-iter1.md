<!-- MANIFEST
findings: 4   severity: {blocker: 0, should-fix: 2, suggestion: 2}
index:
  - {id: T1, sev: should-fix, loc: core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/parser/MathExpressionTest.java:33-222, anchor: "### T1 ", cert: E1, basis: "Self-verifying gate does not pin the 4 named invariants: divide widening, +-*/ null propagation, Date+Long, String concat all untested"}
  - {id: T2, sev: should-fix, loc: core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/parser/SQLMathExpression.java:1, anchor: "### T2 ", cert: P7, basis: "Edited file is under sql/parser/ — forbidden by CLAUDE.md, excluded from Spotless + ErrorProne/NullAway; track does not acknowledge"}
  - {id: T3, sev: suggestion, loc: core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/parser/SQLMathExpression.java:568-655, anchor: "### T3 ", cert: P5, basis: "Dispatch is 3-level with per-constant super.apply callbacks, not the 2-hop D17 describes; 8 constants call super.apply"}
  - {id: T4, sev: suggestion, loc: core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/parser/SQLMathExpression.java:82-538, anchor: "### T4 ", cert: P6, basis: "apply(Object,Object) is overridden per-constant on all 12; inventory of 'five typed overloads + one fallback' undercounts the moving surface"}
evidence_base: {section: "## Evidence base", certs: 11, matches: 9}
cert_index:
  - {id: P1, verdict: CONFIRMED, anchor: "#### P1 "}
  - {id: P2, verdict: CONFIRMED, anchor: "#### P2 "}
  - {id: P3, verdict: CONFIRMED, anchor: "#### P3 "}
  - {id: P4, verdict: CONFIRMED, anchor: "#### P4 "}
  - {id: P5, verdict: PARTIAL, anchor: "#### P5 "}
  - {id: P6, verdict: PARTIAL, anchor: "#### P6 "}
  - {id: P7, verdict: PARTIAL, anchor: "#### P7 "}
  - {id: I1, verdict: MATCHES, anchor: "#### I1 "}
  - {id: E1, verdict: WRONG, anchor: "#### E1 "}
  - {id: E2, verdict: WRONG, anchor: "#### E2 "}
  - {id: E3, verdict: WRONG, anchor: "#### E3 "}
flags: [CONTRACT_OK]
-->

## Findings

### T1 [should-fix]
**Certificate**: E1 (also E2, E3) — edge-case traces of the four named invariants against the actual `MathExpressionTest` body.
**Location**: Track 2 `## Validation and Acceptance`, `## Plan of Work` (invariants list), `## Invariants & Constraints`; source `MathExpressionTest.java:33-222`.
**Issue**: The track's entire validation strategy is a single self-verifying gate: "the existing AST math-test suite (e.g. `MathExpressionTest`) stays green, and any divergence in promotion semantics — integer-divide widening, null propagation, `Date + Long`, `String` concat — surfaces as a math-test failure." That claim is false for all four of the named edge cases. Reading `MathExpressionTest` end to end:
- **Integer-divide widening (SLASH remainder ≠ 0)** is never exercised. `testTypes` only calls `op.apply(1, 1)` (remainder zero) across the basic ops, so the `((double) left) / right` branch at `SQLMathExpression.java:95` / `:103` — the exact branch the DR cites as a drift risk — has no test.
- **Null propagation for `+ - * /`** is never exercised. The only null-operand test is `testNullCoalescing`, which drives `NULL_COALESCING` only. The `apply(Object, Object)` null branches in PLUS (`:199-217`), MINUS (`:253-269`), STAR (`:82-87`), SLASH/REM/shifts are not reached by any test.
- **`Date + Long` / `Long + Date` / `Date - Long`** has no test at all. The `toLong(...)` + `new Date(result.longValue())` paths (`:212-214`, `:263-265`) are uncovered.
- **`String` concatenation via PLUS** is never exercised; the `str(...)` helper is only used with `NULL_COALESCING`, never with PLUS.

So the lift-and-shift could silently regress any of the four invariants the DR names as the *reason* `NumericOps` exists, and the "self-verifying" gate would stay green. The gate as written verifies type-promotion shape (`testTypes`) and precedence folding (`testPriority*`), not the four edge-case semantics.
**Proposed fix**: Do not rely on the existing suite as the acceptance gate for these four properties. Decomposition should add a step (or fold into the extraction step) that introduces direct `NumericOps`/`Operator.apply(Object,Object)` characterization tests for: (a) `SLASH` with non-zero remainder returning `Double` and zero remainder returning the integer type; (b) `null + x = x`, `null - x = 0 - x`, `null * x = null`, `null / x = null`; (c) `Date + Long`, `Long + Date`, `Date - Long` returning `Date`; (d) `'a' + 1` String concat. These can be written against develop first (red-confirms-they-test-something), then must stay green after the delegation — that makes the gate genuinely self-verifying for the invariants the track lists. Update `## Validation and Acceptance` to stop claiming the existing suite pins these four.

### T2 [should-fix]
**Certificate**: P7 — `SQLMathExpression.java` location, generated-file header, and build-tooling exclusions.
**Location**: Track 2 `## Context and Orientation`, `## Plan of Work` step 3, `## Interfaces and Dependencies`; source `SQLMathExpression.java:1` and `pom.xml:124,640`, `core/pom.xml:233-237`.
**Issue**: The track plans to modify `SQLMathExpression.Operator.apply` in `core/.../sql/parser/SQLMathExpression.java` and never acknowledges that this directory has special status:
- `CLAUDE.md` Tip 3 and Tip 4 say "Don't edit files in `core/.../sql/parser/`" (generated from `YouTrackDBSql.jjt`). The file carries the JJTree/JavaCC `do not edit this line` header (`:1`, `:1400`).
- `pom.xml:640` excludes `**/internal/core/sql/parser/**` from Spotless, and `pom.xml:124` excludes `.*/internal/core/sql/parser/.*` from ErrorProne (`-XepExcludedPaths`). So the *edited* file is neither auto-formatted nor statically checked, but the *new* `NumericOps.java` under `sql/util/` is subject to both (it is outside both exclusions).

The lift-and-shift is feasible despite the header — verified — but the track makes no mention of this asymmetry, and it carries two concrete risks. First, the formatting/lint baseline flips for any code that moves: code that compiled clean under the parser exclusion (e.g. the `@Nullable Long toLong(...)` then `result.longValue()` deref at `:213-214`, `:264-265`; `super.apply(...)` reference-equality patterns) will now pass through ErrorProne checks like `ReferenceEquality`, `OperatorPrecedence`, `StatementSwitchToExpressionSwitch` once it lives in `sql/util/`, and any new violation fails the build. (NullAway is opt-in via `@NullMarked` — no `sql/*` package is null-marked and Track 1's `query/analyzed/` is not either, so NullAway will not fire unless the author opts in; the other ErrorProne checks are not opt-in.) Second, the parser file must be hand-formatted to the 100-col / 2-space style because Spotless will not touch it.

Generated-file safety itself is fine: JJTree with `MULTI=true` does not overwrite an existing node file (the grammar was last touched 2026-03-19, newer than the node file's 2026-02-11 commit, yet the node file was not regenerated), and the grammar only references `SQLMathExpression.Operator.STAR` etc. — it does not generate the `Operator` enum body. The delegation edit will survive `clean package`.
**Proposed fix**: Add a sentence to `## Context and Orientation` (or a Surprises-log entry) recording that (1) `SQLMathExpression.java` is a hand-maintained JJTree node file that the build does not regenerate, so editing it is safe despite the `do not edit` header and the CLAUDE.md tip; (2) it is excluded from Spotless and ErrorProne, so the edit must be hand-formatted and the new `NumericOps.java` (which IS checked) must compile clean under ErrorProne. Decomposition should keep `Operator.apply` edits minimal (a thin delegator) precisely to minimize hand-formatting surface, and the step's acceptance must include a clean `./mvnw -pl core spotless:check` plus a successful ErrorProne compile of `NumericOps`.

### T3 [suggestion]
**Certificate**: P5 — actual dispatch chain shape (per-constant `apply(Object,Object)` → `super.apply(Object,Object)` → `apply(Number,Operator,Number)` → typed overload).
**Location**: Track 2 `## Decision Log` D17 ("two-hop dispatch chain"); `## Plan of Work` step 2; source `SQLMathExpression.java:568-655` plus the per-constant bodies.
**Issue**: D17 describes the hot path as a "two-hop dispatch chain: `operator.apply(Object,Object)` → shared widening entry `apply(Number,Operator,Number)` → typed `apply` overload" with "two virtual dispatches on the enum constant." The real chain is one level deeper and more entangled. The entry from `iterateOnPriorities`/`execute` is the *per-constant* `apply(Object, Object)` override (each of the 12 constants has its own body), and 8 of them (STAR, SLASH, REM, LSHIFT, RSHIFT, RUNSIGNEDSHIFT, BIT_AND, BIT_OR) delegate up via `super.apply(left, right)` to the base `Operator.apply(Object, Object)` (`:568`), which then calls `apply((Number) left, this, (Number) right)` → `apply(Number, Operator, Number)` (`:582`) → the typed overload. PLUS, MINUS, XOR, NULL_COALESCING instead call `apply(Number, Operator, Number)` (or the typed overloads) directly from their own `apply(Object,Object)` body, bypassing `super`. So the chain is effectively three dispatch levels with a per-constant first hop, and the `super.apply` callback is structural: a naive "move everything into `NumericOps`" cannot reproduce `super.apply(...)` (there is no `super` once the logic is static). The D17 perf-neutrality argument ("leave the existing two-hop chain intact, add one monomorphic static hop in front") rests on a chain shape that is not quite what the code does.
**Proposed fix**: When decomposition resolves the D17 typed-overload boundary, state explicitly how the 8 `super.apply(...)` callbacks are reproduced after the move — typically by `NumericOps` exposing a `applyObject(Operator, Object, Object)` entry that the per-constant `apply(Object,Object)` bodies (which must stay on the enum, since they are per-constant) delegate into, replacing `super.apply`. Correct D17's "two-hop" wording to reflect the per-constant first hop plus the `super`/widening levels, so the perf claim is measured against the real chain at S1.

### T4 [suggestion]
**Certificate**: P6 — per-constant override inventory (`apply(Object,Object)` overridden on all 12 constants, not just the five typed overloads).
**Location**: Track 2 `## Decision Log` D5-R and D17, `## Context and Orientation`, `## Interfaces and Dependencies` ("the five typed-pair `apply` overloads, the fallback `apply(Object, Object)`"); source `SQLMathExpression.java:82-538`.
**Issue**: The track inventories the moving surface as "the five typed-pair `apply` overloads, the fallback `apply(Object, Object)`, the shared `apply(Number, Operator, Number)` widening entry, and the private `toLong`." That undercounts: PSI confirms `apply(Object, Object)` is *overridden in every one of the 12 enum constants' bodies* (STAR `:82`, SLASH `:123`, REM `:158`, PLUS `:199`, MINUS `:253`, LSHIFT `:302`, RSHIFT `:340`, RUNSIGNEDSHIFT `:378`, BIT_AND `:414`, XOR `:452`, BIT_OR `:501`, NULL_COALESCING `:535`), each with distinct null/Date/String/concat handling — not a single shared fallback. The "whole-enum lift" therefore must move (or keep-and-delegate) twelve per-constant `apply(Object,Object)` bodies plus the five typed overloads per constant, not "five overloads + one fallback." This matters for sizing (the diff is larger than the inventory implies) and for the D17 boundary decision: because `MathExpressionTest:48-65` calls `op.apply(Object,Object)` and the typed `op.apply(1,1)` overloads directly on enum constants, whatever moves into `NumericOps` must leave the enum with working `apply(Object,Object)` and typed-overload surfaces, or those test call sites break at compile time.
**Proposed fix**: Correct the moving-surface inventory in D5-R / `## Interfaces and Dependencies` to "the per-constant `apply(Object,Object)` body on each of the 12 constants, the five per-constant typed overloads, the base `apply(Object,Object)`, the shared `apply(Number,Operator,Number)` widening entry, and `toLong`." Note in the track that `MathExpressionTest` invokes the typed and `Object,Object` overloads directly on the enum, so the enum must retain those signatures (delegating bodies are fine) regardless of the D17 boundary choice.

## Evidence base

#### P1 SQLMathExpression.Operator is an inner enum with 12 constants
- **Track claim**: "`SQLMathExpression.Operator` is an inner enum with 12 constants (the numeric argument on each is its precedence priority)."
- **Search performed**: `findClass` on `...sql.parser.SQLMathExpression`, then enumerated `innerClasses` and `PsiEnumConstant` fields via `steroid_execute_code`.
- **Code location**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/parser/SQLMathExpression.java:53-538`.
- **Actual behavior**: `isEnum=true`; 12 constants: STAR, SLASH, REM, PLUS, MINUS, LSHIFT, RSHIFT, RUNSIGNEDSHIFT, BIT_AND, XOR, BIT_OR, NULL_COALESCING. Constructor `Operator(int priority)` (`:553`); `getPriority()` returns it (`:657`).
- **Verdict**: CONFIRMED

#### P2 Five abstract typed-pair apply overloads exist on the enum
- **Track claim**: "abstract typed-pair `apply` overloads (`apply(Integer,Integer)`, `apply(Long,Long)`, `apply(Float,Float)`, `apply(Double,Double)`, `apply(BigDecimal,BigDecimal)`)."
- **Search performed**: PSI method enumeration on the `Operator` PsiClass.
- **Code location**: `SQLMathExpression.java:557-565`.
- **Actual behavior**: `public abstract Number apply(Integer,Integer)`, `apply(Long,Long)`, `apply(Float,Float)`, `apply(Double,Double)`, `apply(BigDecimal,BigDecimal)` — all five present, all abstract, all `Number`-returning.
- **Verdict**: CONFIRMED

#### P3 Fallback apply(Object,Object) and shared widening apply(Number,Operator,Number) exist
- **Track claim**: "a fallback `apply(Object,Object)` and a shared `apply(Number,Operator,Number)` widening entry."
- **Search performed**: PSI method enumeration.
- **Code location**: base `apply(Object,Object)` `:567-580`; `apply(Number,Operator,Number)` `:582-655`.
- **Actual behavior**: `@Nullable public Object apply(Object left, Object right)` base implementation (`:568`); `public Number apply(final Number a, final Operator operation, final Number b)` widening dispatcher (`:582`) that throws `IllegalArgumentException` on null or unsupported type pairs.
- **Verdict**: CONFIRMED

#### P4 getPriority() and private static toLong exist
- **Track claim**: "plus a `getPriority()` and a private `toLong`."
- **Search performed**: PSI method enumeration; modifier inspection.
- **Code location**: `getPriority()` `:657`; `toLong` `:540-549`.
- **Actual behavior**: `public int getPriority()`; `@Nullable private static Long toLong(Object left)` (returns `null` for non-Number, non-Date input — relevant to the Date arithmetic null-deref noted in T2).
- **Verdict**: CONFIRMED

#### P5 The dispatch chain is three-level with per-constant super.apply callbacks
- **Track claim**: D17 — "the chain (`operator.apply(Object,Object)` → shared widening entry `apply(Number,Operator,Number)` → typed `apply` overload) has two virtual dispatches on the enum constant."
- **Search performed**: Read the per-constant `apply(Object,Object)` bodies and base methods; `ReferencesSearch` on `apply(Object,Object)` and `apply(Number,Operator,Number)`.
- **Code location**: per-constant `apply(Object,Object)` bodies `:82,:123,:158,:199,:253,:302,:340,:378,:414,:452,:501,:535`; base `:568`; widening `:582`.
- **Actual behavior**: The entry is the per-constant `apply(Object,Object)` override. 8 constants (STAR/SLASH/REM/LSHIFT/RSHIFT/RUNSIGNEDSHIFT/BIT_AND/BIT_OR) call `super.apply(left,right)` → base `apply(Object,Object)` (`:568`) → `apply(Number,Operator,Number)` (`:576`/`:582`) → typed overload. PLUS/MINUS/XOR/NULL_COALESCING call the widening/typed overloads directly. The chain is effectively three levels with a per-constant first hop plus a `super` callback, not a flat two-hop.
- **Verdict**: PARTIAL — chain exists and is intact-able, but is deeper and uses `super` callbacks the "two-hop" framing omits.
- **Detail**: feeds T3.

#### P6 apply(Object,Object) is overridden per-constant on all 12 constants
- **Track claim**: moving surface is "the five typed-pair `apply` overloads, the fallback `apply(Object, Object)`."
- **Search performed**: PSI `initializingClass` (constant body) inspection on the first constant; manual read of all 12 constant bodies.
- **Code location**: `SQLMathExpression.java:82-538` (each constant body declares its own `apply(Object,Object)` plus the five typed overloads).
- **Actual behavior**: Every constant overrides `apply(Object,Object)` with distinct logic (null handling, Date arithmetic in PLUS/MINUS, String concat in PLUS, `super.apply` in the eight bitwise/arith-zero cases). The base `apply(Object,Object)` (`:568`) is one more body on top of the 12. So the moving surface is 12 per-constant `apply(Object,Object)` + 12×5 typed overrides + base `apply(Object,Object)` + widening + `toLong`, not "five overloads + one fallback."
- **Verdict**: PARTIAL — the named members exist, but the inventory undercounts the per-constant override surface.
- **Detail**: feeds T4.

#### P7 SQLMathExpression.java is a JJTree node file under a build-excluded directory
- **Track claim**: "no Spotless / `pom.xml` / `--add-opens` changes (same `core` module)"; the track modifies `SQLMathExpression.Operator.apply` and treats the file as ordinary editable source.
- **Search performed**: Read file header; `git log` on the node file vs grammar; read `core/pom.xml` javacc plugin config; grep `pom.xml` Spotless + ErrorProne excludes; grep the `.jjt` grammar for the `Operator` enum body.
- **Code location**: header `SQLMathExpression.java:1,:1400`; javacc plugin `core/pom.xml:221-238` (`interimDirectory`/`outputDirectory` both `${basedir}/src/main/java`); Spotless exclude `pom.xml:640`; ErrorProne exclude `pom.xml:124`; grammar `core/src/main/grammar/YouTrackDBSql.jjt:2273-2305` (references `Operator.STAR` etc., no enum body).
- **Actual behavior**: The file is a hand-maintained JJTree node (`MULTI=true`, `NODE_PREFIX="SQL"`); JJTree does not overwrite existing node files, confirmed by the grammar (2026-03-19) being newer than the node file (2026-02-11) without regeneration. The grammar references the `Operator` constants but does not generate the enum body. `core/.../sql/parser/**` is excluded from both Spotless and ErrorProne; `sql/util/` (the new `NumericOps` home) is excluded from neither. `CLAUDE.md` Tips 3/4 say not to edit `sql/parser/` files.
- **Verdict**: PARTIAL — editing is safe (no regen, no Spotless/pom change needed for the parser file), but the directory's special status (forbidden-by-convention, no Spotless, no ErrorProne on the edited file; full Spotless+ErrorProne on the new file) is unacknowledged.
- **Detail**: feeds T2.

#### I1 No external production consumer of the Operator.apply promotion engine
- **Plan claim**: "This track is AST-side only … Track 4's evaluator is the downstream consumer that will delegate `+ - * /` to the same `NumericOps`, but Track 4 depends on this track, not the reverse." Implies no current external caller would break.
- **Actual entry point**: `Operator.apply(Object,Object)` is invoked from `SQLMathExpression.execute` (`:700,:721`), `calculateWithOpPriority` (`:745,:775`), and `iterateOnPriorities` (`:811,:822`); typed overloads and `apply(Number,Operator,Number)` are invoked only from within the enum and the base methods.
- **Caller analysis**: `ReferencesSearch` (PSI find-usages) on all apply overloads, `getPriority`, and `toLong` across `allScope`. Every reference is `[SELF]` (inside `SQLMathExpression.java`) or `[TEST]` (`MathExpressionTest.java:48-65,:50`). Zero external production callers in any other class/module.
- **Breaking change risk**: None in production. The only external caller is `MathExpressionTest`, which calls typed `op.apply(1,1)` and `op.apply(Object,Object)` directly on enum constants — so the enum must retain those signatures (delegating is fine). No SPI, no serialization of the apply methods (only `Operator.toString()`/`valueOf` are serialized, `:1381-1387`, unaffected by the lift).
- **Verdict**: MATCHES

#### E1 Self-verifying gate does not pin integer-divide widening or +-*/ null propagation
- **Trigger**: SLASH with non-zero remainder; `null + x`, `null * x`, `null / x` via `Operator.apply(Object,Object)`.
- **Code path trace**:
  1. `MathExpressionTest.testTypes` @ `:34` calls `op.apply(1, 1)` for SLASH — `left % right == 0` true → integer branch (`SQLMathExpression.java:92-94`). The `((double) left) / right` widening branch (`:95`) is never reached.
  2. No test constructs a SLASH with non-zero remainder, and no test passes a `null` operand to PLUS/MINUS/STAR/SLASH (only `NULL_COALESCING` gets null operands, `testNullCoalescing` `:202-209`).
- **Outcome**: A regression in divide-widening or `+ - * /` null propagation during the lift would not fail any existing test — the gate stays green.
- **Track coverage**: No — the track asserts the suite pins "integer-divide widening" and "null propagation."
- **Verdict**: WRONG (track's gate claim) → feeds T1.

#### E2 Self-verifying gate does not pin Date arithmetic
- **Trigger**: `Date + Long`, `Long + Date`, `Date - Long`.
- **Code path trace**:
  1. PLUS `apply(Object,Object)` Date branch @ `SQLMathExpression.java:212-215` → `toLong(left)`/`toLong(right)` → `apply(Long,Long)` → `new Date(...)`.
  2. `MathExpressionTest` never constructs a `Date` operand (helpers are `integer`, `str`, `nullExpr` only, `:184-200`).
- **Outcome**: A regression in Date arithmetic during the lift would not fail any existing test.
- **Track coverage**: No.
- **Verdict**: WRONG (track's gate claim) → feeds T1.

#### E3 Self-verifying gate does not pin String concatenation
- **Trigger**: `'a' + 1` (PLUS with a String operand).
- **Code path trace**:
  1. PLUS `apply(Object,Object)` fall-through @ `SQLMathExpression.java:216` → `String.valueOf(left) + right`.
  2. `MathExpressionTest.str(...)` (`:192`) is used only inside `testNullCoalescingGeneric` with `NULL_COALESCING`; no test drives a String operand through PLUS.
- **Outcome**: A regression in String concat during the lift would not fail any existing test.
- **Track coverage**: No.
- **Verdict**: WRONG (track's gate claim) → feeds T1.
