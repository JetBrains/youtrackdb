package com.jetbrains.youtrackdb.internal.core.metadata.security;

import java.lang.reflect.Modifier;
import org.junit.Assert;
import org.junit.Test;

/**
 * Shape pins for {@link SecurityRole} (interface constants and method surface) and
 * {@link Identity} (abstract base class). These tests are standalone — no database required.
 *
 * <p>The tests confirm that the interface/class shape matches what the rest of the codebase
 * depends on, so that future refactors that inadvertently remove a required method are caught
 * at compile time rather than at runtime.
 */
public class SecurityRoleAndIdentityShapeTest {

  // ─── SecurityRole interface ───────────────────────────────────────────────

  /**
   * Verifies that {@link SecurityRole} is an interface (not a class or enum).
   */
  @Test
  public void testSecurityRoleIsAnInterface() {
    Assert.assertTrue("SecurityRole must be an interface",
        SecurityRole.class.isInterface());
  }

  /**
   * Verifies that {@link SecurityRole} declares the {@code allow(ResourceGeneric, String, int)}
   * method, which is the primary permission check entry-point for all rule evaluations.
   */
  @Test
  public void testSecurityRoleDeclaresPrimaryAllowMethod() throws NoSuchMethodException {
    var m = SecurityRole.class.getMethod(
        "allow",
        Rule.ResourceGeneric.class,
        String.class,
        int.class);
    Assert.assertNotNull(m);
    Assert.assertEquals(boolean.class, m.getReturnType());
  }

  /**
   * Verifies that {@link SecurityRole} declares {@code hasRule(ResourceGeneric, String)}.
   */
  @Test
  public void testSecurityRoleDeclaresHasRule() throws NoSuchMethodException {
    var m = SecurityRole.class.getMethod(
        "hasRule",
        Rule.ResourceGeneric.class,
        String.class);
    Assert.assertNotNull(m);
    Assert.assertEquals(boolean.class, m.getReturnType());
  }

  /**
   * Verifies that {@link SecurityRole} declares {@code getRuleSet()} returning a {@link java.util.Set}.
   */
  @Test
  public void testSecurityRoleDeclaresGetRuleSet() throws NoSuchMethodException {
    var m = SecurityRole.class.getMethod("getRuleSet");
    Assert.assertNotNull(m);
    Assert.assertEquals(java.util.Set.class, m.getReturnType());
  }

  /**
   * Verifies that {@link SecurityRole} declares {@code getName(DatabaseSessionEmbedded)}
   * returning {@link String}.
   */
  @Test
  public void testSecurityRoleDeclaresGetName() throws NoSuchMethodException {
    var m = SecurityRole.class.getMethod(
        "getName",
        com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded.class);
    Assert.assertNotNull(m);
    Assert.assertEquals(String.class, m.getReturnType());
  }

  // ─── Identity abstract class ──────────────────────────────────────────────

  /**
   * Verifies that {@link Identity} is an abstract class (not an interface).
   */
  @Test
  public void testIdentityIsAbstractClass() {
    Assert.assertFalse("Identity must not be an interface", Identity.class.isInterface());
    Assert.assertTrue("Identity must be abstract",
        Modifier.isAbstract(Identity.class.getModifiers()));
  }

  /**
   * Verifies that {@link Identity#CLASS_NAME} equals the legacy OrientDB OIdentity class name
   * that the persistence layer uses to resolve identity records.
   */
  @Test
  public void testIdentityClassNameConstantIsOIdentity() {
    Assert.assertEquals("OIdentity", Identity.CLASS_NAME);
  }

  /**
   * Verifies that {@link Identity} declares the two-argument constructor
   * {@code (DatabaseSessionEmbedded, String)} used when creating a new identity record.
   */
  @Test
  public void testIdentityDeclaresSessionAndClassNameConstructor() {
    boolean found = false;
    for (var c : Identity.class.getDeclaredConstructors()) {
      var params = c.getParameterTypes();
      if (params.length == 2
          && params[0].equals(
              com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded.class)
          && params[1].equals(String.class)) {
        found = true;
        break;
      }
    }
    Assert.assertTrue(
        "Identity must declare a constructor(DatabaseSessionEmbedded, String)", found);
  }

  // ─── SystemRole ───────────────────────────────────────────────────────────

  /**
   * Verifies that {@link SystemRole} extends {@link Role} and exposes
   * the {@code DB_FILTER} constant used by {@code SecuritySystemUserImpl} to filter roles
   * by database name.
   */
  @Test
  public void testSystemRoleExtendsRoleAndExposesDbFilterConstant() {
    Assert.assertTrue("SystemRole must extend Role",
        Role.class.isAssignableFrom(SystemRole.class));
    Assert.assertEquals("dbFilter", SystemRole.DB_FILTER);
  }
}
