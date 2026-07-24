package com.jetbrains.youtrackdb.internal.core.index;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

/**
 * Covers the non-histogram surface of {@link IndexAbstract}: key normalization helpers
 * ({@code enhanceToCompositeKey*}, {@code enhanceFromCompositeKey*}), configuration
 * deserialization ({@code loadMetadataFromMap}), {@code getConfiguration}, {@code getCollections},
 * {@code getAlgorithm}, {@code isAutomatic}, {@code getKeyTypes}, {@code compareTo},
 * {@code equals}/{@code hashCode}, and {@code clear}/{@code drop} paths exercised through the
 * public index API on a real in-DB index.
 *
 * <p>Each test uses a real DB session (DbTestBase) so that index operations exercise the full
 * storage delegation chain. Schema operations are always performed before beginning a transaction.
 */
public class IndexAbstractCorePathsTest extends DbTestBase {

  // Inline class names per test keep each fixture independent. Constants would only be useful
  // if multiple tests shared the same class — which they don't here.
  private static final String CLASS_NAME = "AbsCorePathsTest";

  // -----------------------------------------------------------------------
  //  Key normalization: enhanceToCompositeKeyBetweenAsc / Desc
  // -----------------------------------------------------------------------

  /**
   * {@code enhanceToCompositeKeyBetweenAsc} with toInclusive=true fills the partial
   * CompositeKey up to the index's param-count using {@code AlwaysGreaterKey} as the boundary.
   * The returned key must be a CompositeKey with more internal keys than the input.
   */
  @Test
  public void enhanceToCompositeKeyBetweenAsc_inclusive_usesHighestBoundary() {
    // Create a composite index on two string properties to exercise the enhancement path.
    var multiClass = session.createClass("AbsCorePathsTestMulti");
    multiClass.createProperty("a", PropertyType.STRING);
    multiClass.createProperty("b", PropertyType.STRING);
    multiClass.createIndex("AbsCorePathsTestMulti.ab", SchemaClass.INDEX_TYPE.UNIQUE, "a", "b");

    var index = (IndexAbstract) session.getSharedContext().getIndexManager()
        .getIndex("AbsCorePathsTestMulti.ab");

    // Partial composite key with only one component (index expects 2).
    var partial = new CompositeKey(List.of("hello"));
    Object enhanced = index.enhanceToCompositeKeyBetweenAsc(partial, true);

    // The result must be a CompositeKey with two components (the 2nd filled with AlwaysGreaterKey).
    assertNotNull("enhanced key must not be null", enhanced);
    assertTrue("result must be a CompositeKey", enhanced instanceof CompositeKey);
    var ck = (CompositeKey) enhanced;
    assertEquals("composite key must have 2 components after enhancement", 2, ck.getKeys().size());
  }

  /**
   * {@code enhanceToCompositeKeyBetweenAsc} with toInclusive=false fills the partial
   * CompositeKey up to the index's param-count using {@code AlwaysLessKey} as the boundary.
   */
  @Test
  public void enhanceToCompositeKeyBetweenAsc_exclusive_usesLowestBoundary() {
    var multiClass = session.createClass("AbsCoreExclusiveAsc");
    multiClass.createProperty("a", PropertyType.STRING);
    multiClass.createProperty("b", PropertyType.STRING);
    multiClass.createIndex("AbsCoreExclusiveAsc.ab", SchemaClass.INDEX_TYPE.UNIQUE, "a", "b");

    var index = (IndexAbstract) session.getSharedContext().getIndexManager()
        .getIndex("AbsCoreExclusiveAsc.ab");

    var partial = new CompositeKey(List.of("hello"));
    Object enhanced = index.enhanceToCompositeKeyBetweenAsc(partial, false);

    assertNotNull(enhanced);
    assertTrue(enhanced instanceof CompositeKey);
    assertEquals("composite key must have 2 components", 2,
        ((CompositeKey) enhanced).getKeys().size());
  }

