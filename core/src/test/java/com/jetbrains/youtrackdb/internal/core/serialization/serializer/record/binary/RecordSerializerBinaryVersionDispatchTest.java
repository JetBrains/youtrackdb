package com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB.LocalUserCredential;
import com.jetbrains.youtrackdb.api.YouTrackDB.PredefinedLocalRole;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
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

  // --- Database lifecycle: persist → close → reopen → verify ---

  /**
   * In-memory storage lifecycle: create entities with various V2 property types, commit,
   * close the database session, reopen it, and verify all properties read back correctly.
   * This tests V2 through the in-memory storage layer (session close/reopen cycle).
   */
  @Test
  public void dbLifecycle_persistCloseReopenVerify() {
    var dbName = "v2LifecycleTest";
    var basePath = DbTestBase.getBaseDirectoryPathStr(getClass()) + "temp";
    try (var ytdb = (YouTrackDBImpl) YourTracks.instance(basePath)) {
      ytdb.create(dbName, DatabaseType.MEMORY,
          new LocalUserCredential("admin", "adminpwd", PredefinedLocalRole.ADMIN));

      RID rid;
      // Create and persist an entity with various property types
      try (var db = (DatabaseSessionEmbedded) ytdb.open(dbName, "admin", "adminpwd")) {
        db.createClass("V2Entity");

        db.begin();
        var entity = (EntityImpl) db.newEntity("V2Entity");
        entity.setProperty("name", "lifecycle_test");
        entity.setProperty("count", 42);
        entity.setProperty("score", 98.6);
        entity.setProperty("active", true);
        entity.setProperty("created", new Date(1700000000000L));
        entity.setProperty("ratio", 3.14f);
        entity.setProperty("amount", new BigDecimal("999.99"));
        db.commit();
        rid = entity.getIdentity();
      }

      // Reopen and verify all properties
      try (var db = (DatabaseSessionEmbedded) ytdb.open(dbName, "admin", "adminpwd")) {
        db.begin();
        var reloaded = (EntityImpl) db.load(rid);
        assertThat(reloaded).isNotNull();
        assertThat((String) reloaded.getProperty("name")).isEqualTo("lifecycle_test");
        assertThat((int) reloaded.getProperty("count")).isEqualTo(42);
        assertThat((double) reloaded.getProperty("score")).isEqualTo(98.6);
        assertThat((boolean) reloaded.getProperty("active")).isTrue();
        assertThat((Date) reloaded.getProperty("created")).isEqualTo(new Date(1700000000000L));
        assertThat((float) reloaded.getProperty("ratio")).isEqualTo(3.14f);
        assertThat((BigDecimal) reloaded.getProperty("amount"))
            .isEqualByComparingTo(new BigDecimal("999.99"));
        assertThat((Iterable<String>) reloaded.getPropertyNames()).hasSize(7);
        db.rollback();
      }

      ytdb.drop(dbName);
    }
  }

  /**
   * Update lifecycle: persist an entity, reopen the DB, modify properties,
   * re-persist, close, reopen again and verify the updated values.
   */
  @Test
  public void dbLifecycle_updateAndVerify() {
    var dbName = "v2UpdateTest";
    var basePath = DbTestBase.getBaseDirectoryPathStr(getClass()) + "temp";
    try (var ytdb = (YouTrackDBImpl) YourTracks.instance(basePath)) {
      ytdb.create(dbName, DatabaseType.MEMORY,
          new LocalUserCredential("admin", "adminpwd", PredefinedLocalRole.ADMIN));

      RID rid;
      // Create initial entity
      try (var db = (DatabaseSessionEmbedded) ytdb.open(dbName, "admin", "adminpwd")) {
        db.createClass("UpdateEntity");
        db.begin();
        var entity = (EntityImpl) db.newEntity("UpdateEntity");
        entity.setProperty("name", "original");
        entity.setProperty("version", 1);
        db.commit();
        rid = entity.getIdentity();
      }

      // Reopen, update, re-persist
      try (var db = (DatabaseSessionEmbedded) ytdb.open(dbName, "admin", "adminpwd")) {
        db.begin();
        var entity = (EntityImpl) db.load(rid);
        entity.setProperty("name", "updated");
        entity.setProperty("version", 2);
        entity.setProperty("newField", "added_after_update");
        db.commit();
      }

      // Reopen and verify updated values
      try (var db = (DatabaseSessionEmbedded) ytdb.open(dbName, "admin", "adminpwd")) {
        db.begin();
        var reloaded = (EntityImpl) db.load(rid);
        assertThat((String) reloaded.getProperty("name")).isEqualTo("updated");
        assertThat((int) reloaded.getProperty("version")).isEqualTo(2);
        assertThat((String) reloaded.getProperty("newField")).isEqualTo("added_after_update");
        assertThat((Iterable<String>) reloaded.getPropertyNames()).hasSize(3);
        db.rollback();
      }

      ytdb.drop(dbName);
    }
  }

  // --- Disk-storage DB lifecycle: persist → close → reopen → verify ---

  /**
   * Disk-storage lifecycle for tier 1 (linear mode, ≤2 properties): persist an entity with 2
   * properties to actual disk pages via DatabaseType.DISK, close the database, reopen it, and
   * verify all properties survive the full page flush and reload cycle.
   */
  @Test
  public void dbLifecycle_diskStorage_linearTier_persistCloseReopenVerify() {
    var dbName = "v2DiskLinearTierTest";
    var basePath = DbTestBase.getBaseDirectoryPathStr(getClass()) + "diskLinear";
    try (var ytdb = (YouTrackDBImpl) YourTracks.instance(basePath)) {
      ytdb.create(dbName, DatabaseType.DISK,
          new LocalUserCredential("admin", "adminpwd", PredefinedLocalRole.ADMIN));
      try {
        RID rid;
        // Create entity with 2 properties (tier 1 linear mode)
        try (var db = (DatabaseSessionEmbedded) ytdb.open(dbName, "admin", "adminpwd")) {
          db.createClass("LinearTierEntity");
          db.begin();
          var entity = (EntityImpl) db.newEntity("LinearTierEntity");
          entity.setProperty("name", "disk_linear");
          entity.setProperty("count", 7);
          db.commit();
          rid = entity.getIdentity();
        }

        // Reopen and verify properties survive full disk flush
        try (var db = (DatabaseSessionEmbedded) ytdb.open(dbName, "admin", "adminpwd")) {
          db.begin();
          var reloaded = (EntityImpl) db.load(rid);
          assertThat(reloaded).isNotNull();
          assertThat((String) reloaded.getProperty("name")).isEqualTo("disk_linear");
          assertThat((int) reloaded.getProperty("count")).isEqualTo(7);
          assertThat((Iterable<String>) reloaded.getPropertyNames())
              .containsExactlyInAnyOrder("name", "count");
          db.rollback();
        }
      } finally {
        ytdb.drop(dbName);
      }
    }
  }

  /**
   * Disk-storage lifecycle for tier 3 (cuckoo hash mode, >12 properties): persist an entity
   * with 15 properties to disk pages, close the database, reopen it, and verify all properties
   * survive the persist→close→reopen cycle with cuckoo-serialized records on actual disk pages.
   */
  @Test
  public void dbLifecycle_diskStorage_cuckooTier_persistCloseReopenVerify() {
    var dbName = "v2DiskCuckooTierTest";
    var basePath = DbTestBase.getBaseDirectoryPathStr(getClass()) + "diskCuckoo";
    try (var ytdb = (YouTrackDBImpl) YourTracks.instance(basePath)) {
      ytdb.create(dbName, DatabaseType.DISK,
          new LocalUserCredential("admin", "adminpwd", PredefinedLocalRole.ADMIN));
      try {
        RID rid;
        // Create entity with 15 properties using mixed types (tier 3 cuckoo mode).
        // Mixed types exercise variable-width value encoding (varint for strings,
        // fixed 4/8 bytes for int/double) in the cuckoo offset table.
        try (var db = (DatabaseSessionEmbedded) ytdb.open(dbName, "admin", "adminpwd")) {
          db.createClass("CuckooTierEntity");
          db.begin();
          var entity = (EntityImpl) db.newEntity("CuckooTierEntity");
          for (int i = 0; i < 10; i++) {
            entity.setProperty("str_" + i, "value_" + i);
          }
          entity.setProperty("intProp", 42);
          entity.setProperty("doubleProp", 3.14);
          entity.setProperty("boolProp", true);
          entity.setProperty("dateProp", new Date(1700000000000L));
          entity.setProperty("decimalProp", new BigDecimal("999.99"));
          db.commit();
          rid = entity.getIdentity();
        }

        // Reopen and verify all 15 properties survive full disk flush
        try (var db = (DatabaseSessionEmbedded) ytdb.open(dbName, "admin", "adminpwd")) {
          db.begin();
          var reloaded = (EntityImpl) db.load(rid);
          assertThat(reloaded).isNotNull();
          for (int i = 0; i < 10; i++) {
            assertThat((String) reloaded.getProperty("str_" + i))
                .as("str_%d", i)
                .isEqualTo("value_" + i);
          }
          assertThat((int) reloaded.getProperty("intProp")).isEqualTo(42);
          assertThat((double) reloaded.getProperty("doubleProp")).isEqualTo(3.14);
          assertThat((boolean) reloaded.getProperty("boolProp")).isTrue();
          assertThat((Date) reloaded.getProperty("dateProp"))
              .isEqualTo(new Date(1700000000000L));
          assertThat((BigDecimal) reloaded.getProperty("decimalProp"))
              .isEqualByComparingTo(new BigDecimal("999.99"));
          var expectedNames = new String[15];
          for (int i = 0; i < 10; i++) {
            expectedNames[i] = "str_" + i;
          }
          expectedNames[10] = "intProp";
          expectedNames[11] = "doubleProp";
          expectedNames[12] = "boolProp";
          expectedNames[13] = "dateProp";
          expectedNames[14] = "decimalProp";
          assertThat((Iterable<String>) reloaded.getPropertyNames())
              .containsExactlyInAnyOrder(expectedNames);
          db.rollback();
        }
      } finally {
        ytdb.drop(dbName);
      }
    }
  }

  /**
   * Disk-storage partial deserialization for cuckoo tier: persist an entity with 15+ properties
   * to disk, reopen the database, access a single property via getProperty() which triggers
   * partial deserialization through the cuckoo 2-bucket scan on disk-loaded pages.
   */
  @Test
  public void dbLifecycle_diskStorage_cuckooTier_partialDeserialization() {
    var dbName = "v2DiskCuckooPartialTest";
    var basePath = DbTestBase.getBaseDirectoryPathStr(getClass()) + "diskCuckooPartial";
    try (var ytdb = (YouTrackDBImpl) YourTracks.instance(basePath)) {
      ytdb.create(dbName, DatabaseType.DISK,
          new LocalUserCredential("admin", "adminpwd", PredefinedLocalRole.ADMIN));
      try {
        RID rid;
        // Create entity with 15 properties (cuckoo mode)
        try (var db = (DatabaseSessionEmbedded) ytdb.open(dbName, "admin", "adminpwd")) {
          db.createClass("CuckooPartialEntity");
          db.begin();
          var entity = (EntityImpl) db.newEntity("CuckooPartialEntity");
          for (int i = 0; i < 15; i++) {
            entity.setProperty("field_" + i, "data_" + i);
          }
          db.commit();
          rid = entity.getIdentity();
        }

        // Reopen and access a single property — triggers partial deserialization via
        // EntityImpl.checkForProperties(name) → deserializePartial() through the cuckoo
        // 2-bucket scan path on disk-loaded pages.
        try (var db = (DatabaseSessionEmbedded) ytdb.open(dbName, "admin", "adminpwd")) {
          db.begin();
          var reloaded = (EntityImpl) db.load(rid);
          assertThat(reloaded).isNotNull();
          // Access a mid-range property to exercise the cuckoo hash lookup path
          assertThat((String) reloaded.getProperty("field_7")).isEqualTo("data_7");
          // Access first and last properties as well
          assertThat((String) reloaded.getProperty("field_0")).isEqualTo("data_0");
          assertThat((String) reloaded.getProperty("field_14")).isEqualTo("data_14");
          // Verify non-existent property returns null (not a phantom cuckoo collision)
          assertThat((String) reloaded.getProperty("nonexistent_field")).isNull();
          db.rollback();
        }
      } finally {
        ytdb.drop(dbName);
      }
    }
  }

  // --- Binary comparator correctness with V2 ---

  /**
   * Binary comparator correctness: serialize two entities with V2, use
   * deserializeField() to locate a shared field, then verify
   * BinaryComparatorV0.isEqual() and compare() produce correct results.
   */
  @Test
  public void binaryComparator_v2DeserializeFieldWithComparatorV0() {
    session.begin();
    // Two entities with the same field "score" but different values
    var entity1 = (EntityImpl) session.newEntity();
    entity1.setString("name", "Alice");
    entity1.setInt("age", 30);
    entity1.setDouble("score", 85.5);

    var entity2 = (EntityImpl) session.newEntity();
    entity2.setString("name", "Bob");
    entity2.setInt("age", 25);
    entity2.setDouble("score", 92.0);

    // Entity with same score as entity1
    var entity3 = (EntityImpl) session.newEntity();
    entity3.setString("name", "Charlie");
    entity3.setInt("age", 35);
    entity3.setDouble("score", 85.5);

    var v2 = new RecordSerializerBinaryV2();

    // Serialize all three with V2
    var field1 = getV2BinaryField(v2, entity1, "score");
    var field2 = getV2BinaryField(v2, entity2, "score");
    var field3 = getV2BinaryField(v2, entity3, "score");

    assertThat(field1).isNotNull();
    assertThat(field2).isNotNull();
    assertThat(field3).isNotNull();

    var comparator = new BinaryComparatorV0();

    // entity1.score (85.5) != entity2.score (92.0)
    assertThat(comparator.isEqual(session, field1, field2)).isFalse();

    // entity1.score (85.5) == entity3.score (85.5)
    assertThat(comparator.isEqual(session, field1, field3)).isTrue();

    // entity1.score (85.5) < entity2.score (92.0) → negative
    assertThat(comparator.compare(session, field1, field2)).isLessThan(0);

    // entity2.score (92.0) > entity1.score (85.5) → positive
    assertThat(comparator.compare(session, field2, field1)).isGreaterThan(0);

    // entity1.score (85.5) == entity3.score (85.5) → zero
    assertThat(comparator.compare(session, field1, field3)).isEqualTo(0);
  }

  @Test
  public void binaryComparator_v2StringFieldComparison() {
    session.begin();
    var entity1 = (EntityImpl) session.newEntity();
    entity1.setString("key", "apple");
    entity1.setString("pad1", "x");
    entity1.setString("pad2", "y");

    var entity2 = (EntityImpl) session.newEntity();
    entity2.setString("key", "banana");
    entity2.setString("pad1", "x");
    entity2.setString("pad2", "y");

    var entity3 = (EntityImpl) session.newEntity();
    entity3.setString("key", "apple");
    entity3.setString("pad1", "x");
    entity3.setString("pad2", "y");

    var v2 = new RecordSerializerBinaryV2();

    var field1 = getV2BinaryField(v2, entity1, "key");
    var field2 = getV2BinaryField(v2, entity2, "key");
    var field3 = getV2BinaryField(v2, entity3, "key");

    var comparator = new BinaryComparatorV0();

    // "apple" != "banana"
    assertThat(comparator.isEqual(session, field1, field2)).isFalse();

    // "apple" == "apple"
    assertThat(comparator.isEqual(session, field1, field3)).isTrue();

    // "apple" < "banana" → negative
    assertThat(comparator.compare(session, field1, field2)).isLessThan(0);

    // "banana" > "apple" → positive (reverse comparison symmetry)
    assertThat(comparator.compare(session, field2, field1)).isGreaterThan(0);

    // "apple" == "apple" → zero
    assertThat(comparator.compare(session, field1, field3)).isEqualTo(0);
  }

  private BinaryField getV2BinaryField(RecordSerializerBinaryV2 v2, EntityImpl entity,
      String fieldName) {
    var bytes = new BytesContainer();
    v2.serialize(session, entity, bytes);
    var readBytes = new BytesContainer(bytes.bytes);
    return v2.deserializeField(session, readBytes, null, fieldName, false, null, null);
  }
}
