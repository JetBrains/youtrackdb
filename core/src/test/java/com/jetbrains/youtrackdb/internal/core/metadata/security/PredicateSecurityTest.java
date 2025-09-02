package com.jetbrains.youtrackdb.internal.core.metadata.security;

import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.api.exception.SecurityException;
import com.jetbrains.youtrackdb.api.record.Entity;
import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.CreateDatabaseUtil;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import org.apache.commons.configuration2.BaseConfiguration;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

public class PredicateSecurityTest {

  private static final String DB_NAME = PredicateSecurityTest.class.getSimpleName();
  private static YouTrackDBImpl youTrackDB;
  private DatabaseSessionEmbedded session;

  @BeforeClass
  public static void beforeClass() {
    var config = new BaseConfiguration();
    config.setProperty(GlobalConfiguration.CREATE_DEFAULT_USERS.getKey(), false);
    youTrackDB =
        (YouTrackDBImpl) YourTracks.instance(
            DbTestBase.getBaseDirectoryPath(PredicateSecurityTest.class), config);
  }

  @AfterClass
  public static void afterClass() {
    youTrackDB.close();
  }

  @Before
  public void before() {
    youTrackDB.execute(
        "create database "
            + DB_NAME
            + " "
            + "memory"
            + " users ( admin identified by '"
            + CreateDatabaseUtil.NEW_ADMIN_PASSWORD
            + "' role admin, reader identified by '"
            + CreateDatabaseUtil.NEW_ADMIN_PASSWORD
            + "' role reader, writer identified by '"
            + CreateDatabaseUtil.NEW_ADMIN_PASSWORD
            + "' role writer)");
    this.session =
        (DatabaseSessionEmbedded)
            youTrackDB.open(DB_NAME, "admin", CreateDatabaseUtil.NEW_ADMIN_PASSWORD);
  }

  @After
  public void after() {
    this.session.close();
    youTrackDB.drop(DB_NAME);
    this.session = null;
  }

  @Test
  public void testCreate() {
    var security = session.getSharedContext().getSecurity();

    session.createClass("Person");

    session.begin();
    var policy = security.createSecurityPolicy(session, "testPolicy");
    policy.setActive(true);
    policy.setCreateRule("name = 'foo'");
    security.saveSecurityPolicy(session, policy);
    security.setSecurityPolicy(session, security.getRole(session, "writer"),
        "database.class.Person", policy);
    session.commit();

    session.close();
    this.session =
        (DatabaseSessionEmbedded)
            youTrackDB.open(DB_NAME, "writer", CreateDatabaseUtil.NEW_ADMIN_PASSWORD); // "writer"

    session.executeInTx(
        transaction -> {
          var elem = session.newEntity("Person");
          elem.setProperty("name", "foo");
        });
    try {
      session.executeInTx(
          transaction -> {
            var elem = session.newEntity("Person");
            elem.setProperty("name", "bar");
          });

      Assert.fail();
    } catch (SecurityException ex) {
    }
  }

  @Test
  public void testSqlCreate() throws InterruptedException {
    var security = session.getSharedContext().getSecurity();

    session.createClass("Person");

    session.begin();
    var policy = security.createSecurityPolicy(session, "testPolicy");
    policy.setActive(true);
    policy.setCreateRule("name = 'foo'");
    security.saveSecurityPolicy(session, policy);
    security.setSecurityPolicy(session, security.getRole(session, "writer"),
        "database.class.Person", policy);
    session.commit();

    session.close();
    Thread.sleep(500);
    this.session =
        (DatabaseSessionEmbedded)
            youTrackDB.open(DB_NAME, "writer", CreateDatabaseUtil.NEW_ADMIN_PASSWORD); // "writer"

    session.begin();
    session.execute("insert into Person SET name = 'foo'");
    session.commit();

    try {
      session.begin();
      session.execute("insert into Person SET name = 'bar'");
      session.commit();
      Assert.fail();
    } catch (SecurityException ex) {
    }
  }

