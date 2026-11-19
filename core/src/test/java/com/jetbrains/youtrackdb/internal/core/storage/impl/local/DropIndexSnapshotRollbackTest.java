package com.jetbrains.youtrackdb.internal.core.storage.impl.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertThrows;

import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.exception.CommandInterruptedException;
import com.jetbrains.youtrackdb.internal.core.index.Index;
import com.jetbrains.youtrackdb.internal.core.index.engine.v1.BTreeIndexEngine;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass.INDEX_TYPE;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/** DROP rollback must reconnect every engine/key layout to its retained history. */
@RunWith(Parameterized.class)
public class DropIndexSnapshotRollbackTest extends DbTestBase {

  @Parameterized.Parameters(name = "{0}_nullKey_{1}")
  public static Object[][] parameters() {
    return new Object[][] {
        {INDEX_TYPE.UNIQUE, false}, {INDEX_TYPE.UNIQUE, true},
        {INDEX_TYPE.NOTUNIQUE, false}, {INDEX_TYPE.NOTUNIQUE, true}};
  }

  private final INDEX_TYPE type;
  private final Object key;

  public DropIndexSnapshotRollbackTest(INDEX_TYPE type, boolean nullKey) {
    this.type = type;
    key = nullKey ? null : "key";
  }

  @Override
  protected YouTrackDBImpl createContext() {
    dbPath = getBaseDirectoryPathStr(getClass()) + "-" + UUID.randomUUID();
    return (YouTrackDBImpl) YourTracks.instance(dbPath, createConfig());
  }

  private Index createIndex() {
    session.createClass("Payload");
    var cls = session.createClass("Indexed");
    cls.createProperty("value", PropertyType.STRING);
    cls.createIndex("dropped", type, "value");
    return session.getIndex("dropped");
  }

  private RID newPayload() {
    session.begin();
    var rid = session.newEntity("Payload").getIdentity();
    session.commit();
    return rid;
  }

  private BTreeIndexEngine engine(String name) throws Exception {
    var storage = session.getStorage();
    return (BTreeIndexEngine) storage.getIndexEngine(storage.loadIndexEngine(name));
  }

  private List<RID> lookup(DatabaseSessionEmbedded reader, Index index, Object lookupKey) {
    reader.activateOnCurrentThread();
    try (var rids = index.getRids(reader, lookupKey)) {
      return rids.toList();
    }
  }

  /** A post-delete fault restores the tree and the original reader's history-dependent RID. */
  @Test
  public void failedDropKeepsHistoryAndTree() throws Exception {
    failedDrop(false);
  }

  /** Create-undo deletes B before restoring A in the same slot, without losing A's history. */
  @Test
  public void failedDropAndSameSlotCreateKeepsHistoryAndTree() throws Exception {
    failedDrop(true);
  }

  private void failedDrop(boolean createReplacement) throws Exception {
    var index = createIndex();
    var original = newPayload();
    var survivor = newPayload();
    session.begin();
    index.put(session.getTransactionInternal(), key, original);
    index.put(session.getTransactionInternal(), "survivor", survivor);
    session.commit();
    var originalEngine = engine("dropped");
    var storage = session.getStorage();
    try (var reader = openDatabase()) {
      reader.begin();
      assertThat(lookup(reader, index, key)).containsExactly(original);
      session.activateOnCurrentThread();
      session.begin();
      index.remove(session.getTransactionInternal(), key, original);
      session.commit();
      assertThat(lookup(reader, index, key)).containsExactly(original);
      session.activateOnCurrentThread();
      var history = type == INDEX_TYPE.NOTUNIQUE && key == null
          ? storage.subNullIndexSnapshot(originalEngine.getFileBaseId())
          : storage.subIndexSnapshot(originalEngine.getFileBaseId());
      var retained = List.copyOf(history.allEntries());
      assertThat(retained).hasSize(2);
      var hookRan = new AtomicBoolean();
      storage.setPostEngineBuildTestHook(() -> {
        assertThat(storage.isIndexEngineRegisteredInCommitWindow("dropped")).isFalse();
        if (createReplacement) {
          try {
            var replacement = engine("replacement");
            assertThat(replacement.getId()).isEqualTo(originalEngine.getId());
            assertThat(replacement.getFileBaseId()).isNotEqualTo(originalEngine.getFileBaseId());
          } catch (Exception e) {
            throw new AssertionError(e);
          }
        }
        hookRan.set(true);
        throw new CommandInterruptedException(session.getDatabaseName(), "post-DROP failure");
      });
      try {
        session.begin();
        session.getSharedContext().getIndexManager().dropIndex(session, "dropped");
        if (createReplacement) {
          session.getMetadata().getSchema().getClass("Indexed")
              .createIndex("replacement", type, "value");
        }
        assertThrows(CommandInterruptedException.class, session::commit);
      } finally {
        storage.setPostEngineBuildTestHook(null);
      }
      assertThat(hookRan.get()).isTrue();
      // Re-resolve the restored handle. Never query the engine object torn down by DROP.
      var restored = session.getIndex("dropped");
      var restoredEngine = engine("dropped");
      assertThat(restoredEngine).isNotSameAs(originalEngine);
      assertThat(restoredEngine.getFileBaseId()).isEqualTo(originalEngine.getFileBaseId());
      assertThat(lookup(reader, restored, key)).containsExactly(original);
      assertThat(lookup(reader, restored, "survivor")).containsExactly(survivor);
      assertThat(history.allEntries()).containsExactlyElementsOf(retained);
      session.activateOnCurrentThread();
      session.begin();
      assertThat(lookup(session, restored, key)).isEmpty();
      // A second key on the dropped index proves physical B-tree data survived rollback.
      assertThat(lookup(session, restored, "survivor")).containsExactly(survivor);
      session.rollback();
      reader.activateOnCurrentThread();
      reader.rollback();
    } finally {
      session.activateOnCurrentThread();
    }
  }

  /** A committed same-slot replacement cannot expose the dropped family's RID to any reader. */
  @Test
  public void successfulDropAndSameSlotCreateDoesNotExposeOldHistory() throws Exception {
    var index = createIndex();
    var original = newPayload();
    var current = newPayload();
    session.begin();
    index.put(session.getTransactionInternal(), key, original);
    session.commit();
    var oldEngine = engine("dropped");
    try (var reader = openDatabase()) {
      reader.begin();
      assertThat(lookup(reader, index, key)).containsExactly(original);
      session.activateOnCurrentThread();
      session.begin();
      index.remove(session.getTransactionInternal(), key, original);
      session.commit();
      session.begin();
      session.getSharedContext().getIndexManager().dropIndex(session, "dropped");
      session.getMetadata().getSchema().getClass("Indexed")
          .createIndex("replacement", type, "value");
      session.commit();
      var replacement = session.getIndex("replacement");
      var replacementEngine = engine("replacement");
      assertThat(replacementEngine.getId()).isEqualTo(oldEngine.getId());
      assertThat(replacementEngine.getFileBaseId()).isNotEqualTo(oldEngine.getFileBaseId());
      assertThat(lookup(reader, replacement, key)).isEmpty();
      session.activateOnCurrentThread();
      session.begin();
      replacement.put(session.getTransactionInternal(), key, current);
      session.commit();
      assertThat(lookup(reader, replacement, key)).isEmpty();
      session.activateOnCurrentThread();
      session.begin();
      assertThat(lookup(session, replacement, key)).containsExactly(current);
      session.rollback();
      reader.activateOnCurrentThread();
      reader.rollback();
    } finally {
      session.activateOnCurrentThread();
    }
  }
}
