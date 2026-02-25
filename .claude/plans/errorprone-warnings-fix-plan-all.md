# Plan: Fix All Remaining ErrorProne Warnings and Elevate to Errors

## Context

The project has 11 ErrorProne checks already elevated to ERROR. There are 11 remaining warnings across 7 distinct check types (all in `core` module). The goal is to fix all fixable warnings one check at a time (suppressing intentional patterns where appropriate), commit each fix separately (reviewed by code-reviewer agent in a loop until satisfied), elevate the fixed check to ERROR immediately after each fix to verify it is resolved, then as a final step switch to `-Werror` (which promotes all WARNING-level checks to ERROR at once), keep explicit flags only for below-warning checks (`NullAway`, `RemoveUnusedImports`), and disable `NonApiType` with OFF.

**Each step is executed in its own separate Claude session.** At the start of each session, Claude should read this file, find the first unchecked step, execute it, and stop. Progress is tracked via checkboxes below.

## ErrorProne Config Location

**Root pom.xml** property `errorprone.args` (line ~105)

## Per-Step Workflow

1. Read this plan file and find the first unchecked (`- [ ]`) step
2. Fix all instances of the warning for that step (code fix or `@SuppressWarnings` as indicated)
3. Elevate the check to ERROR: add `-Xep:<CheckName>:ERROR` to `errorprone.args` in root `pom.xml`
4. Verify: `./mvnw clean compile -DskipTests` — compilation must succeed with 0 errors for that check
5. Code review via code-reviewer sub-agent — iterate fixes until reviewer is satisfied
6. Mark the checkbox in this plan file (`- [ ]` → `- [x]`)
7. Commit **both** the code fix and the updated plan file with message `Fix ErrorProne <CheckName> warnings`
8. **Stop and wait** — the next step will be done in a new session

## Steps

- [x] **Step 1: `MissingOverride`** (18 instances — 2 in MetadataDefault, 16 in ImmutableUser)
  - `MetadataDefault.java` — added `@Override` to `getSchema()` and `getScheduler()`
  - `ImmutableUser.java` — added `@Override` to all 16 methods implementing `SecurityUser` interface

