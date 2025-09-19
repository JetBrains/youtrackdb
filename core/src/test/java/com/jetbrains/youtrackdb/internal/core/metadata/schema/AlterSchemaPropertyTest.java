package com.jetbrains.youtrackdb.internal.core.metadata.schema;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.api.exception.SchemaException;
import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import org.junit.Assert;
import org.junit.Test;

public class AlterSchemaPropertyTest extends DbTestBase {

  @Test
  public void testPropertyRenaming() {
    Schema schema = session.getMetadata().getSlowMutableSchema();
    var classA = schema.createClass("TestPropertyRenaming");
    var property = classA.createProperty("propertyOld", PropertyType.STRING);
    assertEquals(property, classA.getProperty("propertyOld"));
    assertNull(classA.getProperty("propertyNew"));
    property.setName("propertyNew");
    assertNull(classA.getProperty("propertyOld"));
    assertEquals(property, classA.getProperty("propertyNew"));
  }

  @Test
  public void testPropertyRenamingReload() {
    Schema schema = session.getMetadata().getSlowMutableSchema();
    var classA = schema.createClass("TestPropertyRenaming");
    var property = classA.createProperty("propertyOld", PropertyType.STRING);
    assertEquals(property, classA.getProperty("propertyOld"));
    assertNull(classA.getProperty("propertyNew"));
    property.setName("propertyNew");
    classA = schema.getClass("TestPropertyRenaming");
    assertNull(classA.getProperty("propertyOld"));
    assertEquals(property, classA.getProperty("propertyNew"));
  }

  @Test
  public void testLinkedMapPropertyLinkedType() {
    Schema schema = session.getMetadata().getSlowMutableSchema();
    var classA = schema.createClass("TestMapProperty");
    try {
      classA.createProperty("propertyMap", PropertyType.LINKMAP, PropertyType.STRING);
      fail("create linkmap property should not allow linked type");
    } catch (SchemaException e) {

    }

    var prop = classA.getProperty("propertyMap");
    assertNull(prop);
  }

  @Test
  public void testLinkedMapPropertyLinkedClass() {
    Schema schema = session.getMetadata().getSlowMutableSchema();
    var classA = schema.createClass("TestMapProperty");
    var classLinked = schema.createClass("LinkedClass");
    try {
      classA.createProperty("propertyString", PropertyType.STRING, classLinked);
      fail("create linkmap property should not allow linked type");
    } catch (SchemaException e) {

    }

    var prop = classA.getProperty("propertyString");
    assertNull(prop);
  }

  @Test
  public void testRemoveLinkedClass() {
    Schema schema = session.getMetadata().getSlowMutableSchema();
    var classA = schema.createClass("TestRemoveLinkedClass");
    var classLinked = schema.createClass("LinkedClass");
    var prop = classA.createProperty("propertyLink", PropertyType.LINK, classLinked);
    assertNotNull(prop.getLinkedClass());
    prop.setLinkedClass(null);
    assertNull(prop.getLinkedClass());
  }

  @Test
  public void testRemoveLinkedClassSQL() {
    Schema schema = session.getMetadata().getSlowMutableSchema();
    var classA = schema.createClass("TestRemoveLinkedClass");
    var classLinked = schema.createClass("LinkedClass");
    var prop = classA.createProperty("propertyLink", PropertyType.LINK, classLinked);
    assertNotNull(prop.getLinkedClass());
    session.execute("alter property TestRemoveLinkedClass.propertyLink linkedclass null").close();
    assertNull(prop.getLinkedClass());
  }

  @Test
  public void testMax() {
    Schema schema = session.getMetadata().getSlowMutableSchema();
    var classA = schema.createClass("TestWrongMax");
    var prop = classA.createProperty("dates", PropertyType.EMBEDDEDLIST,
        PropertyType.DATE);

    session.execute("alter property TestWrongMax.dates max 2016-05-25").close();

    try {
      session.execute("alter property TestWrongMax.dates max '2016-05-25'").close();
      Assert.fail();
    } catch (Exception e) {
    }
  }

  @Test
  public void testAlterPropertyWithDot() {

    Schema schema = session.getMetadata().getSlowMutableSchema();
    session.execute("create class testAlterPropertyWithDot").close();
    session.execute("create property testAlterPropertyWithDot.`a.b` STRING").close();
    Assert.assertNotNull(schema.getClass("testAlterPropertyWithDot").getProperty("a.b"));
    session.execute("alter property testAlterPropertyWithDot.`a.b` name c").close();
    Assert.assertNull(schema.getClass("testAlterPropertyWithDot").getProperty("a.b"));
    Assert.assertNotNull(schema.getClass("testAlterPropertyWithDot").getProperty("c"));
  }

  @Test
  public void testAlterCustomAttributeInProperty() {
    Schema schema = session.getMetadata().getSlowMutableSchema();
    var oClass = schema.createClass("TestCreateCustomAttributeClass");
    var property = oClass.createProperty("property", PropertyType.STRING);

    property.setCustom("customAttribute", "value1");
    assertEquals("value1", property.getCustom("customAttribute"));

    property.setCustom("custom.attribute", "value2");
    assertEquals("value2", property.getCustom("custom.attribute"));
  }
}
