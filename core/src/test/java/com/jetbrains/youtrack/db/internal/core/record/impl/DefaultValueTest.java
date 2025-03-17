package com.jetbrains.youtrack.db.internal.core.record.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.core.record.RecordInternal;
import com.jetbrains.youtrack.db.internal.core.util.DateHelper;
import java.util.Date;
import org.junit.Test;

public class DefaultValueTest extends DbTestBase {

  @Test
  public void testKeepValueSerialization() {
    // create example schema
    Schema schema = session.getMetadata().getSchema();
    var classA = schema.createClass("ClassC");

    var prop = classA.createProperty("name", PropertyType.STRING);
    prop.setDefaultValue("uuid()");

    session.begin();
    var doc = (EntityImpl) session.newEntity("ClassC");

    var val = doc.toStream();
    var doc1 = (EntityImpl) session.newEntity();
    RecordInternal.unsetDirty(doc1);
    doc1.fromStream(val);
    doc1.deserializeProperties();
    assertEquals(doc.getProperty("name"), (String) doc1.getProperty("name"));
    session.rollback();
  }

  @Test
  public void testDefaultValueDate() {
    Schema schema = session.getMetadata().getSchema();
    var classA = schema.createClass("ClassA");

    var prop = classA.createProperty("date", PropertyType.DATE);
    prop.setDefaultValue(DateHelper.getDateTimeFormatInstance(session).format(new Date()));
    var some = classA.createProperty("id", PropertyType.STRING);
    some.setDefaultValue("uuid()");

    session.begin();
    var doc = (EntityImpl) session.newEntity(classA);
    EntityImpl saved = doc;
    session.commit();

    session.begin();
    saved = session.bindToSession(saved);
    assertNotNull(saved.getProperty("date"));
    assertTrue(saved.getProperty("date") instanceof Date);
    assertNotNull(saved.getProperty("id"));

    var inserted = session.command("insert into ClassA content {}").next();
    session.commit();

    session.begin();
    EntityImpl seved1 = session.load(inserted.getIdentity());
    assertNotNull(seved1.getProperty("date"));
    assertNotNull(seved1.getProperty("id"));
    assertTrue(seved1.getProperty("date") instanceof Date);
    session.commit();
  }

  @Test
  public void testDefaultValueDateFromContent() {
    Schema schema = session.getMetadata().getSchema();
    var classA = schema.createClass("ClassA");

    var prop = classA.createProperty("date", PropertyType.DATE);
    prop.setDefaultValue(DateHelper.getDateTimeFormatInstance(session).format(new Date()));
    var some = classA.createProperty("id", PropertyType.STRING);
    some.setDefaultValue("uuid()");

    var value = "2000-01-01 00:00:00";

    session.begin();
    var doc = (EntityImpl) session.newEntity(classA);
    EntityImpl saved = doc;
    session.commit();

    session.begin();
    saved = session.bindToSession(saved);
    assertNotNull(saved.getProperty("date"));
    assertTrue(saved.getProperty("date") instanceof Date);
    assertNotNull(saved.getProperty("id"));
    session.commit();

    session.begin();
    var inserted = session.command("insert into ClassA content {\"date\":\"" + value + "\"}")
        .next();
    session.commit();

    session.begin();
    EntityImpl seved1 = session.load(inserted.getIdentity());
    assertNotNull(seved1.getProperty("date"));
    assertNotNull(seved1.getProperty("id"));
    assertTrue(seved1.getProperty("date") instanceof Date);
    assertEquals(DateHelper.getDateTimeFormatInstance(session).format(seved1.getProperty("date")),
        value);
    session.commit();
  }

  @Test
  public void testDefaultValueFromJson() {
    Schema schema = session.getMetadata().getSchema();
    var classA = schema.createClass("ClassA");

    var prop = classA.createProperty("date", PropertyType.DATE);
    prop.setDefaultValue(DateHelper.getDateTimeFormatInstance(session).format(new Date()));

    session.begin();
    var doc = (EntityImpl) session.newEntity("ClassA");
    doc.updateFromJSON("{\"@class\":\"ClassA\",\"other\":\"other\"}");
    EntityImpl saved = doc;
    session.commit();

    session.begin();
    saved = session.bindToSession(saved);
    assertNotNull(saved.getProperty("date"));
    assertTrue(saved.getProperty("date") instanceof Date);
    assertNotNull(saved.getProperty("other"));
    session.commit();
  }

