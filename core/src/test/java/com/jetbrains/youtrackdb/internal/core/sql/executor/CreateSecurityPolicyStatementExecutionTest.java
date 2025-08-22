package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import org.junit.Assert;
import org.junit.Test;

/**
 *
 */
public class CreateSecurityPolicyStatementExecutionTest extends DbTestBase {

  @Test
  public void testPlain() {
    session.begin();
    var result = session.execute("CREATE SECURITY POLICY foo");
    result.close();
    session.commit();

    session.begin();
    var security = session.getSharedContext().getSecurity();
    Assert.assertNotNull(security.getSecurityPolicy(session, "foo"));
    session.commit();
  }
}
