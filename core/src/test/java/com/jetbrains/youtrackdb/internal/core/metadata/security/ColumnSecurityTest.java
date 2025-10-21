package com.jetbrains.youtrackdb.internal.core.metadata.security;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.api.exception.SecurityException;
import com.jetbrains.youtrackdb.api.gremlin.YTDBGraph;
import com.jetbrains.youtrackdb.api.gremlin.embedded.domain.YTDBSchemaIndex.IndexType;
import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.CreateDatabaseUtil;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.index.IndexException;
import com.jetbrains.youtrackdb.internal.core.sql.executor.FetchFromIndexStep;
import org.apache.commons.configuration2.BaseConfiguration;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;

@Ignore
public class ColumnSecurityTest {

  static String DB_NAME = "test";
  static YouTrackDBImpl context;
  private DatabaseSessionInternal session;
  private YTDBGraph graph;

  @BeforeClass
  public static void beforeClass() {
    var config = new BaseConfiguration();
    config.setProperty(GlobalConfiguration.CREATE_DEFAULT_USERS.getKey(), false);
    context =
        (YouTrackDBImpl) YourTracks.instance(
            DbTestBase.getBaseDirectoryPath(ColumnSecurityTest.class),
            config);
  }

  @AfterClass
  public static void afterClass() {
    context.close();
  }

  @Before
  public void before() {
    context.create("test", DatabaseType.MEMORY,
        "admin", CreateDatabaseUtil.NEW_ADMIN_PASSWORD, "admin",
        "writer", "writer", "writer",
        "reader", "reader", "reader");
    graph = context.openGraph(DB_NAME, "admin",
        CreateDatabaseUtil.NEW_ADMIN_PASSWORD);
    this.session = (DatabaseSessionInternal) context.open(DB_NAME, "admin",
        CreateDatabaseUtil.NEW_ADMIN_PASSWORD);
  }

  @After
  public void after() {
    this.session.close();
    graph.close();
    context.drop("test");
    this.session = null;
  }

  @Test
  public void testIndexWithPolicy() {
    var security = session.getSharedContext().getSecurity();

    graph.autoExecuteInTx(g ->
        g.createSchemaClass("Person").createSchemaProperty("name", PropertyType.STRING)
    );

    session.begin();
    var policy = security.createSecurityPolicy(session, "testPolicy");
    policy.setActive(true);
    policy.setReadRule("name = 'foo'");
    security.saveSecurityPolicy(session, policy);
    security.setSecurityPolicy(
        session, security.getRole(session, "reader"), "database.class.Person.name", policy);
    session.commit();

    session.execute("create index Person.name on Person (name) NOTUNIQUE");
  }

  @Test
  public void testIndexWithPolicy1() throws InterruptedException {
    var security = session.getSharedContext().getSecurity();

    graph.autoExecuteInTx(g ->
        g.createSchemaClass("Person").as("cl").
            createSchemaProperty("name", PropertyType.STRING).select("cl").
            createSchemaProperty("surname", PropertyType.STRING)
    );

    session.begin();
    var policy = security.createSecurityPolicy(session, "testPolicy");
    policy.setActive(true);
    policy.setReadRule("name = 'foo'");
    security.saveSecurityPolicy(session, policy);
    security.setSecurityPolicy(
        session, security.getRole(session, "reader"), "database.class.Person.name", policy);
    session.commit();

    Thread.sleep(100);
    try {
      session.execute("create index Person.name_surname on Person (name, surname) NOTUNIQUE");
      Assert.fail();
    } catch (IndexException ignored) {
    }
  }

  @Test
  public void testIndexWithPolicy2() {
    var security = session.getSharedContext().getSecurity();

    graph.autoExecuteInTx(
        g ->
            g.createSchemaClass("Person").
                createSchemaProperty("name", PropertyType.STRING)
    );

    session.begin();
    var policy = security.createSecurityPolicy(session, "testPolicy");
    policy.setActive(true);
    policy.setCreateRule("name = 'foo'");
    policy.setBeforeUpdateRule("name = 'foo'");
    policy.setAfterUpdateRule("name = 'foo'");
    policy.setDeleteRule("name = 'foo'");

    security.saveSecurityPolicy(session, policy);
    security.setSecurityPolicy(
        session, security.getRole(session, "reader"), "database.class.Person.name", policy);
    session.commit();

    session.execute("create index Person.name on Person (name) NOTUNIQUE");
  }

