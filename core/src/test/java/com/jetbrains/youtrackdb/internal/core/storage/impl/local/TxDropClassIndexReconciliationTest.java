package com.jetbrains.youtrackdb.internal.core.storage.impl.local;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.storage.config.CollectionBasedStorageConfiguration;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

/**
 * Pins for the transactional {@code dropClass} index reconciliation: a class dropped inside a
 * transaction must drop its committed indexes at commit — registry entry, {@code
 * classPropertyIndex} key, storage-configuration engine entry, engine files, and the per-index
 * entity record — exactly as the non-transactional path does eagerly through {@code
 * dropClassIndexes}. Before this reconciliation the tx-local drop branch recorded no index drops
 * into the overlay (the Track 5 seam), so the dropped class's indexes survived the commit fully
 * registered but orphaned: unreachable through any class, their engines referencing the dropped
 * class's deleted collections.
 */
public class TxDropClassIndexReconciliationTest extends DbTestBase {

  /**
   * The core pin: tx { dropClass C } with a committed index must remove every trace of the index
   * at commit — the shared registry, the class-property lookup, the engine registration, the
   * storage-config engine entry, and the {@code ie_*} engine file family — and stay gone across
   * a reload and reopen (the index-manager link and entity record are deleted durably).
   */
  @Test
  public void txDropClassDropsItsCommittedIndexes() throws Exception {
    var schema = session.getMetadata().getSchema();
    var cls = schema.createClass("TxDropTarget");
    cls.createProperty("val", PropertyType.STRING);
    cls.createIndex("TxDropTarget.val", SchemaClass.INDEX_TYPE.UNIQUE, "val");

    var storage = (AbstractStorage) session.getStorage();
    var config = (CollectionBasedStorageConfiguration) storage.configuration;
    final int fileBaseId = storage.getAtomicOperationsManager().calculateInsideAtomicOperation(
        op -> config.getIndexEngine("TxDropTarget.val", -1, op)).getFileBaseId();
    assertFalse("precondition: the engine's files exist",
        engineFiles(storage, fileBaseId).isEmpty());

    session.begin();
    session.getMetadata().getSchema().dropClass("TxDropTarget");
    session.commit();

    var indexManager = session.getSharedContext().getIndexManager();
    assertFalse("the dropped class's index must leave the shared registry",
        indexManager.existsIndex("TxDropTarget.val"));
    assertNull("no index handle may survive", indexManager.getIndex("TxDropTarget.val"));
    assertTrue("no classPropertyIndex key may survive",
        indexManager.getClassIndexes(session, "TxDropTarget").isEmpty());
    assertEquals("the engine must be unregistered",
        -1, storage.loadIndexEngine("TxDropTarget.val"));
    assertNull("the storage-config engine entry must be deleted",
        storage.getAtomicOperationsManager().calculateInsideAtomicOperation(
            op -> config.getIndexEngine("TxDropTarget.val", -1, op)));
    assertTrue("the whole ie_* engine file family must be deleted",
        engineFiles(storage, fileBaseId).isEmpty());

    // Durable: the index-manager link and the index entity record were deleted in the commit, so
    // a reload + reopen re-derives the same absence from durable state.
    indexManager.reload(session);
    reOpen("admin", ADMIN_PASSWORD);
    var reloaded = session.getSharedContext().getIndexManager();
    assertFalse("the drop must be durable across reload and reopen",
        reloaded.existsIndex("TxDropTarget.val"));
  }

