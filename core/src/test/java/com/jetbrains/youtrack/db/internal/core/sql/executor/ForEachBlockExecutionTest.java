package com.jetbrains.youtrack.db.internal.core.sql.executor;

import com.jetbrains.youtrack.db.internal.DbTestBase;
import org.junit.Assert;
import org.junit.Test;

/**
 *
 */
public class ForEachBlockExecutionTest extends DbTestBase {

  @Test
  public void testPlain() {

    var className = "testPlain";

    session.createClass(className);

    var script = "";
    script += "FOREACH ($val in [1,2,3]){\n";
    script += "  insert into " + className + " set value = $val;\n";
    script += "}";
    script += "SELECT FROM " + className;

    session.begin();
    var results = session.execute("sql", script);

    var tot = 0;
    var sum = 0;
    while (results.hasNext()) {
      var item = results.next();
      sum += item.<Integer>getProperty("value");
      tot++;
    }
    Assert.assertEquals(3, tot);
    Assert.assertEquals(6, sum);
    results.close();
    session.commit();
  }

  @Test
  public void testReturn() {
    var className = "testReturn";

    session.createClass(className);

    session.begin();
    var script = "";
    script += "FOREACH ($val in [1,2,3]){\n";
    script += "  insert into " + className + " set value = $val;\n";
    script += "  if($val = 2){\n";
    script += "    RETURN;\n";
    script += "  }\n";
    script += "}";

    var results = session.execute("sql", script);
    results.close();
    results = session.query("SELECT FROM " + className);

    var tot = 0;
    var sum = 0;
    while (results.hasNext()) {
      var item = results.next();
      sum += item.<Integer>getProperty("value");
      tot++;
    }
    Assert.assertEquals(2, tot);
    Assert.assertEquals(3, sum);
    results.close();
    session.commit();
  }
}
