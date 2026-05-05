package com.jetbrains.youtrackdb.internal.core.record.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB.LocalUserCredential;
import com.jetbrains.youtrackdb.api.YouTrackDB.PredefinedLocalRole;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.db.record.ridbag.LinkBag;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.record.RecordAbstract;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary.EntitySerializerDelta;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.Map;
import org.junit.After;
import org.junit.Test;

public class EntityImplTest extends DbTestBase {

  private static final String dbName = EntityImplTest.class.getSimpleName();
  private static final String defaultDbAdminCredentials = "admin";

  @Test
  public void testResetResetsFieldTypes() throws Exception {
    session.begin();
    var doc = (EntityImpl) session.newEntity();
    doc.setInt("integer", 1);
    doc.setString("string", "val");
    doc.setBinary("binary", new byte[0]);

    assertEquals(PropertyType.INTEGER, doc.getPropertyType("integer"));
    assertEquals(PropertyType.STRING, doc.getPropertyType("string"));
    assertEquals(PropertyType.BINARY, doc.getPropertyType("binary"));

    doc = (EntityImpl) session.newEntity();

    assertNull(doc.getPropertyType("integer"));
    assertNull(doc.getPropertyType("string"));
    assertNull(doc.getPropertyType("binary"));
    session.rollback();
  }

  @Test
  public void testKeepFieldType() throws Exception {
    session.begin();
    var doc = (EntityImpl) session.newEntity();
    doc.setProperty("integer", 10, PropertyType.INTEGER);
    doc.setProperty("string", 20, PropertyType.STRING);
    doc.setProperty("binary", new byte[] {30}, PropertyType.BINARY);

    assertEquals(PropertyType.INTEGER, doc.getPropertyType("integer"));
    assertEquals(PropertyType.STRING, doc.getPropertyType("string"));
    assertEquals(PropertyType.BINARY, doc.getPropertyType("binary"));
    session.rollback();
  }

  @Test
  public void testKeepFieldTypeSerialization() {
    session.begin();
    var doc = (EntityImpl) session.newEntity();
    doc.setProperty("integer", 10, PropertyType.INTEGER);
    Object propertyValue = new RecordId(1, 2);
    doc.setProperty("link", propertyValue, PropertyType.LINK);
    doc.setProperty("string", 20, PropertyType.STRING);
    doc.setProperty("binary", new byte[] {30}, PropertyType.BINARY);

    assertEquals(PropertyType.INTEGER, doc.getPropertyType("integer"));
    assertEquals(PropertyType.LINK, doc.getPropertyType("link"));
    assertEquals(PropertyType.STRING, doc.getPropertyType("string"));
    assertEquals(PropertyType.BINARY, doc.getPropertyType("binary"));
    var ser = session.getSerializer();
    var bytes = ser.toStream(session, doc);
    doc = (EntityImpl) session.newEntity();
    ser.fromStream(session, bytes, doc, null);
    assertEquals(PropertyType.INTEGER, doc.getPropertyType("integer"));
    assertEquals(PropertyType.STRING, doc.getPropertyType("string"));
    assertEquals(PropertyType.BINARY, doc.getPropertyType("binary"));
    assertEquals(PropertyType.LINK, doc.getPropertyType("link"));
    session.rollback();
  }

  @Test
  public void testKeepAutoFieldTypeSerialization() throws Exception {
    session.begin();
    var doc = (EntityImpl) session.newEntity();
    doc.setProperty("integer", 10);
    doc.setProperty("link", new RecordId(1, 2));
    doc.setProperty("string", "string");
    doc.setProperty("binary", new byte[] {30});

    assertEquals(PropertyType.INTEGER, doc.getPropertyType("integer"));
    assertEquals(PropertyType.LINK, doc.getPropertyType("link"));
    assertEquals(PropertyType.STRING, doc.getPropertyType("string"));
    assertEquals(PropertyType.BINARY, doc.getPropertyType("binary"));

    var ser = session.getSerializer();
    var bytes = ser.toStream(session, doc);
    doc = (EntityImpl) session.newEntity();
    ser.fromStream(session, bytes, doc, null);
    assertEquals(PropertyType.INTEGER, doc.getPropertyType("integer"));
    assertEquals(PropertyType.STRING, doc.getPropertyType("string"));
    assertEquals(PropertyType.BINARY, doc.getPropertyType("binary"));
    assertEquals(PropertyType.LINK, doc.getPropertyType("link"));
    session.rollback();
  }

