package com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.record.EntityEmbeddedListImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.EntityEmbeddedMapImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.EntityEmbeddedSetImpl;
import com.jetbrains.youtrackdb.internal.core.exception.SerializationException;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.record.impl.EmbeddedEntityImpl;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import org.junit.Test;

/**
 * Round-trip tests for RecordSerializerBinaryV2: serialize an entity, deserialize it, and verify
 * all properties are preserved. Tests cover linear mode (0-2 properties), hash table mode (3+
 * properties), all property types, embedded entities, and edge cases.
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

  // --- Linear mode (1-2 properties) ---

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
  public void roundTrip_twoProperties_linearMode() {
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

  // --- Hash table mode (3+ properties) ---

  @Test
  public void roundTrip_threeProperties_hashMode() {
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
  public void roundTrip_fiveProperties_hashMode() {
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
    // Spot-check a few
    assertThat((String) deserialized.getProperty("prop_0")).isEqualTo("str_0");
    assertThat((int) deserialized.getProperty("prop_1")).isEqualTo(1);
    assertThat((double) deserialized.getProperty("prop_2")).isEqualTo(3.0);
    assertThat((boolean) deserialized.getProperty("prop_3")).isFalse();
    assertThat((long) deserialized.getProperty("prop_4")).isEqualTo(4000L);
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
  public void serializedBytes_hashTableMode_containsSeedAndCapacity() {
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

    // Read seed (4 bytes LE)
    int seed = com.jetbrains.youtrackdb.internal.common.serialization.types.IntegerSerializer
        .deserializeLiteral(result.bytes, result.offset);
    result.skip(4);

    // Read log2Capacity (1 byte)
    int log2Cap = result.bytes[result.offset++] & 0xFF;
    assertThat(log2Cap).isGreaterThanOrEqualTo(RecordSerializerBinaryV2.MIN_LOG2_CAPACITY);
    assertThat(log2Cap).isLessThanOrEqualTo(RecordSerializerBinaryV2.MAX_LOG2_CAPACITY);

    // Verify the seed produces no collisions
    int capacity = 1 << log2Cap;
    boolean[] occupied = new boolean[capacity];
    for (String name : new String[] {"a", "b", "c"}) {
      byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
      int hash = com.jetbrains.youtrackdb.internal.common.hash.MurmurHash3
          .hash32WithSeed(nameBytes, 0, nameBytes.length, seed);
      int slot = RecordSerializerBinaryV2.fibonacciIndex(hash, log2Cap);
      assertThat(occupied[slot]).as("Collision for property '%s'", name).isFalse();
      occupied[slot] = true;
    }
  }

  @Test
  public void serializedBytes_linearMode_noHashTable() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setString("x", "hello");

    var bytes = new BytesContainer();
    v2.serialize(session, entity, bytes);
    var result = new BytesContainer(bytes.bytes);

    // Read property count
    int count = VarIntSerializer.readAsInteger(result);
    assertThat(count).isEqualTo(1);

    // Next byte should be the name length (for schema-less), not seed bytes
    int nameLen = VarIntSerializer.readAsInteger(result);
    assertThat(nameLen).isEqualTo(1); // "x" is 1 byte
  }

  // --- Error paths ---

  @Test(expected = SerializationException.class)
  public void serialize_kvRegionExceeding64KB_throwsSerializationException() {
    // KV region >64 KB triggers overflow guard (2-byte offsets cannot address beyond 64 KB).
    // The guard checks entryOffset > MAX_KV_REGION_SIZE (65534) at the START of each entry,
    // so we need enough entries that a later entry's offset exceeds 64 KB.
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    // 4 entries of ~17 KB each = ~68 KB; the 4th entry starts at ~51 KB (under limit),
    // but we need the 5th entry to start beyond 64 KB
    String largeValue = "x".repeat(14_000);
    for (int i = 0; i < 6; i++) {
      entity.setString("field_" + i, largeValue);
    }
    // Total KV ~84 KB; the check triggers when an entry starts at offset >65534
    var bytes = new BytesContainer();
    v2.serialize(session, entity, bytes);
  }

  @Test(expected = SerializationException.class)
  public void deserialize_corruptedLog2Capacity_throwsSerializationException() {
    // Corrupt the log2Capacity byte to an invalid value (>MAX_LOG2_CAPACITY)
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setString("a", "x");
    entity.setString("b", "y");
    entity.setString("c", "z");
    var bytes = new BytesContainer();
    v2.serialize(session, entity, bytes);

    // Find and corrupt the log2Capacity byte: after propertyCount varint + 4-byte seed
    var readBytes = new BytesContainer(bytes.bytes);
    VarIntSerializer.readAsInteger(readBytes); // skip propertyCount
    readBytes.skip(4); // skip seed
    int log2CapPos = readBytes.offset;
    bytes.bytes[log2CapPos] = (byte) 30; // invalid: would mean 1B+ slots

    var target = (EntityImpl) session.newEntity();
    v2.deserialize(session, target, new BytesContainer(bytes.bytes));
  }

  @Test(expected = SerializationException.class)
  public void deserialize_negativePropertyCount_throwsSerializationException() {
    session.begin();
    // Craft a byte array with a negative property count varint
    var bytes = new BytesContainer();
    VarIntSerializer.write(bytes, -5); // negative count
    var target = (EntityImpl) session.newEntity();
    v2.deserialize(session, target, new BytesContainer(bytes.bytes));
  }

  // --- Capacity boundary: 40 vs 41 properties (2x vs 4x capacity switch) ---

  @Test
  public void roundTrip_fortyProperties_2xCapacityBoundary() {
    // 40 properties use 2x capacity (HIGH_CAPACITY_THRESHOLD boundary)
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
  public void roundTrip_fortyOneProperties_4xCapacityBoundary() {
    // 41 properties switch to 4x capacity
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
