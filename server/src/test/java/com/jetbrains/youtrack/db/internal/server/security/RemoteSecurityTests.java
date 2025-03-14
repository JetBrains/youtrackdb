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
    db.begin();
    db.command("CREATE SECURITY POLICY testPolicy SET create = (name = 'foo')");
    db.command("ALTER ROLE writer SET POLICY testPolicy ON database.class.Person");
    db.commit();

    try (var filteredSession = youTrackDB.open(DB_NAME, "writer", "writer")) {
      filteredSession.begin();
      var elem = filteredSession.newEntity("Person");
      elem.setProperty("name", "foo");
      filteredSession.commit();
      try {
        filteredSession.begin();
        elem = filteredSession.newEntity("Person");
        elem.setProperty("name", "bar");
        filteredSession.commit();
        Assert.fail();
      } catch (SecurityException ex) {
      }
    }
  }

  @Test
  public void testSqlCreate() {
    db.begin();
    db.command("CREATE SECURITY POLICY testPolicy SET create = (name = 'foo')");
    db.command("ALTER ROLE writer SET POLICY testPolicy ON database.class.Person");
    db.commit();

    try (var filteredSession = youTrackDB.open(DB_NAME, "writer", "writer")) {
      filteredSession.begin();
      filteredSession.command("insert into Person SET name = 'foo'");
      filteredSession.commit();
      try {
        filteredSession.begin();
        filteredSession.command("insert into Person SET name = 'bar'");
        filteredSession.commit();
        Assert.fail();
      } catch (SecurityException ex) {
      }
    }
  }

  @Test
  public void testSqlRead() {
    db.begin();
    db.command("CREATE SECURITY POLICY testPolicy SET read = (name = 'foo')");
    db.command("ALTER ROLE reader SET POLICY testPolicy ON database.class.Person");
    db.commit();

    db.begin();
    var elem = db.newEntity("Person");
    elem.setProperty("name", "foo");

    elem = db.newEntity("Person");
    elem.setProperty("name", "bar");
    db.commit();

    db.close();
    try (var filteredSession = youTrackDB.open(DB_NAME, "reader", "reader")) {
      try (var rs = filteredSession.query("select from Person")) {
        Assert.assertTrue(rs.hasNext());
        rs.next();
        Assert.assertFalse(rs.hasNext());
      }
    }
  }

  @Test
  public void testSqlReadWithIndex() {
    db.command("create index Person.name on Person (name) NOTUNIQUE");

    db.begin();
    db.command("CREATE SECURITY POLICY testPolicy SET read = (name = 'foo')");
    db.command("ALTER ROLE reader SET POLICY testPolicy ON database.class.Person");
    db.commit();

    db.begin();
    var elem = db.newEntity("Person");
    elem.setProperty("name", "foo");

    elem = db.newEntity("Person");
    elem.setProperty("name", "bar");
    db.commit();

    try (var filteredSession = youTrackDB.open(DB_NAME, "reader", "reader")) {
      try (var rs = filteredSession.query("select from Person where name = 'bar'")) {

        Assert.assertFalse(rs.hasNext());
      }
    }
  }

  @Test
  public void testSqlReadWithIndex2() {
    db.command("create index Person.name on Person (name) NOTUNIQUE");

    db.begin();
    db.command("CREATE SECURITY POLICY testPolicy SET read = (surname = 'foo')");
    db.command("ALTER ROLE reader SET POLICY testPolicy ON database.class.Person");
    db.commit();

    db.begin();
    var elem = db.newEntity("Person");
    elem.setProperty("name", "foo");
    elem.setProperty("surname", "foo");

    elem = db.newEntity("Person");
    elem.setProperty("name", "foo");
    elem.setProperty("surname", "bar");
    db.commit();

    try (var filteredSession = youTrackDB.open(DB_NAME, "reader", "reader")) {
      try (var rs = filteredSession.query("select from Person where name = 'foo'")) {
        Assert.assertTrue(rs.hasNext());
        var item = rs.next();
        Assert.assertEquals("foo", item.getProperty("surname"));
        Assert.assertFalse(rs.hasNext());
      }
    }
  }

  @Test
  public void testBeforeUpdateCreate() {
    db.begin();
    db.command("CREATE SECURITY POLICY testPolicy SET BEFORE UPDATE = (name = 'bar')");
    db.command("ALTER ROLE writer SET POLICY testPolicy ON database.class.Person");
    db.commit();

    try (var filteredSession = youTrackDB.open(DB_NAME, "writer", "writer")) {
      filteredSession.begin();
      var elem = filteredSession.newEntity("Person");
      elem.setProperty("name", "foo");
      filteredSession.commit();
      try {
        filteredSession.begin();
        elem = filteredSession.bindToSession(elem);
        elem.setProperty("name", "baz");
        filteredSession.commit();
        Assert.fail();
      } catch (SecurityException ex) {
      }
      Assert.assertEquals("foo", filteredSession.bindToSession(elem).getProperty("name"));
    }
  }

  @Test
  public void testBeforeUpdateCreateSQL() {
    db.begin();
    db.command("CREATE SECURITY POLICY testPolicy SET BEFORE UPDATE = (name = 'bar')");
    db.command("ALTER ROLE writer SET POLICY testPolicy ON database.class.Person");
    db.commit();

    try (var filteredSession = youTrackDB.open(DB_NAME, "writer", "writer")) {
      filteredSession.begin();
      var elem = filteredSession.newEntity("Person");
      elem.setProperty("name", "foo");
      filteredSession.commit();
      try {
        filteredSession.begin();
        filteredSession.command("update Person set name = 'bar'");
        filteredSession.commit();
        Assert.fail();
      } catch (SecurityException ex) {
      }

      Assert.assertEquals("foo", filteredSession.bindToSession(elem).getProperty("name"));
    }
  }

  @Test
  public void testAfterUpdate() {
    db.begin();
    db.command("CREATE SECURITY POLICY testPolicy SET AFTER UPDATE = (name = 'foo')");
    db.command("ALTER ROLE writer SET POLICY testPolicy ON database.class.Person");
    db.commit();

    try (var filteredSession = youTrackDB.open(DB_NAME, "writer", "writer")) {
      filteredSession.begin();
      var elem = filteredSession.newEntity("Person");
      elem.setProperty("name", "foo");
      filteredSession.commit();
      try {
        filteredSession.begin();
        elem = filteredSession.bindToSession(elem);
        elem.setProperty("name", "bar");
        filteredSession.commit();
        Assert.fail();
      } catch (SecurityException ex) {
      }

      Assert.assertEquals("foo", filteredSession.bindToSession(elem).getProperty("name"));
    }
  }

  @Test
  public void testAfterUpdateSQL() {
    db.begin();
    db.command("CREATE SECURITY POLICY testPolicy SET AFTER UPDATE = (name = 'foo')");
    db.command("ALTER ROLE writer SET POLICY testPolicy ON database.class.Person");
    db.commit();

    try (var filteredSession = youTrackDB.open(DB_NAME, "writer", "writer")) {
      filteredSession.begin();
      var elem = filteredSession.newEntity("Person");
      elem.setProperty("name", "foo");
      filteredSession.commit();
      try {
        filteredSession.begin();
        filteredSession.command("update Person set name = 'bar'");
        filteredSession.commit();
        Assert.fail();
      } catch (SecurityException ex) {
      }

      Assert.assertEquals("foo", filteredSession.bindToSession(elem).getProperty("name"));
    }
  }

  @Test
  public void testDelete() {
    db.begin();
    db.command("CREATE SECURITY POLICY testPolicy SET DELETE = (name = 'foo')");
    db.command("ALTER ROLE writer SET POLICY testPolicy ON database.class.Person");
    db.commit();

    try (var filteredSession = youTrackDB.open(DB_NAME, "writer", "writer")) {

      filteredSession.begin();
      var elem = filteredSession.newEntity("Person");
      elem.setProperty("name", "bar");
      filteredSession.commit();
      try {
        filteredSession.begin();
        elem = filteredSession.bindToSession(elem);
        filteredSession.delete(elem);
        filteredSession.commit();
        Assert.fail();
      } catch (SecurityException ex) {
      }

      filteredSession.begin();
      elem = filteredSession.newEntity("Person");
      elem.setProperty("name", "foo");
      filteredSession.delete(elem);
      filteredSession.commit();
    }
  }

  @Test
  public void testDeleteSQL() {
    db.begin();
    db.command("CREATE SECURITY POLICY testPolicy SET DELETE = (name = 'foo')");
    db.command("ALTER ROLE writer SET POLICY testPolicy ON database.class.Person");
    db.commit();

    try (var filteredSession = youTrackDB.open(DB_NAME, "writer", "writer")) {
      filteredSession.begin();
      var elem = filteredSession.newEntity("Person");
      elem.setProperty("name", "foo");

      elem = filteredSession.newEntity("Person");
      elem.setProperty("name", "bar");
      filteredSession.commit();

      filteredSession.begin();
      filteredSession.command("delete from Person where name = 'foo'");
      filteredSession.commit();
      try {
        filteredSession.begin();
        filteredSession.command("delete from Person where name = 'bar'");
        filteredSession.commit();
        Assert.fail();
      } catch (SecurityException ex) {
      }

      try (var rs = filteredSession.query("select from Person")) {
        Assert.assertTrue(rs.hasNext());
        Assert.assertEquals("bar", rs.next().getProperty("name"));
        Assert.assertFalse(rs.hasNext());
      }
    }
  }

  @Test
  public void testSqlCount() {
    db.begin();
    db.command("CREATE SECURITY POLICY testPolicy SET read = (name = 'foo')");
    db.command("ALTER ROLE reader SET POLICY testPolicy ON database.class.Person");
    db.commit();

    db.begin();
    var elem = db.newEntity("Person");
    elem.setProperty("name", "foo");

    elem = db.newEntity("Person");
    elem.setProperty("name", "bar");
    db.commit();

    try (var filteredSession = youTrackDB.open(DB_NAME, "reader", "reader")) {
      try (var rs = filteredSession.query("select count(*) as count from Person")) {
        Assert.assertEquals(1L, (long) rs.next().getProperty("count"));
      }
    }
  }

  @Test
  public void testSqlCountWithIndex() {
    db.command("create index Person.name on Person (name) NOTUNIQUE");

    db.begin();
    db.command("CREATE SECURITY POLICY testPolicy SET read = (name = 'foo')");
    db.command("ALTER ROLE reader SET POLICY testPolicy ON database.class.Person");
    db.commit();

    db.begin();
    var elem = db.newEntity("Person");
    elem.setProperty("name", "foo");

    elem = db.newEntity("Person");
    elem.setProperty("name", "bar");
    db.commit();

    db.close();
    try (var filteredSession = youTrackDB.open(DB_NAME, "reader", "reader")) {
      try (var rs =
          filteredSession.query("select count(*) as count from Person where name = 'bar'")) {
        Assert.assertEquals(0L, (long) rs.next().getProperty("count"));
      }

      try (var rs =
          filteredSession.query("select count(*) as count from Person where name = 'foo'")) {
        Assert.assertEquals(1L, (long) rs.next().getProperty("count"));
      }
    }
  }

  @Test
  public void testIndexGet() {
    db.command("create index Person.name on Person (name) NOTUNIQUE");

    db.begin();
    db.command("CREATE SECURITY POLICY testPolicy SET read = (name = 'foo')");
    db.command("ALTER ROLE reader SET POLICY testPolicy ON database.class.Person");
    db.commit();

    db.begin();
    var elem = db.newEntity("Person");
    elem.setProperty("name", "foo");

    elem = db.newEntity("Person");
    elem.setProperty("name", "bar");
    db.commit();

    try (var filteredSession = youTrackDB.open(DB_NAME, "reader", "reader")) {
      try (final var resultSet =
          filteredSession.query("SELECT from Person where name = ?", "bar")) {
        Assert.assertEquals(0, resultSet.stream().count());
      }

      try (final var resultSet =
          filteredSession.query("SELECT from Person where name = ?", "foo")) {
        Assert.assertEquals(1, resultSet.stream().count());
      }
    }
  }

  @Test
  public void testIndexGetAndColumnSecurity() {
    db.command("create index Person.name on Person (name) NOTUNIQUE");

    db.begin();
    db.command("CREATE SECURITY POLICY testPolicy SET read = (name = 'foo')");
    db.command("ALTER ROLE reader SET POLICY testPolicy ON database.class.Person.name");
    db.commit();

    db.begin();
    var elem = db.newEntity("Person");
    elem.setProperty("name", "foo");

    elem = db.newEntity("Person");
    elem.setProperty("name", "bar");
    db.commit();

    try (var filteredSession = youTrackDB.open(DB_NAME, "reader", "reader")) {
      try (final var resultSet =
          filteredSession.query("SELECT from Person where name = ?", "bar")) {
        Assert.assertEquals(0, resultSet.stream().count());
      }

      try (final var resultSet =
          filteredSession.query("SELECT from Person where name = ?", "foo")) {
        Assert.assertEquals(1, resultSet.stream().count());
      }
    }
  }

  @Test
  public void testReadHiddenColumn() {
    db.begin();
    db.command("CREATE SECURITY POLICY testPolicy SET read = (name = 'bar')");
    db.command("ALTER ROLE reader SET POLICY testPolicy ON database.class.Person.name");
    db.commit();

    db.begin();
    var elem = db.newEntity("Person");
    elem.setProperty("name", "foo");
    elem.setProperty("surname", "foo");
    db.commit();

    db.close();

    db = youTrackDB.open(DB_NAME, "reader", "reader");
    try (final var resultSet = db.query("SELECT from Person")) {
      var item = resultSet.next();
      Assert.assertNull(item.getProperty("name"));
    }
  }

  @Test
  public void testUpdateHiddenColumn() {
    db.begin();
    db.command("CREATE SECURITY POLICY testPolicy SET read = (name = 'bar')");
    db.command("ALTER ROLE reader SET POLICY testPolicy ON database.class.Person.name");
    db.commit();

    db.begin();
    var elem = db.newEntity("Person");
    elem.setProperty("name", "foo");
    elem.setProperty("surname", "foo");
    db.commit();

    db.close();

    db = youTrackDB.open(DB_NAME, "reader", "reader");
    try (final var resultSet = db.query("SELECT from Person")) {
      try {
        db.begin();
        var item = resultSet.next();
        var doc = item.asEntity();
        doc.setProperty("name", "bar");

        db.commit();
        Assert.fail();
      } catch (Exception e) {

      }
    }
  }
}
