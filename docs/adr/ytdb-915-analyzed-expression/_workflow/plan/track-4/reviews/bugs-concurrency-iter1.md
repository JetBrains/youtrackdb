<!--MANIFEST
dimension: bugs-concurrency
prefix: BC
iter: 1
verdict: APPROVE
findings_total: 3
blockers: 0
should_fix: 0
suggestions: 3
evidence_base: { certs: 3 }
cert_index: [C1, C2, C3]
flags: [psi-parser-unreachable-in-ide-jvm]
index:
  - id: BC1
    sev: suggestion
    anchor: bc1-visitvar-dollar-variable
    loc: core/src/main/java/com/jetbrains/youtrackdb/internal/core/query/analyzed/AnalyzedExprEvaluator.java:92
    cert: C1
    basis: PSI (SQLSuffixIdentifier.execute, AnalyzedExprLowerer.lowerIdentifier) + parser-reachability caveat
  - id: BC2
    sev: suggestion
    anchor: bc2-funccall-current-vs-row
    loc: core/src/main/java/com/jetbrains/youtrackdb/internal/core/query/analyzed/AnalyzedExprEvaluator.java:176
    cert: C2
    basis: PSI (SQLMethodCall.invokeMethod, SQLModifier.executeOneLevel, SQLBaseExpression.execute)
  - id: BC3
    sev: suggestion
    anchor: bc3-funccall-context-mutation
    loc: core/src/main/java/com/jetbrains/youtrackdb/internal/core/query/analyzed/AnalyzedExprEvaluator.java:173
    cert: C3
    basis: PSI (SQLModifier.executeOneLevel) + diff
-->

## Findings

### BC1 [suggestion] `visitVar` omits the `$`-variable / `$parent` resolution the AST column lookup performs {#bc1-visitvar-dollar-variable}

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/query/analyzed/AnalyzedExprEvaluator.java` (lines 92-116)

**Issue**: `visitVar` resolves a single-segment name as `hasProperty` → result-metadata → temporary-property → `null`. Its doc says it mirrors `SQLSuffixIdentifier.execute(Result, ctx)`. The AST method runs two resolution branches *before* the record lookup that `visitVar` reproduces: a `$parent` branch and a general `varName.startsWith("$") && ctx.getVariable(varName) != null` branch (`SQLSuffixIdentifier.java:143-155`). `visitVar` has neither. The lowerer does not filter `$`-prefixed names — `AnalyzedExprLowerer.lowerIdentifier` builds `new AnalyzedExpr.Var(List.of(columnName))` from `suffix.getIdentifier().getStringValue()` with no `$` guard (`AnalyzedExprLowerer.java:251-252`). So if a `$var`/`$parent` reference parses into the `identifier.suffix` shape (rather than `levelZero`, which lowering throws), it lowers to a `Var` whose name starts with `$`, and `visitVar` would return the row property/`null` where the AST resolves the context variable — a silent round-trip parity divergence rather than the contract's typed throw.

**Failure scenario**: A context-variable comparison such as `name = $myvar` (with `$myvar` set on the `CommandContext`). The AST `SQLBinaryCondition.evaluate(Result, ctx)` slow path resolves `$myvar` to its context value; the IR `visitVar("$myvar")` finds no such row property and returns `null`, so the two sides disagree on the comparison result.

**Evidence**: PSI dump of `SQLSuffixIdentifier.execute(Result, CommandContext)` shows the `$parent` and `$`-variable branches at lines 143-155 ahead of the `hasProperty`/metadata/temporary chain at 156-168 that `visitVar` mirrors. PSI dump of `AnalyzedExprLowerer.lowerIdentifier` shows no `$`-name filter before constructing the `Var`.

**Refutation considered**: I tried to confirm whether `$parent`/`$myvar` actually parses into the `suffix`-identifier path (lowerable to a `Var`) or into `levelZero`/a record-attribute (which `lowerIdentifier` throws on at lines 243-249). The decisive test is a live parse, which I could not run: the IDE JVM does not have the project's `YouTrackDBSql` parser on its classpath (`ClassNotFoundException`), and this is a static review (no Maven/test runs permitted). If `$`-names are not lowerable to a `Var`, this gap is unreachable and harmless; if they are, it is a silent parity divergence. Either way it is not a live failure of any matrix row (the round-trip suite includes no `$`-variable fragment), so it is a coverage/scope gap, not a confirmed defect in the tested behavior — hence suggestion, not blocker.

**Suggestion**: Either (a) add the `$parent` / `$`-variable resolution branch to `visitVar` to keep the "mirrors `SQLSuffixIdentifier.execute(Result)`" contract total, or (b) if `$`-names are intended to be out of subset, make `visitVar` (or the lowerer) reject a `$`-prefixed `Var` with `UnsupportedAnalyzedNodeException` so an out-of-subset reference fails loudly instead of diverging silently. Confirm reachability against the Track 3 lowering contract / a live parse before choosing.

### BC2 [suggestion] `visitFuncCall` passes `row` as the method's `iCurrentRecord`, where the AST passes `(Result) $current` {#bc2-funccall-current-vs-row}

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/query/analyzed/AnalyzedExprEvaluator.java` (line 176)

