package com.jetbrains.youtrackdb.internal.core.storage.impl.local;

import com.jetbrains.youtrackdb.api.exception.RecordNotFoundException;
import com.jetbrains.youtrackdb.internal.common.function.TxFunction;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.exception.BaseException;
import com.jetbrains.youtrackdb.internal.core.exception.StorageException;
import com.jetbrains.youtrackdb.internal.core.id.ChangeableRecordId;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.index.lifecycle.IndexBuildStateStore;
import com.jetbrains.youtrackdb.internal.core.metadata.MetadataDefault;
import com.jetbrains.youtrackdb.internal.core.record.impl.RecordBytes;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Stores index lifecycle values in the storage-owned build state collection. */
final class IndexBuildStateStorageBackend implements IndexBuildStateStore.Backend {

  private static final int MAX_FIELDS = 64;
  private static final byte NULL_VALUE = 0;
  private static final byte INTEGER_VALUE = 1;
  private static final byte LONG_VALUE = 2;
  private static final byte STRING_VALUE = 3;
  private static final byte BOOLEAN_VALUE = 4;

  private final AbstractStorage storage;

  /** Test signal fired before atomic admission. Caller state protection depends on the path. */
  private volatile Runnable beforeAtomicOperationTestHook;

  IndexBuildStateStorageBackend(AbstractStorage storage) {
    this.storage = storage;
  }

  @Override
  public synchronized Optional<IndexBuildStateStore.VersionedRecord> read(RID recordIdentity) {
    verifyBuildStateIdentity(recordIdentity);
    return runAtomic(
        operation -> {
          try {
            var result = storage.readRecord(new RecordId(recordIdentity), operation).toRawBuffer();
            return Optional.of(
                new IndexBuildStateStore.VersionedRecord(
                    decode(result.buffer()), result.recordVersion()));
          } catch (RecordNotFoundException exception) {
            return Optional.empty();
          }
        });
  }

  @Override
  public IndexBuildStateStore.CreatedRecord create(Map<String, Object> value) {
    rejectProtectedStandaloneCaller(
        "Standalone lifecycle creation cannot run with storage state protection. Use "
            + "createInsideAtomicOperation instead");
    synchronized (this) {
      storage.stateLock.readLock().lock();
      try {
        var rid = new ChangeableRecordId(buildStateCollectionId(), RID.COLLECTION_POS_INVALID);
        return runAtomic(
            operation -> {
              var version =
                  storage.createRecordInsideAtomicOperation(
                      operation, rid, encode(value), RecordBytes.RECORD_TYPE);
              return new IndexBuildStateStore.CreatedRecord(
                  rid.copy(), new IndexBuildStateStore.VersionedRecord(value, version));
            });
      } finally {
        storage.stateLock.readLock().unlock();
      }
    }
  }

  IndexBuildStateStore.CreatedRecord createInsideAtomicOperation(
      com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation operation,
      Map<String, Object> value) {
    var rid = new ChangeableRecordId(buildStateCollectionId(), RID.COLLECTION_POS_INVALID);
    try {
      var version =
          storage.createRecordInsideCommitAtomicOperation(
              operation, rid, encode(value), RecordBytes.RECORD_TYPE);
      return new IndexBuildStateStore.CreatedRecord(
          rid.copy(), new IndexBuildStateStore.VersionedRecord(value, version));
    } catch (IOException exception) {
      throw BaseException.wrapException(
          new StorageException(storage.getName(), "Index build state creation failed"),
          exception,
          storage.getName());
    }
  }

  @Override
  public void checkStandaloneUpdateAllowed() {
    rejectProtectedStandaloneCaller(
        "Standalone lifecycle update cannot run with storage state protection");
  }

  @Override
  public IndexBuildStateStore.VersionedRecord update(
      RID recordIdentity, long expectedVersion, Map<String, Object> value) {
    checkStandaloneUpdateAllowed();
    synchronized (this) {
      storage.stateLock.readLock().lock();
      try {
        verifyBuildStateIdentity(recordIdentity);
        return runAtomic(
            operation -> {
              var version =
                  storage.updateRecordInsideAtomicOperation(
                      operation,
                      new RecordId(recordIdentity),
                      encode(value),
                      expectedVersion,
                      RecordBytes.RECORD_TYPE);
              return new IndexBuildStateStore.VersionedRecord(value, version);
            });
      } finally {
        storage.stateLock.readLock().unlock();
      }
    }
  }

