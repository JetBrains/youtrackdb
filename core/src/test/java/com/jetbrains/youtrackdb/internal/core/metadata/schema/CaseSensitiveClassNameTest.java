package com.jetbrains.youtrackdb.internal.core.metadata.schema;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.BaseMemoryInternalDatabase;
import com.jetbrains.youtrackdb.internal.core.index.Index;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import java.util.Collections;
import java.util.Set;
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

    var found = schema.getClass("MyClass");
    assertNotNull("Exact-case lookup should find the class", found);
    assertEquals("MyClass", found.getName());
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

    assertEquals("Class object should report new name", "NewName", cls.getName());
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

    assertEquals("Class object should report new case", "person", cls.getName());
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

    int cls1CollectionId = schema.getClass("CounterTest1").getCollectionIds()[0];
    int cls2CollectionId = schema.getClass("CounterTest2").getCollectionIds()[0];

    // Counter-based IDs should be monotonically increasing
    assertTrue("Collection IDs should be monotonically increasing",
        cls2CollectionId > cls1CollectionId);

    // Close and reopen session to force schema reload from disk
    session.close();
    session = pool.acquire();

    schema = session.getMetadata().getSchema();

    // Verify previously created classes survived the reload
    assertNotNull("CounterTest1 should survive reload",
        schema.getClass("CounterTest1"));
    assertNotNull("CounterTest2 should survive reload",
        schema.getClass("CounterTest2"));

    // Create another class — should get a counter value higher than pre-reload
    schema.createClass("CounterTest3");

    var cls3 = schema.getClass("CounterTest3");
    assertNotNull(cls3);

    assertTrue("Post-reload collection ID should be greater than pre-reload IDs",
        cls3.getCollectionIds()[0] > cls2CollectionId);
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

    // Different case should create a NEW class, not return the existing one
    var cls2 = schema.getOrCreateClass("mygetorcreate");
    assertNotNull(cls2);
    assertEquals("mygetorcreate", cls2.getName());

    // They should be distinct classes with different collection IDs
    assertTrue("getOrCreateClass with different case should create a separate class",
        cls1.getCollectionIds()[0] != cls2.getCollectionIds()[0]);
  }

  /**
   * Verifies that two classes with the same name in different cases can coexist
   * independently, each with its own collections and data isolation.
   */
  @Test
  public void testCreateClassAllowsDifferentCase() {
    Schema schema = session.getMetadata().getSchema();

    schema.createClass("Animal");
    schema.createClass("animal");

    var cls1 = schema.getClass("Animal");
    var cls2 = schema.getClass("animal");
    assertNotNull("'Animal' should exist", cls1);
    assertNotNull("'animal' should exist", cls2);

    // Each class must have distinct collection IDs
    assertTrue("Case-variant classes must have different collection IDs",
        cls1.getCollectionIds()[0] != cls2.getCollectionIds()[0]);
  }

  /**
   * Verifies that isSubClassOf uses exact-case matching after the migration.
   */
  @Test
  public void testIsSubClassOfIsCaseSensitive() {
    Schema schema = session.getMetadata().getSchema();
    var child = schema.createClass("MyVertex", schema.getClass("V"));

    assertTrue("Exact-case isSubClassOf should match",
        child.isSubClassOf("V"));
    assertFalse("Different-case isSubClassOf should not match",
        child.isSubClassOf("v"));
  }

  /**
   * Verifies that isSubClassOf traverses multi-level inheritance with
   * exact-case matching: GrandParent → Parent → Child.
   */
  @Test
  public void testIsSubClassOfMultiLevelCaseSensitive() {
    Schema schema = session.getMetadata().getSchema();

    var grandparent = schema.createClass("GrandParent");
    var parent = schema.createClass("Parent", grandparent);
    var child = schema.createClass("Child", parent);

    assertTrue(child.isSubClassOf("GrandParent"));
    assertTrue(child.isSubClassOf("Parent"));
    assertFalse("Must not match wrong case", child.isSubClassOf("grandparent"));
    assertFalse("Must not match wrong case", child.isSubClassOf("parent"));
  }

  /**
   * Verifies that dropClass uses exact-case matching: dropping "Animal"
   * must not affect the "animal" class or its data.
   */
  @Test
  public void testDropClassIsCaseSensitive() {
    Schema schema = session.getMetadata().getSchema();

    schema.createClass("Animal");
    schema.createClass("animal");

    // Insert a record into "animal" to verify data isolation
    session.begin();
    session.newInstance("animal");
    session.commit();

    schema.dropClass("Animal");

    assertNull("Dropped class should not exist", schema.getClass("Animal"));
    assertNotNull("Other-case class should still exist", schema.getClass("animal"));

    // Verify data in "animal" is intact
    session.begin();
    long count = session.countClass("animal");
    session.rollback();
    assertEquals("Data in other-case class should be intact", 1L, count);
  }

  /**
   * Verifies that the collection counter does not reuse indices after a class
   * is dropped. Collection name counter suffixes must strictly increase.
   */
  @Test
  public void testCollectionCounterMonotonicallyIncreasesAfterDrop() {
    Schema schema = session.getMetadata().getSchema();

    var cls1 = schema.createClass("Mono1");
    String name1 = session.getCollectionNameById(cls1.getCollectionIds()[0]);
    int suffix1 = extractCounterSuffix(name1);

    schema.dropClass("Mono1");

    var cls2 = schema.createClass("Mono2");
    String name2 = session.getCollectionNameById(cls2.getCollectionIds()[0]);
    int suffix2 = extractCounterSuffix(name2);

    assertTrue("Counter suffix after drop must be strictly greater ("
        + suffix2 + " > " + suffix1 + ")", suffix2 > suffix1);
  }

  private static int extractCounterSuffix(String collectionName) {
    int idx = collectionName.lastIndexOf('_');
    assertTrue("Collection name should contain counter suffix: " + collectionName,
        idx >= 0 && idx < collectionName.length() - 1);
    return Integer.parseInt(collectionName.substring(idx + 1));
  }

  /**
   * Verifies that the immutable schema snapshot preserves case-sensitive
   * class lookup. ImmutableSchema is the primary read path during query
   * execution.
   */
  @Test
  public void testImmutableSchemaLookupIsCaseSensitive() {
    Schema schema = session.getMetadata().getSchema();
    schema.createClass("SnapshotTest");

    // Force a fresh snapshot via getImmutableSchemaSnapshot
    var immutableSchema = session.getMetadata().getImmutableSchemaSnapshot();

    assertTrue(immutableSchema.existsClass("SnapshotTest"));
    assertFalse(immutableSchema.existsClass("snapshottest"));
    assertNotNull(immutableSchema.getClass("SnapshotTest"));
    assertNull(immutableSchema.getClass("SNAPSHOTTEST"));
  }

  /**
   * Verifies that making an abstract class concrete generates a collection
   * using the counter-based naming scheme.
   */
  @Test
  public void testAbstractToConcreteCreatesCounterBasedCollection() {
    Schema schema = session.getMetadata().getSchema();

    var cls = schema.createAbstractClass("MixedCase");
    assertEquals(-1, cls.getCollectionIds()[0]);

    cls.setAbstract(false);

    int[] ids = cls.getCollectionIds();
    assertTrue("Should have a valid collection ID", ids[0] >= 0);

    String collectionName = session.getCollectionNameById(ids[0]);
    assertTrue("Collection name should start with lowercase class name",
        collectionName.startsWith("mixedcase_"));
  }

  // --- Index case-sensitivity tests (Track 2) ---

  /**
   * Verifies that an index created on a class is retrievable by its exact
   * original-case name through the immutable schema snapshot, and that a
   * different-case lookup does not find it.
   */
  @Test
  public void testIndexLookupByExactCaseName() {
    Schema schema = session.getMetadata().getSchema();

    var cls = schema.createClass("IdxLookup");
    cls.createProperty("name", PropertyType.STRING);
    cls.createIndex("IdxLookup.nameIdx", SchemaClass.INDEX_TYPE.NOTUNIQUE, "name");

    var immutableSchema = session.getMetadata().getImmutableSchemaSnapshot();

    assertTrue("Exact-case index name should exist",
        immutableSchema.indexExists("IdxLookup.nameIdx"));
    assertFalse("Different-case index name should not exist",
        immutableSchema.indexExists("idxlookup.nameidx"));
    assertFalse("Different-case index name should not exist",
        immutableSchema.indexExists("IDXLOOKUP.NAMEIDX"));

    var def = immutableSchema.getIndexDefinition("IdxLookup.nameIdx");
    assertNotNull("Index definition should be retrievable", def);
    assertEquals("IdxLookup.nameIdx", def.name());
  }

  /**
   * Verifies that the classPropertyIndex lookup in IndexManager works with
   * the exact original-case class name. After creating an index on "PropLookup",
   * querying involved indexes for "PropLookup" should return the index, but
   * querying for "proplookup" should not.
   */
  @Test
  public void testClassPropertyIndexLookupIsCaseSensitive() {
    Schema schema = session.getMetadata().getSchema();

    var cls = schema.createClass("PropLookup");
    cls.createProperty("value", PropertyType.INTEGER);
    cls.createIndex("PropLookup.valueIdx", SchemaClass.INDEX_TYPE.NOTUNIQUE, "value");

    var indexManager = session.getSharedContext().getIndexManager();

    // Exact-case class name should find the index
    Set<Index> indexes = indexManager.getClassInvolvedIndexes(
        session, "PropLookup", Collections.singletonList("value"));
    assertFalse("Exact-case className should find involved indexes",
        indexes.isEmpty());

    // Different-case class name should not find the index
    Set<Index> wrongCase = indexManager.getClassInvolvedIndexes(
        session, "proplookup", Collections.singletonList("value"));
    assertTrue("Different-case className should not find involved indexes",
        wrongCase.isEmpty());
  }

  /**
   * Verifies that index removal works correctly with case-sensitive class
   * names: after dropping an index, the classPropertyIndex entry is properly
   * cleaned up and the index is no longer found.
   */
  @Test
  public void testIndexRemovalWithCaseSensitiveClassName() {
    Schema schema = session.getMetadata().getSchema();

    var cls = schema.createClass("RemoveIdx");
    cls.createProperty("field", PropertyType.STRING);
    cls.createIndex("RemoveIdx.fieldIdx", SchemaClass.INDEX_TYPE.NOTUNIQUE, "field");

    var indexManager = session.getSharedContext().getIndexManager();

    // Verify index exists before removal
    Set<Index> before = indexManager.getClassInvolvedIndexes(
        session, "RemoveIdx", Collections.singletonList("field"));
    assertFalse("Index should exist before removal", before.isEmpty());

    // Drop the index
    indexManager.dropIndex(session, "RemoveIdx.fieldIdx");

    // Verify index is gone from classPropertyIndex
    Set<Index> after = indexManager.getClassInvolvedIndexes(
        session, "RemoveIdx", Collections.singletonList("field"));
    assertTrue("Index should be gone after removal", after.isEmpty());

    // Verify through immutable schema too
    var snap = session.getMetadata().getImmutableSchemaSnapshot();
    assertFalse("Index should not exist in snapshot after removal",
        snap.indexExists("RemoveIdx.fieldIdx"));
  }

  /**
   * Verifies that getClassIndex uses exact-case class name matching.
   * Looking up an index with the wrong-case class name should return null.
   */
  @Test
  public void testGetClassIndexIsCaseSensitive() {
    Schema schema = session.getMetadata().getSchema();

    var cls = schema.createClass("ClassIdx");
    cls.createProperty("data", PropertyType.STRING);
    cls.createIndex("ClassIdx.dataIdx", SchemaClass.INDEX_TYPE.NOTUNIQUE, "data");

    var indexManager = session.getSharedContext().getIndexManager();

    // Exact-case class name should find the index
    var idx = indexManager.getClassIndex(session, "ClassIdx", "ClassIdx.dataIdx");
    assertNotNull("Exact-case getClassIndex should find the index", idx);

    // Different-case class name should not find the index
    var wrongCaseIdx = indexManager.getClassIndex(
        session, "classidx", "ClassIdx.dataIdx");
    assertNull("Different-case getClassIndex should not find the index",
        wrongCaseIdx);
  }
}
