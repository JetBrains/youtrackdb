# Track 1 Technical Review: EdgeKey timestamp and tombstone value model

## Review Summary

Track 1 is the foundational data model change for SI. The plan correctly identifies
the core components (EdgeKey, EdgeKeySerializer, LinkBagValue, LinkBagValueSerializer)
and their modification scope. One pre-existing bug was found in EdgeKeySerializer
that must be fixed as part of this track.

## Findings

### Finding T1 [blocker]
**Location**: `EdgeKeySerializer.doGetObjectSize()` (line 29) —
`core/.../ridbag/ridbagbtree/EdgeKeySerializer.java`
**Issue**: The second field's size is read from `startPosition` instead of
`startPosition + size`, using the ridBagId's length prefix byte as the
targetCollection's size. This produces incorrect total sizes when ridBagId
and targetCollection have different variable-length byte widths. The
deserialization (`doDeserialize`) and ByteBuffer/WALChanges variants are
correct — only the `byte[]` `doGetObjectSize` method has the bug.
**Proposed fix**: Fix the offset in `doGetObjectSize` as part of Step 1
(pre-existing bug fix before adding 4th field):
```java
var size = LongSerializer.getObjectSize(stream, startPosition);
size += IntSerializer.INSTANCE.getObjectSize(serializerFactory, stream, startPosition + size);
return size + LongSerializer.getObjectSize(stream, startPosition + size);
```
Add a regression test with values that produce different variable-length sizes.

### Finding T2 [should-fix]
**Location**: Track 1 plan — EdgeKey serializer refactoring
**Issue**: When adding `ts` as a 4th field, the serializer has 7 code paths
(byte[] serialize/deserialize/size, ByteBuffer position/offset, WALChanges,
object-based size). Each must correctly track offsets. The current pattern
in `doGetObjectSize` (accumulating `size` without updating a separate offset
variable) is error-prone for 4 fields.
**Proposed fix**: When updating EdgeKeySerializer, use a clear offset-tracking
pattern where each field advances the offset explicitly (similar to how
`doDeserialize` already works). Ensure all 7 code paths are updated
consistently.

### Finding T3 [suggestion]
**Location**: Track 1 plan — boundary EdgeKey values in IsolatedLinkBagBTreeImpl
**Issue**: The plan mentions updating call sites but doesn't explicitly address
boundary-condition EdgeKeys used for range iteration (e.g.,
`new EdgeKey(linkBagId, Integer.MIN_VALUE, Long.MIN_VALUE)` and
`new EdgeKey(linkBagId, Integer.MAX_VALUE, Long.MAX_VALUE)` at ~14 locations
in `IsolatedLinkBagBTreeImpl`). These need `Long.MIN_VALUE`/`Long.MAX_VALUE`
for the `ts` parameter to preserve their boundary semantics.
**Proposed fix**: Use `Long.MIN_VALUE` for lower-bound EdgeKeys and
`Long.MAX_VALUE` for upper-bound EdgeKeys. Consider a static factory method
(`EdgeKey.lowerBound(linkBagId)`, `EdgeKey.upperBound(linkBagId)`) to
centralize the boundary pattern.

### Finding T4 [suggestion]
**Location**: Track 1 plan — test coverage for EdgeKeySerializer
**Issue**: The existing `EdgeKeySerializerTest` has 4 round-trip tests but
uses the same values (42, 24, 67) for all. Adding `ts` requires tests with
varying value sizes to exercise the variable-length encoding correctly.
**Proposed fix**: Add tests with: (a) small values (1-byte encoding),
(b) large values (8-byte encoding for longs), (c) mixed sizes across fields,
(d) boundary values (0, Long.MAX_VALUE). Test all serializer code paths.

## Gate Verdict: PASS (with T1 fix required)

T1 is a pre-existing bug that will become critical when a 4th field is added.
Fix it as part of the first step. T2-T4 are execution guidance, not blockers.
