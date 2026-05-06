package com.jetbrains.youtrackdb.internal.core.metadata.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import org.junit.After;
import org.junit.Test;

/**
 * Unit tests for ImmutableUser covering constructor variants, role handling,
 * and SecuritySystemUserImpl construction.
 */
public class ImmutableUserTest extends DbTestBase {

  /**
   * Roll back any transaction left open by a failing test before the database is dropped.
   * JUnit 4 runs subclass {@code @After} methods before superclass ones, so this fires
   * ahead of the database teardown.
   */
  @After
  public void rollbackIfLeftOpen() {
    if (session != null && !session.isClosed() && session.isTxActive()) {
      session.rollback();
    }
  }

  /**
   * Verifies that the convenience constructor with no role creates an ImmutableUser
   * with empty roles list and set.
   */
  @Test
  public void testConstructorWithNullRoleCreatesEmptyRoles() {
    var user = new ImmutableUser(session, "testUser", "database");

    assertEquals("testUser", user.getName(session));
    assertTrue(user.getRoles().isEmpty());
    assertEquals("database", user.getUserType());
  }

  /**
   * Verifies that the full constructor with a non-null SecurityRole wraps it
   * into an ImmutableRole and stores it as a singleton.
   */
  @Test
  public void testConstructorWithNonNullRoleCreatesSingletonRoles() {
    // Create a SecurityRole from the database's existing admin role
    var security = session.getMetadata().getSecurity();
    var adminRole = security.getRole("admin");
    assertNotNull("admin role should exist", adminRole);

    var user = new ImmutableUser(session, "testAdmin", "pwd", "database", adminRole);

    assertEquals("testAdmin", user.getName(session));
    assertEquals(1, user.getRoles().size());
    assertFalse(user.getRoles().isEmpty());
  }

  /**
   * Verifies that the full constructor with an already-immutable role reuses it
   * directly (identity check via instanceof ImmutableRole).
   */
  @Test
  public void testConstructorWithImmutableRoleReusesIt() {
    var security = session.getMetadata().getSecurity();
    var rawRole = security.getRole("admin");
    assertNotNull(rawRole);

    // Wrap it as immutable first
    var immutableRole = new ImmutableRole(session, rawRole);

    var user = new ImmutableUser(session, "testAdmin", "pwd", "database", immutableRole);

    assertEquals(1, user.getRoles().size());
    // The role in the set should be the exact same ImmutableRole instance (no re-wrapping)
    var storedRole = user.getRoles().iterator().next();
    assertSame(immutableRole, storedRole);
  }

  // ─── SecuritySystemUserImpl ──────────────────────────────────────────────

  /**
   * Verifies that {@link SecuritySystemUserImpl#getUserType()} always returns
   * the constant {@code "SYSTEM_USER"} and that getName returns the underlying entity's
   * name property.
   *
   * <p>Roles in a regular (non-system) database have no {@code dbFilter} property.
   * The {@code populateSystemRoles} method only reads {@code dbFilter} in the
   * {@code databaseName == null || databaseName.isEmpty()} branch (the else branch), which
   * contains a null check. Passing an empty dbName ensures the null-safe code path is
   * exercised; this also matches how the system database creates a SecuritySystemUserImpl
   * without a specific target database name when listing all-database roles.
   */
  @Test
  public void testSecuritySystemUserImplGetUserTypeIsSystemUser() {
    session.begin();
    EntityImpl userEntity;
    try (var rs = session.getActiveTransaction().query("SELECT FROM OUser WHERE name = ?",
        adminUser)) {
      userEntity = rs.hasNext() ? (EntityImpl) rs.next().asEntity() : null;
    }
    assertNotNull("admin user entity must exist", userEntity);

    // Empty dbName uses the null-safe else branch in populateSystemRoles.
    var sysUser = new SecuritySystemUserImpl(session, userEntity, "");
    var userName = sysUser.getName(session);
    var userType = sysUser.getUserType();
    session.commit();

    assertEquals(SecuritySystemUserImpl.SYSTEM_USER, userType);
    assertEquals(adminUser, userName);
  }

  /**
   * Verifies that when dbName is empty, roles without a {@code dbFilter} property are
   * included in the systemRoles set (the null-safe else branch in populateSystemRoles).
   * All roles in a fresh regular database have no dbFilter → all are included.
   */
  @Test
  public void testSecuritySystemUserImplRolesIncludedWhenDbNameEmpty() {
    session.begin();
    EntityImpl userEntity;
    try (var rs = session.getActiveTransaction().query("SELECT FROM OUser WHERE name = ?",
        adminUser)) {
      userEntity = rs.hasNext() ? (EntityImpl) rs.next().asEntity() : null;
    }
    assertNotNull(userEntity);

    // Empty dbName → the null-safe else-branch includes roles without dbFilter.
    var sysUser = new SecuritySystemUserImpl(session, userEntity, "");
    var roleCount = sysUser.getRoles().size();
    session.commit();

    // The admin user has at least one role; roles with no dbFilter are included.
    assertTrue("systemRoles must be non-empty when dbName is empty", roleCount > 0);
  }

  /**
   * Verifies that {@link SecuritySystemUserImpl#SYSTEM_USER} constant is "SYSTEM_USER" —
   * a shape pin that guards against accidental rename.
   */
  @Test
  public void testSecuritySystemUserImplConstantValue() {
    assertEquals("SYSTEM_USER", SecuritySystemUserImpl.SYSTEM_USER);
  }
}
