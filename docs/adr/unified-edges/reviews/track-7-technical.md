# Track 7 Technical Review

## Finding T1 [should-fix]
**Location**: Track 7 plan item "Delete `LightWeightEdgesTest`"
**Issue**: `LightWeightEdgesTest` was already deleted in a prior track. No action needed.
**Proposed fix**: Note in step file that this is already complete.

## Finding T2 [should-fix]
**Location**: Track 7 plan item "Regenerate DSL classes"
**Issue**: Gremlin DSL classes are generated at compile time by `GremlinDslProcessor`
from the `@GremlinDsl` annotation. They live in `core/target/generated-sources/` and
are not tracked in git. Neither the DSL source annotations nor generated output contain
any `StatefulEdge` references. "DSL regeneration" happens automatically on compile.
**Proposed fix**: Remove "Regenerate DSL classes" as a separate step.

## Finding T3 [should-fix]
**Location**: `YTDBStatefulEdge.java` in `api/gremlin/embedded/`
**Issue**: The plan's component map suggests renaming `YTDBStatefulEdge`, but the correct
approach is to **delete** it entirely. The `RID id()` declaration should be moved to
`YTDBEdge` (matching `YTDBVertex` and `YTDBDomainObject` which already declare `RID id()`).
Then `YTDBEdgeImpl` drops the implements clause and `YTDBGremlinPlugin` removes the import.
**Proposed fix**: (1) Add `RID id()` to `YTDBEdge`, (2) Remove `implements YTDBStatefulEdge`
from `YTDBEdgeImpl`, (3) Remove `YTDBStatefulEdge.class` from `YTDBGremlinPlugin`, (4)
Delete `YTDBStatefulEdge.java`.

## Finding T4 [suggestion]
**Location**: `DoubleSidedEdgeLinkBagTest` and `LinkBagIndexTest`
**Issue**: `DoubleSidedEdgeLinkBagTest` is already fully updated. `LinkBagIndexTest` has
~11 occurrences of "lightweight entry" in comments describing single-RID LinkBag entries
on non-edge documents — confusing terminology post-unification.
**Proposed fix**: Update `LinkBagIndexTest` comments to replace "lightweight entry" with
"single-RID entry". `DoubleSidedEdgeLinkBagTest` needs no changes.

## Finding T5 [suggestion]
**Location**: `SelectStatementExecutionTest.java`
**Issue**: 7 test methods contain "StateFull" in names (e.g.,
`testOutEStateFullEdgesIndexUsageInGraph`). Cosmetic but misleading post-unification.
**Proposed fix**: Rename test methods to remove "StateFull" from names.

## Finding T6 [blocker]
**Location**: `YTDBGremlinPlugin.java`
**Issue**: Imports and registers `YTDBStatefulEdge.class` in Gremlin plugin imports (line 37).
If `YTDBStatefulEdge` is deleted without updating this file, build fails.
**Proposed fix**: Remove `YTDBStatefulEdge.class` import and `addClassImports()` entry.
`YTDBEdge.class` is already present — no replacement needed.

## Finding T7 [suggestion]
**Location**: Cucumber test verification
**Issue**: Cucumber tests run as part of `./mvnw -pl core,embedded clean test`. Prior tracks
maintained compilation and test passing throughout, so this is a confirmation check.
**Proposed fix**: Reframe as confirmation rather than debugging exercise.

## Summary

**PASS** with adjustments. One blocker (T6) addressed by including `YTDBGremlinPlugin`
in the cleanup. Remaining work is narrower than the plan anticipated — most items are
already done from prior tracks.