  /**
   * The rollback half: a rolled-back tx { dropClass } leaves the index fully intact and usable —
   * registry, lookup key, config entry, engine files, and live maintenance.
   */
  @Test
  public void rolledBackTxDropClassLeavesIndexesIntact() throws Exception {
    var schema = session.getMetadata().getSchema();
    var cls = schema.createClass("TxDropRb");
    cls.createProperty("val", PropertyType.STRING);
    cls.createIndex("TxDropRb.val", SchemaClass.INDEX_TYPE.UNIQUE, "val");

    var storage = (AbstractStorage) session.getStorage();
    var config = (CollectionBasedStorageConfiguration) storage.configuration;
    final int fileBaseId = storage.getAtomicOperationsManager().calculateInsideAtomicOperation(
        op -> config.getIndexEngine("TxDropRb.val", -1, op)).getFileBaseId();

    session.begin();
    session.getMetadata().getSchema().dropClass("TxDropRb");
    session.rollback();

    var indexManager = session.getSharedContext().getIndexManager();
    assertTrue("a rolled-back drop must leave the index registered",
        indexManager.existsIndex("TxDropRb.val"));
    assertEquals("a rolled-back drop must leave the classPropertyIndex key",
        1, indexManager.getClassIndexes(session, "TxDropRb").size());
    assertNotNull("a rolled-back drop must leave the config entry",
        storage.getAtomicOperationsManager().calculateInsideAtomicOperation(
            op -> config.getIndexEngine("TxDropRb.val", -1, op)));
    assertFalse("a rolled-back drop must leave the engine files",
        engineFiles(storage, fileBaseId).isEmpty());

    // The index is still fully usable and maintained after the rollback.
    session.executeInTx(tx -> tx.newEntity("TxDropRb").setProperty("val", "alive"));
    var index = indexManager.getIndex("TxDropRb.val");
    assertEquals("the index must keep serving lookups after the rollback",
        1, session.computeInTx(tx -> index.getRids(session, "alive").toList()).size());
  }

  /**
   * A same-transaction create-then-class-drop nets out: the index created in the transaction on
   * a committed class is a pending overlay create, and the class drop cancels it — nothing is
   * built at commit, no registry entry, no engine, no files beyond the pre-transaction baseline.
   */
  @Test
  public void indexCreateThenClassDropSameTxNetsOut() {
    var schema = session.getMetadata().getSchema();
    var cls = schema.createClass("TxDropNet");
    cls.createProperty("val", PropertyType.STRING);

    var storage = (AbstractStorage) session.getStorage();
    var filesBefore = allEngineFiles(storage);

    session.begin();
    session.getMetadata().getSchema().getClass("TxDropNet")
        .createIndex("TxDropNet.val", SchemaClass.INDEX_TYPE.UNIQUE, "val");
    session.getMetadata().getSchema().dropClass("TxDropNet");
    session.commit();

    var indexManager = session.getSharedContext().getIndexManager();
    assertFalse("the cancelled create must publish nothing",
        indexManager.existsIndex("TxDropNet.val"));
    assertEquals("no engine file may appear for the cancelled create",
        filesBefore, allEngineFiles(storage));
  }

  /**
   * Drop-then-recreate under the same class name in one transaction: the committed class's index
   * is dropped, the recreated class starts index-free, and the retired-name machinery (which
   * blocks rename recording for recycled names) does not cross-contaminate the recorded drops.
   * The recreated class is renamed at the end to prove the retirement guard and the drops
   * coexist. (An in-transaction index on the recreated class is inexpressible today — property
   * creation is blocked inside transactions — so the recreation is pinned index-free.)
   */
  @Test
  public void dropThenRecreateSameClassNameSameTx() {
    var schema = session.getMetadata().getSchema();
    var cls = schema.createClass("TxDropRecreate");
    cls.createProperty("val", PropertyType.STRING);
    cls.createIndex("TxDropRecreate.val", SchemaClass.INDEX_TYPE.UNIQUE, "val");

    session.begin();
    session.getMetadata().getSchema().dropClass("TxDropRecreate");
    session.getMetadata().getSchema().createClass("TxDropRecreate");
    session.getMetadata().getSchema().getClass("TxDropRecreate").setName("TxDropReborn");
    session.commit();

    var indexManager = session.getSharedContext().getIndexManager();
    assertFalse("the dropped committed index must be gone",
        indexManager.existsIndex("TxDropRecreate.val"));
    assertTrue("the recreated class must start index-free",
        indexManager.getClassIndexes(session, "TxDropRecreate").isEmpty());
    assertTrue("the renamed recreated class must own nothing either",
        indexManager.getClassIndexes(session, "TxDropReborn").isEmpty());
    assertNotNull("the recreated class must survive under its final name",
        session.getMetadata().getSchema().getClass("TxDropReborn"));
  }

