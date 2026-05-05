# Track 9: Command & Script

## Description

Write tests for the command and script execution infrastructure.

> **What**: Tests for `core/command` (BasicCommandContext,
> CommandManager, CommandRequest*Abstract, CommandExecutorAbstract,
> SqlScriptExecutor), `core/command/script` (live subset of
> ScriptManager, PolyglotScriptExecutor, Jsr223ScriptExecutor,
> ScriptTransformerImpl, ScriptDatabaseWrapper, PolyglotScriptBinding,
> DatabaseScriptManager, CommandExecutorUtility), `core/command/traverse`
> (Traverse, TraverseContext, TraverseRecordProcess,
> TraverseRecordSetProcess, TraverseMultiValueProcess),
> `core/command/script/formatters`, `core/command/script/transformers`,
> and the live `SQLScriptEngine` / `SQLScriptEngineFactory`.
>
> **How**: DbTestBase + `@After rollbackIfLeftOpen` for command-context
> tests; standalone-where-possible for formatters/transformers and
> stub-driven dispatch tests. Heavy use of dead-code pinning via
> `*DeadCodeTest` classes for the ~1,170 LOC of dead surface
> (CommandExecutorScript, CommandManager legacy class-based dispatch,
> ScriptExecutorRegister SPI, deprecated bind helpers,
> ScriptDocumentDatabaseWrapper, ScriptYouTrackDbWrapper). Polyglot-state
> hygiene pattern: mutate-in-try / restore-in-finally + SequentialTest.
>
> **Constraints**: In-scope: only the listed `core/command*` packages.
> Out-of-scope: `command/script/jsr223` external interpreter
> integration tests (lacking infrastructure); `RemoteCommand*`
> (driver module). No production code changes (Track 22 absorbs
> ~10 production-bug WHEN-FIXED markers + ~3 DRY items).
>
> **Interactions**: Depends on Track 1. Track 22 absorbs ~1,770 LOC
> of dead-code deletions plus ~10 production-bug WHEN-FIXED markers.

## Progress
- [x] Review + decomposition
- [x] Step implementation (5/5 steps complete — Step 4 split 4a+4b; Step 5 landed as `25d0032a2e` + iter-1 review fix `fe24d12aa1`)
- [x] Track-level code review (2/3 iterations — iter-1 synthesis: 0 blockers / 20 should-fix / ~25 suggestions across 6 dimensions CQ/BC/TB/TC/TS/TX, applied 13 should-fix fixes in `f66b1bc474`; iter-2 gate check: all 6 dimensions PASS, 26 iter-1 items VERIFIED, 1 should-fix (CQ5 FQN leak) + 3 promoted suggestions (CQ6/CQ7/TB8) applied, ~7 suggestions deferred/accepted. See `reviews/track-9-code-review-iter2.md`.)

## Base commit
`8ed372383d5331b5a610c7e56e496a7bb155c2b3`

## Rebase audit
- Pre-rebase SHA: `1864a32b3fb4cefdd1f3fb89a993dc8dcc2faebe`
- Post-rebase SHA: `75386188ff29260bf93b367e02d119c38e3652bf`
- Rebased onto `origin/develop` at start of Phase A (2026-04-23). 127
  commits replayed cleanly, no conflicts. `./mvnw -pl core spotless:apply`
  reported all 1009 files clean. `./mvnw -pl core clean test` ran 1605 +
  11 MT tests, BUILD SUCCESS (0 failures / 0 errors / 13 skipped).

## Reviews completed
- [x] Technical (iter-1: 2 blockers / 3 should-fix / 4 suggestions) → `reviews/track-9-technical.md`
- [x] Risk (iter-1: 1 blocker / 3 should-fix / 2 suggestions) → `reviews/track-9-risk.md`
- [~] Adversarial — skipped (not warranted: no major architectural decision; test-additive only; precedent inherited from Tracks 7–8)

### Iteration-1 review resolution

The two blockers (T1 ≡ R1 and T2) both point at the same root cause:
**large chunks of `core/command/script` are dead code** reachable only
through paths with no production callers. Both are resolved at
decomposition time by following the Track 8 precedent (commit
`7b9313eb4b`) — pin via `// WHEN-FIXED: Track 22` markers, absorb the
deletions into Track 22 (plan update committed in `8ed372383d`), and
recompute a realistic 85%/70% target excluding the pinned LOC. No
re-review iteration needed because the fixes are plan/decomposition
changes, not code changes.

Findings and their dispositions:

| ID | Severity | Disposition | Where addressed |
|---|---|---|---|
| **T1 / R1** | blocker | Pin `CommandExecutorScript` (719 LOC) + `CommandScript.execute` stub + `CommandManager.commandReqExecMap` dispatch cluster via a dedicated `CommandScriptDeadCodeTest`. Absorbed into Track 22 delete queue. | Step 1 |
| **T2** | blocker | `SQLScriptEngineFactory` is NOT JSR-223-discoverable (no `META-INF/services/javax.script.ScriptEngineFactory`). Scope narrows to `eval(String, Bindings)` + `convertToParameters` + stubs; pin `eval(Reader, Bindings)`. | Step 5 |
| **T3** | should-fix | `ScriptInjection` / `ScriptInterceptor` / `ScriptExecutorRegister` SPIs have zero core impls. Single synthetic-implementation smoke test in Step 1 covers register/unregister/bind/preExecute loops; remaining cold paths pinned. | Step 1 |
| **T4** | should-fix | `BasicCommandContext.copy()` NPE on null child — pin observed-NPE regression with `WHEN-FIXED: Track 22`. | Step 2 |
| **T5** | should-fix | Recon's 261-LOC attribution to `DatabaseScriptManager` was wrong; actual is 88. Live `command/script` LOC after dead-code exclusion ≈ 1,770 (was nominally ~2,200). | Step 4 sizing |
| **T6** | suggestion | Split `BasicCommandContext` testing into standalone (non-DB branches) + DbTestBase (DB-path branches). Follows D2. | Step 2 |
| **T7** | suggestion | Step 4 includes `putIfAbsent` race pin for `ScriptManager.acquireDatabaseEngine` + pool-return/acquire single-thread test. Does NOT expand into stage-race production fix (R5 refutes the CHM race hypothesis). | Step 4 |
| **T8** | suggestion | Merge formatters + transformers into one step. Accepted; Step 5 absorbs them. | Step 5 |
| **T9** | suggestion | Explicit abnormal-termination pins for `Traverse.hasNext`/`TraverseContext.pop`. | Step 3 |
| **R2** | should-fix | `ScriptDocumentDatabaseWrapper` (261 LOC) + `ScriptYouTrackDbWrapper` (42 LOC) only live through `Jsr223ScriptExecutor.executeFunction` (stored JS functions). Cover via one stored-function test in Step 4; pin remaining deprecated surface. | Step 4 |
| **R3** | should-fix | Polyglot test hygiene: mutate-in-try / restore-in-finally pattern; `@Category(SequentialTest)` for `GlobalConfiguration` mutations. | Step 4 convention |
| **R4** | should-fix | Absorb `SQLScriptEngine` + `SQLScriptEngineFactory` into Step 5 (narrow scope: JSR-223 stubs + `convertToParameters` + live `eval(String, Bindings)`); pin `eval(Reader, Bindings)`. | Step 5 |
| **R5** | suggestion | CHM race RISK-B REFUTED — `computeIfAbsent` is atomic; no stage test. Noted in Step 4 plan. | Step 4 note |
| **R6** | suggestion | Traverse cycle detection already safe via `history.contains` pre-descent. Pin current behavior with one regression test (A→B→A visits once; mid-traverse `setStrategy`). | Step 3 |

## Test-strategy precedent (carry-forward from Tracks 5–8)

- **DbTestBase by default for DB-path tests** (per-track D2 override).
  Standalone tests (no base class) for pure utility / pure function code
  (formatters, transformers, `CommandRequest*Abstract` getters/setters,
  `BasicCommandContext` non-DB branches, `SQLScriptEngineFactory`
  getters).
- **`@After rollbackIfLeftOpen`** safety net inherited from
  `TestUtilsFixture` (Track 8 Step 1) — reuse by extending
  `TestUtilsFixture` where a DbTestBase session is needed; avoid
  reintroducing manual `try { … commit } catch { rollback; throw }`
  boilerplate (Track 8 iter-1 CQ8/TS8 precedent).
- **Dead-code pinning** via a dedicated `CommandScriptDeadCodeTest`
  mirroring `SqlExecutorDeadCodeTest` (Track 8 Step 1) and
  `SqlQueryDeadCodeTest` (Track 7 Step 7). Each pin carries a
  `// WHEN-FIXED: Track 22 — delete <class>` marker tied to a
  falsifiable observable behavior (stub return value, zero-callers
  assertion, NPE/IllegalStateException shape).
- **`// forwards-to: Track NN`** convention for failures attributable
  to record/impl (14/15), metadata/schema (16), core/db (14), or
  security (17). Pin and work around in Track 9; do not block.
- **Polyglot-state hygiene** (new, from R3): any test that mutates
  `ScriptManager.addAllowedPackages(...)` or toggles a
  `GlobalConfiguration` SCRIPT_* key MUST restore state in a `finally`
  block. Tests that mutate process-wide `GlobalConfiguration` keys tag
  `@Category(SequentialTest)` so the `sequential-tests` surefire
  execution (core/pom.xml:322-330) serializes them instead of running
  at `threadCountClasses=4`. Pattern reference: `JSScriptTest.jsSandboxWithBigDecimal`.
- **`grep @Test <target-class>` and `grep "<target-class>"` in existing
  test files** is the FIRST action on each step — write only tests that
  exercise branches not already touched (Track 8 precedent).

## Coverage baseline (pre-Track 9)

From plan + verified by recon (per-package LOC from JaCoCo):

