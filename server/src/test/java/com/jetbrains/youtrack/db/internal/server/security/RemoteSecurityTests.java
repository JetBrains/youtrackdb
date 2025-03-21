package com.jetbrains.youtrack.db.internal.server.security;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.YouTrackDB;
import com.jetbrains.youtrack.db.api.config.YouTrackDBConfig;
import com.jetbrains.youtrack.db.api.exception.SecurityException;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrack.db.internal.server.YouTrackDBServer;
import java.io.IOException;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class RemoteSecurityTests {

  private static final String DB_NAME = RemoteSecurityTests.class.getSimpleName();
  private YouTrackDB youTrackDB;
  private YouTrackDBServer server;
  private DatabaseSession db;

  @Before
  public void before()
      throws ClassNotFoundException, InstantiationException, IllegalAccessException, IOException {
    server = YouTrackDBServer.startFromClasspathConfig("abstract-youtrackdb-server-config.xml");
    youTrackDB = new YouTrackDBImpl("remote:localhost", "root", "root",
        YouTrackDBConfig.defaultConfig());
    youTrackDB.execute(
        "create database ? memory users (admin identified by 'admin' role admin, writer identified"
            + " by 'writer' role writer, reader identified by 'reader' role reader)",
        DB_NAME);
    this.db = youTrackDB.open(DB_NAME, "admin", "admin");
    var person = db.createClass("Person");
    person.createProperty("name", PropertyType.STRING);
  }

  @After
  public void after() {
    this.db.close();
    youTrackDB.drop(DB_NAME);
    youTrackDB.close();
    server.shutdown();
  }

  @Test
  public void testCreate() {
    var tx = db.begin();
    tx.command("CREATE SECURITY POLICY testPolicy SET create = (name = 'foo')");
    tx.command("ALTER ROLE writer SET POLICY testPolicy ON database.class.Person");
    tx.commit();

    try (var filteredSession = youTrackDB.open(DB_NAME, "writer", "writer")) {
      tx = filteredSession.begin();
      var elem = tx.newEntity("Person");
      elem.setProperty("name", "foo");
      tx.commit();
      try {
        tx = filteredSession.begin();
        elem = tx.newEntity("Person");
        elem.setProperty("name", "bar");
        tx.commit();
        Assert.fail();
      } catch (SecurityException ex) {
      }
    }
  }

  @Test
  public void testSqlCreate() {
    var tx = db.begin();
    tx.command("CREATE SECURITY POLICY testPolicy SET create = (name = 'foo')");
    tx.command("ALTER ROLE writer SET POLICY testPolicy ON database.class.Person");
    tx.commit();

    try (var filteredSession = youTrackDB.open(DB_NAME, "writer", "writer")) {
      tx = filteredSession.begin();
      tx.command("insert into Person SET name = 'foo'");
      tx.commit();
      try {
        tx = filteredSession.begin();
        tx.command("insert into Person SET name = 'bar'");
        tx.commit();
        Assert.fail();
      } catch (SecurityException ex) {
      }
    }
  }

  @Test
  public void testSqlRead() {
    var tx = db.begin();
    tx.command("CREATE SECURITY POLICY testPolicy SET read = (name = 'foo')");
    tx.command("ALTER ROLE reader SET POLICY testPolicy ON database.class.Person");
    tx.commit();

    tx = db.begin();
    var elem = tx.newEntity("Person");
    elem.setProperty("name", "foo");

    elem = tx.newEntity("Person");
    elem.setProperty("name", "bar");
    tx.commit();

    db.close();

    try (var filteredSession = youTrackDB.open(DB_NAME, "reader", "reader")) {
      tx = filteredSession.begin();
      try (var rs = tx.query("select from Person")) {
        Assert.assertTrue(rs.hasNext());
        rs.next();
        Assert.assertFalse(rs.hasNext());
      }
      tx.commit();
    }
  }

  @Test
  public void testSqlReadWithIndex() {
    db.runScript("sql", "create index Person.name on Person (name) NOTUNIQUE");

    var tx = db.begin();
    tx.command("CREATE SECURITY POLICY testPolicy SET read = (name = 'foo')");
    tx.command("ALTER ROLE reader SET POLICY testPolicy ON database.class.Person");
    tx.commit();

    tx = db.begin();
    var elem = tx.newEntity("Person");
    elem.setProperty("name", "foo");

    elem = tx.newEntity("Person");
    elem.setProperty("name", "bar");
    tx.commit();

    try (var filteredSession = youTrackDB.open(DB_NAME, "reader", "reader")) {
      tx = filteredSession.begin();
      try (var rs = tx.query("select from Person where name = 'bar'")) {

        Assert.assertFalse(rs.hasNext());
      }
      tx.commit();
    }
  }

  @Test
  public void testSqlReadWithIndex2() {
    db.runScript("sql", "create index Person.name on Person(name)NOTUNIQUE");

    var tx = db.begin();
    tx.command("CREATE SECURITY POLICY testPolicy SET read = (surname = 'foo')");
    tx.command("ALTER ROLE reader SET POLICY testPolicy ON database.class.Person");
    tx.commit();

    tx = db.begin();
    var elem = tx.newEntity("Person");
    elem.setProperty("name", "foo");
    elem.setProperty("surname", "foo");

    elem = tx.newEntity("Person");
    elem.setProperty("name", "foo");
    elem.setProperty("surname", "bar");
    tx.commit();

    try (var filteredSession = youTrackDB.open(DB_NAME, "reader", "reader")) {
      filteredSession.executeInTx(transaction -> {
        try (var rs = transaction.query("select from Person where name = 'foo'")) {
          Assert.assertTrue(rs.hasNext());
          var item = rs.next();
          Assert.assertEquals("foo", item.getProperty("surname"));
          Assert.assertFalse(rs.hasNext());
        }
      });
    }
  }

  @Test
  public void testBeforeUpdateCreate() {
    var tx = db.begin();
    tx.command("CREATE SECURITY POLICY testPolicy SET BEFORE UPDATE = (name = 'bar')");
    tx.command("ALTER ROLE writer SET POLICY testPolicy ON database.class.Person");
    tx.commit();

    try (var filteredSession = youTrackDB.open(DB_NAME, "writer", "writer")) {
      tx = filteredSession.begin();
      var elem = tx.newEntity("Person");
      elem.setProperty("name", "foo");
      tx.commit();
      try {
        tx = filteredSession.begin();
        elem = tx.bindToSession(elem);
        elem.setProperty("name", "baz");
        tx.commit();
        Assert.fail();
      } catch (SecurityException ex) {
      }
      tx = filteredSession.begin();
      Assert.assertEquals("foo", tx.bindToSession(elem).getProperty("name"));
      tx.commit();
    }
  }

  @Test
  public void testBeforeUpdateCreateSQL() {
    var tx = db.begin();
    tx.command("CREATE SECURITY POLICY testPolicy SET BEFORE UPDATE = (name = 'bar')");
    tx.command("ALTER ROLE writer SET POLICY testPolicy ON database.class.Person");
    tx.commit();

    try (var filteredSession = youTrackDB.open(DB_NAME, "writer", "writer")) {
      tx = filteredSession.begin();
      var elem = tx.newEntity("Person");
      elem.setProperty("name", "foo");
      tx.commit();
      try {
        tx = filteredSession.begin();
        tx.command("update Person set name = 'bar'");
        tx.commit();
        Assert.fail();
      } catch (SecurityException ex) {
      }

      tx = filteredSession.begin();
      Assert.assertEquals("foo", tx.bindToSession(elem).getProperty("name"));
      tx.commit();
    }
  }

  @Test
  public void testAfterUpdate() {
    var tx = db.begin();
    tx.command("CREATE SECURITY POLICY testPolicy SET AFTER UPDATE = (name = 'foo')");
    tx.command("ALTER ROLE writer SET POLICY testPolicy ON database.class.Person");
    tx.commit();

    try (var filteredSession = youTrackDB.open(DB_NAME, "writer", "writer")) {
      tx = filteredSession.begin();
      var elem = tx.newEntity("Person");
      elem.setProperty("name", "foo");
      tx.commit();
      try {
        tx = filteredSession.begin();
        elem = tx.bindToSession(elem);
        elem.setProperty("name", "bar");
        tx.commit();
        Assert.fail();
      } catch (SecurityException ex) {
      }

      tx = filteredSession.begin();
      Assert.assertEquals("foo", tx.bindToSession(elem).getProperty("name"));
      tx.commit();
    }
  }

  @Test
  public void testAfterUpdateSQL() {
    var tx = db.begin();
    tx.command("CREATE SECURITY POLICY testPolicy SET AFTER UPDATE = (name = 'foo')");
    tx.command("ALTER ROLE writer SET POLICY testPolicy ON database.class.Person");
    tx.commit();

    try (var filteredSession = youTrackDB.open(DB_NAME, "writer", "writer")) {
      tx = filteredSession.begin();
      var elem = tx.newEntity("Person");
      elem.setProperty("name", "foo");
      tx.commit();
      try {
        tx = filteredSession.begin();
        tx.command("update Person set name = 'bar'");
        tx.commit();
        Assert.fail();
      } catch (SecurityException ex) {
      }

      tx = filteredSession.begin();
      Assert.assertEquals("foo", tx.bindToSession(elem).getProperty("name"));
      tx.commit();
    }
  }

  @Test
  public void testDelete() {
    var tx = db.begin();
    tx.command("CREATE SECURITY POLICY testPolicy SET DELETE = (name = 'foo')");
    tx.command("ALTER ROLE writer SET POLICY testPolicy ON database.class.Person");
    tx.commit();

    try (var filteredSession = youTrackDB.open(DB_NAME, "writer", "writer")) {
      tx = filteredSession.begin();
      var elem = tx.newEntity("Person");
      elem.setProperty("name", "bar");
      tx.commit();
      try {
        tx = filteredSession.begin();
        elem = tx.bindToSession(elem);
        tx.delete(elem);
        tx.commit();
        Assert.fail();
      } catch (SecurityException ex) {
      }

      tx = filteredSession.begin();
      elem = tx.newEntity("Person");
      elem.setProperty("name", "foo");
      tx.delete(elem);
      tx.commit();
    }
  }

  @Test
  public void testDeleteSQL() {
    var tx = db.begin();
    tx.command("CREATE SECURITY POLICY testPolicy SET DELETE = (name = 'foo')");
    tx.command("ALTER ROLE writer SET POLICY testPolicy ON database.class.Person");
    tx.commit();

    try (var filteredSession = youTrackDB.open(DB_NAME, "writer", "writer")) {
      tx = filteredSession.begin();
      var elem = tx.newEntity("Person");
      elem.setProperty("name", "foo");

      elem = tx.newEntity("Person");
      elem.setProperty("name", "bar");
      tx.commit();

      tx = filteredSession.begin();
      tx.command("delete from Person where name = 'foo'");
      tx.commit();
      try {
        tx = filteredSession.begin();
        tx.command("delete from Person where name = 'bar'");
        tx.commit();
        Assert.fail();
      } catch (SecurityException ex) {
      }

      tx = filteredSession.begin();
      try (var rs = tx.query("select from Person")) {
        Assert.assertTrue(rs.hasNext());
        Assert.assertEquals("bar", rs.next().getProperty("name"));
        Assert.assertFalse(rs.hasNext());
      }
      tx.commit();
    }
  }

  @Test
  public void testSqlCount() {
    var tx = db.begin();
    tx.command("CREATE SECURITY POLICY testPolicy SET read = (name = 'foo')");
    tx.command("ALTER ROLE reader SET POLICY testPolicy ON database.class.Person");
    tx.commit();

    tx = db.begin();
    var elem = tx.newEntity("Person");
    elem.setProperty("name", "foo");

    elem = tx.newEntity("Person");
    elem.setProperty("name", "bar");
    tx.commit();

    try (var filteredSession = youTrackDB.open(DB_NAME, "reader", "reader")) {
      tx = filteredSession.begin();
      try (var rs = tx.query("select count(*) as count from Person")) {
        Assert.assertEquals(1L, (long) rs.next().getProperty("count"));
      }
      tx.commit();
    }
  }

  @Test
  public void testSqlCountWithIndex() {
    db.runScript("sql", "create index Person.name on Person (name) NOTUNIQUE");

    var tx = db.begin();
    tx.command("CREATE SECURITY POLICY testPolicy SET read = (name = 'foo')");
    tx.command("ALTER ROLE reader SET POLICY testPolicy ON database.class.Person");
    tx.commit();

    tx = db.begin();
    var elem = tx.newEntity("Person");
    elem.setProperty("name", "foo");

    elem = tx.newEntity("Person");
    elem.setProperty("name", "bar");
    tx.commit();

    db.close();
    try (var filteredSession = youTrackDB.open(DB_NAME, "reader", "reader")) {
      tx = filteredSession.begin();
      try (var rs = tx.query("select count(*) as count from Person where name = 'bar'")) {
        Assert.assertEquals(0L, (long) rs.next().getProperty("count"));
      }

      try (var rs =
          tx.query("select count(*) as count from Person where name = 'foo'")) {
        Assert.assertEquals(1L, (long) rs.next().getProperty("count"));
      }
      tx.commit();
    }
  }

  @Test
  public void testIndexGet() {
    db.runScript("sql", "create index Person.name on Person (name) NOTUNIQUE");

    var tx = db.begin();
    tx.command("CREATE SECURITY POLICY testPolicy SET read = (name = 'foo')");
    tx.command("ALTER ROLE reader SET POLICY testPolicy ON database.class.Person");
    tx.commit();

    tx = db.begin();
    var elem = tx.newEntity("Person");
    elem.setProperty("name", "foo");

    elem = tx.newEntity("Person");
    elem.setProperty("name", "bar");
    tx.commit();

    try (var filteredSession = youTrackDB.open(DB_NAME, "reader", "reader")) {
      tx = filteredSession.begin();
      try (final var resultSet =
          tx.query("SELECT from Person where name = ?", "bar")) {
        Assert.assertEquals(0, resultSet.stream().count());
      }

      try (final var resultSet =
          tx.query("SELECT from Person where name = ?", "foo")) {
        Assert.assertEquals(1, resultSet.stream().count());
      }
      tx.commit();
    }
  }

  @Test
  public void testIndexGetAndColumnSecurity() {
    db.runScript("sql", "create index Person.name on Person (name) NOTUNIQUE");

    var tx = db.begin();
    tx.command("CREATE SECURITY POLICY testPolicy SET read = (name = 'foo')");
    tx.command("ALTER ROLE reader SET POLICY testPolicy ON database.class.Person.name");
    tx.commit();

    tx = db.begin();
    var elem = tx.newEntity("Person");
    elem.setProperty("name", "foo");

    elem = tx.newEntity("Person");
    elem.setProperty("name", "bar");
    tx.commit();

    try (var filteredSession = youTrackDB.open(DB_NAME, "reader", "reader")) {
      tx = filteredSession.begin();
      try (final var resultSet =
          tx.query("SELECT from Person where name = ?", "bar")) {
        Assert.assertEquals(0, resultSet.stream().count());
      }

      try (final var resultSet =
          tx.query("SELECT from Person where name = ?", "foo")) {
        Assert.assertEquals(1, resultSet.stream().count());
      }
      tx.commit();
    }
  }

  @Test
  public void testReadHiddenColumn() {
    var tx = db.begin();
    tx.command("CREATE SECURITY POLICY testPolicy SET read = (name = 'bar')");
    tx.command("ALTER ROLE reader SET POLICY testPolicy ON database.class.Person.name");
    tx.commit();

    tx = db.begin();
    var elem = tx.newEntity("Person");
    elem.setProperty("name", "foo");
    elem.setProperty("surname", "foo");
    tx.commit();

    db.close();

    db = youTrackDB.open(DB_NAME, "reader", "reader");
    tx = db.begin();
    try (final var resultSet = tx.query("SELECT from Person")) {
      var item = resultSet.next();
      Assert.assertNull(item.getProperty("name"));
    }
    tx.commit();
  }

  @Test
  public void testUpdateHiddenColumn() {
    var tx = db.begin();
    tx.command("CREATE SECURITY POLICY testPolicy SET read = (name = 'bar')");
    tx.command("ALTER ROLE reader SET POLICY testPolicy ON database.class.Person.name");
    tx.commit();

    tx = db.begin();
    var elem = tx.newEntity("Person");
    elem.setProperty("name", "foo");
    elem.setProperty("surname", "foo");
    tx.commit();

    db.close();

    db = youTrackDB.open(DB_NAME, "reader", "reader");

    tx = db.begin();
    try (final var resultSet = tx.query("SELECT from Person")) {
      try {
        var item = resultSet.next();
        var doc = item.asEntity();
        doc.setProperty("name", "bar");

        tx.commit();
        Assert.fail();
      } catch (Exception e) {
      }
    }
  }
}
