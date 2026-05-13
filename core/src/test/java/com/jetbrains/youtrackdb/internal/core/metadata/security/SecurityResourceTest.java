package com.jetbrains.youtrackdb.internal.core.metadata.security;

import static org.junit.Assert.assertThrows;

import com.jetbrains.youtrackdb.internal.core.exception.SecurityException;
import org.junit.Assert;
import org.junit.Test;

public class SecurityResourceTest {

  @Test
  public void testParse() {
    Assert.assertEquals(
        SecurityResourceClass.ALL_CLASSES, SecurityResource.parseResource("database.class.*"));
    Assert.assertEquals(
        SecurityResourceProperty.ALL_PROPERTIES,
        SecurityResource.parseResource("database.class.*.*"));
    Assert.assertEquals(
        SecurityResourceCollection.ALL_COLLECTIONS,
        SecurityResource.parseResource("database.collection.*"));
    Assert.assertEquals(
        SecurityResourceFunction.ALL_FUNCTIONS,
        SecurityResource.parseResource("database.function.*"));
    Assert.assertTrue(
        SecurityResource.parseResource("database.class.Person") instanceof SecurityResourceClass);
    Assert.assertEquals(
        "Person",
        ((SecurityResourceClass) SecurityResource.parseResource("database.class.Person"))
            .getClassName());
    Assert.assertTrue(
        SecurityResource
            .parseResource("database.class.Person.name") instanceof SecurityResourceProperty);
    Assert.assertEquals(
        "Person",
        ((SecurityResourceProperty) SecurityResource.parseResource("database.class.Person.name"))
            .getClassName());
    Assert.assertEquals(
        "name",
        ((SecurityResourceProperty) SecurityResource.parseResource("database.class.Person.name"))
            .getPropertyName());
    Assert.assertTrue(
        SecurityResource
            .parseResource("database.class.*.name") instanceof SecurityResourceProperty);
    Assert.assertTrue(
        SecurityResource
            .parseResource("database.collection.person") instanceof SecurityResourceCollection);
    Assert.assertEquals(
        "person",
        ((SecurityResourceCollection) SecurityResource.parseResource("database.collection.person"))
            .getCollectionName());
    Assert.assertTrue(
        SecurityResource
            .parseResource("database.function.foo") instanceof SecurityResourceFunction);
    Assert.assertEquals(
        SecurityResourceDatabaseOp.BYPASS_RESTRICTED,
        SecurityResource.parseResource("database.bypassRestricted"));
    Assert.assertEquals(
        SecurityResourceDatabaseOp.COMMAND, SecurityResource.parseResource("database.command"));
    Assert.assertEquals(
        SecurityResourceDatabaseOp.COMMAND_GREMLIN,
        SecurityResource.parseResource("database.command.gremlin"));
    Assert.assertEquals(
        SecurityResourceDatabaseOp.COPY, SecurityResource.parseResource("database.copy"));
    Assert.assertEquals(
        SecurityResourceDatabaseOp.CREATE, SecurityResource.parseResource("database.create"));
    Assert.assertEquals(
        SecurityResourceDatabaseOp.DB, SecurityResource.parseResource("database"));
    Assert.assertEquals(
        SecurityResourceDatabaseOp.DROP, SecurityResource.parseResource("database.drop"));
    Assert.assertEquals(
        SecurityResourceDatabaseOp.EXISTS, SecurityResource.parseResource("database.exists"));
    Assert.assertEquals(
        SecurityResourceDatabaseOp.FREEZE, SecurityResource.parseResource("database.freeze"));
    Assert.assertEquals(
        SecurityResourceDatabaseOp.PASS_THROUGH,
        SecurityResource.parseResource("database.passthrough"));
    Assert.assertEquals(
        SecurityResourceDatabaseOp.RELEASE, SecurityResource.parseResource("database.release"));
    Assert.assertEquals(
        SecurityResourceDatabaseOp.HOOK_RECORD,
        SecurityResource.parseResource("database.hook.record"));
    Assert.assertNotEquals(
        SecurityResourceDatabaseOp.DB, SecurityResource.parseResource("database.command"));

    Assert.assertEquals(
        SecurityResourceServerOp.SERVER, SecurityResource.parseResource("server"));
    Assert.assertEquals(
        SecurityResourceServerOp.REMOVE, SecurityResource.parseResource("server.remove"));
    Assert.assertEquals(
        SecurityResourceServerOp.STATUS, SecurityResource.parseResource("server.status"));
    Assert.assertEquals(
        SecurityResourceServerOp.ADMIN, SecurityResource.parseResource("server.admin"));

    // The five negative shapes below all reach SecurityResource's
    // SecurityException-throwing branch; narrowing the catch to that exact type
    // means an unrelated RuntimeException (e.g., a future NPE in parseResource)
    // would no longer be silently swallowed by the test.
    assertThrows(SecurityException.class,
        () -> SecurityResource.parseResource("database.class.person.foo.bar"));
    assertThrows(SecurityException.class,
        () -> SecurityResource.parseResource("database.collection.person.foo"));
    assertThrows(SecurityException.class,
        () -> SecurityResource.parseResource("database.function.foo.bar"));
    assertThrows(SecurityException.class,
        () -> SecurityResource.parseResource("database.foo"));
    assertThrows(SecurityException.class,
        () -> SecurityResource.parseResource("server.foo"));
  }

