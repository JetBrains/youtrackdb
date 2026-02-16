package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations;

import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationsTable.AtomicOperationsSnapshot;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WriteAheadLog;
import it.unimi.dsi.fastutil.ints.IntSet;
import java.io.IOException;
import javax.annotation.Nonnull;

public interface AtomicOperation {

  @Nonnull
  AtomicOperationsSnapshot getAtomicOperationsSnapshot();

  void deactivate();

  long getCommitTs();

  long getCommitTsUnsafe();

  void startToApplyOperations(long commitTs);

  CacheEntry loadPageForWrite(long fileId, long pageIndex, int pageCount, boolean verifyChecksum)
      throws IOException;

  CacheEntry loadPageForRead(long fileId, long pageIndex) throws IOException;

  void addMetadata(AtomicOperationMetadata<?> metadata);

  AtomicOperationMetadata<?> getMetadata(String key);

  CacheEntry addPage(long fileId) throws IOException;

  void releasePageFromRead(CacheEntry cacheEntry);

  void releasePageFromWrite(CacheEntry cacheEntry) throws IOException;

  long filledUpTo(long fileId);

  long addFile(String fileName) throws IOException;

  long loadFile(String fileName) throws IOException;

  void deleteFile(long fileId) throws IOException;

  boolean isFileExists(String fileName);

  long fileIdByName(String name);

  void truncateFile(long fileId) throws IOException;

  boolean containsInLockedObjects(String lockName);

  void addLockedObject(String lockName);

  void rollbackInProgress();

  boolean isRollbackInProgress();

  LogSequenceNumber commitChanges(long commitTs, WriteAheadLog writeAheadLog) throws IOException;

  Iterable<String> lockedObjects();

  void addDeletedRecordPosition(final int collectionId, final int pageIndex,
      final int recordPosition);

  IntSet getBookedRecordPositions(final int collectionId, final int pageIndex);

  @SuppressWarnings("unused")
  boolean isActive();
}
