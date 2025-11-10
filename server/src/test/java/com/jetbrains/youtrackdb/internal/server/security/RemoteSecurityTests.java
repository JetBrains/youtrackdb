package com.jetbrains.youtrackdb.internal.server.security;

import com.jetbrains.youtrackdb.api.exception.SecurityException;
import com.jetbrains.youtrackdb.api.record.RID;
import com.jetbrains.youtrackdb.api.remote.RemoteDatabaseSession;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBRemoteImpl;
import com.jetbrains.youtrackdb.internal.server.YouTrackDBServer;
import java.io.IOException;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

public class RemoteSecurityTests {

  private static final String DB_NAME = RemoteSecurityTests.class.getSimpleName();
  private YouTrackDBRemoteImpl youTrackDB;
  private YouTrackDBServer server;
  private RemoteDatabaseSession session;

  @Before
  public void before()
      throws ClassNotFoundException, InstantiationException, IllegalAccessException, IOException {
    server = YouTrackDBServer.startFromClasspathConfig("abstract-youtrackdb-server-config.xml");
    youTrackDB = (YouTrackDBRemoteImpl) YouTrackDBRemoteImpl.remote("remote:localhost", "root",
        "root");
    youTrackDB.execute(
        "create database ? memory users (admin identified by 'admin' role admin, writer identified"
            + " by 'writer' role writer, reader identified by 'reader' role reader)",
        DB_NAME);
    this.session = youTrackDB.open(DB_NAME, "admin", "admin");

    session.command("CREATE CLASS Person");
    session.command("CREATE PROPERTY Person.name String");
  }

  @After
  public void after() {
    this.session.close();
    youTrackDB.drop(DB_NAME);
    youTrackDB.close();
    server.shutdown();
  }

  @Test
  @Ignore
  public void testCreate() {
    session.command("BEGIN");
    session.command("CREATE SECURITY POLICY testPolicy SET create = (name = 'foo')");
    session.command("ALTER ROLE writer SET POLICY testPolicy ON database.class.Person");
    session.command("COMMIT");

    try (var filteredSession = youTrackDB.open(DB_NAME, "writer", "writer")) {
      filteredSession.command("BEGIN");
      filteredSession.command("INSERT INTO Person (name) VALUES ('foo')");
      filteredSession.command("COMMIT");
      try {
        filteredSession.command("BEGIN");
        filteredSession.command("INSERT INTO Person (name) VALUES ('bar')");
        filteredSession.command("COMMIT");
        Assert.fail();
      } catch (SecurityException ex) {
        //expected
      }
    }
  }

  @Ignore
  @Test
  public void testSqlCreate() {
    session.command("BEGIN");
    session.command("CREATE SECURITY POLICY testPolicy SET create = (name = 'foo')");
    session.command("ALTER ROLE writer SET POLICY testPolicy ON database.class.Person");
    session.command("COMMIT");

    try (var filteredSession = youTrackDB.open(DB_NAME, "writer", "writer")) {
      filteredSession.command("BEGIN");
      filteredSession.command("insert into Person SET name = 'foo'");
      filteredSession.command("COMMIT");
      try {
        filteredSession.command("BEGIN");
        filteredSession.command("insert into Person SET name = 'bar'");
        filteredSession.command("COMMIT");
        Assert.fail();
      } catch (SecurityException ex) {
        //expected
      }
    }
  }

  @Ignore
  @Test
  public void testSqlRead() {
    session.command("BEGIN");
    session.command("CREATE SECURITY POLICY testPolicy SET read = (name = 'foo')");
    session.command("ALTER ROLE reader SET POLICY testPolicy ON database.class.Person");
    session.command("COMMIT");

    session.command("BEGIN");
    session.command("INSERT INTO Person SET name = 'foo'");
    session.command("INSERT INTO Person SET name = 'bar'");
    session.command("COMMIT");

    session.close();

    try (var filteredSession = youTrackDB.open(DB_NAME, "reader", "reader")) {
      filteredSession.command("BEGIN");
      try (var rs = filteredSession.query("select from Person")) {
        Assert.assertTrue(rs.hasNext());
        rs.next();
        Assert.assertFalse(rs.hasNext());
      }
      filteredSession.command("COMMIT");
    }
  }