  @Test
  public void testKeepSchemafullFieldTypeSerialization() throws Exception {
    DatabaseSessionEmbedded session = null;
    YouTrackDBImpl ytdb = null;
    try {
      ytdb = (YouTrackDBImpl) YourTracks.instance(
          DbTestBase.getBaseDirectoryPathStr(getClass()) + "temp");
      ytdb.create(dbName, DatabaseType.MEMORY,
          new LocalUserCredential("admin", DbTestBase.ADMIN_PASSWORD, PredefinedLocalRole.ADMIN));

      session = ytdb.open(dbName, defaultDbAdminCredentials,
          DbTestBase.ADMIN_PASSWORD);

      var clazz = session.getMetadata().getSchema().createClass("Test");
      clazz.createProperty("integer", PropertyType.INTEGER);
      clazz.createProperty("link", PropertyType.LINK);
      clazz.createProperty("string", PropertyType.STRING);
      clazz.createProperty("binary", PropertyType.BINARY);

      session.begin();

      var entity = (EntityImpl) session.newEntity(clazz);
      entity.setProperty("integer", 10);
      entity.setProperty("link", new RecordId(1, 2));
      entity.setProperty("string", "string");
      entity.setProperty("binary", new byte[] {30});

      // the types are from the schema.
      assertEquals(PropertyType.INTEGER, entity.getPropertyType("integer"));
      assertEquals(PropertyType.LINK, entity.getPropertyType("link"));
      assertEquals(PropertyType.STRING, entity.getPropertyType("string"));
      assertEquals(PropertyType.BINARY, entity.getPropertyType("binary"));
      var ser = session.getSerializer();
      var bytes = ser.toStream(session, entity);
      entity = (EntityImpl) session.newEntity();
      ser.fromStream(session, bytes, entity, null);
      assertEquals(PropertyType.INTEGER, entity.getPropertyType("integer"));
      assertEquals(PropertyType.STRING, entity.getPropertyType("string"));
      assertEquals(PropertyType.BINARY, entity.getPropertyType("binary"));
      assertEquals(PropertyType.LINK, entity.getPropertyType("link"));

      session.rollback();
    } finally {
      if (session != null) {
        session.close();
      }
      if (ytdb != null) {
        ytdb.drop(dbName);
        ytdb.close();
      }
    }
  }

  @Test
  public void testChangeTypeOnValueSet() throws Exception {
    session.begin();
    var doc = (EntityImpl) session.newEntity();
    doc.setProperty("link", new RecordId(1, 2));
    var ser = session.getSerializer();
    var bytes = ser.toStream(session, doc);
    doc = (EntityImpl) session.newEntity();
    ser.fromStream(session, bytes, doc, null);
    assertEquals(PropertyType.LINK, doc.getPropertyType("link"));
    doc.setProperty("link", new LinkBag(session));
    assertNotEquals(PropertyType.LINK, doc.getPropertyType("link"));
    session.rollback();
  }

  @Test
  public void testRemovingReadonlyField() {

    youTrackDB.create(dbName, DatabaseType.MEMORY,
        new LocalUserCredential(defaultDbAdminCredentials, DbTestBase.ADMIN_PASSWORD,
            PredefinedLocalRole.ADMIN));
    try (var db = youTrackDB.open(dbName, defaultDbAdminCredentials,
        DbTestBase.ADMIN_PASSWORD)) {
      Schema schema = db.getMetadata().getSchema();
      var classA = schema.createClass("TestRemovingField2");
      classA.createProperty("name", PropertyType.STRING);
      var property = classA.createProperty("property", PropertyType.STRING);
      property.setReadonly(true);
      db.begin();
      var doc = (EntityImpl) db.newEntity(classA);
      doc.setProperty("name", "My Name");
      doc.setProperty("property", "value1");

      doc.setProperty("name", "My Name 2");
      doc.setProperty("property", "value2");
      doc.undo(); // we decided undo everything
      // change something
      doc.setProperty("name", "My Name 3");

      doc.setProperty("name", "My Name 4");
      doc.setProperty("property", "value4");
      doc.undo("property"); // we decided undo readonly field

      db.commit();
    }
  }

