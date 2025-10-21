package com.jetbrains.youtrackdb.internal.core.record.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.gremlin.__;
import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.record.RecordAbstract;
import com.jetbrains.youtrackdb.internal.core.util.DateHelper;
import java.util.Date;
import org.junit.Test;

public class DefaultValueTest extends DbTestBase {

  @Test
  public void testKeepValueSerialization() {
    // create example schema
    graph.autoExecuteInTx(
        g -> g.createSchemaClass("ClassC").createSchemaProperty("name", PropertyType.STRING)
            .defaultValueAttr("uuid()"));

    session.begin();
    var doc = (EntityImpl) session.newEntity("ClassC");

    var val = doc.toStream();
    var doc1 = (EntityImpl) session.newEntity();
    final var rec = (RecordAbstract) doc1;
    rec.unsetDirty();
    doc1.fromStream(val);
    doc1.deserializeProperties();
    assertEquals(doc.getProperty("name"), (String) doc1.getProperty("name"));
    session.rollback();
  }

  @Test
  public void testDefaultValueDate() {

    var classA = "ClassA";
    graph.autoExecuteInTx(g -> g.createSchemaClass("ClassA",
        __.createSchemaProperty("date", PropertyType.DATE)
                .defaultValueAttr(DateHelper.getDateTimeFormatInstance(session).format(new Date())),
        __.createSchemaProperty("id", PropertyType.STRING)
                .defaultValueAttr("uuid()")
        )
    );

    session.begin();
    var doc = (EntityImpl) session.newEntity(classA);
    EntityImpl saved = doc;
    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    saved = activeTx.load(saved);
    assertNotNull(saved.getProperty("date"));
    assertTrue(saved.getProperty("date") instanceof Date);
    assertNotNull(saved.getProperty("id"));

    var inserted = session.execute("insert into ClassA content {}").next();
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
    var classA = "ClassA";
    graph.autoExecuteInTx(g -> g.createSchemaClass(classA,
        __.createSchemaProperty("date", PropertyType.DATE)
                .defaultValueAttr(DateHelper.getDateTimeFormatInstance(session).format(new Date())),
        __.createSchemaProperty("id", PropertyType.STRING)
                .defaultValueAttr("uuid()")
        )
    );

    var value = "2000-01-01 00:00:00";

    session.begin();
    var saved = (EntityImpl) session.newEntity(classA);
    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    saved = activeTx.load(saved);
    assertNotNull(saved.getProperty("date"));
    assertTrue(saved.getProperty("date") instanceof Date);
    assertNotNull(saved.getProperty("id"));
    session.commit();

    session.begin();
    var inserted = session.execute("insert into ClassA content {\"date\":\"" + value + "\"}")
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
    var classA = "ClassA";
    graph.autoExecuteInTx(g -> g.createSchemaClass(classA,
        __.createSchemaProperty("date", PropertyType.DATE)
                .defaultValueAttr(DateHelper.getDateTimeFormatInstance(session).format(new Date()))
        )
    );

    session.begin();
    var doc = (EntityImpl) session.newEntity("ClassA");
    doc.updateFromJSON("{\"@class\":\"ClassA\",\"other\":\"other\"}");
    var saved = doc;
    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    saved = activeTx.load(saved);
    assertNotNull(saved.getProperty("date"));
    assertTrue(saved.getProperty("date") instanceof Date);
    assertNotNull(saved.getProperty("other"));
    session.commit();
  }

  @Test
  public void testDefaultValueProvidedFromJson() {
    graph.autoExecuteInTx(g -> g.createSchemaClass("ClassA",
        __.createSchemaProperty("date", PropertyType.DATETIME)
                .defaultValueAttr(DateHelper.getDateTimeFormatInstance(session).format(new Date()))
        )
    );

    var value1 = DateHelper.getDateTimeFormatInstance(session).format(new Date());
    session.begin();
    var doc = (EntityImpl) session.newEntity("ClassA");
    doc.updateFromJSON("{\"@class\":\"ClassA\",\"date\":\"" + value1 + "\",\"other\":\"other\"}");
    var saved = doc;
    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    saved = activeTx.load(saved);
    assertNotNull(saved.getProperty("date"));
    assertEquals(DateHelper.getDateTimeFormatInstance(session).format(saved.getProperty("date")),
        value1);
    assertNotNull(saved.getProperty("other"));
    session.commit();
  }

