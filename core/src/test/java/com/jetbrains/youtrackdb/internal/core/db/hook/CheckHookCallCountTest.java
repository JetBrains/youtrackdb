package com.jetbrains.youtrackdb.internal.core.db.hook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.jetbrains.youtrackdb.api.common.BasicDatabaseSession;
import com.jetbrains.youtrackdb.api.gremlin.__;
import com.jetbrains.youtrackdb.api.gremlin.embedded.domain.YTDBSchemaIndex.IndexType;
import com.jetbrains.youtrackdb.api.record.Entity;
import com.jetbrains.youtrackdb.api.record.EntityHookAbstract;
import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.UUID;
import org.junit.Test;

public class CheckHookCallCountTest extends DbTestBase {

  private final String CLASS_NAME = "Data";
  private final String FIELD_ID = "ID";
  private final String FIELD_STATUS = "STATUS";
  private final String STATUS = "processed";

  @Test
  public void testMultipleCallHook() {
    graph.autoExecuteInTx(g ->
        g.createSchemaClass(CLASS_NAME,
            __.createSchemaProperty(FIELD_ID, PropertyType.STRING),
            __.createSchemaProperty(FIELD_STATUS, PropertyType.STRING)
                .createPropertyIndex("IDX", IndexType.NOT_UNIQUE)
        )
    );

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

    graph.autoExecuteInTx(g ->
        g.createSchemaClass("TestInHook",
            __.createSchemaProperty("a", PropertyType.INTEGER),
            __.createSchemaProperty("b", PropertyType.INTEGER),
            __.createSchemaProperty("c", PropertyType.INTEGER)
        )
    );

    session.begin();
    var doc = (EntityImpl) session.newEntity("TestInHook");
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
        new EntityHookAbstract() {

          {
            setIncludeClasses("TestInHook");
          }

          @Override
          public void onAfterEntityCreate(Entity entity) {
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
    doc = (EntityImpl) session.newEntity("TestInHook");
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

    public TestHook(BasicDatabaseSession session) {
      super();
    }

    @Override
    public void onEntityRead(Entity entity) {
      readCount++;
    }
  }
}
