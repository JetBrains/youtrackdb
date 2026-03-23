# Track 2: Strengthen partial deserialization test coverage

## Progress
- [x] Review + decomposition
- [ ] Step implementation (0/2 complete)
- [ ] Track-level code review

## Base commit
`e9d4342971`

## Reviews completed
- [x] Technical

## Steps
- [ ] Step: Schema-less partial deserialization and deserializeField tests
  Add test methods to `EntitySchemalessBinarySerializationTest` covering:
  1. **Partial deserialization edge cases** via serializer-level `fromStream(session, bytes, entity, fields)`:
     - Request a non-existent field → entity should not contain that field
     - Request a field when entity has null-valued properties → null values preserved
     - Partial deserialization of an entity with an embedded entity property →
       embedded entity correctly deserialized when its field is requested
     - Request a subset of fields from an entity with many properties (10+) →
       only requested fields populated
  2. **`deserializeField()` unit tests** for binary-comparable types:
     - Serialize an entity with properties of all 13 binary-comparable types
       (INTEGER, LONG, STRING, DOUBLE, FLOAT, SHORT, BYTE, BOOLEAN, DATE,
       DATETIME, BINARY, LINK, DECIMAL), call `deserializeField()` for each →
       verify non-null `BinaryField` with correct `type`
     - Call `deserializeField()` for a non-binary-comparable type (e.g., EMBEDDED)
       → returns null
     - Call `deserializeField()` for a null-valued property → returns null
     - Call `deserializeField()` for a non-existent field name → returns null
  3. **Version byte handling**: Extract version byte from `toStream()` output,
     obtain `EntitySerializer` via `RecordSerializerBinary.getSerializer(version)`,
     create `BytesContainer` skipping version byte. Use
     `PropertyEncryptionNone.instance()` for encryption parameter; pass null
     for `iClass` and `schema` in schema-less mode.

- [ ] Step: Schema-aware/mixed getFieldNames, deserializeField, and getProperty integration test
  Add tests covering schema-aware and mixed-mode serialization:
  1. **`getFieldNames()` for schema-aware properties**: Create a temporary
     database with a schema class, serialize an entity with schema-defined
     properties, verify `getFieldNames()` returns all property names correctly.
     (Schema-less `getFieldNames()` is already covered by existing
     `testFieldNames`/`testFieldNamesRaw`.)
  2. **`getFieldNames()` for mixed entities**: Entity with both schema-aware
     (global property ID encoded) and schema-less (inline name encoded)
     properties → `getFieldNames()` returns all names from both encoding modes.
  3. **`deserializeField()` with schema-aware properties**: Serialize a
     schema-aware entity, call `deserializeField()` with real `ImmutableSchema`
     (from `session.getMetadata().getImmutableSchemaSnapshot()`) and non-null
     `SchemaClass` → verify correct `BinaryField` for binary-comparable types.
  4. **`getProperty()` integration test** (database-backed): Persist an entity
     to disk (or memory-with-flush), reload it via `session.load(rid)`, call
     `getProperty(name)` for a single property → verify correct value returned.
     This tests the full `EntityImpl.checkForProperties(name)` →
     `deserializePartial()` path. Verify correctness of the returned value
     (not internal partial state, per review finding T1).
  Uses temporary database pattern (as in `testSimpleLiteralSet`). Tests go
  in `EntitySchemalessBinarySerializationTest` to maintain version
  parameterization.
