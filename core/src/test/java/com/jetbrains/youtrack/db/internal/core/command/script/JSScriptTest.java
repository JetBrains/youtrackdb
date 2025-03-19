package com.jetbrains.youtrack.db.internal.core.command.script;

import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.common.io.IOUtils;
import com.jetbrains.youtrack.db.internal.core.db.YouTrackDBInternal;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import javax.script.ScriptException;
import org.junit.Assert;
import org.junit.Test;

/**
 *
 */
public class JSScriptTest extends DbTestBase {

  @Test
  public void jsSimpleTest() {
    var resultSet = session.runScript("javascript", "'foo'");
    Assert.assertTrue(resultSet.hasNext());
    var result = resultSet.next();
    String ret = result.getProperty("value");
    Assert.assertEquals("foo", ret);
  }

  @Test
  public void jsQueryTest() {
    session.begin();
    var script = "db.query('select from OUser')";
    var resultSet = session.runScript("javascript", script);
    Assert.assertTrue(resultSet.hasNext());

    var results = resultSet.stream().toList();
    Assert.assertEquals(2, results.size()); // no default users anymore, 'admin' created

    results.stream()
        .map(Result::asEntity)
        .forEach(
            oElement -> {
              Assert.assertEquals("OUser", oElement.getSchemaClassName());
            });
    session.commit();
  }

  @Test
  public void jsScriptTest() throws IOException {
    var stream = ClassLoader.getSystemResourceAsStream("fixtures/scriptTest.js");
    session.begin();
    var resultSet = session.runScript("javascript", IOUtils.readStreamAsString(stream));
    Assert.assertTrue(resultSet.hasNext());

    var results = resultSet.stream().toList();
    Assert.assertEquals(1, results.size());

    var linkList = results.getFirst().getLinkList("value");
    linkList.stream()
        .map(identifiable -> identifiable.getEntity(session))
        .forEach(
            entity -> {
              Assert.assertEquals("OUser", entity.getSchemaClassName());
            });
    session.commit();
  }

  @Test
  public void jsScriptCountTest() throws IOException {
    var stream = ClassLoader.getSystemResourceAsStream("fixtures/scriptCountTest.js");
    var resultSet = session.runScript("javascript", IOUtils.readStreamAsString(stream));
    Assert.assertTrue(resultSet.hasNext());

    var results = resultSet.stream().toList();
    Assert.assertEquals(1, results.size());

    Number value = results.getFirst().getProperty("value");
    Assert.assertEquals(2, value.intValue()); // no default users anymore, 'admin' created
  }

  @Test
  public void jsSandboxTestWithJavaType() {
    try {
      session.runScript(
          "javascript", "var File = Java.type(\"java.io.File\");\n  File.pathSeparator;");

      Assert.fail("It should receive a class not found exception");
    } catch (RuntimeException e) {
      Assert.assertEquals(
          GlobalConfiguration.SCRIPT_POLYGLOT_USE_GRAAL.getValueAsBoolean()
              ? ScriptException.class
              : ClassNotFoundException.class,
          e.getCause().getClass());
    }
  }

  // @Test
  // THIS TEST WONT PASS WITH GRAALVM
  public void jsSandboxWithNativeTest() {
    var scriptManager = YouTrackDBInternal.extract(context).getScriptManager();
    try {
      scriptManager.addAllowedPackages(new HashSet<>(List.of("java.lang.System")));

      var resultSet =
          session.runScript(
              "javascript", "var System = Java.type('java.lang.System'); System.nanoTime();");
      Assert.assertEquals(0, resultSet.stream().count());
    } finally {
      scriptManager.removeAllowedPackages(new HashSet<>(List.of("java.lang.System")));
    }
  }

  @Test
  public void jsSandboxWithMathTest() {
    var resultSet = session.runScript("javascript", "Math.random()");
    Assert.assertEquals(1, resultSet.stream().count());
    resultSet.close();
  }

  @Test
  public void jsSandboxWithDB() {
    session.begin();
    var resultSet =
        session.runScript(
            "javascript",
            """
                var rs = db.query("select from OUser");
                var elem = rs.next();
                var prop = elem.getProperty("name");
                rs.close();
                prop;
                """);
    Assert.assertEquals(1, resultSet.stream().count());
    resultSet.close();
    session.commit();
  }

  @Test
  public void jsSandboxWithBigDecimal() {
    final var scriptManager = YouTrackDBInternal.extract(context).getScriptManager();
    try {
      scriptManager.addAllowedPackages(new HashSet<>(List.of("java.math.BigDecimal")));

      try (var resultSet =
          session.runScript(
              "javascript",
              "var BigDecimal = Java.type('java.math.BigDecimal'); new BigDecimal(1.0);")) {
        Assert.assertEquals(1, resultSet.stream().count());
      }
      scriptManager.removeAllowedPackages(new HashSet<>(List.of("java.math.BigDecimal")));
      scriptManager.closeAll();

      try {
        session.runScript("javascript", "new java.math.BigDecimal(1.0);");
        Assert.fail("It should receive a class not found exception");
      } catch (RuntimeException e) {
        Assert.assertEquals(
            GlobalConfiguration.SCRIPT_POLYGLOT_USE_GRAAL.getValueAsBoolean()
                ? ScriptException.class
                : ClassNotFoundException.class,
            e.getCause().getClass());
      }

      scriptManager.addAllowedPackages(new HashSet<>(List.of("java.math.*")));
      scriptManager.closeAll();

      try (var resultSet = session.runScript("javascript", "new java.math.BigDecimal(1.0);")) {
        Assert.assertEquals(1, resultSet.stream().count());
      }

    } finally {
      scriptManager.removeAllowedPackages(
          new HashSet<>(Arrays.asList("java.math.BigDecimal", "java.math.*")));
    }
  }

  @Test
  public void jsSandboxWithYouTrackDb() {
    try (var resultSet =
        session.runScript("javascript", "youtrackdb.getScriptManager().addAllowedPackages([])")) {
      Assert.assertEquals(1, resultSet.stream().count());
    } catch (Exception e) {
      Assert.assertEquals(ScriptException.class, e.getCause().getClass());
    }

    try (var resultSet =
        session.runScript(
            "javascript",
            "youtrackdb.getScriptManager().addAllowedPackages([])")) {
      Assert.assertEquals(1, resultSet.stream().count());
    } catch (Exception e) {
      Assert.assertEquals(
          GlobalConfiguration.SCRIPT_POLYGLOT_USE_GRAAL.getValueAsBoolean()
              ? ScriptException.class
              : ClassNotFoundException.class,
          e.getCause().getClass());
    }
  }
}