**Issue**: The evaluator calls `method.execute(target, row, ctx, target, params)` — `iCurrentRecord = row`. The AST path it mirrors is `SQLMethodCall.invokeMethod`, which calls `method.execute(targetObjects, (Result) val, ctx, targetObjects, paramValues.toArray())` where `val = ctx.getSystemVariable(VAR_CURRENT)` — the `$current` system variable, not necessarily the row being evaluated (`SQLMethodCall.java:128, 144-156`). The AST seeds `$current` with `iCurrentRecord` *only if it is unset* (`SQLModifier.executeOneLevel`, `SQLModifier.java:145-147`); if a prior operation already set `$current` to a different record, the AST passes that record while the evaluator still passes `row`. The AST also reloads an `Identifiable`-typed `$current` via `transaction.loadEntity(...)` before the cast (`SQLMethodCall.java:150-153`); the evaluator does no such reload.

**Failure scenario**: An S1+ caller evaluates an IR `FuncCall` whose target method reads its `iCurrentRecord` argument, on a `CommandContext` whose `$current` was set to record A by an enclosing operation, against `row` = record B. The AST method sees A; the IR method sees B — divergent results. The covered S0 method (`asInteger()`) ignores `iCurrentRecord`, so no current matrix row exercises this.

**Evidence**: PSI dumps of `SQLBaseExpression.execute(Result, ctx)` (resolves the base value, then `nextModifier.execute(iCurrentRecord, result, ctx)`), `SQLModifier.executeOneLevel` (guarded `$current` seed, then `methodCall.execute(result, ctx)`), and `SQLMethodCall.invokeMethod` (passes `(Result) $current`, after an `Identifiable`→`loadEntity` reload). For a fresh context with `$current` unset and equal to `row`, all three coincide with the evaluator's call — which is why the parity suite passes.

**Refutation considered**: Is this a live bug in the tested code? No — the round-trip suite always runs on a fresh `BasicCommandContext` where `$current` is unset, so the evaluator's own guarded seed (line 173-175) makes `$current == row` and `(Result) $current == row`; the single covered method (`asInteger`) ignores `iCurrentRecord` anyway. So `funcCallMethodCoercionParity` legitimately passes and the divergence is latent, not active. It becomes observable only for an S1+ caller that reuses a context with `$current` pre-set or that lowers a method reading `$current`/its receiver record. Suggestion, not blocker.

**Suggestion**: Either pass `(Result) ctx.getSystemVariable(VAR_CURRENT)` (after the seed) as `iCurrentRecord` to match the AST exactly, or add a code comment recording that S0 deliberately passes `row` because the covered methods ignore `iCurrentRecord`, and that an S1+ method reading `$current` must revisit this. Cross-references the same S1+ obligation already recorded around D11/D16.

