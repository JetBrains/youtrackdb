package com.jetbrains.youtrackdb.internal.core.sql;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;

public class CommandExecutorSQLScriptTest extends DbTestBase {

  @Override
  public void beforeTest() throws Exception {
    super.beforeTest();

    session.execute("CREATE class foo").close();

    session.begin();
    session.execute("insert into foo (name, bar) values ('a', 1)").close();
    session.execute("insert into foo (name, bar) values ('b', 2)").close();
    session.execute("insert into foo (name, bar) values ('c', 3)").close();
    session.commit();

    session.activateOnCurrentThread();
  }

  @Test
  public void testQuery() {
    var script = """
        let $a = select from foo;
        return $a;
        """;
    session.begin();
    var qResult = session.computeScript("sql", script).toList();

    Assert.assertEquals(3, qResult.size());
    session.commit();
  }

  @Test
  public void testTx() {
    var script =
        """
            begin;
            let $a = insert into V set test = 'sql script test';
            commit retry 10;
            return $a;
            """;
    var result = session.computeScript("sql", script).toList();
    Assert.assertEquals(1, result.size());
  }

  @Test
  public void testReturnExpanded() {
    var script = new StringBuilder();
    script.append("begin;\n");
    script.append("let $a = insert into V set test = 'sql script test';\n");
    script.append("commit;\n");
    script.append("begin;\n");
    script.append("let $rid = $a.@rid;\n");
    script.append("let $b = select $current.toJSON() as json from $rid;\n");
    script.append("commit;");
    script.append("return $b;\n");

    var json = session.computeScript("sql", script.toString())
        .findFirst(result -> result.getString("json"));
    Assert.assertNotNull(json);

    session.begin();
    session.createOrLoadEntityFromJson(json);
    session.commit();

    script = new StringBuilder();
    script.append("let $a = select from V limit 2;\n");
    script.append("return $a.toJSON();\n");
    session.begin();
    json = session.computeScript("sql", script.toString()).findFirst(r -> r.getString("value"));

    Assert.assertNotNull(json);
    json = json.trim();
    Assert.assertTrue(!json.isEmpty() && json.charAt(0) == '[');
    Assert.assertEquals(']', json.charAt(json.length() - 1));
  }

  @Test
  public void testSleep() {
    var begin = System.currentTimeMillis();

    session.computeScript("sql", "sleep 500").close();

    Assert.assertTrue(System.currentTimeMillis() - begin >= 500);
  }

  @Test
  public void testConsoleLog() {
    var script = """
        LET $a = 'log';
        console.log 'This is a test of log for ${a}';""";
    session.computeScript("sql", script).close();
  }

  @Test
  public void testConsoleOutput() {
    var script = """
        LET $a = 'output';
        console.output 'This is a test of log for ${a}';""";
    session.computeScript("sql", script).close();
  }

  @Test
  public void testConsoleError() {
    var script = """
        LET $a = 'error';
        console.error 'This is a test of log for ${a}';""";
    session.computeScript("sql", script).close();
  }

  @Test
  public void testReturnObject() {
    final var result = session.computeScript("sql", "return [{ a: 'b' }, { c: 'd'}]");

    assertThat((Object) result).isNotNull();
    assertThat(result.hasNext()).isTrue();
    final var value = ((Collection<Object>) result.next().getProperty("value"));
    assertThat(result.hasNext()).isFalse();
    result.close();

    Assert.assertTrue(value.iterator().next() instanceof Map);
  }

  @Test
  @Ignore
  public void testIncrementAndLet() {

    var script =
        """
            CREATE CLASS TestCounter;
            begin;
            INSERT INTO TestCounter set weight = 3;
            LET counter = SELECT count(*) FROM TestCounter;
            UPDATE TestCounter INCREMENT weight = $counter[0].count RETURN AfTER @this;
            commit;
            """;
    var qResult = session.computeScript("sql", script);
    assertThat(
        qResult.findFirstEntity(e -> e.getInt("weight")).intValue()).isEqualTo(4);
  }

  @Test
  @Ignore
  public void testIncrementAndLetNewApi() {

    var script =
        """
            CREATE CLASS TestCounter;
            begin;
            INSERT INTO TestCounter set weight = 3;
            LET counter = SELECT count(*) FROM TestCounter;
            UPDATE TestCounter INCREMENT weight = $counter[0].count RETURN AfTER @this;
            commit;
            """;
    var qResult = session.computeScript("sql", script);

    assertThat(qResult.next().asEntity().<Long>getProperty("weight")).isEqualTo(4L);
  }

  @Test
  public void testIf1() {

    var script =
        """
            let $a = select 1 as one;
            if($a[0].one = 1){;
             return 'OK';
            };
            return 'FAIL';
            """;
    var qResult = session.computeScript("sql", script).stream().findFirst().orElseThrow();

    Assert.assertNotNull(qResult);
    Assert.assertEquals("OK", qResult.getProperty("value"));
  }

  @Test
  public void testIf2() {

    var script =
        """
            let $a = select 1 as one;
            if    ($a[0].one = 1)   {;
             return 'OK';
                 };     \s
            return 'FAIL';
            """;
    var qResult = session.computeScript("sql", script).stream().findFirst().orElseThrow();

    Assert.assertNotNull(qResult);
    Assert.assertEquals("OK", qResult.getProperty("value"));
  }

