package com.jetbrains.youtrack.db.internal.core.db.hook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.api.record.EntityHookAbstract;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import java.util.UUID;
import org.junit.Test;

public class CheckHookCallCountTest extends DbTestBase {

  private final String CLASS_NAME = "Data";
  private final String FIELD_ID = "ID";
  private final String FIELD_STATUS = "STATUS";
  private final String STATUS = "processed";

  @Test
  public void testMultipleCallHook() {
    var aClass = session.getMetadata().getSchema().createClass(CLASS_NAME);
    aClass.createProperty(FIELD_ID, PropertyType.STRING);
    aClass.createProperty(FIELD_STATUS, PropertyType.STRING);
    aClass.createIndex("IDX", SchemaClass.INDEX_TYPE.NOTUNIQUE, FIELD_ID);
    var hook = new TestHook(session);
    session.registerHook(hook);

    var id = UUID.randomUUID().toString();
    session.begin();
    var first = (EntityImpl) session.newEntity(CLASS_NAME);
    first.setProperty(FIELD_ID, id);
    first.setProperty(FIELD_STATUS, STATUS);
    session.commit();

    session.begin();
    session
        .query("SELECT FROM " + CLASS_NAME + " WHERE " + FIELD_STATUS + " = '" + STATUS + "'")
        .stream()
        .count();
    hook.readCount = 0;
    session.query("SELECT FROM " + CLASS_NAME + " WHERE " + FIELD_ID + " = '" + id + "'").stream()
        .count();
    session.commit();
  }

  @Test
  public void testInHook() throws Exception {
    Schema schema = session.getMetadata().getSchema();
    var oClass = schema.createClass("TestInHook");
    oClass.createProperty("a", PropertyType.INTEGER);
    oClass.createProperty("b", PropertyType.INTEGER);
    oClass.createProperty("c", PropertyType.INTEGER);

    session.begin();
    var doc = (EntityImpl) session.newEntity(oClass);
    doc.setProperty("a", 2);
    doc.setProperty("b", 2);

    session.commit();

    session.begin();
    var activeTx2 = session.getActiveTransaction();
    doc = activeTx2.load(doc);
    assertEquals(Integer.valueOf(2), doc.getProperty("a"));
    assertEquals(Integer.valueOf(2), doc.getProperty("b"));
    assertNull(doc.getProperty("c"));
    session.rollback();

    session.registerHook(
        new EntityHookAbstract(session) {

          {
            setIncludeClasses("TestInHook");
          }

          @Override
          public void onEntityCreate(Entity entity) {
            onEntityRead(entity);
          }

          @Override
          public void onEntityRead(Entity entity) {
            var script = "select sum(a, b) as value from " + entity.getIdentity();
            try (var calculated = session.getActiveTransaction().query(script)) {
              if (calculated.hasNext()) {
                entity.setProperty("c", calculated.next().getProperty("value"));
              }
            }
          }

        });

    session.begin();
    var activeTx1 = session.getActiveTransaction();
    doc = activeTx1.load(doc);
    assertEquals(Integer.valueOf(2), doc.getProperty("a"));
    assertEquals(Integer.valueOf(2), doc.getProperty("b"));
    assertEquals(Integer.valueOf(4), doc.getProperty("c"));
    session.rollback();

    session.begin();
    doc = (EntityImpl) session.newEntity(oClass);
    doc.setProperty("a", 3);
    doc.setProperty("b", 3);

    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    doc = activeTx.load(doc);
    assertEquals(Integer.valueOf(3), doc.getProperty("a"));
    assertEquals(Integer.valueOf(3), doc.getProperty("b"));
    assertEquals(Integer.valueOf(6), doc.getProperty("c"));
    session.rollback();
  }

  public class TestHook extends EntityHookAbstract {

    public int readCount;

    public TestHook(DatabaseSession session) {
      super(session);
    }

    @Override
    public void onEntityRead(Entity entity) {
      readCount++;
    }
  }
}
