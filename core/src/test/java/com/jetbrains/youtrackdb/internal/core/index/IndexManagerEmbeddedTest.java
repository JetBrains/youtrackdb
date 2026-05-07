package com.jetbrains.youtrackdb.internal.core.index;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import java.util.Collections;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for {@link IndexManagerEmbedded} covering collection management, index lifecycle,
 * crash-recovery flag logic, and property-index lookup methods.
 */
public class IndexManagerEmbeddedTest extends DbTestBase {

  private static final String CLS = "ImeTestCls";
  private static final String IDX = CLS + ".val";

  @Override
  @Before
  public void beforeTest() throws Exception {
    super.beforeTest();
    // Schema setup must happen outside an active transaction.
    var cls = session.getMetadata().getSchema().createClass(CLS);
    cls.createProperty("val", PropertyType.INTEGER);
    cls.createProperty("name", PropertyType.STRING);
    cls.createIndex(IDX, SchemaClass.INDEX_TYPE.UNIQUE, "val");
  }

  // -----------------------------------------------------------------------
  //  addCollectionToIndex
  // -----------------------------------------------------------------------

  /**
   * Calling addCollectionToIndex with requireEmpty=false when the collection is already
   * linked to the index must be a no-op (the early-return branch inside the shared-lock
   * block is taken). Verified by checking the collection appears exactly once.
   */
  @Test
  public void addCollectionToIndex_collectionAlreadyPresent_isIdempotent() {
    var mgr = (IndexManagerEmbedded) session.getSharedContext().getIndexManager();
    var extraColl = "imeColl1";
    session.addCollection(extraColl);

    mgr.addCollectionToIndex(session, extraColl, IDX, false);
    // Second call — collection is already present, so the method returns early.
    mgr.addCollectionToIndex(session, extraColl, IDX, false);

    var idx = mgr.getIndex(IDX);
    long countMatching = idx.getCollections().stream()
        .filter(c -> c.equals(extraColl))
        .count();
    assertEquals("Collection should appear exactly once after two idempotent adds",
        1, countMatching);
  }

  /**
   * Calling addCollectionToIndex for an index name that does not exist must throw
   * IndexException (NullPointerException from index.getCollections() propagates as
   * RuntimeException; in practice the manager propagates it as-is).
   */
  @Test(expected = RuntimeException.class)
  public void addCollectionToIndex_unknownIndexName_throwsException() {
    var mgr = (IndexManagerEmbedded) session.getSharedContext().getIndexManager();
    session.addCollection("imeColl2");
    // "NoSuchIdx" does not exist → index lookup returns null → NPE
    mgr.addCollectionToIndex(session, "imeColl2", "NoSuchIdx", false);
  }

  // -----------------------------------------------------------------------
  //  removeCollectionFromIndex
  // -----------------------------------------------------------------------

  /**
   * Calling removeCollectionFromIndex when the collection is NOT in the index must be a
   * no-op (the early-return branch inside the shared-lock block is taken).
   * Verified by asserting no exception is thrown and no collection was removed.
   */
  @Test
  public void removeCollectionFromIndex_collectionNotPresent_isIdempotent() {
    var mgr = (IndexManagerEmbedded) session.getSharedContext().getIndexManager();
    var notLinked = "imeCollNotLinked";
    session.addCollection(notLinked);

    // notLinked was never added to IDX — removeCollectionFromIndex must return early.
    mgr.removeCollectionFromIndex(session, notLinked, IDX);

    // IDX should still be in the manager.
    assertNotNull("Index should still exist after no-op remove", mgr.getIndex(IDX));
  }

  // -----------------------------------------------------------------------
  //  getIndexesConfiguration
  // -----------------------------------------------------------------------

  /**
   * getIndexesConfiguration must return one config map per registered index. Each map
   * must contain at least the 'type' key.
   */
  @Test
  public void getIndexesConfiguration_returnsConfigForAllIndexes() {
    var mgr = (IndexManagerEmbedded) session.getSharedContext().getIndexManager();
    var configs = mgr.getIndexesConfiguration(session);

    assertFalse("Configuration list must not be empty", configs.isEmpty());
    for (var cfg : configs) {
      assertNotNull("Each config map must have a 'type' entry", cfg.get(Index.CONFIG_TYPE));
    }
  }

  // -----------------------------------------------------------------------
  //  autoRecreateIndexesAfterCrash
  // -----------------------------------------------------------------------

  /**
   * For a freshly created in-memory database there was no crash, so
   * autoRecreateIndexesAfterCrash must return false.
   */
  @Test
  public void autoRecreateIndexesAfterCrash_freshMemoryDb_returnsFalse() {
    var mgr = (IndexManagerEmbedded) session.getSharedContext().getIndexManager();
    // A fresh memory DB: wereDataRestoredAfterOpen() = false → returns false.
    assertFalse("No crash recovery needed for a fresh memory database",
        mgr.autoRecreateIndexesAfterCrash(session));
  }

