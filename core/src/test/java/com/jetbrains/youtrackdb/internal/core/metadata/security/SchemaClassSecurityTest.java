package com.jetbrains.youtrackdb.internal.core.metadata.security;

import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.api.gremlin.YTDBGraph;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.CreateDatabaseUtil;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import org.apache.commons.configuration2.BaseConfiguration;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

public class SchemaClassSecurityTest {

  private static final String DB_NAME = SchemaClassSecurityTest.class.getSimpleName();
  private static YouTrackDBImpl youTrackDB;
  private DatabaseSessionInternal session;
  private YTDBGraph graph;

  @BeforeClass
  public static void beforeClass() {
    var config = new BaseConfiguration();
    config.setProperty(GlobalConfiguration.CREATE_DEFAULT_USERS.getKey(), false);
    youTrackDB =
        (YouTrackDBImpl) YourTracks.instance(
            DbTestBase.getBaseDirectoryPath(SecurityEngineTest.class),
            config);
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
    graph = youTrackDB.openGraph(DB_NAME, "admin",
        CreateDatabaseUtil.NEW_ADMIN_PASSWORD);
    this.session = (DatabaseSessionInternal) youTrackDB.open(DB_NAME, "admin",
        CreateDatabaseUtil.NEW_ADMIN_PASSWORD);
  }

  @After
  public void after() {
    this.session.close();
    graph.close();
    youTrackDB.drop(DB_NAME);
    this.session = null;
  }

  @Test
  public void testReadWithClassPermissions() {
    graph.autoExecuteInTx(g -> g.addSchemaClass("Person"));

    session.begin();
    var reader = session.getMetadata().getSecurity().getRole("reader");
    reader.grant(session, Rule.ResourceGeneric.CLASS, "Person", Role.PERMISSION_NONE);
    reader.revoke(session, Rule.ResourceGeneric.CLASS, "Person", Role.PERMISSION_READ);
    reader.save(session);
    session.commit();

    session.begin();
    var elem = session.newEntity("Person");
    elem.setProperty("name", "foo");
    elem.setProperty("surname", "foo");
    session.commit();

    session.close();

    session = (DatabaseSessionInternal) youTrackDB.open(DB_NAME, "reader",
        CreateDatabaseUtil.NEW_ADMIN_PASSWORD); // "reader"
    session.begin();
    try (final var resultSet = session.query("SELECT from Person")) {
      Assert.assertFalse(resultSet.hasNext());
    }
    session.commit();
  }
}
