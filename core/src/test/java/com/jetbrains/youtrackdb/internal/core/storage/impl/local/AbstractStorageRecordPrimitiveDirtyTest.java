package com.jetbrains.youtrackdb.internal.core.storage.impl.local;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.id.ChangeableRecordId;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import com.jetbrains.youtrackdb.internal.core.record.impl.RecordBytes;
import java.util.UUID;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Verifies crash-recovery dirty marking for caller-owned record writes on disk storage. */
public class AbstractStorageRecordPrimitiveDirtyTest {

  private final String dbName = "record-primitive-dirty-" + UUID.randomUUID();
  private YouTrackDBImpl youTrackDB;
  private DatabaseSessionEmbedded db;

  @Before
  public void before() {
    youTrackDB = DbTestBase.createYTDBManagerAndDb(dbName, DatabaseType.DISK, getClass());
    db = youTrackDB.open(dbName, "admin", DbTestBase.ADMIN_PASSWORD);
  }

  @After
  public void after() {
    db.close();
    youTrackDB.close();
  }

  /** Both creation and update mark clean storage dirty before their page mutations. */
  @Test
  public void callerOwnedCreateAndUpdateMarkStorageDirty() throws Exception {
    var cls = db.createVertexClass("DirtyPrimitiveRecord");
    var storage = (AbstractStorage) db.getStorage();
    var rid =
        new ChangeableRecordId(
            cls.getCollectionIds()[0], RecordIdInternal.COLLECTION_POS_INVALID);

    storage.clearStorageDirty();
    assertThat(storage.isDirty()).isFalse();

    long initialVersion;
    storage.stateLock.readLock().lock();
    try {
      initialVersion =
          storage
              .getAtomicOperationsManager()
              .calculateInsideAtomicOperation(
                  operation -> storage.createRecordInsideAtomicOperation(
                      operation, rid, new byte[] {1}, RecordBytes.RECORD_TYPE));
    } finally {
      storage.stateLock.readLock().unlock();
    }
    assertThat(storage.isDirty()).isTrue();

    storage.clearStorageDirty();
    assertThat(storage.isDirty()).isFalse();

    storage.stateLock.readLock().lock();
    try {
      storage
          .getAtomicOperationsManager()
          .calculateInsideAtomicOperation(
              operation -> storage.updateRecordInsideAtomicOperation(
                  operation,
                  rid,
                  new byte[] {2},
                  initialVersion,
                  RecordBytes.RECORD_TYPE));
    } finally {
      storage.stateLock.readLock().unlock();
    }
    assertThat(storage.isDirty()).isTrue();
  }
}