  @Ignore
  @Test
  public void testSqlReadWithIndex() {
    session.computeScript("sql", "create index Person.name on Person (name) NOTUNIQUE");

    session.command("BEGIN");
    session.command("CREATE SECURITY POLICY testPolicy SET read = (name = 'foo')");
    session.command("ALTER ROLE reader SET POLICY testPolicy ON database.class.Person");
    session.command("COMMIT");

    session.command("BEGIN");
    session.command("INSERT INTO Person SET name = 'foo'");
    session.command("INSERT INTO Person SET name = 'bar'");
    session.command("COMMIT");

    try (var filteredSession = youTrackDB.open(DB_NAME, "reader", "reader")) {
      filteredSession.command("BEGIN");
      try (var rs = filteredSession.query("select from Person where name = 'bar'")) {

        Assert.assertFalse(rs.hasNext());
      }
      filteredSession.command("COMMIT");
    }
  }

  @Ignore
  @Test
  public void testSqlReadWithIndex2() {
    session.computeScript("sql", "create index Person.name on Person(name)NOTUNIQUE");

    session.command("BEGIN");
    session.command("CREATE SECURITY POLICY testPolicy SET read = (surname = 'foo')");
    session.command("ALTER ROLE reader SET POLICY testPolicy ON database.class.Person");
    session.command("COMMIT");

    session.computeScript("sql", """
        BEGIN;
        INSERT INTO Person SET name = 'foo', surname = 'foo';
        INSERT INTO Person SET name = 'foo', surname = 'bar';
        COMMIT;
        """).close();

    try (var filteredSession = youTrackDB.open(DB_NAME, "reader", "reader")) {
      filteredSession.command("BEGIN");
      try (var rs = filteredSession.query("select from Person where name = 'foo'")) {
        Assert.assertTrue(rs.hasNext());
        var item = rs.next();
        Assert.assertEquals("foo", item.getProperty("surname"));
        Assert.assertFalse(rs.hasNext());
      }
      filteredSession.command("COMMIT");
    }
  }

  @Ignore
  @Test
  public void testBeforeUpdateCreate() {
    session.computeSQLScript("""
        BEGIN;
        CREATE SECURITY POLICY testPolicy SET BEFORE UPDATE = (name = 'bar');
        ALTER ROLE writer SET POLICY testPolicy ON database.class.Person;
        COMMIT;
        """).close();
    RID rid = null;

    try (var filteredSession = youTrackDB.open(DB_NAME, "writer", "writer")) {
      try {
        rid = filteredSession.computeSQLScript("""
            BEGIN;
            let res = INSERT INTO Person SET name = 'foo';
            COMMIT;
            return $res
            """).findFirst().getIdentity();

        filteredSession.computeSQLScript("""
            BEGIN;
            UPDATE Person SET name = 'baz' where name = 'foo';
            COMMIT;
            """).close();
        Assert.fail();
      } catch (SecurityException ex) {
        //expected
      }

      filteredSession.command("BEGIN");
      Assert.assertEquals("foo",
          filteredSession.query("select name from " + rid).findFirst().getProperty("name"));
      filteredSession.command("COMMIT");
    }
  }

  @Ignore
  @Test
  public void testBeforeUpdateCreateSQL() {
    session.command("BEGIN");
    session.command("CREATE SECURITY POLICY testPolicy SET BEFORE UPDATE = (name = 'bar')");
    session.command("ALTER ROLE writer SET POLICY testPolicy ON database.class.Person");
    session.command("COMMIT");

    try (var filteredSession = youTrackDB.open(DB_NAME, "writer", "writer")) {
      var rid = filteredSession.computeSQLScript("""
          BEGIN;
          let res = INSERT INTO Person SET name = 'foo';
          COMMIT;
          return $res
          """).findFirst().getIdentity();
      try {
        filteredSession.command("BEGIN");
        filteredSession.command("update Person set name = 'bar'");
        filteredSession.command("COMMIT");
        Assert.fail();
      } catch (SecurityException ex) {
        //expected
      }

      filteredSession.command("BEGIN");
      Assert.assertEquals("foo",
          filteredSession.query("select name from " + rid).findFirst().getProperty("name"));
      filteredSession.command("COMMIT");
    }
  }

  @Ignore
  @Test
  public void testAfterUpdate() {
    session.command("BEGIN");
    session.command("CREATE SECURITY POLICY testPolicy SET AFTER UPDATE = (name = 'foo')");
    session.command("ALTER ROLE writer SET POLICY testPolicy ON database.class.Person");
    session.command("COMMIT");

    try (var filteredSession = youTrackDB.open(DB_NAME, "writer", "writer")) {
      var rid = filteredSession.computeSQLScript("""
          BEGIN;
          let res = INSERT INTO Person SET name = 'foo';
          COMMIT;
          return $res
          """).findFirst().getIdentity();
      try {
        filteredSession.command("BEGIN");
        filteredSession.command("update Person set name = 'bar' where @rid = ?", rid);
        filteredSession.command("COMMIT");
        Assert.fail();
      } catch (SecurityException ex) {
        //expected
      }

      filteredSession.command("BEGIN");
      Assert.assertEquals("foo",
          filteredSession.query("select name from " + rid).findFirst().getProperty("name"));
      filteredSession.command("COMMIT");
    }
  }