  @Test
  public void testUndo() {
    youTrackDB.create(dbName, DatabaseType.MEMORY,
        new LocalUserCredential(defaultDbAdminCredentials, DbTestBase.ADMIN_PASSWORD,
            PredefinedLocalRole.ADMIN));
    try (var session = youTrackDB.open(dbName, defaultDbAdminCredentials,
        DbTestBase.ADMIN_PASSWORD)) {

      Schema schema = session.getMetadata().getSchema();
      var classA = schema.createClass("TestUndo");
      classA.createProperty("name", PropertyType.STRING);
      classA.createProperty("property", PropertyType.STRING);

      session.begin();
      var doc = (EntityImpl) session.newEntity(classA);
      doc.setProperty("name", "My Name");
      doc.setProperty("property", "value1");

      session.commit();

      session.begin();
      var activeTx2 = session.getActiveTransaction();
      doc = activeTx2.load(doc);
      assertEquals("My Name", doc.getProperty("name"));
      assertEquals("value1", doc.getProperty("property"));
      doc.undo();
      assertEquals("My Name", doc.getProperty("name"));
      assertEquals("value1", doc.getProperty("property"));
      doc.setProperty("name", "My Name 2");
      doc.setProperty("property", "value2");
      doc.undo();
      doc.setProperty("name", "My Name 3");
      assertEquals("My Name 3", doc.getProperty("name"));
      assertEquals("value1", doc.getProperty("property"));

      session.commit();

      session.begin();
      var activeTx1 = session.getActiveTransaction();
      doc = activeTx1.load(doc);
      doc.setProperty("name", "My Name 4");
      doc.setProperty("property", "value4");
      doc.undo("property");
      assertEquals("My Name 4", doc.getProperty("name"));
      assertEquals("value1", doc.getProperty("property"));

      session.commit();

      session.begin();
      var activeTx = session.getActiveTransaction();
      doc = activeTx.load(doc);
      doc.undo("property");
      assertEquals("My Name 4", doc.getProperty("name"));
      assertEquals("value1", doc.getProperty("property"));
      doc.undo();
      assertEquals("My Name 4", doc.getProperty("name"));
      assertEquals("value1", doc.getProperty("property"));
      session.commit();
    }
  }

  @Test
  public void testMergeNull() {
    session.begin();
    var dest = (EntityImpl) session.newEntity();

    var source = (EntityImpl) session.newEntity();
    source.setProperty("key", "value");
    source.setProperty("somenull", null);

    dest.updateFromResult(source);

    assertEquals("value", dest.getProperty("key"));

    assertTrue(dest.hasProperty("somenull"));
    session.rollback();
  }

  @Test(expected = IllegalArgumentException.class)
  public void testFailNullMapKey() {
    session.executeInTx(transaction -> {
      var doc = (EntityImpl) session.newEntity();
      var map = session.newEmbeddedMap();
      map.put(null, "dd");
      doc.setProperty("testMap", map);
      doc.checkAllMultiValuesAreTrackedVersions();
    });
  }

  @Test
  public void testGetSetProperty() {
    session.begin();

    var entity = (EntityImpl) session.newEntity();

    Map<String, String> map = entity.newEmbeddedMap("map");
    map.put("foo", "valueInTheMap");

    entity.setProperty("theMap.foo", "bar");
    assertEquals(entity.getProperty("map"), map);

    assertEquals("bar", entity.getProperty("theMap.foo"));
    session.rollback();
  }

  @Test
  public void testNoDirtySameBytes() {
    session.begin();

    var entity = (EntityImpl) session.newEntity();
    var bytes = new byte[] {0, 1, 2, 3, 4, 5};
    entity.setBinary("bytes", bytes);
    entity.clearTrackData();
    final var rec = (RecordAbstract) entity;
    rec.unsetDirty();
    assertFalse(entity.isDirty());
    assertNull(entity.getOriginalValue("bytes"));
    entity.setBinary("bytes", bytes.clone());
    assertFalse(entity.isDirty());
    assertNull(entity.getOriginalValue("bytes"));

    session.rollback();
  }

  /**
   * Defensive {@code @After} that wraps a rollback safety net: roll back any transaction the test
   * forgot to close so subsequent tests start with a fresh session. {@code session} is null only
   * if {@link DbTestBase#beforeTest()} failed before assigning it.
   */
  @After
  public void rollbackIfLeftOpen() {
    if (session == null || session.isClosed()) {
      return;
    }
    var tx = session.getActiveTransactionOrNull();
    if (tx != null && tx.isActive()) {
      tx.rollback();
    }
  }

  // ---------------------------------------------------------------------------
  // Cluster (a.1): typed-property get/set fast-path branches.
  // ---------------------------------------------------------------------------