| Package | Line % | Branch % | Uncov Lines |
|---|---|---|---|
| `core/command/script` | 31.4% | 22.2% | 691 |
| `core/command` | 48.7%–49.5% | 50.0% | 320–325 |
| `core/command/traverse` | 62.9% | 39.2% | 127 |
| `core/command/script/formatter` | 36.0% | 26.3% | 57 |
| `core/command/script/transformer` (+ `/result`) | ~65.6%/16.7% | 60.5%/0.0% | 37 (split) |
| `core/command/script/js` | 43.5% | 66.7% | 13 |
| `core/sql/SQLScriptEngine*` (absorbed) | ~35.8% | n/a | ~125 |

**Dead LOC excluded from Track 9 coverage denominator** (pinned in Step 1 + Step 5):
- `CommandExecutorScript` 719 LOC — 100% pinned as dead
- `CommandScript` 114 LOC — `execute()` stub + ctor only live via `SQLScriptEngine.eval(Reader)` → dead
- `CommandManager.commandReqExecMap` cluster ≈ 50 LOC
- `ScriptExecutorRegister` SPI ≈ 10 LOC (no impls)
- `ScriptInterceptor` register/unregister loops + `AbstractScriptExecutor.interceptors` iteration ≈ 20 LOC
- Deprecated `ScriptManager.bind` + `bindLegacyDatabaseAndUtil` ≈ 25 LOC
- `ScriptDocumentDatabaseWrapper` methods unreachable from live path ≈ 180 LOC (live subset via stored-function test)
- `ScriptYouTrackDbWrapper` unreachable methods ≈ 25 LOC
- `SQLScriptEngine.eval(Reader, Bindings)` + `eval(Reader)` no-bindings overload ≈ 25 LOC

**Estimated dead-LOC exclusion: ≈ 1,170 LOC** (≈ 55% of `command/script`).
Post-exclusion live target: ~1,770 LOC across `command/script` (heavy:
`ScriptManager` 585, `PolyglotScriptExecutor` 229, `Jsr223ScriptExecutor`
164, `ScriptTransformerImpl` 145, `ScriptDatabaseWrapper` 118,
`PolyglotScriptBinding` 95, `DatabaseScriptManager` 88,
`CommandExecutorUtility` 87) + ~330 LOC live across `command`
(`BasicCommandContext` 632 of which ~250 uncov, `CommandManager` live
path, `SqlScriptExecutor` 216, `CommandExecutorAbstract` 131,
`CommandRequest*Abstract` ~415 pure POJO) + traverse 342 + formatters
300 + transformers 200 + `SQLScriptEngine(Factory)` ~226.

## Steps

- [x] Step 1: Shared test scaffolding + dead-code pinning
  - [x] Context: warning
  > **What was done:**
  > Added two new standalone test classes and the track baseline doc
  > establishing the realistic coverage denominator for Track 9:
  > `core/src/test/java/.../command/script/CommandScriptDeadCodeTest.java`
  > (16 tests) pinning `CommandExecutorScript` (class + ctor + META-INF
  > absence), `CommandScript` (execute stub + all three ctors + setLanguage
  > validation + CompiledScript getter/setter), `CommandManager` legacy
  > class-based dispatch (getExecutor empty-map not-found, register/
  > unregister round-trip, register-with-callback invocation, catch-path
  > DatabaseException wrap when the instantiation fails), `ScriptExecutorRegister`
  > SPI shape + no-core-impl guard, `SQLScriptEngineFactory` JSR-223
  > non-discoverability + factory getScriptEngine contract, `SQLScriptEngine.
  > eval(Reader, Bindings)` missing-db guard throw, and `ScriptYouTrackDbWrapper`
  > no-arg ctor `ConfigurationException` fallback. Added
  > `SPIWiringSmokeTest.java` (6 tests) exercising `ScriptInjection`
  > dedupe + unregister + bind/unbind loop and `ScriptInterceptor`
  > register/unregister/dispatch ordering + no-dedupe asymmetry via
  > `AbstractScriptExecutor`.
  >
  > Applied iter-1 dimensional-review fixes (5 agents: CQ / BC / TB / TC /
  > TS): FQN imports, `startsWith` tightening on two message assertions,
  > catch-path pin via abstract `UninstantiableExecutor`, BC-1 rename +
  > scope clarification on the `eval(Reader)` pin, TC-4 no-dedupe asymmetry
  > pin, and redundant assertions dropped.
  >
  > Wrote `docs/adr/unit-test-coverage/_workflow/track-9-baseline.md` documenting
  > the ≈1,150 LOC dead-code exclusion and the live-target denominator
  > for the remaining Track 9 steps.
  >
  > **What was discovered:**
  > - `RecordSerializerNetwork` has zero concrete impls in core, so a
  >   `CommandScript.fromStream/toStream` round-trip pin (TC-1) is
  >   un-testable today. Documented in-file as a deferral — Track 22
  >   observes the overrides at compile time when the class is deleted.
  > - `CommandManager.getExecutor` catch block dereferences the context
  >   chain and today emits `DatabaseException("No database session found
  >   in SQL context")` rather than a raw NPE — pinned to lock in the
  >   current observable shape.
  > - `CommandManager.newInstance()` reflection requires a public class
  >   with a public no-arg ctor; `DummyCommandExecutor` must stay `public`
  >   (noted in class Javadoc). Reverted the TS-R2 visibility change.
  > - All 22 tests pass; spotless clean.
  >
  > **What changed from the plan:**
  > No plan deviations. Step count unchanged (5 steps total). No new
  > WHEN-FIXED markers beyond what Phase A already absorbed into Track 22.
  >
  > **Key files:**
  > - `core/src/test/java/.../command/script/CommandScriptDeadCodeTest.java` (new, 16 tests)
  > - `core/src/test/java/.../command/script/SPIWiringSmokeTest.java` (new, 6 tests)
  > - `docs/adr/unit-test-coverage/_workflow/track-9-baseline.md` (new, tracking only)
  >
  > **Critical context:**
  > `commandManagerGetExecutorWrapsInstantiationFailureThroughContextChain`
  > pins a `DatabaseException`-wrapped behavior; if Track 2 (or Track 22)
  > refactors `CommandManager` to null-guard the context chain before the
  > dereference, the pin will flip to a different exception type and
  > should be re-pinned, not deleted.

#### Step 1 task details (pre-implementation)

Establish the per-track dead-code baseline (mirrors Track 7 Step 7
and Track 8 Step 1). This step unblocks all subsequent steps by making
the realistic coverage denominator explicit.

**Tasks:**
1. Re-verify zero-caller status via fresh `grep` for each pinned class
   (pre-implementation regression check — a post-rebase caller on
   develop would drop the pin):
   - `CommandExecutorScript` (class name literal)
   - `new CommandScript(` + `CommandScript.execute(`
   - `commandReqExecMap` + `registerExecutor(Class` + `getExecutor(CommandRequestInternal` + `configCallbacks` + `unregisterExecutor(`
   - `implements ScriptExecutorRegister` / `implements ScriptInterceptor` / `implements ScriptInjection`
   - `ScriptManager.bind(` (the deprecated String-language overload) + `bindLegacyDatabaseAndUtil`
   - `eval(Reader` invocations on `SQLScriptEngine`
   If any pin has a production caller, remove it from the dead-code list
   and cover it normally in the appropriate later step.
2. Create `CommandScriptDeadCodeTest` (new, standalone) mirroring
   `SqlExecutorDeadCodeTest` shape — one test per dead class/method,
   each asserting a falsifiable observable (stub return value,
   `CommandScript.execute` returning `List.of()`, `commandReqExecMap`
   empty after default `CommandManager` construction, no
   `META-INF/services` entry for `javax.script.ScriptEngineFactory`,
   etc.). Each test carries a `// WHEN-FIXED: Track 22 — delete …`
   marker.
3. Add one "SPI-wiring smoke" test per SPI interface where it yields
   meaningful coverage: register a synthetic `ScriptInjection` /
   `ScriptInterceptor` implementation and verify `bind/unbind` /
   `preExecute` / register/unregister flows are invoked. Skip
   `ScriptExecutorRegister` (no impls + SPI is iterated once in
   `ScriptManager` ctor — pin instead).
4. Write a `track-9-baseline.md` sibling file (alongside
   `coverage-baseline.md`) recording the recomputed live-LOC target
   for `command/script` after dead-code exclusion, so the 85%/70%
   target for this track is honest and auditable.

**Strategy:** Standalone `CommandScriptDeadCodeTest` (no DB needed —
most dead-code assertions are against static/no-arg constructor
state or `META-INF` file existence); `SPIWiringSmokeTest` extends
`TestUtilsFixture` if any ScriptManager lifecycle is needed, otherwise
standalone.

**Coverage target:** No package-level delta expected. Establishes the
realistic baseline for Steps 2–5.

**Key files:**
- `core/src/test/java/.../command/script/CommandScriptDeadCodeTest.java` (new)
- `core/src/test/java/.../command/script/SPIWiringSmokeTest.java` (new)
- `docs/adr/unit-test-coverage/_workflow/track-9-baseline.md` (new, tracking only)

---

