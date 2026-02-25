# Plan: Fix All Remaining ErrorProne Warnings and Elevate to Errors

## Context

The project has 11 ErrorProne checks already elevated to ERROR. There are 11 remaining warnings across 8 distinct check types. The goal is to fix all fixable warnings one check at a time, commit each fix separately (reviewed by code-reviewer agent in a loop until satisfied), then as a final step switch to `-Werror` (which promotes all WARNING-level checks to ERROR at once), keep explicit flags only for below-warning checks (`NullAway`, `RemoveUnusedImports`), and disable `NonApiType` with OFF.

**Each step is executed in its own separate Claude session.** At the start of each session, Claude should read this file, find the first unchecked step, execute it, and stop. Progress is tracked via checkboxes below.

## ErrorProne Config Location

**Root pom.xml** property `errorprone.args` (line ~105)

## Per-Step Workflow

1. Read this plan file and find the first unchecked (`- [ ]`) step
2. Fix all instances of the warning for that step
3. Code review via code-reviewer sub-agent — iterate fixes until reviewer is satisfied
4. Mark the checkbox in this plan file (`- [ ]` → `- [x]`)
5. Commit **both** the code fix and the updated plan file with message `Fix ErrorProne <CheckName> warnings`
6. **Stop and wait** — the next step will be done in a new session

## Steps

- [x] **Step 1: `MissingOverride`** (18 instances — 2 in MetadataDefault, 16 in ImmutableUser)
  - `MetadataDefault.java` — added `@Override` to `getSchema()` and `getScheduler()`
  - `ImmutableUser.java` — added `@Override` to all 16 methods implementing `SecurityUser` interface

- [x] **Step 2: `ImmutableEnumChecker`** (1 instance)
  - `core/src/main/java/com/jetbrains/youtrackdb/internal/common/profiler/metrics/Meter.java:37` — add `@SuppressWarnings("ImmutableEnumChecker")` to the `ratioDenominator` field (`BiFunction` is a JDK type that can't be annotated `@Immutable`)

- [x] **Step 3: `EffectivelyPrivate`** (1 instance)
  - `core/src/main/java/com/jetbrains/youtrackdb/internal/common/profiler/metrics/Meter.java:152` — removed `public` from `record()` in private inner class `ThreadLocalMeter`

- [ ] **Step 4: `StatementSwitchToExpressionSwitch`** (1 instance)
  - `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/AbstractStorage.java:4464` — convert `switch (txEntry.type)` from `case X: { ... break; }` to arrow-style `case X -> { ... }`

- [ ] **Step 5: `OperatorPrecedence`** (1 instance)
  - `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/AbstractStorage.java:4673` — add parentheses: `(reportBatchSize > 0 && recordsProcessed % reportBatchSize == 0) || ...`

- [ ] **Step 6: `InvalidParam`** (1 instance)
  - `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/AbstractStorage.java:5496` — disambiguate Javadoc `{@code tsMin}` from parameter `tsMins` (use `{@code holder.tsMin}` or `{@code TsMinHolder.tsMin}`)

- [ ] **Step 7: `EmptyBlockTag`** (1 instance)
  - `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/FreezableStorageComponent.java:37` — add description to empty `@param db` tag

- [ ] **Step 8: Switch to `-Werror`, clean up individual flags, disable `NonApiType`**
  - In `pom.xml` root property `errorprone.args`, replace the current value with:
    ```
    -Xplugin:ErrorProne -Werror -XepExcludedPaths:.*/src/main/generated/.*|.*/internal/core/sql/parser/.*|.*/src/test/.*|.*/generated-test-sources/.* -XepOpt:NullAway:OnlyNullMarked=true -Xep:NullAway:ERROR -Xep:RemoveUnusedImports:ERROR -Xep:NonApiType:OFF
    ```
  - `-Werror` promotes **all** WARNING-level checks to ERROR (covers 15+ checks at once)
  - Only two checks need explicit `-Xep:X:ERROR` because they are below WARNING by default:
    - `NullAway` — third-party plugin, OFF by default
    - `RemoveUnusedImports` — SUGGESTION level, disabled by default
  - `-Xep:NonApiType:OFF` disables this check entirely (intentional `TreeMap` usage)
  - All previous individual `-Xep:X:ERROR` flags for WARNING-level checks are removed (now redundant)
  - Verify: `./mvnw clean package -DskipTests` — expect 0 ErrorProne errors and 0 warnings
  - Verify: `./mvnw -pl core clean test` — all core unit tests pass
  - Commit: `Switch ErrorProne to -Werror and disable NonApiType`
