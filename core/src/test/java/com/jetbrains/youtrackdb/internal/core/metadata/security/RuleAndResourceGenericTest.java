package com.jetbrains.youtrackdb.internal.core.metadata.security;

import org.junit.Assert;
import org.junit.Test;

/**
 * Unit tests for {@link Rule}, {@link Rule.ResourceGeneric}, and {@link Role#permissionToString}.
 *
 * <p>All tests are standalone (no database required) because Rule and ResourceGeneric operate on
 * in-memory bitmasks and static lookup tables.
 */
public class RuleAndResourceGenericTest {

  // ─── Rule.grantAccess / revokeAccess / isAllowed ────────────────────────────

  /**
   * Verifies that grantAccess with a null resource sets the generic-level access bitmask,
   * and isAllowed on null resource returns true for the granted operation.
   */
  @Test
  public void testGrantAccessNullResourceSetsGenericBitmask() {
    var rule = new Rule(Rule.ResourceGeneric.CLASS, null, null);
    rule.grantAccess(null, Role.PERMISSION_READ);

    Assert.assertTrue(rule.isAllowed(null, Role.PERMISSION_READ));
    Assert.assertFalse(rule.isAllowed(null, Role.PERMISSION_DELETE));
  }

  /**
   * Verifies that grantAccess for a named specific resource stores a separate bitmask
   * and isAllowed delegates to it.  For a resource name not in the specific-resources map the
   * fallback path reaches the generic access (which is null here), so isAllowed returns null —
   * signalling "no rule for this resource; delegate to parent" to the containing Role.allow().
   */
  @Test
  public void testGrantAccessSpecificResourceStoresSeparateBitmask() {
    var rule = new Rule(Rule.ResourceGeneric.CLASS, null, null);
    // No generic access yet
    rule.grantAccess("person", Role.PERMISSION_READ);

    // Specific resource allowed
    Assert.assertTrue(rule.isAllowed("person", Role.PERMISSION_READ));
    // "employee" is not in the specific-resources map; generic access is also null.
    // isAllowed falls back to the generic bitmask (also null) → returns null.
    Assert.assertNull("isAllowed must be null (no decision) for an unknown specific resource",
        rule.isAllowed("employee", Role.PERMISSION_READ));
  }

  /**
   * Verifies that containsSpecificResource returns true only for a resource that was added.
   */
  @Test
  public void testContainsSpecificResource() {
    var rule = new Rule(Rule.ResourceGeneric.CLASS, null, null);
    rule.grantAccess("person", Role.PERMISSION_READ);

    Assert.assertTrue(rule.containsSpecificResource("person"));
    Assert.assertFalse(rule.containsSpecificResource("employee"));
  }

  /**
   * Verifies that revokeAccess on a specific resource clears the requested permission bit
   * while leaving other bits untouched.
   */
  @Test
  public void testRevokeAccessClearsOnlySpecifiedBit() {
    var rule = new Rule(Rule.ResourceGeneric.CLASS, null, null);
    rule.grantAccess("person", Role.PERMISSION_READ | Role.PERMISSION_UPDATE);
    rule.revokeAccess("person", Role.PERMISSION_READ);

    Assert.assertFalse(rule.isAllowed("person", Role.PERMISSION_READ));
    Assert.assertTrue(rule.isAllowed("person", Role.PERMISSION_UPDATE));
  }

  /**
   * Verifies that revoking PERMISSION_NONE is a no-op (guard at the top of revokeAccess).
   */
  @Test
  public void testRevokePermissionNoneIsNoOp() {
    var rule = new Rule(Rule.ResourceGeneric.CLASS, null, null);
    rule.grantAccess(null, Role.PERMISSION_READ);
    rule.revokeAccess(null, Role.PERMISSION_NONE); // should be a no-op

    // READ should still be allowed
    Assert.assertTrue(rule.isAllowed(null, Role.PERMISSION_READ));
  }

