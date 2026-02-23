package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import org.junit.Assert;
import org.junit.Test;

/** Integration tests for SQL SELECT statement execution under high-volume workloads. */
public class SelectStatementExecutionTestIT extends DbTestBase {

  @Test
  public void stressTestNew() {
    var className = "stressTestNew";
    session.getMetadata().getSchema().createClass(className);
    for (var i = 0; i < 1000000; i++) {
      session.begin();
      var doc = session.newInstance(className);
      doc.setProperty("name", "name" + i);
      doc.setProperty("surname", "surname" + i);
      session.commit();
    }

    for (var run = 0; run < 5; run++) {
      var begin = System.nanoTime();
      session.begin();
      var result = session.query("select name from " + className + " where name <> 'name1' ");
      for (var i = 0; i < 999999; i++) {
        var item = result.next();
        var name = item.getProperty("name");
        Assert.assertNotEquals("name1", name);
      }
      Assert.assertFalse(result.hasNext());
      result.close();
      var end = System.nanoTime();
      System.out.println("new: " + ((end - begin) / 1000000));
      session.commit();
    }
  }
}