- [x] **Step 2: `ImmutableEnumChecker`** (1 instance)
  - `core/src/main/java/com/jetbrains/youtrackdb/internal/common/profiler/metrics/Meter.java:37` — add `@SuppressWarnings("ImmutableEnumChecker")` to the `ratioDenominator` field (`BiFunction` is a JDK type that can't be annotated `@Immutable`)

- [x] **Step 3: `EffectivelyPrivate`** (1 instance)
  - `core/src/main/java/com/jetbrains/youtrackdb/internal/common/profiler/metrics/Meter.java:152` — removed `public` from `record()` in private inner class `ThreadLocalMeter`

- [x] **Step 4: `StatementSwitchToExpressionSwitch`** (1 instance)
  - `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/AbstractStorage.java:4464` — convert `switch (txEntry.type)` from `case X: { ... break; }` to arrow-style `case X -> { ... }`

- [x] **Step 5: `OperatorPrecedence`** (1 instance)
  - `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/AbstractStorage.java:4669` — added parentheses around `&&` sub-expression to clarify precedence with `||`

- [x] **Step 6: Elevate previously-fixed checks to ERROR** (5 checks from steps 1–5)
  - Added all 5 to `errorprone.args` in root `pom.xml`: `-Xep:MissingOverride:ERROR -Xep:ImmutableEnumChecker:ERROR -Xep:EffectivelyPrivate:ERROR -Xep:StatementSwitchToExpressionSwitch:ERROR -Xep:OperatorPrecedence:ERROR`
  - Original plan vastly underestimated instance counts — actual instances found: 150+ MissingOverride, 30+ EffectivelyPrivate, 60+ StatementSwitchToExpressionSwitch, 30+ OperatorPrecedence, 4 ImmutableEnumChecker
  - Fixed all additional instances across ~80 files in `core` module
  - Verified: `./mvnw clean compile -DskipTests` — BUILD SUCCESS with 0 errors

- [x] **Step 7: `InvalidParam`** (3 instances total)
  - `core/.../storage/impl/local/AbstractStorage.java:5492` — changed `{@code tsMin}` to `{@link TsMinHolder#tsMin}` to disambiguate from parameter `tsMins`
  - `core/.../storage/cache/chm/readbuffer/Buffer.java:28` — removed stale `@param <E>` tag (interface has no type parameter)
  - `core/.../storage/cache/chm/readbuffer/BoundedBuffer.java:26` — removed stale `@param <E>` tag (class has no type parameter)
  - Added `-Xep:InvalidParam:ERROR` to `errorprone.args` in root `pom.xml`
  - Verified: `./mvnw clean compile -DskipTests` — BUILD SUCCESS with 0 errors

- [x] **Step 8: `EmptyBlockTag`** (3 instances in plan, 65 actual instances across 64 files)
  - Plan originally listed 3 instances but actual count was ~65 empty block tags across 64 files in `core` module
  - Added meaningful descriptions to all empty `@param`, `@return`, `@throws`, and `@deprecated` tags
  - Removed empty tags entirely where the method summary already conveys the information
  - Added `-Xep:EmptyBlockTag:ERROR` to `errorprone.args` in root `pom.xml`
  - Verified: `./mvnw clean compile -DskipTests` — BUILD SUCCESS with 0 errors

- [x] **Step 9: `ReferenceEquality`** (plan listed 2 instances, actual count: 13 across 9 files)
  - Plan originally listed 2 instances but actual count was 13 across 9 files in `core` module
  - **Code fixes** (3 files — converted `==`/`!=` to `.equals()` where value equality is correct):
    - `AnsiLogFormatter.java` — converted 5 `java.util.logging.Level` comparisons from `==` to `.equals()`; also fixed pre-existing bug where duplicate `Level.CONFIG` check (dead code) was changed to `Level.FINE`
    - `ScriptManager.java:410` — fixed String comparison bug: `words[0] != "("` to `!"(".equals(words[0])`
    - `BinaryTokenSerializer.java:98` — converted String assert from `==` to `.equals()`
  - **Suppressions** (7 files — intentional identity checks):
    - `LocalRecordCache.java` — `updateRecord()`: same cached object instance check
    - `LiveQueryHookV2.java` — `prevousUpdate()`: same entity object instance check
    - `RecordAbstract.java` — `incrementDirtyCounterAndRegisterInTx()` and `assertIfAlreadyLoaded()`: same record instance checks
    - `FrontendTransactionImpl.java` — `addRecordOperation()`: same record/txEntry instance checks
    - `DirectMemoryAllocator.java` — `TrackedPointerKey.equals()`: identity-based pointer key comparison
    - `FrontendTransactionIndexChangesList.java` — `getNode()`: same entry instance check
    - `WeakValueHashMap.java` — `cleanupReference()`: same WeakReference instance check
  - Added `-Xep:ReferenceEquality:ERROR` to `errorprone.args` in root `pom.xml`
  - Verified: `./mvnw clean compile -DskipTests` — BUILD SUCCESS with 0 errors

- [x] **Step 10: `EqualsGetClass`** (plan listed 1 instance, actual count: 36 across 36 files)
  - Plan originally listed 1 instance but actual count was 36 across 36 files in `core` module
  - Converted all `getClass() != o.getClass()` checks in `equals()` methods to `instanceof` pattern matching
  - Pattern: `if (o == null || getClass() != o.getClass()) { return false; } Type that = (Type) o;` → `if (!(o instanceof Type that)) { return false; }`
  - Handled generics correctly: `Pair<?, ?>`, `Triple<?, ?, ?>`, `MultiValueChangeEvent<?, ?>`, `SBTreeValue<?>`
  - Preserved `super.equals()` call chains in hierarchies: `PropertyIndexDefinition`, `PropertyMapIndexDefinition`, `AbstractPageWALRecord`, `UpdatePageRecord`
  - Added `-Xep:EqualsGetClass:ERROR` to `errorprone.args` in root `pom.xml`
  - Verified: `./mvnw clean compile -DskipTests` — BUILD SUCCESS with 0 errors

- [x] **Step 11: `StringSplitter`** (plan listed 1 instance, actual count: 28 across 20 files)
  - Plan originally listed 1 instance but actual count was 28 `String.split(String)` and `Pattern.split(CharSequence)` calls across 20 files in `core` module
  - Added `, -1` as second argument to all flagged `split()` calls to preserve trailing empty strings (making behavior explicit instead of relying on Java's surprising default of silently discarding them)
  - Files fixed: `CommandSQLParsingException`, `FileUtils`, `IOUtils`, `Native` (3 calls), `AnsiCode`, `YouTrackDBConstants` (3 calls), `CommandExecutorScript`, `JSONSerializerJackson`, `JSONReader`, `FetchPlan` (2 calls), `YTDBVertexPropertyIdJacksonDeserializer`, `YTDBVertexPropertyIdGyroSerializer`, `IndexDefinitionFactory` (2 Pattern.split calls), `SecurityShared`, `SecurityManager`, `SymmetricKey`, `FieldTypesString`, `CartesianProductStep` (2 calls), `GlobalLetQueryStep`, `ParallelExecStep` (2 calls)
  - Added `-Xep:StringSplitter:ERROR` to `errorprone.args` in root `pom.xml`
  - Verified: `./mvnw clean compile -DskipTests` — BUILD SUCCESS with 0 errors

- [ ] **Step 12: `TypeParameterUnusedInFormals`** (1 instance — intentional API design, suppress)
  - `core/.../query/BasicResult.java:42` — `<T> T getProperty(String name)` uses a type parameter only in the return type; this is an intentional API convenience pattern (avoids casts at call sites) used across `BasicResult`, `ResultInternal`, and `TraverseResult`
  - Add `@SuppressWarnings("TypeParameterUnusedInFormals")` to the method in `BasicResult` interface and all overriding implementations (`ResultInternal.java`, `TraverseResult.java`, and `MatchStepUnitTest.java` in tests)
  - After fix: add `-Xep:TypeParameterUnusedInFormals:ERROR` to `errorprone.args` in root `pom.xml`
  - Verify: `./mvnw clean compile -DskipTests` — must succeed with 0 `TypeParameterUnusedInFormals` errors
  - Note: test code is excluded from ErrorProne via `-XepExcludedPaths`, so only main source files need the suppression
  - Commit: `Fix ErrorProne TypeParameterUnusedInFormals warnings`

- [ ] **Step 13: Switch to `-Werror`, clean up individual flags, disable `NonApiType`**
  - In `pom.xml` root property `errorprone.args`, replace the current value with:
    ```
    -Xplugin:ErrorProne -Werror -XepExcludedPaths:.*/src/main/generated/.*|.*/internal/core/sql/parser/.*|.*/src/test/.*|.*/generated-test-sources/.* -XepOpt:NullAway:OnlyNullMarked=true -Xep:NullAway:ERROR -Xep:RemoveUnusedImports:ERROR -Xep:NonApiType:OFF
    ```
  - `-Werror` promotes **all** WARNING-level checks to ERROR (covers 15+ checks at once)
  - Only two checks need explicit `-Xep:X:ERROR` because they are below WARNING by default:
    - `NullAway` — third-party plugin, OFF by default
    - `RemoveUnusedImports` — SUGGESTION level, disabled by default
  - `-Xep:NonApiType:OFF` disables this check entirely (intentional `TreeMap` usage in `AbstractStorage`)
  - All previous individual `-Xep:X:ERROR` flags for WARNING-level checks are removed (now redundant under `-Werror`)
  - Verify: `./mvnw clean compile -DskipTests` — expect 0 ErrorProne errors and 0 warnings
  - Verify: `./mvnw -pl core clean test` — all core unit tests pass
  - Commit: `Switch ErrorProne to -Werror and disable NonApiType`