  @Test
  public void testIndexWithPolicy3() {
    var security = session.getSharedContext().getSecurity();

    graph.autoExecuteInTx(g ->
        g.createSchemaClass("Person").
            createSchemaProperty("name", PropertyType.STRING)
    );

    session.begin();
    var policy = security.createSecurityPolicy(session, "testPolicy");
    policy.setActive(true);
    policy.setReadRule("name = 'foo'");
    security.saveSecurityPolicy(session, policy);
    security.setSecurityPolicy(
        session, security.getRole(session, "reader"), "database.class.Person.surname", policy);
    session.commit();

    session.execute("create index Person.name on Person (name) NOTUNIQUE");
  }

  @Test
  public void testIndexWithPolicy4() {
    var security = session.getSharedContext().getSecurity();

    graph.autoExecuteInTx(
        g -> g.createSchemaClass("Person").as("cl").
            createSchemaProperty("name", PropertyType.STRING).select("cl").
            createSchemaProperty("address", PropertyType.STRING)

    );

    session.execute("create index Person.name_address on Person (name, address) NOTUNIQUE");

    session.begin();
    var policy = security.createSecurityPolicy(session, "testPolicy");
    policy.setActive(true);
    policy.setReadRule("name = 'foo'");
    security.saveSecurityPolicy(session, policy);
    security.setSecurityPolicy(
        session, security.getRole(session, "reader"), "database.class.Person.surname", policy);
    session.commit();
  }

  @Test
  public void testIndexWithPolicy5() {
    var security = session.getSharedContext().getSecurity();

    graph.autoExecuteInTx(g ->
        g.createSchemaClass("Person").as("cl").
            createSchemaProperty("name", PropertyType.STRING).select("cl").
            createSchemaProperty("surname", PropertyType.STRING)
    );

    session.execute("create index Person.name_surname on Person (name, surname) NOTUNIQUE");

    session.begin();
    var policy = security.createSecurityPolicy(session, "testPolicy");
    policy.setActive(true);
    policy.setReadRule("name = 'foo'");
    security.saveSecurityPolicy(session, policy);

    try {
      security.setSecurityPolicy(
          session, security.getRole(session, "reader"), "database.class.Person.name", policy);
      Assert.fail();
    } catch (Exception ignored) {
    }
    session.commit();
  }

  @Test
  public void testIndexWithPolicy6() {
    var security = session.getSharedContext().getSecurity();

    graph.autoExecuteInTx(g ->
        g.createSchemaClass("Person").
            createSchemaProperty("name", PropertyType.STRING)
    );

    session.execute("create index Person.name on Person (name) NOTUNIQUE");

    session.begin();
    var policy = security.createSecurityPolicy(session, "testPolicy");
    policy.setActive(true);
    policy.setReadRule("name = 'foo'");
    security.saveSecurityPolicy(session, policy);
    security.setSecurityPolicy(
        session, security.getRole(session, "reader"), "database.class.Person.name", policy);
    session.commit();
  }

  @Test
  public void testReadFilterOneProperty() {
    var security = session.getSharedContext().getSecurity();

    graph.autoExecuteInTx(g -> g.createSchemaClass("Person"));

    session.begin();
    var policy = security.createSecurityPolicy(session, "testPolicy");
    policy.setActive(true);
    policy.setReadRule("name = 'foo'");
    security.saveSecurityPolicy(session, policy);
    security.setSecurityPolicy(
        session, security.getRole(session, "reader"), "database.class.Person.name", policy);

    var elem = session.newEntity("Person");
    elem.setProperty("name", "foo");

    elem = session.newEntity("Person");
    elem.setProperty("name", "bar");
    session.commit();

    session.close();
    this.session = (DatabaseSessionInternal) context.open(DB_NAME, "reader", "reader");
    session.begin();
    var rs = session.query("select from Person");
    var fooFound = false;
    var nullFound = false;

    for (var i = 0; i < 2; i++) {
      var item = rs.next();
      if ("foo".equals(item.getProperty("name"))) {
        fooFound = true;
      }
      if (item.getProperty("name") == null) {
        nullFound = true;
      }
    }

    Assert.assertFalse(rs.hasNext());
    rs.close();

    Assert.assertTrue(fooFound);
    Assert.assertTrue(nullFound);
    session.commit();
  }

