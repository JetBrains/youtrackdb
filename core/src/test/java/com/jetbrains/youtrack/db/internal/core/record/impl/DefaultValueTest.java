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

    var prop = classA.createProperty(session, "name", PropertyType.STRING);
    prop.setDefaultValue(session, "uuid()");

    session.begin();
    var doc = (EntityImpl) session.newEntity("ClassC");

    var val = doc.toStream();
    var doc1 = (EntityImpl) session.newEntity();
    RecordInternal.unsetDirty(doc1);
    doc1.fromStream(val);
    doc1.deserializeFields();
    assertEquals(doc.field("name"), (String) doc1.field("name"));
    session.rollback();
  }

  @Test
  public void testDefaultValueDate() {
    Schema schema = session.getMetadata().getSchema();
    var classA = schema.createClass("ClassA");

    var prop = classA.createProperty(session, "date", PropertyType.DATE);
    prop.setDefaultValue(session, DateHelper.getDateTimeFormatInstance(session).format(new Date()));
    var some = classA.createProperty(session, "id", PropertyType.STRING);
    some.setDefaultValue(session, "uuid()");

    session.begin();
    var doc = (EntityImpl) session.newEntity(classA);
    EntityImpl saved = doc;
    session.commit();

    session.begin();
    saved = session.bindToSession(saved);
    assertNotNull(saved.field("date"));
    assertTrue(saved.field("date") instanceof Date);
    assertNotNull(saved.field("id"));

    var inserted = session.command("insert into ClassA content {}").next();
    session.commit();

    session.begin();
    EntityImpl seved1 = session.load(inserted.getIdentity());
    assertNotNull(seved1.field("date"));
    assertNotNull(seved1.field("id"));
    assertTrue(seved1.field("date") instanceof Date);
    session.commit();
  }

  @Test
  public void testDefaultValueDateFromContent() {
    Schema schema = session.getMetadata().getSchema();
    var classA = schema.createClass("ClassA");

    var prop = classA.createProperty(session, "date", PropertyType.DATE);
    prop.setDefaultValue(session, DateHelper.getDateTimeFormatInstance(session).format(new Date()));
    var some = classA.createProperty(session, "id", PropertyType.STRING);
    some.setDefaultValue(session, "uuid()");

    var value = "2000-01-01 00:00:00";

    session.begin();
    var doc = (EntityImpl) session.newEntity(classA);
    EntityImpl saved = doc;
    session.commit();

    session.begin();
    saved = session.bindToSession(saved);
    assertNotNull(saved.field("date"));
    assertTrue(saved.field("date") instanceof Date);
    assertNotNull(saved.field("id"));
    session.commit();

    session.begin();
    var inserted = session.command("insert into ClassA content {\"date\":\"" + value + "\"}")
        .next();
    session.commit();

    session.begin();
    EntityImpl seved1 = session.load(inserted.getIdentity());
    assertNotNull(seved1.field("date"));
    assertNotNull(seved1.field("id"));
    assertTrue(seved1.field("date") instanceof Date);
    assertEquals(DateHelper.getDateTimeFormatInstance(session).format(seved1.field("date")), value);
    session.commit();
  }

  @Test
  public void testDefaultValueFromJson() {
    Schema schema = session.getMetadata().getSchema();
    var classA = schema.createClass("ClassA");

    var prop = classA.createProperty(session, "date", PropertyType.DATE);
    prop.setDefaultValue(session, DateHelper.getDateTimeFormatInstance(session).format(new Date()));

    session.begin();
    var doc = (EntityImpl) session.newEntity("ClassA");
    doc.updateFromJSON("{\"@class\":\"ClassA\",\"other\":\"other\"}");
    EntityImpl saved = doc;
    session.commit();

    session.begin();
    saved = session.bindToSession(saved);
    assertNotNull(saved.field("date"));
    assertTrue(saved.field("date") instanceof Date);
    assertNotNull(saved.field("other"));
    session.commit();
  }

  @Test
  public void testDefaultValueProvidedFromJson() {
    Schema schema = session.getMetadata().getSchema();
    var classA = schema.createClass("ClassA");

    var prop = classA.createProperty(session, "date", PropertyType.DATETIME);
    prop.setDefaultValue(session, DateHelper.getDateTimeFormatInstance(session).format(new Date()));

    var value1 = DateHelper.getDateTimeFormatInstance(session).format(new Date());
    session.begin();
    var doc = (EntityImpl) session.newEntity("ClassA");
    doc.updateFromJSON("{\"@class\":\"ClassA\",\"date\":\"" + value1 + "\",\"other\":\"other\"}");
    EntityImpl saved = doc;
    session.commit();

    session.begin();
    saved = session.bindToSession(saved);
    assertNotNull(saved.field("date"));
    assertEquals(DateHelper.getDateTimeFormatInstance(session).format(saved.field("date")), value1);
    assertNotNull(saved.field("other"));
    session.commit();
  }

  @Test
  public void testDefaultValueMandatoryReadonlyFromJson() {
    Schema schema = session.getMetadata().getSchema();
    var classA = schema.createClass("ClassA");

    var prop = classA.createProperty(session, "date", PropertyType.DATE);
    prop.setMandatory(session, true);
    prop.setReadonly(session, true);
    prop.setDefaultValue(session, DateHelper.getDateTimeFormatInstance(session).format(new Date()));

    session.begin();
    var doc = (EntityImpl) session.newEntity("ClassA");
    doc.updateFromJSON("{\"@class\":\"ClassA\",\"other\":\"other\"}");
    EntityImpl saved = doc;
    session.commit();

    session.begin();
    saved = session.bindToSession(saved);
    assertNotNull(saved.field("date"));
    assertTrue(saved.field("date") instanceof Date);
    assertNotNull(saved.field("other"));
    session.commit();
  }

  @Test
  public void testDefaultValueProvidedMandatoryReadonlyFromJson() {
    Schema schema = session.getMetadata().getSchema();
    var classA = schema.createClass("ClassA");

    var prop = classA.createProperty(session, "date", PropertyType.DATETIME);
    prop.setMandatory(session, true);
    prop.setReadonly(session, true);
    prop.setDefaultValue(session, DateHelper.getDateTimeFormatInstance(session).format(new Date()));

    var value1 = DateHelper.getDateTimeFormatInstance(session).format(new Date());
    session.begin();
    var doc = (EntityImpl) session.newEntity("ClassA");
    doc.updateFromJSON("{\"@class\":\"ClassA\",\"date\":\"" + value1 + "\",\"other\":\"other\"}");
    EntityImpl saved = doc;
    session.commit();

    session.begin();
    saved = session.bindToSession(saved);
    assertNotNull(saved.field("date"));
    assertEquals(DateHelper.getDateTimeFormatInstance(session).format(saved.field("date")), value1);
    assertNotNull(saved.field("other"));
    session.commit();
  }

  @Test
  public void testDefaultValueUpdateMandatoryReadonlyFromJson() {
    Schema schema = session.getMetadata().getSchema();
    var classA = schema.createClass("ClassA");

    var prop = classA.createProperty(session, "date", PropertyType.DATETIME);
    prop.setMandatory(session, true);
    prop.setReadonly(session, true);
    prop.setDefaultValue(session, DateHelper.getDateTimeFormatInstance(session).format(new Date()));

    session.begin();
    var doc = (EntityImpl) session.newEntity("ClassA");
    doc.updateFromJSON("{\"@class\":\"ClassA\",\"other\":\"other\"}");
    EntityImpl saved = doc;
    session.commit();

    session.begin();
    saved = session.bindToSession(saved);
    doc = session.bindToSession(doc);

    assertNotNull(saved.field("date"));
    assertTrue(saved.field("date") instanceof Date);
    assertNotNull(saved.field("other"));
    var val = DateHelper.getDateTimeFormatInstance(session).format(doc.field("date"));
    var doc1 = (EntityImpl) session.newEntity("ClassA");
    doc1.updateFromJSON("{\"@class\":\"ClassA\",\"date\":\"" + val + "\",\"other\":\"other1\"}");
    saved.merge(doc1, true, true);
    session.commit();

    session.begin();
    saved = session.bindToSession(saved);
    assertNotNull(saved.field("date"));
    assertEquals(DateHelper.getDateTimeFormatInstance(session).format(saved.field("date")), val);
    assertEquals(saved.field("other"), "other1");
    session.commit();
  }
}
