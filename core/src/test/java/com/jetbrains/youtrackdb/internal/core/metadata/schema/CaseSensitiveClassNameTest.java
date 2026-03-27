package com.jetbrains.youtrackdb.internal.core.metadata.schema;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.internal.BaseMemoryInternalDatabase;
import com.jetbrains.youtrackdb.internal.core.db.tool.DatabaseExport;
import com.jetbrains.youtrackdb.internal.core.db.tool.DatabaseImport;
import com.jetbrains.youtrackdb.internal.core.exception.SchemaException;
import com.jetbrains.youtrackdb.internal.core.index.Index;
import com.jetbrains.youtrackdb.internal.core.index.IndexException;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.storage.StorageCollection;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
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
   * different-case lookup does not find it. Also verifies that the
   * IndexDefinition record preserves the original-case class name.
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

    // getIndexDefinition throws IllegalArgumentException if not found, never returns null
    var def = immutableSchema.getIndexDefinition("IdxLookup.nameIdx");
    assertEquals("IdxLookup.nameIdx", def.name());
    assertEquals("className in IndexDefinition must match original case",
        "IdxLookup", def.className());
  }

  /**
   * Verifies that getIndexDefinition throws IllegalArgumentException when
   * called with a wrong-case index name, confirming that case-sensitive
   * lookup is enforced on the throwing path as well.
   */
  @Test(expected = IllegalArgumentException.class)
  public void testGetIndexDefinitionThrowsForWrongCaseName() {
    Schema schema = session.getMetadata().getSchema();
    var cls = schema.createClass("DefThrow");
    cls.createProperty("val", PropertyType.STRING);
    cls.createIndex("DefThrow.valIdx", SchemaClass.INDEX_TYPE.NOTUNIQUE, "val");

    var immutableSchema = session.getMetadata().getImmutableSchemaSnapshot();
    // Should throw because the name does not match (wrong case)
    immutableSchema.getIndexDefinition("defthrow.validx");
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

    // Exact-case class name should find exactly one index
    Set<Index> indexes = indexManager.getClassInvolvedIndexes(
        session, "PropLookup", "value");
    assertEquals("Exact-case lookup should return exactly one index",
        1, indexes.size());
    assertEquals("PropLookup.valueIdx",
        indexes.iterator().next().getName());

    // Different-case class name should not find the index
    Set<Index> wrongCase = indexManager.getClassInvolvedIndexes(
        session, "proplookup", "value");
    assertTrue("Different-case className should not find involved indexes",
        wrongCase.isEmpty());
  }

  /**
   * Verifies that index removal works correctly with case-sensitive class
   * names: after dropping an index, the classPropertyIndex entry is properly
   * cleaned up and the index is no longer found via any lookup path.
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
        session, "RemoveIdx", "field");
    assertEquals("Exactly one index should exist before removal",
        1, before.size());
    assertEquals("RemoveIdx.fieldIdx", before.iterator().next().getName());

    // Drop the index
    indexManager.dropIndex(session, "RemoveIdx.fieldIdx");

    // Verify index is gone from classPropertyIndex
    Set<Index> after = indexManager.getClassInvolvedIndexes(
        session, "RemoveIdx", "field");
    assertTrue("Index should be gone after removal", after.isEmpty());

    // Verify through immutable schema
    var snap = session.getMetadata().getImmutableSchemaSnapshot();
    assertFalse("Index should not exist in snapshot after removal",
        snap.indexExists("RemoveIdx.fieldIdx"));

    // Verify getClassIndex also returns null after removal
    assertNull("getClassIndex should return null after index removal",
        indexManager.getClassIndex(session, "RemoveIdx", "RemoveIdx.fieldIdx"));
  }

  /**
   * Verifies that getClassIndex uses exact-case class name matching.
   * Looking up an index with the wrong-case class name should return null.
   * Also verifies the returned index identity for the exact-case lookup.
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
    assertEquals("ClassIdx.dataIdx", idx.getName());
    assertEquals("ClassIdx", idx.getDefinition().getClassName());

    // Different-case class name should not find the index
    var wrongCaseIdx = indexManager.getClassIndex(
        session, "classidx", "ClassIdx.dataIdx");
    assertNull("Different-case getClassIndex should not find the index",
        wrongCaseIdx);
  }

  /**
   * Verifies that areIndexed() uses exact-case class name matching.
   * The query planner uses this to decide index scan eligibility — a
   * false positive for wrong-case names would silently select wrong indexes.
   */
  @Test
  public void testAreIndexedIsCaseSensitive() {
    Schema schema = session.getMetadata().getSchema();
    var cls = schema.createClass("AreIdxClass");
    cls.createProperty("score", PropertyType.INTEGER);
    cls.createIndex("AreIdxClass.scoreIdx", SchemaClass.INDEX_TYPE.NOTUNIQUE, "score");

    var indexManager = session.getSharedContext().getIndexManager();

    assertTrue("Exact-case areIndexed should return true",
        indexManager.areIndexed(session, "AreIdxClass", "score"));
    assertFalse("Different-case areIndexed should return false",
        indexManager.areIndexed(session, "areidxclass", "score"));
  }

  /**
   * Verifies that getClassIndexes() uses exact-case class name matching.
   * Wrong-case class name should return an empty set.
   */
  @Test
  public void testGetClassIndexesIsCaseSensitive() {
    Schema schema = session.getMetadata().getSchema();
    var cls = schema.createClass("ClsIdxTest");
    cls.createProperty("field", PropertyType.STRING);
    cls.createIndex("ClsIdxTest.fieldIdx", SchemaClass.INDEX_TYPE.NOTUNIQUE, "field");

    var indexManager = session.getSharedContext().getIndexManager();

    Set<Index> exact = indexManager.getClassIndexes(session, "ClsIdxTest");
    assertEquals("Exact-case getClassIndexes should return one index",
        1, exact.size());
    assertEquals("ClsIdxTest.fieldIdx", exact.iterator().next().getName());

    Set<Index> wrongCase = indexManager.getClassIndexes(session, "clsidxtest");
    assertTrue("Different-case getClassIndexes should return empty set",
        wrongCase.isEmpty());
  }

  /**
   * Verifies that a composite (multi-property) index is correctly stored
   * and retrieved using exact-case class names. The addIndexInternalNoLock
   * loop builds one MultiKey per property prefix — all must be retrievable
   * with the exact-case class name and not with wrong case.
   */
  @Test
  public void testCompositeIndexLookupIsCaseSensitive() {
    Schema schema = session.getMetadata().getSchema();
    var cls = schema.createClass("CompositeIdx");
    cls.createProperty("first", PropertyType.STRING);
    cls.createProperty("last", PropertyType.STRING);
    cls.createIndex(
        "CompositeIdx.firstLastIdx",
        SchemaClass.INDEX_TYPE.NOTUNIQUE,
        "first", "last");

    var indexManager = session.getSharedContext().getIndexManager();

    // Full key lookup — exact class name
    Set<Index> fullKey = indexManager.getClassInvolvedIndexes(
        session, "CompositeIdx", "first", "last");
    assertEquals("Full composite key should return exactly one index",
        1, fullKey.size());
    assertEquals("CompositeIdx.firstLastIdx",
        fullKey.iterator().next().getName());

    // Prefix lookup — exact class name
    Set<Index> prefix = indexManager.getClassInvolvedIndexes(
        session, "CompositeIdx", "first");
    assertEquals("Prefix key should return exactly one index",
        1, prefix.size());
    assertEquals("CompositeIdx.firstLastIdx",
        prefix.iterator().next().getName());

    // Wrong-case class name — must find nothing for both lookups
    assertTrue("Full composite key, wrong-case class name: should be empty",
        indexManager.getClassInvolvedIndexes(
            session, "compositeidx", "first", "last").isEmpty());
    assertTrue("Prefix key, wrong-case class name: should be empty",
        indexManager.getClassInvolvedIndexes(
            session, "compositeidx", "first").isEmpty());
  }

  /**
   * Two classes with the same letters but different case can each hold their
   * own index without collision in classPropertyIndex. Before the fix, both
   * class names lowercased to the same key, causing one to silently overwrite
   * the other.
   */
  @Test
  public void testIndexesOfCaseVariantClassesDontCollide() {
    Schema schema = session.getMetadata().getSchema();
    var upper = schema.createClass("Widget");
    upper.createProperty("name", PropertyType.STRING);
    upper.createIndex("Widget.nameIdx", SchemaClass.INDEX_TYPE.NOTUNIQUE, "name");

    var lower = schema.createClass("widget");
    lower.createProperty("name", PropertyType.STRING);
    lower.createIndex("widget.nameIdx", SchemaClass.INDEX_TYPE.NOTUNIQUE, "name");

    var indexManager = session.getSharedContext().getIndexManager();

    Set<Index> forUpper = indexManager.getClassInvolvedIndexes(
        session, "Widget", "name");
    Set<Index> forLower = indexManager.getClassInvolvedIndexes(
        session, "widget", "name");

    assertEquals("'Widget' should have exactly one involved index",
        1, forUpper.size());
    assertEquals("'widget' should have exactly one involved index",
        1, forLower.size());

    assertEquals("Widget.nameIdx", forUpper.iterator().next().getName());
    assertEquals("widget.nameIdx", forLower.iterator().next().getName());
  }

  // --- Deferred test scenarios from Track 2 code review ---

  /**
   * TC4: Verifies that index names are preserved with exact original case
   * across a session reload (create → persist → reload → case-sensitive
   * lookup). This tests the full round-trip through IndexManager persistence
   * and ImmutableSchema reconstruction.
   */
  @Test
  public void testIndexNamePreservationAcrossSessionReload() {
    Schema schema = session.getMetadata().getSchema();
    var cls = schema.createClass("ReloadIdx");
    cls.createProperty("name", PropertyType.STRING);
    cls.createIndex("ReloadIdx.NameIdx", SchemaClass.INDEX_TYPE.NOTUNIQUE, "name");

    // Verify before reload
    var snap = session.getMetadata().getImmutableSchemaSnapshot();
    assertTrue("Index should exist before reload",
        snap.indexExists("ReloadIdx.NameIdx"));

    // Close and reopen session to force schema reload from storage
    session.close();
    session = pool.acquire();

    // Verify after reload: exact-case lookup should find the index
    snap = session.getMetadata().getImmutableSchemaSnapshot();
    assertTrue("Exact-case index name should survive reload",
        snap.indexExists("ReloadIdx.NameIdx"));
    assertFalse("Wrong-case index name should not exist after reload",
        snap.indexExists("reloadidx.nameidx"));
    assertFalse("Wrong-case index name should not exist after reload",
        snap.indexExists("RELOADIDX.NAMEIDX"));

    // Verify IndexDefinition preserves class name case
    var def = snap.getIndexDefinition("ReloadIdx.NameIdx");
    assertEquals("ReloadIdx.NameIdx", def.name());
    assertEquals("ReloadIdx", def.className());
  }

  /**
   * TC2: Verifies that the isAllClasses() guard in the security filter works
   * correctly with case-sensitive class names. A wildcard security rule
   * (database.class.*.property) must match any class regardless of name case,
   * blocking composite index creation on the filtered property.
   */
  @Test
  public void testWildcardSecurityRuleBlocksCompositeIndexForAnyClass() {
    var security = session.getSharedContext().getSecurity();

    var cls = session.getMetadata().getSchema().createClass("SecWild");
    cls.createProperty("filtered", PropertyType.STRING);
    cls.createProperty("other", PropertyType.STRING);

    // Set up wildcard security rule: database.class.*.filtered
    // with a non-trivial read rule (only non-trivial rules trigger filtering)
    session.begin();
    var policy = security.createSecurityPolicy(session, "wildcardPolicy");
    policy.setActive(true);
    policy.setReadRule("filtered = 'allowed'");
    security.saveSecurityPolicy(session, policy);
    security.setSecurityPolicy(
        session, security.getRole(session, "reader"),
        "database.class.*.filtered", policy);
    session.commit();

    // Creating a composite index on (filtered, other) should throw because
    // the wildcard rule matches ANY class including "SecWild"
    IndexException thrown = null;
    try {
      cls.createIndex("SecWild.compositeIdx", SchemaClass.INDEX_TYPE.NOTUNIQUE,
          "filtered", "other");
      fail("Expected IndexException for composite index on"
          + " wildcard-filtered property");
    } catch (IndexException e) {
      thrown = e;
    }
    assertTrue("Exception should mention the class name",
        thrown.getMessage().contains("SecWild"));
    assertTrue("Exception should mention the filtered property",
        thrown.getMessage().contains("filtered"));
    assertTrue("Exception should mention security rules",
        thrown.getMessage().contains("column security rules"));
  }

  /**
   * TC2: Verifies that a class-specific security rule only matches the
   * exact-case class name. A security rule set for "secexact" (lowercase)
   * should NOT block index creation on "SecExact" (original case) because
   * the equals() comparison is case-sensitive.
   */
  @Test
  public void testSpecificClassSecurityRuleRequiresExactCaseMatch() {
    var security = session.getSharedContext().getSecurity();

    var cls = session.getMetadata().getSchema().createClass("SecExact");
    cls.createProperty("secret", PropertyType.STRING);
    cls.createProperty("extra", PropertyType.STRING);

    // Set up class-specific security rule with WRONG case: "secexact"
    // instead of "SecExact". With case-sensitive equals(), this rule
    // should NOT match the actual class "SecExact".
    session.begin();
    var policy = security.createSecurityPolicy(session, "specificPolicy");
    policy.setActive(true);
    policy.setReadRule("secret = 'allowed'");
    security.saveSecurityPolicy(session, policy);
    security.setSecurityPolicy(
        session, security.getRole(session, "reader"),
        "database.class.secexact.secret", policy);
    session.commit();

    // Creating a composite index should SUCCEED because "secexact" != "SecExact"
    cls.createIndex("SecExact.compositeIdx", SchemaClass.INDEX_TYPE.NOTUNIQUE,
        "secret", "extra");

    // Verify the index was actually created
    var indexManager = session.getSharedContext().getIndexManager();
    Set<Index> indexes = indexManager.getClassInvolvedIndexes(
        session, "SecExact", "secret", "extra");
    assertEquals("Composite index should have been created", 1, indexes.size());
    assertEquals("SecExact.compositeIdx", indexes.iterator().next().getName());
  }

  /**
   * TC3: Verifies that index names survive an export/import cycle with
   * exact case preserved. The import flow uses equals() to compare index
   * names against the EXPORT_IMPORT_INDEX_NAME constant — this test
   * ensures that case-sensitive comparison works correctly in that path.
   */
  @Test
  public void testExportImportPreservesIndexNameCase() throws IOException {
    // Create class with mixed-case index name
    var schema = session.getMetadata().getSchema();
    var cls = schema.createClass("ExpImpTest");
    cls.createProperty("value", PropertyType.STRING);
    cls.createIndex("ExpImpTest.ValueIdx", SchemaClass.INDEX_TYPE.NOTUNIQUE, "value");

    // Export the database
    var output = new ByteArrayOutputStream();
    var exp = new DatabaseExport(session, output, (text) -> {
    });
    exp.exportDatabase();
    exp.close();
    session.close();

    // Create a fresh database for import
    String importDbName = databaseName + "_imp";
    youTrackDB.create(importDbName, dbType,
        adminUser, adminPassword, "admin",
        readerUser, readerPassword, "reader");
    try {
      session = youTrackDB.open(importDbName, adminUser, adminPassword);

      // Import from export data
      var imp = new DatabaseImport(
          session, new ByteArrayInputStream(output.toByteArray()), (text) -> {
          });
      imp.importDatabase();
      imp.close();

      // Verify index name is preserved with exact case
      var snap = session.getMetadata().getImmutableSchemaSnapshot();
      assertTrue("Exact-case index name should exist after import",
          snap.indexExists("ExpImpTest.ValueIdx"));
      assertFalse("Wrong-case index name should not exist after import",
          snap.indexExists("expimptest.valueidx"));

      // Verify IndexDefinition preserves both index name and class name case
      var def = snap.getIndexDefinition("ExpImpTest.ValueIdx");
      assertEquals("Index name should be preserved after import",
          "ExpImpTest.ValueIdx", def.name());
      assertEquals("Class name in IndexDefinition should be preserved after import",
          "ExpImpTest", def.className());

    } finally {
      if (session != null && !session.isClosed()) {
        session.close();
      }
      if (youTrackDB.exists(importDbName)) {
        youTrackDB.drop(importDbName);
      }
      // Reopen original session for test teardown
      session = pool.acquire();
    }
  }

  /**
   * TC2: Verifies that a class-specific security rule with exact-case class
   * name DOES block composite index creation. This is the positive counterpart
   * to testSpecificClassSecurityRuleRequiresExactCaseMatch — together they
   * confirm that equals() (not equalsIgnoreCase()) is used for class name
   * matching in the security filter.
   */
  @Test
  public void testSpecificClassSecurityRuleBlocksWhenExactCaseMatches() {
    var security = session.getSharedContext().getSecurity();

    var cls = session.getMetadata().getSchema().createClass("SecBlock");
    cls.createProperty("secret", PropertyType.STRING);
    cls.createProperty("extra", PropertyType.STRING);

    // Set up class-specific security rule with CORRECT case: "SecBlock"
    session.begin();
    var policy = security.createSecurityPolicy(session, "blockPolicy");
    policy.setActive(true);
    policy.setReadRule("secret = 'allowed'");
    security.saveSecurityPolicy(session, policy);
    security.setSecurityPolicy(
        session, security.getRole(session, "reader"),
        "database.class.SecBlock.secret", policy);
    session.commit();

    // Creating a composite index should FAIL because "SecBlock" == "SecBlock"
    IndexException thrown = null;
    try {
      cls.createIndex("SecBlock.compositeIdx", SchemaClass.INDEX_TYPE.NOTUNIQUE,
          "secret", "extra");
      fail("Expected IndexException for composite index on"
          + " exact-case-filtered property");
    } catch (IndexException e) {
      thrown = e;
    }
    assertTrue("Exception should mention the class name",
        thrown.getMessage().contains("SecBlock"));
    assertTrue("Exception should mention the filtered property",
        thrown.getMessage().contains("secret"));
    assertTrue("Exception should mention security rules",
        thrown.getMessage().contains("column security rules"));
  }

  // --- changeClassName error paths ---

  /**
   * Verifies that renaming a class to its own (identical) name is a no-op
   * — the class name remains unchanged and no error is thrown.
   * SchemaClassEmbedded.setName short-circuits with equals() before calling
   * changeClassName.
   */
  @Test
  public void testRenameSameNameIsNoOp() {
    Schema schema = session.getMetadata().getSchema();
    var cls = schema.createClass("SameName");
    cls.setName("SameName");
    assertEquals("SameName", cls.getName());
    assertNotNull(schema.getClass("SameName"));
  }

  /**
   * Verifies that renaming a class to a name already taken by another class
   * throws SchemaException. The check in SchemaClassEmbedded.setName uses
   * exact-case lookup (no toLowerCase normalization).
   */
  @Test
  public void testRenameToExistingClassNameThrows() {
    Schema schema = session.getMetadata().getSchema();
    schema.createClass("Existing");
    var toRename = schema.createClass("ToRename");
    try {
      toRename.setName("Existing");
      fail("Expected SchemaException when renaming to existing class name");
    } catch (SchemaException e) {
      assertTrue("Message should mention the conflicting class name",
          e.getMessage().contains("Existing"));
    }
  }

  /**
   * Verifies that renaming a class to a name that differs only by case from
   * an existing class succeeds (since lookups are now case-sensitive).
   */
  @Test
  public void testRenameToDifferentCaseOfExistingClassSucceeds() {
    Schema schema = session.getMetadata().getSchema();
    schema.createClass("Existing");
    var toRename = schema.createClass("ToRename");
    // "existing" (lowercase) is different from "Existing" in case-sensitive mode
    toRename.setName("existing");
    assertEquals("existing", toRename.getName());
    assertNotNull("Renamed class should be findable", schema.getClass("existing"));
    assertNotNull("Original class should still exist", schema.getClass("Existing"));
  }

  // --- renameCollection edge cases ---

  /**
   * Verifies that renaming an abstract class (collectionId == -1) does not
   * attempt to rename any collections. The renameCollection loop should skip
   * negative collection IDs gracefully.
   */
  @Test
  public void testRenameAbstractClassSkipsCollectionRename() {
    Schema schema = session.getMetadata().getSchema();
    var cls = schema.createAbstractClass("AbstractOld");
    assertEquals("Abstract class should have collectionId -1",
        -1, cls.getCollectionIds()[0]);

    cls.setName("AbstractNew");

    assertEquals("AbstractNew", cls.getName());
    assertNull("Old name should not be found", schema.getClass("AbstractOld"));
    assertNotNull("New name should be found", schema.getClass("AbstractNew"));
  }

  /**
   * Verifies that renaming a concrete class correctly renames its underlying
   * collection(s) from the old lowercase prefix to the new one, preserving
   * the counter suffix. For example, "oldname_5" becomes "newname_5".
   */
  @Test
  public void testRenameClassRenamesCounterBasedCollection() {
    Schema schema = session.getMetadata().getSchema();
    var cls = schema.createClass("RenColl");

    // Record the collection name before rename
    int collectionId = cls.getCollectionIds()[0];
    String oldCollName = session.getCollectionNameById(collectionId);
    assertTrue("Collection name should start with lowercase class name",
        oldCollName.startsWith("rencoll_"));

    cls.setName("NewColl");

    // After rename, collection name should use the new prefix
    String newCollName = session.getCollectionNameById(collectionId);
    assertTrue("Renamed collection should start with new lowercase prefix",
        newCollName.startsWith("newcoll_"));
    // The numeric suffix should be preserved
    assertEquals("Counter suffix should be preserved after rename",
        oldCollName.substring(oldCollName.lastIndexOf('_')),
        newCollName.substring(newCollName.lastIndexOf('_')));
  }

  // --- initCollectionCounterFromExisting tests ---

  /**
   * Verifies that initCollectionCounterFromExisting correctly scans existing
   * collection names for the maximum _N suffix and returns a counter value
   * that is at least max(classes.size(), maxSuffix + 1). This method is
   * the fallback for pre-migration schemas that lack a persisted counter.
   */
  @Test
  public void testInitCollectionCounterFromExistingWithNumericSuffixes() {
    // Create classes to produce collections with _N suffixes
    Schema schema = session.getMetadata().getSchema();
    schema.createClass("Init1");
    schema.createClass("Init2");
    schema.createClass("Init3");

    var schemaShared = (SchemaShared) session.getSharedContext().getSchema();
    int counter = schemaShared.initCollectionCounterFromExisting(session);

    // The counter should be >= classes.size() and >= (maxSuffix + 1)
    assertTrue("Counter should be at least as large as class count",
        counter >= schemaShared.getClasses(session).size());
    assertTrue("Counter should be positive", counter > 0);
  }

  /**
   * Verifies that initCollectionCounterFromExisting handles collection names
   * without numeric suffixes (e.g., legacy names like "myclass" with no _N).
   * In this case maxSuffix stays -1, so the counter falls back to
   * classes.size().
   */
  @Test
  public void testInitCollectionCounterFromExistingNoNumericSuffix() {
    // Add a collection with a non-numeric suffix to exercise the
    // NumberFormatException catch branch
    session.addCollection("legacy_test_abc");

    var schemaShared = (SchemaShared) session.getSharedContext().getSchema();
    int counter = schemaShared.initCollectionCounterFromExisting(session);

    // Counter should still be valid (>= classes.size())
    assertTrue("Counter should be at least as large as class count",
        counter >= schemaShared.getClasses(session).size());
  }

  /**
   * Verifies that initCollectionCounterFromExisting handles a collection name
   * with no underscore at all (e.g., a bare name like "orphan").
   */
  @Test
  public void testInitCollectionCounterFromExistingNoUnderscore() {
    session.addCollection("barenamecollection");

    var schemaShared = (SchemaShared) session.getSharedContext().getSchema();
    int counter = schemaShared.initCollectionCounterFromExisting(session);

    assertTrue("Counter should be at least as large as class count",
        counter >= schemaShared.getClasses(session).size());
  }

  /**
   * Verifies that initCollectionCounterFromExisting returns at least
   * maxSuffix + 1 when a collection has a very high numeric suffix that
   * exceeds classes.size(). This ensures the Math.max selects the suffix-
   * based value, preventing collection name collisions with orphan collections.
   */
  @Test
  public void testInitCollectionCounterFromExistingHighSuffixWins() {
    session.addCollection("orphan_9999");

    var schemaShared = (SchemaShared) session.getSharedContext().getSchema();
    int counter = schemaShared.initCollectionCounterFromExisting(session);

    assertTrue("Counter should be at least maxSuffix + 1 = 10000",
        counter >= 10000);
  }

  // --- Legacy superclass fallback test ---

  /**
   * Verifies that the case-insensitive fallback in fromStream resolves
   * superclass references when the stored superclass name has different case
   * than the actual class name. This simulates a pre-migration schema where
   * superclass names were stored as lowercased.
   */
  @Test
  public void testLegacySuperclassFallbackResolvesMismatchedCase() {
    Schema schema = session.getMetadata().getSchema();

    // Create Parent → Child hierarchy
    var parent = schema.createClass("ParentCls");
    var child = schema.createClass("ChildCls", parent);

    assertTrue("Child should be a subclass of ParentCls before manipulation",
        child.isSubClassOf("ParentCls"));

    // Get the schema record and modify the child's stored superclass name to lowercase
    var schemaShared = (SchemaShared) session.getSharedContext().getSchema();
    var schemaRid = schemaShared.getIdentity();

    session.executeInTx(tx -> {
      EntityImpl schemaEntity = session.load(schemaRid);
      Collection<EntityImpl> storedClasses = schemaEntity.getProperty("classes");

      for (var storedClass : storedClasses) {
        String name = storedClass.getProperty("name");
        if ("ChildCls".equals(name)) {
          // Replace "ParentCls" with "parentcls" in the superClasses list.
          // Use session.newEmbeddedList() to create a proper embedded container.
          List<String> superClassNames = storedClass.getProperty("superClasses");
          if (superClassNames != null && superClassNames.contains("ParentCls")) {
            var modified = session.newEmbeddedList(superClassNames.size());
            for (var s : superClassNames) {
              modified.add("ParentCls".equals(s) ? "parentcls" : s);
            }
            storedClass.setProperty("superClasses", modified, PropertyType.EMBEDDEDLIST);
          }
          // Also clear the legacy "superClass" (singular) field so fromStream
          // doesn't re-add the original-case name, causing a duplicate.
          storedClass.removeProperty("superClass");
        }
      }
    });

    // Force schema reload — the fallback should resolve "parentcls" → ParentCls
    schemaShared.reload(session);

    // Verify the child class still has ParentCls as its superclass
    var reloadedChild = schema.getClass("ChildCls");
    assertNotNull("ChildCls should still exist after reload", reloadedChild);
    var supers = reloadedChild.getSuperClasses();
    assertEquals("Child should have exactly one superclass", 1, supers.size());
    assertEquals("Superclass should be ParentCls (resolved via fallback)",
        "ParentCls", supers.getFirst().getName());
  }

  /**
   * Verifies that the collectionCounter is correctly restored from the schema
   * entity when it is present (the normal, non-fallback path), and also
   * exercises the fromStream branch where persistedCounter != null.
   */
  @Test
  public void testCollectionCounterRestoredFromPersistedValue() {
    Schema schema = session.getMetadata().getSchema();

    // Create classes to advance the counter
    schema.createClass("Persist1");
    schema.createClass("Persist2");

    int id2 = schema.getClass("Persist2").getCollectionIds()[0];

    // Reload schema from storage — should restore the persisted counter
    var schemaShared = (SchemaShared) session.getSharedContext().getSchema();
    schemaShared.reload(session);

    // Create another class after reload — its collection ID must be greater
    schema.createClass("Persist3");
    int id3 = schema.getClass("Persist3").getCollectionIds()[0];
    assertTrue("Post-reload collection ID should be > pre-reload ID",
        id3 > id2);
  }

  // --- Direct changeClassName tests (package-private access) ---

  /**
   * Verifies that changeClassName throws IllegalArgumentException when called
   * with the same old and new name. This exercises the equals() guard in
   * changeClassName that prevents no-op renames at the low level.
   * SchemaClassEmbedded.setName short-circuits before this check, so
   * we must call changeClassName directly.
   */
  @Test
  public void testChangeClassNameSameNameThrowsDirectly() {
    Schema schema = session.getMetadata().getSchema();
    schema.createClass("DirectRename");

    var schemaShared = (SchemaShared) session.getSharedContext().getSchema();
    var clsImpl = schemaShared.getClass("DirectRename");
    try {
      schemaShared.changeClassName(session, "DirectRename", "DirectRename", clsImpl);
      fail("Expected IllegalArgumentException for same-name rename");
    } catch (IllegalArgumentException e) {
      assertTrue("Message should mention the class name",
          e.getMessage().contains("DirectRename"));
      assertTrue("Message should indicate same-name rename",
          e.getMessage().contains("cannot be renamed with the same name"));
    }
  }

  /**
   * Verifies that changeClassName throws IllegalArgumentException when the
   * new name already exists in the classes map. This exercises the
   * containsKey() guard in changeClassName.
   */
  @Test
  public void testChangeClassNameToExistingThrowsDirectly() {
    Schema schema = session.getMetadata().getSchema();
    schema.createClass("Target");
    schema.createClass("Source");

    var schemaShared = (SchemaShared) session.getSharedContext().getSchema();
    var clsImpl = schemaShared.getClass("Source");
    try {
      schemaShared.changeClassName(session, "Source", "Target", clsImpl);
      fail("Expected IllegalArgumentException for duplicate name");
    } catch (IllegalArgumentException e) {
      assertTrue("Message should mention the target name",
          e.getMessage().contains("Target"));
      assertTrue("Message should indicate name already exists",
          e.getMessage().contains("already present"));
    }
  }

  /**
   * Verifies that changeClassName handles a null old name gracefully (used
   * when registering a class for the first time with no previous name).
   * This exercises the short-circuit false branch of the "oldName != null &&
   * oldName.equals(newName)" guard on line 458.
   */
  @Test
  public void testChangeClassNameWithNullOldName() {
    Schema schema = session.getMetadata().getSchema();
    var cls = schema.createClass("NullOld");

    var schemaShared = (SchemaShared) session.getSharedContext().getSchema();
    var clsImpl = schemaShared.getClass("NullOld");

    // Calling with oldName=null should not throw — it just adds the class
    // under the new name without removing from the old key. This intentionally
    // leaves the class registered under both "NullOld" and "NullOldRenamed"
    // in the classes map (inconsistent state) for coverage purposes only.
    // Each test method runs with a fresh database, so this does not leak.
    schemaShared.changeClassName(session, null, "NullOldRenamed", clsImpl);

    assertNotNull("Class should be findable under new name",
        schemaShared.getClass("NullOldRenamed"));
    assertNotNull("Original name should still exist (old entry not removed)",
        schemaShared.getClass("NullOld"));
  }

  // --- renameCollection with legacy collection name ---

  /**
   * Verifies that renameCollection handles legacy collection names (without
   * counter suffix). When a collection is named "oldname" (no _N suffix),
   * renaming the class to "NewName" should rename the collection to "newname"
   * (the new lowercase prefix without suffix).
   */
  @Test
  public void testRenameClassWithLegacyCollectionName() {
    Schema schema = session.getMetadata().getSchema();
    var cls = schema.createClass("LegacyCol");

    int collectionId = cls.getCollectionIds()[0];

    // Manually rename the collection to a legacy name (no counter suffix)
    session.getStorage().setCollectionAttribute(
        collectionId,
        StorageCollection.ATTRIBUTES.NAME,
        "legacycol");

    // Verify the rename took effect
    assertEquals("legacycol", session.getCollectionNameById(collectionId));

    // Now rename the class — renameCollection should detect the legacy name
    // and rename it from "legacycol" to "newlegacy"
    cls.setName("NewLegacy");

    String newCollName = session.getCollectionNameById(collectionId);
    assertEquals("Legacy collection should be renamed to new lowercase prefix",
        "newlegacy", newCollName);
  }

  /**
   * Verifies that renameCollection skips collections whose names don't match
   * the expected pattern (neither legacy nor counter-based). This exercises
   * the final "continue" branch in renameCollection when the collection name
   * matches neither the legacy prefix nor the counter-based prefix pattern.
   */
  @Test
  public void testRenameClassSkipsUnrelatedCollectionNames() {
    Schema schema = session.getMetadata().getSchema();
    var cls = schema.createClass("SkipCol");

    int collectionId = cls.getCollectionIds()[0];

    // Manually rename the collection to something unrelated
    session.getStorage().setCollectionAttribute(
        collectionId,
        StorageCollection.ATTRIBUTES.NAME,
        "something_unrelated");

    assertEquals("something_unrelated", session.getCollectionNameById(collectionId));

    // Rename the class — renameCollection should skip the unrelated collection
    cls.setName("SkipColRenamed");

    // The collection name should remain unchanged since it didn't match
    String afterRename = session.getCollectionNameById(collectionId);
    assertEquals("Unrelated collection name should not be changed",
        "something_unrelated", afterRename);
  }
}
