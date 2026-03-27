package com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.common.serialization.types.IntegerSerializer;
import com.jetbrains.youtrackdb.internal.core.db.record.EntityEmbeddedListImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.EntityEmbeddedMapImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.EntityEmbeddedSetImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.exception.SerializationException;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.record.impl.EmbeddedEntityImpl;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

/**
 * Round-trip tests for RecordSerializerBinaryV2: serialize an entity, deserialize it, and verify
 * all properties are preserved. V2 uses hash-accelerated linear scan: each property entry is
 * prefixed with a 4-byte MurmurHash3 hash for fast rejection during partial deserialization.
 * Tests cover various property counts, all property types, embedded entities, and edge cases.
 */
public class RecordSerializerBinaryV2RoundTripTest extends DbTestBase {

  private final RecordSerializerBinaryV2 v2 = new RecordSerializerBinaryV2();

  // --- Empty entity ---

  @Test
  public void roundTrip_emptyEntity() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    var deserialized = serializeAndDeserialize(entity);
    assertThat((Iterable<String>) deserialized.getPropertyNames()).isEmpty();
  }

  // --- Small entities (1-2 properties) ---

  @Test
  public void roundTrip_singleStringProperty() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setString("name", "Alice");
    var deserialized = serializeAndDeserialize(entity);
    assertThat((String) deserialized.getProperty("name")).isEqualTo("Alice");
    assertThat((Iterable<String>) deserialized.getPropertyNames()).containsExactly("name");
  }

  @Test
  public void roundTrip_twoProperties() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setString("first", "Hello");
    entity.setInt("second", 42);
    var deserialized = serializeAndDeserialize(entity);
    assertThat((String) deserialized.getProperty("first")).isEqualTo("Hello");
    assertThat((int) deserialized.getProperty("second")).isEqualTo(42);
  }

  @Test
  public void roundTrip_singleNullProperty() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setProperty("value", null);
    var deserialized = serializeAndDeserialize(entity);
    assertThat(deserialized.hasProperty("value")).isTrue();
    assertThat((Object) deserialized.getProperty("value")).isNull();
  }

  // --- Moderate entities (3-12 properties) ---

  @Test
  public void roundTrip_threeProperties() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setString("name", "Bob");
    entity.setInt("age", 30);
    entity.setBoolean("active", true);
    var deserialized = serializeAndDeserialize(entity);
    assertThat((String) deserialized.getProperty("name")).isEqualTo("Bob");
    assertThat((int) deserialized.getProperty("age")).isEqualTo(30);
    assertThat((boolean) deserialized.getProperty("active")).isTrue();
  }

  @Test
  public void roundTrip_fiveProperties() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setString("name", "Charlie");
    entity.setInt("age", 25);
    entity.setDouble("score", 99.5);
    entity.setBoolean("active", false);
    entity.setString("email", "charlie@example.com");
    var deserialized = serializeAndDeserialize(entity);
    assertThat((String) deserialized.getProperty("name")).isEqualTo("Charlie");
    assertThat((int) deserialized.getProperty("age")).isEqualTo(25);
    assertThat((double) deserialized.getProperty("score")).isEqualTo(99.5);
    assertThat((boolean) deserialized.getProperty("active")).isFalse();
    assertThat((String) deserialized.getProperty("email")).isEqualTo("charlie@example.com");
  }

  // --- All property types ---

  @Test
  public void roundTrip_integerProperty() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setInt("a", 1);
    entity.setInt("b", 2);
    entity.setInt("value", Integer.MAX_VALUE);
    var deserialized = serializeAndDeserialize(entity);
    assertThat((int) deserialized.getProperty("value")).isEqualTo(Integer.MAX_VALUE);
  }

  @Test
  public void roundTrip_longProperty() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setLong("a", 1L);
    entity.setLong("b", 2L);
    entity.setLong("value", Long.MAX_VALUE);
    var deserialized = serializeAndDeserialize(entity);
    assertThat((long) deserialized.getProperty("value")).isEqualTo(Long.MAX_VALUE);
  }

  @Test
  public void roundTrip_shortProperty() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setShort("a", (short) 1);
    entity.setShort("b", (short) 2);
    entity.setShort("value", Short.MAX_VALUE);
    var deserialized = serializeAndDeserialize(entity);
    assertThat((short) deserialized.getProperty("value")).isEqualTo(Short.MAX_VALUE);
  }

  @Test
  public void roundTrip_floatProperty() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setFloat("a", 1.0f);
    entity.setFloat("b", 2.0f);
    entity.setFloat("value", 3.14f);
    var deserialized = serializeAndDeserialize(entity);
    assertThat((float) deserialized.getProperty("value")).isEqualTo(3.14f);
  }

  @Test
  public void roundTrip_doubleProperty() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setDouble("a", 1.0);
    entity.setDouble("b", 2.0);
    entity.setDouble("value", Math.PI);
    var deserialized = serializeAndDeserialize(entity);
    assertThat((double) deserialized.getProperty("value")).isEqualTo(Math.PI);
  }

  @Test
  public void roundTrip_byteProperty() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setByte("a", (byte) 1);
    entity.setByte("b", (byte) 2);
    entity.setByte("value", (byte) 0x7F);
    var deserialized = serializeAndDeserialize(entity);
    assertThat((byte) deserialized.getProperty("value")).isEqualTo((byte) 0x7F);
  }

  @Test
  public void roundTrip_booleanProperty() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setBoolean("a", true);
    entity.setBoolean("b", false);
    entity.setBoolean("value", true);
    var deserialized = serializeAndDeserialize(entity);
    assertThat((boolean) deserialized.getProperty("value")).isTrue();
  }

  @Test
  public void roundTrip_datetimeProperty() {
    session.begin();
    var now = new Date();
    var entity = (EntityImpl) session.newEntity();
    entity.setDateTime("a", now);
    entity.setDateTime("b", now);
    entity.setDateTime("value", now);
    var deserialized = serializeAndDeserialize(entity);
    assertThat((Date) deserialized.getProperty("value")).isEqualTo(now);
  }

  @Test
  public void roundTrip_dateProperty() {
    session.begin();
    var today = new Date(1711238400000L); // 2024-03-24 midnight UTC
    var entity = (EntityImpl) session.newEntity();
    entity.setDate("a", today);
    entity.setDate("b", today);
    entity.setDate("value", today);
    var deserialized = serializeAndDeserialize(entity);
    // DATE precision is day-level; midnight UTC should round-trip as the same day
    var result = (Date) deserialized.getProperty("value");
    assertThat(result).isNotNull();
    // Compare day-level: both dates should represent the same calendar day
    var expectedCal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
    expectedCal.setTime(today);
    var actualCal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
    actualCal.setTime(result);
    assertThat(actualCal.get(java.util.Calendar.YEAR))
        .isEqualTo(expectedCal.get(java.util.Calendar.YEAR));
    assertThat(actualCal.get(java.util.Calendar.DAY_OF_YEAR))
        .isEqualTo(expectedCal.get(java.util.Calendar.DAY_OF_YEAR));
  }

  @Test
  public void roundTrip_binaryProperty() {
    session.begin();
    byte[] data = {1, 2, 3, 4, 5};
    var entity = (EntityImpl) session.newEntity();
    entity.setProperty("a", "x");
    entity.setProperty("b", "y");
    entity.setProperty("value", data);
    var deserialized = serializeAndDeserialize(entity);
    assertThat((byte[]) deserialized.getProperty("value")).isEqualTo(data);
  }

  @Test
  public void roundTrip_decimalProperty() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setProperty("a", "x");
    entity.setProperty("b", "y");
    entity.setProperty("value", new BigDecimal("12345.67890"));
    var deserialized = serializeAndDeserialize(entity);
    assertThat((BigDecimal) deserialized.getProperty("value"))
        .isEqualByComparingTo(new BigDecimal("12345.67890"));
  }

  @Test
  public void roundTrip_stringProperty_unicode() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setString("a", "x");
    entity.setString("b", "y");
    entity.setString("value", "Hello \u4e16\u754c \u0410\u0411\u0412");
    var deserialized = serializeAndDeserialize(entity);
    assertThat((String) deserialized.getProperty("value"))
        .isEqualTo("Hello \u4e16\u754c \u0410\u0411\u0412");
  }

  // --- Embedded entities ---

  @Test
  public void roundTrip_embeddedEntity() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setString("a", "x");
    entity.setString("b", "y");

    var embedded = new EmbeddedEntityImpl(session);
    embedded.setString("innerName", "nested");
    embedded.setInt("innerAge", 10);
    entity.setProperty("embedded", embedded, PropertyType.EMBEDDED);

    var deserialized = serializeAndDeserialize(entity);
    var innerEntity = (EntityImpl) deserialized.getProperty("embedded");
    assertThat(innerEntity).isNotNull();
    assertThat((String) innerEntity.getProperty("innerName")).isEqualTo("nested");
    assertThat((int) innerEntity.getProperty("innerAge")).isEqualTo(10);
  }

  @Test
  public void roundTrip_deeplyNestedEmbedded() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setString("a", "x");
    entity.setString("b", "y");

    var level1 = new EmbeddedEntityImpl(session);
    level1.setString("level", "1");
    var level2 = new EmbeddedEntityImpl(session);
    level2.setString("level", "2");
    level1.setProperty("nested", level2, PropertyType.EMBEDDED);
    entity.setProperty("child", level1, PropertyType.EMBEDDED);

    var deserialized = serializeAndDeserialize(entity);
    var child = (EntityImpl) deserialized.getProperty("child");
    assertThat((String) child.getProperty("level")).isEqualTo("1");
    var grandchild = (EntityImpl) child.getProperty("nested");
    assertThat((String) grandchild.getProperty("level")).isEqualTo("2");
  }

  // --- Collections ---

  @Test
  public void roundTrip_embeddedList() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setString("a", "x");
    entity.setString("b", "y");

    var list = new EntityEmbeddedListImpl<>(entity);
    list.addInternal("item1");
    list.addInternal("item2");
    list.addInternal("item3");
    entity.setProperty("values", list, PropertyType.EMBEDDEDLIST);

    var deserialized = serializeAndDeserialize(entity);
    @SuppressWarnings("unchecked")
    var result = (List<String>) deserialized.getProperty("values");
    assertThat(result).containsExactly("item1", "item2", "item3");
  }

  @Test
  public void roundTrip_embeddedSet() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setString("a", "x");
    entity.setString("b", "y");

    var set = new EntityEmbeddedSetImpl<>(entity);
    set.addInternal("alpha");
    set.addInternal("beta");
    entity.setProperty("tags", set, PropertyType.EMBEDDEDSET);

    var deserialized = serializeAndDeserialize(entity);
    @SuppressWarnings("unchecked")
    var result = (java.util.Set<String>) deserialized.getProperty("tags");
    assertThat(result).containsExactlyInAnyOrder("alpha", "beta");
  }

  @Test
  public void roundTrip_embeddedMap() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setString("a", "x");
    entity.setString("b", "y");

    var map = new EntityEmbeddedMapImpl<>(entity);
    map.putInternal("key1", "value1");
    map.putInternal("key2", 42);
    entity.setProperty("metadata", map, PropertyType.EMBEDDEDMAP);

    var deserialized = serializeAndDeserialize(entity);
    var result = (java.util.Map<?, ?>) deserialized.getProperty("metadata");
    assertThat(result.get("key1")).isEqualTo("value1");
    assertThat(result.get("key2")).isEqualTo(42);
  }

  // --- Many properties (stress) ---

  @Test
  public void roundTrip_twentyProperties() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    for (int i = 0; i < 20; i++) {
      entity.setString("field_" + i, "value_" + i);
    }
    var deserialized = serializeAndDeserialize(entity);
    for (int i = 0; i < 20; i++) {
      assertThat((String) deserialized.getProperty("field_" + i)).isEqualTo("value_" + i);
    }
    assertThat((Iterable<String>) deserialized.getPropertyNames()).hasSize(20);
  }

  @Test
  public void roundTrip_fiftyMixedProperties() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    for (int i = 0; i < 50; i++) {
      switch (i % 5) {
        case 0 -> entity.setString("prop_" + i, "str_" + i);
        case 1 -> entity.setInt("prop_" + i, i);
        case 2 -> entity.setDouble("prop_" + i, i * 1.5);
        case 3 -> entity.setBoolean("prop_" + i, i % 2 == 0);
        case 4 -> entity.setLong("prop_" + i, (long) i * 1000);
      }
    }
    var deserialized = serializeAndDeserialize(entity);
    assertThat((Iterable<String>) deserialized.getPropertyNames()).hasSize(50);
    for (int i = 0; i < 50; i++) {
      switch (i % 5) {
        case 0 -> assertThat((String) deserialized.getProperty("prop_" + i))
            .as("prop_%d", i).isEqualTo("str_" + i);
        case 1 -> assertThat((int) deserialized.getProperty("prop_" + i))
            .as("prop_%d", i).isEqualTo(i);
        case 2 -> assertThat((double) deserialized.getProperty("prop_" + i))
            .as("prop_%d", i).isEqualTo(i * 1.5);
        case 3 -> assertThat((boolean) deserialized.getProperty("prop_" + i))
            .as("prop_%d", i).isEqualTo(i % 2 == 0);
        case 4 -> assertThat((long) deserialized.getProperty("prop_" + i))
            .as("prop_%d", i).isEqualTo((long) i * 1000);
      }
    }
  }

  // --- Null values mixed with non-null ---

  @Test
  public void roundTrip_mixedNullAndNonNull() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setString("name", "Test");
    entity.setProperty("empty", null);
    entity.setInt("count", 5);
    var deserialized = serializeAndDeserialize(entity);
    assertThat((String) deserialized.getProperty("name")).isEqualTo("Test");
    assertThat((Object) deserialized.getProperty("empty")).isNull();
    assertThat((int) deserialized.getProperty("count")).isEqualTo(5);
  }

  // --- Verify V2 binary format structure ---

  @Test
  public void serializedBytes_propertyCountIsFirst() {
    // For a non-embedded entity, the first data after version byte is property count
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setString("a", "x");
    entity.setString("b", "y");
    entity.setString("c", "z");

    var bytes = new BytesContainer();
    v2.serialize(session, entity, bytes);
    var result = new BytesContainer(bytes.bytes);

    // Read property count
    int count = VarIntSerializer.readAsInteger(result);
    assertThat(count).isEqualTo(3);
  }

  @Test
  public void serializedBytes_containsHashTableAndOffsetTable() {
    // Verify the header-separated format: [count][hash table][offset table][data area]
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setString("alpha", "val_a");
    entity.setString("beta", "val_b");

    var bytes = new BytesContainer();
    v2.serialize(session, entity, bytes);
    var result = new BytesContainer(bytes.bytes);

    int count = VarIntSerializer.readAsInteger(result);
    assertThat(count).isEqualTo(2);

    // Read hash table (count × 4 bytes)
    int hashTableStart = result.offset;
    int[] hashes = new int[count];
    for (int i = 0; i < count; i++) {
      hashes[i] = IntegerSerializer.deserializeLiteral(result.bytes, result.offset);
      result.skip(4);
    }

    // Read offset table (count × 4 bytes)
    int offsetTableStart = result.offset;
    int[] offsets = new int[count];
    for (int i = 0; i < count; i++) {
      offsets[i] = IntegerSerializer.deserializeLiteral(result.bytes, result.offset);
      result.skip(4);
    }
    int dataAreaStart = result.offset;

    // Verify each entry in the data area matches its hash
    for (int i = 0; i < count; i++) {
      // Jump to entry via offset
      result.offset = dataAreaStart + offsets[i];

      // Read name
      int nameLen = VarIntSerializer.readAsInteger(result);
      assertThat(nameLen).isGreaterThan(0);
      String name = new String(result.bytes, result.offset, nameLen, StandardCharsets.UTF_8);
      result.skip(nameLen);

      // Verify hash matches the property name
      byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
      int expectedHash = com.jetbrains.youtrackdb.internal.common.hash.MurmurHash3
          .hash32WithSeed(nameBytes, 0, nameBytes.length, 0);
      assertThat(hashes[i]).as("Hash for '%s'", name).isEqualTo(expectedHash);

      // Verify offset points to the right position
      assertThat(offsets[i]).as("Offset for entry %d", i).isGreaterThanOrEqualTo(0);

      // Skip type byte + value
      result.skip(1);
      int valueLen = VarIntSerializer.readAsInteger(result);
      result.skip(valueLen);
    }
  }

  // --- Error paths ---

  @Test
  public void deserialize_negativePropertyCount_throwsSerializationException() {
    session.begin();
    // Craft a byte array with a negative property count varint
    var bytes = new BytesContainer();
    VarIntSerializer.write(bytes, -5); // negative count
    var target = (EntityImpl) session.newEntity();
    assertThatThrownBy(
        () -> v2.deserialize(session, target, new BytesContainer(bytes.bytes)))
        .isInstanceOf(SerializationException.class)
        .hasMessageContaining("negative property count");
  }

  /**
   * Verifies that a property count exceeding MAX_PROPERTY_COUNT (2048) is rejected with
   * a clear SerializationException during deserialization.
   */
  @Test
  public void deserialize_excessivePropertyCount_throwsSerializationException() {
    session.begin();
    var bytes = new BytesContainer();
    VarIntSerializer.write(bytes, RecordSerializerBinaryV2.MAX_PROPERTY_COUNT + 1);
    var target = (EntityImpl) session.newEntity();
    assertThatThrownBy(
        () -> v2.deserialize(session, target, new BytesContainer(bytes.bytes)))
        .isInstanceOf(SerializationException.class)
        .hasMessageContaining("exceeds maximum");
  }

  /**
   * Verifies that a corrupted record with a negative value length varint is rejected
   * during full deserialization, preventing backwards buffer offset movement.
   */
  @Test
  public void deserialize_negativeValueLength_throwsSerializationException() {
    session.begin();
    var bytes = new BytesContainer();
    // Property count = 1
    VarIntSerializer.write(bytes, 1);
    // Hash table: 1 × 4 bytes (arbitrary hash)
    int hashStart = bytes.alloc(IntegerSerializer.INT_SIZE);
    IntegerSerializer.serializeLiteral(0x12345678, bytes.bytes, hashStart);
    // Offset table: 1 × 4 bytes (offset 0 — entry starts at data area start)
    int offsetStart = bytes.alloc(IntegerSerializer.INT_SIZE);
    IntegerSerializer.serializeLiteral(0, bytes.bytes, offsetStart);
    // Data area: schema-less name "test" + type + negative value length
    byte[] nameBytes = "test".getBytes(StandardCharsets.UTF_8);
    VarIntSerializer.write(bytes, nameBytes.length);
    int nameStart = bytes.alloc(nameBytes.length);
    System.arraycopy(nameBytes, 0, bytes.bytes, nameStart, nameBytes.length);
    // Type byte (STRING = 7)
    bytes.bytes[bytes.alloc(1)] = 7;
    // Negative value length (corrupted varint)
    VarIntSerializer.write(bytes, -3);
    var target = (EntityImpl) session.newEntity();
    assertThatThrownBy(
        () -> v2.deserialize(session, target, new BytesContainer(bytes.bytes)))
        .isInstanceOf(SerializationException.class)
        .hasMessageContaining("negative value length");
  }

  // --- Moderate scale ---

  @Test
  public void roundTrip_fortyProperties() {
    // 40 properties: moderate scale
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    for (int i = 0; i < 40; i++) {
      entity.setString("field_" + i, "value_" + i);
    }
    var deserialized = serializeAndDeserialize(entity);
    assertThat((Iterable<String>) deserialized.getPropertyNames()).hasSize(40);
    for (int i = 0; i < 40; i++) {
      assertThat((String) deserialized.getProperty("field_" + i))
          .as("field_%d", i).isEqualTo("value_" + i);
    }
  }

  @Test
  public void roundTrip_fortyOneProperties() {
    // 41 properties: just above 40
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    for (int i = 0; i < 41; i++) {
      entity.setString("field_" + i, "value_" + i);
    }
    var deserialized = serializeAndDeserialize(entity);
    assertThat((Iterable<String>) deserialized.getPropertyNames()).hasSize(41);
    for (int i = 0; i < 41; i++) {
      assertThat((String) deserialized.getProperty("field_" + i))
          .as("field_%d", i).isEqualTo("value_" + i);
    }
  }

  // --- Schema-aware round-trip ---

  @Test
  public void roundTrip_schemaAwareProperties() {
    // Schema-aware properties use global property ID encoding (negative varint)
    // instead of inline name strings. Verify V2 correctly writes and reads them.
    var clazz = session.createClass("SchemaAwareV2Test");
    clazz.createProperty("name", PropertyType.STRING);
    clazz.createProperty("age", PropertyType.INTEGER);
    clazz.createProperty("score", PropertyType.DOUBLE);
    clazz.createProperty("active", PropertyType.BOOLEAN);
    clazz.createProperty("created", PropertyType.DATETIME);
    clazz.createProperty("ratio", PropertyType.FLOAT);

    session.begin();
    var entity = (EntityImpl) session.newEntity("SchemaAwareV2Test");
    var now = new Date();
    entity.setProperty("name", "SchemaTest");
    entity.setProperty("age", 42);
    entity.setProperty("score", 98.6);
    entity.setProperty("active", true);
    entity.setProperty("created", now);
    entity.setProperty("ratio", 3.14f);

    var deserialized = serializeAndDeserialize(entity);
    assertThat((String) deserialized.getProperty("name")).isEqualTo("SchemaTest");
    assertThat((int) deserialized.getProperty("age")).isEqualTo(42);
    assertThat((double) deserialized.getProperty("score")).isEqualTo(98.6);
    assertThat((boolean) deserialized.getProperty("active")).isTrue();
    assertThat((Date) deserialized.getProperty("created")).isEqualTo(now);
    assertThat((float) deserialized.getProperty("ratio")).isEqualTo(3.14f);
    assertThat((Iterable<String>) deserialized.getPropertyNames()).hasSize(6);
  }

  @Test
  public void roundTrip_schemaAwareWithExtraDynamicProperty() {
    // Mixed: some properties are schema-defined (global property ID encoding),
    // one is dynamic/schema-less (inline name encoding). Both must round-trip.
    var clazz = session.createClass("MixedSchemaV2Test");
    clazz.createProperty("schemaField", PropertyType.STRING);
    clazz.createProperty("schemaInt", PropertyType.INTEGER);

    session.begin();
    var entity = (EntityImpl) session.newEntity("MixedSchemaV2Test");
    entity.setProperty("schemaField", "defined");
    entity.setProperty("schemaInt", 99);
    entity.setProperty("dynamicExtra", "dynamic_value");

    var deserialized = serializeAndDeserialize(entity);
    assertThat((String) deserialized.getProperty("schemaField")).isEqualTo("defined");
    assertThat((int) deserialized.getProperty("schemaInt")).isEqualTo(99);
    assertThat((String) deserialized.getProperty("dynamicExtra")).isEqualTo("dynamic_value");
    assertThat((Iterable<String>) deserialized.getPropertyNames()).hasSize(3);
  }

  @Test
  public void roundTrip_schemaAwareThirteenProperties() {
    // 13 schema-defined properties with global property ID encoding.
    // Schema-aware names encode the property ID as a negative varint, changing the byte
    // length of the name encoding. Verify hash-accelerated scan works with this encoding.
    var clazz = session.createClass("SchemaHashTableV2Test");
    for (int i = 0; i < 13; i++) {
      clazz.createProperty("field_" + i, PropertyType.STRING);
    }
    session.begin();
    var entity = (EntityImpl) session.newEntity("SchemaHashTableV2Test");
    for (int i = 0; i < 13; i++) {
      entity.setProperty("field_" + i, "value_" + i);
    }
    var deserialized = serializeAndDeserialize(entity);
    for (int i = 0; i < 13; i++) {
      assertThat((String) deserialized.getProperty("field_" + i))
          .as("field_%d", i).isEqualTo("value_" + i);
    }
    assertThat((Iterable<String>) deserialized.getPropertyNames()).hasSize(13);
  }

  // --- Stress tests ---

  @Test
  public void roundTrip_oneHundredMixedProperties() {
    // Stress test with 100 properties of mixed types to verify serialization and
    // hash-accelerated deserialization at scale.
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    for (int i = 0; i < 100; i++) {
      switch (i % 5) {
        case 0 -> entity.setString("prop_" + i, "str_" + i);
        case 1 -> entity.setInt("prop_" + i, i);
        case 2 -> entity.setDouble("prop_" + i, i * 1.1);
        case 3 -> entity.setBoolean("prop_" + i, i % 4 == 0);
        case 4 -> entity.setLong("prop_" + i, (long) i * 10000);
      }
    }
    var deserialized = serializeAndDeserialize(entity);
    assertThat((Iterable<String>) deserialized.getPropertyNames()).hasSize(100);
    for (int i = 0; i < 100; i++) {
      switch (i % 5) {
        case 0 -> assertThat((String) deserialized.getProperty("prop_" + i))
            .as("prop_%d", i).isEqualTo("str_" + i);
        case 1 -> assertThat((int) deserialized.getProperty("prop_" + i))
            .as("prop_%d", i).isEqualTo(i);
        case 2 -> assertThat((double) deserialized.getProperty("prop_" + i))
            .as("prop_%d", i).isEqualTo(i * 1.1);
        case 3 -> assertThat((boolean) deserialized.getProperty("prop_" + i))
            .as("prop_%d", i).isEqualTo(i % 4 == 0);
        case 4 -> assertThat((long) deserialized.getProperty("prop_" + i))
            .as("prop_%d", i).isEqualTo((long) i * 10000);
      }
    }
  }

  // --- Long property names ---

  @Test
  public void roundTrip_longPropertyNames() {
    // Property names with 200-500 characters stress the MurmurHash3 hash function
    // on long UTF-8 inputs. Verify hash-accelerated scan correctly handles these names.
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    var name200 = "a".repeat(200);
    var name350 = "b".repeat(350);
    var name500 = "c".repeat(500);
    entity.setString(name200, "val200");
    entity.setString(name350, "val350");
    entity.setString(name500, "val500");

    var deserialized = serializeAndDeserialize(entity);
    assertThat((Iterable<String>) deserialized.getPropertyNames()).hasSize(3);
    assertThat((String) deserialized.getProperty(name200)).isEqualTo("val200");
    assertThat((String) deserialized.getProperty(name350)).isEqualTo("val350");
    assertThat((String) deserialized.getProperty(name500)).isEqualTo("val500");
  }

  // --- Link types ---

  @Test
  public void roundTrip_singleLinkProperty() {
    // LINK property stores a RID referencing another entity. V2 delegates LINK
    // value serialization to V1 but still encodes the name + type via V2 format.
    // Uses real persisted entities because getProperty() triggers lazy-load for
    // LINK values — a synthetic RecordId that doesn't exist in the DB returns null.
    session.createClass("LinkTarget");
    session.begin();
    var target = (EntityImpl) session.newEntity("LinkTarget");
    target.setString("label", "target");
    session.commit();
    var targetRid = target.getIdentity();

    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setString("name", "source");
    entity.setString("extra", "padding");
    entity.setProperty("ref", targetRid, PropertyType.LINK);

    var deserialized = serializeAndDeserialize(entity);
    assertThat((String) deserialized.getProperty("name")).isEqualTo("source");
    var refId = (Identifiable) deserialized.getProperty("ref");
    assertThat(refId.getIdentity()).isEqualTo(targetRid);
  }

  @Test
  public void roundTrip_linkList() {
    // LINKLIST: ordered list of RIDs. V2 delegates to V1 for value encoding.
    session.createClass("LinkListTarget");
    session.begin();
    var t1 = (EntityImpl) session.newEntity("LinkListTarget");
    t1.setString("n", "1");
    var t2 = (EntityImpl) session.newEntity("LinkListTarget");
    t2.setString("n", "2");
    var t3 = (EntityImpl) session.newEntity("LinkListTarget");
    t3.setString("n", "3");
    session.commit();
    var rid1 = t1.getIdentity();
    var rid2 = t2.getIdentity();
    var rid3 = t3.getIdentity();

    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setString("a", "pad1");
    entity.setString("b", "pad2");
    var linkList = session.newLinkList();
    linkList.add(rid1);
    linkList.add(rid2);
    linkList.add(rid3);
    entity.setProperty("refs", linkList, PropertyType.LINKLIST);

    var deserialized = serializeAndDeserialize(entity);
    @SuppressWarnings("unchecked")
    var result = (List<Identifiable>) deserialized.getProperty("refs");
    assertThat(result).hasSize(3);
    assertThat(result.get(0).getIdentity()).isEqualTo(rid1);
    assertThat(result.get(1).getIdentity()).isEqualTo(rid2);
    assertThat(result.get(2).getIdentity()).isEqualTo(rid3);
  }

  @Test
  public void roundTrip_linkSet() {
    // LINKSET: unordered set of RIDs. V2 delegates to V1 for value encoding.
    session.createClass("LinkSetTarget");
    session.begin();
    var t1 = (EntityImpl) session.newEntity("LinkSetTarget");
    t1.setString("n", "a");
    var t2 = (EntityImpl) session.newEntity("LinkSetTarget");
    t2.setString("n", "b");
    session.commit();
    var rid1 = t1.getIdentity();
    var rid2 = t2.getIdentity();

    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setString("x", "pad1");
    entity.setString("y", "pad2");
    var linkSet = session.newLinkSet();
    linkSet.add(rid1);
    linkSet.add(rid2);
    entity.setProperty("members", linkSet, PropertyType.LINKSET);

    var deserialized = serializeAndDeserialize(entity);
    @SuppressWarnings("unchecked")
    var result = (Set<Identifiable>) deserialized.getProperty("members");
    assertThat(result).hasSize(2);
    var rids = new HashSet<RID>();
    for (var item : result) {
      rids.add(item.getIdentity());
    }
    assertThat(rids).containsExactlyInAnyOrder(rid1, rid2);
  }

  @Test
  public void roundTrip_linkMap() {
    // LINKMAP: map of string→RID. V2 delegates to V1 for value encoding.
    session.createClass("LinkMapTarget");
    session.begin();
    var t1 = (EntityImpl) session.newEntity("LinkMapTarget");
    t1.setString("n", "target1");
    session.commit();
    var rid1 = t1.getIdentity();

    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setString("p", "pad1");
    entity.setString("q", "pad2");
    var linkMap = session.newLinkMap();
    linkMap.put("primary", rid1);
    entity.setProperty("links", linkMap, PropertyType.LINKMAP);

    var deserialized = serializeAndDeserialize(entity);
    @SuppressWarnings("unchecked")
    var result = (Map<String, Identifiable>) deserialized.getProperty("links");
    assertThat(result).hasSize(1);
    assertThat(result.get("primary").getIdentity()).isEqualTo(rid1);
  }

  @Test
  public void roundTrip_mixedLinksAndRegularProperties() {
    // Combine LINK, LINKLIST, and regular properties in one entity to verify
    // V2 correctly handles the mixed type set.
    session.createClass("MixedLinkTarget");
    session.begin();
    var target = (EntityImpl) session.newEntity("MixedLinkTarget");
    target.setString("label", "linked");
    session.commit();
    var targetRid = target.getIdentity();

    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setString("name", "mixed");
    entity.setInt("count", 7);
    entity.setProperty("singleRef", targetRid, PropertyType.LINK);
    var linkList = session.newLinkList();
    linkList.add(targetRid);
    entity.setProperty("refList", linkList, PropertyType.LINKLIST);
    entity.setDouble("score", 88.5);

    var deserialized = serializeAndDeserialize(entity);
    assertThat((String) deserialized.getProperty("name")).isEqualTo("mixed");
    assertThat((int) deserialized.getProperty("count")).isEqualTo(7);
    assertThat((double) deserialized.getProperty("score")).isEqualTo(88.5);
    var refId = (Identifiable) deserialized.getProperty("singleRef");
    assertThat(refId.getIdentity()).isEqualTo(targetRid);
    @SuppressWarnings("unchecked")
    var refListResult = (List<Identifiable>) deserialized.getProperty("refList");
    assertThat(refListResult).hasSize(1);
    assertThat(refListResult.get(0).getIdentity()).isEqualTo(targetRid);
  }

  // --- Various property count tests ---

  @Test
  public void roundTrip_twelveProperties() {
    // 12 properties round-trip
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    for (int i = 0; i < 12; i++) {
      entity.setString("field_" + i, "value_" + i);
    }
    var deserialized = serializeAndDeserialize(entity);
    for (int i = 0; i < 12; i++) {
      assertThat(deserialized.getString("field_" + i)).isEqualTo("value_" + i);
    }
    session.rollback();
  }

  @Test
  public void roundTrip_thirteenProperties() {
    // 13 properties round-trip
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    for (int i = 0; i < 13; i++) {
      entity.setString("field_" + i, "value_" + i);
    }
    var deserialized = serializeAndDeserialize(entity);
    for (int i = 0; i < 13; i++) {
      assertThat(deserialized.getString("field_" + i)).isEqualTo("value_" + i);
    }
    session.rollback();
  }

  @Test
  public void roundTrip_unicodePropertyNames_thirteenProperties() {
    // 13 schema-less properties with multi-byte UTF-8 names exercise the preEncodedName
    // reuse path in serializePropertyEntry. Byte-length differs from char-length for these
    // names, catching any length mismatch in the optimization.
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setString("\u540d\u524d", "name_val");
    entity.setString("\u30e1\u30fc\u30eb", "mail_val");
    entity.setString("r\u00f4le", "role_val");
    entity.setString("\u00fcber", "uber_val");
    entity.setString("stra\u00dfe", "strasse_val");
    entity.setString("\u0438\u043c\u044f", "imya_val");
    entity.setString("\u4e3b\u952e", "pk_val");
    entity.setString("caf\u00e9", "cafe_val");
    entity.setString("na\u00efve", "naive_val");
    entity.setString("\u03b1\u03b2\u03b3", "greek_val");
    entity.setString("normal_ascii", "ascii_val");
    entity.setString("_\u2603_snowman", "snow_val");
    entity.setString("prop_extra", "extra_val");

    var deserialized = serializeAndDeserialize(entity);
    assertThat((Iterable<String>) deserialized.getPropertyNames())
        .containsExactlyInAnyOrder(
            "\u540d\u524d", "\u30e1\u30fc\u30eb", "r\u00f4le", "\u00fcber",
            "stra\u00dfe", "\u0438\u043c\u044f", "\u4e3b\u952e", "caf\u00e9",
            "na\u00efve", "\u03b1\u03b2\u03b3", "normal_ascii", "_\u2603_snowman",
            "prop_extra");
    assertThat((String) deserialized.getProperty("\u540d\u524d")).isEqualTo("name_val");
    assertThat((String) deserialized.getProperty("\u30e1\u30fc\u30eb")).isEqualTo("mail_val");
    assertThat((String) deserialized.getProperty("r\u00f4le")).isEqualTo("role_val");
    assertThat((String) deserialized.getProperty("\u00fcber")).isEqualTo("uber_val");
    assertThat((String) deserialized.getProperty("stra\u00dfe")).isEqualTo("strasse_val");
    assertThat((String) deserialized.getProperty("\u0438\u043c\u044f")).isEqualTo("imya_val");
    assertThat((String) deserialized.getProperty("\u4e3b\u952e")).isEqualTo("pk_val");
    assertThat((String) deserialized.getProperty("caf\u00e9")).isEqualTo("cafe_val");
    assertThat((String) deserialized.getProperty("na\u00efve")).isEqualTo("naive_val");
    assertThat((String) deserialized.getProperty("\u03b1\u03b2\u03b3")).isEqualTo("greek_val");
    assertThat((String) deserialized.getProperty("normal_ascii")).isEqualTo("ascii_val");
    assertThat((String) deserialized.getProperty("_\u2603_snowman")).isEqualTo("snow_val");
    assertThat((String) deserialized.getProperty("prop_extra")).isEqualTo("extra_val");
    session.rollback();
  }

  @Test
  public void roundTrip_withEmbeddedEntity_tempBufferReuse() {
    // 15 properties where tempBuffer is reused per property during serialization.
    // An embedded entity (large serialized size) followed by small string properties
    // verifies that tempBuffer.reset() correctly clears stale embedded data.
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    for (int i = 0; i < 12; i++) {
      entity.setString("prop_" + i, "val_" + i);
    }
    var embedded = new EmbeddedEntityImpl(session);
    embedded.setString("innerA", "nested_value_1");
    embedded.setString("innerB", "nested_value_2");
    embedded.setInt("innerC", 42);
    entity.setProperty("embeddedProp", embedded, PropertyType.EMBEDDED);
    // Properties after the embedded one reuse tempBuffer post-reset
    entity.setString("afterEmbed1", "short");
    entity.setString("afterEmbed2", "tiny");

    var deserialized = serializeAndDeserialize(entity);
    assertThat((Iterable<String>) deserialized.getPropertyNames()).hasSize(15);
    var inner = (EntityImpl) deserialized.getProperty("embeddedProp");
    assertThat((String) inner.getProperty("innerA")).isEqualTo("nested_value_1");
    assertThat((String) inner.getProperty("innerB")).isEqualTo("nested_value_2");
    assertThat((int) inner.getProperty("innerC")).isEqualTo(42);
    assertThat((String) deserialized.getProperty("afterEmbed1")).isEqualTo("short");
    assertThat((String) deserialized.getProperty("afterEmbed2")).isEqualTo("tiny");
    session.rollback();
  }

  @Test
  public void roundTrip_mixedSchemaAwareAndSchemaLess_fourteenProperties() {
    // 14 properties where some are schema-defined (negative varint name encoding)
    // and some are schema-less (inline UTF-8 via preEncodedName).
    // Verifies that preEncodedName is correctly ignored for schema-aware properties.
    var clazz = session.createClass("MixedHashV2Test");
    for (int i = 0; i < 8; i++) {
      clazz.createProperty("schema_" + i, PropertyType.STRING);
    }

    session.begin();
    var entity = (EntityImpl) session.newEntity("MixedHashV2Test");
    for (int i = 0; i < 8; i++) {
      entity.setProperty("schema_" + i, "sval_" + i);
    }
    for (int i = 0; i < 6; i++) {
      entity.setProperty("dynamic_" + i, "dval_" + i);
    }

    var deserialized = serializeAndDeserialize(entity);
    assertThat((Iterable<String>) deserialized.getPropertyNames()).hasSize(14);
    for (int i = 0; i < 8; i++) {
      assertThat((String) deserialized.getProperty("schema_" + i)).isEqualTo("sval_" + i);
    }
    for (int i = 0; i < 6; i++) {
      assertThat((String) deserialized.getProperty("dynamic_" + i)).isEqualTo("dval_" + i);
    }
    session.rollback();
  }

  @Test
  public void serializeDeterminism_sameEntityProducesIdenticalBytes() {
    // Same entity serialized twice should produce identical bytes (deterministic hash computation)
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    for (int i = 0; i < 20; i++) {
      entity.setString("prop_" + i, "val_" + i);
    }

    var bytes1 = new BytesContainer();
    v2.serialize(session, entity, bytes1);
    var bytes2 = new BytesContainer();
    v2.serialize(session, entity, bytes2);

    assertThat(java.util.Arrays.copyOf(bytes1.bytes, bytes1.offset))
        .isEqualTo(java.util.Arrays.copyOf(bytes2.bytes, bytes2.offset));
    session.rollback();
  }

  // --- Helper ---

  private EntityImpl serializeAndDeserialize(EntityImpl entity) {
    var bytes = new BytesContainer();
    v2.serialize(session, entity, bytes);

    var deserialized = (EntityImpl) session.newEntity();
    var readBytes = new BytesContainer(bytes.bytes);
    readBytes.offset = 0;
    v2.deserialize(session, deserialized, readBytes);

    return deserialized;
  }
}
