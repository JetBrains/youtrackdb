package com.jetbrains.youtrackdb.internal.core.storage.impl.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.api.exception.RecordDuplicatedException;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.id.SnapshotMarkerRID;
import com.jetbrains.youtrackdb.internal.core.id.TombstoneRID;
import com.jetbrains.youtrackdb.internal.core.index.CompositeKey;
import com.jetbrains.youtrackdb.internal.core.index.Index;
import com.jetbrains.youtrackdb.internal.core.index.IndexEngineTestSupport;
import com.jetbrains.youtrackdb.internal.core.index.IndexesSnapshot;
import com.jetbrains.youtrackdb.internal.core.index.engine.v1.BTreeIndexEngine;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass.INDEX_TYPE;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.v3.BTree;
import com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.v3.CellBTreeSingleValueBucketV3;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransactionIndexChanges.OPERATION;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/** CLEAR rollback and retained-history lifecycle for both engines and key layouts. */
@RunWith(Parameterized.class)
public class ClearIndexSnapshotRollbackTest extends DbTestBase {

  @Parameterized.Parameters(name = "{0}_nullKey_{1}")
  public static Object[][] parameters() {
    return new Object[][] {
        {INDEX_TYPE.UNIQUE, false}, {INDEX_TYPE.UNIQUE, true},
        {INDEX_TYPE.NOTUNIQUE, false}, {INDEX_TYPE.NOTUNIQUE, true}};
  }

  private final INDEX_TYPE type;
  private final Object key;

  public ClearIndexSnapshotRollbackTest(INDEX_TYPE type, boolean nullKey) {
    this.type = type;
    key = nullKey ? null : "key";
  }

  @Override
  protected YouTrackDBImpl createContext() {
    // Parameter sets can run concurrently. Give each manager its own path so
    // closing one parameter's manager cannot close another parameter's storage.
    dbPath = getBaseDirectoryPathStr(getClass()) + "-" + UUID.randomUUID();
    return (YouTrackDBImpl) YourTracks.instance(dbPath, createConfig());
  }

  private Index createIndex() {
    session.createClass("Payload");
    var cls = session.createClass("Indexed");
    cls.createProperty("value", PropertyType.STRING);
    // Commit processes indexes by name. The cleared index must precede the failure.
    cls.createIndex("a_cleared", type, "value");
    cls.createIndex("z_duplicate", INDEX_TYPE.UNIQUE, "value");
    return session.getIndex("a_cleared");
  }

  private RID newPayload() {
    session.begin();
    var entity = session.newEntity("Payload");
    session.commit();
    return entity.getIdentity();
  }

  private void put(Index index, RID rid) {
    session.begin();
    index.put(session.getTransactionInternal(), key, rid);
    session.commit();
  }

  private void replace(Index index, RID oldRid, RID newRid) {
    session.begin();
    index.remove(session.getTransactionInternal(), key, oldRid);
    index.put(session.getTransactionInternal(), key, newRid);
    session.commit();
  }

  private void clear(Index index) {
    session.begin();
    session.getTransactionInternal().addIndexEntry(index, index.getName(), OPERATION.CLEAR,
        null, null);
    session.commit();
  }

  private static List<RID> lookup(DatabaseSessionEmbedded reader, Index index, Object key) {
    reader.activateOnCurrentThread();
    try (var rids = index.getRids(reader, key)) {
      return rids.toList();
    }
  }

  private BTreeIndexEngine engine(Index index) throws Exception {
    return (BTreeIndexEngine) session.getStorage()
        .getIndexEngine(IndexEngineTestSupport.externalIdentifier(index));
  }

  private IndexesSnapshot history(Index index) throws Exception {
    var storage = session.getStorage();
    var fileBaseId = engine(index).getFileBaseId();
    var snapshot = type == INDEX_TYPE.NOTUNIQUE && key == null
        ? storage.subNullIndexSnapshot(fileBaseId)
        : storage.subIndexSnapshot(fileBaseId);
    var byName = type == INDEX_TYPE.NOTUNIQUE && key == null
        ? storage.getNullIndexSnapshotByEngineName(index.getName())
        : storage.getIndexSnapshotByEngineName(index.getName());
    assertThat(byName.allEntries()).containsExactlyElementsOf(snapshot.allEntries());
    return snapshot;
  }

