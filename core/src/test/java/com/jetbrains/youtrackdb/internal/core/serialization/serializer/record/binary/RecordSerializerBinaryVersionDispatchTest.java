package com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.math.BigDecimal;
import java.util.Date;
import org.junit.Test;

/**
 * Tests for version dispatch in RecordSerializerBinary: V2 registration, backward compatibility
 * with V1 records, mixed-version coexistence, and version byte correctness.
 */
public class RecordSerializerBinaryVersionDispatchTest extends DbTestBase {

  // --- Registration ---

  @Test
  public void registrationSupportsTwoVersions() {
    var rsb = RecordSerializerBinary.INSTANCE;
    assertThat(rsb.getNumberOfSupportedVersions()).isEqualTo(2);
  }

  @Test
  public void currentVersionIsOne() {
    var rsb = RecordSerializerBinary.INSTANCE;
    assertThat(rsb.getCurrentVersion()).isEqualTo(1);
  }

  @Test
  public void serializerAtIndexZeroIsV1() {
    var rsb = RecordSerializerBinary.INSTANCE;
    assertThat(rsb.getSerializer(0)).isInstanceOf(RecordSerializerBinaryV1.class);
  }

  @Test
  public void serializerAtIndexOneIsV2() {
    var rsb = RecordSerializerBinary.INSTANCE;
    assertThat(rsb.getSerializer(1)).isInstanceOf(RecordSerializerBinaryV2.class);
  }

  @Test
  public void currentSerializerIsV2() {
    var rsb = RecordSerializerBinary.INSTANCE;
    assertThat(rsb.getCurrentSerializer()).isInstanceOf(RecordSerializerBinaryV2.class);
  }

  // --- Version byte ---

  @Test
  public void v2SerializationWritesVersionByteOne() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setString("name", "test");
    entity.setInt("age", 25);
    entity.setDouble("score", 99.5);

    var rsb = new RecordSerializerBinary((byte) 1);
    byte[] bytes = rsb.toStream(session, entity);