  /**
   * Verifies that grantAccess with PERMISSION_NONE resets the access bitmask to 0 (revoke all).
   */
  @Test
  public void testGrantPermissionNoneResetsToZero() {
    var rule = new Rule(Rule.ResourceGeneric.CLASS, null, null);
    rule.grantAccess(null, Role.PERMISSION_READ);
    rule.grantAccess(null, Role.PERMISSION_NONE); // special: resets to 0

    Assert.assertFalse(rule.isAllowed(null, Role.PERMISSION_READ));
  }

  /**
   * Verifies that getAccess and getResourceGeneric return the constructor values.
   */
  @Test
  public void testConstructorValuesAreStoredCorrectly() {
    var rule = new Rule(Rule.ResourceGeneric.FUNCTION, null, (byte) Role.PERMISSION_ALL);

    Assert.assertEquals(Rule.ResourceGeneric.FUNCTION, rule.getResourceGeneric());
    Assert.assertEquals((byte) Role.PERMISSION_ALL, (byte) rule.getAccess());
    Assert.assertTrue(rule.getSpecificResources().isEmpty());
  }

  // ─── Rule.mapLegacyResourceToGenericResource ─────────────────────────────

  /**
   * Verifies that well-known legacy resource strings map to the expected ResourceGeneric constants.
   */
  @Test
  public void testMapLegacyResourceToGenericResource() {
    Assert.assertEquals(
        Rule.ResourceGeneric.CLASS,
        Rule.mapLegacyResourceToGenericResource("database.class.*"));
    Assert.assertEquals(
        Rule.ResourceGeneric.DATABASE,
        Rule.mapLegacyResourceToGenericResource("database"));
    Assert.assertEquals(
        Rule.ResourceGeneric.SCHEMA,
        Rule.mapLegacyResourceToGenericResource("database.schema"));
    Assert.assertNull(Rule.mapLegacyResourceToGenericResource("nonexistent.resource"));
  }

  /**
   * Verifies that legacy-to-specific extraction returns the sub-resource after the prefix dot.
   */
  @Test
  public void testMapLegacyResourceToSpecificResource() {
    // "database.class.Person" → specific part is "Person"
    Assert.assertEquals("Person",
        Rule.mapLegacyResourceToSpecificResource("database.class.Person"));
    // "database.class.*" → specific part is "*" (prefix "database.class" matched,
    // remaining = ".*" → substring after dot = "*")
    Assert.assertEquals("*",
        Rule.mapLegacyResourceToSpecificResource("database.class.*"));
    // "database.class" (exact prefix match, no trailing dot) → returns null
    Assert.assertNull(Rule.mapLegacyResourceToSpecificResource("database.class"));
    // Completely unknown → returned as-is
    Assert.assertEquals("foo.bar",
        Rule.mapLegacyResourceToSpecificResource("foo.bar"));
  }

  /**
   * Verifies that mapResourceGenericToLegacyResource returns the registered legacy name
   * for the CLASS and FUNCTION resource constants.
   */
  @Test
  public void testMapResourceGenericToLegacyResource() {
    var classLegacy = Rule.mapResourceGenericToLegacyResource(Rule.ResourceGeneric.CLASS);
    Assert.assertNotNull(classLegacy);
    Assert.assertEquals(DatabaseSecurityResources.CLASS, classLegacy);

    var functionLegacy = Rule.mapResourceGenericToLegacyResource(Rule.ResourceGeneric.FUNCTION);
    Assert.assertEquals(DatabaseSecurityResources.FUNCTION, functionLegacy);
  }

  // ─── Rule.ResourceGeneric static lookup ──────────────────────────────────

  /**
   * Verifies that ResourceGeneric.valueOf looks up constants by their symbolic name.
   */
  @Test
  public void testResourceGenericValueOf() {
    Assert.assertSame(Rule.ResourceGeneric.ALL, Rule.ResourceGeneric.valueOf("ALL"));
    Assert.assertSame(Rule.ResourceGeneric.CLASS, Rule.ResourceGeneric.valueOf("CLASS"));
    Assert.assertSame(Rule.ResourceGeneric.FUNCTION, Rule.ResourceGeneric.valueOf("FUNCTION"));
    Assert.assertNull(Rule.ResourceGeneric.valueOf("DOES_NOT_EXIST"));
  }

