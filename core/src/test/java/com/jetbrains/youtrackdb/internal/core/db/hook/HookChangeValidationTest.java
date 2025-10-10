package com.jetbrains.youtrackdb.internal.core.db.hook;

import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrackdb.api.exception.ValidationException;
import com.jetbrains.youtrackdb.api.record.Entity;
import com.jetbrains.youtrackdb.api.record.EntityHookAbstract;
import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import org.junit.Assert;
import org.junit.Test;

public class HookChangeValidationTest extends DbTestBase {
  @Test
  public void testBeforeHookCreateChangeTx() {
    graph.autoExecuteInTx(g ->
        g.addSchemaClass("TestClass").as("c").
            addSchemaProperty("property1", PropertyType.STRING).notNullAttr(true).select("c").
            addSchemaProperty("property2", PropertyType.STRING).readOnlyAttr(true).select("c").
            addSchemaProperty("property3", PropertyType.STRING).mandatoryAttr(true)
    );

    session.registerHook(
        new EntityHookAbstract() {
          @Override
          public void onBeforeEntityCreate(Entity entity) {
            entity.removeProperty("property1");
            entity.removeProperty("property2");
            entity.removeProperty("property3");
          }
        });
    session.begin();
    var doc = (EntityImpl) session.newEntity("TestClass");
    doc.setProperty("property1", "value1-create");
    doc.setProperty("property2", "value2-create");
    doc.setProperty("property3", "value3-create");
    try {
      session.commit();
      Assert.fail("The document save should fail with illegal state exception");
    } catch (IllegalStateException ignored) {
    }
  }

  @Test
  public void testAfterHookCreateChangeTx() {
    graph.autoExecuteInTx(g ->
        g.addSchemaClass("TestClass").as("cl").
            addSchemaProperty("property1", PropertyType.STRING).notNullAttr(true).select("cl").
            addSchemaProperty("property2", PropertyType.STRING).readOnlyAttr(true).select("cl").
            addSchemaProperty("property3", PropertyType.STRING).mandatoryAttr(true)
    );
    session.registerHook(
        new EntityHookAbstract() {
          @Override
          public void onAfterEntityCreate(Entity entity) {
            entity.removeProperty("property1");
            entity.removeProperty("property2");
            entity.removeProperty("property3");
          }
        });
    session.begin();
    var doc = (EntityImpl) session.newEntity("TestClass");
    doc.setProperty("property1", "value1-create");
    doc.setProperty("property2", "value2-create");
    doc.setProperty("property3", "value3-create");
    try {

      session.commit();
      Assert.fail("The document save should fail for validation exception");
    } catch (ValidationException ignored) {
    }
  }

  @Test
  public void testBeforeHookUpdateChangeTx() {

    graph.autoExecuteInTx(g ->
        g.addSchemaClass("TestClass").as("cl").
            addSchemaProperty("property1", PropertyType.STRING).notNullAttr(true).select("cl").
            addSchemaProperty("property2", PropertyType.STRING).readOnlyAttr(true).select("cl").
            addSchemaProperty("property3", PropertyType.STRING).mandatoryAttr(true)
    );
    session.registerHook(
        new EntityHookAbstract() {
          @Override
          public void onBeforeEntityUpdate(Entity entity) {
            entity.removeProperty("property1");
            entity.removeProperty("property2");
            entity.removeProperty("property3");
          }
        });

    session.begin();
    var doc = (EntityImpl) session.newEntity("TestClass");
    doc.setProperty("property1", "value1-create");
    doc.setProperty("property2", "value2-create");
    doc.setProperty("property3", "value3-create");

    session.commit();

    session.begin();
    try {
      var activeTx = session.getActiveTransaction();
      doc = activeTx.load(doc);
      assertEquals("value1-create", doc.getProperty("property1"));
      assertEquals("value2-create", doc.getProperty("property2"));
      assertEquals("value3-create", doc.getProperty("property3"));

      doc.setProperty("property1", "value1-update");
      doc.setProperty("property2", "value2-update");

      session.commit();
      Assert.fail("The document save should fail with illegal exception");
    } catch (IllegalStateException ignored) {
    }
  }

  @Test
  public void testAfterHookUpdateChangeTx() {
    graph.autoExecuteInTx(g ->
        g.addSchemaClass("TestClass").as("cl").
            addSchemaProperty("property1", PropertyType.STRING).notNullAttr(true).select("cl").
            addSchemaProperty("property2", PropertyType.STRING).readOnlyAttr(true).select("cl").
            addSchemaProperty("property3", PropertyType.STRING).mandatoryAttr(true)
    );
    session.registerHook(
        new EntityHookAbstract() {
          @Override
          public void onAfterEntityUpdate(Entity entity) {
            entity.removeProperty("property1");
            entity.removeProperty("property2");
            entity.removeProperty("property3");
          }
        });

    session.begin();
    var doc = (EntityImpl) session.newEntity("TestClass");
    doc.setProperty("property1", "value1-create");
    doc.setProperty("property2", "value2-create");
    doc.setProperty("property3", "value3-create");

    session.commit();

    session.begin();
    try {
      var activeTx = session.getActiveTransaction();
      doc = activeTx.load(doc);
      assertEquals("value1-create", doc.getProperty("property1"));
      assertEquals("value2-create", doc.getProperty("property2"));
      assertEquals("value3-create", doc.getProperty("property3"));

      doc.setProperty("property1", "value1-update");
      doc.setProperty("property2", "value2-update");

      session.commit();
      Assert.fail("The document save should fail for validation exception");
    } catch (ValidationException ignored) {
    }
  }
}
