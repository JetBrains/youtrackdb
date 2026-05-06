package com.jetbrains.youtrackdb.internal.core.metadata.security;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.List;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

public class SecuritySharedTest extends DbTestBase {

  /**
   * Roll back any transaction left open by a failing test before the database is dropped.
   * JUnit 4 runs subclass {@code @After} methods before superclass ones, so this fires
   * ahead of the database teardown. Carry-forward convention from Tracks 8–16.
   */
  @After
  public void rollbackIfLeftOpen() {
    if (session != null && !session.isClosed() && session.isTxActive()) {
      session.rollback();
    }
  }

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

  /**
   * Verifies that dropUser correctly deletes a previously created user and returns true,
   * and returns false when attempting to drop a non-existent user.
   */
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

  /**
   * Verifies that incrementVersion (which recalculates filtered properties via an
   * internal query) does not leak an implicit transaction when called outside an
   * active transaction.
   */
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

  /**
   * Verifies that getAllUsers returns a non-empty list containing at least the admin user
   * that is created by DbTestBase when the database is set up.
   */
  @Test
  public void testGetAllUsersReturnsAtLeastAdminUser() {
    var security = session.getSharedContext().getSecurity();

    // getAllUsers runs its own computeInTx; access properties inside a fresh transaction
    // so the records are bound to the current session.
    session.begin();
    List<EntityImpl> users = security.getAllUsers(session);
    Assert.assertNotNull(users);
    Assert.assertFalse("getAllUsers must return at least the admin user", users.isEmpty());
    boolean foundAdmin = users.stream()
        .anyMatch(e -> adminUser.equals(e.getProperty("name")));
    session.commit();

    Assert.assertTrue("admin user must appear in getAllUsers", foundAdmin);
  }

  /**
   * Verifies that getAllRoles returns the default roles created for the test database
   * (admin, reader, writer at minimum).
   */
  @Test
  public void testGetAllRolesReturnsDefaultRoles() {
    var security = session.getSharedContext().getSecurity();

    session.begin();
    List<EntityImpl> roles = security.getAllRoles(session);
    Assert.assertNotNull(roles);
    boolean foundAdmin = roles.stream().anyMatch(e -> "admin".equals(e.getProperty("name")));
    boolean foundReader = roles.stream().anyMatch(e -> "reader".equals(e.getProperty("name")));
    int roleCount = roles.size();
    session.commit();

    Assert.assertTrue("There must be at least 3 default roles", roleCount >= 3);
    Assert.assertTrue(foundAdmin);
    Assert.assertTrue(foundReader);
  }

  /**
   * Verifies that dropRole correctly removes a previously created role,
   * and that the role is no longer visible after the transaction commits.
   */
  @Test
  public void testDropRoleRemovesRole() {
    var security = session.getSharedContext().getSecurity();

    session.begin();
    security.createRole(session, "tempRole");
    session.commit();

    session.begin();
    Assert.assertNotNull(security.getRole(session, "tempRole"));
    session.commit();

    // SecurityInternal.dropRole returns boolean (true if removed)
    session.begin();
    boolean removed = security.dropRole(session, "tempRole");
    session.commit();

    Assert.assertTrue("dropRole must return true for an existing role", removed);
    session.begin();
    Assert.assertNull(security.getRole(session, "tempRole"));
    session.commit();
  }

  /**
   * Verifies that createRole with a parent establishes the inheritance chain accessible
   * via getParentRole() on the returned role object.
   */
  @Test
  public void testCreateRoleWithParentSetsInheritance() {
    var security = session.getSharedContext().getSecurity();

    session.begin();
    var readerRole = security.getRole(session, "reader");
    var childRole = security.createRole(session, "childOfReader", readerRole);
    session.commit();

    Assert.assertNotNull(childRole.getParentRole());
    Assert.assertEquals("reader", childRole.getParentRole().getName(session));
  }