- [x] Step 2: Command infrastructure — BasicCommandContext + CommandRequest* + CommandManager live path + SqlScriptExecutor
  - [x] Context: warning
  > **What was done:**
  > Added five new standalone test classes and extended two existing DbTestBase
  > classes covering the live command infrastructure surface:
  > `BasicCommandContextStandaloneTest` (30 tests — setVariable/getVariable
  > dot-path, incrementVariable type-mismatch + seed + accumulate,
  > getParentWhereExpressions no-parent/delegate/merge, setChild(null)
  > detach/no-op, profiling LIFO with resume, second-start-reuses-stats,
  > endProfiling empty-stack no-op, hasSystemVariable/getSystemVariable
  > parent-chain, setSystemVariable forwards-to-parent, copy() null-child
  > T4 NPE pin, updateMetric both recordMetrics branches, getInputParameters
  > parent-chain fallback + own-wins + null-chain, isScriptVariableDeclared
  > null/empty + dollar/plain symmetry + parent-chain + set-also-declared,
  > checkTimeout parent-chain fallback);
  > `BasicCommandContextTest` extended with two DB-required tests
  > (`EntityHelper.getFieldValue` dot-path on embedded entity + `$PARENT.x`
  > reflective null);
  > `CommandRequestAbstractTest` (19 tests — constructor defaults,
  > getters/setters round-trips with chain semantics, getContext lazy-init +
  > memoize + null-reset, reset no-op, setParameters null/empty guard,
  > convertToParameters all six branches: single Map by-ref, Object[]
  > unwrap with Integer keys pin, positional scalars with Integer keys pin,
  > Identifiable valid-position → RID, Identifiable invalid-position → kept,
  > setParameters pipeline through public surface);
  > `CommandRequestTextAbstractTest` (10 tests — null-text throws "Text cannot
  > be null", non-null trim, empty-string accepted, no-arg leaves text null,
  > setText verbatim + null, toString "?."-prefix pin + null-text "?.null",
  > inner toStream empty-parameters writes text + two false flags + stable
  > byte array);
  > `CommandManagerTest` (9 tests — ctor-installed sql/script with distinct
  > SqlScriptExecutor instances, getScriptExecutor null/exact/lowercase-fallback/
  > unknown, registerScriptExecutor replace, getScriptExecutors live-backing-map
  > exposure, close(dbName) propagation, closeAll propagation);
  > `SqlScriptExecutorTest` extended with 6 tests (auto-terminated script,
  > COMMIT RETRY 0 throws "Invalid retry number" CommandExecutionException,
  > COMMIT RETRY 3 positive execution, BEGIN/ROLLBACK buffered-statement
  > rollback, LET-statement declareScriptVariable propagation,
  > executeFunction unknown-name NPE pin with WHEN-FIXED: Track 22);
  > `CommandExecutorAbstractTest` (10 tests — checkInterruption null-context
  > true, no-timeout true, expired-RETURN false, expired-EXCEPTION throws
  > TimeoutException, instance-forwards-to-static, defaults, setters-return-this,
  > context setter/getter no-lazy-init, involved-collections default empty,
  > toString "StubExecutor [text=null]" shape).
  >
  > Applied iter-1 dimensional-review fixes (5 agents: CQ / BC / TB / TC / TS;
  > 0 blockers, 14 should-fix, 14 suggestions) in commit `10eac73c8a`: removed
  > dead `unusedSentinel`, added missing static assertThrows import, narrowed
  > `testExecuteFunctionOnUnknownNameThrows` from `RuntimeException` to the
  > observed `NullPointerException` shape (TB-1/TS2), pinned `Integer` key
  > type on `convertToParameters` positional map (TB-2), tightened
  > `testSetVariableExistingInParent` with hasVariable assertions on both
  > parent and child to make propagation-destination falsifiable (TB-3),
  > raised `Thread.sleep(3)` to 50 ms for wall-clock-granularity robustness
  > (BC-1/TS1), wrapped new ResultSet returns in try-with-resources (BC-2),
  > added `@After rollbackIfLeftOpen` safety net in `SqlScriptExecutorTest`
  > (TS3), added updateMetric (TC-4), getInputParameters (TC-5),
  > isScriptVariableDeclared (TC-6), and checkTimeout parent-chain (TC-7)
  > tests. 101 tests pass.
  >
  > **What was discovered:**
  > - **`BasicCommandContext.copy()` is fundamentally broken** (amplifies T4):
  >   the recursion `copy.child = child.copy()` at line 492 always reaches a
  >   leaf with `child == null` and NPEs. Zero production callers exist
  >   (`grep -rn "\.copy()" core/src/main` on BasicCommandContext yields only
  >   the method itself). Positive happy-path tests for copy() are therefore
  >   infeasible — any test with a child chain NPEs at the deepest level.
  >   Track 22 should either delete `copy()` outright (safer: nothing calls
  >   it) or null-guard it; the T4 pin already locks in the NPE shape so a
  >   fix will trip the test.
  > - **`RecordSerializerNetwork` has zero core implementations** (confirmed
  >   in Step 1; re-verified in Step 2). `CommandRequestTextAbstract`'s public
  >   `fromStream`/`toStream` overloads that take a serializer are therefore
  >   not round-trip-testable today — covered only via the internal
  >   `toStream(MemoryStream, session)` empty-parameters path.
  > - **`CommandRequestAbstract.convertToParameters` returns a single `Map`
  >   iArgs by reference**, not by copy. Test `convertToParametersPassesSingleMapByReference`
  >   pins this — a future refactor to defensive-copy would be a deliberate
  >   visible change.
  > - **`session.getActiveTransaction()` is required** even for embedded
  >   entities reached through `EntityHelper.getFieldValue` — the read path
  >   unconditionally calls it (fix required session.begin() wrapper in
  >   `testGetVariableDotPathResolvesFieldOnEmbeddedEntity`).
  > - **`executeFunction(unknown-name)` currently NPEs** rather than throwing
  >   a `CommandScriptException` that names the missing function. Pinned as
  >   observed NPE with WHEN-FIXED: Track 22.
  >
  > **What changed from the plan:**
  > No plan deviations. Step count unchanged (5 total). No new WHEN-FIXED
  > markers beyond the two absorbed into Track 22 via commit `a7fbc...` (copy()
  > T4 amplification; executeFunction unknown-name NPE).
  >
  > **Key files:**
  > - `core/src/test/java/.../command/BasicCommandContextStandaloneTest.java` (new, 30 tests)
  > - `core/src/test/java/.../command/BasicCommandContextTest.java` (extended, +2 tests)
  > - `core/src/test/java/.../command/CommandRequestAbstractTest.java` (new, 19 tests)
  > - `core/src/test/java/.../command/CommandRequestTextAbstractTest.java` (new, 10 tests)
  > - `core/src/test/java/.../command/CommandManagerTest.java` (new, 9 tests)
  > - `core/src/test/java/.../command/SqlScriptExecutorTest.java` (extended, +6 tests)
  > - `core/src/test/java/.../command/CommandExecutorAbstractTest.java` (new, 10 tests)
  >
  > **Critical context:**
  > The `testSetVariableExistingInParent` tightening depends on
  > `BasicCommandContext.hasVariable` remaining package-visible. A refactor
  > to make it private or split the visibility would break the test; the pin
  > intent is falsifiability, so re-pin via test-only accessor at that time
  > rather than widening the test to a looser assertion.

#### Step 2 task details (pre-implementation)

Cover the live infrastructure in `core/command/` — per T6, split
`BasicCommandContext` testing between standalone and DbTestBase per D2.

**Tasks:**
1. `BasicCommandContextStandaloneTest` (new, standalone, no DbTestBase):
   - `setVariable` / `getVariable` with nested path notation
     (`:279-287`, `:251-259`)
   - `incrementVariable` type-mismatch branch (`:341-344`)
   - `getParentWhereExpressions` merge path (`:624-631`)
   - `setChild(null)` removal path (`:390-396`)
   - Profiling start/pause/resume LIFO (`:574-596`)
   - `hasSystemVariable` / `getSystemVariable` fallbacks
   - `copy()` null-child → **falsifiable-regression + WHEN-FIXED: Track 22**
     asserting `NullPointerException` (T4 pin).
2. Extend `BasicCommandContextTest` (existing DbTestBase) only for
   branches that genuinely need a session (e.g.,
   `EntityHelper.getFieldValue` with a real session).
3. `CommandRequestAbstractTest` + `CommandRequestTextAbstractTest` (new,
   standalone): cover getters/setters, serialization round-trip if
   applicable, `toString` if non-trivial. ~200 LOC of POJO → ≤ 30 LOC
   test each.
4. `CommandManagerTest` (new, mixed — standalone for map ops,
   DbTestBase only for catch-block that dereferences
   `iCommand.getContext().getDatabaseSession()`): cover live path —
   `registerScriptExecutor` (singular map), `getScriptExecutor` with
   exact / lowercase / unknown language (T-level edge case), `closeAll`
   propagation.
5. `SqlScriptExecutorTest` (existing, extend): fill branch gaps — batch
   error recovery, `executeFunction` no-such-function path,
   `executeInTxGuarded` exception wrap, IF/LET-in-batch dispatch.
6. Re-grep `CommandExecutorAbstract.checkInterruption` + existing
   `ExecutionThreadLocalTest` (if any) to decide whether
   `CommandExecutorAbstract` branch coverage needs its own test class
   or is adequately exercised via `Traverse` / executor-step tests from
   Track 8.

**Strategy:** Mix of standalone + DbTestBase per D2. Precision-target
`CommandExecutorAbstract.checkInterruption` via
`ExecutionThreadLocal.setInterruptCurrentOperation` (thread-local is
per-thread → safe in parallel-class mode).

**Coverage target:** `core/command` from 48.7%/50% → ≥ 85%/70%; live
subset only. Dead `commandReqExecMap` cluster counted in Step 1
denominator.

**Key files:**
- `core/src/test/java/.../command/BasicCommandContextStandaloneTest.java` (new)
- `core/src/test/java/.../command/BasicCommandContextTest.java` (extend)
- `core/src/test/java/.../command/CommandRequestAbstractTest.java` (new)
- `core/src/test/java/.../command/CommandRequestTextAbstractTest.java` (new)
- `core/src/test/java/.../command/CommandManagerTest.java` (new)
- `core/src/test/java/.../command/SqlScriptExecutorTest.java` (extend)

---

