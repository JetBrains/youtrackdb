package com.jetbrains.youtrackdb.internal.core.sql.executor;

import static com.jetbrains.youtrackdb.internal.core.sql.executor.ExecutionPlanPrintUtils.printExecutionPlan;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import org.junit.Assert;
import org.junit.Test;

/** Tests for the ALTER PROPERTY SQL statement execution. */
public class AlterSchemaPropertyStatementExecutionTest extends DbTestBase {

  @Test
  public void testSetProperty() {
    var className = "testSetProperty";
    var clazz = session.getMetadata().getSchema().createClass(className);
    var prop = clazz.createProperty("name", PropertyType.STRING);
    prop.setMax("15");

    var result = session.execute("alter property " + className + ".name max 30");
    printExecutionPlan(null, result);
    Object currentValue = prop.getMax();

    Assert.assertNotNull(result);
    Assert.assertTrue(result.hasNext());
    var next = result.next();
    Assert.assertNotNull(next);
    Assert.assertEquals("15", next.getProperty("oldValue"));
    Assert.assertEquals("30", currentValue);
    Assert.assertEquals(currentValue, next.getProperty("newValue"));
    result.close();
  }

  @Test
  public void testSetCustom() {
    var className = "testSetCustom";
    var clazz = session.getMetadata().getSchema().createClass(className);
    var prop = clazz.createProperty("name", PropertyType.STRING);
    prop.setCustom("foo", "bar");

    var result = session.execute("alter property " + className + ".name custom foo='baz'");
    printExecutionPlan(null, result);
    Object currentValue = prop.getCustom("foo");

    Assert.assertNotNull(result);
    Assert.assertTrue(result.hasNext());
    var next = result.next();
    Assert.assertNotNull(next);
    Assert.assertEquals("bar", next.getProperty("oldValue"));
    Assert.assertEquals("baz", currentValue);
    Assert.assertEquals(currentValue, next.getProperty("newValue"));
    result.close();
  }
}
