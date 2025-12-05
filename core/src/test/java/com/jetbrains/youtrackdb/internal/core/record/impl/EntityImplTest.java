package com.jetbrains.youtrackdb.internal.core.record.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB.PredefinedRole;
import com.jetbrains.youtrackdb.api.YouTrackDB.UserCredential;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.api.schema.Schema;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.ridbag.LinkBag;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.record.RecordAbstract;
import java.util.Map;
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
    doc.setProperty("binary", new byte[]{30}, PropertyType.BINARY);

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
    doc.setProperty("binary", new byte[]{30}, PropertyType.BINARY);

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
    doc.setProperty("binary", new byte[]{30});

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
          new UserCredential("admin", DbTestBase.ADMIN_PASSWORD, PredefinedRole.ADMIN));

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
      entity.setProperty("binary", new byte[]{30});

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
        new UserCredential(defaultDbAdminCredentials, DbTestBase.ADMIN_PASSWORD,
            PredefinedRole.ADMIN));
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
        new UserCredential(defaultDbAdminCredentials, DbTestBase.ADMIN_PASSWORD,
            PredefinedRole.ADMIN));
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
    var bytes = new byte[]{0, 1, 2, 3, 4, 5};
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
}
