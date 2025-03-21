package com.jetbrains.youtrack.db.internal.tools.console;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

/**
 *
 */
public class ConsoleDatabaseAppTest {

  @Rule
  public TestName testName = new TestName();

  @Test
  public void testSelectBinaryDoc() throws IOException {
    final var builder = new StringBuilder();

    var app =
        new ConsoleDatabaseApp(new String[]{}) {
          @Override
          public void message(String iMessage) {
            builder.append(iMessage).append("\n");
          }
        };
    try {

      app.executeServerCommand("connect env embedded:./target/ root root");
      app.executeServerCommand(
          "create database test memory users (admin identified by 'admin' role admin)");
      app.open("test", "admin", "admin");

      var db = (DatabaseSessionInternal) app.getCurrentDatabaseSession();
      db.addBlobCluster("blobTest");

      db.begin();
      var record = db.newBlob("blobContent".getBytes());
      db.commit();
      builder.setLength(0);
      app.select(" from " + record.getIdentity() + " limit -1 ");
      assertTrue(builder.toString().contains("<binary>"));
    } finally {
      app.dropDatabase("memory");
    }
  }

  @Test
  public void testWrongCommand() {

    var builder =
        "connect env embedded:./target/ root root;\n"
            + "create database OConsoleDatabaseAppTest2 memory users (admin identified by 'admin'"
            + " role admin);\n"
            + "open OConsoleDatabaseAppTest2 admin admin;\n"
            + "create class foo;\n"
            + "begin;\n"
            + "insert into foo set name ="
            + " 'foo';\n"
            + "insert into foo set name ="
            + " 'bla';\n"
            + "blabla;\n" // <- wrong command, this should break the console
            + "update foo set surname = 'bar' where name = 'foo';\n"
            + "commit;\n";
    var c = new ConsoleTest(new String[]{builder});
    var console = c.console();

    try {
      console.run();

      try (var db = console.getCurrentDatabaseSession()) {
        db.executeInTx(transaction -> {
          try (var result = transaction.query("select from foo where name = 'foo'")) {
            var doc = result.next();
            Assert.assertNull(doc.getProperty("surname"));
            Assert.assertFalse(result.hasNext());
          }
        });
      }
    } finally {
      console.close();
    }
  }

  @Test
  public void testOldCreateDatabase() {

    var builder =
        """
            create database memory:./target/OConsoleDatabaseAppTest2 admin adminpwd memory
            create class foo;
            begin;\
            insert into foo set name = 'foo';
            insert into foo set name = 'bla';
            commit;""";
    var c = new ConsoleTest(new String[]{builder});
    var console = c.console();

    try {
      console.run();

      try (var db = console.getCurrentDatabaseSession()) {
        db.executeInTx(transaction -> {
          var size = transaction.query("select from foo where name = 'foo'").stream().count();
          Assert.assertEquals(1, size);
        });
      }
    } finally {
      console.close();
    }
  }

  @Test
  public void testDumpRecordDetails() {
    var c = new ConsoleTest();
    try {

      c.console().executeServerCommand("connect env embedded:./target/ root root");
      c.console()
          .executeServerCommand(
              "create database ConsoleDatabaseAppTestDumpRecordDetails memory users (admin"
                  + " identified by 'admin' role admin)");
      c.console().open("ConsoleDatabaseAppTestDumpRecordDetails", "admin", "admin");

      c.console().createClass("class foo");
      c.console().begin();
      c.console().insert("into foo set name = 'barbar'");
      c.console().commit();
      c.console().select("from foo limit -1");
      c.resetOutput();

      c.console().set("maxBinaryDisplay", "10000");
      c.console().select("from foo limit -1");

      var resultString = c.getConsoleOutput();
      Assert.assertTrue(resultString.contains("@class"));
      Assert.assertTrue(resultString.contains("foo"));
      Assert.assertTrue(resultString.contains("name"));
      Assert.assertTrue(resultString.contains("barbar"));
    } catch (Exception e) {
      Assert.fail();
    } finally {
      c.shutdown();
    }
  }

  @Test
  public void testHelp() {
    var c = new ConsoleTest();
    try {
      c.console().help(null);
      var resultString = c.getConsoleOutput();
      Assert.assertTrue(resultString.contains("connect"));
      Assert.assertTrue(resultString.contains("alter class"));
      Assert.assertTrue(resultString.contains("create class"));
      Assert.assertTrue(resultString.contains("select"));
      Assert.assertTrue(resultString.contains("update"));
      Assert.assertTrue(resultString.contains("delete"));
      Assert.assertTrue(resultString.contains("create vertex"));
      Assert.assertTrue(resultString.contains("create edge"));
      Assert.assertTrue(resultString.contains("help"));
      Assert.assertTrue(resultString.contains("exit"));

    } catch (Exception e) {
      Assert.fail();
    } finally {
      c.shutdown();
    }
  }

  @Test
  public void testHelpCommand() {
    var c = new ConsoleTest();
    try {
      c.console().help("select");
      var resultString = c.getConsoleOutput();
      Assert.assertTrue(resultString.contains("COMMAND: select"));

    } catch (Exception e) {
      Assert.fail();
    } finally {
      c.shutdown();
    }
  }