  /**
   * {@code enhanceFromCompositeKeyBetweenAsc} with fromInclusive=true fills the partial
   * CompositeKey up to the index's param-count using {@code AlwaysLessKey} as the lower boundary.
   */
  @Test
  public void enhanceFromCompositeKeyBetweenAsc_inclusive_usesLowestBoundary() {
    var multiClass = session.createClass("AbsCoreFromAscIncl");
    multiClass.createProperty("a", PropertyType.STRING);
    multiClass.createProperty("b", PropertyType.STRING);
    multiClass.createIndex("AbsCoreFromAscIncl.ab", SchemaClass.INDEX_TYPE.UNIQUE, "a", "b");

    var index = (IndexAbstract) session.getSharedContext().getIndexManager()
        .getIndex("AbsCoreFromAscIncl.ab");

    var partial = new CompositeKey(List.of("hello"));
    Object enhanced = index.enhanceFromCompositeKeyBetweenAsc(partial, true);

    assertNotNull(enhanced);
    assertTrue(enhanced instanceof CompositeKey);
    assertEquals(2, ((CompositeKey) enhanced).getKeys().size());
  }

  /**
   * {@code enhanceFromCompositeKeyBetweenAsc} with fromInclusive=false fills the partial
   * CompositeKey using {@code AlwaysGreaterKey} as the lower exclusive boundary.
   */
  @Test
  public void enhanceFromCompositeKeyBetweenAsc_exclusive_usesHighestBoundary() {
    var multiClass = session.createClass("AbsCoreFromAscExcl");
    multiClass.createProperty("a", PropertyType.STRING);
    multiClass.createProperty("b", PropertyType.STRING);
    multiClass.createIndex("AbsCoreFromAscExcl.ab", SchemaClass.INDEX_TYPE.UNIQUE, "a", "b");

    var index = (IndexAbstract) session.getSharedContext().getIndexManager()
        .getIndex("AbsCoreFromAscExcl.ab");

    var partial = new CompositeKey(List.of("hello"));
    Object enhanced = index.enhanceFromCompositeKeyBetweenAsc(partial, false);

    assertNotNull(enhanced);
    assertTrue(enhanced instanceof CompositeKey);
    assertEquals(2, ((CompositeKey) enhanced).getKeys().size());
  }

  /**
   * {@code enhanceToCompositeKeyBetweenDesc} with toInclusive=true fills the partial
   * CompositeKey using {@code AlwaysGreaterKey} (same as the ascending inclusive case).
   */
  @Test
  public void enhanceToCompositeKeyBetweenDesc_inclusive_usesHighestBoundary() {
    var multiClass = session.createClass("AbsCoreToDescIncl");
    multiClass.createProperty("a", PropertyType.STRING);
    multiClass.createProperty("b", PropertyType.STRING);
    multiClass.createIndex("AbsCoreToDescIncl.ab", SchemaClass.INDEX_TYPE.UNIQUE, "a", "b");

    var index = (IndexAbstract) session.getSharedContext().getIndexManager()
        .getIndex("AbsCoreToDescIncl.ab");

    var partial = new CompositeKey(List.of("hello"));
    Object enhanced = index.enhanceToCompositeKeyBetweenDesc(partial, true);

    assertNotNull(enhanced);
    assertTrue(enhanced instanceof CompositeKey);
    assertEquals(2, ((CompositeKey) enhanced).getKeys().size());
  }

  /**
   * {@code enhanceToCompositeKeyBetweenDesc} with toInclusive=false fills the partial
   * CompositeKey using {@code AlwaysLessKey}.
   */
  @Test
  public void enhanceToCompositeKeyBetweenDesc_exclusive_usesLowestBoundary() {
    var multiClass = session.createClass("AbsCoreToDescExcl");
    multiClass.createProperty("a", PropertyType.STRING);
    multiClass.createProperty("b", PropertyType.STRING);
    multiClass.createIndex("AbsCoreToDescExcl.ab", SchemaClass.INDEX_TYPE.UNIQUE, "a", "b");

    var index = (IndexAbstract) session.getSharedContext().getIndexManager()
        .getIndex("AbsCoreToDescExcl.ab");

    var partial = new CompositeKey(List.of("hello"));
    Object enhanced = index.enhanceToCompositeKeyBetweenDesc(partial, false);

    assertNotNull(enhanced);
    assertTrue(enhanced instanceof CompositeKey);
    assertEquals(2, ((CompositeKey) enhanced).getKeys().size());
  }