  // -----------------------------------------------------------------------
  //  waitTillIndexRestore
  // -----------------------------------------------------------------------

  /**
   * waitTillIndexRestore must return immediately when no recreate-indexes thread is running
   * (recreateIndexesThread is null). This covers the early-return path.
   */
  @Test
  public void waitTillIndexRestore_noActiveThread_returnsImmediately() {
    var mgr = (IndexManagerEmbedded) session.getSharedContext().getIndexManager();
    // No thread was started — the null-check branch is taken and the method returns at once.
    mgr.waitTillIndexRestore();
    // If we reach here without hanging, the test passes.
  }

  // -----------------------------------------------------------------------
  //  areIndexed
  // -----------------------------------------------------------------------

  /**
   * areIndexed with a single property that has an index on it must return true.
   */
  @Test
  public void areIndexed_singleIndexedProperty_returnsTrue() {
    var mgr = (IndexManagerEmbedded) session.getSharedContext().getIndexManager();
    assertTrue("'val' has a UNIQUE index — areIndexed must return true",
        mgr.areIndexed(session, CLS, "val"));
  }

  /**
   * areIndexed with a property that has no index on it must return false.
   */
  @Test
  public void areIndexed_nonIndexedProperty_returnsFalse() {
    var mgr = (IndexManagerEmbedded) session.getSharedContext().getIndexManager();
    assertFalse("'name' has no index — areIndexed must return false",
        mgr.areIndexed(session, CLS, "name"));
  }

  /**
   * areIndexed with a class that has no indexes at all must return false (propertyIndex == null
   * branch).
   */
  @Test
  public void areIndexed_classWithNoIndexes_returnsFalse() {
    var mgr = (IndexManagerEmbedded) session.getSharedContext().getIndexManager();
    assertFalse("A class not in the classPropertyIndex map must return false",
        mgr.areIndexed(session, "NonExistentClass", "someField"));
  }

  // -----------------------------------------------------------------------
  //  getClassInvolvedIndexes
  // -----------------------------------------------------------------------

  /**
   * getClassInvolvedIndexes with the exact set of indexed fields must return the index.
   */
  @Test
  public void getClassInvolvedIndexes_indexedField_returnsIndex() {
    var mgr = (IndexManagerEmbedded) session.getSharedContext().getIndexManager();
    var involved = mgr.getClassInvolvedIndexes(session, CLS, Collections.singletonList("val"));
    assertFalse("getClassInvolvedIndexes must return at least one index for 'val'",
        involved.isEmpty());
    assertTrue("The returned index must be IDX",
        involved.stream().anyMatch(i -> IDX.equals(i.getName())));
  }

  /**
   * getClassInvolvedIndexes for a class not in the map must return an empty set.
   */
  @Test
  public void getClassInvolvedIndexes_unknownClass_returnsEmpty() {
    var mgr = (IndexManagerEmbedded) session.getSharedContext().getIndexManager();
    var involved =
        mgr.getClassInvolvedIndexes(session, "NoSuchCls", Collections.singletonList("f"));
    assertTrue("Unknown class must produce an empty set", involved.isEmpty());
  }

  // -----------------------------------------------------------------------
  //  getClassIndex
  // -----------------------------------------------------------------------

  /**
   * getClassIndex returns the index when the index's class name matches the requested class.
   */
  @Test
  public void getClassIndex_knownClassAndIndex_returnsIndex() {
    var mgr = (IndexManagerEmbedded) session.getSharedContext().getIndexManager();
    var idx = mgr.getClassIndex(session, CLS, IDX);
    assertNotNull("getClassIndex must return non-null for a known class+index pair", idx);
    assertEquals("Returned index name must match IDX", IDX, idx.getName());
  }

  /**
   * getClassIndex returns null when the index exists but belongs to a different class.
   */
  @Test
  public void getClassIndex_wrongClass_returnsNull() {
    var mgr = (IndexManagerEmbedded) session.getSharedContext().getIndexManager();
    // IDX belongs to CLS, not "WrongClass".
    var idx = mgr.getClassIndex(session, "WrongClass", IDX);
    assertNull("getClassIndex must return null when the class does not match", idx);
  }

  // -----------------------------------------------------------------------
  //  dropIndex
  // -----------------------------------------------------------------------

  /**
   * dropIndex removes the index from the manager and from the class-property index map.
   * After drop, existsIndex must return false and areIndexed must return false.
   */
  @Test
  public void dropIndex_existingIndex_removesFromManager() {
    var mgr = (IndexManagerEmbedded) session.getSharedContext().getIndexManager();
    assertTrue("Index must exist before drop", mgr.existsIndex(IDX));

    mgr.dropIndex(session, IDX);

    assertFalse("Index must not exist after drop", mgr.existsIndex(IDX));
    assertFalse("areIndexed must return false after index is dropped",
        mgr.areIndexed(session, CLS, "val"));
  }
}