  @Test
  public void testSqlRead() {
    var security = session.getSharedContext().getSecurity();

    session.createClass("Person");

    session.begin();
    var policy = security.createSecurityPolicy(session, "testPolicy");
    policy.setActive(true);
    policy.setReadRule("name = 'foo'");
    security.saveSecurityPolicy(session, policy);
    security.setSecurityPolicy(session, security.getRole(session, "reader"),
        "database.class.Person", policy);
    session.commit();

    session.executeInTx(
        transaction -> {
          var elem = session.newEntity("Person");
          elem.setProperty("name", "foo");
        });

    session.executeInTx(
        transaction -> {
          var elem = session.newEntity("Person");
          elem.setProperty("name", "bar");
        });

    session.close();
    this.session =
        (DatabaseSessionEmbedded)
            youTrackDB.open(DB_NAME, "reader", CreateDatabaseUtil.NEW_ADMIN_PASSWORD); // "reader"
    session.begin();
    var rs = session.query("select from Person");
    Assert.assertTrue(rs.hasNext());
    rs.next();
    Assert.assertFalse(rs.hasNext());
    rs.close();
    session.commit();
  }

  @Test
  public void testSqlReadWithIndex() {
    var security = session.getSharedContext().getSecurity();

    var person = session.createClass("Person");
    person.createProperty("name", PropertyType.STRING);
    session.execute("create index Person.name on Person (name) NOTUNIQUE");

    session.begin();
    var policy = security.createSecurityPolicy(session, "testPolicy");
    policy.setActive(true);
    policy.setReadRule("name = 'foo'");
    security.saveSecurityPolicy(session, policy);
    security.setSecurityPolicy(session, security.getRole(session, "reader"),
        "database.class.Person", policy);
    session.commit();

    session.executeInTx(
        transaction -> {
          var elem = session.newEntity("Person");
          elem.setProperty("name", "foo");
        });

    session.executeInTx(
        transaction -> {
          var elem = session.newEntity("Person");
          elem.setProperty("name", "bar");
        });

    session.close();
    this.session =
        (DatabaseSessionEmbedded)
            youTrackDB.open(DB_NAME, "reader", CreateDatabaseUtil.NEW_ADMIN_PASSWORD); // "reader"
    session.begin();
    var rs = session.query("select from Person where name = 'bar'");
    Assert.assertFalse(rs.hasNext());
    rs.close();
    session.commit();
  }

  @Test
  public void testSqlReadWithIndex2() {
    var security = session.getSharedContext().getSecurity();

    var person = session.createClass("Person");
    person.createProperty("name", PropertyType.STRING);
    session.execute("create index Person.name on Person (name) NOTUNIQUE");

    session.begin();
    var policy = security.createSecurityPolicy(session, "testPolicy");
    policy.setActive(true);
    policy.setReadRule("surname = 'foo'");
    security.saveSecurityPolicy(session, policy);
    security.setSecurityPolicy(session, security.getRole(session, "reader"),
        "database.class.Person", policy);
    session.commit();

    session.executeInTx(
        transaction -> {
          var elem = session.newEntity("Person");
          elem.setProperty("name", "foo");
          elem.setProperty("surname", "foo");
        });

    session.executeInTx(
        transaction -> {
          var elem = session.newEntity("Person");
          elem.setProperty("name", "foo");
          elem.setProperty("surname", "bar");
        });

    session.close();
    this.session =
        (DatabaseSessionEmbedded)
            youTrackDB.open(DB_NAME, "reader", CreateDatabaseUtil.NEW_ADMIN_PASSWORD); // "reader"
    session.begin();
    var rs = session.query("select from Person where name = 'foo'");
    Assert.assertTrue(rs.hasNext());
    var item = rs.next();
    Assert.assertEquals("foo", item.getProperty("surname"));
    Assert.assertFalse(rs.hasNext());
    rs.close();
    session.commit();
  }

