package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.gremlin.YTDBGraph;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.CreateDatabaseUtil;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.metadata.security.Role;
import com.jetbrains.youtrackdb.internal.core.metadata.security.Rule;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

public class RevokeStatementExecutionTest {

  static YouTrackDBImpl youTrackDB;
  private DatabaseSessionEmbedded session;
  private YTDBGraph graph;

  @BeforeClass
  public static void beforeClass() {
    youTrackDB = (YouTrackDBImpl) YourTracks.instance(
        DbTestBase.getBaseDirectoryPath(RevokeStatementExecutionTest.class));
  }

  @AfterClass
  public static void afterClass() {
    youTrackDB.close();
  }

  @Before
  public void before() {
    CreateDatabaseUtil.createDatabase("test", youTrackDB, CreateDatabaseUtil.TYPE_MEMORY);
    this.session = (DatabaseSessionEmbedded) youTrackDB.open("test", "admin",
        CreateDatabaseUtil.NEW_ADMIN_PASSWORD);
    graph = youTrackDB.openGraph("test", "admin",
        CreateDatabaseUtil.NEW_ADMIN_PASSWORD);
  }

  @After
  public void after() {
    this.session.close();
    graph.close();
    youTrackDB.drop("test");
    this.session = null;
  }

  @Test
  public void testSimple() {
    session.begin();
    var testRole =
        session.getMetadata()
            .getSecurity()
            .createRole("testRole");
    Assert.assertFalse(
        testRole.allow(Rule.ResourceGeneric.SERVER, "server", Role.PERMISSION_EXECUTE));
    session.commit();

    session.begin();
    session.execute("GRANT execute on server.remove to testRole");
    session.commit();
    session.begin();
    testRole = session.getMetadata().getSecurity().getRole("testRole");
    Assert.assertTrue(
        testRole.allow(Rule.ResourceGeneric.SERVER, "remove", Role.PERMISSION_EXECUTE));
    session.execute("REVOKE execute on server.remove from testRole");
    session.commit();
    session.begin();
    testRole = session.getMetadata().getSecurity().getRole("testRole");
    Assert.assertFalse(
        testRole.allow(Rule.ResourceGeneric.SERVER, "remove", Role.PERMISSION_EXECUTE));
    session.commit();
  }

  @Test
  public void testRemovePolicy() {
    var security = session.getSharedContext().getSecurity();

    graph.autoExecuteInTx(g -> g.addSchemaClass("Person"));

    session.begin();
    var policy = security.createSecurityPolicy(session, "testPolicy");
    policy.setActive(true);
    policy.setReadRule("name = 'foo'");
    security.saveSecurityPolicy(session, policy);
    security.setSecurityPolicy(session, security.getRole(session, "reader"),
        "database.class.Person", policy);
    session.commit();

    session.begin();
    Assert.assertEquals(
        "testPolicy",
        security
            .getSecurityPolicies(session, security.getRole(session, "reader"))
            .get("database.class.Person")
            .getName());

    session.execute("REVOKE POLICY ON database.class.Person FROM reader").close();
    session.commit();

    session.begin();
    Assert.assertNull(
        security
            .getSecurityPolicies(session, security.getRole(session, "reader"))
            .get("database.class.Person"));
    session.commit();
  }
}