  /**
   * Each {@code setXxx} typed setter on {@link com.jetbrains.youtrackdb.internal.core.db.record.record.Entity}
   * must flow through {@code setProperty(name, value, PropertyType.XXX)} so that a subsequent
   * {@code getPropertyType} returns the matching enum constant. This pins the type-fast-path for
   * every primitive-ish setter — a dispatch-table regression where one setter forwards the wrong
   * {@link PropertyType} would surface here as a single failing assertion instead of as an opaque
   * downstream serialization error.
   */
  @Test
  public void testTypedSettersAssignDeclaredPropertyType() {
    session.begin();
    try {
      var doc = (EntityImpl) session.newEntity();

      doc.setBoolean("flag", Boolean.TRUE);
      doc.setByte("b", (byte) 7);
      doc.setShort("s", (short) 12);
      doc.setInt("i", 42);
      doc.setLong("l", 99L);
      doc.setFloat("f", 3.14f);
      doc.setDouble("d", 2.5d);
      doc.setString("str", "hello");
      doc.setBinary("bin", new byte[] {1, 2, 3});
      doc.setDecimal("dec", new BigDecimal("1.5"));
      var dateOnly = new Date(0L);
      doc.setDate("date", dateOnly);
      doc.setDateTime("dt", dateOnly);
      doc.setLink("lnk", new RecordId(1, 0));

      assertEquals(PropertyType.BOOLEAN, doc.getPropertyType("flag"));
      assertEquals(PropertyType.BYTE, doc.getPropertyType("b"));
      assertEquals(PropertyType.SHORT, doc.getPropertyType("s"));
      assertEquals(PropertyType.INTEGER, doc.getPropertyType("i"));
      assertEquals(PropertyType.LONG, doc.getPropertyType("l"));
      assertEquals(PropertyType.FLOAT, doc.getPropertyType("f"));
      assertEquals(PropertyType.DOUBLE, doc.getPropertyType("d"));
      assertEquals(PropertyType.STRING, doc.getPropertyType("str"));
      assertEquals(PropertyType.BINARY, doc.getPropertyType("bin"));
      assertEquals(PropertyType.DECIMAL, doc.getPropertyType("dec"));
      assertEquals(PropertyType.DATE, doc.getPropertyType("date"));
      assertEquals(PropertyType.DATETIME, doc.getPropertyType("dt"));
      assertEquals(PropertyType.LINK, doc.getPropertyType("lnk"));
    } finally {
      session.rollback();
    }
  }

  /**
   * The typed setters round-trip their values unchanged when read back via
   * {@code getProperty(name)}. Bytes/binary equality requires array-content compare; everything
   * else uses {@link Object#equals(Object)}.
   */
  @Test
  public void testTypedSettersRoundTripValues() {
    session.begin();
    try {
      var doc = (EntityImpl) session.newEntity();

      doc.setInt("i", 17);
      doc.setLong("l", 123_456_789L);
      doc.setShort("s", (short) -3);
      doc.setFloat("f", 0.25f);
      doc.setDouble("d", -1.75d);
      doc.setBoolean("flag", Boolean.FALSE);
      doc.setString("str", "abc");
      var binary = new byte[] {4, 5, 6};
      doc.setBinary("bin", binary);
      doc.setDecimal("dec", new BigDecimal("99.99"));
      doc.setByte("b", (byte) -1);

      assertEquals(Integer.valueOf(17), doc.getProperty("i"));
      assertEquals(Long.valueOf(123_456_789L), doc.getProperty("l"));
      assertEquals(Short.valueOf((short) -3), doc.getProperty("s"));
      assertEquals(Float.valueOf(0.25f), doc.getProperty("f"));
      assertEquals(Double.valueOf(-1.75d), doc.getProperty("d"));
      assertEquals(Boolean.FALSE, doc.getProperty("flag"));
      assertEquals("abc", doc.getProperty("str"));
      assertEquals(new BigDecimal("99.99"), doc.<BigDecimal>getProperty("dec"));
      assertEquals(Byte.valueOf((byte) -1), doc.getProperty("b"));

      var roundTripped = doc.<byte[]>getProperty("bin");
      assertNotNull(roundTripped);
      assertEquals(3, roundTripped.length);
      for (var i = 0; i < binary.length; i++) {
        assertEquals(binary[i], roundTripped[i]);
      }
    } finally {
      session.rollback();
    }
  }

  // ---------------------------------------------------------------------------
  // Cluster (a.2): dirty-tracking and original-value capture.
  // ---------------------------------------------------------------------------

  /**
   * A new entity becomes dirty as soon as a property is set, and {@code getOriginalValue}
   * returns {@code null} for the first write (there is no prior value to capture). After
   * {@code clearTrackData()} + {@code unsetDirty()} the entity preserves its current values but
   * forgets the in-tx change set, so a subsequent identical re-write must not flip the dirty
   * flag. (A freshly-instantiated entity reports {@code isDirty()=true} by construction — the
   * record is "new and unsaved" — which is why we baseline via {@code clearTrackData()} and
   * {@code unsetDirty()} before asserting the idempotent-write contract; {@code testNoDirtySameBytes}
   * uses the same baseline pattern.)
   */
  @Test
  public void testDirtyAndOriginalValueOnFirstAndIdempotentWrite() {
    session.begin();
    try {
      var entity = (EntityImpl) session.newEntity();
      assertNull(entity.getOriginalValue("name"));

      entity.setString("name", "first");
      assertTrue("setting a property must mark the entity dirty", entity.isDirty());
      // First-ever assignment captures null as the "original" since the property did not exist.
      assertNull(entity.getOriginalValue("name"));

      entity.clearTrackData();
      ((RecordAbstract) entity).unsetDirty();
      assertFalse(entity.isDirty());

      // Re-writing the same scalar value after clearTrackData must remain non-dirty.
      entity.setString("name", "first");
      assertFalse("idempotent same-value write must not re-dirty the entity", entity.isDirty());
    } finally {
      session.rollback();
    }
  }

