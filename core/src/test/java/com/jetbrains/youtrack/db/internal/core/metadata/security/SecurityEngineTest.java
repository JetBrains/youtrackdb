package com.jetbrains.youtrack.db.internal.core.metadata.security;

import com.jetbrains.youtrack.db.api.YourTracks;
import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.api.config.YouTrackDBConfig;
import com.jetbrains.youtrack.db.api.exception.RecordNotFoundException;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.core.CreateDatabaseUtil;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrack.db.internal.core.db.YouTrackDBImpl;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

public class SecurityEngineTest {

  static YouTrackDBImpl youTrackDB;
  private DatabaseSessionEmbedded session;
  private static final String DB_NAME = "test";

  @BeforeClass
  public static void beforeClass() {
    youTrackDB =
        (YouTrackDBImpl) YourTracks.embedded(
            DbTestBase.getBaseDirectoryPath(SecurityEngineTest.class),
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
            + "' role admin)");
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
  public void testAllClasses() {
    var security = session.getSharedContext().getSecurity();
    session.createClass("Person");

    session.begin();
    var policy = security.createSecurityPolicy(session, "policy1");
    policy.setActive(true);
    policy.setReadRule("name = 'admin'");
    security.saveSecurityPolicy(session, policy);
    security.setSecurityPolicy(session, security.getRole(session, "admin"), "database.class.*",
        policy);
    session.commit();

    var pred =
        SecurityEngine.getPredicateForSecurityResource(
            session, (SecurityShared) security, "database.class.Person", SecurityPolicy.Scope.READ);

    Assert.assertEquals("name = \"admin\"", pred.toString());
  }

  @Test
  public void testSingleClass() {
    var security = session.getSharedContext().getSecurity();

    session.createClass("Person");

    session.begin();
    var policy = security.createSecurityPolicy(session, "policy1");
    policy.setActive(true);
    policy.setReadRule("name = 'foo'");
    security.saveSecurityPolicy(session, policy);
    security.setSecurityPolicy(session, security.getRole(session, "admin"), "database.class.Person",
        policy);
    session.commit();

    var pred =
        SecurityEngine.getPredicateForSecurityResource(
            session, (SecurityShared) security, "database.class.Person", SecurityPolicy.Scope.READ);

    Assert.assertEquals("name = \"foo\"", pred.toString());
  }

  @Test
  public void testSuperclass() {
    var security = session.getSharedContext().getSecurity();

    session.createClass("Person");
    session.createClass("Employee", "Person");

    session.begin();
    var policy = security.createSecurityPolicy(session, "policy1");
    policy.setActive(true);
    policy.setReadRule("name = 'foo'");
    security.saveSecurityPolicy(session, policy);
    security.setSecurityPolicy(session, security.getRole(session, "admin"), "database.class.Person",
        policy);
    session.commit();

    var pred =
        SecurityEngine.getPredicateForSecurityResource(
            session, (SecurityShared) security, "database.class.Employee",
            SecurityPolicy.Scope.READ);

    Assert.assertEquals("name = \"foo\"", pred.toString());
  }

  @Test
  public void testSuperclass2() {
    var security = session.getSharedContext().getSecurity();

    session.createClass("Person");
    session.createClass("Employee", "Person");

    session.begin();
    var policy = security.createSecurityPolicy(session, "policy1");
    policy.setActive(true);
    policy.setReadRule("name = 'foo'");
    security.saveSecurityPolicy(session, policy);
    security.setSecurityPolicy(session, security.getRole(session, "admin"), "database.class.Person",
        policy);

    policy = security.createSecurityPolicy(session, "policy2");
    policy.setActive(true);
    policy.setReadRule("name = 'bar'");
    security.saveSecurityPolicy(session, policy);
    security.setSecurityPolicy(
        session, security.getRole(session, "admin"), "database.class.Employee", policy);
    session.commit();

    var pred =
        SecurityEngine.getPredicateForSecurityResource(
            session, (SecurityShared) security, "database.class.Employee",
            SecurityPolicy.Scope.READ);

    Assert.assertEquals("name = \"bar\"", pred.toString());
  }

  @Test
  public void testSuperclass3() {
    var security = session.getSharedContext().getSecurity();

    session.createClass("Person");
    session.createClass("Employee", "Person");

    session.begin();
    var policy = security.createSecurityPolicy(session, "policy1");
    policy.setActive(true);
    policy.setReadRule("name = 'admin'");
    security.saveSecurityPolicy(session, policy);
    security.setSecurityPolicy(session, security.getRole(session, "admin"), "database.class.Person",
        policy);

    policy = security.createSecurityPolicy(session, "policy2");
    policy.setActive(true);
    policy.setReadRule("name = 'bar' OR name = 'admin'");
    security.saveSecurityPolicy(session, policy);
    security.setSecurityPolicy(session, security.getRole(session, "admin"), "database.class.*",
        policy);
    session.commit();

    var pred =
        SecurityEngine.getPredicateForSecurityResource(
            session, (SecurityShared) security, "database.class.Employee",
            SecurityPolicy.Scope.READ);

    Assert.assertEquals("name = \"admin\"", pred.toString());
  }

  @Test
  public void testTwoSuperclasses() {
    var security = session.getSharedContext().getSecurity();

    session.createClass("Person");
    session.createClass("Foo");
    session.createClass("Employee", "Person", "Foo");

    session.begin();
    var policy = security.createSecurityPolicy(session, "policy1");
    policy.setActive(true);
    policy.setReadRule("name = 'foo'");
    security.saveSecurityPolicy(session, policy);
    security.setSecurityPolicy(session, security.getRole(session, "admin"), "database.class.Person",
        policy);

    policy = security.createSecurityPolicy(session, "policy2");
    policy.setActive(true);
    policy.setReadRule("surname = 'bar'");
    security.saveSecurityPolicy(session, policy);
    security.setSecurityPolicy(session, security.getRole(session, "admin"), "database.class.Foo",
        policy);
    session.commit();

    var pred =
        SecurityEngine.getPredicateForSecurityResource(
            session, (SecurityShared) security, "database.class.Employee",
            SecurityPolicy.Scope.READ);

    Assert.assertTrue(
        "name = \"foo\" AND surname = \"bar\"".equals(pred.toString())
            || "surname = \"bar\" AND name = \"foo\"".equals(pred.toString()));
  }

  @Test
  public void testTwoRoles() {

    session.begin();
    session.execute(
        "Update OUser set roles = roles || (select from orole where name = 'reader') where name ="
            + " 'admin'");
    session.commit();
    session.close();
    session =
        (DatabaseSessionEmbedded)
            youTrackDB.open(DB_NAME, "admin", CreateDatabaseUtil.NEW_ADMIN_PASSWORD);

    var security = session.getSharedContext().getSecurity();

    session.createClass("Person");

    session.begin();
    var policy = security.createSecurityPolicy(session, "policy1");
    policy.setActive(true);
    policy.setReadRule("name = 'foo'");
    security.saveSecurityPolicy(session, policy);
    security.setSecurityPolicy(session, security.getRole(session, "admin"), "database.class.Person",
        policy);

    policy = security.createSecurityPolicy(session, "policy2");
    policy.setActive(true);
    policy.setReadRule("surname = 'bar'");
    security.saveSecurityPolicy(session, policy);
    security.setSecurityPolicy(session, security.getRole(session, "reader"),
        "database.class.Person", policy);
    session.commit();

    var pred =
        SecurityEngine.getPredicateForSecurityResource(
            session, (SecurityShared) security, "database.class.Person", SecurityPolicy.Scope.READ);

    Assert.assertTrue(
        "name = \"foo\" OR surname = \"bar\"".equals(pred.toString())
            || "surname = \"bar\" OR name = \"foo\"".equals(pred.toString()));
  }

  @Test
  public void testRecordFiltering() {
    var security = session.getSharedContext().getSecurity();

    session.createClass("Person");
    var rec1 =
        session.computeInTx(
            transaction -> {
              var record1 = session.newEntity("Person");
              record1.setProperty("name", "foo");
              return record1;
            });

    var rec2 =
        session.computeInTx(
            transaction -> {
              var record2 = session.newEntity("Person");
              record2.setProperty("name", "bar");
              return record2;
            });

    session.begin();
    var policy = security.createSecurityPolicy(session, "policy1");
    policy.setActive(true);
    policy.setReadRule("name = 'foo'");
    security.saveSecurityPolicy(session, policy);
    security.setSecurityPolicy(session, security.getRole(session, "admin"), "database.class.Person",
        policy);
    session.commit();

    session.executeInTx(transaction -> {
      var activeTx1 = session.getActiveTransaction();
      activeTx1.load(rec1);
      Assert.assertTrue(rec1.getIdentity().isPersistent());

      try {
        var activeTx = session.getActiveTransaction();
        activeTx.load(rec2);
        Assert.fail();
      } catch (RecordNotFoundException e) {
        // ignore
      }
    });
  }
}