  private Index createIndexInReusedSlot() throws Exception {
    var discardedClass = session.createClass("Discarded");
    discardedClass.createProperty("value", PropertyType.STRING);
    discardedClass.createIndex("discarded", type, "value");
    var discardedEngine = engine(session.getIndex("discarded"));
    session.getSharedContext().getIndexManager().dropIndex(session, "discarded");
    // Commit-time creation uses the first free registry slot.
    session.begin();
    createIndex();
    session.commit();
    var index = session.getIndex("a_cleared");
    var currentEngine = engine(index);
    assertThat(currentEngine.getId()).isEqualTo(discardedEngine.getId());
    assertThat(currentEngine.getFileBaseId()).isNotEqualTo(currentEngine.getId());
    assertThat(currentEngine.getFileBaseId()).isGreaterThan(discardedEngine.getFileBaseId());
    return index;
  }

  @SuppressWarnings("unchecked")
  private BTree<CompositeKey> tree(BTreeIndexEngine engine) throws Exception {
    var treeField = engine.getClass().getDeclaredField(
        type == INDEX_TYPE.UNIQUE ? "sbTree" : key == null ? "nullTree" : "svTree");
    treeField.setAccessible(true);
    return (BTree<CompositeKey>) treeField.get(engine);
  }

  /**
   * A real removal must write history under the new file base id, never the reused slot.
   * Keep a different RID in the slot namespace throughout the history-dependent reader
   * lookup so that selecting the old family's view cannot silently pass.
   */
  @Test
  public void reusedSlotWritesAndReadsOnlyTheNewFileFamilyHistory() throws Exception {
    var index = createIndexInReusedSlot();
    var currentEngine = engine(index);
    var original = newPayload();
    var foreign = newPayload();
    put(index, original);
    var tree = tree(currentEngine);
    var operations = session.getStorage().getAtomicOperationsManager();
    var entryKey = operations.calculateInsideAtomicOperation(op -> {
      try (var entries = tree.allEntries(op)) {
        return entries.findFirst().orElseThrow().first();
      }
    });
    var prefix = new CompositeKey(entryKey.getKeys().subList(0, entryKey.getKeys().size() - 1));
    var slotHistory = type == INDEX_TYPE.NOTUNIQUE && key == null
        ? session.getStorage().subNullIndexSnapshot(currentEngine.getId())
        : session.getStorage().subIndexSnapshot(currentEngine.getId());
    // Seed after DROP because DROP retention belongs to a separate lifecycle contract.
    // Version zero leaves this distinguishable sentinel intact beside real write versions.
    slotHistory.addSnapshotPair(new CompositeKey(prefix, 0L),
        new CompositeKey(prefix, Long.MAX_VALUE), foreign);
    var sentinel = List.copyOf(slotHistory.allEntries());
    try (var reader = openDatabase()) {
      reader.begin();
      assertThat(lookup(reader, index, key)).containsExactly(original);
      session.activateOnCurrentThread();
      assertThat(history(index).allEntries()).isEmpty();
      session.begin();
      index.remove(session.getTransactionInternal(), key, original);
      session.commit();
      // Check the producer before any synthetic history is added to the file namespace.
      assertThat(history(index).allEntries()).hasSize(2).allSatisfy(entry -> {
        assertThat(entry.getKey().getKeys().getFirst())
            .isEqualTo((long) currentEngine.getFileBaseId());
        assertThat(entry.getValue().getIdentity()).isEqualTo(original);
      });
      // The slot contains only the old-family sentinel, not the real removal's pair.
      assertThat(slotHistory.allEntries()).containsExactlyElementsOf(sentinel);
      assertThat(lookup(reader, index, key)).containsExactly(original);
      session.activateOnCurrentThread();
      assertThat(slotHistory.allEntries()).containsExactlyElementsOf(sentinel);
      reader.activateOnCurrentThread();
      reader.rollback();
    } finally {
      session.activateOnCurrentThread();
      slotHistory.clear();
    }
  }

