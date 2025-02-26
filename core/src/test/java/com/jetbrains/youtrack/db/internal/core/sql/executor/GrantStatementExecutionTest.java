package com.jetbrains.youtrack.db.internal.core.sql.executor;

import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.core.metadata.security.Role;
import com.jetbrains.youtrack.db.internal.core.metadata.security.Rule;
import org.junit.Assert;
import org.junit.Test;

/**
 *
 */
public class GrantStatementExecutionTest extends DbTestBase {

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
    session.command("GRANT execute on server.remove to testRole");
    session.commit();

    session.begin();
    testRole = session.getMetadata().getSecurity().getRole("testRole");
    Assert.assertTrue(
        testRole.allow(Rule.ResourceGeneric.SERVER, "remove", Role.PERMISSION_EXECUTE));
    session.commit();
  }

  @Test
  public void testGrantPolicy() {
    var security = session.getSharedContext().getSecurity();

    session.createClass("Person");

    session.begin();
    var policy = security.createSecurityPolicy(session, "testPolicy");
    policy.setActive(session, true);
    policy.setReadRule(session, "name = 'foo'");
    security.saveSecurityPolicy(session, policy);
    session.command("GRANT POLICY testPolicy ON database.class.Person TO reader").close();
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
}