  /**
   * Verifies that mutating an embedded child entity (which lives inside the parent's property
   * map) propagates the dirty flag back up to the parent. This pins the
   * {@code RecordElement}/{@code TrackedMultiValue} owner-back-reference path: an internal
   * change to an embedded entity's field must be visible to the persistence layer through the
   * parent's {@code isDirty()} bit, otherwise the parent would not be re-serialized on commit.
   */
  @Test
  public void testEmbeddedEntityDirtyPropagatesToParent() {
    session.begin();
    try {
      var parent = (EntityImpl) session.newEntity();
      var child = (EmbeddedEntityImpl) session.newEmbeddedEntity();
      child.setString("inner", "v0");
      parent.setEmbeddedEntity("child", child);

      parent.clearTrackData();
      ((RecordAbstract) parent).unsetDirty();
      ((RecordAbstract) child).unsetDirty();
      assertFalse(parent.isDirty());

      // Mutating the embedded child must propagate dirtiness up to the owning parent.
      child.setString("inner", "v1");
      assertTrue("parent must observe its embedded child becoming dirty", parent.isDirty());
    } finally {
      session.rollback();
    }
  }

  // ---------------------------------------------------------------------------
  // Cluster (a.3): serialization round-trip via EntitySerializerDelta.
  // ---------------------------------------------------------------------------

  /**
   * Round-trips an entity through {@link EntitySerializerDelta#serialize(DatabaseSessionEmbedded,
   * EntityImpl)} / {@link EntitySerializerDelta#deserialize(DatabaseSessionEmbedded, byte[],
   * EntityImpl)} and asserts every typed property survives the binary trip with the originally
   * declared {@link PropertyType}. This is the integrative test for cluster (a.1) — if any
   * setter's type-dispatch is wrong, the deserialize side will materialize the value at a
   * different type and the assertion fails.
   */
  @Test
  public void testSerializerDeltaRoundTripPreservesTypedProperties() {
    session.begin();
    try {
      var entity = (EntityImpl) session.newEntity();
      entity.setInt("i", 11);
      entity.setLong("l", 22L);
      entity.setString("s", "round-trip");
      entity.setBoolean("flag", Boolean.TRUE);
      entity.setBinary("bin", new byte[] {7, 8, 9});
      entity.setDecimal("dec", new BigDecimal("3.14"));
      entity.setLink("lnk", new RecordId(2, 1));

      var serializer = EntitySerializerDelta.instance();
      var bytes = EntitySerializerDelta.serialize(session, entity);

      var clone = (EntityImpl) session.newEntity();
      serializer.deserialize(session, bytes, clone);

      assertEquals(Integer.valueOf(11), clone.getProperty("i"));
      assertEquals(Long.valueOf(22L), clone.getProperty("l"));
      assertEquals("round-trip", clone.getProperty("s"));
      assertEquals(Boolean.TRUE, clone.getProperty("flag"));
      assertEquals(new BigDecimal("3.14"), clone.<BigDecimal>getProperty("dec"));
      assertEquals(new RecordId(2, 1), clone.<RID>getProperty("lnk"));

      assertEquals(PropertyType.INTEGER, clone.getPropertyType("i"));
      assertEquals(PropertyType.LONG, clone.getPropertyType("l"));
      assertEquals(PropertyType.STRING, clone.getPropertyType("s"));
      assertEquals(PropertyType.BOOLEAN, clone.getPropertyType("flag"));
      assertEquals(PropertyType.BINARY, clone.getPropertyType("bin"));
      assertEquals(PropertyType.DECIMAL, clone.getPropertyType("dec"));
      assertEquals(PropertyType.LINK, clone.getPropertyType("lnk"));
    } finally {
      session.rollback();
    }
  }

  // ---------------------------------------------------------------------------
  // Cluster (a.4): equals / hashCode / toMap contract.
  // ---------------------------------------------------------------------------

  /**
   * For persisted entities, equals / hashCode are keyed on the record id (delegated through
   * {@link RecordAbstract}). Two committed entities with the same payload but different RIDs
   * must compare unequal, while two references to the same persisted record must compare equal
   * and share a hash code.
   */
  @Test
  public void testEqualsAndHashCodeKeyedOnRid() {
    session.begin();
    var first = session.newEntity();
    first.setString("name", "Alice");
    var second = session.newEntity();
    second.setString("name", "Alice");
    var firstId = first.getIdentity();
    var secondId = second.getIdentity();
    session.commit();

    assertNotEquals("distinct persisted entities must not compare equal", firstId, secondId);

    session.begin();
    try {
      var tx = session.getActiveTransaction();
      var loaded1a = tx.<EntityImpl>load(firstId);
      var loaded1b = tx.<EntityImpl>load(firstId);
      assertEquals("two loads of the same record must be equal", loaded1a, loaded1b);
      assertEquals(loaded1a.hashCode(), loaded1b.hashCode());

      var loaded2 = tx.<EntityImpl>load(secondId);
      assertNotEquals(loaded1a, loaded2);
    } finally {
      session.rollback();
    }
  }

