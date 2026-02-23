# Plan: Fix ErrorProne Warnings and Elevate to Errors

## Context

The project has ErrorProne static analysis enabled with NullAway set to ERROR level. However, 7 other ErrorProne checks are still at their default WARNING severity, producing 27 warnings across the codebase. The goal is to fix all warnings one check at a time, commit each fix separately, then elevate each check from WARNING to ERROR in `pom.xml` so regressions are prevented. Progress is tracked in this file.

## Current ErrorProne Config

**Location**: `pom.xml:411` and `core/pom.xml:297`

```
-Xplugin:ErrorProne -XepExcludedPaths:.*/src/main/generated/.*|.*/internal/core/sql/parser/.* -XepOpt:NullAway:OnlyNullMarked=true -Xep:NullAway:ERROR
```

Only `NullAway` is explicitly set to ERROR. All other checks use default severity (WARNING).

## Progress

| # | Check | Instances | Status | Commit |
|---|-------|-----------|--------|--------|
| 1 | `BooleanLiteral` | 13 | done | 70baa040ae |
| 2 | `MissingSummary` | 356 | done | beb19dc9e6 |
| 3 | `StringCaseLocaleUsage` | 30 | done | 9e5b7379a8 |
| 4 | `PatternMatchingInstanceof` | 227 | done | f9958b82ec |
| 5 | `UnusedVariable` | 71 | done | 835ccc7e66 |
| 6 | `UnnecessaryParentheses` | 139 | done | c0caeec5a5 |
| 7 | `MixedMutabilityReturnType` | 1 | pending | - |

## Per-Step Workflow

Each step follows the same pattern:

1. **Fix all instances** of the warning in the codebase
2. **Elevate to ERROR** in pom.xml by adding `-Xep:<CheckName>:ERROR` to the ErrorProne plugin args (both root `pom.xml:411` and `core/pom.xml:297`)
3. **Run `./mvnw clean compile`** to verify zero warnings for that check and no build failures
4. **Run unit tests** for affected modules
5. **Update this progress file** marking the check as done with commit hash
6. **Commit** with message `YTDB-507: Fix ErrorProne <CheckName> warnings and elevate to error`

## Step Details

### Step 1: `BooleanLiteral` (6 instances, core only)
- **File**: `core/src/main/java/com/jetbrains/youtrackdb/api/config/GlobalConfiguration.java` lines 40, 440, 584, 622, 630, 923
- **Fix**: Replace `Boolean.valueOf(false)` / `Boolean.valueOf(true)` with `false` / `true` literals
- **Test**: `./mvnw -pl core clean test`

### Step 2: `MissingSummary` (6 instances, test-commons + core + server)
- **Files**:
  - `test-commons/src/main/java/com/jetbrains/youtrackdb/internal/test/CompositeException.java:24`
  - `test-commons/src/main/java/com/jetbrains/youtrackdb/internal/test/ConcurrentTestHelper.java:17`
  - `test-commons/src/main/java/com/jetbrains/youtrackdb/internal/test/TestFactory.java:21`
  - `test-commons/src/main/java/com/jetbrains/youtrackdb/internal/test/TestBuilder.java:24`
  - `core/src/main/java/com/jetbrains/youtrackdb/api/config/GlobalConfiguration.java:1219`
  - `server/src/main/java/com/jetbrains/youtrackdb/internal/server/security/SelfSignedCertificate.java:26`
- **Fix**: Add summary lines to public/protected Javadoc comments
- **Test**: `./mvnw -pl test-commons,core,server clean test`

### Step 3: `StringCaseLocaleUsage` (5 instances, core + server)
- **Files**:
  - `core/src/main/java/com/jetbrains/youtrackdb/api/YouTrackDB.java:351`
  - `server/src/main/java/com/jetbrains/youtrackdb/internal/server/plugin/gremlin/YTDBAbstractOpProcessor.java:123-124` (4 instances)
- **Fix**: Add `Locale.ROOT` to `toLowerCase()`/`toUpperCase()` calls
- **Test**: `./mvnw -pl core,server clean test`

### Step 4: `PatternMatchingInstanceof` (5 instances, core + server)
- **Files**:
  - `core/src/main/java/com/jetbrains/youtrackdb/api/config/GlobalConfiguration.java:1277, 1290, 1295`
  - `server/src/main/java/com/jetbrains/youtrackdb/internal/server/plugin/gremlin/YTDBAbstractOpProcessor.java:680, 708`
- **Fix**: Convert `if (x instanceof Foo) { Foo f = (Foo) x; ... }` to `if (x instanceof Foo f) { ... }`
- **Test**: `./mvnw -pl core,server clean test`

### Step 5: `UnusedVariable` (2 instances, server only)
- **Files**:
  - `server/src/main/java/com/jetbrains/youtrackdb/internal/server/ServerSecurityConfig.java:12` (unused field `serverCfg`)
  - `server/src/main/java/com/jetbrains/youtrackdb/internal/server/plugin/gremlin/YTDBAbstractOpProcessor.java:517` (unused local `message`)
- **Fix**: Remove unused variables (or use them if intended)
- **Test**: `./mvnw -pl server clean test`

### Step 6: `UnnecessaryParentheses` (2 instances, server only)
- **Files**:
  - `server/src/main/java/com/jetbrains/youtrackdb/internal/server/YouTrackDBServer.java:219`
  - `server/src/main/java/com/jetbrains/youtrackdb/internal/server/security/SelfSignedCertificate.java:59`
- **Fix**: Remove unnecessary parentheses
- **Test**: `./mvnw -pl server clean test`

### Step 7: `MixedMutabilityReturnType` (1 instance, server only)
- **File**: `server/src/main/java/com/jetbrains/youtrackdb/internal/server/plugin/gremlin/YTDBAbstractOpProcessor.java:417`
- **Fix**: Ensure method returns consistent mutability (wrap in `Collections.unmodifiableMap()` or always return mutable)
- **Test**: `./mvnw -pl server clean test`

### Step 8: Final verification
1. Run full build: `./mvnw clean compile` — expect zero ErrorProne warnings
2. Run full test suite: `./mvnw clean package`
3. Update this file to mark all complete

## POM Change Pattern

For each check, append to the ErrorProne plugin arg in **both** `pom.xml` and `core/pom.xml`:

```
-Xep:<CheckName>:ERROR
```

After all 7 checks the arg will end with:
```
-Xep:NullAway:ERROR -Xep:BooleanLiteral:ERROR -Xep:MissingSummary:ERROR -Xep:StringCaseLocaleUsage:ERROR -Xep:PatternMatchingInstanceof:ERROR -Xep:UnusedVariable:ERROR -Xep:UnnecessaryParentheses:ERROR -Xep:MixedMutabilityReturnType:ERROR
```
