package com.jetbrains.youtrack.db.internal.core.metadata.security;

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
        SecurityResource.parseResource("database.class.Person.name")
            instanceof SecurityResourceProperty);
    Assert.assertEquals(
        "Person",
        ((SecurityResourceProperty) SecurityResource.parseResource("database.class.Person.name"))
            .getClassName());
    Assert.assertEquals(
        "name",
        ((SecurityResourceProperty) SecurityResource.parseResource("database.class.Person.name"))
            .getPropertyName());
    Assert.assertTrue(
        SecurityResource.parseResource("database.class.*.name")
            instanceof SecurityResourceProperty);
    Assert.assertTrue(
        SecurityResource.parseResource("database.collection.person")
            instanceof SecurityResourceCollection);
    Assert.assertEquals(
        "person",
        ((SecurityResourceCollection) SecurityResource.parseResource("database.collection.person"))
            .getCollectionName());
    Assert.assertTrue(
        SecurityResource.parseResource("database.function.foo")
            instanceof SecurityResourceFunction);
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

    try {
      SecurityResource.parseResource("database.class.person.foo.bar");
      Assert.fail();
    } catch (Exception e) {
    }
    try {
      SecurityResource.parseResource("database.collection.person.foo");
      Assert.fail();
    } catch (Exception e) {
    }
    try {
      SecurityResource.parseResource("database.function.foo.bar");
      Assert.fail();
    } catch (Exception e) {
    }
    try {
      SecurityResource.parseResource("database.foo");
      Assert.fail();
    } catch (Exception e) {
    }
    try {
      SecurityResource.parseResource("server.foo");
      Assert.fail();
    } catch (Exception e) {
    }
  }

  @Test
  public void testCache() {
    var person = SecurityResource.getInstance("database.class.Person");
    var person2 = SecurityResource.getInstance("database.class.Person");
    Assert.assertSame(person, person2);
  }
}