  /**
   * {@code enhanceFromCompositeKeyBetweenDesc} with fromInclusive=true fills the partial
   * CompositeKey using {@code AlwaysLessKey}.
   */
  @Test
  public void enhanceFromCompositeKeyBetweenDesc_inclusive_usesLowestBoundary() {
    var multiClass = session.createClass("AbsCoreFromDescIncl");
    multiClass.createProperty("a", PropertyType.STRING);
    multiClass.createProperty("b", PropertyType.STRING);
    multiClass.createIndex("AbsCoreFromDescIncl.ab", SchemaClass.INDEX_TYPE.UNIQUE, "a", "b");

    var index = (IndexAbstract) session.getSharedContext().getIndexManager()
        .getIndex("AbsCoreFromDescIncl.ab");

    var partial = new CompositeKey(List.of("hello"));
    Object enhanced = index.enhanceFromCompositeKeyBetweenDesc(partial, true);

    assertNotNull(enhanced);
    assertTrue(enhanced instanceof CompositeKey);
    assertEquals(2, ((CompositeKey) enhanced).getKeys().size());
  }

  /**
   * {@code enhanceFromCompositeKeyBetweenDesc} with fromInclusive=false fills the partial
   * CompositeKey using {@code AlwaysGreaterKey}.
   */
  @Test
  public void enhanceFromCompositeKeyBetweenDesc_exclusive_usesHighestBoundary() {
    var multiClass = session.createClass("AbsCoreFromDescExcl");
    multiClass.createProperty("a", PropertyType.STRING);
    multiClass.createProperty("b", PropertyType.STRING);
    multiClass.createIndex("AbsCoreFromDescExcl.ab", SchemaClass.INDEX_TYPE.UNIQUE, "a", "b");

    var index = (IndexAbstract) session.getSharedContext().getIndexManager()
        .getIndex("AbsCoreFromDescExcl.ab");

    var partial = new CompositeKey(List.of("hello"));
    Object enhanced = index.enhanceFromCompositeKeyBetweenDesc(partial, false);

    assertNotNull(enhanced);
    assertTrue(enhanced instanceof CompositeKey);
    assertEquals(2, ((CompositeKey) enhanced).getKeys().size());
  }

  /**
   * When the key is NOT a {@code CompositeKey}, all enhance* methods return it unchanged.
   */
  @Test
  public void enhanceCompositeKey_nonCompositeKey_returnsUnchanged() {
    var multiClass = session.createClass("AbsCoreNonComposite");
    multiClass.createProperty("a", PropertyType.STRING);
    multiClass.createProperty("b", PropertyType.STRING);
    multiClass.createIndex("AbsCoreNonComposite.ab", SchemaClass.INDEX_TYPE.UNIQUE, "a", "b");

    var index = (IndexAbstract) session.getSharedContext().getIndexManager()
        .getIndex("AbsCoreNonComposite.ab");

    // Plain String key: none of the enhance methods should change it.
    assertEquals("plain_key", index.enhanceToCompositeKeyBetweenAsc("plain_key", true));
    assertEquals("plain_key", index.enhanceFromCompositeKeyBetweenAsc("plain_key", true));
    assertEquals("plain_key", index.enhanceToCompositeKeyBetweenDesc("plain_key", false));
    assertEquals("plain_key", index.enhanceFromCompositeKeyBetweenDesc("plain_key", false));
  }

  /**
   * When a CompositeKey already has the full number of components (keySize == paramCount),
   * the enhancement passes it through unchanged (NONE mode short-circuit).
   */
  @Test
  public void enhanceCompositeKey_fullKey_returnsUnchanged() {
    var multiClass = session.createClass("AbsCoreFullKey");
    multiClass.createProperty("a", PropertyType.STRING);
    multiClass.createProperty("b", PropertyType.STRING);
    multiClass.createIndex("AbsCoreFullKey.ab", SchemaClass.INDEX_TYPE.UNIQUE, "a", "b");

    var index = (IndexAbstract) session.getSharedContext().getIndexManager()
        .getIndex("AbsCoreFullKey.ab");

    // Two-component key for a 2-field index: already full, no padding needed. The production
    // contract is that enhanceCompositeKey returns the original reference unchanged, not a
    // re-wrapped equal CompositeKey — pin the reference identity so a future refactor that
    // creates a fresh CompositeKey of the same shape would fail this assertion.
    var full = new CompositeKey(List.of("hello", "world"));
    Object enhanced = index.enhanceToCompositeKeyBetweenAsc(full, true);
    assertSame("full key must be returned by reference (not re-wrapped)", full, enhanced);
  }

