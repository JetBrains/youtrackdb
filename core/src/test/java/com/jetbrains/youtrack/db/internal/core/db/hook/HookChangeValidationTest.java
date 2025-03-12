package com.jetbrains.youtrack.db.internal.core.db.hook;

import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrack.db.api.exception.ValidationException;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.core.hook.DocumentHookAbstract;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import org.junit.Assert;
import org.junit.Test;

public class HookChangeValidationTest extends DbTestBase {

  @Test
  public void testHookCreateChangeTx() {

    Schema schema = session.getMetadata().getSchema();
    var classA = schema.createClass("TestClass");
    classA.createProperty("property1", PropertyType.STRING).setNotNull(true);
    classA.createProperty("property2", PropertyType.STRING).setReadonly(true);
    classA.createProperty("property3", PropertyType.STRING).setMandatory(true);
    session.registerHook(
        new DocumentHookAbstract(session) {
          @Override
          public RESULT onRecordBeforeCreate(EntityImpl entity) {
            entity.removeField("property1");
            entity.removeField("property2");
            entity.removeField("property3");
            return RESULT.RECORD_CHANGED;
          }

          @Override
          public RESULT onRecordBeforeUpdate(EntityImpl entity) {
            return RESULT.RECORD_NOT_CHANGED;
          }

        });
    session.begin();
    var doc = (EntityImpl) session.newEntity(classA);
    doc.field("property1", "value1-create");
    doc.field("property2", "value2-create");
    doc.field("property3", "value3-create");
    try {

      session.commit();
      Assert.fail("The document save should fail for validation exception");
    } catch (ValidationException ex) {

    }
  }

  @Test
  public void testHookUpdateChangeTx() {

    Schema schema = session.getMetadata().getSchema();
    var classA = schema.createClass("TestClass");
    classA.createProperty("property1", PropertyType.STRING).setNotNull(true);
    classA.createProperty("property2", PropertyType.STRING).setReadonly(true);
    classA.createProperty("property3", PropertyType.STRING).setMandatory(true);
    session.registerHook(
        new DocumentHookAbstract(session) {
          @Override
          public RESULT onRecordBeforeCreate(EntityImpl entity) {
            return RESULT.RECORD_NOT_CHANGED;
          }

          @Override
          public RESULT onRecordBeforeUpdate(EntityImpl entity) {
            entity.removeField("property1");
            entity.removeField("property2");
            entity.removeField("property3");
            return RESULT.RECORD_CHANGED;
          }

        });

    session.begin();
    var doc = (EntityImpl) session.newEntity(classA);
    doc.field("property1", "value1-create");
    doc.field("property2", "value2-create");
    doc.field("property3", "value3-create");

    session.commit();

    session.begin();
    try {
      doc = session.bindToSession(doc);
      assertEquals("value1-create", doc.field("property1"));
      assertEquals("value2-create", doc.field("property2"));
      assertEquals("value3-create", doc.field("property3"));

      doc.field("property1", "value1-update");
      doc.field("property2", "value2-update");

      session.commit();
      Assert.fail("The document save should fail for validation exception");
    } catch (ValidationException ex) {
    }
  }
}