  /**
   * Rename composed with drop: tx { rename A→B; dropClass B } must drop committed A's indexes
   * (found through the rename-aware lookup at drop time) and leave nothing under either name.
   */
  @Test
  public void renameThenDropClassDropsTheCommittedIndexes() {
    var schema = session.getMetadata().getSchema();
    var cls = schema.createClass("TxDropRenA");
    cls.createProperty("val", PropertyType.STRING);
    cls.createIndex("TxDropRenA.val", SchemaClass.INDEX_TYPE.UNIQUE, "val");

    session.begin();
    session.getMetadata().getSchema().getClass("TxDropRenA").setName("TxDropRenB");
    session.getMetadata().getSchema().dropClass("TxDropRenB");
    session.commit();

    var indexManager = session.getSharedContext().getIndexManager();
    assertFalse("the renamed-then-dropped class's index must be gone",
        indexManager.existsIndex("TxDropRenA.val"));
    assertTrue(indexManager.getClassIndexes(session, "TxDropRenA").isEmpty());
    assertTrue(indexManager.getClassIndexes(session, "TxDropRenB").isEmpty());
    assertEquals("the engine must be unregistered",
        -1, ((AbstractStorage) session.getStorage()).loadIndexEngine("TxDropRenA.val"));
  }

  /**
   * Regression check for the sibling ripple: dropping a SUBCLASS under an indexed parent must
   * only shrink the parent index's collection membership (the existing removeBaseClassInternal
   * ripple) — the parent's own index must survive untouched, proving the new own-index drop
   * recording does not overreach into polymorphic parents.
   */
  @Test
  public void subclassDropOnlyShrinksParentIndexMembership() {
    var schema = session.getMetadata().getSchema();
    var parent = schema.createClass("TxDropParent");
    parent.createProperty("val", PropertyType.STRING);
    parent.createIndex("TxDropParent.val", SchemaClass.INDEX_TYPE.NOTUNIQUE, "val");
    schema.createClass("TxDropChild", schema.getClass("TxDropParent"));

    var indexManager = session.getSharedContext().getIndexManager();
    var childCollections = new HashSet<String>();
    for (var collectionId : schema.getClass("TxDropChild").getCollectionIds()) {
      childCollections.add(session.getCollectionNameById(collectionId));
    }
    for (var collectionName : childCollections) {
      assertTrue("precondition: the parent index covers the child collection",
          indexManager.getIndex("TxDropParent.val").getCollections().contains(collectionName));
    }

    session.begin();
    session.getMetadata().getSchema().dropClass("TxDropChild");
    session.commit();

    assertTrue("the parent's own index must survive the subclass drop",
        indexManager.existsIndex("TxDropParent.val"));
    assertEquals("the parent must keep exactly its own index",
        1, indexManager.getClassIndexes(session, "TxDropParent").size());
    for (var collectionName : childCollections) {
      assertFalse("the parent index must stop covering the dropped child's collection",
          indexManager.getIndex("TxDropParent.val").getCollections().contains(collectionName));
    }
  }

  /**
   * The write cache's file names carrying the engine's {@code ie_<fileBaseId>} stem (any
   * extension, including {@code $null} variants).
   */
  private static Set<String> engineFiles(AbstractStorage storage, int fileBaseId) {
    var stem = AbstractStorage.indexEngineFileStem(fileBaseId);
    var result = new HashSet<String>();
    for (var fileName : storage.getWriteCache().files().keySet()) {
      if (fileName.startsWith(stem + ".")
          || fileName.startsWith(stem + AbstractStorage.NULL_TREE_SUFFIX + ".")) {
        result.add(fileName);
      }
    }
    return result;
  }

  /** Every engine-family file name in the write cache (any {@code ie_} stem). */
  private static Set<String> allEngineFiles(AbstractStorage storage) {
    var result = new HashSet<String>();
    for (var fileName : storage.getWriteCache().files().keySet()) {
      if (fileName.startsWith(AbstractStorage.INDEX_ENGINE_FILE_STEM_PREFIX)) {
        result.add(fileName);
      }
    }
    return result;
  }
}
