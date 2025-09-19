package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.api.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaClass;
import org.junit.Assert;
import org.junit.Test;

/**
 *
 */
public class DropSchemaPropertyStatementExecutionTest extends DbTestBase {

  @Test
  public void testPlain() {
    var className = "testPlain";
    var propertyName = "foo";
    Schema schema = session.getMetadata().getSlowMutableSchema();
    schema.createClass(className).createProperty(propertyName, PropertyType.STRING);

    Assert.assertNotNull(schema.getClass(className).getProperty(propertyName));
    var result = session.execute("drop property " + className + "." + propertyName);
    Assert.assertTrue(result.hasNext());
    var next = result.next();
    Assert.assertEquals("drop property", next.getProperty("operation"));
    Assert.assertFalse(result.hasNext());
    result.close();

    Assert.assertNull(schema.getClass(className).getProperty(propertyName));
  }

  @Test
  public void testDropIndexForce() {
    var className = "testDropIndexForce";
    var propertyName = "foo";
    Schema schema = session.getMetadata().getSlowMutableSchema();
    schema
        .createClass(className)
        .createProperty(propertyName, PropertyType.STRING)
        .createIndex(SchemaClass.INDEX_TYPE.NOTUNIQUE);

    Assert.assertNotNull(schema.getClass(className).getProperty(propertyName));
    var result = session.execute("drop property " + className + "." + propertyName + " force");
    for (var i = 0; i < 2; i++) {
      Assert.assertTrue(result.hasNext());
      result.next();
    }

    Assert.assertFalse(result.hasNext());

    result.close();

    Assert.assertNull(schema.getClass(className).getProperty(propertyName));
  }

  @Test
  public void testDropIndex() {

    var className = "testDropIndex";
    var propertyName = "foo";
    Schema schema = session.getMetadata().getSlowMutableSchema();
    schema
        .createClass(className)
        .createProperty(propertyName, PropertyType.STRING)
        .createIndex(SchemaClass.INDEX_TYPE.NOTUNIQUE);

    Assert.assertNotNull(schema.getClass(className).getProperty(propertyName));
    try {
      session.execute("drop property " + className + "." + propertyName);
      Assert.fail();
    } catch (CommandExecutionException e) {
    } catch (Exception e) {
      Assert.fail();
    }
  }
}