  @Test
  public void testDefaultValueMandatoryReadonlyFromJson() {
    graph.autoExecuteInTx(g ->
        g.createSchemaClass("ClassA").createSchemaProperty("date", PropertyType.DATE)
            .defaultValueAttr(DateHelper.getDateTimeFormatInstance(session).format(new Date()))
            .mandatoryAttr(true).readOnlyAttr(true)
    );

    session.begin();
    var doc = (EntityImpl) session.newEntity("ClassA");
    doc.updateFromJSON("{\"@class\":\"ClassA\",\"other\":\"other\"}");
    EntityImpl saved = doc;
    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    saved = activeTx.load(saved);
    assertNotNull(saved.getProperty("date"));
    assertTrue(saved.getProperty("date") instanceof Date);
    assertNotNull(saved.getProperty("other"));
    session.commit();
  }

  @Test
  public void testDefaultValueProvidedMandatoryReadonlyFromJson() {
    graph.autoExecuteInTx(
        g -> g.createSchemaClass("ClassA").createSchemaProperty("date", PropertyType.DATETIME)
            .defaultValueAttr(DateHelper.getDateTimeFormatInstance(session).format(new Date()))
            .mandatoryAttr(true).readOnlyAttr(true)
    );

    var value1 = DateHelper.getDateTimeFormatInstance(session).format(new Date());
    session.begin();
    var doc = (EntityImpl) session.newEntity("ClassA");
    doc.updateFromJSON("{\"@class\":\"ClassA\",\"date\":\"" + value1 + "\",\"other\":\"other\"}");
    var saved = doc;
    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    saved = activeTx.load(saved);
    assertNotNull(saved.getProperty("date"));
    assertEquals(DateHelper.getDateTimeFormatInstance(session).format(saved.getProperty("date")),
        value1);
    assertNotNull(saved.getProperty("other"));
    session.commit();
  }

  @Test
  public void testDefaultValueUpdateMandatoryReadonlyFromJson() {
    graph.autoExecuteInTx(
        g -> g.createSchemaClass("ClassA").createSchemaProperty("date", PropertyType.DATETIME)
            .defaultValueAttr(DateHelper.getDateTimeFormatInstance(session).format(new Date()))
            .mandatoryAttr(true).readOnlyAttr(true)
    );

    session.begin();
    var doc = (EntityImpl) session.newEntity("ClassA");
    doc.updateFromJSON("{\"@class\":\"ClassA\",\"other\":\"other\"}");
    var saved = doc;
    session.commit();

    session.begin();
    var activeTx2 = session.getActiveTransaction();
    saved = activeTx2.load(saved);
    var activeTx1 = session.getActiveTransaction();
    doc = activeTx1.load(doc);

    assertNotNull(saved.getProperty("date"));
    assertTrue(saved.getProperty("date") instanceof Date);
    assertNotNull(saved.getProperty("other"));
    var val = DateHelper.getDateTimeFormatInstance(session).format(doc.getProperty("date"));
    var entity1 = (EntityImpl) session.newEntity("ClassA");
    entity1.updateFromJSON("{\"@class\":\"ClassA\",\"date\":\"" + val + "\",\"other\":\"other1\"}");
    saved.updateFromResult(entity1);
    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    saved = activeTx.load(saved);
    assertNotNull(saved.getProperty("date"));
    assertEquals(DateHelper.getDateTimeFormatInstance(session).format(saved.getProperty("date")),
        val);
    assertEquals(saved.getProperty("other"), "other1");
    session.commit();
  }
}