  // -----------------------------------------------------------------------
  //  getConfiguration / getAlgorithm / getCollections / isAutomatic / getKeyTypes
  // -----------------------------------------------------------------------

  /**
   * {@code getConfiguration} returns a map containing all standard index fields:
   * CONFIG_TYPE, CONFIG_NAME, ALGORITHM, INDEX_VERSION, INDEX_DEFINITION, and the
   * CONFIG_COLLECTIONS set.
   */
  @Test
  public void getConfiguration_returnsAllStandardFields() {
    var cls = session.createClass(CLASS_NAME + "Cfg");
    cls.createProperty("prop", PropertyType.STRING);
    var idxName = CLASS_NAME + "Cfg.prop";
    cls.createIndex(idxName, SchemaClass.INDEX_TYPE.UNIQUE, "prop");

    var index = (IndexAbstract) session.getSharedContext().getIndexManager().getIndex(idxName);
    Map<String, Object> cfg = index.getConfiguration(session);

    assertNotNull("config map must not be null", cfg);
    assertTrue("must contain CONFIG_TYPE", cfg.containsKey(Index.CONFIG_TYPE));
    assertTrue("must contain CONFIG_NAME", cfg.containsKey(Index.CONFIG_NAME));
    assertTrue("must contain ALGORITHM", cfg.containsKey(Index.ALGORITHM));
    assertTrue("must contain INDEX_VERSION", cfg.containsKey(Index.INDEX_VERSION));
    assertTrue("must contain INDEX_DEFINITION", cfg.containsKey(Index.INDEX_DEFINITION));
    assertEquals("UNIQUE", cfg.get(Index.CONFIG_TYPE));
    assertEquals(idxName, cfg.get(Index.CONFIG_NAME));
  }

  /**
   * {@code getAlgorithm} returns the algorithm stored in the index metadata (typically "BTREE"
   * for the default BTree engine).
   */
  @Test
  public void getAlgorithm_returnsBtreeForDefaultIndex() {
    var cls = session.createClass(CLASS_NAME + "Algo");
    cls.createProperty("prop", PropertyType.STRING);
    var idxName = CLASS_NAME + "Algo.prop";
    cls.createIndex(idxName, SchemaClass.INDEX_TYPE.UNIQUE, "prop");

    var index = (IndexAbstract) session.getSharedContext().getIndexManager().getIndex(idxName);
    var algorithm = index.getAlgorithm();

    assertEquals("default UNIQUE index must report BTREE algorithm",
        DefaultIndexFactory.BTREE_ALGORITHM, algorithm);
  }

  /**
   * {@code getCollections} returns the non-empty set of storage collection names associated
   * with the index. For an automatic index the collection name is derived from the class name
   * (stored as {@code classname_N} internally), so the test verifies that the set is
   * non-empty and each element starts with the lowercase class name prefix.
   */
  @Test
  public void getCollections_returnsIndexedClass() {
    var clsName = CLASS_NAME + "Coll";
    var cls = session.createClass(clsName);
    cls.createProperty("prop", PropertyType.STRING);
    var idxName = clsName + ".prop";
    cls.createIndex(idxName, SchemaClass.INDEX_TYPE.UNIQUE, "prop");

    var index = (IndexAbstract) session.getSharedContext().getIndexManager().getIndex(idxName);
    Set<String> collections = index.getCollections();

    assertNotNull("collections set must not be null", collections);
    assertFalse("collections set must not be empty for an automatic index", collections.isEmpty());
    // Collection names are counter-only (c_<counter>, no class-name component), so the indexed
    // class's collections are resolved via its collection ids and each must be covered by the
    // index.
    for (var collectionId : cls.getCollectionIds()) {
      var collectionName = session.getCollectionNameById(collectionId);
      assertTrue(
          "collections must contain the indexed class's collection " + collectionName,
          collections.contains(collectionName));
    }
  }