  @Test
  public void testBeforeUpdateCreate() throws InterruptedException {
    var security = session.getSharedContext().getSecurity();
    session.createClass("Person");

    session.begin();
    var policy = security.createSecurityPolicy(session, "testPolicy");
    policy.setActive(true);
    policy.setBeforeUpdateRule("name = 'bar'");
    security.saveSecurityPolicy(session, policy);
    security.setSecurityPolicy(session, security.getRole(session, "writer"),
        "database.class.Person", policy);
    session.commit();

    session.close();
    Thread.sleep(500);
    this.session =
        (DatabaseSessionEmbedded)
            youTrackDB.open(DB_NAME, "writer", CreateDatabaseUtil.NEW_ADMIN_PASSWORD); // "writer"

    var elem =
        session.computeInTx(
            transaction -> {
              var e = session.newEntity("Person");
              e.setProperty("name", "foo");
              return e;
            });

    try {
      session.begin();
      var activeTx = session.getActiveTransaction();
      elem = activeTx.load(elem);
      elem.setProperty("name", "baz");
      var elemToSave = elem;
      session.commit();
      Assert.fail();
    } catch (SecurityException ex) {

    }

    session.begin();
    elem = session.load(elem.getIdentity());
    Assert.assertEquals("foo", elem.getProperty("name"));
    session.commit();
  }

  @Test
  public void testBeforeUpdateCreateSQL() throws InterruptedException {
    var security = session.getSharedContext().getSecurity();

    session.createClass("Person");

    session.begin();
    var policy = security.createSecurityPolicy(session, "testPolicy");
    policy.setActive(true);
    policy.setBeforeUpdateRule("name = 'bar'");
    security.saveSecurityPolicy(session, policy);
    security.setSecurityPolicy(session, security.getRole(session, "writer"),
        "database.class.Person", policy);
    session.commit();

    session.close();

    if (!doTestBeforeUpdateSQL()) {
      session.close();
      Thread.sleep(500);
      if (!doTestBeforeUpdateSQL()) {
        Assert.fail();
      }
    }
  }

  private boolean doTestBeforeUpdateSQL() {
    this.session =
        (DatabaseSessionEmbedded)
            youTrackDB.open(DB_NAME, "writer", CreateDatabaseUtil.NEW_ADMIN_PASSWORD); // "writer"

    var elem =
        session.computeInTx(
            transaction -> {
              var e = session.newEntity("Person");
              e.setProperty("name", "foo");
              return e;
            });

    try {
      session.begin();
      session.execute("update Person set name = 'bar'");
      session.commit();
      return false;
    } catch (SecurityException ex) {
    }

    session.begin();
    var activeTx = session.getActiveTransaction();
    Assert.assertEquals("foo", activeTx.<Entity>load(elem).getProperty("name"));
    session.commit();
    return true;
  }

  @Test
  public void testAfterUpdate() {
    var security = session.getSharedContext().getSecurity();

    session.createClass("Person");

    session.begin();
    var policy = security.createSecurityPolicy(session, "testPolicy");
    policy.setActive(true);
    policy.setAfterUpdateRule("name = 'foo'");
    security.saveSecurityPolicy(session, policy);
    security.setSecurityPolicy(session, security.getRole(session, "writer"),
        "database.class.Person", policy);
    session.commit();

    session.close();
    this.session =
        (DatabaseSessionEmbedded)
            youTrackDB.open(DB_NAME, "writer", CreateDatabaseUtil.NEW_ADMIN_PASSWORD); // "writer"

    var elem =
        session.computeInTx(
            transaction -> {
              var e = session.newEntity("Person");
              e.setProperty("name", "foo");
              return e;
            });

    try {
      session.begin();
      var activeTx = session.getActiveTransaction();
      elem = activeTx.load(elem);
      elem.setProperty("name", "bar");
      session.commit();
      Assert.fail();
    } catch (SecurityException ex) {
    }

    session.begin();
    elem = session.load(elem.getIdentity());
    Assert.assertEquals("foo", elem.getProperty("name"));
    session.commit();
  }

