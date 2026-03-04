package com.jetbrains.youtrackdb.internal.core.metadata.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import org.junit.Test;

/**
 * Unit tests for ImmutableUser covering constructor variants and role handling.
 */
public class ImmutableUserTest extends DbTestBase {

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
}