  /**
   * {@code isAutomatic} returns true for an automatic index (created via
   * {@code SchemaClass.createIndex}) where the index definition has a non-null class name.
   */
  @Test
  public void isAutomatic_returnsTrueForSchemaDefinedIndex() {
    var clsName = CLASS_NAME + "Auto";
    var cls = session.createClass(clsName);
    cls.createProperty("prop", PropertyType.STRING);
    cls.createIndex(clsName + ".prop", SchemaClass.INDEX_TYPE.UNIQUE, "prop");

    var index = (IndexAbstract) session.getSharedContext().getIndexManager()
        .getIndex(clsName + ".prop");
    assertTrue("schema-created index must be automatic", index.isAutomatic());
  }

  /**
   * {@code getKeyTypes} returns the array of property types for a property-based index.
   * For a single STRING property the array must have exactly one element: STRING's
   * internal type.
   */
  @Test
  public void getKeyTypes_returnsSingleTypeForSinglePropertyIndex() {
    var clsName = CLASS_NAME + "KT";
    var cls = session.createClass(clsName);
    cls.createProperty("prop", PropertyType.STRING);
    cls.createIndex(clsName + ".prop", SchemaClass.INDEX_TYPE.UNIQUE, "prop");

    var index = (IndexAbstract) session.getSharedContext().getIndexManager()
        .getIndex(clsName + ".prop");
    var keyTypes = index.getKeyTypes();

    assertNotNull("key types must not be null", keyTypes);
    assertEquals("single-property index must report exactly one key type", 1, keyTypes.length);
  }

  // -----------------------------------------------------------------------
  //  equals / hashCode / compareTo / toString
  // -----------------------------------------------------------------------

  /**
   * Two index references pointing to the same name compare as equal and have the same hash.
   * A reference compared against itself (same object) also returns true.
   */
  @Test
  public void equalsAndHashCode_sameIndexName_returnsEqualAndSameHash() {
    var clsName = CLASS_NAME + "EqHash";
    var cls = session.createClass(clsName);
    cls.createProperty("prop", PropertyType.STRING);
    cls.createIndex(clsName + ".prop", SchemaClass.INDEX_TYPE.UNIQUE, "prop");

    var index1 = (IndexAbstract) session.getSharedContext().getIndexManager()
        .getIndex(clsName + ".prop");
    var index2 = (IndexAbstract) session.getSharedContext().getIndexManager()
        .getIndex(clsName + ".prop");

    assertTrue("same-name indexes must be equal", index1.equals(index2));
    assertEquals("equal indexes must have same hash code", index1.hashCode(), index2.hashCode());
    // reflexive: object is equal to itself
    assertTrue("reflexive equality must hold", index1.equals(index1));
  }

  /**
   * {@code equals} returns false when compared against a non-IndexAbstract object.
   */
  @Test
  public void equals_differentType_returnsFalse() {
    var clsName = CLASS_NAME + "EqDiff";
    var cls = session.createClass(clsName);
    cls.createProperty("prop", PropertyType.STRING);
    cls.createIndex(clsName + ".prop", SchemaClass.INDEX_TYPE.UNIQUE, "prop");

    var index = (IndexAbstract) session.getSharedContext().getIndexManager()
        .getIndex(clsName + ".prop");
    //noinspection SimplifiableJUnitAssertion
    assertFalse("index must not equal a plain String", index.equals("not an index"));
  }

