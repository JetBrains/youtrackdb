package com.jetbrains.youtrackdb.internal.core.command;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests SQL script execution including plain scripts and parameterized scripts.
 */
public class SqlScriptExecutorTest extends DbTestBase {

  @Test
  public void testPlain() {
    session.begin();
    var script = "insert into V set name ='a';\n";
    script += "insert into V set name ='b';\n";
    script += "insert into V set name ='c';\n";
    script += "insert into V set name ='d';\n";
    script += "select from v;";

    var result = session.computeScript("sql", script);
    var list =
        result.stream().map(x -> x.getProperty("name")).toList();
    result.close();

    Assert.assertTrue(list.contains("a"));
    Assert.assertTrue(list.contains("b"));
    Assert.assertTrue(list.contains("c"));
    Assert.assertTrue(list.contains("d"));
    Assert.assertEquals(4, list.size());
    session.commit();
  }

  @Test
  public void testWithPositionalParams() {
    session.begin();
    var script = "insert into V set name ='a';\n";
    script += "insert into V set name ='b';\n";
    script += "insert into V set name ='c';\n";
    script += "insert into V set name ='d';\n";
    script += "select from v where name = ?;\n";

    var result = session.computeScript("sql", script, "a");

    var list =
        result.stream().map(x -> x.getProperty("name")).collect(Collectors.toList());
    result.close();
    Assert.assertTrue(list.contains("a"));

    Assert.assertEquals(1, list.size());
    session.commit();
  }

  @Test
  public void testWithNamedParams() {
    session.begin();
    var script = "insert into V set name ='a';\n";
    script += "insert into V set name ='b';\n";
    script += "insert into V set name ='c';\n";
    script += "insert into V set name ='d';\n";
    script += "select from v where name = :name;";

    Map<String, Object> params = new HashMap<String, Object>();
    params.put("name", "a");

    var result = session.computeScript("sql", script, params);
    var list =
        result.stream().map(x -> x.getProperty("name")).toList();
    result.close();

    Assert.assertTrue(list.contains("a"));

    Assert.assertEquals(1, list.size());
    session.commit();
  }

  @Test
  public void testMultipleCreateEdgeOnTheSameLet() {
    session.begin();
    var script = "let $v1 = create vertex v set name = 'Foo';\n";
    script += "let $v2 = create vertex v set name = 'Bar';\n";
    script += "create edge from $v1 to $v2;\n";
    script += "let $v3 = create vertex v set name = 'Baz';\n";
    script += "create edge from $v1 to $v3;\n";

    var result = session.computeScript("sql", script);
    result.close();

    result = session.query("SELECT expand(out()) FROM V WHERE name ='Foo'");
    Assert.assertEquals(2, result.stream().count());
    result.close();
    session.commit();
  }
}