  @Test
  public void testReadFilterOnePropertyWithIndex() {
    var security = session.getSharedContext().getSecurity();

    graph.autoExecuteInTx(g ->
        g.createSchemaClass("Person").
            createSchemaProperty("name", PropertyType.STRING)
            .createPropertyIndex(IndexType.NOT_UNIQUE)
    );

    session.begin();
    var policy = security.createSecurityPolicy(session, "testPolicy");
    policy.setActive(true);
    policy.setReadRule("name = 'foo'");
    security.saveSecurityPolicy(session, policy);
    security.setSecurityPolicy(
        session, security.getRole(session, "reader"), "database.class.Person.name", policy);

    var elem = session.newEntity("Person");
    elem.setProperty("name", "foo");

    elem = session.newEntity("Person");
    elem.setProperty("name", "bar");
    session.commit();

    session.close();
    this.session = (DatabaseSessionInternal) context.open(DB_NAME, "reader", "reader");
    session.begin();
    var rs = session.query("select from Person where name = 'foo'");
    Assert.assertTrue(rs.hasNext());
    rs.next();
    Assert.assertFalse(rs.hasNext());
    rs.close();
    session.commit();

    session.begin();
    rs = session.query("select from Person where name = 'bar'");
    Assert.assertFalse(rs.hasNext());
    Assert.assertTrue(
        rs.getExecutionPlan().getSteps().stream()
            .anyMatch(x -> x instanceof FetchFromIndexStep));
    rs.close();
    session.commit();
  }

  @Test
  public void testReadWithPredicateAndQuery() throws InterruptedException {
    var security = session.getSharedContext().getSecurity();

    graph.autoExecuteInTx(g -> g.createSchemaClass("Person"));

    session.begin();
    var policy = security.createSecurityPolicy(session, "testPolicy");
    policy.setActive(true);
    policy.setReadRule("name IN (select 'foo' as foo)");
    security.saveSecurityPolicy(session, policy);
    security.setSecurityPolicy(
        session, security.getRole(session, "reader"), "database.class.Person.name", policy);

    var elem = session.newEntity("Person");
    elem.setProperty("name", "foo");

    elem = session.newEntity("Person");
    elem.setProperty("name", "bar");
    session.commit();

    session.begin();
    var rs = session.query("select from Person");
    var fooFound = false;
    var barFound = false;

    for (var i = 0; i < 2; i++) {
      var item = rs.next();
      if ("foo".equals(item.getProperty("name"))) {
        fooFound = true;
      }
      if ("bar".equals(item.getProperty("name"))) {
        barFound = true;
      }
    }

    Assert.assertFalse(rs.hasNext());
    rs.close();

    Assert.assertTrue(fooFound);
    Assert.assertTrue(barFound);
    session.commit();

    session.close();
    Thread.sleep(200);

    this.session = (DatabaseSessionInternal) context.open(DB_NAME, "reader", "reader");
    session.begin();
    rs = session.query("select from Person");
    fooFound = false;
    var nullFound = false;

    for (var i = 0; i < 2; i++) {
      var item = rs.next();
      if ("foo".equals(item.getProperty("name"))) {
        fooFound = true;
      }
      if (item.getProperty("name") == null) {
        nullFound = true;
      }
    }

    Assert.assertFalse(rs.hasNext());
    rs.close();

    Assert.assertTrue(fooFound);
    Assert.assertTrue(nullFound);
    session.commit();
  }

  @Test
  public void testReadFilterOnePropertyWithQuery() {
    var security = session.getSharedContext().getSecurity();

    graph.autoExecuteInTx(g -> g.createSchemaClass("Person"));

    session.begin();
    var policy = security.createSecurityPolicy(session, "testPolicy");
    policy.setActive(true);
    policy.setReadRule("name = 'foo'");
    security.saveSecurityPolicy(session, policy);
    security.setSecurityPolicy(
        session, security.getRole(session, "reader"), "database.class.Person.name", policy);

    var elem = session.newEntity("Person");
    elem.setProperty("name", "foo");
    elem.setProperty("surname", "foo");

    elem = session.newEntity("Person");
    elem.setProperty("name", "bar");
    elem.setProperty("surname", "bar");
    session.commit();

    session.close();

    this.session = (DatabaseSessionInternal) context.open(DB_NAME, "reader", "reader");
    session.begin();
    var rs = session.query("select from Person where name = 'foo' OR name = 'bar'");

    var item = rs.next();
    Assert.assertEquals("foo", item.getProperty("name"));

    Assert.assertFalse(rs.hasNext());
    rs.close();
    session.commit();
  }

  @Test
  public void testCreate() {
    var security = session.getSharedContext().getSecurity();

    graph.autoExecuteInTx(g -> g.createSchemaClass("Person"));

    session.begin();
    var policy = security.createSecurityPolicy(session, "testPolicy");
    policy.setActive(true);
    policy.setCreateRule("name = 'foo'");
    security.saveSecurityPolicy(session, policy);
    security.setSecurityPolicy(
        session, security.getRole(session, "writer"), "database.class.Person.name", policy);
    session.commit();

    session.close();
    this.session = (DatabaseSessionInternal) context.open(DB_NAME, "writer", "writer");

    session.begin();
    var elem = session.newEntity("Person");
    elem.setProperty("name", "foo");
    elem.setProperty("surname", "foo");

    elem = session.newEntity("Person");
    elem.setProperty("name", "bar");
    elem.setProperty("surname", "bar");
    try {
      session.commit();
      Assert.fail();
    } catch (SecurityException ignored) {
    }
  }