  @Test
  public void testSimple() {

    var builder =
        "connect env embedded:./target/ root root;\n"
            + "create database "
            + testName.getMethodName()
            + " memory users (admin identified by 'admin' role admin);\n"
            + "open "
            + testName.getMethodName()
            + " admin admin;\n"
            + "profile storage on;\n"
            + "create class foo;\n"
            + "config;\n"
            + "list classes;\n"
            + "list properties;\n"
            + "list clusters;\n"
            + "list indexes;\n"
            + "info class OUser;\n"
            + "info property OUser.name;\n"
            + "begin;\n"
            + "insert into foo set name = 'foo';\n"
            + "insert into foo set name = 'bla';\n"
            + "update foo set surname = 'bar' where name = 'foo';\n"
            + "commit;\n"
            + "select from foo;\n"
            + "create class bar;\n"
            + "create property bar.name STRING;\n"
            + "create index bar_name on bar (name) NOTUNIQUE;\n"
            + "begin;\n"
            + "insert into bar set name = 'foo';\n"
            + "delete from bar;\n"
            + "commit;\n"
            + "begin;\n"
            + "insert into bar set name = 'foo';\n"
            + "rollback;\n"
            + "begin;\n"
            + "create vertex V set name = 'foo';\n"
            + "create vertex V set name = 'bar';\n"
            + "commit;\n"
            + "begin;\n"
            + "traverse out() from V;\n"
            + "create edge from (select from V where name = 'foo') to (select from V where name ="
            + " 'bar');\n"
            + "commit;\n"
            + "begin;\n"
            + "traverse out() from V;\n"
            + "profile storage off;\n"
            + "repair database -v;\n"
            + "commit;\n";
    var c = new ConsoleTest(new String[]{builder});
    var console = c.console();

    try {
      console.run();

      var db = console.getCurrentDatabaseSession();
      db.executeInTx(transaction -> {
        var result = transaction.query("select from foo where name = 'foo'");
        var doc = result.next();
        Assert.assertEquals("bar", doc.getProperty("surname"));
        Assert.assertFalse(result.hasNext());
        result.close();

        result = transaction.query("select from bar");
        Assert.assertEquals(0, result.stream().count());
      });

    } finally {
      console.close();
    }
  }

  @Test
  @Ignore
  public void testMultiLine() {
    var dbUrl = "memory:" + testName.getMethodName();

    var builder =
        "create database "
            + dbUrl
            + ";\n"
            + "profile storage on;\n"
            + "create class foo;\n"
            + "config;\n"
            + "list classes;\n"
            + "list properties;\n"
            + "list clusters;\n"
            + "list indexes;\n"
            + "info class OUser;\n"
            + "info property OUser.name;\n"
            + "begin;\n"
            + "insert into foo set name = 'foo';\n"
            + "insert into foo set name = 'bla';\n"
            + "update foo set surname = 'bar' where name = 'foo';\n"
            + "commit;\n"
            + "select from foo;\n"
            + "create class bar;\n"
            + "create property bar.name STRING;\n"
            + "create index bar_name on bar (name) NOTUNIQUE;\n"
            + "insert into bar set name = 'foo';\n"
            + "delete from bar;\n"
            + "begin;\n"
            + "insert into bar set name = 'foo';\n"
            + "rollback;\n"
            + "create vertex V set name = 'foo';\n"
            + "create vertex V set name = 'bar';\n"
            + "traverse out() from V;\n"

            //    builder.append("create edge from (select from V where name = 'foo') to (select
            // from V
            // where name = 'bar');\n");

            + "create edge from \n"
            + "(select from V where name = 'foo') \n"
            + "to (select from V where name = 'bar');\n";

    var c = new ConsoleTest(new String[]{builder});
    var console = c.console();

    try {
      console.run();

      var db = console.getCurrentDatabaseSession();
      db.executeInTx(transaction -> {
        var result = transaction.query("select from foo where name = 'foo'");
        var doc = result.next();
        Assert.assertEquals("bar", doc.getProperty("surname"));
        Assert.assertFalse(result.hasNext());
        result.close();

        result = transaction.query("select from bar");
        Assert.assertEquals(0, result.stream().count());
      });

    } finally {
      console.close();
    }
  }

  static class ConsoleTest {

    ConsoleDatabaseApp console;
    ByteArrayOutputStream out;
    PrintStream stream;

    ConsoleTest() {
      console =
          new ConsoleDatabaseApp(null) {
            @Override
            protected void onException(Throwable e) {
              super.onException(e);
              fail(e.getMessage());
            }
          };
      resetOutput();
    }

    ConsoleTest(String[] args) {
      console =
          new ConsoleDatabaseApp(args) {
            @Override
            protected void onException(Throwable e) {
              super.onException(e);
              fail(e.getMessage());
            }
          };
      resetOutput();
    }

    public ConsoleDatabaseApp console() {
      return console;
    }

    public String getConsoleOutput() {
      return out.toString();
    }

    void resetOutput() {
      if (out != null) {
        try {
          stream.close();
          out.close();
        } catch (IOException e) {
          e.printStackTrace();
        }
      }
      out = new ByteArrayOutputStream();
      stream = new PrintStream(out);
      console.setOutput(stream);
    }

    void shutdown() {
      if (out != null) {
        try {
          stream.close();
          out.close();
        } catch (Exception e) {
          e.printStackTrace();
        }
      }
      console.close();
    }
  }
}