    // First byte is the version byte
    assertThat(bytes[0]).isEqualTo((byte) 1);
  }

  @Test
  public void v1SerializationWritesVersionByteZero() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setString("name", "test");
    entity.setInt("age", 25);
    entity.setDouble("score", 99.5);

    var rsb = new RecordSerializerBinary((byte) 0);
    byte[] bytes = rsb.toStream(session, entity);

    // First byte is the version byte
    assertThat(bytes[0]).isEqualTo((byte) 0);
  }

  // --- Backward compatibility: V1 records remain readable after V2 registration ---

  @Test
  public void v1RecordDeserializesCorrectlyViaDispatcher() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setString("name", "Alice");
    entity.setInt("age", 30);
    entity.setDouble("score", 95.5);

    // Serialize as V1 (version byte 0)
    var v1Serializer = new RecordSerializerBinary((byte) 0);
    byte[] v1Bytes = v1Serializer.toStream(session, entity);
    assertThat(v1Bytes[0]).isEqualTo((byte) 0);

    // Deserialize via the default dispatcher (which now has V2 registered)
    var deserialized = (EntityImpl) session.newEntity();
    RecordSerializerBinary.INSTANCE.fromStream(session, v1Bytes, deserialized, null);

    assertThat((String) deserialized.getProperty("name")).isEqualTo("Alice");
    assertThat((int) deserialized.getProperty("age")).isEqualTo(30);
    assertThat((double) deserialized.getProperty("score")).isEqualTo(95.5);
  }

  @Test
  public void v1RecordPartialDeserializeViaDispatcher() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setString("name", "Bob");
    entity.setInt("age", 25);
    entity.setString("city", "NYC");

    // Serialize as V1
    var v1Serializer = new RecordSerializerBinary((byte) 0);
    byte[] v1Bytes = v1Serializer.toStream(session, entity);

    // Partial deserialize via dispatcher — request only "name"
    var deserialized = (EntityImpl) session.newEntity();
    RecordSerializerBinary.INSTANCE.fromStream(
        session, v1Bytes, deserialized, new String[] {"name"});

    assertThat((String) deserialized.getProperty("name")).isEqualTo("Bob");
    // Unrequested fields must not be loaded
    assertThat(deserialized.hasProperty("age")).isFalse();
    assertThat(deserialized.hasProperty("city")).isFalse();
  }

  @Test
  public void v1RecordFieldNamesViaDispatcher() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setString("first", "a");
    entity.setInt("second", 1);
    entity.setDouble("third", 2.0);

    // Serialize as V1
    var v1Serializer = new RecordSerializerBinary((byte) 0);
    byte[] v1Bytes = v1Serializer.toStream(session, entity);

    // Get field names via dispatcher
    String[] fieldNames = RecordSerializerBinary.INSTANCE.getFieldNames(
        session, (EntityImpl) session.newEntity(), v1Bytes);

    assertThat(fieldNames).containsExactlyInAnyOrder("first", "second", "third");
  }

  // --- Mixed version: V1 and V2 records produce identical content ---

  @Test
  public void mixedVersionRoundTrip_simpleProperties() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setString("name", "Charlie");
    entity.setInt("age", 40);
    entity.setDouble("height", 1.85);
    entity.setBoolean("active", true);

    // Serialize as V1
    var v1Serializer = new RecordSerializerBinary((byte) 0);
    byte[] v1Bytes = v1Serializer.toStream(session, entity);

    // Serialize as V2
    var v2Serializer = new RecordSerializerBinary((byte) 1);
    byte[] v2Bytes = v2Serializer.toStream(session, entity);

    // Different version bytes
    assertThat(v1Bytes[0]).isEqualTo((byte) 0);
    assertThat(v2Bytes[0]).isEqualTo((byte) 1);

    // Deserialize both via dispatcher
    var fromV1 = (EntityImpl) session.newEntity();
    RecordSerializerBinary.INSTANCE.fromStream(session, v1Bytes, fromV1, null);

    var fromV2 = (EntityImpl) session.newEntity();
    RecordSerializerBinary.INSTANCE.fromStream(session, v2Bytes, fromV2, null);

    // Both produce identical content
    assertThat((String) fromV1.getProperty("name")).isEqualTo("Charlie");
    assertThat((String) fromV2.getProperty("name")).isEqualTo("Charlie");
    assertThat((int) fromV1.getProperty("age")).isEqualTo(40);
    assertThat((int) fromV2.getProperty("age")).isEqualTo(40);
    assertThat((double) fromV1.getProperty("height")).isEqualTo(1.85);
    assertThat((double) fromV2.getProperty("height")).isEqualTo(1.85);
    assertThat((boolean) fromV1.getProperty("active")).isTrue();
    assertThat((boolean) fromV2.getProperty("active")).isTrue();
  }

  @Test
  public void mixedVersionRoundTrip_allCommonTypes() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setString("str", "hello");
    entity.setInt("int", Integer.MAX_VALUE);
    entity.setLong("long", Long.MIN_VALUE);
    entity.setShort("short", Short.MAX_VALUE);
    entity.setFloat("float", 3.14f);
    entity.setDouble("double", 2.718281828);
    entity.setProperty("decimal", new BigDecimal("123456789.987654321"));
    entity.setProperty("date", new Date(1700000000000L));
    entity.setBoolean("bool", false);
    entity.setProperty("byte", (byte) 0x7F);
    entity.setProperty("binary", new byte[] {1, 2, 3, 4, 5});

    // Serialize with both versions
    var v1Serializer = new RecordSerializerBinary((byte) 0);
    byte[] v1Bytes = v1Serializer.toStream(session, entity);

    var v2Serializer = new RecordSerializerBinary((byte) 1);
    byte[] v2Bytes = v2Serializer.toStream(session, entity);

    // Deserialize both
    var fromV1 = (EntityImpl) session.newEntity();
    RecordSerializerBinary.INSTANCE.fromStream(session, v1Bytes, fromV1, null);

    var fromV2 = (EntityImpl) session.newEntity();
    RecordSerializerBinary.INSTANCE.fromStream(session, v2Bytes, fromV2, null);

    // Verify both produce correct values (assert against known inputs, not just V1==V2)
    for (var from : new EntityImpl[] {fromV1, fromV2}) {
      assertThat((String) from.getProperty("str")).isEqualTo("hello");
      assertThat((int) from.getProperty("int")).isEqualTo(Integer.MAX_VALUE);
      assertThat((long) from.getProperty("long")).isEqualTo(Long.MIN_VALUE);
      assertThat((short) from.getProperty("short")).isEqualTo(Short.MAX_VALUE);
      assertThat((float) from.getProperty("float")).isEqualTo(3.14f);
      assertThat((double) from.getProperty("double")).isEqualTo(2.718281828);
      assertThat((BigDecimal) from.getProperty("decimal"))
          .isEqualTo(new BigDecimal("123456789.987654321"));
      assertThat((Date) from.getProperty("date")).isEqualTo(new Date(1700000000000L));
      assertThat((boolean) from.getProperty("bool")).isFalse();
      assertThat((byte) from.getProperty("byte")).isEqualTo((byte) 0x7F);
      assertThat((byte[]) from.getProperty("binary")).isEqualTo(new byte[] {1, 2, 3, 4, 5});
    }
  }

  @Test
  public void mixedVersionRoundTrip_partialDeserialization() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setString("alpha", "a");
    entity.setString("beta", "b");
    entity.setString("gamma", "c");
    entity.setInt("delta", 4);
    entity.setDouble("epsilon", 5.0);

    // Serialize as V1 and V2
    var v1Serializer = new RecordSerializerBinary((byte) 0);
    byte[] v1Bytes = v1Serializer.toStream(session, entity);

    var v2Serializer = new RecordSerializerBinary((byte) 1);
    byte[] v2Bytes = v2Serializer.toStream(session, entity);

    // Partial deserialize — request "beta" and "delta" from both
    var fromV1 = (EntityImpl) session.newEntity();
    RecordSerializerBinary.INSTANCE.fromStream(
        session, v1Bytes, fromV1, new String[] {"beta", "delta"});

    var fromV2 = (EntityImpl) session.newEntity();
    RecordSerializerBinary.INSTANCE.fromStream(
        session, v2Bytes, fromV2, new String[] {"beta", "delta"});

    assertThat((String) fromV1.getProperty("beta")).isEqualTo("b");
    assertThat((String) fromV2.getProperty("beta")).isEqualTo("b");
    assertThat((int) fromV1.getProperty("delta")).isEqualTo(4);
    assertThat((int) fromV2.getProperty("delta")).isEqualTo(4);

    // Unrequested fields must not be loaded
    for (var from : new EntityImpl[] {fromV1, fromV2}) {
      assertThat(from.hasProperty("alpha")).isFalse();
      assertThat(from.hasProperty("gamma")).isFalse();
      assertThat(from.hasProperty("epsilon")).isFalse();
    }
  }

  @Test
  public void mixedVersionRoundTrip_fieldNames() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setString("x", "1");
    entity.setString("y", "2");
    entity.setString("z", "3");

    // Serialize as V1 and V2
    var v1Serializer = new RecordSerializerBinary((byte) 0);
    byte[] v1Bytes = v1Serializer.toStream(session, entity);

    var v2Serializer = new RecordSerializerBinary((byte) 1);
    byte[] v2Bytes = v2Serializer.toStream(session, entity);

    // Field names from both versions
    var v1Names = RecordSerializerBinary.INSTANCE.getFieldNames(
        session, (EntityImpl) session.newEntity(), v1Bytes);
    var v2Names = RecordSerializerBinary.INSTANCE.getFieldNames(
        session, (EntityImpl) session.newEntity(), v2Bytes);

    assertThat(v1Names).containsExactlyInAnyOrder("x", "y", "z");
    assertThat(v2Names).containsExactlyInAnyOrder("x", "y", "z");
  }

  // --- Default serializer uses V2 ---

  @Test
  public void defaultSerializerUsesV2() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setString("key", "value");
    entity.setInt("num", 42);
    entity.setDouble("pi", 3.14);

    // Default serializer (INSTANCE) should use V2 (version byte = 1)
    byte[] bytes = RecordSerializerBinary.INSTANCE.toStream(session, entity);
    assertThat(bytes[0]).isEqualTo((byte) 1);

    // Should round-trip correctly
    var deserialized = (EntityImpl) session.newEntity();
    RecordSerializerBinary.INSTANCE.fromStream(session, bytes, deserialized, null);
    assertThat((String) deserialized.getProperty("key")).isEqualTo("value");
    assertThat((int) deserialized.getProperty("num")).isEqualTo(42);
    assertThat((double) deserialized.getProperty("pi")).isEqualTo(3.14);
  }

  // --- rawContainsProperty guard regression test ---

  /**
   * Verifies that full deserialization does not overwrite properties that were already loaded
   * via partial deserialization and then modified in memory. This is the regression test for
   * the rawContainsProperty() guard added in V2's deserializeEntry().
   */
  @Test
  public void v2FullDeserializePreservesInMemoryModifiedProperty() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setString("name", "original");
    entity.setInt("age", 30);
    entity.setString("city", "Berlin");

    var rsb = new RecordSerializerBinary((byte) 1);
    byte[] v2Bytes = rsb.toStream(session, entity);

    // Partial deserialize: load only "name"
    var target = (EntityImpl) session.newEntity();
    RecordSerializerBinary.INSTANCE.fromStream(
        session, v2Bytes, target, new String[] {"name"});
    assertThat((String) target.getProperty("name")).isEqualTo("original");

    // Modify "name" in memory
    target.setString("name", "modified");

    // Full deserialize on the same entity (simulates lazy loading of remaining fields)
    RecordSerializerBinary.INSTANCE.fromStream(session, v2Bytes, target, null);

    // "name" must retain the in-memory modification, not revert to "original"
    assertThat((String) target.getProperty("name")).isEqualTo("modified");
    // Other fields loaded normally
    assertThat((int) target.getProperty("age")).isEqualTo(30);
    assertThat((String) target.getProperty("city")).isEqualTo("Berlin");
  }
}
