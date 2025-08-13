package com.jetbrains.youtrackdb.internal.core.sql.executor;

import static com.jetbrains.youtrackdb.internal.core.sql.executor.ExecutionPlanPrintUtils.printExecutionPlan;

import com.jetbrains.youtrackdb.api.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.api.record.RID;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import org.junit.Assert;
import org.junit.Test;

/**
 *
 */
public class DeleteStatementExecutionTest extends DbTestBase {

  @Test
  public void testSimple() {
    var className = "testSimple";
    session.getMetadata().getSchema().createClass(className);
    for (var i = 0; i < 10; i++) {
      session.begin();
      var doc = session.newInstance(className);
      doc.setProperty("name", "name" + i);

      session.commit();
      if (i == 4) {
        System.out.println("deleted");
      }
      System.out.println(doc.getIdentity());
    }

    session.begin();
    try (var rs = session.query("select from " + className)) {
      Assert.assertEquals(10, rs.stream().count());
    }
    session.commit();

    session.begin();
    var result = session.execute("delete from  " + className + " where name = 'name4'");
    printExecutionPlan(result);
    for (var i = 0; i < 1; i++) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      Assert.assertNotNull(item);
      Assert.assertEquals((Object) 1L, item.getProperty("count"));
    }
    Assert.assertFalse(result.hasNext());
    session.commit();

    System.out.println("-------------------");

    session.begin();
    result = session.query("select from " + className);
    for (var i = 0; i < 9; i++) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      System.out.println(item.getIdentity());
      Assert.assertNotNull(item);
      Assert.assertNotEquals("name4", item.getProperty("name"));
    }
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testUnsafe1() {
    var className = "testUnsafe1";
    var v = session.getMetadata().getSchema().getClass("V");
    if (v == null) {
      session.getMetadata().getSchema().createClass("V");
    }
    session.getMetadata().getSchema().createClass(className, v);
    for (var i = 0; i < 10; i++) {
      session.begin();
      var doc = session.newVertex(className);
      doc.setProperty("name", "name" + i);
      session.commit();
    }

    try {
      session.begin();
      session.execute("delete from  " + className + " where name = 'name4'");
      Assert.fail();
    } catch (CommandExecutionException ex) {
    }
    session.rollback();
  }

  @Test
  public void testUnsafe2() {
    var className = "testUnsafe2";
    var v = session.getMetadata().getSchema().getClass("V");
    if (v == null) {
      session.getMetadata().getSchema().createClass("V");
    }
    session.getMetadata().getSchema().createClass(className, v);
    for (var i = 0; i < 10; i++) {
      session.begin();
      var vertex = session.newVertex(className);
      vertex.setProperty("name", "name" + i);

      session.commit();
    }

    session.begin();
    var result = session.execute("delete from  " + className + " where name = 'name4' unsafe");

    printExecutionPlan(result);
    for (var i = 0; i < 1; i++) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      Assert.assertNotNull(item);
      Assert.assertEquals((Object) 1L, item.getProperty("count"));
    }
    Assert.assertFalse(result.hasNext());
    session.commit();

    session.begin();
    result = session.query("select from " + className);
    for (var i = 0; i < 9; i++) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      Assert.assertNotNull(item);
      Assert.assertNotEquals("name4", item.getProperty("name"));
    }
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testReturnBefore() {
    var className = "testReturnBefore";
    session.getMetadata().getSchema().createClass(className);
    RID fourthId = null;

    for (var i = 0; i < 10; i++) {
      session.begin();
      EntityImpl doc = session.newInstance(className);
      doc.setProperty("name", "name" + i);
      if (i == 4) {
        fourthId = doc.getIdentity();
      }

      session.commit();
    }

    session.begin();
    var result =
        session.execute("delete from  " + className + " return before where name = 'name4' ");
    printExecutionPlan(result);
    for (var i = 0; i < 1; i++) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      Assert.assertNotNull(item);
      Assert.assertEquals(fourthId, item.getIdentity());
    }
    Assert.assertFalse(result.hasNext());
    session.commit();

    session.begin();
    result = session.query("select from " + className);
    for (var i = 0; i < 9; i++) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      Assert.assertNotNull(item);
      Assert.assertNotEquals("name4", item.getProperty("name"));
    }
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testLimit() {
    var className = "testLimit";
    session.getMetadata().getSchema().createClass(className);
    for (var i = 0; i < 10; i++) {
      session.begin();
      EntityImpl doc = session.newInstance(className);
      doc.setProperty("name", "name" + i);

      session.commit();
    }
    session.begin();
    var result = session.execute("delete from  " + className + " limit 5");
    printExecutionPlan(result);
    for (var i = 0; i < 1; i++) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      Assert.assertNotNull(item);
      Assert.assertEquals((Object) 5L, item.getProperty("count"));
    }
    Assert.assertFalse(result.hasNext());
    session.commit();

    session.begin();
    result = session.query("select from " + className);
    for (var i = 0; i < 5; i++) {
      Assert.assertTrue(result.hasNext());
      var item = result.next();
      Assert.assertNotNull(item);
    }
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }
}
