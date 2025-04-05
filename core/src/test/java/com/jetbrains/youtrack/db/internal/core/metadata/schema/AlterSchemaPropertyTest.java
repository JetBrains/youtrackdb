package com.jetbrains.youtrack.db.internal.core.metadata.schema;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import com.jetbrains.youtrack.db.api.exception.SchemaException;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.api.schema.SchemaProperty;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import org.junit.Assert;
import org.junit.Test;

public class AlterSchemaPropertyTest extends DbTestBase {

  @Test
  public void testPropertyRenaming() {
    Schema schema = db.getMetadata().getSchema();
    SchemaClass classA = schema.createClass("TestPropertyRenaming");
    SchemaProperty property = classA.createProperty(db, "propertyOld", PropertyType.STRING);
    assertEquals(property, classA.getProperty(db, "propertyOld"));
    assertNull(classA.getProperty(db, "propertyNew"));
    property.setName(db, "propertyNew");
    assertNull(classA.getProperty(db, "propertyOld"));
    assertEquals(property, classA.getProperty(db, "propertyNew"));
  }

  @Test
  public void testPropertyRenamingReload() {
    Schema schema = db.getMetadata().getSchema();
    SchemaClass classA = schema.createClass("TestPropertyRenaming");
    SchemaProperty property = classA.createProperty(db, "propertyOld", PropertyType.STRING);
    assertEquals(property, classA.getProperty(db, "propertyOld"));
    assertNull(classA.getProperty(db, "propertyNew"));
    property.setName(db, "propertyNew");
    classA = schema.getClass("TestPropertyRenaming");
    assertNull(classA.getProperty(db, "propertyOld"));
    assertEquals(property, classA.getProperty(db, "propertyNew"));
  }

  @Test
  public void testLinkedMapPropertyLinkedType() {
    Schema schema = db.getMetadata().getSchema();
    SchemaClass classA = schema.createClass("TestMapProperty");
    try {
      classA.createProperty(db, "propertyMap", PropertyType.LINKMAP, PropertyType.STRING);
      fail("create linkmap property should not allow linked type");
    } catch (SchemaException e) {

    }

    SchemaProperty prop = classA.getProperty(db, "propertyMap");
    assertNull(prop);
  }

  @Test
  public void testLinkedMapPropertyLinkedClass() {
    Schema schema = db.getMetadata().getSchema();
    SchemaClass classA = schema.createClass("TestMapProperty");
    SchemaClass classLinked = schema.createClass("LinkedClass");
    try {
      classA.createProperty(db, "propertyString", PropertyType.STRING, classLinked);
      fail("create linkmap property should not allow linked type");
    } catch (SchemaException e) {

    }

    SchemaProperty prop = classA.getProperty(db, "propertyString");
    assertNull(prop);
  }

  @Test
  public void testRemoveLinkedClass() {
    Schema schema = db.getMetadata().getSchema();
    SchemaClass classA = schema.createClass("TestRemoveLinkedClass");
    SchemaClass classLinked = schema.createClass("LinkedClass");
    SchemaProperty prop = classA.createProperty(db, "propertyLink", PropertyType.LINK, classLinked);
    assertNotNull(prop.getLinkedClass(db));
    prop.setLinkedClass(db, null);
    assertNull(prop.getLinkedClass(db));
  }

  @Test
  public void testRemoveLinkedClassSQL() {
    Schema schema = db.getMetadata().getSchema();
    SchemaClass classA = schema.createClass("TestRemoveLinkedClass");
    SchemaClass classLinked = schema.createClass("LinkedClass");
    SchemaProperty prop = classA.createProperty(db, "propertyLink", PropertyType.LINK, classLinked);
    assertNotNull(prop.getLinkedClass(db));
    db.command("alter property TestRemoveLinkedClass.propertyLink linkedclass null").close();
    assertNull(prop.getLinkedClass(db));
  }

  @Test
  public void testMax() {
    Schema schema = db.getMetadata().getSchema();
    SchemaClass classA = schema.createClass("TestWrongMax");
    SchemaProperty prop = classA.createProperty(db, "dates", PropertyType.EMBEDDEDLIST,
        PropertyType.DATE);

    db.command("alter property TestWrongMax.dates max 2016-05-25").close();

    try {
      db.command("alter property TestWrongMax.dates max '2016-05-25'").close();
      Assert.fail();
    } catch (Exception e) {
    }
  }

  @Test
  public void testAlterPropertyWithDot() {

    Schema schema = db.getMetadata().getSchema();
    db.command("create class testAlterPropertyWithDot").close();
    db.command("create property testAlterPropertyWithDot.`a.b` STRING").close();
    Assert.assertNotNull(schema.getClass("testAlterPropertyWithDot").getProperty(db, "a.b"));
    db.command("alter property testAlterPropertyWithDot.`a.b` name c").close();
    Assert.assertNull(schema.getClass("testAlterPropertyWithDot").getProperty(db, "a.b"));
    Assert.assertNotNull(schema.getClass("testAlterPropertyWithDot").getProperty(db, "c"));
  }

  @Test
  public void testAlterCustomAttributeInProperty() {
    Schema schema = db.getMetadata().getSchema();
    SchemaClass oClass = schema.createClass("TestCreateCustomAttributeClass");
    SchemaProperty property = oClass.createProperty(db, "property", PropertyType.STRING);

    property.setCustom(db, "customAttribute", "value1");
    assertEquals("value1", property.getCustom("customAttribute"));

    property.setCustom(db, "custom.attribute", "value2");
    assertEquals("value2", property.getCustom("custom.attribute"));
  }
}