  /**
   * Exercise GC on the real engine's leaf, with distinct slot and file ids, for all
   * regular/null tree layouts. A retained-history fixture makes an old marker eligible
   * for the identity-dependent GC check. A subsequent real remove must preserve its
   * tombstone and the reader's old RID.
   */
  @Test
  public void reusedSlotKeepsHistoryAndGcInTheFileBaseNamespace() throws Exception {
    var index = createIndexInReusedSlot();
    var currentEngine = engine(index);
    var original = newPayload();
    if (type == INDEX_TYPE.UNIQUE) {
      var previous = newPayload();
      put(index, previous);
      replace(index, previous, original);
    } else {
      put(index, original);
      // MV re-put creates a marker. UNIQUE treats a same-RID put as a no-op.
      put(index, original);
    }
    newPayload(); // Advance the next reader's LWM past the marker version.
    var tree = tree(currentEngine);
    var operations = session.getStorage().getAtomicOperationsManager();
    var markerKey = operations.calculateInsideAtomicOperation(op -> {
      try (var entries = tree.allEntries(op)) {
        return entries.filter(p -> p.second() instanceof SnapshotMarkerRID)
            .findFirst().orElseThrow().first();
      }
    });
    var prefix = new CompositeKey(markerKey.getKeys().subList(0, markerKey.getKeys().size() - 1));
    try (var reader = openDatabase()) {
      reader.begin();
      assertThat(lookup(reader, index, key)).containsExactly(original);
      session.activateOnCurrentThread();
      // Model retained history from a reverted write. Its guard is newer than LWM while the
      // restored marker is older. GC must query this engine's file base id to keep the marker.
      history(index).addSnapshotPair(markerKey,
          new CompositeKey(prefix, Long.MAX_VALUE - 1), original);
      var lwm = session.getStorage().computeGlobalLowWaterMark();
      assertThat((Long) markerKey.getKeys().getLast()).isLessThan(lwm);
      assertThat(session.getStorage().hasActiveIndexSnapshotEntriesById(
          currentEngine.getFileBaseId(), type == INDEX_TYPE.NOTUNIQUE && key == null, prefix, lwm))
          .isTrue();
      assertThat(session.getStorage().hasActiveIndexSnapshotEntriesById(
          currentEngine.getId(), type == INDEX_TYPE.NOTUNIQUE && key == null, prefix, lwm))
          .isFalse();
      var gc = BTree.class.getDeclaredMethod("filterAndRebuildBucket",
          CellBTreeSingleValueBucketV3.class);
      gc.setAccessible(true);
      operations.executeInsideAtomicOperation(op -> {
        try (var page = op.loadPageForWrite(tree.getFileId(), 1, 1, true)) {
          var bucket = new CellBTreeSingleValueBucketV3<CompositeKey>(page);
          assertThat(bucket.isLeaf()).isTrue();
          assertThat(gc.invoke(tree, bucket)).isEqualTo(0);
        }
        assertThat(tree.get(markerKey, op)).isInstanceOf(SnapshotMarkerRID.class);
      });
      session.begin();
      index.remove(session.getTransactionInternal(), key, original);
      session.commit();
      operations.executeInsideAtomicOperation(op -> {
        try (var page = op.loadPageForWrite(tree.getFileId(), 1, 1, true)) {
          assertThat(gc.invoke(tree, new CellBTreeSingleValueBucketV3<CompositeKey>(page)))
              .isEqualTo(0);
        }
        try (var entries = tree.allEntries(op)) {
          assertThat(entries.map(p -> p.second()).toList())
              .anyMatch(TombstoneRID.class::isInstance);
        }
      });
      assertThat(lookup(reader, index, key)).containsExactly(original);
      reader.rollback();
    } finally {
      session.activateOnCurrentThread();
    }
  }

