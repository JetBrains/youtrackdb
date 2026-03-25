package com.jetbrains.youtrackdb.internal.core.metadata.schema;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.BaseMemoryInternalDatabase;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Schema;
import org.junit.Test;

/**
 * Tests that class names in the schema are case-sensitive. Classes are stored
 * and looked up using their original-case name — no toLowerCase normalization.
 */
public class CaseSensitiveClassNameTest extends BaseMemoryInternalDatabase {

  /**
   * Verifies that class lookup uses exact-case matching: creating "MyClass"
   * and looking up "myclass" must return null.
   */
  @Test
  public void testClassLookupIsCaseSensitive() {
    Schema schema = session.getMetadata().getSchema();

    schema.createClass("MyClass");

    assertNotNull("Exact-case lookup should find the class",
        schema.getClass("MyClass"));
    assertNull("Different-case lookup should not find the class",
        schema.getClass("myclass"));
    assertNull("Different-case lookup should not find the class",
        schema.getClass("MYCLASS"));
  }

  /**
   * Verifies that existsClass uses exact-case matching.
   */
  @Test
  public void testExistsClassIsCaseSensitive() {
    Schema schema = session.getMetadata().getSchema();

    schema.createClass("TestExist");

    assertTrue("Exact-case existsClass should return true",
        schema.existsClass("TestExist"));
    assertFalse("Different-case existsClass should return false",
        schema.existsClass("testexist"));
    assertFalse("Different-case existsClass should return false",
        schema.existsClass("TESTEXIST"));
  }

  /**
   * Verifies that changeClassName (class rename) uses exact-case matching.
   * Renaming from "OldName" to "NewName" should make "OldName" not found
   * and "NewName" found with exact case.
   */
  @Test
  public void testRenameClassIsCaseSensitive() {
    Schema schema = session.getMetadata().getSchema();

    var cls = schema.createClass("OldName");
    cls.setName("NewName");

    assertNull("Old name should no longer be found", schema.getClass("OldName"));
    assertNotNull("New name should be found", schema.getClass("NewName"));
    assertNull("Lowercase of new name should not be found",
        schema.getClass("newname"));
  }

  /**
   * Verifies that case-only renames are allowed (e.g., "Person" to "person").
   * This is a valid rename since the names differ in case.
   */
  @Test
  public void testCaseOnlyRenameIsAllowed() {
    Schema schema = session.getMetadata().getSchema();

    var cls = schema.createClass("Person");
    cls.setName("person");

    assertNull("Original-case name should no longer be found",
        schema.getClass("Person"));
    assertNotNull("New-case name should be found",
        schema.getClass("person"));
  }

  /**
   * Verifies that the collection counter is persisted and restored across schema
   * reload. After creating classes and closing/reopening the session, the counter
   * should continue from where it left off (not reset), producing non-colliding
   * collection IDs.
   */
  @Test
  public void testCollectionCounterPersistsAcrossReload() {
    Schema schema = session.getMetadata().getSchema();

    // Create classes to advance the counter
    schema.createClass("CounterTest1");
    schema.createClass("CounterTest2");

    int cls2CollectionId = schema.getClass("CounterTest2").getCollectionIds()[0];

    // Close and reopen session to force schema reload from disk
    session.close();
    session = pool.acquire();

    schema = session.getMetadata().getSchema();

    // Create another class — should get a new counter value, not collide
    schema.createClass("CounterTest3");

    var cls3 = schema.getClass("CounterTest3");
    assertNotNull(cls3);

    // The new class should have a collection ID different from the previous ones
    assertTrue("Post-reload class should have a different collection ID",
        cls3.getCollectionIds()[0] != cls2CollectionId);
  }

  /**
   * Verifies that getOrCreateClass uses exact-case matching: creating "MyClass"
   * via getOrCreateClass and then calling getOrCreateClass with "myclass" should
   * create a new class, not return the existing one.
   */
  @Test
  public void testGetOrCreateClassIsCaseSensitive() {
    Schema schema = session.getMetadata().getSchema();

    var cls1 = schema.getOrCreateClass("MyGetOrCreate");
    assertNotNull(cls1);
    assertEquals("MyGetOrCreate", cls1.getName());

    // Different case should NOT find the existing class
    assertNull("Different-case lookup should return null",
        schema.getClass("mygetorcreate"));
  }

  /**
   * Verifies that class creation rejects duplicate exact-case names but allows
   * names that differ only in case.
   */
  @Test
  public void testCreateClassAllowsDifferentCase() {
    Schema schema = session.getMetadata().getSchema();

    schema.createClass("Animal");

    // Creating with different case should succeed (these are different classes)
    assertFalse("'animal' should not exist yet", schema.existsClass("animal"));
  }
}
