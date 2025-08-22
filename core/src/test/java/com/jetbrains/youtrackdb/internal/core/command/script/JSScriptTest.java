package com.jetbrains.youtrackdb.internal.core.command.script;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.api.query.Result;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.common.io.IOUtils;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBInternal;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBInternalEmbedded;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import javax.script.ScriptException;
import org.junit.Assert;
import org.junit.Test;

public class JSScriptTest extends DbTestBase {
  @Test
  public void jsSimpleTest() {
    var resultSet = session.computeScript("javascript", "'foo'");
    Assert.assertTrue(resultSet.hasNext());
    var result = resultSet.next();
    String ret = result.getProperty("value");
    Assert.assertEquals("foo", ret);
  }

  @Test
  public void jsQueryTest() {
    session.begin();
    var script = "db.query('select from OUser')";
    var resultSet = session.computeScript("javascript", script);
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
    var resultSet = session.computeScript("javascript", IOUtils.readStreamAsString(stream));
    Assert.assertTrue(resultSet.hasNext());

    var results = resultSet.stream().toList();
    Assert.assertEquals(1, results.size());

    var linkList = results.getFirst().getLinkList("value");
    linkList.stream()
        .map(identifiable -> {
          var transaction = session.getActiveTransaction();
          return transaction.loadEntity(identifiable);
        })
        .forEach(
            entity -> {
              Assert.assertEquals("OUser", entity.getSchemaClassName());
            });
    session.commit();
  }

  @Test
  public void jsScriptCountTest() throws IOException {
    var stream = ClassLoader.getSystemResourceAsStream("fixtures/scriptCountTest.js");
    var resultSet = session.computeScript("javascript", IOUtils.readStreamAsString(stream));
    Assert.assertTrue(resultSet.hasNext());

    var results = resultSet.stream().toList();
    Assert.assertEquals(1, results.size());

    Number value = results.getFirst().getProperty("value");
    Assert.assertEquals(2, value.intValue()); // no default users anymore, 'admin' created
  }

  @Test
  public void jsSandboxTestWithJavaType() {
    try {
      session.computeScript(
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

  @Test
  public void jsSandboxWithMathTest() {
    var resultSet = session.computeScript("javascript", "Math.random()");
    Assert.assertEquals(1, resultSet.stream().count());
    resultSet.close();
  }

  @Test
  public void jsSandboxWithDB() {
    session.begin();
    var resultSet =
        session.computeScript(
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
    final var scriptManager = ((YouTrackDBInternalEmbedded)YouTrackDBInternal.extract(youTrackDB)).getScriptManager();
    try {
      scriptManager.addAllowedPackages(new HashSet<>(List.of("java.math.BigDecimal")));

      try (var resultSet =
          session.computeScript(
              "javascript",
              "var BigDecimal = Java.type('java.math.BigDecimal'); new BigDecimal(1.0);")) {
        Assert.assertEquals(1, resultSet.stream().count());
      }
      scriptManager.removeAllowedPackages(new HashSet<>(List.of("java.math.BigDecimal")));
      scriptManager.closeAll();

      try {
        session.computeScript("javascript", "new java.math.BigDecimal(1.0);");
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

      try (var resultSet = session.computeScript("javascript", "new java.math.BigDecimal(1.0);")) {
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
        session.computeScript("javascript", "youtrackdb.getScriptManager().addAllowedPackages([])")) {
      Assert.assertEquals(1, resultSet.stream().count());
    } catch (Exception e) {
      Assert.assertEquals(ScriptException.class, e.getCause().getClass());
    }

    try (var resultSet =
        session.computeScript(
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