  @Ignore
  @Test
  public void testAfterUpdateSQL() {
    session.command("BEGIN");
    session.command("CREATE SECURITY POLICY testPolicy SET AFTER UPDATE = (name = 'foo')");
    session.command("ALTER ROLE writer SET POLICY testPolicy ON database.class.Person");
    session.command("COMMIT");

    try (var filteredSession = youTrackDB.open(DB_NAME, "writer", "writer")) {
      var rid = filteredSession.computeSQLScript("""
          BEGIN;
          let res = INSERT INTO Person SET name = 'foo';
          COMMIT;
          return $res
          """).findFirst().getIdentity();
      try {
        filteredSession.command("BEGIN");
        filteredSession.command("update Person set name = 'bar'");
        filteredSession.command("COMMIT");
        Assert.fail();
      } catch (SecurityException ex) {
        //expected
      }

      filteredSession.command("BEGIN");
      Assert.assertEquals("foo",
          filteredSession.query("select name from " + rid).findFirst().getProperty("name"));
      filteredSession.command("COMMIT");
    }
  }

  @Ignore
  @Test
  public void testDelete() {
    session.command("BEGIN");
    session.command("CREATE SECURITY POLICY testPolicy SET DELETE = (name = 'foo')");
    session.command("ALTER ROLE writer SET POLICY testPolicy ON database.class.Person");
    session.command("COMMIT");

    try (var filteredSession = youTrackDB.open(DB_NAME, "writer", "writer")) {
      var rid = filteredSession.computeSQLScript("""
          BEGIN;
          let res = INSERT INTO Person SET name = 'bar';
          COMMIT;
          return $res
          """).findFirst().getIdentity();
      try {
        filteredSession.executeSQLScript("""
            BEGIN;
            DELETE FROM Person where @rid = ?;
            COMMIT;
            """, rid);
        Assert.fail();
      } catch (SecurityException ex) {
        //expected
      }

      filteredSession.executeSQLScript("""
          BEGIN;
          let entity = insert into Person SET name = 'foo';
          delete from $entity;
          COMMIT;
          """);
    }
  }

  @Ignore
  @Test
  public void testDeleteSQL() {
    session.command("BEGIN");
    session.command("CREATE SECURITY POLICY testPolicy SET DELETE = (name = 'foo')");
    session.command("ALTER ROLE writer SET POLICY testPolicy ON database.class.Person");
    session.command("COMMIT");

    try (var filteredSession = youTrackDB.open(DB_NAME, "writer", "writer")) {
      filteredSession.executeSQLScript("""
          begin;
          insert into Person SET name = 'foo';
          insert into Person SET name = 'bar';
          commit;
          begin;
          delete from Person where name = 'foo';
          commit;
          """);
      try {
        filteredSession.executeSQLScript("""
            begin;
            delete from Person where name = 'bar';
            commit;
            """);
        Assert.fail();
      } catch (SecurityException ex) {
        //expected
      }

      try (var rs = filteredSession.query("select  from Person")) {
        var result = rs.next();
        Assert.assertEquals("bar", result.getProperty("name"));
        Assert.assertFalse(rs.hasNext());
      }
    }
  }

  @Ignore
  @Test
  public void testSqlCount() {
    session.command("BEGIN");
    session.command("CREATE SECURITY POLICY testPolicy SET read = (name = 'foo')");
    session.command("ALTER ROLE reader SET POLICY testPolicy ON database.class.Person");
    session.command("COMMIT");

    session.executeSQLScript("""
        begin;
        insert into Person SET name = 'foo';
        insert into Person SET name = 'bar';
        commit;
        """);

    try (var filteredSession = youTrackDB.open(DB_NAME, "reader", "reader")) {
      long count = filteredSession.query("select count(*)  as count from Person")
          .findFirst(remoteResult -> remoteResult.getLong("count"));
      Assert.assertEquals(1, count);
    }
  }

