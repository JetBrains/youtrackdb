# Risk Review — Track 5

## Findings

### R1 [should-fix] — Plan contradicts inline vs. delegation
Track 5 says "inline for all types to avoid per-step serializer lookups and virtual
dispatch" but also references CompositeKeySerializer as template (which uses delegation).
Delegation is preferred — lookup cost (PropertyTypeInternal array + EnumMap) is
negligible vs allocation savings. Adopting delegation for implementation.

### R2 [should-fix] — ID space difference requires careful adaptation
IndexMultiValuKeySerializer stores PropertyTypeInternal IDs (LONG=3) while
CompositeKeySerializer stores serializer IDs (LongSerializer.ID=10). Null encoding
also differs: `-(typeId+1)` vs NullSerializer.ID. The compareInByteBuffer must:
- Detect nulls via `typeId < 0` (not `== NullSerializer.ID`)
- Resolve serializers via `PropertyTypeInternal.getById(typeId)` +
  `serializerFactory.getObjectSerializer(type)`
Critical: copy-pasting from CompositeKeySerializer without these adaptations would
silently produce wrong results.

### R3 [suggestion] — 5 serializer types lack compareInByteBuffer overrides
BooleanSerializer, ByteSerializer, FloatSerializer, DoubleSerializer,
CompactedLinkSerializer fall through to default (deserialize + compareTo).
Functionally correct. For common case (INTEGER/LONG/STRING + LONG version),
zero-alloc path is fully effective.

### R7 [should-fix] — Coverage achievable with delegation (~8-10 branches)
Delegation reduces branch count in the new code. Type coverage comes from existing
serializer tests. New tests focus on composite-level walking, null handling, and
prefix comparison.

## Risk Assessment
- **Blast radius**: Moderate — affects all callers of `find(byte[])` in BTree
- **Rollback**: Clean — remove 2 methods from serializer + re-apply Track 4a
- **Performance**: Sound assumption — eliminates CompositeKey allocation per search step
- **WAL testability**: Adequate infrastructure exists (23 test files)

## Result: PASS (no blockers)