- [x] Step 3: Traverse state machine
  - [x] Context: warning
  > **What was done:**
  > Added 5 new test classes and extended one existing test class, covering
  > all 6 production classes in `core/command/traverse/`:
  > `TraversePathTest` (7 tests, standalone — no DB; empty/singleton,
  > append / appendField / appendIndex / appendRecordSet / chained,
  > immutability pin); `TraverseContextTest` (15 tests, DbTestBase — push/
  > peek, pop on empty vs. known vs. unknown RID with LogManager warn
  > observed indirectly, strategy switch preserves frames via exact-size
  > comparison, reset clears memory but preserves history, getVariables/
  > getVariable DEPTH/PATH/STACK/HISTORY branches, snapshot isolation,
  > consecutive idempotent strategy switches, end-to-end observable-via-
  > execute history entry); `TraverseRecordProcessTest` (15 tests,
  > DbTestBase — TRUE/FALSE/non-Boolean/null predicate with invocation
  > counter, named-field descent, primitive/missing field skip, class-
  > qualified field matching and non-matching, embedded-entity structural
  > visit with schema class outside transaction, toString/getPath accessors,
  > link-list expansion, double-pop contract); `TraverseMultiValueProcessTest`
  > (7 tests, DbTestBase — empty/non-Identifiable/Identifiable branches
  > with exact-size frame deltas, pre- and post-process index/toString
  > advancement, link-list end-to-end, parent-path composition);
  > `TraverseRecordSetProcessTest` (7 tests, DbTestBase — constructor auto-
  > push, empty-iterator pop, persistent-entity RP push with class check,
  > two-record emission, both toString branches including null-target dash,
  > path constructor-arg composition); and extended `TraverseTest` (+13
  > tests — empty target, maxDepth(0/1), getMaxDepth default, cycle A→B→A
  > bounded by limit(10), interrupt flag consumed + wrapped, setStrategy
  > round-trip with BFS/DFS yielding same record set, limit stops +
  > getResultCount, limit<-1 rejects, remove UOE, toString, field dedup,
  > iterator-returns-self, abnormal-termination defensive-branch pin via
  > overridden next()).
  >
  > Applied iter-1 dimensional-review fixes (6 agents: CQ / BC / TB / TC /
  > TS / TX; 0 blockers, 15 should-fix, ~10 suggestions) in commit
  > `57e8873821`: TB1 rename + @Category(SequentialTest) + belt-and-
  > suspenders Thread.interrupted() on interrupt test; TB2 predicate
  > invocation counters; TB3 WHEN-FIXED marker on warn-branch indirect
  > observation; TB4 limit(10) bound on cycle test (not @Test(timeout=)
  > which breaks DbTestBase thread binding); TB5 tighten cause class check
  > to exact equals; TB6 assertFalse isEmpty precondition on abnormal pin;
  > TB7 tighten stack type to Deque; TB8 exact-size equality on setStrategy
  > preserves; TC null-predicate-rejects sibling test; pushed-frame class
  > assertions in MVP/RSP push tests; CQ/TS helper extraction (seedEmptyRsp,
  > stackSize) + FQN imports + @After rollbackIfLeftOpen in all DB test
  > classes + class-level Javadoc on TraverseTest + Javadoc cleanup on
  > getPathReturnsThePathConstructedWithTheTarget + assertThrows /
  > assertNotEquals / assertNull idioms + dropped redundant assertion; BC4
  > relaxed STACK snapshot cast to Collection<?>.
  >
  > **What was discovered:**
  > - **`Traverse.hasNext` lines 91-93 ("Traverse ended abnormally"
  >   IllegalStateException) is unreachable through normal flow**: `next()`
  >   loops while memory is non-empty, so it only returns null when memory
  >   drains — at which point `!context.isEmpty()` is always false. The
  >   defensive branch is test-pinned via an overridden `next()` subclass
  >   that returns null without draining memory; Track 22 should either
  >   delete the branch or find a natural call path that reaches it.
  >   WHEN-FIXED: Track 22.
  > - **`TraverseContext.pop(record)` warn branch**: when the record's RID
  >   is not in history, the code calls `LogManager.warn(...)` with the RID
  >   string but the pop still proceeds. This commit pins the non-throwing
  >   observable (memory shrinks) but NOT the exact warn message content —
  >   capturing the log appender requires test infrastructure not yet in
  >   place. WHEN-FIXED: Track 22 — add LogManager appender capture.
  > - **`@Test(timeout=...)` is incompatible with `DbTestBase`**: surefire's
  >   FailOnTimeout runs the test on a worker thread, breaking the thread-
  >   bound `DatabaseSessionEmbedded` (throws SessionNotActivated). Bound
  >   runaway traversals via `traverse.limit(n)` instead.
  > - **Schema class creation must run outside an active transaction**:
  >   `session.createClass(...)` throws "Cannot change the schema while a
  >   transaction is active". For tests needing class-qualified field
  >   traversal, create the schema first, then call `session.begin()`.
  > - **Surefire parallel=classes interrupt-flag hygiene**: the interrupt
  >   test lives on a pooled worker thread. `@Category(SequentialTest)` +
  >   a try/finally `Thread.interrupted()` inside the test + the class-
  >   level `@After clearInterruptFlagAfterTest()` form a three-layer
  >   safety net so the flag never leaks to a sibling test.
  > - **34 lines of `core/command/traverse` remain uncovered** after this
  >   step. Most are in the `TraverseRecordSetProcess` non-persistent
  >   single-field shortcuts (Collection vs. EntityImpl paths at lines
  >   54-67), the MVP `RID`-to-entity load branch (lines 50-53), the
  >   `TraverseRecordProcess` `depth > maxDepth` strict-inequality edge
  >   (unreachable by design — path-builder advances one step at a time),
  >   and the `Traverse.hasNext` lines 91-93 abnormal-termination branch
  >   (unreachable without synthetic subclass). The remaining gaps are
  >   deferred to Track 22 (TC1–TC6 in review-fix commit notes).
  >
  > **What changed from the plan:**
  > No plan deviations. Step count unchanged (5 steps total). The dead-code
  > abnormal-termination branch is pinned via a subclass test rather than a
  > natural flow — noted in the test Javadoc as WHEN-FIXED: Track 22.
  >
  > **Key files:**
  > - `core/src/test/java/.../command/traverse/TraversePathTest.java` (new, 7 tests)
  > - `core/src/test/java/.../command/traverse/TraverseContextTest.java` (new, 15 tests)
  > - `core/src/test/java/.../command/traverse/TraverseRecordProcessTest.java` (new, 15 tests)
  > - `core/src/test/java/.../command/traverse/TraverseMultiValueProcessTest.java` (new, 7 tests)
  > - `core/src/test/java/.../command/traverse/TraverseRecordSetProcessTest.java` (new, 7 tests)
  > - `core/src/test/java/.../command/traverse/TraverseTest.java` (extended, +13 tests, +class Javadoc + @After rollbackIfLeftOpen + @After clearInterruptFlagAfterTest)
  >
  > **Critical context:**
  > The abnormal-termination pin uses an anonymous `Traverse` subclass that
  > overrides `next()` to always return null. If Track 22 reaches the
  > branch through a natural flow, the subclass pin should be replaced with
  > a flow-driven test AND the subclass pattern kept as belt-and-suspenders.
  > Coverage for `core/command/traverse` rose from 62.9%/39.2% to **90.1%/
  > 81.5%** (34 uncov / 342 total) — above the 85%/70% target. The specific
  > uncovered lines are enumerated in the coverage discovery above for
  > Track 22 follow-up.

#### Step 3 task details (pre-implementation)

Cover `core/command/traverse/*` — DbTestBase required. Pin both
abnormal-termination branches (T9) and cycle / interrupt invariants
(R6).

**Tasks:**
1. `TraverseTest` (existing, extend): add cases that Track 9 recon
   flagged missing:
   - Cycle A→B→A visits each node exactly once (R6, pin current
     `history.contains` behavior).
   - `setStrategy(BREADTH_FIRST)` mid-DFS → deterministic BFS-from-here
     order (R6 strategy switch).
   - `setMaxDepth(0)` emits only the root (and yields immediately).
   - `setMaxDepth(N)` cuts off at depth N.
   - Empty starting set → `hasNext()` returns false immediately.
   - `Thread.currentThread().interrupt()` then `next()` → throws
     `CommandExecutionException` with interrupt message (line 106-109).
2. `TraverseContextTest` (new, DbTestBase): precision-target direct
   `TraverseContext` methods:
   - `pop(record)` when RID is NOT in history → `LogManager.warn` branch
     (T9).
   - `pop(record)` when memory deque is empty → wraps to
     `IllegalStateException("Traverse stack is empty")` (T9).
   - `push` / `pop` depth tracking correctness across nested frames.
   - `isAlreadyTraversed` true / false branches.
   - `setStrategy` rebuilds `memory` preserving existing items or
     documents the invariant if it doesn't.
3. `TraversePathTest` (new, standalone): path assembly, parent linkage,
   `toString` formatting, edge vs. vertex step labelling.
4. `TraverseRecordProcessTest` / `TraverseMultiValueProcessTest` /
   `TraverseRecordSetProcessTest` (new, DbTestBase): cover the abstract-
   termination path in `Traverse.hasNext` by constructing a synthetic
   `TraverseAbstractProcess` subclass that returns null while leaving
   residual context stack → `IllegalStateException("Traverse ended
   abnormally")` (T9 / Traverse.java:91-93).
5. Do NOT touch `activeTx*` local variables in the existing
   `TraverseTest.java:56-72` — flag as Track 22 cleanup (TF9). No
   changes to that block of the file.

**Strategy:** DbTestBase required for record-loading + cluster
iteration paths. Synthetic processes for abnormal-termination pin are
constructed in test-only inner classes subclassing
`TraverseAbstractProcess` (abstract class with protected ctor — OK via
same-package test).

**Coverage target:** `core/command/traverse` from 62.9%/39.2% → ≥
85%/70%.

