package com.jetbrains.youtrackdb.internal.core.db.hook;

import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrackdb.api.exception.ValidationException;
import com.jetbrains.youtrackdb.api.record.Entity;
import com.jetbrains.youtrackdb.api.record.EntityHookAbstract;
import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import org.junit.Assert;
import org.junit.Test;

public class HookChangeValidationTest extends DbTestBase {

  @Test
  public void testBeforeHookCreateChangeTx() {
    Schema schema = session.getMetadata().getSchema();
    var classA = schema.createClass("TestClass");
    classA.createProperty("property1", PropertyType.STRING).setNotNull(true);
    classA.createProperty("property2", PropertyType.STRING).setReadonly(true);
    classA.createProperty("property3", PropertyType.STRING).setMandatory(true);
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
    var doc = (EntityImpl) session.newEntity(classA);
    doc.setProperty("property1", "value1-create");
    doc.setProperty("property2", "value2-create");
    doc.setProperty("property3", "value3-create");
    try {
      session.commit();
      Assert.fail("The document save should fail with illegal state exception");
    } catch (IllegalStateException ex) {
    }
  }

  @Test
  public void testAfterHookCreateChangeTx() {
    Schema schema = session.getMetadata().getSchema();
    var classA = schema.createClass("TestClass");
    classA.createProperty("property1", PropertyType.STRING).setNotNull(true);
    classA.createProperty("property2", PropertyType.STRING).setReadonly(true);
    classA.createProperty("property3", PropertyType.STRING).setMandatory(true);
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
    var doc = (EntityImpl) session.newEntity(classA);
    doc.setProperty("property1", "value1-create");
    doc.setProperty("property2", "value2-create");
    doc.setProperty("property3", "value3-create");
    try {

      session.commit();
      Assert.fail("The document save should fail for validation exception");
    } catch (ValidationException ex) {
    }
  }

  @Test
  public void testBeforeHookUpdateChangeTx() {
    Schema schema = session.getMetadata().getSchema();
    var classA = schema.createClass("TestClass");
    classA.createProperty("property1", PropertyType.STRING).setNotNull(true);
    classA.createProperty("property2", PropertyType.STRING).setReadonly(true);
    classA.createProperty("property3", PropertyType.STRING).setMandatory(true);
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
    var doc = (EntityImpl) session.newEntity(classA);
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
    } catch (IllegalStateException ex) {
    }
  }

  @Test
  public void testAfterHookUpdateChangeTx() {
    Schema schema = session.getMetadata().getSchema();
    var classA = schema.createClass("TestClass");
    classA.createProperty("property1", PropertyType.STRING).setNotNull(true);
    classA.createProperty("property2", PropertyType.STRING).setReadonly(true);
    classA.createProperty("property3", PropertyType.STRING).setMandatory(true);
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
    var doc = (EntityImpl) session.newEntity(classA);
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
    } catch (ValidationException ex) {
    }
  }
}