  @Test
  public void testBeforeUpdate() {
    var security = session.getSharedContext().getSecurity();

    graph.autoExecuteInTx(g -> g.createSchemaClass("Person"));

    session.begin();
    var policy = security.createSecurityPolicy(session, "testPolicy");
    policy.setActive(true);
    policy.setBeforeUpdateRule("name = 'foo'");
    security.saveSecurityPolicy(session, policy);
    security.setSecurityPolicy(
        session, security.getRole(session, "writer"), "database.class.Person.name", policy);

    var elem = session.newEntity("Person");
    elem.setProperty("name", "foo");
    elem.setProperty("surname", "foo");

    elem = session.newEntity("Person");
    elem.setProperty("name", "bar");
    elem.setProperty("surname", "bar");
    session.commit();

    session.close();

    this.session = (DatabaseSessionInternal) context.open(DB_NAME, "writer", "writer");
    session.begin();
    session.execute("UPDATE Person SET name = 'foo1' WHERE name = 'foo'");
    session.commit();

    session.begin();
    try (var rs = session.query("SELECT FROM Person WHERE name = 'foo1'")) {
      Assert.assertTrue(rs.hasNext());
      rs.next();
      Assert.assertFalse(rs.hasNext());
    }
    session.commit();

    try {
      session.begin();
      session.execute("UPDATE Person SET name = 'bar1' WHERE name = 'bar'");
      session.commit();
      Assert.fail();
    } catch (SecurityException ignored) {

    }
  }

  @Test
  public void testAfterUpdate() {
    var security = session.getSharedContext().getSecurity();

    graph.autoExecuteInTx(g -> g.createSchemaClass("Person"));

    session.begin();
    var policy = security.createSecurityPolicy(session, "testPolicy");
    policy.setActive(true);
    policy.setAfterUpdateRule("name <> 'invalid'");
    security.saveSecurityPolicy(session, policy);
    security.setSecurityPolicy(
        session, security.getRole(session, "writer"), "database.class.Person.name", policy);

    var elem = session.newEntity("Person");
    elem.setProperty("name", "foo");
    elem.setProperty("surname", "foo");
    session.commit();

    session.close();
    this.session = (DatabaseSessionInternal) context.open(DB_NAME, "writer", "writer");

    session.begin();
    session.execute("UPDATE Person SET name = 'foo1' WHERE name = 'foo'");

    try (var rs = session.query("SELECT FROM Person WHERE name = 'foo1'")) {
      Assert.assertTrue(rs.hasNext());
      rs.next();
      Assert.assertFalse(rs.hasNext());
    }
    session.commit();

    try {
      session.begin();
      session.execute("UPDATE Person SET name = 'invalid'");
      session.commit();
      Assert.fail();
    } catch (SecurityException ignored) {

    }
  }

  @Test
  public void testReadHiddenColumn() {
    session.execute("CREATE CLASS Person");
    session.begin();
    session.execute("CREATE SECURITY POLICY testPolicy SET read = (name = 'bar')");
    session.execute("ALTER ROLE reader SET POLICY testPolicy ON database.class.Person.name");

    var elem = session.newEntity("Person");
    elem.setProperty("name", "foo");
    elem.setProperty("surname", "foo");
    session.commit();

    session.close();

    session = (DatabaseSessionInternal) context.open(DB_NAME, "reader", "reader");
    session.begin();
    try (final var resultSet = session.query("SELECT from Person")) {
      var item = resultSet.next();
      Assert.assertNull(item.getProperty("name"));
    }
    session.commit();
  }

  @Test
  public void testUpdateHiddenColumn() {
    session.execute("CREATE CLASS Person");

    session.begin();
    session.execute("CREATE SECURITY POLICY testPolicy SET read = (name = 'bar')");
    session.execute("ALTER ROLE reader SET POLICY testPolicy ON database.class.Person.name");

    var elem = session.newEntity("Person");
    elem.setProperty("name", "foo");
    elem.setProperty("surname", "foo");
    session.commit();

    session.close();

    session = (DatabaseSessionInternal) context.open(DB_NAME, "reader", "reader");
    session.begin();
    try (final var resultSet = session.query("SELECT from Person")) {
      var item = resultSet.next();
      Assert.assertNull(item.getProperty("name"));
      var doc = item.asEntity();
      doc.setProperty("name", "bar");
      try {
        session.commit();
        Assert.fail();
      } catch (Exception ignored) {

      }
    }
  }
}
