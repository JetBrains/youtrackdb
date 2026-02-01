package com.jetbrains.youtrackdb.internal.core.tx;

import static junit.framework.TestCase.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.exception.RecordNotFoundException;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.common.util.Triple;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.RecordElement.STATUS;
import com.jetbrains.youtrackdb.internal.core.db.record.record.DBRecord;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.exception.ConcurrentCreateException;
import com.jetbrains.youtrackdb.internal.core.id.ChangeableRecordId;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class TransactionRidAllocationTest {

  private static final String ADMIN_PASSWORD = "adminpwd";
  private YouTrackDBImpl youTrackDB;
  private DatabaseSessionEmbedded db;

  @Before
  public void before() {
    youTrackDB = (YouTrackDBImpl) YourTracks.instance(DbTestBase.getBaseDirectoryPath(getClass()));
    youTrackDB.create("test", DatabaseType.MEMORY, "admin", ADMIN_PASSWORD, "admin");

    db = youTrackDB.open("test", "admin", ADMIN_PASSWORD);
  }

  @Test
  public void testAllocation() {
    db.begin();
    var v = db.newVertex("V");

    ((AbstractStorage) db.getStorage())
        .preallocateRids(db.getTransactionInternal());
    var generated = (RecordIdInternal) v.getIdentity();
    assertTrue(generated.isValidPosition());

    var db1 = youTrackDB.open("test", "admin", ADMIN_PASSWORD);

    var tx = db1.begin();
    try {
      tx.load(generated);
      Assert.fail();
    } catch (RecordNotFoundException e) {
      // ignore
    }
    tx.commit();

    db1.close();
  }

  @Test
  public void testAllocationCommit() {
    db.begin();
    var v = db.newVertex("V");

    ((AbstractStorage) db.getStorage())
        .preallocateRids(db.getTransactionInternal());
    var generated = v.getIdentity();
    ((AbstractStorage) db.getStorage())
        .commitPreAllocated((FrontendTransactionImpl) db.getTransactionInternal());

    var db1 = youTrackDB.open("test", "admin", ADMIN_PASSWORD);

    var tx = db1.begin();
    assertNotNull(tx.load(generated));
    tx.commit();
    db1.close();
  }

  @Test
  public void testMultipleDbAllocationAndCommit() {
    DatabaseSessionEmbedded second;
    youTrackDB.create("secondTest", DatabaseType.MEMORY, "admin", ADMIN_PASSWORD, "admin");
    second = youTrackDB.open("secondTest", "admin", ADMIN_PASSWORD);

    db.activateOnCurrentThread();
    db.begin();
    var v = db.newVertex("V");

    ((AbstractStorage) db.getStorage())
        .preallocateRids(db.getTransactionInternal());
    var generated = v.getIdentity();
    var transaction = db.getTransactionInternal();
    List<Triple<Byte, Identifiable, byte[]>> recordOperations = new ArrayList<>();
    for (var operation : transaction.getRecordOperationsInternal()) {
      var record = operation.record;
      recordOperations.add(
          new Triple<>(operation.type, operation.getRecordId(), record.toStream()));
    }

    second.activateOnCurrentThread();
    second.begin();
    var transactionOptimistic = (FrontendTransactionImpl) second.getTransactionInternal();

    for (var recordOperation : recordOperations) {
      var record = recordOperation.value.value;

      var serializer = second.getSerializer();
      var entityRid = new ChangeableRecordId(RID.COLLECTION_ID_INVALID, RID.COLLECTION_POS_INVALID);
      var deserialized = new EntityImpl(entityRid, second, "V");
      serializer.fromStream(second, record, deserialized, null);

      entityRid.setCollectionId(recordOperation.value.key.getIdentity().getCollectionId());
      entityRid.setCollectionPosition(
          recordOperation.value.key.getIdentity().getCollectionPosition());

      deserialized.setInternalStatus(STATUS.LOADED);

      transactionOptimistic.addRecordOperation(deserialized, recordOperation.key);
    }

    ((AbstractStorage) second.getStorage()).preallocateRids(transactionOptimistic);
    db.activateOnCurrentThread();
    ((AbstractStorage) db.getStorage())
        .commitPreAllocated((FrontendTransactionImpl) db.getTransactionInternal());

    var db1 = youTrackDB.open("test", "admin", ADMIN_PASSWORD);
    var tx = db1.begin();
    assertNotNull(tx.load(generated));
    tx.commit();

    db1.close();
    second.activateOnCurrentThread();
    ((AbstractStorage) second.getStorage())
        .commitPreAllocated((FrontendTransactionImpl) second.getTransactionInternal());
    second.close();
    var db2 = youTrackDB.open("secondTest", "admin", ADMIN_PASSWORD);
    tx = db2.begin();
    assertNotNull(tx.load(generated));
    tx.commit();
    db2.close();
  }

  @Test(expected = ConcurrentCreateException.class)
  public void testMultipleDbAllocationNotAlignedFailure() {
    DatabaseSessionEmbedded second;
    youTrackDB.create("secondTest", DatabaseType.MEMORY, "admin", ADMIN_PASSWORD, "admin");
    second =
        youTrackDB.open("secondTest", "admin", ADMIN_PASSWORD);
    // THIS OFFSET FIRST DB FROM THE SECOND
    for (var i = 0; i < 20; i++) {
      second.begin();
      second.newVertex("V");
      second.commit();
    }

    db.activateOnCurrentThread();
    db.begin();
    var v = db.newVertex("V");

    ((AbstractStorage) db.getStorage()).preallocateRids(db.getTransactionInternal());
    var transaction = db.getTransactionInternal();
    List<Triple<Byte, Identifiable, byte[]>> recordOperations = new ArrayList<>();
    for (var operation : transaction.getRecordOperationsInternal()) {
      var record = operation.record;
      recordOperations.add(new Triple<>(operation.type, record.getIdentity(), record.toStream()));
    }

    second.activateOnCurrentThread();
    second.begin();
    var transactionOptimistic = (FrontendTransactionImpl) second.getTransactionInternal();
    for (var recordOperation : recordOperations) {
      var record = recordOperation.value.value;
      var serializer = second.getSerializer();

      var entityRid = new ChangeableRecordId(RID.COLLECTION_ID_INVALID, RID.COLLECTION_POS_INVALID);
      var deserialized = new EntityImpl(entityRid, second, "V");
      serializer.fromStream(second, record, deserialized, null);

      entityRid.setCollectionId(recordOperation.value.key.getIdentity().getCollectionId());
      entityRid.setCollectionPosition(
          recordOperation.value.key.getIdentity().getCollectionPosition());

      deserialized.setInternalStatus(STATUS.LOADED);
      transactionOptimistic.addRecordOperation(deserialized, recordOperation.key);
    }
    ((AbstractStorage) second.getStorage()).preallocateRids(transactionOptimistic);
  }

  @Test
  public void testAllocationMultipleCommit() {
    db.begin();

    List<DBRecord> orecords = new ArrayList<>();
    var v0 = db.newVertex("V");
    for (var i = 0; i < 20; i++) {
      var v = db.newVertex("V");
      var edge = v0.addStateFulEdge(v);
      orecords.add(edge);
      orecords.add(v);
    }

    ((AbstractStorage) db.getStorage())
        .preallocateRids(db.getTransactionInternal());
    List<RID> allocated = new ArrayList<>();
    for (var rec : orecords) {
      allocated.add(rec.getIdentity());
    }
    ((AbstractStorage) db.getStorage())
        .commitPreAllocated((FrontendTransactionImpl) db.getTransactionInternal());

    var db1 = youTrackDB.open("test", "admin", ADMIN_PASSWORD);
    var tx = db1.begin();
    for (final var id : allocated) {
      assertNotNull(tx.load(id));
    }
    tx.commit();
    db1.close();
  }

  @After
  public void after() {
    db.activateOnCurrentThread();
    db.close();
    youTrackDB.close();
  }
}
