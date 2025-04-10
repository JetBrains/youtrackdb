package com.jetbrains.youtrack.db.internal.core.sql.executor;

import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.core.metadata.security.SecurityPolicy;
import org.junit.Assert;
import org.junit.Test;

/**
 *
 */
public class AlterSecurityPolicyStatementExecutionTest extends DbTestBase {

  @Test
  public void testPlain() {
    session.begin();
    session.execute("CREATE SECURITY POLICY foo").close();
    session.execute("ALTER SECURITY POLICY foo SET READ = (name = 'foo')").close();
    session.commit();

    session.begin();
    var security = session.getSharedContext().getSecurity();
    SecurityPolicy policy = security.getSecurityPolicy(session, "foo");
    Assert.assertNotNull(policy);
    Assert.assertNotNull("foo", policy.getName());
    Assert.assertEquals("name = \"foo\"", policy.getReadRule());
    Assert.assertNull(policy.getCreateRule());
    Assert.assertNull(policy.getBeforeUpdateRule());
    Assert.assertNull(policy.getAfterUpdateRule());
    Assert.assertNull(policy.getDeleteRule());
    Assert.assertNull(policy.getExecuteRule());

    session.execute("ALTER SECURITY POLICY foo REMOVE READ").close();
    session.commit();

    session.begin();
    policy = security.getSecurityPolicy(session, "foo");
    Assert.assertNotNull(policy);
    Assert.assertNull(policy.getReadRule());
    session.commit();
  }
}
