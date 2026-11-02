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
import com.jetbrains.youtrackdb.internal.core.index.Index;
import com.jetbrains.youtrackdb.internal.core.index.IndexEngineTestSupport;
import com.jetbrains.youtrackdb.internal.core.index.IndexesSnapshot;
import com.jetbrains.youtrackdb.internal.core.index.engine.v1.BTreeIndexEngine;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass.INDEX_TYPE;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
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

  private IndexesSnapshot history(Index index) {
    var storage = session.getStorage();
    return type == INDEX_TYPE.NOTUNIQUE && key == null
        ? storage.getNullIndexSnapshotByEngineName(index.getName())
        : storage.getIndexSnapshotByEngineName(index.getName());
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
