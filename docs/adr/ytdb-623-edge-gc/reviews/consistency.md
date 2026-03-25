# Consistency Review — Edge Tombstone GC During Page Split

**Date**: 2026-03-24
**Status**: PASS (1 iteration)

## Findings

### Finding CR1 [should-fix] — VERIFIED
**Location**: Plan (Track 1, D2) and Design (workflow, method description)
**Issue**: Prose references `shrink(0)` but `Bucket.shrink()` requires two
parameters: `(int newSize, BinarySerializerFactory serializerFactory)`.
**Fix applied**: Changed all prose references to `shrink(0, serializerFactory)`.

### Finding CR2 [should-fix] — VERIFIED
**Location**: Design class diagram, `SharedLinkBagBTree.updateSize`
**Issue**: Diagram showed `updateSize(int, AtomicOperation)` but actual
signature uses `long`.
**Fix applied**: Changed to `updateSize(long, AtomicOperation)`.

### Finding CR3 [should-fix] — VERIFIED
**Location**: Design class diagram, `AtomicOperation` block
**Issue**: `AtomicOperation` is an interface, not a class. Missing
`<<interface>>` stereotype.
**Fix applied**: Added `<<interface>>` stereotype.

### Finding CR4 [should-fix] — VERIFIED
**Location**: Plan, Track 1 description
**Issue**: Plan didn't specify the control flow mechanism for insert retry
after filtering. The current code uses a `while (!addLeafEntry)` loop;
the retry should use `continue`.
**Fix applied**: Track 1 now describes using `continue` to re-evaluate
the loop condition.

### Finding CR5 [suggestion] — VERIFIED
**Location**: Design workflow diagram and method description
**Issue**: Separate `getKey()`/`getValue()` calls shown, but
`Bucket.getEntry()` returns both in one deserialization pass.
**Fix applied**: Updated diagram and prose to use `getEntry()`. Updated
class diagram to show `getEntry` instead of separate accessors.

### Finding CR6 [suggestion] — VERIFIED
**Location**: Plan, Component Map annotation
**Issue**: Didn't explain how `SharedLinkBagBTree` accesses
`AbstractStorage`.
**Fix applied**: Added note that `storage` reference is inherited from
`DurableComponent`.

### Finding CR7 [cosmetic] — VERIFIED
**Location**: Design workflow diagram, `getRawEntry(i)` calls
**Issue**: Missing `serializerFactory` parameter, inconsistent with
`getEntry(i, serializerFactory)` in the same diagram.
**Fix applied**: Changed to `getRawEntry(i, serializerFactory)`.
