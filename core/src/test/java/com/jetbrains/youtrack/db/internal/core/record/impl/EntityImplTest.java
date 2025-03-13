package com.jetbrains.youtrack.db.internal.core.record.impl;

import com.jetbrains.youtrack.db.api.YouTrackDB;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.core.CreateDatabaseUtil;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionAbstract;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.record.ridbag.RidBag;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.record.RecordInternal;
import java.util.Map;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class EntityImplTest extends DbTestBase {

  private static final String dbName = EntityImplTest.class.getSimpleName();
  private static final String defaultDbAdminCredentials = "admin";

  @Test
  public void testClearResetsFieldTypes() throws Exception {
    session.begin();
    var doc = (EntityImpl) session.newEntity();
    doc.setFieldType("integer", PropertyType.INTEGER);
    doc.setFieldType("string", PropertyType.STRING);
    doc.setFieldType("binary", PropertyType.BINARY);

    assertEquals(PropertyType.INTEGER, doc.getPropertyType("integer"));
    assertEquals(PropertyType.STRING, doc.getPropertyType("string"));
    assertEquals(PropertyType.BINARY, doc.getPropertyType("binary"));

    doc.clear();

    assertNull(doc.getPropertyType("integer"));
    assertNull(doc.getPropertyType("string"));
    assertNull(doc.getPropertyType("binary"));
    session.rollback();
  }

  @Test
  public void testResetResetsFieldTypes() throws Exception {
    session.begin();
    var doc = (EntityImpl) session.newEntity();
    doc.setFieldType("integer", PropertyType.INTEGER);
    doc.setFieldType("string", PropertyType.STRING);
    doc.setFieldType("binary", PropertyType.BINARY);

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
    doc.field("integer", 10, PropertyType.INTEGER);
    doc.field("string", 20, PropertyType.STRING);
    doc.field("binary", new byte[]{30}, PropertyType.BINARY);

    assertEquals(PropertyType.INTEGER, doc.getPropertyType("integer"));
    assertEquals(PropertyType.STRING, doc.getPropertyType("string"));
    assertEquals(PropertyType.BINARY, doc.getPropertyType("binary"));
    session.rollback();
  }

  @Test
  public void testKeepFieldTypeSerialization() {
    session.begin();
    var doc = (EntityImpl) session.newEntity();
    doc.field("integer", 10, PropertyType.INTEGER);
    doc.field("link", new RecordId(1, 2), PropertyType.LINK);
    doc.field("string", 20, PropertyType.STRING);
    doc.field("binary", new byte[]{30}, PropertyType.BINARY);

    assertEquals(PropertyType.INTEGER, doc.getPropertyType("integer"));
    assertEquals(PropertyType.LINK, doc.getPropertyType("link"));
    assertEquals(PropertyType.STRING, doc.getPropertyType("string"));
    assertEquals(PropertyType.BINARY, doc.getPropertyType("binary"));
    var ser = DatabaseSessionAbstract.getDefaultSerializer();
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
    doc.field("integer", 10);
    doc.field("link", new RecordId(1, 2));
    doc.field("string", "string");
    doc.field("binary", new byte[]{30});

    assertEquals(PropertyType.INTEGER, doc.getPropertyType("integer"));
    assertEquals(PropertyType.LINK, doc.getPropertyType("link"));
    assertEquals(PropertyType.STRING, doc.getPropertyType("string"));
    assertEquals(PropertyType.BINARY, doc.getPropertyType("binary"));

    var ser = DatabaseSessionAbstract.getDefaultSerializer();
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
    DatabaseSessionInternal session = null;
    YouTrackDB ytdb = null;
    try {
      ytdb = CreateDatabaseUtil.createDatabase(dbName, "memory:", CreateDatabaseUtil.TYPE_MEMORY);
      session = (DatabaseSessionInternal) ytdb.open(dbName, defaultDbAdminCredentials,
          CreateDatabaseUtil.NEW_ADMIN_PASSWORD);

      var clazz = session.getMetadata().getSchema().createClass("Test");
      clazz.createProperty("integer", PropertyType.INTEGER);
      clazz.createProperty("link", PropertyType.LINK);
      clazz.createProperty("string", PropertyType.STRING);
      clazz.createProperty("binary", PropertyType.BINARY);

      session.begin();

      var entity = (EntityImpl) session.newEntity(clazz);
      entity.field("integer", 10);
      entity.field("link", new RecordId(1, 2));
      entity.field("string", "string");
      entity.field("binary", new byte[]{30});

      // the types are from the schema.
      assertEquals(PropertyType.INTEGER, entity.getPropertyType("integer"));
      assertEquals(PropertyType.LINK, entity.getPropertyType("link"));
      assertEquals(PropertyType.STRING, entity.getPropertyType("string"));
      assertEquals(PropertyType.BINARY, entity.getPropertyType("binary"));
      var ser = DatabaseSessionAbstract.getDefaultSerializer();
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
    doc.field("link", new RecordId(1, 2));
    var ser = DatabaseSessionAbstract.getDefaultSerializer();
    var bytes = ser.toStream(session, doc);
    doc = (EntityImpl) session.newEntity();
    ser.fromStream(session, bytes, doc, null);
    assertEquals(PropertyType.LINK, doc.getPropertyType("link"));
    doc.field("link", new RidBag(session));
    assertNotEquals(PropertyType.LINK, doc.getPropertyType("link"));
    session.rollback();
  }

  @Test
  public void testRemovingReadonlyField() {
    DatabaseSessionInternal db = null;
    YouTrackDB odb = null;
    try {
      odb = CreateDatabaseUtil.createDatabase(dbName, "memory:", CreateDatabaseUtil.TYPE_MEMORY);
      db = (DatabaseSessionInternal) odb.open(dbName, defaultDbAdminCredentials,
          CreateDatabaseUtil.NEW_ADMIN_PASSWORD);

      Schema schema = db.getMetadata().getSchema();
      var classA = schema.createClass("TestRemovingField2");
      classA.createProperty("name", PropertyType.STRING);
      var property = classA.createProperty("property", PropertyType.STRING);
      property.setReadonly(true);
      db.begin();
      var doc = (EntityImpl) db.newEntity(classA);
      doc.field("name", "My Name");
      doc.field("property", "value1");

      doc.field("name", "My Name 2");
      doc.field("property", "value2");
      doc.undo(); // we decided undo everything
      doc.field("name", "My Name 3"); // change something

      doc.field("name", "My Name 4");
      doc.field("property", "value4");
      doc.undo("property"); // we decided undo readonly field

      db.commit();
    } finally {
      if (db != null) {
        db.close();
      }
      if (odb != null) {
        odb.drop(dbName);
        odb.close();
      }
    }
  }

  @Test
  public void testUndo() {
    DatabaseSessionInternal session = null;
    YouTrackDB odb = null;
    try {
      odb = CreateDatabaseUtil.createDatabase(dbName, "memory:", CreateDatabaseUtil.TYPE_MEMORY);
      session = (DatabaseSessionInternal) odb.open(dbName, defaultDbAdminCredentials,
          CreateDatabaseUtil.NEW_ADMIN_PASSWORD);

      Schema schema = session.getMetadata().getSchema();
      var classA = schema.createClass("TestUndo");
      classA.createProperty("name", PropertyType.STRING);
      classA.createProperty("property", PropertyType.STRING);

      session.begin();
      var doc = (EntityImpl) session.newEntity(classA);
      doc.field("name", "My Name");
      doc.field("property", "value1");

      session.commit();

      session.begin();
      doc = session.bindToSession(doc);
      assertEquals("My Name", doc.field("name"));
      assertEquals("value1", doc.field("property"));
      doc.undo();
      assertEquals("My Name", doc.field("name"));
      assertEquals("value1", doc.field("property"));
      doc.field("name", "My Name 2");
      doc.field("property", "value2");
      doc.undo();
      doc.field("name", "My Name 3");
      assertEquals("My Name 3", doc.field("name"));
      assertEquals("value1", doc.field("property"));

      session.commit();

      session.begin();
      doc = session.bindToSession(doc);
      doc.field("name", "My Name 4");
      doc.field("property", "value4");
      doc.undo("property");
      assertEquals("My Name 4", doc.field("name"));
      assertEquals("value1", doc.field("property"));

      session.commit();

      session.begin();
      doc = session.bindToSession(doc);
      doc.undo("property");
      assertEquals("My Name 4", doc.field("name"));
      assertEquals("value1", doc.field("property"));
      doc.undo();
      assertEquals("My Name 4", doc.field("name"));
      assertEquals("value1", doc.field("property"));
      session.commit();
    } finally {
      if (session != null) {
        session.close();
      }
      if (odb != null) {
        odb.drop(dbName);
        odb.close();
      }
    }
  }

  @Test
  public void testMergeNull() {
    session.begin();
    var dest = (EntityImpl) session.newEntity();

    var source = (EntityImpl) session.newEntity();
    source.field("key", "value");
    source.field("somenull", null);

    dest.merge(source, true, false);

    assertEquals("value", dest.field("key"));

    assertTrue(dest.containsField("somenull"));
    session.rollback();
  }


  @Test(expected = IllegalArgumentException.class)
  public void testFailNullMapKey() {
    session.executeInTx(() -> {
      var doc = (EntityImpl) session.newEntity();
      var map = session.newEmbeddedMap();
      map.put(null, "dd");
      doc.field("testMap", map);
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
    var bytes = new byte[]{0, 1, 2, 3, 4, 5};
    entity.setBinary("bytes", bytes);
    entity.clearTrackData();
    RecordInternal.unsetDirty(entity);
    assertFalse(entity.isDirty());
    assertNull(entity.getOriginalValue("bytes"));
    entity.setBinary("bytes", bytes.clone());
    assertFalse(entity.isDirty());
    assertNull(entity.getOriginalValue("bytes"));

    session.rollback();
  }
}