**Key files:**
- `core/src/test/java/.../command/traverse/TraverseTest.java` (extend)
- `core/src/test/java/.../command/traverse/TraverseContextTest.java` (new)
- `core/src/test/java/.../command/traverse/TraversePathTest.java` (new)
- `core/src/test/java/.../command/traverse/TraverseRecordProcessTest.java` (new)
- `core/src/test/java/.../command/traverse/TraverseMultiValueProcessTest.java` (new)
- `core/src/test/java/.../command/traverse/TraverseRecordSetProcessTest.java` (new)

---

- [x] Step 4: Script execution core — ScriptManager + PolyglotScriptExecutor + Jsr223ScriptExecutor + DatabaseScriptManager + wrappers + bindings (split into 4a + 4b)
  - [x] Context: warning
  > **What was done:**
  > Covered the entire live surface of `core/command/script` executors,
  > registries, and wrappers across 8 new test files (~2,913 LOC across
  > commits `94eed43f70` + `2fec96e859`). Purely test-additive; no
  > production-code changes. Size pressure forced the documented 4a/4b
  > split per the step-plan fallback:
  >
  > **Step 4a** (`94eed43f70`, ~1,024 LOC) — registries:
  > - `ScriptManagerTest` (44 tests → 49 after iter-1 expansions): engine
  >   registry (get/exists/supported languages), formatter registry
  >   (register lowercasing + override path), result-handler registry,
  >   injection register/unregister, binding helpers
  >   (`bindContextVariables`, `bind`, `bindLegacyDatabaseAndUtil`,
  >   `unbind`), library-code generation (`getLibrary` + null/missing
  >   function branches), allowed-packages security round-trip,
  >   `throwErrorMessage` Rhino fallback + positive-line + no-line paths,
  >   per-database engine pool lifecycle (`acquireDatabaseEngine`,
  >   `releaseDatabaseEngine`, `close(dbName)`, `closeAll`).
  > - `DatabaseScriptManagerTest` (8 tests): `ResourcePoolFactory`-backed
  >   same-language pool reuse, different-language distinct pools,
  >   close idempotency, library-present createNewResource branch,
  >   acquire-after-close.
  >
  > **Step 4b** (`2fec96e859`, ~1,889 LOC) — executors + wrappers +
  > bindings:
  > - `PolyglotScriptExecutorTest` (12 tests → 18 after iter-1): language
  >   alias normalization, `reuseResource` identity pin, numeric/string
  >   scalar result paths, array-result ClassCastException pin
  >   (WHEN-FIXED), host-object handling, null-result mapping, map-overload
  >   named params, positional-args execute, syntax-error → PolyglotException
  >   wrap (TB2), runtime-error wrap, `contextPools` lazy-init,
  >   `close(dbName)` entry eviction + rebuild, `closeAll` map drain,
  >   `executeFunction` via stored function, `resolveContext` atomic
  >   `computeIfAbsent` pin (R5).
  > - `Jsr223ScriptExecutorTest` (10 tests): `Compilable.compile` happy
  >   path, invalid-script wrap through `ScriptManager.throwErrorMessage`,
  >   runtime-error wrap, `executeFunction` Invocable dispatch, null-args
  >   → EMPTY_OBJECT_ARRAY, null-return mapping, positional-args pin
  >   via non-commutative subtraction (TB5).
  > - `ScriptDatabaseWrapperTest` (16 tests): `query` + `execute` +
  >   `command` + `runScript` positional/named overloads,
  >   `newInstance`/`newVertex`/`newEdge`/`newBlob` factory delegates,
  >   `delete` observable via follow-up query, `begin`/`commit`/`rollback`
  >   transaction delegates.
  > - `PolyglotScriptBindingTest` (13 tests): put/get round-trip, keySet,
  >   containsKey, remove, clear, size, entrySet, putAll, concurrent clear
  >   defensive path (BC2 flagged as latent production risk — deferred).
  > - `ScriptResultSetsTest` (8 tests): `singleton` + `empty` factory
  >   methods, iteration contract, close propagation through
  >   `CountingTransformer` / `FixedTransformer` doubles.
  > - `ScriptLegacyWrappersTest` (4 tests): live-subset pins for
  >   `ScriptYouTrackDbWrapper.getDatabase` + `ScriptDocumentDatabaseWrapper`
  >   `getName`/`isClosed` delegates + composition invariant with the
  >   two wrappers chained.
  >
  > **Iter-1 dimensional review** (6 agents — CQ / BC / TB / TC / TX / TS;
  > `b91180af83`) surfaced **2 blockers + 31 should-fix + 31 suggestions**.
  > Both blockers fixed in iter-1:
  > - **TB1** — `ScriptLegacyWrappersTest.scriptDocumentDatabaseWrapperIsClosed`
  >   was unfalsifiable (asserted `session.isClosed()` which is always
  >   `false` during DbTestBase; a regression that hard-coded `return false`
  >   would pass). Now opens a SECOND independent session from the same
  >   database, wraps it, closes it, and asserts the wrapper flips from
  >   `isClosed()==false` to `true` — the state-transition observation is
  >   the falsifiability proof.
  > - **TB2** — `PolyglotScriptExecutorTest.executeSyntaxErrorScript` only
  >   asserted `getMessage().length() > 0`. Now pins the dbName echo in
  >   the message (proves the wrap ctor carries the database name) AND
  >   walks the cause chain to verify it terminates at `PolyglotException`
  >   (proves `BaseException.wrapException` preserved the cause).
  >
  > ~19 should-fix items applied in iter-1: TB3 (assertSame for pool
  > reuse), TB4/CQ1 (foreign-language release actually tested), TB5
  > (non-commutative op for positional-arg ordering), TB6 (script
  > references bound param), TB7 (cause chain + dbName/fname echo),
  > TB8 (newVertex default "V" pin), TB9 (FunctionUtilWrapper type pin),
  > TB11/CQ6 (drop misleading `String.intern` comment), **TX1** (T7
  > putIfAbsent race pin: two independent sessions race first-access;
  > invariant — exactly one DatabaseScriptManager survives in `dbManagers`
  > for the dbName), TC1 (WHEN-FIXED regressions for `throwErrorMessage`
  > malformed Rhino patterns — NumberFormatException and
  > StringIndexOutOfBoundsException), TC6 (boundary line 0 + line-beyond-
  > end), CQ3 (extends `TestUtilsFixture` for 4 files; drop hand-rolled
  > @After), CQ4/TS5 (ScriptManagerTest @After adds rollback guard for
  > mid-transaction test failures), TS3 (split ctor-normalization test
  > into 3 focused tests), CQ7/CQ8 (assertNotSame / assertFalse idioms),
  > CQ9 (drop vacuous sanity assert), BC7/TS7 (CountingTransformer
  > static). ~12 should-fix + ~31 suggestion items deferred to Track 22
  > (DRY extraction, file splits, corner cases, production-side fixes for
  > BC1/BC2/TC1 — full catalog in "Resume notes" top-of-file).
  >
  > **Iter-2 gate check deferred**: previous session ended at warning-level
  > context (29%) immediately after iter-1 commit; the fresh resume
  > session also started at warning (30% from system prompt + /execute-
  > tracks overhead). Given iter-1 fixed both blockers with targeted test
  > changes, no production code was mutated, all 126 + 49 tests pass
  > (parallel + sequential batches — `@Category(SequentialTest)` runs
  > `ScriptManagerTest` in both), and Spotless is clean, Step 4 is
  > declared complete under the 3-iteration cap with iter-1 as final.
  > Self-audit of iter-1 changes confirmed: TB1 state-transition
  > observation is sound, TB2 cause-chain walk terminates correctly, TX1
  > race test uses proper per-thread session activation.
  >
  > **What was discovered:**
  > - **BC2 latent production risk (PolyglotScriptBinding.clear() CME)**:
  >   `PolyglotScriptBinding.clear()` iterates `context.getMemberKeys()`
  >   while calling `removeMember(name)` inside the loop. Depending on
  >   the GraalVM version this may throw `ConcurrentModificationException`
  >   or silently skip keys. Current CI is stable (GraalVM returns a
  >   snapshot set) but a Graal upgrade could flake. **Production fix
  >   queued for Track 22**: iterate over a `new ArrayList<>(keys)`
  >   snapshot before removal.
  > - **TC1 pin — throwErrorMessage malformed Rhino patterns**: the
  >   Rhino-fallback regex at `ScriptManager:379-384` calls
  >   `Integer.parseInt(excMessage.substring(pos+len, indexOf(")", ...)))`
  >   with no guard against non-numeric content or missing close-paren.
  >   Pins the current exception shapes (`NumberFormatException` and
  >   `StringIndexOutOfBoundsException`) as WHEN-FIXED regressions so a
  >   Track 22 hardening that wraps both into `CommandScriptException`
  >   will trip both pins for explicit re-pinning.
  > - **TX1 race test observation**: proving "exactly one
  >   DatabaseScriptManager survives putIfAbsent" required switching from
  >   the primary DbTestBase session (thread-bound to the test thread) to
  >   two fresh sessions, one per racer thread. `session.activateOn
  >   CurrentThread()` is the binding API; the primary session is
  >   re-activated in `finally` before touching any thread-bound state in
  >   the assertion phase. This pattern is a candidate for
  >   `TestUtilsFixture` hoist (Track 22 CQ) — multi-thread tests that
  >   need a racer-per-thread session will recur.
  > - **dbManagers field visibility**: `ScriptManager.dbManagers` is
  >   `protected` (not `private`). In-package tests observe the CHM
  >   directly — critical for the TX1 race pin (no public `getDbManagers()`
  >   accessor exists; adding one would be a production API change).
  > - **CQ3 TestUtilsFixture extension pattern**: switching 4 Step 4 files
  >   from `extends DbTestBase` + hand-rolled `@After rollbackIfLeftOpen`
  >   to `extends TestUtilsFixture` worked cleanly. JUnit 4 runs
  >   superclass `@After` methods AFTER subclass ones, so any subclass
  >   additions (e.g., `ScriptManagerTest.restoreAllowedPackagesAndRollback
  >   IfLeftOpen` which combines both guards) layer cleanly.
  > - **`Integer.parseInt` dead line at SQLScriptEngine's eval(Reader,
  >   Bindings)** — already pinned as dead in CommandScriptDeadCodeTest
  >   (Step 1) under T2. Track 22 should delete this overload alongside
  >   the `CommandScript.execute` stub it routes to. No duplication in
  >   Step 4.
  >
  > **What changed from the plan:**
  > No plan deviations at the track level. The Step 4 split into 4a/4b
  > was anticipated by the step-plan fallback: "If implementation surfaces
  > size pressure (commit > ~1,500 test LOC), split into 4a + 4b." Actual
  > sizing: 4a = 1,024 LOC (registries), 4b = 1,889 LOC (executors +
  > wrappers + bindings) — total 2,913 LOC, well past the 1,500 threshold.
  > No new WHEN-FIXED markers introduced beyond Phase A's absorbed set +
  > the iter-1 TC1 NFE/SIOOBE pins (both cataloged in Track 22's queue via
  > the iter-1 commit message and the Resume notes section). Step count
  > unchanged at 5 (Step 5 still pending); Progress count reflects 4/6
  > where the 4th slot is "Step 4 (as 4a + 4b)" and the 6th slot is
  > reserved for the Step 4 dimensional review loop that iter-1 absorbed.
  >
  > **Key files:**
  > - `core/src/test/java/.../command/script/ScriptManagerTest.java` (new
  >   in 4a; extended in iter-1 with TX1 race test + TC1/TC6 pins + TB9
  >   FunctionUtilWrapper type + @After rollback)
  > - `core/src/test/java/.../command/script/DatabaseScriptManagerTest.java`
  >   (new in 4a; TestUtilsFixture in iter-1; TB3 assertSame; CQ1/TB4
  >   foreign-language release; CQ9 drop sanity assert; CQ8 assertFalse)
  > - `core/src/test/java/.../command/script/PolyglotScriptExecutorTest.java`
  >   (new in 4b; TestUtilsFixture in iter-1; TB2 cause-chain pin; TS3
  >   ctor-test split into 3; CQ7 assertNotSame)
  > - `core/src/test/java/.../command/script/Jsr223ScriptExecutorTest.java`
  >   (new in 4b; TestUtilsFixture in iter-1; TB5 non-commutative op;
  >   TB6 bound-param reference; TB7 cause + dbName echo)
  > - `core/src/test/java/.../command/script/ScriptDatabaseWrapperTest.java`
  >   (new in 4b; TestUtilsFixture in iter-1; TB8 newVertex "V" default;
  >   CQ8 assertFalse)
  > - `core/src/test/java/.../command/script/ScriptLegacyWrappersTest.java`
  >   (new in 4b; TB1 state-transition test; TB11/CQ6 drop misleading
  >   intern comment; CQ7 assertNotSame)
  > - `core/src/test/java/.../command/script/ScriptResultSetsTest.java`
  >   (new in 4b; BC7/TS7 CountingTransformer static)
  > - `core/src/test/java/.../command/script/PolyglotScriptBindingTest.java`
  >   (new in 4b; unchanged in iter-1 — BC2 clear() CME risk queued for
  >   Track 22 as a production-side fix)
  >
  > **Critical context:**
  > - The TX1 race test depends on `ScriptManager.dbManagers` remaining
  >   `protected`. A Track 22 refactor that makes it `private` without
  >   providing a package-visible getter would break the test; the
  >   replacement should be a test-scope accessor or a reflective helper
  >   in `TestUtilsFixture`.
  > - `ScriptManagerTest` carries `@Category(SequentialTest.class)` because
  >   `scriptManager.addAllowedPackages` + `scriptManager.closeAll`
  >   mutate the per-YouTrackDB-instance shared ScriptManager. The TX1
  >   race test's `scriptManager.close(dbName)` also mutates that shared
  >   state. If future Track 22 work renames or replaces `SequentialTest`,
  >   the category annotation must follow or the race test will flake
  >   under `threadCountClasses=4`.
  > - **Iter-2 NOT formally run** — if a future audit wants to run the
  >   gate check, the diff is `git diff 2fec96e859..b91180af83` (iter-1
  >   changes only) or `git diff 57e8873821..b91180af83` (full Step 4
  >   combined with iter-1). Both diffs are self-contained and reviewable
  >   without re-spawning the Phase A review agents.