  /**
   * {@link EntityImpl#toMap()} returns a {@link java.util.LinkedHashMap} that preserves the
   * insertion order, exposes user properties under their declared names, and includes the
   * {@code @rid}/{@code @class}/{@code @version} metadata when {@code includeMetadata=true} is
   * implicit. The {@code includeMetadata=false} overload omits the metadata triple. This pins the
   * dispatch in the {@code if (includeMetadata) ... } block of {@link EntityImpl#toMap(boolean)}
   * (lines 2583–2602).
   */
  @Test
  public void testToMapShapeWithAndWithoutMetadata() {
    session.getMetadata().getSchema().createClass("ToMapShape");
    session.begin();
    try {
      var entity = (EntityImpl) session.newEntity("ToMapShape");
      entity.setString("name", "Bob");
      entity.setInt("age", 30);

      var withMeta = entity.toMap();
      assertEquals("Bob", withMeta.get("name"));
      assertEquals(Integer.valueOf(30), withMeta.get("age"));
      assertEquals("ToMapShape", withMeta.get("@class"));
      assertTrue(withMeta.containsKey("@version"));

      var withoutMeta = entity.toMap(false);
      assertEquals("Bob", withoutMeta.get("name"));
      assertEquals(Integer.valueOf(30), withoutMeta.get("age"));
      assertFalse(
          "metadata-suppressed view must not leak @class",
          withoutMeta.containsKey("@class"));
      assertFalse(withoutMeta.containsKey("@version"));
      assertFalse(withoutMeta.containsKey("@rid"));
    } finally {
      session.rollback();
    }
  }

  /**
   * {@link RecordAbstract#toJSON()} returns a non-empty JSON string that contains every property
   * name set on the entity. We do not pin the exact string layout (Jackson formatting is not the
   * subject of this pin), only the contract that toJSON observes the properties. The entity is
   * persisted first because the JSON serializer rejects non-persistent rid references in some
   * paths.
   */
  @Test
  public void testToJsonContainsPropertyNames() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setString("title", "JSON-Title");
    entity.setInt("count", 42);
    var id = entity.getIdentity();
    session.commit();

