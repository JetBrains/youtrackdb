# Track 6 Risk Review

## Findings

### Finding R1 [resolved — not applicable]
**Location**: SQL grammar extension
**Issue**: Plan's risk of grammar ambiguity with new tokens.
**Resolution**: No grammar changes needed — reusing existing `BY KEY`/`BY VALUE`.

### Finding R2 [resolved — invalid]
**Location**: `AbstractLinkBag` change events
**Issue**: Risk review claimed secondary RID not available in change events.
**Resolution**: `MultiValueChangeEvent.getValue()` already returns the secondary
RID. Confirmed by reading `AbstractLinkBag.addEvent()` (passes primaryRid as
key, secondaryRid as value) and `MultiValueChangeEvent` (generic `<K, V>` with
both `getKey()` and `getValue()`). No event infrastructure changes needed.

### Finding R3 [should-fix]
**Location**: `IndexDefinitionFactory.createSingleFieldIndexDefinition()` line 238
**Issue**: Factory routing needs to check `indexBy` for LINKBAG type. Currently
ignored. Straightforward change — add a conditional in the LINKBAG branch.
**Proposed fix**: Check `indexBy` parameter: VALUE → secondary definition,
KEY/null → primary definition.

### Finding R4 [suggestion]
**Location**: WAL atomic operation scope
**Issue**: Both primary and secondary index updates must be in same atomic
operation.
**Resolution**: Already guaranteed — `ClassIndexManager.processMultiValueIndex()`
processes all index definitions for a field in the same loop, within the same
atomic operation. Both primary and secondary indexes on the same field will be
updated atomically.

### Finding R5 [should-fix]
**Location**: Serialization/DDL round-trip
**Issue**: Secondary index definition must persist its class name and produce
correct DDL for recreation.
**Proposed fix**: Override `getFieldsToIndex()` and `toCreateIndexDDL()`.
Add round-trip test: create → persist → reload → verify.

### Finding R6 [should-fix]
**Location**: Test coverage
**Issue**: Need comprehensive tests for secondary index CRUD operations.
**Proposed fix**: Test edge add/remove with secondary index, dual indexes
(primary + secondary on same field), SQL queries using secondary index,
persistence round-trip.

### Finding R7 [resolved — invalid]
**Location**: Event structure transitive coupling
**Issue**: Risk review claimed event structure changes would cascade to all
index definitions.
**Resolution**: No event structure changes needed. Finding R2 invalidates
the premise.

## Summary

Track 6 risk profile is **low** after resolving the grammar and event model
concerns. Main risks are: (1) factory routing (straightforward), (2) DDL
serialization (follow existing pattern), (3) test coverage (achievable). No
blockers. WAL atomicity is guaranteed by the existing framework.