  @Test
  public void testIf3() {
    var qResult =
        session.computeScript(

                "sql",
                "let $a = select 1 as one; if($a[0].one = 1){return 'OK';}return 'FAIL';").stream()
            .findFirst().orElseThrow();
    Assert.assertNotNull(qResult);
    Assert.assertEquals("OK", qResult.getProperty("value"));
  }

  @Test
  public void testNestedIf2() {

    var script =
        """
            let $a = select 1 as one;
            if($a[0].one = 1){;
                if($a[0].one = 'zz'){;
                  return 'FAIL';
                };
              return 'OK';
            };
            return 'FAIL';
            """;
    var qResult = session.computeScript("sql", script).stream().findFirst().orElseThrow();

    Assert.assertNotNull(qResult);
    Assert.assertEquals("OK", qResult.getProperty("value"));
  }

  @Test
  public void testNestedIf3() {

    var script =
        """
            let $a = select 1 as one;
            if($a[0].one = 'zz'){;
                if($a[0].one = 1){;
                  return 'FAIL';
                };
              return 'FAIL';
            };
            return 'OK';
            """;
    var qResult = session.computeScript("sql", script).stream().findFirst().orElseThrow();

    Assert.assertNotNull(qResult);
    Assert.assertEquals("OK", qResult.getProperty("value"));
  }

  @Test
  public void testIfRealQuery() {

    var script =
        """
            let $a = select from foo;
            if($a is not null and $a.size() = 3){
              return $a;
            }
            return 'FAIL';
            """;
    session.begin();
    var qResult = session.computeScript("sql", script).toList();

    Assert.assertNotNull(qResult);
    Assert.assertEquals(3, qResult.size());
    session.commit();
  }

  @Test
  public void testIfMultipleStatements() {

    var script =
        """
            let $a = select 1 as one;
            if($a[0].one = 1){;
              let $b = select 'OK' as ok;
              return $b[0].ok;
            };
            return 'FAIL';
            """;
    var qResult = session.computeScript("sql", script).stream().findFirst().orElseThrow();

    Assert.assertNotNull(qResult);
    Assert.assertEquals("OK", qResult.getProperty("value"));
  }

  @Test
  @Ignore
  public void testScriptSubContext() {

    var script =
        """
            let $a = select from foo limit 1;
            select from (traverse doesnotexist from $a);
            """;
    var qResult = session.computeScript("sql", script);

    Assert.assertNotNull(qResult);
    var iterator = qResult.toList().iterator();
    Assert.assertTrue(iterator.hasNext());
    iterator.next();
    Assert.assertFalse(iterator.hasNext());
  }

  @Test
  public void testSemicolonInString() {
    // testing parsing problem
    var script =
        """
            let $a = select 'foo ; bar' as one;
            let $b = select 'foo \\'; bar' as one;
            let $a = select "foo ; bar" as one;
            let $b = select "foo \\"; bar" as one;
            """;
    session.computeScript("sql", script).close();
  }

  @Test
  public void testQuotedRegex() {
    // issue #4996 (simplified)
    session.execute("CREATE CLASS QuotedRegex2").close();
    var batch = "begin;INSERT INTO QuotedRegex2 SET regexp=\"'';\";commit;";

    session.computeScript("sql", batch).close();
    var result = session.query("SELECT FROM QuotedRegex2");
    Assert.assertTrue(result.hasNext());
    var doc = result.next();
    Assert.assertEquals("'';", doc.getString("regexp"));
    Assert.assertFalse(result.hasNext());
    result.close();
  }

  @Test
  public void testParameters1() {
    var className = "testParameters1";
    session.createVertexClass(className);
    var script =
        "BEGIN;"
            + "LET $a = CREATE VERTEX "
            + className
            + " SET name = :name;"
            + "LET $b = CREATE VERTEX "
            + className
            + " SET name = :_name2;"
            + "LET $edge = CREATE EDGE E from $a to $b;"
            + "COMMIT;"
            + "RETURN $edge;";

    var map = new HashMap<String, Object>();
    map.put("name", "bozo");
    map.put("_name2", "bozi");

    var rs = session.computeScript("sql", script, map);
    rs.close();

    rs = session.query("SELECT FROM " + className + " WHERE name = ?", "bozo");

    Assert.assertTrue(rs.hasNext());
    rs.next();
    rs.close();
  }

  @Test
  public void testPositionalParameters() {
    var className = "testPositionalParameters";
    session.createVertexClass(className);
    var script =
        "BEGIN;"
            + "LET $a = CREATE VERTEX "
            + className
            + " SET name = ?;"
            + "LET $b = CREATE VERTEX "
            + className
            + " SET name = ?;"
            + "LET $edge = CREATE EDGE E from $a to $b;"
            + "COMMIT;"
            + "RETURN $edge;";

    var rs = session.computeScript("sql", script, "bozo", "bozi");
    rs.close();

    rs = session.query("SELECT FROM " + className + " WHERE name = ?", "bozo");

    Assert.assertTrue(rs.hasNext());
    rs.next();
    rs.close();
  }
}
