package com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.record.impl.EmbeddedEntityImpl;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.math.BigDecimal;
import java.util.Date;
import java.util.Map;
import org.junit.Test;

/**
 * Tests for V2 partial deserialization (deserializePartial), field lookup (deserializeField), and
 * field name extraction (getFieldNames). Covers linear mode (<=12 properties), linear probing hash table
 * mode (13+ properties), and edge cases.
 */
public class RecordSerializerBinaryV2PartialTest extends DbTestBase {

  private final RecordSerializerBinaryV2 v2 = new RecordSerializerBinaryV2();

  // ========================================================================================
  // deserializePartial
  // ========================================================================================

  @Test
  public void partial_singleField_linearMode() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setString("name", "Alice");
    entity.setInt("age", 30);

    var deserialized = partialDeserialize(entity, "name");
    assertThat((String) deserialized.getProperty("name")).isEqualTo("Alice");
    // age should not be deserialized
    assertThat(deserialized.hasProperty("age")).isFalse();
  }

  @Test
  public void partial_singleField_linearMode_fiveProperties() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setString("name", "Bob");
    entity.setInt("age", 25);
    entity.setDouble("score", 99.5);
    entity.setBoolean("active", true);
    entity.setString("email", "bob@example.com");

    var deserialized = partialDeserialize(entity, "score");
    assertThat((double) deserialized.getProperty("score")).isEqualTo(99.5);
    assertThat(deserialized.hasProperty("name")).isFalse();
    assertThat(deserialized.hasProperty("age")).isFalse();
  }

  @Test
  public void partial_multipleFields_linearMode() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setString("first", "A");
    entity.setString("second", "B");
    entity.setString("third", "C");
    entity.setString("fourth", "D");

    var deserialized = partialDeserialize(entity, "second", "fourth");
    assertThat((String) deserialized.getProperty("second")).isEqualTo("B");
    assertThat((String) deserialized.getProperty("fourth")).isEqualTo("D");
    assertThat(deserialized.hasProperty("first")).isFalse();
    assertThat(deserialized.hasProperty("third")).isFalse();
  }

  @Test
  public void partial_nonExistentField_returnsNull() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setString("name", "Test");
    entity.setString("a", "x");
    entity.setString("b", "y");

    var deserialized = partialDeserialize(entity, "nonexistent");
    assertThat(deserialized.hasProperty("nonexistent")).isFalse();
  }

  @Test
  public void partial_nullValueField() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setProperty("present", "value");
    entity.setProperty("empty", null);
    entity.setProperty("other", "stuff");

    var deserialized = partialDeserialize(entity, "empty");
    assertThat(deserialized.hasProperty("empty")).isTrue();
    assertThat((Object) deserialized.getProperty("empty")).isNull();
  }

  @Test
  public void partial_allFields_linearMode() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setString("a", "1");
    entity.setString("b", "2");
    entity.setString("c", "3");

    var deserialized = partialDeserialize(entity, "a", "b", "c");
    assertThat((String) deserialized.getProperty("a")).isEqualTo("1");
    assertThat((String) deserialized.getProperty("b")).isEqualTo("2");
    assertThat((String) deserialized.getProperty("c")).isEqualTo("3");
  }

  @Test
  public void partial_emptyEntity() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    var deserialized = partialDeserialize(entity, "anything");
    assertThat(deserialized.hasProperty("anything")).isFalse();
  }

  // ========================================================================================
  // Schema-aware partial deserialization
  // ========================================================================================

  @Test
  public void partial_schemaAwareProperty_linearMode() {
    // Schema-aware properties use global property ID encoding. Verify partial
    // deserialization correctly resolves the ID back to the property name and
    // returns the correct value.
    var clazz = session.createClass("PartialSchemaV2Test");
    clazz.createProperty("name", PropertyType.STRING);
    clazz.createProperty("age", PropertyType.INTEGER);
    clazz.createProperty("score", PropertyType.DOUBLE);

    session.begin();
    var entity = (EntityImpl) session.newEntity("PartialSchemaV2Test");
    entity.setProperty("name", "Alice");
    entity.setProperty("age", 30);
    entity.setProperty("score", 95.5);

    var deserialized = partialDeserialize(entity, "score");
    assertThat((double) deserialized.getProperty("score")).isEqualTo(95.5);
    assertThat(deserialized.hasProperty("name")).isFalse();
    assertThat(deserialized.hasProperty("age")).isFalse();
  }

  @Test
  public void partial_schemaAwareMultipleFields() {
    // Request two schema-aware properties out of five to verify multi-field
    // partial deserialization with global property ID encoding.
    var clazz = session.createClass("PartialSchemaMultiV2");
    clazz.createProperty("a", PropertyType.STRING);
    clazz.createProperty("b", PropertyType.INTEGER);
    clazz.createProperty("c", PropertyType.DOUBLE);
    clazz.createProperty("d", PropertyType.BOOLEAN);
    clazz.createProperty("e", PropertyType.STRING);

    session.begin();
    var entity = (EntityImpl) session.newEntity("PartialSchemaMultiV2");
    entity.setProperty("a", "val_a");
    entity.setProperty("b", 10);
    entity.setProperty("c", 3.14);
    entity.setProperty("d", true);
    entity.setProperty("e", "val_e");

    var deserialized = partialDeserialize(entity, "b", "e");
    assertThat((int) deserialized.getProperty("b")).isEqualTo(10);
    assertThat((String) deserialized.getProperty("e")).isEqualTo("val_e");
    assertThat(deserialized.hasProperty("a")).isFalse();
    assertThat(deserialized.hasProperty("c")).isFalse();
    assertThat(deserialized.hasProperty("d")).isFalse();
  }

  // ========================================================================================
  // deserializeField (for binary comparison)
  // ========================================================================================

  @Test
  public void field_stringField_linearMode() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setString("name", "Alice");
    entity.setInt("age", 30);
    entity.setString("email", "alice@test.com");

    var field = deserializeFieldFromEntity(entity, "name", false);
    assertThat(field).isNotNull();
    assertThat(field.name).isEqualTo("name");
    assertThat(field.type).isEqualTo(PropertyTypeInternal.STRING);

    // Verify value bytes are positioned correctly by deserializing the value
    var value = new RecordSerializerBinaryV1()
        .deserializeValue(session, field.bytes, field.type, null);
    assertThat(value).isEqualTo("Alice");
  }

  @Test
  public void field_integerField_linearMode() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setString("name", "Bob");
    entity.setInt("age", 42);
    entity.setDouble("score", 88.0);

    var field = deserializeFieldFromEntity(entity, "age", false);
    assertThat(field).isNotNull();
    assertThat(field.name).isEqualTo("age");
    assertThat(field.type).isEqualTo(PropertyTypeInternal.INTEGER);

    // Verify value bytes are positioned correctly by deserializing the value
    var value = new RecordSerializerBinaryV1()
        .deserializeValue(session, field.bytes, field.type, null);
    assertThat(value).isEqualTo(42);
  }

  @Test
  public void field_nonExistent_returnsNull() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setString("name", "Test");
    entity.setString("a", "x");
    entity.setString("b", "y");

    var field = deserializeFieldFromEntity(entity, "nonexistent", false);
    assertThat(field).isNull();
  }

  @Test
  public void field_nullValue_returnsNull() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setProperty("a", "x");
    entity.setProperty("b", null);
    entity.setProperty("c", "z");

    var field = deserializeFieldFromEntity(entity, "b", false);
    // Null value has valueLength 0 → returns null (not binary comparable)
    assertThat(field).isNull();
  }

  @Test
  public void field_linearMode() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setString("name", "One");
    entity.setInt("count", 7);

    var field = deserializeFieldFromEntity(entity, "count", false);
    assertThat(field).isNotNull();
    assertThat(field.name).isEqualTo("count");
    assertThat(field.type).isEqualTo(PropertyTypeInternal.INTEGER);
  }

  @Test
  public void field_allBinaryComparableTypes() {
    // Verify deserializeField works for all types that BinaryComparatorV0 supports
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setString("s", "hello");
    entity.setInt("i", 42);
    entity.setLong("l", 123L);
    entity.setShort("sh", (short) 7);
    entity.setFloat("f", 3.14f);
    entity.setDouble("d", 2.718);
    entity.setByte("by", (byte) 0x42);
    entity.setBoolean("bo", true);
    entity.setDateTime("dt", new Date(1711238400000L));
    entity.setProperty("dc", new BigDecimal("99.99"));

    var expectedTypes = Map.of(
        "s", PropertyTypeInternal.STRING,
        "i", PropertyTypeInternal.INTEGER,
        "l", PropertyTypeInternal.LONG,
        "sh", PropertyTypeInternal.SHORT,
        "f", PropertyTypeInternal.FLOAT,
        "d", PropertyTypeInternal.DOUBLE,
        "by", PropertyTypeInternal.BYTE,
        "bo", PropertyTypeInternal.BOOLEAN,
        "dt", PropertyTypeInternal.DATETIME,
        "dc", PropertyTypeInternal.DECIMAL);

    for (var entry : expectedTypes.entrySet()) {
      var bf = deserializeFieldFromEntity(entity, entry.getKey(), false);
      assertThat(bf).as("Field '%s' should be non-null", entry.getKey()).isNotNull();
      assertThat(bf.name).isEqualTo(entry.getKey());
      assertThat(bf.type).as("Field '%s' type", entry.getKey()).isEqualTo(entry.getValue());
    }
  }

  // ========================================================================================
  // deserializeField — LINK type
  // ========================================================================================

  @Test
  public void field_linkProperty_linearMode() {
    // Verify deserializeField returns a non-null BinaryField for a LINK property
    // so that BinaryComparatorV0 can compare RID bytes directly.
    session.begin();
    var rid = new RecordId(10, 42);

    var entity = (EntityImpl) session.newEntity();
    entity.setString("name", "source");
    entity.setString("extra", "pad");
    entity.setProperty("ref", rid, PropertyType.LINK);

    var field = deserializeFieldFromEntity(entity, "ref", false);
    assertThat(field).isNotNull();
    assertThat(field.name).isEqualTo("ref");
    assertThat(field.type).isEqualTo(PropertyTypeInternal.LINK);

    // Verify value bytes encode the correct RID (consistent with other field tests).
    // deserializeField returns raw value bytes in V1-compatible format.
    var value = new RecordSerializerBinaryV1()
        .deserializeValue(session, field.bytes, field.type, null);
    assertThat(value).isEqualTo(rid);
  }

  // ========================================================================================
  // getFieldNames
  // ========================================================================================

  @Test
  public void fieldNames_emptyEntity() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    var names = getFieldNamesFromEntity(entity, false);
    assertThat(names).isEmpty();
  }

  @Test
  public void fieldNames_linearMode() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setString("first", "A");
    entity.setInt("second", 2);

    var names = getFieldNamesFromEntity(entity, false);
    assertThat(names).containsExactlyInAnyOrder("first", "second");
  }

  @Test
  public void fieldNames_linearMode_fiveProperties() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setString("a", "1");
    entity.setString("b", "2");
    entity.setString("c", "3");
    entity.setString("d", "4");
    entity.setString("e", "5");

    var names = getFieldNamesFromEntity(entity, false);
    assertThat(names).containsExactlyInAnyOrder("a", "b", "c", "d", "e");
  }

  @Test
  public void fieldNames_manyProperties() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    for (int i = 0; i < 20; i++) {
      entity.setString("prop_" + i, "val");
    }
    var names = getFieldNamesFromEntity(entity, false);
    String[] expected = new String[20];
    for (int i = 0; i < 20; i++) {
      expected[i] = "prop_" + i;
    }
    assertThat(names).containsExactlyInAnyOrder(expected);
  }

  @Test
  public void fieldNames_withNullValue() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setString("name", "test");
    entity.setProperty("empty", null);
    entity.setString("other", "value");

    var names = getFieldNamesFromEntity(entity, false);
    assertThat(names).containsExactlyInAnyOrder("name", "empty", "other");
  }

  // ========================================================================================
  // Boundary: 13-property threshold (first hash table mode case)
  // ========================================================================================

  @Test
  public void partial_thirteenProperties_hashTableModeBoundary() {
    // Exactly 13 properties = first hash table mode case (LINEAR_MODE_THRESHOLD = 12).
    // An off-by-one in the threshold check would route to the wrong deserialize path.
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    for (int i = 0; i < 13; i++) {
      entity.setString("field_" + i, "val_" + i);
    }

    var deserialized = partialDeserialize(entity, "field_7");
    assertThat(deserialized.getString("field_7")).isEqualTo("val_7");
    assertThat(deserialized.hasProperty("field_0")).isFalse();
    assertThat(deserialized.hasProperty("field_12")).isFalse();
  }

  // ========================================================================================
  // deserializeField — embedded=true code path
  // ========================================================================================

  @Test
  public void field_embeddedEntity_linearMode() {
    // Verify deserializeField works when embedded=true, which adds a class name
    // prefix before the property count. If the class name skip is wrong,
    // the field lookup reads garbage.
    session.createClass("EmbFieldTest");
    session.begin();
    var entity = (EntityImpl) session.newEntity("EmbFieldTest");
    entity.setString("name", "embedded");
    entity.setInt("value", 42);
    entity.setString("extra", "pad");

    var field = deserializeFieldFromEntity(entity, "value", true);
    assertThat(field).isNotNull();
    assertThat(field.name).isEqualTo("value");
    assertThat(field.type).isEqualTo(PropertyTypeInternal.INTEGER);

    // Verify value bytes
    var value = new RecordSerializerBinaryV1()
        .deserializeValue(session, field.bytes, field.type, null);
    assertThat(value).isEqualTo(42);
  }

  // ========================================================================================
  // getFieldNames — embedded=true code path
  // ========================================================================================

  @Test
  public void fieldNames_embeddedEntity() {
    // Verify getFieldNames works when embedded=true (same class name skip logic).
    session.createClass("EmbNamesTest");
    session.begin();
    var entity = (EntityImpl) session.newEntity("EmbNamesTest");
    entity.setString("a", "1");
    entity.setString("b", "2");
    entity.setString("c", "3");

    var names = getFieldNamesFromEntity(entity, true);
    assertThat(names).containsExactlyInAnyOrder("a", "b", "c");
  }

  // ========================================================================================
  // deserializeField — non-binary-comparable type returns null
  // ========================================================================================

  @Test
  public void field_nonBinaryComparableType_returnsNull() {
    // EMBEDDEDLIST is not binary-comparable; deserializeField must return null
    // to prevent binary comparison on non-comparable types.
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setString("name", "test");
    entity.setString("extra", "pad");
    var emb = new EmbeddedEntityImpl(session);
    emb.setProperty("inner", "val");
    entity.setProperty("emb", emb, PropertyType.EMBEDDED);

    var field = deserializeFieldFromEntity(entity, "emb", false);
    assertThat(field).isNull();
  }

  // ========================================================================================
  // Hash table mode tests (13+ properties)
  // ========================================================================================

  @Test
  public void partial_hashTableMode_fifteenProperties() {
    // 15 properties triggers hash table mode. Verify partial deserialization finds all requested
    // fields via linear probe — some properties may require multi-step probing.
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    for (int i = 0; i < 15; i++) {
      entity.setString("prop_" + i, "val_" + i);
    }

    // Request 3 fields scattered across the property list
    var deserialized = partialDeserialize(entity, "prop_0", "prop_7", "prop_14");
    assertThat(deserialized.getString("prop_0")).isEqualTo("val_0");
    assertThat(deserialized.getString("prop_7")).isEqualTo("val_7");
    assertThat(deserialized.getString("prop_14")).isEqualTo("val_14");
    assertThat(deserialized.hasProperty("prop_1")).isFalse();
    session.rollback();
  }

  @Test
  public void partial_hashTableMode_fiftyProperties() {
    // 50-property stress test for hash table partial deserialization
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    for (int i = 0; i < 50; i++) {
      entity.setString("field_" + i, "value_" + i);
    }

    var deserialized = partialDeserialize(entity, "field_25", "field_49");
    assertThat(deserialized.getString("field_25")).isEqualTo("value_25");
    assertThat(deserialized.getString("field_49")).isEqualTo("value_49");
    session.rollback();
  }

  @Test
  public void field_hashTableMode_fifteenProperties() {
    // Verify deserializeField works in hash table mode — field lookup via linear probe
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    for (int i = 0; i < 15; i++) {
      entity.setString("prop_" + i, "val_" + i);
    }

    var field = deserializeFieldFromEntity(entity, "prop_10", false);
    assertThat(field).isNotNull();
    assertThat(field.name).isEqualTo("prop_10");
    assertThat(field.type).isEqualTo(PropertyTypeInternal.STRING);
    // Verify the value bytes decode to the correct value
    var value = new RecordSerializerBinaryV1()
        .deserializeValue(session, field.bytes, field.type, null);
    assertThat(value).isEqualTo("val_10");
    session.rollback();
  }

  @Test
  public void fieldNames_hashTableMode_fifteenProperties() {
    // Verify getFieldNames in hash table mode returns all property names
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    for (int i = 0; i < 15; i++) {
      entity.setString("prop_" + i, "val_" + i);
    }

    var names = getFieldNamesFromEntity(entity, false);
    String[] expected = new String[15];
    for (int i = 0; i < 15; i++) {
      expected[i] = "prop_" + i;
    }
    assertThat(names).containsExactlyInAnyOrder(expected);
    session.rollback();
  }

  @Test
  public void partial_hashTableMode_nonExistentField() {
    // Requesting a field that doesn't exist in hash table mode should not throw
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    for (int i = 0; i < 13; i++) {
      entity.setString("prop_" + i, "val_" + i);
    }

    var deserialized = partialDeserialize(entity, "nonexistent");
    assertThat(deserialized.hasProperty("nonexistent")).isFalse();
    session.rollback();
  }

  @Test
  public void partial_hashTableMode_mixedTierEmbeddedEntity() {
    // Small parent (2 properties, linear) with large embedded child (15+ properties, hash table).
    // Verifies that different tiers can coexist in the same serialized record.
    session.begin();
    var parent = (EntityImpl) session.newEntity();
    parent.setString("parentName", "root");

    var child = new EmbeddedEntityImpl(session);
    for (int i = 0; i < 15; i++) {
      child.setProperty("child_" + i, "cval_" + i);
    }
    parent.setProperty("child", child, PropertyType.EMBEDDED);

    // Full round-trip verifies both linear parent and hash table embedded child
    var bytes = new BytesContainer();
    v2.serialize(session, parent, bytes);
    var deserialized = (EntityImpl) session.newEntity();
    v2.deserialize(session, deserialized, new BytesContainer(bytes.bytes));

    assertThat(deserialized.getString("parentName")).isEqualTo("root");
    var deserializedChild = (EntityImpl) deserialized.getProperty("child");
    assertThat(deserializedChild).isNotNull();
    for (int i = 0; i < 15; i++) {
      assertThat(deserializedChild.getString("child_" + i)).isEqualTo("cval_" + i);
    }
    session.rollback();
  }

  // ========================================================================================
  // Binary comparator with hash table mode (15+ properties)
  // ========================================================================================

  /**
   * Binary comparator correctness in hash table mode: serialize entities with 15+ properties
   * using V2, call deserializeField() to locate a field via the hash table linear probe,
   * then verify BinaryComparatorV0.isEqual() and compare() produce correct results.
   * Extends the 3-property binary comparator test from Track 6 to the hash table tier.
   */
  @Test
  public void binaryComparator_hashTableMode_deserializeFieldWithComparatorV0() {
    session.begin();

    // Build two entities with 15 properties each (triggers hash table mode),
    // differing only in the "score" field
    var entity1 = (EntityImpl) session.newEntity();
    var entity2 = (EntityImpl) session.newEntity();
    var entity3 = (EntityImpl) session.newEntity();
    for (int i = 0; i < 14; i++) {
      entity1.setString("pad_" + i, "val_" + i);
      entity2.setString("pad_" + i, "val_" + i);
      entity3.setString("pad_" + i, "val_" + i);
    }
    entity1.setDouble("score", 85.5);
    entity2.setDouble("score", 92.0);
    entity3.setDouble("score", 85.5);

    var field1 = deserializeFieldFromEntity(entity1, "score", false);
    var field2 = deserializeFieldFromEntity(entity2, "score", false);
    var field3 = deserializeFieldFromEntity(entity3, "score", false);

    assertThat(field1).isNotNull();
    assertThat(field2).isNotNull();
    assertThat(field3).isNotNull();

    var comparator = new BinaryComparatorV0();

    // 85.5 != 92.0
    assertThat(comparator.isEqual(session, field1, field2)).isFalse();

    // 85.5 == 85.5
    assertThat(comparator.isEqual(session, field1, field3)).isTrue();

    // 85.5 < 92.0 → negative
    assertThat(comparator.compare(session, field1, field2)).isLessThan(0);

    // 92.0 > 85.5 → positive
    assertThat(comparator.compare(session, field2, field1)).isGreaterThan(0);

    // 85.5 == 85.5 → zero
    assertThat(comparator.compare(session, field1, field3)).isEqualTo(0);
    session.rollback();
  }

  /**
   * Binary comparator with hash table mode for string fields: verifies that deserializeField()
   * correctly locates string fields in the linear probing hash table and BinaryComparatorV0 compares
   * them correctly via byte-level comparison.
   */
  @Test
  public void binaryComparator_hashTableMode_stringFieldComparison() {
    session.begin();

    var entity1 = (EntityImpl) session.newEntity();
    var entity2 = (EntityImpl) session.newEntity();
    var entity3 = (EntityImpl) session.newEntity();
    for (int i = 0; i < 14; i++) {
      entity1.setString("pad_" + i, "val");
      entity2.setString("pad_" + i, "val");
      entity3.setString("pad_" + i, "val");
    }
    entity1.setString("key", "apple");
    entity2.setString("key", "banana");
    entity3.setString("key", "apple");

    var field1 = deserializeFieldFromEntity(entity1, "key", false);
    var field2 = deserializeFieldFromEntity(entity2, "key", false);
    var field3 = deserializeFieldFromEntity(entity3, "key", false);

    assertThat(field1).isNotNull();
    assertThat(field2).isNotNull();
    assertThat(field3).isNotNull();

    var comparator = new BinaryComparatorV0();

    // "apple" != "banana"
    assertThat(comparator.isEqual(session, field1, field2)).isFalse();
    // "apple" == "apple"
    assertThat(comparator.isEqual(session, field1, field3)).isTrue();
    // "apple" < "banana" → negative
    assertThat(comparator.compare(session, field1, field2)).isLessThan(0);
    // "banana" > "apple" → positive
    assertThat(comparator.compare(session, field2, field1)).isGreaterThan(0);
    // "apple" == "apple" → zero
    assertThat(comparator.compare(session, field1, field3)).isEqualTo(0);
    session.rollback();
  }

  // ========================================================================================
  // Helpers
  // ========================================================================================

  private EntityImpl partialDeserialize(EntityImpl entity, String... fields) {
    var bytes = new BytesContainer();
    v2.serialize(session, entity, bytes);

    var deserialized = (EntityImpl) session.newEntity();
    var readBytes = new BytesContainer(bytes.bytes);
    v2.deserializePartial(session, deserialized, readBytes, fields);
    return deserialized;
  }

  private BinaryField deserializeFieldFromEntity(EntityImpl entity, String fieldName,
      boolean embedded) {
    var bytes = new BytesContainer();
    if (embedded) {
      v2.serializeWithClassName(session, entity, bytes);
    } else {
      v2.serialize(session, entity, bytes);
    }

    var readBytes = new BytesContainer(bytes.bytes);
    return v2.deserializeField(session, readBytes, null, fieldName, embedded, null, null);
  }

  private String[] getFieldNamesFromEntity(EntityImpl entity, boolean embedded) {
    var bytes = new BytesContainer();
    if (embedded) {
      v2.serializeWithClassName(session, entity, bytes);
    } else {
      v2.serialize(session, entity, bytes);
    }

    var readBytes = new BytesContainer(bytes.bytes);
    return v2.getFieldNames(session, entity, readBytes, embedded);
  }
}