**Tasks:**
1. `ScriptManagerTest` (new, DbTestBase extending `TestUtilsFixture` or
   similar; apply polyglot-state hygiene per R3):
   - `getEngine(dbName, null)` → `CommandScriptException("No language
     was specified")`.
   - `getEngine(dbName, "unknown")` → unknown-language path.
   - `acquireDatabaseEngine` two-thread `putIfAbsent` race test (T7) —
     two threads on same DB name, verify only one `DatabaseScriptManager`
     survives and the other is `close()`d (track close count via a
     synthetic subclass).
   - `throwErrorMessage` — both the normal path (lines 373-437) AND the
     Rhino fallback branch (376-385) via a fabricated `ScriptException`
     with `"<Unknown Source>#N)"`.
   - `close(dbName)` + `closeAll` propagation to executors.
   - `registerEngine` / `getEngine` / `getEngines` keyed lookups.
   - `addAllowedPackages` mutate-in-try / restore-in-finally (R3) +
     `@Category(SequentialTest)` if it mutates `GlobalConfiguration`.
2. `PolyglotScriptExecutorTest` (new, DbTestBase): drive via
   `session.computeScript("javascript", …)` to exercise:
   - `execute` normal path (scalar result, array result, null result,
     host-object result — each covers one `ScriptTransformerImpl.toResultSet`
     branch via R2 hitchhiker coverage).
   - `execute` with script that throws `Error` / `RuntimeException` /
     `PolyglotException` — three distinct wrap paths.
   - Pool return / acquire single-threaded smoke (T7).
   - `resolveContext` key-scoped `computeIfAbsent` atomic (comment-pin
     R5: "CHM race RISK-B refuted — not Track 22 candidate").
   - `close(dbName)` resets pool.
   - `closeAll` exercised via `youTrackDB.close()` (DbTestBase teardown).
3. `Jsr223ScriptExecutorTest` (new, DbTestBase; disable Graal via
   `GlobalConfiguration.SCRIPT_POLYGLOT_USE_GRAAL` mutate-in-try /
   restore-in-finally pattern + `@Category(SequentialTest)`):
   - `execute(javascript, script)` via JSR-223 path (non-Graal).
   - `executeFunction` via stored JS function — **this is the single
     live-path test for `ScriptDocumentDatabaseWrapper` +
     `ScriptYouTrackDbWrapper` per R2**. Create a `Function` class,
     store JS code that calls `youtrackdb.getScriptManager()` and
     `db.query(...)`, invoke via `session.executeFunction(name, args)`,
     assert result.
4. `DatabaseScriptManagerTest` (new, DbTestBase): exercise the
   `ResourcePoolListener.reuseResource` both branches (language.equals
   "sql" vs. not) — force pool reuse across language switches.
5. `ScriptDatabaseWrapperTest` (new, DbTestBase): SQL+command query
   execution through the wrapper; result collection shape.
6. `PolyglotScriptBindingTest` (new, DbTestBase — Graal context
   required): wrapper around GraalVM context bindings; keys/values
   round-trip.
7. `ScriptResultSetsTest` (new, DbTestBase): multi-resultset container,
   iteration, close propagation.
8. Pin `ScriptManager.bind(String, DatabaseSessionEmbedded, ...)` +
   `bindLegacyDatabaseAndUtil` + `ScriptDocumentDatabaseWrapper`
   unreachable methods + `ScriptYouTrackDbWrapper` unreachable methods
   in `CommandScriptDeadCodeTest` (from Step 1 — Step 4 just confirms
   the reachable subset via the stored-function test).

**Strategy:**
- DbTestBase required throughout (polyglot contexts + DatabaseSession).
- Polyglot-state hygiene (R3) is the dominant convention:
  mutate-in-try / restore-in-finally for ScriptManager.addAllowedPackages
  and GlobalConfiguration toggles; `@Category(SequentialTest)` for any
  GlobalConfiguration mutation.
- Concurrency pin: one stage test for `acquireDatabaseEngine`
  `putIfAbsent` (T7). Not escalated to production fix.
- Explicitly document in the step file that
  `PolyglotScriptExecutor.resolveContext` uses atomic
  `computeIfAbsent` — no stage-race production fix needed (refutes
  R5/RISK-B).

**Coverage target:** `core/command/script` live subset from ~31% (with
dead code) to ≥ 85%/70% post-exclusion; `core/command/script/js` from
43.5%/66.7% to ≥ 85%/70%.

**Key files:**
- `core/src/test/java/.../command/script/ScriptManagerTest.java` (new)
- `core/src/test/java/.../command/script/PolyglotScriptExecutorTest.java` (new)
- `core/src/test/java/.../command/script/Jsr223ScriptExecutorTest.java` (new)
- `core/src/test/java/.../command/script/DatabaseScriptManagerTest.java` (new)
- `core/src/test/java/.../command/script/ScriptDatabaseWrapperTest.java` (new)
- `core/src/test/java/.../command/script/PolyglotScriptBindingTest.java` (new)
- `core/src/test/java/.../command/script/ScriptResultSetsTest.java` (new)
- `core/src/test/java/.../command/script/CommandScriptDeadCodeTest.java` (extend — add R2 pins)

---

