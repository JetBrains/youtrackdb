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
 * field name extraction (getFieldNames). Covers both linear mode (<=2 properties) and hash table
 * mode (3+ properties), including edge cases.
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
  public void partial_singleField_hashMode() {
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
  public void partial_multipleFields_hashMode() {
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
  public void partial_allFields_hashMode() {
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
  public void partial_schemaAwareProperty_hashMode() {
    // Schema-aware properties use global property ID encoding. Verify partial
    // deserialization correctly resolves the ID back to the property name and
    // returns the correct value via hash table lookup.
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
  public void field_stringField_hashMode() {
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
  public void field_integerField_hashMode() {
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
  public void field_linkProperty_hashMode() {
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
  public void fieldNames_hashMode() {
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
    assertThat(names).hasSize(20);
    for (int i = 0; i < 20; i++) {
      assertThat(names).contains("prop_" + i);
    }
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
  // Boundary: 3-property threshold (first hash mode case)
  // ========================================================================================

  @Test
  public void partial_threeProperties_hashModeBoundary() {
    // Exactly 3 properties = first hash mode case (LINEAR_MODE_THRESHOLD = 2).
    // An off-by-one in the threshold check would route to the wrong deserialize path.
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setString("a", "val_a");
    entity.setInt("b", 42);
    entity.setDouble("c", 3.14);

    var deserialized = partialDeserialize(entity, "b");
    assertThat((int) deserialized.getProperty("b")).isEqualTo(42);
    assertThat(deserialized.hasProperty("a")).isFalse();
    assertThat(deserialized.hasProperty("c")).isFalse();
  }

  // ========================================================================================
  // deserializeField — embedded=true code path
  // ========================================================================================

  @Test
  public void field_embeddedEntity_hashMode() {
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