  @Test
  public void testAfterUpdateSQL() {
    var security = session.getSharedContext().getSecurity();

    session.createClass("Person");

    session.begin();
    var policy = security.createSecurityPolicy(session, "testPolicy");
    policy.setActive(true);
    policy.setAfterUpdateRule("name = 'foo'");
    security.saveSecurityPolicy(session, policy);
    security.setSecurityPolicy(session, security.getRole(session, "writer"),
        "database.class.Person", policy);
    session.commit();

    session.close();
    this.session =
        (DatabaseSessionEmbedded)
            youTrackDB.open(DB_NAME, "writer", CreateDatabaseUtil.NEW_ADMIN_PASSWORD); // "writer"

    var elem =
        session.computeInTx(
            transaction -> {
              var e = session.newEntity("Person");
              e.setProperty("name", "foo");
              return e;
            });

    try {
      session.begin();
      session.execute("update Person set name = 'bar'");
      session.commit();
      Assert.fail();
    } catch (SecurityException ex) {
    }

    session.begin();
    var activeTx = session.getActiveTransaction();
    Assert.assertEquals("foo", activeTx.<Entity>load(elem).getProperty("name"));
    session.commit();
  }

  @Test
  public void testDelete() {
    var security = session.getSharedContext().getSecurity();

    session.createClass("Person");

    session.begin();
    var policy = security.createSecurityPolicy(session, "testPolicy");
    policy.setActive(true);
    policy.setDeleteRule("name = 'foo'");
    security.saveSecurityPolicy(session, policy);
    security.setSecurityPolicy(session, security.getRole(session, "writer"),
        "database.class.Person", policy);
    session.commit();

    session.close();
    this.session =
        (DatabaseSessionEmbedded)
            youTrackDB.open(DB_NAME, "writer", CreateDatabaseUtil.NEW_ADMIN_PASSWORD); // "writer"

    var elem =
        session.computeInTx(
            transaction -> {
              var e = session.newEntity("Person");
              e.setProperty("name", "bar");
              return e;
            });

    try {
      var elemToDelete = elem;
      session.executeInTx(transaction -> {
        var activeTx = session.getActiveTransaction();
        session.delete(activeTx.<Entity>load(elemToDelete));
      });
      Assert.fail();
    } catch (SecurityException ex) {
    }

    elem =
        session.computeInTx(
            transaction -> {
              var e = session.newEntity("Person");
              e.setProperty("name", "foo");
              return e;
            });

    var elemToDelete = elem;
    session.executeInTx(transaction -> {
      var activeTx = session.getActiveTransaction();
      session.delete(activeTx.<Entity>load(elemToDelete));
    });
  }

  @Test
  public void testDeleteSQL() {
    var security = session.getSharedContext().getSecurity();

    session.createClass("Person");

    session.begin();
    var policy = security.createSecurityPolicy(session, "testPolicy");
    policy.setActive(true);
    policy.setDeleteRule("name = 'foo'");
    security.saveSecurityPolicy(session, policy);
    security.setSecurityPolicy(session, security.getRole(session, "writer"),
        "database.class.Person", policy);
    session.commit();

    session.close();
    this.session =
        (DatabaseSessionEmbedded)
            youTrackDB.open(DB_NAME, "writer", CreateDatabaseUtil.NEW_ADMIN_PASSWORD); // "writer"

    session.executeInTx(
        transaction -> {
          var elem = session.newEntity("Person");
          elem.setProperty("name", "foo");
        });

    session.executeInTx(
        transaction -> {
          var elem = session.newEntity("Person");
          elem.setProperty("name", "bar");
        });

    session.begin();
    session.execute("delete from Person where name = 'foo'");
    session.commit();
    try {
      session.begin();
      session.execute("delete from Person where name = 'bar'");
      session.commit();
      Assert.fail();
    } catch (SecurityException ex) {
    }

    session.begin();
    var rs = session.query("select from Person");
    Assert.assertTrue(rs.hasNext());
    Assert.assertEquals("bar", rs.next().getProperty("name"));
    Assert.assertFalse(rs.hasNext());
    rs.close();
    session.commit();
  }