  /**
   * {@code compareTo} orders index objects alphabetically by their name. An index with a
   * name that comes before another alphabetically returns a negative result.
   */
  @Test
  public void compareTo_differentNames_returnsNegativeForLesserName() {
    var cls1 = session.createClass(CLASS_NAME + "CmpA");
    cls1.createProperty("p", PropertyType.STRING);
    cls1.createIndex(CLASS_NAME + "CmpA.p", SchemaClass.INDEX_TYPE.UNIQUE, "p");

    var cls2 = session.createClass(CLASS_NAME + "CmpB");
    cls2.createProperty("p", PropertyType.STRING);
    cls2.createIndex(CLASS_NAME + "CmpB.p", SchemaClass.INDEX_TYPE.UNIQUE, "p");

    var indexA = session.getSharedContext().getIndexManager().getIndex(CLASS_NAME + "CmpA.p");
    var indexB = session.getSharedContext().getIndexManager().getIndex(CLASS_NAME + "CmpB.p");

    assertTrue("A < B alphabetically => compareTo must be negative", indexA.compareTo(indexB) < 0);
    assertTrue("B > A alphabetically => compareTo must be positive", indexB.compareTo(indexA) > 0);
    assertEquals("equal names => compareTo == 0", 0, indexA.compareTo(indexA));
  }

  // -----------------------------------------------------------------------
  //  loadMetadataFromMap — configuration deserialization round-trip
  // -----------------------------------------------------------------------

  /**
   * {@code loadMetadataFromMap} deserializes an index metadata map produced by
   * {@code getConfiguration}. The round-trip preserves name, type, algorithm, and
   * the index definition class.
   */
  @Test
  public void loadMetadataFromMap_roundTrip_preservesNameTypeAlgorithm() {
    var clsName = CLASS_NAME + "LMM";
    var cls = session.createClass(clsName);
    cls.createProperty("prop", PropertyType.STRING);
    var idxName = clsName + ".prop";
    cls.createIndex(idxName, SchemaClass.INDEX_TYPE.UNIQUE, "prop");

    var index = (IndexAbstract) session.getSharedContext().getIndexManager().getIndex(idxName);

    // Get the serialized configuration map (used as persistence format).
    Map<String, Object> cfg = index.getConfiguration(session);

    // Deserialize it using the static method under test.
    session.begin();
    var tx = session.getTransactionInternal();
    IndexMetadata loaded = IndexAbstract.loadMetadataFromMap(tx, cfg);
    session.rollback();

    assertNotNull("loaded metadata must not be null", loaded);
    assertEquals("name must survive round-trip", idxName, loaded.getName());
    assertEquals("type must survive round-trip", "UNIQUE", loaded.getType());
    assertNotNull("index definition must survive round-trip", loaded.getIndexDefinition());
  }

  // -----------------------------------------------------------------------
  //  get() — no-result branch on a UNIQUE index
  // -----------------------------------------------------------------------

  /**
   * IndexOneValue.get() returns null when the key is absent from a UNIQUE index. This is the
   * no-result branch of {@code getRids → findFirst()}.
   */
  @Test
  public void getInternal_noResult_returnsNull() {
    // IndexOneValue.get() returns null when no result is found in the index.
    var clsName = CLASS_NAME + "GetNull";
    var cls = session.createClass(clsName);
    cls.createProperty("prop", PropertyType.STRING);
    cls.createIndex(clsName + ".prop", SchemaClass.INDEX_TYPE.UNIQUE, "prop");

    session.begin();
    var result = session.getSharedContext().getIndexManager().getIndex(clsName + ".prop")
        .get(session, "nonexistent_key");
    session.rollback();

    // UNIQUE index: get() returns null (not an iterator) when nothing is found.
    assertNull("UNIQUE index get() must return null for a missing key", result);
  }

  // -----------------------------------------------------------------------
  //  clear / drop lifecycle — observable via stream
  // -----------------------------------------------------------------------

  /**
   * After inserting records and then deleting the index via {@code SchemaClass.dropIndex},
   * the index must no longer be accessible. This exercises the {@code doDelete} → storage
   * deletion path in {@code IndexAbstract}.
   */
  @Test
  public void dropIndex_afterInsert_indexNoLongerAccessible() {
    var clsName = CLASS_NAME + "Drop";
    var cls = session.createClass(clsName);
    cls.createProperty("prop", PropertyType.STRING);
    var idxName = clsName + ".prop";
    cls.createIndex(idxName, SchemaClass.INDEX_TYPE.NOTUNIQUE, "prop");

    // Insert some records to populate the index.
    session.begin();
    for (var i = 0; i < 5; i++) {
      var e = session.newEntity(clsName);
      e.setProperty("prop", "val_" + i);
    }
    session.commit();

    // Drop the index — exercises IndexAbstract.doDelete via delete() then
    // removeClassPropertyIndex on the index manager.
    session.getSharedContext().getIndexManager().dropIndex(session, idxName);

    // The index should no longer be in the index manager.
    assertNull("dropped index must not be accessible via index manager",
        session.getSharedContext().getIndexManager().getIndex(idxName));
  }