### BC3 [suggestion] `visitFuncCall` mutates the caller's `CommandContext` (`$current`) and never restores it {#bc3-funccall-context-mutation}

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/query/analyzed/AnalyzedExprEvaluator.java` (lines 173-175)

**Issue**: `visitFuncCall` performs `ctx.setSystemVariable(VAR_CURRENT, row)` (guarded on `$current` being unset). This is a write to caller-owned shared state that outlives the `evaluate` call — `$current` stays set to `row` after the evaluator returns. It is faithful to the AST (`SQLModifier.executeOneLevel` does the identical guarded seed and likewise never un-sets it), so it is not a regression, but it is a side effect a reader of the otherwise-pure `evaluate(...)` entry would not expect, and it is the one place the evaluator writes to anything beyond its own thread-confined fields.

**Failure scenario**: A caller evaluates `name.asInteger()` against `row` and then, on the same `CommandContext`, evaluates an unrelated expression that reads `$current` expecting it still unset — it now reads `row`. No concurrency hazard (the evaluator instance is thread-confined; the `CommandContext` is caller-owned and single-threaded by contract), and the AST has the same behavior, so this is purely a documentation/least-surprise note.

**Evidence**: Diff lines 173-175; PSI dump of `SQLModifier.executeOneLevel` (`SQLModifier.java:145-147`) confirming the AST seeds and does not restore `$current`. The evaluator instance carries only `final Result row` / `final CommandContext ctx` and is constructed-and-discarded per `evaluate` call (`AnalyzedExprEvaluator.java:60-76`), so no inter-thread publication concern.

**Refutation considered**: Could this be a concurrency bug? No — the evaluator is single-use and thread-confined; the only shared object written is the caller's `CommandContext`, which is single-threaded per its existing contract and is mutated identically by the AST today. So there is no race and no behavioral regression; the note is about the surprise of a mutating "evaluate" and is already mirrored from the AST. Suggestion.

**Suggestion**: Keep the behavior (parity requires it) but consider a one-line comment at the public `evaluate` entry noting that evaluating a `FuncCall` may seed `$current` on the passed context, matching the AST. No code change required.

## Evidence base

The comparison, arithmetic, collate, and operator-reconstruction seams were traced against the AST oracle via PSI and all confirmed parity-faithful; the three certificates below back the surviving findings. Per the YTDB-1069 roster rendering, claims whose refutation check left them CONFIRMED-as-issue are one-lined; refuted/non-passing claims are shown in full.

**Confirmed-parity claims (refutation check passed → no finding):**

- CONFIRMED-PARITY: `evaluateComparison` (lines 218-233) reproduces `SQLBinaryCondition.evaluate(Result, ctx)` slow path (`SQLBinaryCondition.java:99-109`) step-for-step — operand eval, left-then-right collate, both-operand transform, `operator.execute(ctx.getDatabaseSession(), …)`. The AST `isFunctionAny`/`isFunctionAll` pre-branches (lines 71-77) are out of the lowering subset, so they cannot reach the IR.
- CONFIRMED-PARITY: `comparisonOperator` reconstruction is behaviorally identical to the AST instance — `SQLEqualsOperator.execute` calls `QueryOperatorEquals.equals(session, …)` (real session) and `SQLNeOperator.execute` calls `QueryOperatorEquals.equals(null, …)`; neither reads the `int id` constructor arg, so `new SQLXxxOperator(-1)` reproduces the EQ/NE session difference by class identity. `BinaryOperator` has exactly the 10 constants the two switches partition, making the `default` arms dead-but-defensive, not reachable bugs.
- CONFIRMED-PARITY: `collateFor` (lines 246-270) matches `SQLSuffixIdentifier.getCollate` (`SQLSuffixIdentifier.java:505-521`) and `SQLBaseExpression.getCollate` for the single-segment subset (guard `isEntity` → `(EntityImpl) asEntity()` → `getImmutableSchemaClass` → null-guard → `getProperty(name)` → null-guard → `getCollate()`); the `(EntityImpl)` cast is the identical cast the AST performs, so any CCE on a non-`EntityImpl` entity occurs identically on both sides. Non-`Var` operands return `null`, matching `SQLBaseExpression.getCollate` returning `null` for a non-base-identifier.
- CONFIRMED-PARITY: arithmetic routes through `Operator.apply(Object, Object)` (line 187), the null-propagating object-level entry, not the throwing `NumericOps.apply(Number, …)`; `toArithmeticOperator` covers the four arithmetic constants and the `default` is unreachable on the 10-constant enum.
- CONFIRMED-PARITY: `wrap(Identifiable, session)` uses the real `ResultInternal(session, Identifiable)` constructor; `isEntity()` is true and `asEntity()` loads via the active transaction, so the wrapped path resolves collation through the same chain — the deliberate D15 convergence, exercised by the adapter tests.
- CONFIRMED-PARITY: the evaluator instance holds only two `final` fields and is constructed-and-discarded per `evaluate` call, so it is thread-confined; no unsafe publication, no shared mutable evaluator state.

**Surviving claims (one line each — refutation passed, reported as findings):**

- BC1: `visitVar` lacks the AST's `$parent`/`$`-variable branches; reachable iff a `$`-name lowers to a `Var` (parser-reachability unverifiable in the IDE JVM under the static-review constraint → flagged).
- BC2: `visitFuncCall` passes `row` where the AST passes `(Result) $current`; coincides for the fresh-context parity suite, latent for S1+ reuse.
- BC3: `visitFuncCall` seeds `$current` on the caller's context without restoring it; faithful to the AST, no race (thread-confined evaluator + single-threaded context contract), documentation-only.

#### C1 — `$`-variable resolution gap reachability
PSI: `SQLSuffixIdentifier.execute(Result, CommandContext)` lines 143-155 ($parent / $-var branches) precede the record-lookup chain at 156-168 that `visitVar` mirrors. `AnalyzedExprLowerer.lowerIdentifier` (lines 241-254) builds the `Var` from `suffix.getIdentifier().getStringValue()` with no `$` filter. **Caveat (flag `psi-parser-unreachable-in-ide-jvm`):** live parse of `$parent`/`$myvar` to confirm `suffix`-vs-`levelZero` routing could not run — `YouTrackDBSql` is absent from the IDE runtime classpath and Maven/test execution is barred for this static review. Reachability is therefore asserted from the lowerer's missing filter, not from an executed parse.

#### C2 — FuncCall `iCurrentRecord` source
PSI: `SQLBaseExpression.execute(Result, ctx)` resolves the base value then calls `nextModifier.execute(iCurrentRecord, result, ctx)`; `SQLModifier.executeOneLevel` (lines 144-162) guard-seeds `$current = iCurrentRecord` then `methodCall.execute(result, ctx)`; `SQLMethodCall.execute`→`invokeMethod` (lines 122-156) reads `val = $current`, reloads an `Identifiable` via `loadEntity`, and calls `method.execute(targetObjects, (Result) val, ctx, targetObjects, params)`. The evaluator passes `row` for the `(Result) val` slot. Coincidence holds when `$current` is unset and equals `row` (the suite's case).

#### C3 — context mutation / thread-confinement
PSI: `SQLModifier.executeOneLevel` lines 145-147 — guarded `$current` seed, never restored (AST parity). Diff: `AnalyzedExprEvaluator` fields are `final Result row` / `final CommandContext ctx` (lines 60-66), instance constructed in the static `evaluate` (lines 74-76) and discarded — thread-confined; the only cross-call write is `ctx.setSystemVariable(VAR_CURRENT, row)` on the caller-owned, single-threaded `CommandContext`.