  @Test
  public void testSqlCount() {
    var security = session.getSharedContext().getSecurity();

    var person = session.createClass("Person");
    person.createProperty("name", PropertyType.STRING);

    session.begin();
    var policy = security.createSecurityPolicy(session, "testPolicy");
    policy.setActive(true);
    policy.setReadRule("name = 'foo'");
    security.saveSecurityPolicy(session, policy);
    security.setSecurityPolicy(session, security.getRole(session, "reader"),
        "database.class.Person", policy);
    session.commit();

    session.executeInTx(
        transaction -> {
          var elem = session.newEntity("Person");
          elem.setProperty("name", "foo");
        });

    session.executeInTx(
        transaction -> {
          var elem = session.newEntity("Person");
          elem.setProperty("name", "bar");
        });

    session.close();
    this.session =
        (DatabaseSessionEmbedded)
            youTrackDB.open(DB_NAME, "reader", CreateDatabaseUtil.NEW_ADMIN_PASSWORD); // "reader"
    session.begin();
    var rs = session.query("select count(*) as count from Person");
    Assert.assertEquals(1L, (long) rs.next().getProperty("count"));
    rs.close();
    session.commit();
  }

  @Test
  public void testSqlCountWithIndex() {
    var security = session.getSharedContext().getSecurity();

    var person = session.createClass("Person");
    person.createProperty("name", PropertyType.STRING);
    session.execute("create index Person.name on Person (name) NOTUNIQUE");

    session.begin();
    var policy = security.createSecurityPolicy(session, "testPolicy");
    policy.setActive(true);
    policy.setReadRule("name = 'foo'");
    security.saveSecurityPolicy(session, policy);
    security.setSecurityPolicy(session, security.getRole(session, "reader"),
        "database.class.Person", policy);
    session.commit();

    session.executeInTx(
        t -> {
          var e = session.newEntity("Person");
          e.setProperty("name", "foo");
        });

    session.executeInTx(
        transaction -> {
          var elem = session.newEntity("Person");
          elem.setProperty("name", "bar");
        });

    session.close();
    this.session =
        (DatabaseSessionEmbedded)
            youTrackDB.open(DB_NAME, "reader", CreateDatabaseUtil.NEW_ADMIN_PASSWORD); // "reader"
    session.begin();
    var rs = session.query("select count(*) as count from Person where name = 'bar'");
    Assert.assertEquals(0L, (long) rs.next().getProperty("count"));
    rs.close();

    rs = session.query("select count(*) as count from Person where name = 'foo'");
    Assert.assertEquals(1L, (long) rs.next().getProperty("count"));
    rs.close();
    session.commit();
  }

  @Test
  public void testIndexGet() {
    var security = session.getSharedContext().getSecurity();

    var person = session.createClass("Person");
    person.createProperty("name", PropertyType.STRING);
    session.execute("create index Person.name on Person (name) NOTUNIQUE");

    session.begin();
    var policy = security.createSecurityPolicy(session, "testPolicy");
    policy.setActive(true);
    policy.setReadRule("name = 'foo'");
    security.saveSecurityPolicy(session, policy);
    security.setSecurityPolicy(session, security.getRole(session, "reader"),
        "database.class.Person", policy);
    session.commit();

    session.executeInTx(
        transaction -> {
          var elem = session.newEntity("Person");
          elem.setProperty("name", "foo");
        });

    session.executeInTx(
        transaction -> {
          var elem = session.newEntity("Person");
          elem.setProperty("name", "bar");
        });

    session.close();
    this.session =
        (DatabaseSessionEmbedded)
            youTrackDB.open(DB_NAME, "reader", CreateDatabaseUtil.NEW_ADMIN_PASSWORD); // "reader"

    var index = session.getSharedContext().getIndexManager().getIndex("Person.name");

    session.begin();
    try (var rids = index.getRids(session, "bar")) {
      Assert.assertEquals(0, rids.count());
    }

    try (var rids = index.getRids(session, "foo")) {
      Assert.assertEquals(1, rids.count());
    }
    session.commit();
  }
}
