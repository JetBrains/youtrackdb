package com.jetbrains.youtrackdb.internal.core.sql.executor;

import static com.jetbrains.youtrackdb.internal.core.sql.executor.ExecutionPlanPrintUtils.printExecutionPlan;

import com.jetbrains.youtrackdb.api.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.api.schema.Schema;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import java.util.HashSet;
import java.util.Set;
import org.junit.Assert;
import org.junit.Test;

/**
 *
 */
public class CreateVertexStatementExecutionTest extends DbTestBase {

  @Test
  public void testInsertSet() {
    var className = "testInsertSet";
    Schema schema = session.getMetadata().getSchema();
    schema.createClass(className, schema.getClass("V"));

    session.begin();
    var result = session.execute("create vertex " + className + " set name = 'name1'");

    printExecutionPlan(result);
    for (var i = 0; i < 1; i++) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      Assert.assertNotNull(item);
      Assert.assertEquals("name1", item.getProperty("name"));
    }
    Assert.assertFalse(result.hasNext());

    result = session.query("select from " + className);
    for (var i = 0; i < 1; i++) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      Assert.assertNotNull(item);
      Assert.assertEquals("name1", item.getProperty("name"));
    }
    Assert.assertFalse(result.hasNext());
    session.commit();
    result.close();
  }

  @Test
  public void testInsertSetNoVertex() {
    var className = "testInsertSetNoVertex";
    Schema schema = session.getMetadata().getSchema();
    schema.createClass(className);

    try {
      var result = session.execute("create vertex " + className + " set name = 'name1'");
      Assert.fail();
    } catch (CommandExecutionException e1) {
    } catch (Exception e2) {
      Assert.fail();
    }
  }

  @Test
  public void testInsertValue() {
    var className = "testInsertValue";
    Schema schema = session.getMetadata().getSchema();
    schema.createClass(className, schema.getClass("V"));

    session.begin();
    var result =
        session.execute(
            "create vertex " + className + "  (name, surname) values ('name1', 'surname1')");

    printExecutionPlan(result);
    for (var i = 0; i < 1; i++) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      Assert.assertNotNull(item);
      Assert.assertEquals("name1", item.getProperty("name"));
      Assert.assertEquals("surname1", item.getProperty("surname"));
    }
    Assert.assertFalse(result.hasNext());

    result = session.query("select from " + className);
    for (var i = 0; i < 1; i++) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      Assert.assertNotNull(item);
      Assert.assertEquals("name1", item.getProperty("name"));
    }
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testInsertValue2() {
    var className = "testInsertValue2";
    Schema schema = session.getMetadata().getSchema();
    schema.createClass(className, schema.getClass("V"));

    session.begin();
    var result =
        session.execute(
            "create vertex "
                + className
                + "  (name, surname) values ('name1', 'surname1'), ('name2', 'surname2')");

    printExecutionPlan(result);

    for (var i = 0; i < 2; i++) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      Assert.assertNotNull(item);
      Assert.assertEquals("name" + (i + 1), item.getProperty("name"));
      Assert.assertEquals("surname" + (i + 1), item.getProperty("surname"));
    }
    Assert.assertFalse(result.hasNext());

    Set<String> names = new HashSet<>();
    names.add("name1");
    names.add("name2");
    result = session.query("select from " + className);
    for (var i = 0; i < 2; i++) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      Assert.assertNotNull(item);
      Assert.assertNotNull(item.getProperty("name"));
      names.remove(item.getProperty("name"));
      Assert.assertNotNull(item.getProperty("surname"));
    }
    Assert.assertFalse(result.hasNext());
    Assert.assertTrue(names.isEmpty());
    result.close();
    session.commit();
  }

  @Test
  public void testContent() {
    var className = "testContent";
    Schema schema = session.getMetadata().getSchema();
    schema.createClass(className, schema.getClass("V"));

    session.begin();
    var result =
        session.execute(
            "create vertex " + className + " content {'name':'name1', 'surname':'surname1'}");

    printExecutionPlan(result);
    for (var i = 0; i < 1; i++) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      Assert.assertNotNull(item);
      Assert.assertEquals("name1", item.getProperty("name"));
    }
    Assert.assertFalse(result.hasNext());

    result = session.query("select from " + className);
    for (var i = 0; i < 1; i++) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      Assert.assertNotNull(item);
      Assert.assertEquals("name1", item.getProperty("name"));
      Assert.assertEquals("surname1", item.getProperty("surname"));
    }
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }
}