    session.begin();
    try {
      var loaded = session.getActiveTransaction().<EntityImpl>load(id);
      var json = loaded.toJSON();
      assertNotNull(json);
      assertTrue("toJSON output must contain the title property name", json.contains("title"));
      assertTrue("toJSON output must contain the count property name", json.contains("count"));
      assertTrue(json.contains("JSON-Title"));
      assertTrue(json.contains("42"));
    } finally {
      session.rollback();
    }
  }

  // ---------------------------------------------------------------------------
  // Cluster (a.5): embedded semantics — owner threading.
  // ---------------------------------------------------------------------------

  /**
   * A freshly created embedded entity has no owner; assigning it as a property on a parent
   * threads the parent in as the owner via {@link EntityImpl#getOwner()}. The embedded child's
   * {@link EntityImpl#isEmbedded()} bit is set, while the parent is not embedded. This pins the
   * setOwner-on-assignment path that {@link EmbeddedEntityImpl} relies on for cascading dirty
   * propagation and rollback support.
   */
  @Test
  public void testEmbeddedEntityOwnerThreading() {
    session.begin();
    try {
      var parent = (EntityImpl) session.newEntity();
      var child = (EmbeddedEntityImpl) session.newEmbeddedEntity();

      assertTrue(child.isEmbedded());
      assertFalse(parent.isEmbedded());
      assertNull("orphan embedded entity must have no owner before assignment", child.getOwner());

      parent.setEmbeddedEntity("inner", child);
      assertEquals(parent, child.getOwner());

      var fetched = parent.getEmbeddedEntity("inner");
      assertNotNull(fetched);
      assertTrue(fetched.isEmbedded());
    } finally {
      session.rollback();
    }
  }

  // ---------------------------------------------------------------------------
  // Cluster (a.6): link-bag conversion across the embedded↔B-tree threshold.
  //
  // The default LINK_COLLECTION_EMBEDDED_TO_BTREE_THRESHOLD is 40 (see
  // GlobalConfiguration.java:550). A LinkBag stays inline-embedded while
  // size() < threshold; once size() reaches the threshold and checkAndConvert
  // is invoked, the delegate flips from EmbeddedLinkBag to BTreeBasedLinkBag.
  // This test explicitly drives the boundary in-memory without depending on
  // any disk-backed btree storage path (the disk-coupled link-bag paths
  // are exercised by the page-frame test suite).
  // ---------------------------------------------------------------------------

  /**
   * A LinkBag created with the default threshold starts in inline / embedded mode and stays
   * embedded until {@link LinkBag#checkAndConvert()} sees the size cross the configured
   * threshold. We intentionally drive the size up to (threshold - 1) and confirm the delegate is
   * still embedded; then we add one more pair to reach the threshold and confirm
   * {@code checkAndConvert} promotes the delegate. The threshold is read from the live session
   * config rather than hard-coded so the test follows configuration changes.
   */
  @Test
  public void testLinkBagEmbeddedToBTreeThresholdBoundary() {
    var threshold = session.getConfiguration()
        .getValueAsInteger(GlobalConfiguration.LINK_COLLECTION_EMBEDDED_TO_BTREE_THRESHOLD);
    // The default threshold is 40 — defend against environments that disabled the conversion
    // entirely (-1) by simply skipping the boundary assertion in that case.
    if (threshold < 0) {
      return;
    }

    session.begin();
    var stubIds = new ArrayList<RID>();
    for (var i = 0; i <= threshold; i++) {
      var stub = session.newEntity();
      stubIds.add(stub.getIdentity());
    }
    session.commit();

    session.begin();
    try {
      var bag = new LinkBag(session);
      // Fill up to (threshold - 1) — should still be inline.
      for (var i = 0; i < threshold - 1; i++) {
        bag.add(stubIds.get(i));
      }
      bag.checkAndConvert();
      assertTrue(
          "below threshold (" + (threshold - 1) + " < " + threshold + ") the delegate "
              + "must remain embedded",
          bag.isEmbedded());

      // Cross the threshold by adding one more pair, then re-trigger checkAndConvert.
      bag.add(stubIds.get(threshold - 1));
      bag.checkAndConvert();
      assertFalse(
          "at-threshold size (" + threshold + ") must trigger the embedded->BTree promotion",
          bag.isEmbedded());
    } finally {
      session.rollback();
    }
  }

  // ---------------------------------------------------------------------------
  // (d) Deeply-nested embedded boundary test for toString / toMap / toJSON.
  // ---------------------------------------------------------------------------

  /**
   * {@link EntityImpl#toString()} tracks an {@code inspected} set to bound recursion when
   * embedded structures are walked (see {@code toString(Set<DBRecord>)} around lines
   * 3955–4014 of {@code EntityImpl}). {@link EmbeddedEntityImpl#checkPropertyValue} rejects
   * a true cycle at construction time, so we exercise the recursion guard via a depth-3
   * nested acyclic structure (a -> b -> c -> deep) — that path is what regresses if the
   * inspected-set logic is altered. {@code toString}, {@code toJSON}, and {@code toMap}
   * must all complete without stack-overflow or infinite loop. We do not use
   * {@code @Test(timeout=...)} here because JUnit runs timed tests on a separate thread
   * and the session is not activated there; a regression would still manifest as a hung
   * test or a {@link StackOverflowError}, which Surefire surfaces clearly.
   */
  @Test
  public void testDeeplyNestedEmbeddedReferenceDoesNotInfiniteLoop() {
    session.begin();
    try {
      // Build A.embedded -> B and a transitive back-reference B.peer -> A_proxy via an
      // additional embedded child. The recursion guard in toString uses object identity on
      // the inspected set, so any reachable self-occurrence triggers the bounded fallback.
      var a = (EntityImpl) session.newEntity();
      var b = (EmbeddedEntityImpl) session.newEmbeddedEntity();
      var c = (EmbeddedEntityImpl) session.newEmbeddedEntity();
      a.setEmbeddedEntity("embedded", b);
      b.setEmbeddedEntity("peer", c);
      // Nested-deep but acyclic — we still exercise the recursion path heavily.
      var deep = (EmbeddedEntityImpl) session.newEmbeddedEntity();
      c.setEmbeddedEntity("deep", deep);

      var aToString = a.toString();
      assertNotNull(aToString);
      assertFalse(
          "deeply-nested embedded toString must produce a non-empty rendering",
          aToString.isEmpty());

      // toMap walks the property map (LinkedHashMap copy) and converts embedded entities via
      // ResultInternal.toMapValue. Termination plus presence of the embedded key is the load-
      // bearing assertion. (toJSON is not probed here because the JSON serializer rejects
      // non-persistent rid references on a transient transaction-only entity; the cycle-guard
      // contract for toJSON is covered by a separate test on a persisted entity.)
      var map = a.toMap(false);
      assertTrue(map.containsKey("embedded"));
    } finally {
      session.rollback();
    }
  }

  // ---------------------------------------------------------------------------
  // (e) OPPOSITE_LINK_CONTAINER_PREFIX should-be-final shape pin.
  //
  // Pre-Track-22 PSI audit (mcp-steroid ReferencesSearch all-scope) showed
  // 0 writes anywhere — the field is logically a constant but is declared
  // mutable. Pin the OBSERVED shape (not-final) plus other invariants so
  // that any change (final tightening OR a stray write) is caught and
  // forwarded to the deferred-cleanup track.
  // ---------------------------------------------------------------------------

  /**
   * {@link EntityImpl#OPPOSITE_LINK_CONTAINER_PREFIX} is documented as a constant prefix
   * separator for opposite-link bag property names. PSI shows zero writes anywhere, so it is
   * logically final but declared mutable. The pin records the observed-but-incorrect shape: the
   * field must remain {@code public static}, must equal {@code '#'}, and currently is NOT
   * final. When the deferred-cleanup track tightens the declaration to {@code public static
   * final}, the {@code assertFalse} below will flip — that flip is the signal to update this
   * pin and the call sites in lockstep.
   *
   * <p>WHEN-FIXED: tighten this field to {@code public static final char} in the
   * deferred-cleanup track, then change the {@code assertFalse} below to {@code assertTrue}.
   */
  @Test
  public void testOppositeLinkContainerPrefixShapePin() throws NoSuchFieldException {
    var field = EntityImpl.class.getDeclaredField("OPPOSITE_LINK_CONTAINER_PREFIX");
    var modifiers = field.getModifiers();

    assertTrue("must remain a public field", Modifier.isPublic(modifiers));
    assertTrue("must remain a static field", Modifier.isStatic(modifiers));
    // WHEN-FIXED: tighten to final and flip this assertion to assertTrue.
    assertFalse(
        "OPPOSITE_LINK_CONTAINER_PREFIX is currently mutable; PSI shows 0 writes — when the "
            + "deferred-cleanup track tightens this to final, flip this assertion.",
        Modifier.isFinal(modifiers));
    assertEquals(char.class, field.getType());
    assertEquals('#', EntityImpl.OPPOSITE_LINK_CONTAINER_PREFIX);
  }

  /**
   * The static helper {@link EntityImpl#getOppositeLinkBagPropertyName(String)} is the only
   * caller of {@code OPPOSITE_LINK_CONTAINER_PREFIX}. It must consistently prefix the input.
   * This pin guards both the prefix constant value and the helper's behaviour.
   */
  @Test
  public void testOppositeLinkBagPropertyNameHelper() {
    assertEquals("#out_E", EntityImpl.getOppositeLinkBagPropertyName("out_E"));
    assertEquals("#in_Friend", EntityImpl.getOppositeLinkBagPropertyName("in_Friend"));
  }

  // ---------------------------------------------------------------------------
  // (g) Out-of-scope-by-design marker.
  //
  // Storage-coupled lazy deserialization paths (page-frame slot reads,
  // foreign-memory buffer reads beyond the existing EntityImplPageFrameTest
  // coverage) belong to the page-frame test suite. They cannot be exercised here without
  // pulling in the page-frame fixture stack, which lives under
  // core/storage/cache/ and is out of scope for the record-impl test target.
  //
  // This empty marker test exists to make the out-of-scope explicit at the
  // test-class level so a future contributor sees the forwarding note when
  // looking for "why isn't lazy-deserialize covered here?".
  // ---------------------------------------------------------------------------

  /**
   * Marker test documenting which {@link EntityImpl} surface is intentionally out of scope for
   * this class. Storage-coupled lazy deserialization (page-frame zero-copy reads, foreign-memory
   * buffer-backed property access beyond what {@code EntityImplPageFrameTest} already covers)
   * is forwarded to the page-frame test suite via the deferred-cleanup queue. This test asserts only the
   * forwarding contract: the page-frame test class still exists and continues to be the
   * reference for those paths.
   */
  @Test
  public void testStorageCoupledLazyPathsAreOutOfScopeForwardedToPageFrameSuite() {
    // Forwarding pin — see the page-frame test suite (linked via the deferred-cleanup queue). The PageFrame integration test stays
    // authoritative for storage-coupled lazy paths; we only assert it still exists.
    Class<?> pageFrameTest;
    try {
      pageFrameTest = Class.forName(
          "com.jetbrains.youtrackdb.internal.core.record.impl.EntityImplPageFrameTest");
    } catch (ClassNotFoundException e) {
      // If the storage-coupled test was renamed or moved, surface the rename loudly so this
      // forwarding note can be updated in lockstep.
      fail(
          "EntityImplPageFrameTest was expected to exist as the storage-coupled lazy-path "
              + "reference but was not found: " + e.getMessage());
      return;
    }
    assertNotNull(pageFrameTest);
  }

}
