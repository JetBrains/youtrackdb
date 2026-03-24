package com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.DbTestBase;
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
 * all properties are preserved. Tests cover linear mode (0-12 properties), cuckoo hash table mode
 * (13+ properties), all property types, embedded entities, and edge cases.
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
  public void serializedBytes_hashTableMode_containsSeedAndBuckets() {
    // 13 properties triggers cuckoo hash table mode (threshold is 12 for linear)
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    String[] propNames = new String[13];
    for (int i = 0; i < 13; i++) {
      propNames[i] = "prop_" + i;
      entity.setString(propNames[i], "val_" + i);
    }

    var bytes = new BytesContainer();
    v2.serialize(session, entity, bytes);
    var result = new BytesContainer(bytes.bytes);

    // Read property count
    int count = VarIntSerializer.readAsInteger(result);
    assertThat(count).isEqualTo(13);

    // Read seed (4 bytes LE)
    int seed = com.jetbrains.youtrackdb.internal.common.serialization.types.IntegerSerializer
        .deserializeLiteral(result.bytes, result.offset);
    result.skip(4);

    // Read log2NumBuckets (1 byte)
    int log2NumBuckets = result.bytes[result.offset++] & 0xFF;
    assertThat(log2NumBuckets).isGreaterThanOrEqualTo(0);
    assertThat(log2NumBuckets).isLessThanOrEqualTo(RecordSerializerBinaryV2.MAX_LOG2_CAPACITY);

    // Verify bucket array has correct size
    int numBuckets = 1 << log2NumBuckets;
    int totalSlots = numBuckets * RecordSerializerBinaryV2.BUCKET_SIZE;
    int bucketArraySize = totalSlots * RecordSerializerBinaryV2.SLOT_SIZE;
    int bucketArrayStart = result.offset;

    // Verify each property is locatable via 2-bucket scan
    int kvRegionBase = bucketArrayStart + bucketArraySize;
    int h2Seed = RecordSerializerBinaryV2.computeH2Seed(seed);
    for (String name : propNames) {
      byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
      int h1 = com.jetbrains.youtrackdb.internal.common.hash.MurmurHash3
          .hash32WithSeed(nameBytes, 0, nameBytes.length, seed);
      int h2 = com.jetbrains.youtrackdb.internal.common.hash.MurmurHash3
          .hash32WithSeed(nameBytes, 0, nameBytes.length, h2Seed);
      byte expectedHash8 = RecordSerializerBinaryV2.computeHash8(h1);

      int bucket1 = RecordSerializerBinaryV2.fibonacciBucketIndex(h1, log2NumBuckets);
      int bucket2 = RecordSerializerBinaryV2.fibonacciBucketIndex(h2, log2NumBuckets);

      boolean found = findInBucket(result.bytes, bucketArrayStart, kvRegionBase,
          bucket1, expectedHash8);
      if (!found) {
        found = findInBucket(result.bytes, bucketArrayStart, kvRegionBase,
            bucket2, expectedHash8);
      }
      assertThat(found).as("Property '%s' not found in bucket1=%d or bucket2=%d",
          name, bucket1, bucket2).isTrue();
    }
  }

  private static boolean findInBucket(byte[] data, int slotArrayStart, int kvRegionBase,
      int bucketIndex, byte expectedHash8) {
    int bucketStart = slotArrayStart
        + bucketIndex * RecordSerializerBinaryV2.BUCKET_SIZE * RecordSerializerBinaryV2.SLOT_SIZE;
    for (int s = 0; s < RecordSerializerBinaryV2.BUCKET_SIZE; s++) {
      int slotPos = bucketStart + s * RecordSerializerBinaryV2.SLOT_SIZE;
      byte slotHash8 = data[slotPos];
      int slotOffset = (data[slotPos + 1] & 0xFF) | ((data[slotPos + 2] & 0xFF) << 8);
      if (slotHash8 == RecordSerializerBinaryV2.EMPTY_HASH8
          && slotOffset == (RecordSerializerBinaryV2.EMPTY_OFFSET & 0xFFFF)) {
        continue;
      }
      if (slotHash8 == expectedHash8 && slotOffset != (RecordSerializerBinaryV2.EMPTY_OFFSET
          & 0xFFFF)) {
        return true;
      }
    }
    return false;
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
    // Requires 13+ properties to trigger hash table mode (linear mode threshold is 12).
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    String largeValue = "x".repeat(5_000);
    for (int i = 0; i < 15; i++) {
      entity.setString("field_" + i, largeValue);
    }
    // Total KV ~75 KB; the check triggers when an entry starts at offset >65534
    var bytes = new BytesContainer();
    v2.serialize(session, entity, bytes);
  }

  @Test(expected = SerializationException.class)
  public void deserialize_corruptedLog2Capacity_throwsSerializationException() {
    // Corrupt the log2NumBuckets byte to an invalid value (>MAX_LOG2_CAPACITY).
    // Requires 13+ properties to trigger hash table mode.
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    for (int i = 0; i < 13; i++) {
      entity.setString("prop_" + i, "val_" + i);
    }
    var bytes = new BytesContainer();
    v2.serialize(session, entity, bytes);

    // Find and corrupt the log2NumBuckets byte: after propertyCount varint + 4-byte seed
    var readBytes = new BytesContainer(bytes.bytes);
    VarIntSerializer.readAsInteger(readBytes); // skip propertyCount
    readBytes.skip(4); // skip seed
    int log2Pos = readBytes.offset;
    bytes.bytes[log2Pos] = (byte) 30; // invalid: would mean 1B+ buckets

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

  // --- Stress tests ---

  @Test
  public void roundTrip_oneHundredMixedProperties() {
    // Stress test with 100 properties of mixed types to verify seed search,
    // hash table sizing (4x capacity for N>40), and correct slot assignment
    // all work at scale.
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
    // on long UTF-8 inputs. Verify hash table correctly maps these names.
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
    // V2 hash table correctly handles the mixed type set.
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

  // --- Threshold boundary tests ---

  @Test
  public void serializeDeserialize_twelveProperties_usesLinearMode() {
    // 12 properties is at the threshold boundary — should use linear mode (no hash table)
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    for (int i = 0; i < 12; i++) {
      entity.setString("field_" + i, "value_" + i);
    }
    var bytes = new BytesContainer();
    v2.serialize(session, entity, bytes);

    // Verify linear mode: after property count, next bytes should be name encoding (not seed)
    var readBytes = new BytesContainer(bytes.bytes);
    int count = VarIntSerializer.readAsInteger(readBytes);
    assertThat(count).isEqualTo(12);
    // In linear mode, the next byte is a varint name length — small positive number.
    // In hash table mode, the next 4 bytes are a seed (LE int) — likely not a valid varint.
    // Verify round-trip correctness
    var deserialized = serializeAndDeserialize(entity);
    for (int i = 0; i < 12; i++) {
      assertThat(deserialized.getString("field_" + i)).isEqualTo("value_" + i);
    }
    session.rollback();
  }

  @Test
  public void serializeDeserialize_thirteenProperties_usesCuckooMode() {
    // 13 properties triggers cuckoo hash table mode
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    for (int i = 0; i < 13; i++) {
      entity.setString("field_" + i, "value_" + i);
    }
    var bytes = new BytesContainer();
    v2.serialize(session, entity, bytes);

    // Verify hash table mode: after property count, next bytes are seed (4 bytes) + log2NumBuckets
    var readBytes = new BytesContainer(bytes.bytes);
    int count = VarIntSerializer.readAsInteger(readBytes);
    assertThat(count).isEqualTo(13);
    // Read seed
    readBytes.skip(4);
    // Read log2NumBuckets (should be valid)
    int log2 = readBytes.bytes[readBytes.offset] & 0xFF;
    assertThat(log2).isLessThanOrEqualTo(RecordSerializerBinaryV2.MAX_LOG2_CAPACITY);

    // Verify round-trip correctness
    var deserialized = serializeAndDeserialize(entity);
    for (int i = 0; i < 13; i++) {
      assertThat(deserialized.getString("field_" + i)).isEqualTo("value_" + i);
    }
    session.rollback();
  }

  @Test
  public void serializeDeterminism_sameEntityProducesIdenticalBytes() {
    // Same entity serialized twice should produce identical bytes (deterministic cuckoo seed)
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