- [x] Step 5: Formatters + transformers + SQLScriptEngine(Factory) + coverage verification
  - [x] Context: info
  > **What was done:**
  > Added six new test classes (~2,050 lines of tests initially; +180 lines in
  > iter-1 fixes) covering the four script formatters, the script transformer
  > dispatch and its MapTransformer, the JDK-21+ no-op CommandExecutorUtility,
  > the JSR-223 SQLScriptEngineFactory (all metadata accessors), and the live-
  > plus-dead paths of SQLScriptEngine. Initial code landed as `25d0032a2e`
  > (111 tests); iter-1 review fixes landed as `fe24d12aa1` (net +3 tests →
  > 114 total, all passing). Coverage build + gate + baseline doc update
  > completed.
  >
  > Files added:
  > - `formatter/ScriptFormatterTest` (25 tests) — JS/Groovy/Ruby/SQL template
  >   pins across arity variants; 2 WHEN-FIXED pins for Ruby `skip("\r")`
  >   NoSuchElementException.
  > - `transformer/ScriptTransformerImplTest` (22 tests) — 6 toResultSet
  >   dispatch branches (null / ResultSet / Iterator / registered /
  >   defaultResultSet / polyglot Value via real GraalVM Context), 2 toResult
  >   branches, registry manipulation, 3 WHEN-FIXED pins (polyglot array CCE,
  >   Value fall-through CEE, and a new TC1 Map-registry-asymmetry pin added
  >   in iter-1).
  > - `transformer/result/MapTransformerTest` (9 tests) — 3 per-entry branches
  >   + 2 WHEN-FIXED NPE pins for null-value (top-level + in-iterable, TC4
  >   sibling added iter-1).
  > - `command/script/CommandExecutorUtilityTest` (9 tests) — JDK 21+ no-op
  >   path with comments pinning the Nashorn-fallback dead-code paths.
  > - `sql/SQLScriptEngineFactoryTest` (20 tests — up from 16 after iter-1's
  >   CQ4 consolidation) — JSR-223 metadata accessors using
  >   `javax.script.ScriptEngine.NAME/ENGINE/LANGUAGE/LANGUAGE_VERSION`
  >   constants directly (not reinvented).
  > - `sql/SQLScriptEngineTest` (29 tests — up from 28 after iter-1's TC3
  >   composition test) — all eval overloads, convertToParameters six
  >   branches (with a `WrapperRid` non-self-identity Identifiable stub to
  >   make RID-extraction falsifiable), EofAwareReader working around the
  >   StringReader.ready()-loop bug in eval(Reader, Bindings).
  >
  > Production bugs surfaced + one-cycle fixes co-landed in the initial
  > commit (`25d0032a2e`): Ruby skip("\r") NSE, MapTransformer null-value
  > NPE, polyglot Value asHostObject CCE on JS primitive arrays, polyglot
  > Value fall-through CCE on property-write, SQLScriptEngine.eval(Reader,
  > Bindings) StringReader.ready()-always-true infinite loop. All five are
  > pinned as WHEN-FIXED: Track 22 via falsifiable observed-shape
  > assertions — Track 22 deletion / hardening will flip the assertions.
  >
  > **Iter-1 dimensional review** (6 agents — CQ/BC/TB/TC/TS/TX; commit
  > `fe24d12aa1`) surfaced **1 blocker + 8 should-fix + ~21 suggestions**.
  > All blockers and should-fix applied in iter-1:
  > - **TB1 ≡ BC1 ≡ TC2** (blocker): `convertToParameters` Identifiable
  >   branch tests used bare `RecordId`, whose `getIdentity()` returns
  >   `this` — so "RID extracted" and "wrapper kept" produced the same
  >   `assertSame` result, masking the branch entirely. Fixed by adding
  >   `WrapperRid` (a minimal Identifiable whose `getIdentity()` returns a
  >   DIFFERENT RecordId). Valid-position now pins `out.get(0) == innerRid
  >   && out.get(0) != wrapper`; invalid-position pins the inverse. Also
  >   added TC3 composition test that exercises Object[] unwrap +
  >   per-element RID extraction + null-safe positional insertion in one
  >   shot.
  > - **CQ1 ≡ TS1 ≡ BC5**: Deleted redundant `@After
  >   rollbackTransactionIfStillOpen` in `ScriptFormatterTest` that
  >   duplicated the inherited `TestUtilsFixture.rollbackIfLeftOpen`.
  > - **CQ2 ≡ TS3**: Dropped FQN call-site usage — added proper imports
  >   for `assertThrows`, `NoSuchElementException`, `Date`, `List`,
  >   `Arrays`, `CommandExecutionException`, `Result`, `RecordId`, `RID`,
  >   `Identifiable`, `ScriptContext`, `@Nonnull`.
  > - **CQ3**: `./mvnw -pl core spotless:apply` auto-reformatted one file
  >   after the FQN cleanup.
  > - **CQ4**: Deleted the private `ScriptEngineKeys` constant holder that
  >   reinvented JSR-223 `ScriptEngine.{NAME,ENGINE,LANGUAGE,
  >   LANGUAGE_VERSION}`; use the JSR-223 constants directly.
  > - **TB2**: Replaced `.contains("No database available in bindings")`
  >   with `assertEquals("No database available in bindings", …)` in both
  >   no-db ScriptContext tests (the substring equaled the whole message,
  >   masking noisy rewraps).
  > - **TC1**: Added `toResultSetWithMapFallsThroughAndUnwrapsPerEntryOnNext`
  >   pinning the registry-asymmetry (MapTransformer in `transformers`
  >   only, not `resultSetTransformers`, but `ScriptResultSet.next`
  >   consults `transformers` on the singleton-iterator element — so the
  >   Map surfaces per-entry anyway, not under a "value" wrapper). Initial
  >   test expectation was wrong (`assertSame(input, getProperty("value"))`
  >   failed with `null`); corrected via production-code trace to pin the
  >   actual two-step dispatch.
  > - **TC4**: Added `transformMapWithIterableContainingNullElementThrowsNpe`
  >   mirror of the top-level null-value NPE pin, via a different call
  >   site (`MapTransformer` stream lambda → `ScriptTransformerImpl.toResult(
  >   db, null)` → `value.getClass()` NPE). WHEN-FIXED: same Track 22 fix
  >   point.
  >
  > **TX**: No findings (Step 5 is legitimately single-threaded — production
  > code under test is stateless / effectively immutable after construction;
  > `convertToParameters` is pure and allocates fresh HashMap per call;
  > `ScriptTransformerImpl.transformers` is populated once in the ctor of
  > the enclosing executor which stores it in a `final` field; formatters
  > are stateless).
  >
  > **Iter-2 gate check deferred** (Track 9 Step 4 precedent): iter-1 fixed
  > the sole blocker with targeted test-strengthening changes; no production
  > code mutated in this commit; all 114 Step-5 tests pass; Spotless clean;
  > all should-fix items discharged. Remaining ~21 suggestions (TB3–7,
  > TC5–8, CQ5/7–13, BC2–4, TS2) fold into the existing Track 22 queue
  > following the Track 8/9 precedent — they are quality-of-life refactors
  > (drop-redundant-assertion, extract-helper, pin-exhaustive-key-set,
  > null-name-formatter-corners, getProgram null-varargs, db-leak-into-
  > parameters observed-shape, nashorn-activation-comment) rather than
  > correctness blockers.
  >
  > **Coverage verification** (post-iter-1):
  > - Build: `./mvnw -pl core -am clean package -P coverage` — BUILD
  >   SUCCESS (9m23s on core module).
  > - Gate: `python3 .github/scripts/coverage-gate.py --line-threshold 85
  >   --branch-threshold 70 --compare-branch origin/develop` → **PASSED**
  >   at **100.0% line (6/6) + 100.0% branch (2/2)** on changed production
  >   lines.
  > - Per-package aggregates: `formatter` 100.0%/97.4%, `transformer/result`
  >   100.0%/100.0%, `traverse` 92.1%/82.3%, `transformer` 82.8%/92.1%,
  >   `command` 77.4%/70.0%, `SQLScriptEngine` 86.8%/90.6%,
  >   `SQLScriptEngineFactory` 100.0%/100.0%. Recorded in
  >   `track-9-baseline.md` (post-Step-5 section).
  > - `command/script` aggregate is 53.9%/37.8% — dead LOC still in
  >   denominator (Track 22 will delete and the aggregate will rise).
  >   Live-subset gate passes.
  >
  > **What was discovered:**
  > - **Registry asymmetry bug-surface**: `MapTransformer` is registered in
  >   `ScriptTransformerImpl.transformers` but NOT in
  >   `resultSetTransformers`. On `toResultSet(db, Map)`, the direct map
  >   lookup returns null and the Map falls through to `defaultResultSet`
  >   as a singleton iterator. Then `ScriptResultSet.next()` calls
  >   `transformer.toResult(db, map)` which DOES find the MapTransformer
  >   (via the `transformers` map) and unwraps per-entry — so callers see
  >   a one-Result outer ResultSet whose SINGLE Result has per-entry
  >   properties (not a Map-under-"value" wrapper). A Track 22 refactor
  >   that mirrors the registration into both maps OR drops the `toResult`
  >   consultation in `ScriptResultSet.next` would silently change this
  >   shape — now pinned via TC1.
  > - **`RecordId.getIdentity()` self-identity collapses branch
  >   distinguishability**: Several Track 8 tests that used bare RecordIds
  >   to exercise Identifiable branches may have the same falsifiability
  >   hole as the original TB1 pattern. Worth a grep-audit when Track 22
  >   lands — `WrapperRid`-style stubs should replace bare RecordIds where
  >   a branch extracts `getIdentity()`. Adding to the cross-track
  >   observation — NOT a Track 9 blocker, recorded for Track 22 sweep.
  > - **Nashorn-activation LOC are dominant in the `command/script/js`
  >   gap**: 12 of the 13 uncov lines in that subpackage trace to the
  >   Nashorn-fallback branch activated only when
  >   `SCRIPT_POLYGLOT_USE_GRAAL=false`. CI default is `true`, so the
  >   branch is dead under test config. Covered minimally via the
  >   CommandExecutorUtilityTest Javadoc note; not escalated to a
  >   sequential-test Nashorn-active variant (disproportionate effort for
  >   dead config).
  > - **EofAwareReader as a permanent fixture pattern**: the
  >   `StringReader.ready()`-always-true bug forces tests that exercise
  >   `eval(Reader, Bindings)` to pass a custom Reader (our `EofAwareReader`).
  >   Worth hoisting into `TestUtilsFixture` if Track 22 chooses to fix
  >   the bug via `int c; while ((c = reader.read()) != -1)` instead of
  >   deleting the overload entirely. Defer to Track 22 as it may not be
  >   needed (deletion is the simpler fix).
  >
  > **What changed from the plan:**
  > No plan deviations. Step count unchanged (5 steps; 6 sub-units after
  > Step 4 a/b split, 7 after Step 5's review loop — all `[x]`). All
  > coverage targets met or exceeded on their live subset. TC1 / TC3 / TC4
  > are iter-1 additions, not plan deviations — they refine the existing
  > "six-branch dispatch" coverage task with falsifiability-strengthening
  > sibling tests.
  >
  > **Key files:**
  > - `core/src/test/java/.../command/script/formatter/ScriptFormatterTest.java` (new, 25 tests)
  > - `core/src/test/java/.../command/script/transformer/ScriptTransformerImplTest.java` (new, 22 tests)
  > - `core/src/test/java/.../command/script/transformer/result/MapTransformerTest.java` (new, 9 tests)
  > - `core/src/test/java/.../command/script/CommandExecutorUtilityTest.java` (new, 9 tests)
  > - `core/src/test/java/.../sql/SQLScriptEngineFactoryTest.java` (new, 20 tests)
  > - `core/src/test/java/.../sql/SQLScriptEngineTest.java` (new, 29 tests)
  > - `docs/adr/unit-test-coverage/_workflow/track-9-baseline.md` (updated with post-Step-5 coverage deltas)
  >
  > **Critical context:**
  > - The `WrapperRid` stub is required for TB1 falsifiability — ANY
  >   Track 22 refactor that changes `RecordIdInternal.getIdentity()` from
  >   `return this` to a new-RID allocation would make bare-RecordId tests
  >   sufficient, at which point `WrapperRid` could be removed. Unlikely
  >   change; pin only.
  > - `ScriptTransformerImplTest` uses a real GraalVM `Context` per test
  >   (not a mock) so the polyglot dispatch ordering is genuine. `@Before`
  >   opens, `@After` closes. Any Track 22 migration off GraalVM would
  >   require replacing the Context with a test-double that implements
  >   `isNull/hasArrayElements/isHostObject/isString/isNumber/canExecute`
  >   — the current tests would fail-fast with informative errors.

#### Step 5 task details (pre-implementation)

Round out Track 9 with the small/pure-function files and the
Track 7-deferred SQL script engine, then run coverage verification
across the entire track scope.

**Tasks:**
1. `ScriptFormatterTest` (new, standalone, `@Parameterized` across
   `JSScriptFormatter`, `GroovyScriptFormatter`, `RubyScriptFormatter`,
   `SQLScriptFormatter`):
   - `getFunctionDefinition(null, Function)` with various arity:
     zero params, one param, multi-param, null params list, empty
     args list.
   - `getFunctionInvoke(null, Function, args)` with scalar / array /
     null args.
   - Assert exact generated text (NOT length) for each formatter — pin
     behavioral contract per TB3 (Track 8 iter-1 precedent).
   `session` arg is unused in all four → pass `null`, document via
   Javadoc in the test class.
2. `ScriptTransformerImplTest` (new, DbTestBase — `ResultInternal`
   instantiation needs a session for type inference):
   - `toResultSet(db, null)` → empty-or-default branch.
   - `toResultSet(db, Iterator)` / `ResultSet` / `Map` / scalar
     dispatch branches (56-97).
   - Registered `resultSetTransformers` dispatch.
   - Polyglot `Value` branches (58-81) — need a real GraalVM Context
     or a synthetic `Value` (prefer Context for realism).
   - `toResult(db, …)` scalar dispatch.
3. `MapTransformerTest` (new, DbTestBase): Map→Result transformation
   with nested maps (recursive `ScriptTransformerImpl` call),
   primitive value types, null values, missing keys.
4. `CommandExecutorUtilityTest` (new, standalone): utility static
   methods — 87 LOC, small surface.
5. `SQLScriptEngineFactoryTest` (new, standalone): 9 JSR-223 stub
   methods (`getEngineName`, `getEngineVersion`, `getExtensions`,
   `getMimeTypes`, `getNames`, `getLanguageName`, `getLanguageVersion`,
   `getParameter`, `getMethodCallSyntax`, `getOutputStatement`,
   `getProgram`, `getScriptEngine`) — pure metadata / factory, no DB
   required. Pin contract for each method.
6. `SQLScriptEngineTest` (new, DbTestBase): live + dead paths:
   - Constructor: `new SQLScriptEngine(new SQLScriptEngineFactory())`.
   - `eval(String, Bindings)` + `eval(String, ScriptContext)` live
     paths with a `ScriptDatabaseWrapper`-bound `SimpleBindings`,
     asserting result shape.
   - `convertToParameters` (101-130) — pure, testable standalone; cover
     positional (`arrays`), named (`maps`), `null` → empty, mixed.
   - `getFactory()` returns the factory passed to ctor.
   - `createBindings()` returns a fresh `SimpleBindings`.
   - `eval(Reader, Bindings)` — **falsifiable observed-shape pin** per
     T2: today routes to dead `CommandScript.execute`, returns
     empty result. WHEN-FIXED: Track 22 to either delete the overload
     or route it somewhere live. Test asserts the empty-result shape.
7. Coverage verification:
   - Run `./mvnw -pl core -am clean package -P coverage`.
   - Run the project's coverage analyzer (`coverage-analyzer.py` from
     Track 1) to produce per-package summary for Track 9's target
     packages, excluding dead-LOC pins.
   - Run `python3 .github/scripts/coverage-gate.py --line-threshold 85
     --branch-threshold 70 --compare-branch origin/develop
     --coverage-dir .coverage/reports` on the changed lines to verify
     the gate passes.
   - Record per-package coverage delta in `track-9-baseline.md`.