  /**
   * A duplicate on a later index fails commit after CLEAR. The histogram spy proves
   * engine CLEAR ran. The old reader must still get its original RID from history,
   * while a fresh reader gets the replacement RID from the rolled-back B-tree.
   */
  @Test
  public void failedCommitAfterClearPreservesHistoryAndTree() throws Exception {
    var index = createIndex();
    var original = newPayload();
    var replacement = newPayload();
    var duplicate = session.getIndex("z_duplicate");
    put(index, original);
    put(duplicate, original);
    try (var reader = openDatabase()) {
      reader.begin();
      assertThat(lookup(reader, index, key)).containsExactly(original);
      session.activateOnCurrentThread();
      replace(index, original, replacement);
      assertThat(history(index).allEntries()).isNotEmpty();

      var engine = engine(index);
      var originalManager = engine.getHistogramManager();
      var observer = spy(originalManager);
      engine.setHistogramManager(observer);
      try {
        session.begin();
        var tx = session.getTransactionInternal();
        tx.addIndexEntry(index, index.getName(), OPERATION.CLEAR, null, null);
        duplicate.put(tx, key, replacement);
        assertThrows(RecordDuplicatedException.class, () -> session.commit());
        verify(observer).resetOnClear(any(AtomicOperation.class));
      } finally {
        engine.setHistogramManager(originalManager);
      }
      assertThat(lookup(reader, index, key)).containsExactly(original);
      session.activateOnCurrentThread();
      session.begin();
      assertThat(lookup(session, index, key)).containsExactly(replacement);
      session.rollback();
      reader.activateOnCurrentThread();
      reader.rollback();
    } finally {
      session.activateOnCurrentThread();
    }
  }

  /**
   * CLEAR keeps pairs until LWM eviction. A reader started in the absent interval
   * must not resurrect the pre-clear RID after reinsert and update. A fresh reader
   * sees only the latest RID. Ending the last reader allows ordinary cleanup.
   */
  @Test
  public void successfulClearReinsertUpdateAndLowWaterMarkCleanup() throws Exception {
    var index = createIndex();
    var original = newPayload();
    var replacement = newPayload();
    var latest = newPayload();
    put(index, original);
    try (var pin = openDatabase()) {
      pin.begin();
      assertThat(lookup(pin, index, key)).containsExactly(original);
      session.activateOnCurrentThread();
      replace(index, original, replacement);
      var history = history(index);
      var retained = List.copyOf(history.allEntries());
      clear(index);
      assertThat(history.allEntries()).containsExactlyElementsOf(retained);
      try (var absentReader = openDatabase()) {
        absentReader.begin();
        assertThat(lookup(absentReader, index, key)).isEmpty();
        session.activateOnCurrentThread();
        put(index, original);
        replace(index, original, latest);
        assertThat(lookup(absentReader, index, key)).isEmpty();
        session.activateOnCurrentThread();
        session.begin();
        assertThat(lookup(session, index, key)).containsExactly(latest);
        session.rollback();
        absentReader.activateOnCurrentThread();
        absentReader.rollback();
      }
      session.activateOnCurrentThread();
      session.getStorage().getContextConfiguration()
          .setValue(GlobalConfiguration.STORAGE_SNAPSHOT_INDEX_CLEANUP_THRESHOLD, 0);
      assertThat(history.allEntries()).isNotEmpty();
      pin.activateOnCurrentThread();
      pin.rollback();
      session.activateOnCurrentThread();
      // Wait for background cleanup with an older LWM. Holding its reentrant lock
      // through the write ensures transaction-close cleanup cannot skip eviction.
      var cleanupLock = session.getStorage().snapshotCleanupLock;
      assertThat(cleanupLock.tryLock(10, TimeUnit.SECONDS)).isTrue();
      try {
        // A real write advances LWM beyond the last pair. An empty transaction
        // does not allocate a commit timestamp, so it cannot make that pair stale.
        newPayload();
        assertThat(history.allEntries()).isEmpty();
      } finally {
        cleanupLock.unlock();
      }
    } finally {
      session.activateOnCurrentThread();
    }
  }
}
