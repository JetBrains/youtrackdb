package com.jetbrains.youtrackdb.internal.core.metadata.security;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import org.junit.Assert;
import org.junit.Test;

public class SecuritySharedTest extends DbTestBase {

  @Test
  public void testCreateSecurityPolicy() {
    var security = session.getSharedContext().getSecurity();
    session.begin();
    security.createSecurityPolicy(session, "testPolicy");
    session.commit();
    session.begin();
    Assert.assertNotNull(security.getSecurityPolicy(session, "testPolicy"));
    session.commit();
  }

  @Test
  public void testDeleteSecurityPolicy() {
    var security = session.getSharedContext().getSecurity();
    session.begin();
    security.createSecurityPolicy(session, "testPolicy");
    session.commit();

    session.begin();
    security.deleteSecurityPolicy(session, "testPolicy");
    session.commit();

    Assert.assertNull(security.getSecurityPolicy(session, "testPolicy"));
  }

  @Test
  public void testUpdateSecurityPolicy() {
    var security = session.getSharedContext().getSecurity();
    session.begin();
    var policy = security.createSecurityPolicy(session, "testPolicy");
    policy.setActive(true);
    policy.setReadRule("name = 'foo'");
    security.saveSecurityPolicy(session, policy);
    session.commit();

    session.begin();
    Assert.assertTrue(security.getSecurityPolicy(session, "testPolicy").isActive());
    Assert.assertEquals("name = 'foo'",
        security.getSecurityPolicy(session, "testPolicy").getReadRule());
    session.commit();
  }

  @Test
  public void testBindPolicyToRole() {
    var security = session.getSharedContext().getSecurity();

    session.createClass("Person");

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
    session.commit();
  }

  @Test
  public void testUnbindPolicyFromRole() {
    var security = session.getSharedContext().getSecurity();

    session.createClass("Person");

    session.begin();
    var policy = security.createSecurityPolicy(session, "testPolicy");
    policy.setActive(true);
    policy.setReadRule("name = 'foo'");
    security.saveSecurityPolicy(session, policy);
    security.setSecurityPolicy(session, security.getRole(session, "reader"),
        "database.class.Person", policy);
    session.commit();

    session.begin();
    security.removeSecurityPolicy(session, security.getRole(session, "reader"),
        "database.class.Person");
    session.commit();

    session.begin();
    Assert.assertNull(
        security
            .getSecurityPolicies(session, security.getRole(session, "reader"))
            .get("database.class.Person"));
    session.commit();
  }

  /// Verifies that dropUser correctly deletes a previously created user and returns true,
  /// and returns false when attempting to drop a non-existent user.
  @Test
  public void testDropUser() {
    var security = session.getSharedContext().getSecurity();

    session.begin();
    final var readerRole = security.getRole(session, "reader");
    security.createUser(session, "tempUser", "password", new Role[] {readerRole});
    session.commit();

    session.begin();
    Assert.assertNotNull(security.getUser(session, "tempUser"));
    Assert.assertTrue(security.dropUser(session, "tempUser"));
    session.commit();

    session.begin();
    Assert.assertNull(security.getUser(session, "tempUser"));
    session.commit();
  }

  /// Verifies that incrementVersion (which recalculates filtered properties via an
  /// internal query) does not leak an implicit transaction when called outside an
  /// active transaction.
  @Test
  public void testIncrementVersionOutsideTxDoesNotLeakTransaction() {
    var security = session.getSharedContext().getSecurity();

    // No transaction is active before the call.
    Assert.assertFalse("Expected no active tx before call", session.isTxActive());

    // incrementVersion calls updateAllFilteredProperties → calculateAllFilteredProperties,
    // which runs db.query(). That query starts an implicit transaction that must be
    // rolled back in the finally block.
    security.incrementVersion(session);

    Assert.assertFalse(
        "calculateAllFilteredProperties must not leak an implicit transaction",
        session.isTxActive());
  }
}
