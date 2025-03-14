package com.jetbrains.youtrack.db.internal.core.metadata.security;

import com.jetbrains.youtrack.db.api.YouTrackDB;
import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.api.config.YouTrackDBConfig;
import com.jetbrains.youtrack.db.api.exception.SecurityException;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.internal.core.CreateDatabaseUtil;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.YouTrackDBImpl;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

public class PredicateSecurityTest {

  private static final String DB_NAME = PredicateSecurityTest.class.getSimpleName();
  private static YouTrackDB youTrackDB;
  private DatabaseSessionInternal session;

  @BeforeClass
  public static void beforeClass() {
    youTrackDB =
        new YouTrackDBImpl(
            "plocal:.",
            YouTrackDBConfig.builder()
                .addGlobalConfigurationParameter(GlobalConfiguration.CREATE_DEFAULT_USERS, false)
                .build());
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
        (DatabaseSessionInternal)
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
    policy.setActive(session, true);
    policy.setCreateRule(session, "name = 'foo'");
    security.saveSecurityPolicy(session, policy);
    security.setSecurityPolicy(session, security.getRole(session, "writer"),
        "database.class.Person", policy);
    session.commit();

    session.close();
    this.session =
        (DatabaseSessionInternal)
            youTrackDB.open(DB_NAME, "writer", CreateDatabaseUtil.NEW_ADMIN_PASSWORD); // "writer"

    session.executeInTx(
        () -> {
          var elem = session.newEntity("Person");
          elem.setProperty("name", "foo");
        });
    try {
      session.executeInTx(
          () -> {
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
    policy.setActive(session, true);
    policy.setCreateRule(session, "name = 'foo'");
    security.saveSecurityPolicy(session, policy);
    security.setSecurityPolicy(session, security.getRole(session, "writer"),
        "database.class.Person", policy);
    session.commit();

    session.close();
    Thread.sleep(500);
    this.session =
        (DatabaseSessionInternal)
            youTrackDB.open(DB_NAME, "writer", CreateDatabaseUtil.NEW_ADMIN_PASSWORD); // "writer"

    session.begin();
    session.command("insert into Person SET name = 'foo'");
    session.commit();

    try {
      session.begin();
      session.command("insert into Person SET name = 'bar'");
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
    policy.setActive(session, true);
    policy.setReadRule(session, "name = 'foo'");
    security.saveSecurityPolicy(session, policy);
    security.setSecurityPolicy(session, security.getRole(session, "reader"),
        "database.class.Person", policy);
    session.commit();

    session.executeInTx(
        () -> {
          var elem = session.newEntity("Person");
          elem.setProperty("name", "foo");
        });

    session.executeInTx(
        () -> {
          var elem = session.newEntity("Person");
          elem.setProperty("name", "bar");
        });

    session.close();
    this.session =
        (DatabaseSessionInternal)
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
    session.command("create index Person.name on Person (name) NOTUNIQUE");

    session.begin();
    var policy = security.createSecurityPolicy(session, "testPolicy");
    policy.setActive(session, true);
    policy.setReadRule(session, "name = 'foo'");
    security.saveSecurityPolicy(session, policy);
    security.setSecurityPolicy(session, security.getRole(session, "reader"),
        "database.class.Person", policy);
    session.commit();

    session.executeInTx(
        () -> {
          var elem = session.newEntity("Person");
          elem.setProperty("name", "foo");
        });

    session.executeInTx(
        () -> {
          var elem = session.newEntity("Person");
          elem.setProperty("name", "bar");
        });

    session.close();
    this.session =
        (DatabaseSessionInternal)
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
    session.command("create index Person.name on Person (name) NOTUNIQUE");

    session.begin();
    var policy = security.createSecurityPolicy(session, "testPolicy");
    policy.setActive(session, true);
    policy.setReadRule(session, "surname = 'foo'");
    security.saveSecurityPolicy(session, policy);
    security.setSecurityPolicy(session, security.getRole(session, "reader"),
        "database.class.Person", policy);
    session.commit();

    session.executeInTx(
        () -> {
          var elem = session.newEntity("Person");
          elem.setProperty("name", "foo");
          elem.setProperty("surname", "foo");
        });

    session.executeInTx(
        () -> {
          var elem = session.newEntity("Person");
          elem.setProperty("name", "foo");
          elem.setProperty("surname", "bar");
        });

    session.close();
    this.session =
        (DatabaseSessionInternal)
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
    policy.setActive(session, true);
    policy.setBeforeUpdateRule(session, "name = 'bar'");
    security.saveSecurityPolicy(session, policy);
    security.setSecurityPolicy(session, security.getRole(session, "writer"),
        "database.class.Person", policy);
    session.commit();

    session.close();
    Thread.sleep(500);
    this.session =
        (DatabaseSessionInternal)
            youTrackDB.open(DB_NAME, "writer", CreateDatabaseUtil.NEW_ADMIN_PASSWORD); // "writer"

    var elem =
        session.computeInTx(
            () -> {
              var e = session.newEntity("Person");
              e.setProperty("name", "foo");
              return e;
            });

    try {
      session.begin();
      elem = session.bindToSession(elem);
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
    policy.setActive(session, true);
    policy.setBeforeUpdateRule(session, "name = 'bar'");
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
        (DatabaseSessionInternal)
            youTrackDB.open(DB_NAME, "writer", CreateDatabaseUtil.NEW_ADMIN_PASSWORD); // "writer"

    var elem =
        session.computeInTx(
            () -> {
              var e = session.newEntity("Person");
              e.setProperty("name", "foo");
              return e;
            });

    try {
      session.begin();
      session.command("update Person set name = 'bar'");
      session.commit();
      return false;
    } catch (SecurityException ex) {
    }

    session.begin();
    Assert.assertEquals("foo", session.bindToSession(elem).getProperty("name"));
    session.commit();
    return true;
  }

  @Test
  public void testAfterUpdate() {
    var security = session.getSharedContext().getSecurity();

    session.createClass("Person");

    session.begin();
    var policy = security.createSecurityPolicy(session, "testPolicy");
    policy.setActive(session, true);
    policy.setAfterUpdateRule(session, "name = 'foo'");
    security.saveSecurityPolicy(session, policy);
    security.setSecurityPolicy(session, security.getRole(session, "writer"),
        "database.class.Person", policy);
    session.commit();

    session.close();
    this.session =
        (DatabaseSessionInternal)
            youTrackDB.open(DB_NAME, "writer", CreateDatabaseUtil.NEW_ADMIN_PASSWORD); // "writer"

    var elem =
        session.computeInTx(
            () -> {
              var e = session.newEntity("Person");
              e.setProperty("name", "foo");
              return e;
            });

    try {
      session.begin();
      elem = session.bindToSession(elem);
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
    policy.setActive(session, true);
    policy.setAfterUpdateRule(session, "name = 'foo'");
    security.saveSecurityPolicy(session, policy);
    security.setSecurityPolicy(session, security.getRole(session, "writer"),
        "database.class.Person", policy);
    session.commit();

    session.close();
    this.session =
        (DatabaseSessionInternal)
            youTrackDB.open(DB_NAME, "writer", CreateDatabaseUtil.NEW_ADMIN_PASSWORD); // "writer"

    var elem =
        session.computeInTx(
            () -> {
              var e = session.newEntity("Person");
              e.setProperty("name", "foo");
              return e;
            });

    try {
      session.begin();
      session.command("update Person set name = 'bar'");
      session.commit();
      Assert.fail();
    } catch (SecurityException ex) {
    }

    session.begin();
    Assert.assertEquals("foo", session.bindToSession(elem).getProperty("name"));
    session.commit();
  }

  @Test
  public void testDelete() {
    var security = session.getSharedContext().getSecurity();

    session.createClass("Person");

    session.begin();
    var policy = security.createSecurityPolicy(session, "testPolicy");
    policy.setActive(session, true);
    policy.setDeleteRule(session, "name = 'foo'");
    security.saveSecurityPolicy(session, policy);
    security.setSecurityPolicy(session, security.getRole(session, "writer"),
        "database.class.Person", policy);
    session.commit();

    session.close();
    this.session =
        (DatabaseSessionInternal)
            youTrackDB.open(DB_NAME, "writer", CreateDatabaseUtil.NEW_ADMIN_PASSWORD); // "writer"

    var elem =
        session.computeInTx(
            () -> {
              var e = session.newEntity("Person");
              e.setProperty("name", "bar");
              return e;
            });

    try {
      var elemToDelete = elem;
      session.executeInTx(() -> session.delete(session.bindToSession(elemToDelete)));
      Assert.fail();
    } catch (SecurityException ex) {
    }

    elem =
        session.computeInTx(
            () -> {
              var e = session.newEntity("Person");
              e.setProperty("name", "foo");
              return e;
            });

    var elemToDelete = elem;
    session.executeInTx(() -> session.delete(session.bindToSession(elemToDelete)));
  }

  @Test
  public void testDeleteSQL() {
    var security = session.getSharedContext().getSecurity();

    session.createClass("Person");

    session.begin();
    var policy = security.createSecurityPolicy(session, "testPolicy");
    policy.setActive(session, true);
    policy.setDeleteRule(session, "name = 'foo'");
    security.saveSecurityPolicy(session, policy);
    security.setSecurityPolicy(session, security.getRole(session, "writer"),
        "database.class.Person", policy);
    session.commit();

    session.close();
    this.session =
        (DatabaseSessionInternal)
            youTrackDB.open(DB_NAME, "writer", CreateDatabaseUtil.NEW_ADMIN_PASSWORD); // "writer"

    session.executeInTx(
        () -> {
          var elem = session.newEntity("Person");
          elem.setProperty("name", "foo");
        });

    session.executeInTx(
        () -> {
          var elem = session.newEntity("Person");
          elem.setProperty("name", "bar");
        });

    session.begin();
    session.command("delete from Person where name = 'foo'");
    session.commit();
    try {
      session.begin();
      session.command("delete from Person where name = 'bar'");
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
    policy.setActive(session, true);
    policy.setReadRule(session, "name = 'foo'");
    security.saveSecurityPolicy(session, policy);
    security.setSecurityPolicy(session, security.getRole(session, "reader"),
        "database.class.Person", policy);
    session.commit();

    session.executeInTx(
        () -> {
          var elem = session.newEntity("Person");
          elem.setProperty("name", "foo");
        });

    session.executeInTx(
        () -> {
          var elem = session.newEntity("Person");
          elem.setProperty("name", "bar");
        });

    session.close();
    this.session =
        (DatabaseSessionInternal)
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
    session.command("create index Person.name on Person (name) NOTUNIQUE");

    session.begin();
    var policy = security.createSecurityPolicy(session, "testPolicy");
    policy.setActive(session, true);
    policy.setReadRule(session, "name = 'foo'");
    security.saveSecurityPolicy(session, policy);
    security.setSecurityPolicy(session, security.getRole(session, "reader"),
        "database.class.Person", policy);
    session.commit();

    session.executeInTx(
        () -> {
          var e = session.newEntity("Person");
          e.setProperty("name", "foo");
        });

    session.executeInTx(
        () -> {
          var elem = session.newEntity("Person");
          elem.setProperty("name", "bar");
        });

    session.close();
    this.session =
        (DatabaseSessionInternal)
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
    session.command("create index Person.name on Person (name) NOTUNIQUE");

    session.begin();
    var policy = security.createSecurityPolicy(session, "testPolicy");
    policy.setActive(session, true);
    policy.setReadRule(session, "name = 'foo'");
    security.saveSecurityPolicy(session, policy);
    security.setSecurityPolicy(session, security.getRole(session, "reader"),
        "database.class.Person", policy);
    session.commit();

    session.executeInTx(
        () -> {
          var elem = session.newEntity("Person");
          elem.setProperty("name", "foo");
        });

    session.executeInTx(
        () -> {
          var elem = session.newEntity("Person");
          elem.setProperty("name", "bar");
        });

    session.close();
    this.session =
        (DatabaseSessionInternal)
            youTrackDB.open(DB_NAME, "reader", CreateDatabaseUtil.NEW_ADMIN_PASSWORD); // "reader"

    var index = session.getMetadata().getIndexManager().getIndex("Person.name");

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
