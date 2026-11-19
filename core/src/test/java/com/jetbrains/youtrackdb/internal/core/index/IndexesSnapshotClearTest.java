package com.jetbrains.youtrackdb.internal.core.index;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass.INDEX_TYPE;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/** DROP retains history while a reader pins it, then ordinary LWM eviction reclaims it. */
@RunWith(Parameterized.class)
public class IndexesSnapshotClearTest extends DbTestBase {

  @Parameterized.Parameters(name = "{0}_nullKey_{1}")
  public static Object[][] parameters() {
    return new Object[][] {
        {INDEX_TYPE.UNIQUE, false}, {INDEX_TYPE.UNIQUE, true},
        {INDEX_TYPE.NOTUNIQUE, false}, {INDEX_TYPE.NOTUNIQUE, true}};
  }

  private final INDEX_TYPE type;
  private final Object key;

  public IndexesSnapshotClearTest(INDEX_TYPE type, boolean nullKey) {
    this.type = type;
    key = nullKey ? null : "key";
  }

  @Override
  protected YouTrackDBImpl createContext() {
    dbPath = getBaseDirectoryPathStr(getClass()) + "-" + UUID.randomUUID();
    return (YouTrackDBImpl) YourTracks.instance(dbPath, createConfig());
  }

  /** Both history maps and the shared counter survive DROP and return to baseline after eviction. */
  @Test
  public void dropRetainsPinnedHistoryUntilLowWaterMarkCleanup() throws Exception {
    var cls = session.createClass("Indexed");
    cls.createProperty("value", PropertyType.STRING);
    cls.createIndex("dropped", type, "value");
    var index = session.getIndex("dropped");
    session.begin();
    var rid = session.newEntity().getIdentity();
    session.commit();
    session.begin();
    index.put(session.getTransactionInternal(), key, rid);
    session.commit();
    var storage = session.getStorage();
    var history = type == INDEX_TYPE.NOTUNIQUE && key == null
        ? storage.getNullIndexSnapshotByEngineName("dropped")
        : storage.getIndexSnapshotByEngineName("dropped");
    try (var reader = openDatabase()) {
      reader.begin();
      try (var rids = index.getRids(reader, key)) {
        assertThat(rids.toList()).containsExactly(rid);
      }
      session.activateOnCurrentThread();
      session.begin();
      index.remove(session.getTransactionInternal(), key, rid);
      session.commit();
      var retained = List.copyOf(history.allEntries());
      assertThat(retained).hasSize(2);
      var count = storage.getIndexesSnapshotEntriesCount().get();
      session.executeInTx(tx -> session.getSharedContext().getIndexManager()
          .dropIndex(session, "dropped"));
      assertThat(history.allEntries()).containsExactlyElementsOf(retained);
      assertThat(storage.getIndexesSnapshotEntriesCount().get()).isEqualTo(count);
      // Serialize both cleanup checks against background passes that can capture an older LWM.
      var field = AbstractStorage.class.getDeclaredField("snapshotCleanupLock");
      field.setAccessible(true);
      var lock = (ReentrantLock) field.get(storage);
      assertThat(lock.tryLock(10, TimeUnit.SECONDS)).isTrue();
      try {
        storage.getContextConfiguration()
            .setValue(GlobalConfiguration.STORAGE_SNAPSHOT_INDEX_CLEANUP_THRESHOLD, 0);
        // Explicit cleanup runs even though the reader keeps this thread's activeTxCount nonzero.
        var cleanup = AbstractStorage.class.getDeclaredMethod("cleanupSnapshotIndex");
        cleanup.setAccessible(true);
        cleanup.invoke(storage);
        assertThat(history.allEntries()).containsExactlyElementsOf(retained);
        assertThat(storage.getIndexesSnapshotEntriesCount().get()).isEqualTo(count);
        reader.activateOnCurrentThread();
        reader.rollback();
        session.activateOnCurrentThread();
        session.executeInTx(tx -> session.newEntity());
        assertThat(history.allEntries()).isEmpty();
        assertThat(storage.getIndexesSnapshotEntriesCount().get()).isZero();
      } finally {
        lock.unlock();
      }
    } finally {
      session.activateOnCurrentThread();
    }
  }
}
