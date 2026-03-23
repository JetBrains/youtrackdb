# Track 2 Technical Review

## Finding T1 [should-fix]
**Location**: Track 2 description, `getProperty()` triggers partial deserialization
**Issue**: `EntityImpl.properties` is private and every public accessor
(`getPropertiesCount()`, `propertyNames()`, `isEmpty()`) calls
`checkForProperties()` with no arguments, triggering full deserialization.
There is no public API to verify "only that property was deserialized"
without triggering full deserialization. The only approaches are reflection
or testing at the serializer level via `fromStream(session, bytes, entity,
fields)`.
**Proposed fix**: Test partial deserialization at the serializer level using
`fromStream` with specific field names (as existing `testPartial` does), or
reframe the `getProperty()` test to verify correctness of the returned value
rather than asserting internal partial state.

## Finding T2 [should-fix]
**Location**: Track 2 description, schema-aware tests in `EntitySchemalessBinarySerializationTest`
**Issue**: `EntitySchemalessBinarySerializationTest` has no schema setup —
it never calls `createProperty()` or defines schema classes. Adding
schema-aware tests requires a database with schema. Separate
`DocumentSchemafullBinarySerializationTest` exists but is not parameterized
over serializer versions.
**Proposed fix**: Add schema-aware tests within
`EntitySchemalessBinarySerializationTest` by creating a temporary database
with schema classes per test (following the pattern of existing tests like
`testSimpleLiteralSet`). This keeps version parameterization intact.

## Finding T3 [should-fix]
**Location**: Track 2 description, `deserializeField()` unit tests
**Issue**: `deserializeField()` returns `null` for non-binary-comparable
types (EMBEDDED, EMBEDDEDLIST, EMBEDDEDSET, EMBEDDEDMAP, LINKBAG, LINKLIST,
LINKSET, LINKMAP, ANY, CUSTOM, TRANSIENT) and for null-valued properties.
Testing "each property" with non-null expectations will fail for these types.
**Proposed fix**: Scope `deserializeField()` tests to: (1) 13
binary-comparable types expecting non-null `BinaryField`, (2) non-comparable
types expecting null, (3) null-valued properties expecting null, (4)
non-existent fields expecting null.

## Finding T4 [suggestion]
**Location**: Track 2 description, `deserializeField()` parameter setup
**Issue**: Calling `deserializeField()` requires `DatabaseSessionEmbedded`,
`SchemaClass`, `ImmutableSchema`, and `PropertyEncryption`. For schema-less
entities, `SchemaClass` can be null and `PropertyEncryptionNone.instance()`
works. Schema-aware entities need a real session with metadata.
**Proposed fix**: Document that schema-less `deserializeField()` tests can
pass null for `iClass`/`schema` and `PropertyEncryptionNone.instance()` for
encryption.

## Finding T5 [suggestion]
**Location**: Track 2 description, `getFieldNames()` correctness
**Issue**: Existing `testFieldNames()` and `testFieldNamesRaw()` already
cover schema-less `getFieldNames()`. New tests would partially duplicate.
**Proposed fix**: Focus new `getFieldNames()` tests on schema-aware and
mixed (schema-aware + schema-less) entities.

## Finding T6 [suggestion]
**Location**: Track 2 description, version-agnostic `deserializeField()` tests
**Issue**: `deserializeField()` operates on `EntitySerializer`, not
`RecordSerializerBinary`. Tests must strip the version byte from serialized
bytes and obtain the correct `EntitySerializer` via
`((RecordSerializerBinary) serializer).getSerializer(bytes[0])`.
**Proposed fix**: Ensure test instructions specify version byte handling:
serialize via `toStream()`, extract version byte, get `EntitySerializer`,
create `BytesContainer` skipping version byte.

## Summary
No blockers. Three should-fix findings (T1-T3) require adjusting test
expectations and approach. Three suggestions (T4-T6) provide implementation
guidance. All findings are addressable during step decomposition.