  @Override
  public void delete(RID recordIdentity, long expectedVersion) {
    rejectProtectedStandaloneCaller(
        "Standalone lifecycle deletion cannot run with storage state protection");
    synchronized (this) {
      storage.stateLock.readLock().lock();
      try {
        verifyBuildStateIdentity(recordIdentity);
        runAtomic(
            operation -> {
              storage.deleteRecordInsideAtomicOperation(
                  operation, new RecordId(recordIdentity), expectedVersion);
              return null;
            });
      } finally {
        storage.stateLock.readLock().unlock();
      }
    }
  }

  private void verifyBuildStateIdentity(RID recordIdentity) {
    if (recordIdentity.getCollectionId() != buildStateCollectionId()) {
      throw new IllegalArgumentException(
          "Lifecycle link does not point into the index build state collection");
    }
  }

  void validateBuildStateCollection() {
    buildStateCollectionId();
  }

  private int buildStateCollectionId() {
    var collectionId =
        storage.getCollectionIdByNameWithStateLock(
            MetadataDefault.INDEX_BUILD_STATE_COLLECTION_NAME);
    if (collectionId < 0) {
      throw new IllegalStateException("The index build state collection is missing");
    }
    return collectionId;
  }

  private void rejectProtectedStandaloneCaller(String message) {
    if (storage.callerOwnsStateProtection()) {
      throw new StorageStateContextException(message);
    }
  }

  /** Installs the lock-order test signal. Production leaves this value unset. */
  void setBeforeAtomicOperationTestHook(Runnable hook) {
    beforeAtomicOperationTestHook = hook;
  }

  private <T> T runAtomic(TxFunction<T> action) {
    try {
      var testHook = beforeAtomicOperationTestHook;
      if (testHook != null) {
        testHook.run();
      }
      return storage.getAtomicOperationsManager().calculateInsideAtomicOperation(action);
    } catch (IOException exception) {
      throw BaseException.wrapException(
          new StorageException(storage.getName(), "Index build state storage operation failed"),
          exception,
          storage.getName());
    }
  }

  private static byte[] encode(Map<String, Object> value) throws IOException {
    var bytes = new ByteArrayOutputStream();
    try (var output = new DataOutputStream(bytes)) {
      output.writeInt(value.size());
      for (var entry : value.entrySet()) {
        output.writeUTF(entry.getKey());
        writeValue(output, entry.getValue());
      }
    }
    return bytes.toByteArray();
  }

  private static void writeValue(DataOutputStream output, Object value) throws IOException {
    switch (value) {
      case null -> output.writeByte(NULL_VALUE);
      case Integer integer -> {
        output.writeByte(INTEGER_VALUE);
        output.writeInt(integer);
      }
      case Long longValue -> {
        output.writeByte(LONG_VALUE);
        output.writeLong(longValue);
      }
      case String string -> {
        output.writeByte(STRING_VALUE);
        output.writeUTF(string);
      }
      case Boolean booleanValue -> {
        output.writeByte(BOOLEAN_VALUE);
        output.writeBoolean(booleanValue);
      }
      default -> throw new IllegalArgumentException(
          "Unsupported index build state value type " + value.getClass().getName());
    }
  }

  private static Map<String, Object> decode(byte[] bytes) {
    try (var input = new DataInputStream(new ByteArrayInputStream(bytes))) {
      var size = input.readInt();
      if (size < 0 || size > MAX_FIELDS) {
        throw new IllegalStateException("Invalid index build state field count " + size);
      }
      var value = new LinkedHashMap<String, Object>();
      for (var index = 0; index < size; index++) {
        value.put(input.readUTF(), readValue(input));
      }
      if (input.available() != 0) {
        throw new IllegalStateException("Index build state contains trailing bytes");
      }
      return value;
    } catch (IOException exception) {
      throw new IllegalStateException("Cannot decode index build state", exception);
    }
  }

  private static Object readValue(DataInputStream input) throws IOException {
    return switch (input.readByte()) {
      case NULL_VALUE -> null;
      case INTEGER_VALUE -> input.readInt();
      case LONG_VALUE -> input.readLong();
      case STRING_VALUE -> input.readUTF();
      case BOOLEAN_VALUE -> input.readBoolean();
      default -> throw new IllegalStateException("Invalid index build state value type");
    };
  }
}