  @Test
  public void testCache() {
    var person = SecurityResource.getInstance("database.class.Person");
    var person2 = SecurityResource.getInstance("database.class.Person");
    Assert.assertSame(person, person2);
  }

  /**
   * Verifies that the wildcard resource "*" parses to the singleton {@link SecurityResourceAll}.
   */
  @Test
  public void testParseWildcardAllReturnsSecurityResourceAll() {
    var res = SecurityResource.parseResource("*");
    Assert.assertSame(SecurityResourceAll.INSTANCE, res);
  }

  /**
   * Verifies that "database.schema" parses to the singleton {@link SecurityResourceSchema}.
   */
  @Test
  public void testParseDatabaseSchemaReturnsSchemaResource() {
    var res = SecurityResource.parseResource("database.schema");
    Assert.assertSame(SecurityResourceSchema.INSTANCE, res);
  }

  /**
   * Verifies that "database.systemcollections" parses to the singleton SYSTEM_COLLECTIONS
   * instance on {@link SecurityResourceCollection}.
   */
  @Test
  public void testParseDatabaseSystemcollectionsReturnsSystemCollections() {
    var res = SecurityResource.parseResource("database.systemcollections");
    Assert.assertSame(SecurityResourceCollection.SYSTEM_COLLECTIONS, res);
  }

  /**
   * Verifies that {@link SecurityResource#equals} and {@link SecurityResource#hashCode} are
   * based on the resource string: two independently created instances with the same string are
   * equal.
   */
  @Test
  public void testEqualsAndHashCodeBasedOnResourceString() {
    var a = new SecurityResourceClass("database.class.Person", "Person");
    var b = new SecurityResourceClass("database.class.Person", "Person");
    Assert.assertEquals("Two resources with the same string must be equal", a, b);
    Assert.assertEquals("Equal resources must have the same hashCode",
        a.hashCode(), b.hashCode());
  }

  /**
   * Verifies that {@link SecurityResource#equals} returns false for two resources with different
   * resource strings.
   */
  @Test
  public void testNotEqualForDifferentResourceStrings() {
    var a = new SecurityResourceClass("database.class.Person", "Person");
    var b = new SecurityResourceClass("database.class.Employee", "Employee");
    Assert.assertNotEquals(a, b);
  }

  /**
   * Verifies that {@link SecurityResource#equals} returns false when compared to null or a
   * non-SecurityResource object.
   */
  @Test
  public void testEqualsReturnsFalseForNullAndNonSecurityResource() {
    var a = new SecurityResourceClass("database.class.Person", "Person");
    Assert.assertNotEquals(null, a);
    Assert.assertNotEquals("not-a-resource", a);
  }

  /**
   * Verifies the introspection fields of a parsed {@link SecurityResourceProperty}:
   * className, propertyName, and the resource string itself.
   */
  @Test
  public void testParsedPropertyResourceIntrospection() {
    var res = (SecurityResourceProperty) SecurityResource.parseResource(
        "database.class.Person.name");
    Assert.assertEquals("Person", res.getClassName());
    Assert.assertEquals("name", res.getPropertyName());
  }

  /**
   * Verifies the introspection fields of a parsed {@link SecurityResourceCollection}:
   * collectionName.
   */
  @Test
  public void testParsedCollectionResourceIntrospection() {
    var res = (SecurityResourceCollection) SecurityResource.parseResource(
        "database.collection.MyCollection");
    Assert.assertEquals("MyCollection", res.getCollectionName());
  }

  /**
   * Verifies the introspection fields of a parsed {@link SecurityResourceFunction}:
   * functionName.
   */
  @Test
  public void testParsedFunctionResourceIntrospection() {
    var res = (SecurityResourceFunction) SecurityResource.parseResource(
        "database.function.myFunc");
    Assert.assertEquals("myFunc", res.getFunctionName());
  }

  /**
   * Verifies that parsing a resource whose class has a wildcard ("*") but has a specific
   * property name returns a {@link SecurityResourceProperty} with a null className.
   */
  @Test
  public void testParseAllClassesPropertyHasNullClassName() {
    var res = (SecurityResourceProperty) SecurityResource.parseResource("database.class.*.name");
    Assert.assertNull(res.getClassName());
    Assert.assertEquals("name", res.getPropertyName());
  }

  /**
   * Pins the {@link SecurityResource#getInstance} contract for a resource string
   * that cannot be parsed: the underlying {@code parseResource} call throws
   * {@link SecurityException}, and {@code getInstance} propagates it (the cache
   * insert is skipped on parse failure but the exception is not swallowed). The
   * previous test version accepted both "returns null" and "throws" as passing,
   * which made it tautological; this pinning forces the actual contract to
   * surface in case the implementation flips behaviour.
   */
  @Test
  public void testGetInstancePropagatesSecurityExceptionOnInvalidResourceString() {
    assertThrows(SecurityException.class,
        () -> SecurityResource.getInstance("database.foo.invalid.extra.parts.overflow"));
  }
}
