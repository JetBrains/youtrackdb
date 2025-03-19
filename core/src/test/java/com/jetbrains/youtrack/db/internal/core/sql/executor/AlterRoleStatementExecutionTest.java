package com.jetbrains.youtrack.db.internal.core.sql.executor;

import com.jetbrains.youtrack.db.internal.DbTestBase;
import org.junit.Assert;
import org.junit.Test;

/**
 *
 */
public class AlterRoleStatementExecutionTest extends DbTestBase {

  @Test
  public void testAddPolicy() {
    var security = session.getSharedContext().getSecurity();

    session.createClass("Person");

    session.begin();
    var policy = security.createSecurityPolicy(session, "testPolicy");
    policy.setActive(session, true);
    policy.setReadRule(session, "name = 'foo'");
    security.saveSecurityPolicy(session, policy);
    session.execute("ALTER ROLE reader SET POLICY testPolicy ON database.class.Person").close();
    session.commit();

    session.begin();
    Assert.assertEquals(
        "testPolicy",
        security
            .getSecurityPolicies(session, security.getRole(session, "reader"))
            .get("database.class.Person")
            .getName(session));
    session.commit();
  }

  @Test
  public void testRemovePolicy() {
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

    session.begin();
    Assert.assertEquals(
        "testPolicy",
        security
            .getSecurityPolicies(session, security.getRole(session, "reader"))
            .get("database.class.Person")
            .getName(session));

    session.execute("ALTER ROLE reader REMOVE POLICY ON database.class.Person").close();
    session.commit();
    session.begin();

    Assert.assertNull(
        security
            .getSecurityPolicies(session, security.getRole(session, "reader"))
            .get("database.class.Person"));
    session.commit();
  }
}
