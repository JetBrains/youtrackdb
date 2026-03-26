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
 * field name extraction (getFieldNames). V2 uses hash-accelerated linear scan: each property entry
 * is prefixed with a 4-byte MurmurHash3 hash for fast rejection during partial deserialization.
 * Covers various property counts and edge cases.
 */
public class RecordSerializerBinaryV2PartialTest extends DbTestBase {

  private final RecordSerializerBinaryV2 v2 = new RecordSerializerBinaryV2();

  // ========================================================================================
  // deserializePartial
  // ========================================================================================

  @Test
  public void partial_singleField_twoProperties() {
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
  public void partial_singleField_fiveProperties() {
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
  public void partial_multipleFields_fourProperties() {
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
  public void partial_allFields_threeProperties() {
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
  public void partial_schemaAwareProperty() {
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
  public void field_stringField() {
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
  public void field_integerField() {
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
  public void field_twoProperties() {
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
  public void field_linkProperty() {
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
  public void fieldNames_twoProperties() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setString("first", "A");
    entity.setInt("second", 2);

    var names = getFieldNamesFromEntity(entity, false);
    assertThat(names).containsExactlyInAnyOrder("first", "second");
  }

  @Test
  public void fieldNames_fiveProperties() {
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
  // Higher property counts
  // ========================================================================================

  @Test
  public void partial_thirteenProperties() {
    // Thirteen properties — verifies hash-accelerated scan works at moderate count.
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    for (int i = 0; i < 13; i++) {
      entity.setString("field_" + i, "val_" + i);
    }

    var deserialized = partialDeserialize(entity, "field_7");
    assertThat(deserialized.getString("field_7")).isEqualTo("val_7");
    assertThat(deserialized.hasProperty("field_0")).isFalse();
    assertThat(deserialized.hasProperty("field_12")).isFalse();
    session.rollback();
  }

  // ========================================================================================
  // deserializeField — embedded=true code path
  // ========================================================================================

  @Test
  public void field_embeddedEntity() {
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
  // Many-property tests (13+ properties)
  // ========================================================================================

  @Test
  public void partial_fifteenProperties() {
    // 15 properties — verifies hash-accelerated partial deserialization at higher count.
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
  public void partial_fiftyProperties() {
    // 50-property stress test for hash-accelerated partial deserialization
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
  public void field_fifteenProperties() {
    // Verify deserializeField works with 15 properties — hash prefix fast-rejects non-matches
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
  public void fieldNames_fifteenProperties() {
    // Verify getFieldNames returns all property names for 15-property entity
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
  public void partial_nonExistentField_manyProperties() {
    // Requesting a field that doesn't exist in a many-property entity should not throw
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
  public void field_nonExistentField_manyProperties_returnsNull() {
    // deserializeField for a field that doesn't exist in a many-property entity should return
    // null. The hash-accelerated scan rejects all entries via hash prefix mismatch or name
    // comparison, then returns null after exhausting the entry list.
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    for (int i = 0; i < 15; i++) {
      entity.setString("prop_" + i, "val_" + i);
    }
    var field = deserializeFieldFromEntity(entity, "nonexistent_field", false);
    assertThat(field).isNull();
    session.rollback();
  }

  @Test
  public void partial_thirtyProperties_allIndividuallyRetrievable() {
    // Verify each of 30 properties is individually retrievable via partial
    // deserialization. While 4-byte hash collisions are extremely unlikely at this
    // scale (~0.01%), the exhaustive per-field check ensures correctness of the
    // linear scan for all entry positions. In the rare event of a collision, the
    // scan correctly falls through to full name comparison.
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    int n = 30;
    for (int i = 0; i < n; i++) {
      entity.setString("f_" + i, "v_" + i);
    }
    for (int i = 0; i < n; i++) {
      var deserialized = partialDeserialize(entity, "f_" + i);
      assertThat(deserialized.getString("f_" + i))
          .as("property f_%d", i)
          .isEqualTo("v_" + i);
    }
    session.rollback();
  }

  @Test
  public void partial_mixedSizeEmbeddedEntity() {
    // Small parent (2 properties) with large embedded child (15+ properties).
    // Verifies that entities of different sizes coexist in the same serialized record.
    session.begin();
    var parent = (EntityImpl) session.newEntity();
    parent.setString("parentName", "root");

    var child = new EmbeddedEntityImpl(session);
    for (int i = 0; i < 15; i++) {
      child.setProperty("child_" + i, "cval_" + i);
    }
    parent.setProperty("child", child, PropertyType.EMBEDDED);

    // Full round-trip verifies both small parent and large embedded child
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
  // Binary comparator with many properties (15+)
  // ========================================================================================

  /**
   * Binary comparator correctness with 15+ properties: serialize entities using V2, call
   * deserializeField() to locate a field via hash-accelerated scan, then verify
   * BinaryComparatorV0.isEqual() and compare() produce correct results.
   */
  @Test
  public void binaryComparator_manyProperties_deserializeFieldWithComparatorV0() {
    session.begin();

    // Build two entities with 15 properties each, differing only in the "score" field
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
   * Binary comparator with many-property string fields: verifies that deserializeField()
   * correctly locates string fields via hash-accelerated scan and BinaryComparatorV0 compares
   * them correctly via byte-level comparison.
   */
  @Test
  public void binaryComparator_manyProperties_stringFieldComparison() {
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
  // Boundary tests (single property, empty entity)
  // ========================================================================================

  @Test
  public void partial_singleProperty_found() {
    // Entity with exactly one property — minimal non-empty case. The scan loop runs
    // once and should find the match.
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setString("only", "value");

    var deserialized = partialDeserialize(entity, "only");
    assertThat(deserialized.getString("only")).isEqualTo("value");
    session.rollback();
  }

  @Test
  public void partial_singleProperty_notFound() {
    // Entity with exactly one property, but we request a different field.
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setString("only", "value");

    var deserialized = partialDeserialize(entity, "other");
    assertThat(deserialized.hasProperty("other")).isFalse();
    session.rollback();
  }

  @Test
  public void field_emptyEntity_returnsNull() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    var field = deserializeFieldFromEntity(entity, "anything", false);
    assertThat(field).isNull();
    session.rollback();
  }

  @Test
  public void field_singleProperty_found() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setString("only", "value");

    var field = deserializeFieldFromEntity(entity, "only", false);
    assertThat(field).isNotNull();
    assertThat(field.name).isEqualTo("only");
    assertThat(field.type).isEqualTo(PropertyTypeInternal.STRING);
    session.rollback();
  }

  @Test
  public void partial_twentyProperties() {
    // 20 properties — verifies hash-accelerated partial deserialization at a count
    // between the 15-property and 50-property tests.
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    for (int i = 0; i < 20; i++) {
      entity.setString("key_" + i, "val_" + i);
    }

    // Request first, middle, and last fields
    var deserialized = partialDeserialize(entity, "key_0", "key_10", "key_19");
    assertThat(deserialized.getString("key_0")).isEqualTo("val_0");
    assertThat(deserialized.getString("key_10")).isEqualTo("val_10");
    assertThat(deserialized.getString("key_19")).isEqualTo("val_19");
    assertThat(deserialized.hasProperty("key_5")).isFalse();
    session.rollback();
  }

  @Test
  public void partial_unicodePropertyName_foundViaHashScan() {
    // Multi-byte UTF-8 property names: the hash is computed from UTF-8 bytes, so
    // a mismatch between lookup encoding and stored encoding would cause lookup failure.
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setString("ascii", "val1");
    entity.setString("\u4e16\u754c", "world"); // Chinese "world"
    entity.setString("\u0410\u0411", "cyrillic");

    var deserialized = partialDeserialize(entity, "\u4e16\u754c");
    assertThat(deserialized.getString("\u4e16\u754c")).isEqualTo("world");
    assertThat(deserialized.hasProperty("ascii")).isFalse();
    session.rollback();
  }

  // ========================================================================================
  // Hash prefix verification
  // ========================================================================================

  @Test
  public void partial_schemaAware_hashPrefixUsesPropertyName() {
    // Schema-aware properties encode the property ID as a negative varint in the binary
    // format, but the 4-byte hash prefix is always computed from the property NAME string
    // (not the encoded ID). Verify via round-trip and direct byte inspection.
    var clazz = session.createClass("HashPrefixSchemaTest");
    clazz.createProperty("alpha", PropertyType.STRING);
    clazz.createProperty("beta", PropertyType.INTEGER);
    clazz.createProperty("gamma", PropertyType.DOUBLE);
    clazz.createProperty("delta", PropertyType.BOOLEAN);
    clazz.createProperty("epsilon", PropertyType.STRING);

    session.begin();
    var entity = (EntityImpl) session.newEntity("HashPrefixSchemaTest");
    entity.setProperty("alpha", "a_val");
    entity.setProperty("beta", 42);
    entity.setProperty("gamma", 3.14);
    entity.setProperty("delta", true);
    entity.setProperty("epsilon", "e_val");

    // Round-trip: request middle field
    var deserialized = partialDeserialize(entity, "gamma");
    assertThat((double) deserialized.getProperty("gamma")).isEqualTo(3.14);
    assertThat(deserialized.hasProperty("alpha")).isFalse();
    assertThat(deserialized.hasProperty("epsilon")).isFalse();

    // Direct byte inspection: verify the first entry's 4-byte hash prefix is computed
    // from the property name string, not the encoded property ID
    var bytes = new BytesContainer();
    v2.serialize(session, entity, bytes);
    var readBytes = new BytesContainer(bytes.bytes);
    VarIntSerializer.readAsInteger(readBytes); // skip property count
    int storedHash = com.jetbrains.youtrackdb.internal.common.serialization.types.IntegerSerializer
        .deserializeLiteral(readBytes.bytes, readBytes.offset);
    // The first property name varies by serialization order, but we can verify
    // the stored hash matches the MurmurHash3 of whichever name is first
    readBytes.skip(4); // skip hash
    int nameLen = VarIntSerializer.readAsInteger(readBytes);
    String firstName;
    if (nameLen > 0) {
      // schema-less encoding
      firstName = new String(readBytes.bytes, readBytes.offset, nameLen,
          java.nio.charset.StandardCharsets.UTF_8);
    } else {
      // schema-aware encoding: resolve property name from global ID
      int propertyId = (nameLen * -1) - 1;
      var globalProp = session.getMetadata().getSchema().getGlobalPropertyById(propertyId);
      firstName = globalProp.getName();
    }
    byte[] firstNameBytes = firstName.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    int expectedHash = com.jetbrains.youtrackdb.internal.common.hash.MurmurHash3
        .hash32WithSeed(firstNameBytes, 0, firstNameBytes.length, 0);
    assertThat(storedHash).as("Hash prefix for '%s'", firstName).isEqualTo(expectedHash);
    session.rollback();
  }

  @Test
  public void fieldNames_mixedSchemaAwareAndSchemaLess_twentyProperties() {
    // Verify getFieldNames correctly skips 4-byte hash prefix for both schema-aware
    // (negative varint name encoding) and schema-less (inline UTF-8) properties.
    var clazz = session.createClass("FieldNamesMixedTest");
    for (int i = 0; i < 10; i++) {
      clazz.createProperty("schema_" + i, PropertyType.STRING);
    }

    session.begin();
    var entity = (EntityImpl) session.newEntity("FieldNamesMixedTest");
    for (int i = 0; i < 10; i++) {
      entity.setProperty("schema_" + i, "val");
    }
    for (int i = 0; i < 10; i++) {
      entity.setString("dynamic_" + i, "val");
    }
    var names = getFieldNamesFromEntity(entity, false);
    String[] expected = new String[20];
    for (int i = 0; i < 10; i++) {
      expected[i] = "schema_" + i;
      expected[i + 10] = "dynamic_" + i;
    }
    assertThat(names).containsExactlyInAnyOrder(expected);
    session.rollback();
  }

  // --- Hash collision regression tests ---

  @Test
  public void partial_hashCollision_correctlySkipsNonMatchingEntry() {
    // "f_67927" and "f_144759" produce the same MurmurHash3 hash with seed 0 (114566882).
    // When we request only one of them, the collision guard must correctly skip the other
    // entry after hash match + name mismatch, without returning wrong data.
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setString("f_67927", "value_A");
    entity.setString("f_144759", "value_B");
    entity.setString("other", "value_C");

    // Request only the first collision partner — must not get confused by the second
    var deserialized1 = partialDeserialize(entity, "f_67927");
    assertThat(deserialized1.getString("f_67927")).isEqualTo("value_A");
    assertThat(deserialized1.hasProperty("f_144759")).isFalse();

    // Request only the second collision partner
    var deserialized2 = partialDeserialize(entity, "f_144759");
    assertThat(deserialized2.getString("f_144759")).isEqualTo("value_B");
    assertThat(deserialized2.hasProperty("f_67927")).isFalse();
    session.rollback();
  }

  @Test
  public void partial_hashCollision_bothRequestedFieldsReturned() {
    // Regression test: when two requested fields have the same 32-bit MurmurHash3 hash
    // ("f_67927" and "f_144759"), both must be found by deserializePartial. Before the fix,
    // the second field was silently lost because the inner loop broke at the first hash match.
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setString("f_67927", "value_A");
    entity.setString("f_144759", "value_B");
    entity.setString("other", "value_C");

    var deserialized = partialDeserialize(entity, "f_67927", "f_144759");
    assertThat(deserialized.getString("f_67927")).isEqualTo("value_A");
    assertThat(deserialized.getString("f_144759")).isEqualTo("value_B");
    assertThat(deserialized.hasProperty("other")).isFalse();
    session.rollback();
  }

  @Test
  public void field_hashCollision_correctFieldReturned() {
    // Verify deserializeField handles hash collisions: "f_67927" and "f_144759" share
    // the same hash. Requesting one must not return the other's data.
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setString("f_67927", "value_A");
    entity.setString("f_144759", "value_B");

    var field1 = deserializeFieldFromEntity(entity, "f_67927", false);
    assertThat(field1).isNotNull();
    assertThat(field1.name).isEqualTo("f_67927");

    var field2 = deserializeFieldFromEntity(entity, "f_144759", false);
    assertThat(field2).isNotNull();
    assertThat(field2.name).isEqualTo("f_144759");
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