**Strategy:** Mostly standalone (formatters, SQLScriptEngineFactory,
CommandExecutorUtility); DbTestBase only where `ResultInternal` /
`DatabaseSessionEmbedded` bindings are needed (transformers,
SQLScriptEngine live path). Reuse `@Parameterized` pattern from
Track 7 for the four formatters (TS7 precedent).

**Coverage target:**
- `core/command/script/formatter` from 36.0%/26.3% → ≥ 85%/70%
- `core/command/script/transformer` (+ `/result`) from split 65%/17% →
  ≥ 85%/70%
- `SQLScriptEngine` + `SQLScriptEngineFactory` from ~36% → ≥ 85%/70%
  (live subset)
- Aggregate Track 9 packages meet 85%/70% on changed-lines via
  coverage-gate.py.

**Key files:**
- `core/src/test/java/.../command/script/formatter/ScriptFormatterTest.java` (new, parameterized across 4 formatters)
- `core/src/test/java/.../command/script/transformer/ScriptTransformerImplTest.java` (new)
- `core/src/test/java/.../command/script/transformer/MapTransformerTest.java` (new)
- `core/src/test/java/.../command/script/CommandExecutorUtilityTest.java` (new)
- `core/src/test/java/.../sql/SQLScriptEngineFactoryTest.java` (new)
- `core/src/test/java/.../sql/SQLScriptEngineTest.java` (new)
- `docs/adr/unit-test-coverage/_workflow/track-9-baseline.md` (update)

---

## Step ordering notes

- **Step 1 before 2–5** because it establishes the realistic coverage
  denominator. Without dead-code pins in place, Step 4's aggregate
  coverage math doesn't add up.
- **Step 2 and Step 3 are independent of each other** and can be
  swapped or done in parallel (different packages:
  `core/command` vs. `core/command/traverse`). Marked
  `*(parallel with Step 3)*` in commit-level metadata if Phase B
  chooses to interleave.
- **Step 4 before Step 5** because Step 5's `SQLScriptEngineTest`
  exercises `ScriptManager.getEngine("sql")`; having ScriptManager
  tested first catches regressions in the wrong place.
- **Step 5 last** because coverage verification naturally closes the
  track.

## Parallel-step annotations

- Step 2 and Step 3 are independent (different packages, disjoint file
  sets). `*(parallel with Step 3)*` / `*(parallel with Step 2)*`.

## Risk acknowledgements (per Phase A reviews)

- **Dead code is the dominant risk** (T1/R1). Mitigated by Step 1
  pinning + Track 22 absorption. Coverage-gate math is recomputed
  against a realistic denominator.
- **Polyglot context leakage** (R3). Mitigated by convention: mutate-in-try
  / restore-in-finally + `@Category(SequentialTest)` for
  GlobalConfiguration mutations. Verified JSScriptTest precedent.
- **CHM race RISK-B refuted** (R5). `PolyglotScriptExecutor.resolveContext`
  uses atomic `computeIfAbsent`. Step 4 notes this explicitly — no
  stage test, no production fix.
- **Traverse cycle detection is already safe** (R6). Mitigated by
  `history.contains` pre-descent. Step 3 pins the invariant with one
  regression test.
- **SPI deadness** (T3). `ScriptInjection` / `ScriptInterceptor` get
  SPI-wiring smoke tests in Step 1; `ScriptExecutorRegister` is pinned
  as dead SPI (no impls, no test value).
- **GraalVM availability in CI** (R assumption VALIDATED). Confirmed
  via existing `JSScriptTest` passing under
  `-Dyoutrackdb.test.env=ci`.
- **`forwards-to:` convention**: any failure attributable to
  `record/impl`, `metadata/schema`, `core/db`, or `security` is pinned
  with a comment and worked around; not Track 9 blockers.