  /**
   * Verifies that getRoleRID returns the same RID as the Role loaded via getRole.
   */
  @Test
  public void testGetRoleRidMatchesLoadedRole() {
    var security = session.getSharedContext().getSecurity();

    var rid = SecurityShared.getRoleRID(session, "admin");
    Assert.assertNotNull("getRoleRID must return a non-null RID for an existing role", rid);

    session.begin();
    var role = security.getRole(session, "admin");
    session.commit();

    Assert.assertNotNull(role);
    Assert.assertEquals(role.getIdentity().getIdentity(), rid);
  }

  /**
   * Verifies that SecurityProxy correctly delegates getRole and createUser to SecurityShared
   * without introducing an extra layer of indirection visible to callers.
   */
  @Test
  public void testSecurityProxyDelegatesGetRoleAndGetUser() {
    var securityInternal = (SecurityInternal) session.getSharedContext().getSecurity();
    var proxy = new SecurityProxy(securityInternal, session);

    // getRole delegation
    var role = proxy.getRole("admin");
    Assert.assertNotNull("SecurityProxy.getRole must delegate to SecurityShared", role);
    Assert.assertEquals("admin", role.getName(session));

    // getUser delegation
    var user = proxy.getUser(adminUser);
    Assert.assertNotNull("SecurityProxy.getUser must delegate to SecurityShared", user);
    Assert.assertEquals(adminUser, user.getName(session));
  }

  /**
   * Verifies that SecurityProxy.getAllUsers and getAllRoles return the same content as
   * direct calls to SecurityShared to confirm delegation is correct.
   */
  @Test
  public void testSecurityProxyGetAllUsersAndGetAllRoles() {
    var securityInternal = (SecurityInternal) session.getSharedContext().getSecurity();
    var proxy = new SecurityProxy(securityInternal, session);

    var users = proxy.getAllUsers();
    Assert.assertFalse("SecurityProxy.getAllUsers must not be empty", users.isEmpty());

    var roles = proxy.getAllRoles();
    Assert.assertFalse("SecurityProxy.getAllRoles must not be empty", roles.isEmpty());
  }

  /**
   * Verifies that getSecurityPolicies returns the bound policies for a role and that a
   * newly-created role with no explicit binding has no entries. {@link DbTestBase} creates
   * the standard default roles which already have a {@code database.class.*.*} policy
   * bound, so we exercise both branches here: the reader role's map contains the default
   * binding, and a freshly-created role has an empty map.
   */
  @Test
  public void testGetSecurityPoliciesReturnsBoundPoliciesAndEmptyForNewRole() {
    var security = session.getSharedContext().getSecurity();

    // Reader role has the default database.class.*.* binding from DB creation.
    session.begin();
    var readerRole = security.getRole(session, "reader");
    var readerPolicies = security.getSecurityPolicies(session, readerRole);
    session.commit();

    Assert.assertNotNull(readerPolicies);
    Assert.assertFalse(
        "reader role must carry the default database.class.*.* policy created by DbTestBase",
        readerPolicies.isEmpty());

    // A freshly-created role with no explicit policy binding must report an empty map.
    session.begin();
    var fresh = security.createRole(session, "freshRoleNoPolicy");
    var freshPolicies = security.getSecurityPolicies(session, fresh);
    session.commit();

    Assert.assertNotNull(freshPolicies);
    Assert.assertTrue(
        "newly-created role with no explicit policy binding must have no policies",
        freshPolicies.isEmpty());
  }

  /**
   * Verifies that the version increments after each call to incrementVersion.
   */
  @Test
  public void testGetVersionIncrementsAfterIncrementVersion() {
    var security = session.getSharedContext().getSecurity();

    var versionBefore = security.getVersion(session);
    security.incrementVersion(session);
    var versionAfter = security.getVersion(session);

    Assert.assertTrue("version must increase after incrementVersion",
        versionAfter > versionBefore);
  }
}