  @Ignore
  @Test
  public void testSqlCountWithIndex() {
    session.executeSQLScript("create index Person.name on Person (name) NOTUNIQUE");

    session.command("BEGIN");
    session.command("CREATE SECURITY POLICY testPolicy SET read = (name = 'foo')");
    session.command("ALTER ROLE reader SET POLICY testPolicy ON database.class.Person");
    session.command("COMMIT");

    session.executeSQLScript("""
        begin;
        insert into Person SET name = 'foo';
        insert into Person SET name = 'bar';
        commit;
        """);
    session.close();
    try (var filteredSession = youTrackDB.open(DB_NAME, "reader", "reader")) {
      filteredSession.command("begin");
      var count = filteredSession.query("select count(*) as count from Person where name = 'bar'").
          findFirst(remoteResult -> remoteResult.getLong("count"));
      Assert.assertEquals(0, count.longValue());

      count = filteredSession.query("select count(*) as count from Person where name = 'foo'").
          findFirst(remoteResult -> remoteResult.getLong("count"));
      Assert.assertEquals(1, count.longValue());

      filteredSession.command("commit");
    }
  }

  @Ignore
  @Test
  public void testIndexGet() {
    session.computeScript("sql", "create index Person.name on Person (name) NOTUNIQUE");

    session.command("BEGIN");
    session.command("CREATE SECURITY POLICY testPolicy SET read = (name = 'foo')");
    session.command("ALTER ROLE reader SET POLICY testPolicy ON database.class.Person");
    session.command("COMMIT");

    session.executeSQLScript("""
        begin;
        insert into Person SET name = 'foo';
        insert into Person SET name = 'bar';
        commit;
        """);

    try (var filteredSession = youTrackDB.open(DB_NAME, "reader", "reader")) {
      long count = filteredSession.query("select count(*) as count from Person where name = 'bar'")
          .findFirst(remoteResult -> remoteResult.getLong("count"));
      Assert.assertEquals(0, count);

      count = filteredSession.query("select count(*) as count from Person where name = 'foo'")
          .findFirst(remoteResult -> remoteResult.getLong("count"));
      Assert.assertEquals(1, count);
    }
  }

  @Ignore
  @Test
  public void testIndexGetAndColumnSecurity() {
    session.computeScript("sql", "create index Person.name on Person (name) NOTUNIQUE");

    session.command("BEGIN");
    session.command("CREATE SECURITY POLICY testPolicy SET read = (name = 'foo')");
    session.command("ALTER ROLE reader SET POLICY testPolicy ON database.class.Person.name");
    session.command("COMMIT");

    session.executeSQLScript("""
        begin;
        insert into Person SET name = 'foo';
        insert into Person SET name = 'bar';
        commit;
        """);

    try (var filteredSession = youTrackDB.open(DB_NAME, "reader", "reader")) {
      long count = filteredSession.query("select count(*) as count from Person where name = 'bar'")
          .findFirst(remoteResult -> remoteResult.getLong("count"));
      Assert.assertEquals(0, count);

      count = filteredSession.query("select count(*) as count from Person where name = 'foo'")
          .findFirst(remoteResult -> remoteResult.getLong("count"));
      Assert.assertEquals(1, count);
    }
  }

  @Ignore
  @Test
  public void testReadHiddenColumn() {
    session.command("begin");
    session.command("CREATE SECURITY POLICY testPolicy SET read = (name = 'bar')");
    session.command("ALTER ROLE reader SET POLICY testPolicy ON database.class.Person.name");
    session.command("commit");

    session.executeSQLScript("""
        begin;
        insert into Person SET name = 'foo', surname = 'foo';
        commit;
        """);
    session.close();

    try (var filteredSession = youTrackDB.open(DB_NAME, "reader", "reader")) {
      try (var rs = filteredSession.query("select from Person")) {
        var result = rs.next();
        Assert.assertNull(result.getString("name"));
        Assert.assertFalse(rs.hasNext());
      }
    }
  }

  @Ignore
  @Test
  public void testUpdateHiddenColumn() {
    session.command("begin");
    session.command("CREATE SECURITY POLICY testPolicy SET read = (name = 'bar')");
    session.command("ALTER ROLE reader SET POLICY testPolicy ON database.class.Person.name");
    session.command("commit");

    session.executeSQLScript("""
        begin;
        insert into Person SET name = 'foo', surname = 'foo';
        commit;
        """);
    session.close();

    try (var filteredSession = youTrackDB.open(DB_NAME, "reader", "reader")) {
      try {
        filteredSession.executeSQLScript("""
            begin;
            update Person set name = 'bar';
            commit;
            """);
      } catch (SecurityException e) {
        //expected
      }
    }
  }
}
