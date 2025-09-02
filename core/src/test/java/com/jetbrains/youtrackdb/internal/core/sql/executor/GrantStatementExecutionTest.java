package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.metadata.security.Role;
import com.jetbrains.youtrackdb.internal.core.metadata.security.Rule;
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
    session.execute("GRANT execute on server.remove to testRole");
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
    policy.setActive(true);
    policy.setReadRule("name = 'foo'");
    security.saveSecurityPolicy(session, policy);
    session.execute("GRANT POLICY testPolicy ON database.class.Person TO reader").close();
    session.commit();

    session.begin();
    Assert.assertEquals(
        "testPolicy",
        security
            .getSecurityPolicies(session, security.getRole(session, "reader"))
            .get("database.class.Person")
            .getName());
    session.commit();
  }
}