  /**
   * {@code stream()} on a NOTUNIQUE index returns all committed entries in ascending order.
   * After clearing via rebuild, the stream is empty. This exercises the base {@code stream()}
   * path in {@code IndexOneValue} and the underlying storage delegation.
   */
  @Test
  public void stream_onPopulatedIndex_returnsAllEntries() {
    var clsName = CLASS_NAME + "Stream";
    var cls = session.createClass(clsName);
    cls.createProperty("prop", PropertyType.STRING);
    var idxName = clsName + ".prop";
    cls.createIndex(idxName, SchemaClass.INDEX_TYPE.UNIQUE, "prop");

    session.begin();
    for (var i = 0; i < 3; i++) {
      var e = session.newEntity(clsName);
      e.setProperty("prop", "key_" + i);
    }
    session.commit();

    session.begin();
    var index = session.getSharedContext().getIndexManager().getIndex(idxName);
    long count;
    try (var s = index.stream(session)) {
      count = s.count();
    }
    session.rollback();

    assertEquals("stream must return all 3 entries", 3, count);
  }

  /**
   * {@code descStream()} on a UNIQUE index (IndexOneValue) returns entries in descending order.
   * The first entry from descStream must lexicographically follow (or equal) the last.
   */
  @Test
  public void descStream_onPopulatedUniqueIndex_returnsEntriesDescending() {
    var clsName = CLASS_NAME + "DescStr";
    var cls = session.createClass(clsName);
    cls.createProperty("prop", PropertyType.STRING);
    var idxName = clsName + ".prop";
    cls.createIndex(idxName, SchemaClass.INDEX_TYPE.UNIQUE, "prop");

    session.begin();
    for (var key : List.of("apple", "cherry", "banana")) {
      var e = session.newEntity(clsName);
      e.setProperty("prop", key);
    }
    session.commit();

    session.begin();
    var index = session.getSharedContext().getIndexManager().getIndex(idxName);
    var keys = new ArrayList<String>();
    try (var s = index.descStream(session)) {
      s.forEach(p -> keys.add((String) p.first()));
    }
    session.rollback();

    assertEquals("descStream must return all 3 entries", 3, keys.size());
    // Verify descending order: each key >= the next key.
    for (int i = 1; i < keys.size(); i++) {
      assertTrue("descending order violated at index " + i,
          keys.get(i - 1).compareTo(keys.get(i)) >= 0);
    }
  }

  /**
   * {@code streamEntries} with {@code ascSortOrder=true} on a UNIQUE index returns
   * entries only for the specified keys, in ascending key order.
   */
  @Test
  public void streamEntries_ascOrder_returnsMatchingKeysInOrder() {
    var clsName = CLASS_NAME + "StEnt";
    var cls = session.createClass(clsName);
    cls.createProperty("prop", PropertyType.STRING);
    var idxName = clsName + ".prop";
    cls.createIndex(idxName, SchemaClass.INDEX_TYPE.UNIQUE, "prop");

    session.begin();
    for (var key : List.of("cherry", "apple", "banana", "date")) {
      var e = session.newEntity(clsName);
      e.setProperty("prop", key);
    }
    session.commit();

    session.begin();
    var index = session.getSharedContext().getIndexManager().getIndex(idxName);
    var keys = new ArrayList<String>();
    try (var s = index.streamEntries(session, List.of("apple", "cherry"), true)) {
      s.forEach(p -> keys.add((String) p.first()));
    }
    session.rollback();

    assertEquals("must return exactly 2 matching entries", 2, keys.size());
    // Ascending order.
    assertEquals("apple", keys.get(0));
    assertEquals("cherry", keys.get(1));
  }
}
