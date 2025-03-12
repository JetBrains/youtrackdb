package com.jetbrains.youtrack.db.internal.core.sql.executor;

import static com.jetbrains.youtrack.db.internal.core.sql.executor.ExecutionPlanPrintUtils.printExecutionPlan;

import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import org.junit.Assert;
import org.junit.Test;

/**
 *
 */
public class AlterSchemaPropertyStatementExecutionTest extends DbTestBase {

  @Test
  public void testSetProperty() {
    var className = "testSetProperty";
    var clazz = session.getMetadata().getSchema().createClass(className);
    var prop = clazz.createProperty("name", PropertyType.STRING);
    prop.setMax("15");

    var result = session.command("alter property " + className + ".name max 30");
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

    var result = session.command("alter property " + className + ".name custom foo='baz'");
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