  @Test
  public void testDefaultValueProvidedFromJson() {
    Schema schema = session.getMetadata().getSchema();
    var classA = schema.createClass("ClassA");

    var prop = classA.createProperty("date", PropertyType.DATETIME);
    prop.setDefaultValue(DateHelper.getDateTimeFormatInstance(session).format(new Date()));

    var value1 = DateHelper.getDateTimeFormatInstance(session).format(new Date());
    session.begin();
    var doc = (EntityImpl) session.newEntity("ClassA");
    doc.updateFromJSON("{\"@class\":\"ClassA\",\"date\":\"" + value1 + "\",\"other\":\"other\"}");
    EntityImpl saved = doc;
    session.commit();

    session.begin();
    saved = session.bindToSession(saved);
    assertNotNull(saved.getProperty("date"));
    assertEquals(DateHelper.getDateTimeFormatInstance(session).format(saved.getProperty("date")),
        value1);
    assertNotNull(saved.getProperty("other"));
    session.commit();
  }

  @Test
  public void testDefaultValueMandatoryReadonlyFromJson() {
    Schema schema = session.getMetadata().getSchema();
    var classA = schema.createClass("ClassA");

    var prop = classA.createProperty("date", PropertyType.DATE);
    prop.setMandatory(true);
    prop.setReadonly(true);
    prop.setDefaultValue(DateHelper.getDateTimeFormatInstance(session).format(new Date()));

    session.begin();
    var doc = (EntityImpl) session.newEntity("ClassA");
    doc.updateFromJSON("{\"@class\":\"ClassA\",\"other\":\"other\"}");
    EntityImpl saved = doc;
    session.commit();

    session.begin();
    saved = session.bindToSession(saved);
    assertNotNull(saved.getProperty("date"));
    assertTrue(saved.getProperty("date") instanceof Date);
    assertNotNull(saved.getProperty("other"));
    session.commit();
  }

  @Test
  public void testDefaultValueProvidedMandatoryReadonlyFromJson() {
    Schema schema = session.getMetadata().getSchema();
    var classA = schema.createClass("ClassA");

    var prop = classA.createProperty("date", PropertyType.DATETIME);
    prop.setMandatory(true);
    prop.setReadonly(true);
    prop.setDefaultValue(DateHelper.getDateTimeFormatInstance(session).format(new Date()));

    var value1 = DateHelper.getDateTimeFormatInstance(session).format(new Date());
    session.begin();
    var doc = (EntityImpl) session.newEntity("ClassA");
    doc.updateFromJSON("{\"@class\":\"ClassA\",\"date\":\"" + value1 + "\",\"other\":\"other\"}");
    EntityImpl saved = doc;
    session.commit();

    session.begin();
    saved = session.bindToSession(saved);
    assertNotNull(saved.getProperty("date"));
    assertEquals(DateHelper.getDateTimeFormatInstance(session).format(saved.getProperty("date")),
        value1);
    assertNotNull(saved.getProperty("other"));
    session.commit();
  }

  @Test
  public void testDefaultValueUpdateMandatoryReadonlyFromJson() {
    Schema schema = session.getMetadata().getSchema();
    var classA = schema.createClass("ClassA");

    var prop = classA.createProperty("date", PropertyType.DATETIME);
    prop.setMandatory(true);
    prop.setReadonly(true);
    prop.setDefaultValue(DateHelper.getDateTimeFormatInstance(session).format(new Date()));

    session.begin();
    var doc = (EntityImpl) session.newEntity("ClassA");
    doc.updateFromJSON("{\"@class\":\"ClassA\",\"other\":\"other\"}");
    EntityImpl saved = doc;
    session.commit();

    session.begin();
    saved = session.bindToSession(saved);
    doc = session.bindToSession(doc);

    assertNotNull(saved.getProperty("date"));
    assertTrue(saved.getProperty("date") instanceof Date);
    assertNotNull(saved.getProperty("other"));
    var val = DateHelper.getDateTimeFormatInstance(session).format(doc.getProperty("date"));
    var entity1 = (EntityImpl) session.newEntity("ClassA");
    entity1.updateFromJSON("{\"@class\":\"ClassA\",\"date\":\"" + val + "\",\"other\":\"other1\"}");
    saved.updateFromResult(entity1);
    session.commit();

    session.begin();
    saved = session.bindToSession(saved);
    assertNotNull(saved.getProperty("date"));
    assertEquals(DateHelper.getDateTimeFormatInstance(session).format(saved.getProperty("date")),
        val);
    assertEquals(saved.getProperty("other"), "other1");
    session.commit();
  }
}