  /**
   * Verifies that ResourceGeneric.values() returns a non-empty array containing all defined
   * constants (we check for at least the well-known ones without pinning the exact count
   * to allow future additions without breaking the test).
   */
  @Test
  public void testResourceGenericValuesNonEmpty() {
    var values = Rule.ResourceGeneric.values();
    Assert.assertTrue("ResourceGeneric.values() must not be empty", values.length > 0);
    // Spot-check a few
    boolean foundAll = false;
    boolean foundClass = false;
    for (var v : values) {
      if (v == Rule.ResourceGeneric.ALL) {
        foundAll = true;
      }
      if (v == Rule.ResourceGeneric.CLASS) {
        foundClass = true;
      }
    }
    Assert.assertTrue(foundAll);
    Assert.assertTrue(foundClass);
  }

  /**
   * Verifies getName and getLegacyName on a well-known ResourceGeneric constant.
   */
  @Test
  public void testResourceGenericGetNameAndLegacyName() {
    Assert.assertEquals("CLASS", Rule.ResourceGeneric.CLASS.getName());
    Assert.assertEquals(DatabaseSecurityResources.CLASS,
        Rule.ResourceGeneric.CLASS.getLegacyName());
  }

  /**
   * Verifies that ResourceGeneric.toString() contains both name and legacyName components.
   */
  @Test
  public void testResourceGenericToStringContainsNameAndLegacyName() {
    var str = Rule.ResourceGeneric.FUNCTION.toString();
    Assert.assertTrue("toString must contain 'FUNCTION'", str.contains("FUNCTION"));
    Assert.assertTrue("toString must contain the legacy name",
        str.contains(Rule.ResourceGeneric.FUNCTION.getLegacyName()));
  }

  // ─── Role.permissionToString ──────────────────────────────────────────────

  /**
   * Verifies permissionToString for each individual permission constant returns the registered name.
   */
  @Test
  public void testPermissionToStringIndividualBits() {
    Assert.assertTrue(Role.permissionToString(Role.PERMISSION_CREATE).contains("Create"));
    Assert.assertTrue(Role.permissionToString(Role.PERMISSION_READ).contains("Read"));
    Assert.assertTrue(Role.permissionToString(Role.PERMISSION_UPDATE).contains("Update"));
    Assert.assertTrue(Role.permissionToString(Role.PERMISSION_DELETE).contains("Delete"));
    Assert.assertTrue(Role.permissionToString(Role.PERMISSION_EXECUTE).contains("Execute"));
  }

  /**
   * Verifies that PERMISSION_NONE (0) produces an empty string (no bit set).
   */
  @Test
  public void testPermissionToStringNoneIsEmpty() {
    Assert.assertEquals("", Role.permissionToString(Role.PERMISSION_NONE));
  }

  /**
   * Verifies that PERMISSION_ALL produces a comma-separated string containing all five names.
   */
  @Test
  public void testPermissionToStringAll() {
    var str = Role.permissionToString(Role.PERMISSION_ALL);
    Assert.assertTrue(str.contains("Create"));
    Assert.assertTrue(str.contains("Read"));
    Assert.assertTrue(str.contains("Update"));
    Assert.assertTrue(str.contains("Delete"));
    Assert.assertTrue(str.contains("Execute"));
  }

  /**
   * Verifies that an unknown permission bit (e.g. bit 7 set) produces an "Unknown 0x" suffix
   * in the result string.
   */
  @Test
  public void testPermissionToStringUnknownBitProducesUnknownSuffix() {
    // Bit 7 (value 128) is not registered by the Role class-load-time statics
    var str = Role.permissionToString(0x80);
    Assert.assertTrue("Expected 'Unknown 0x' in output: " + str, str.contains("Unknown 0x"));
  }
}
