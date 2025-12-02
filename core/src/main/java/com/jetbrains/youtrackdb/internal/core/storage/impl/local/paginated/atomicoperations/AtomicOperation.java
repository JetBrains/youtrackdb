package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations;

import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrackdb.internal.core.storage.cache.FileHandler;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WriteAheadLog;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree.LinkBagBucketPointer;
import it.unimi.dsi.fastutil.ints.IntSet;
import java.io.IOException;
import java.util.Set;

public interface AtomicOperation {

  long getOperationUnitId();

  CacheEntry loadPageForWrite(FileHandler fileHandler, long pageIndex, int pageCount,
      boolean verifyChecksum)
      throws IOException;

  CacheEntry loadPageForRead(FileHandler fileHandler, long pageIndex) throws IOException;

  void addMetadata(AtomicOperationMetadata<?> metadata);

  AtomicOperationMetadata<?> getMetadata(String key);

  void addDeletedRidBag(LinkBagBucketPointer rootPointer);

  Set<LinkBagBucketPointer> getDeletedBonsaiPointers();

  CacheEntry addPage(FileHandler fileHandler) throws IOException;

  void releasePageFromRead(CacheEntry cacheEntry);

  void releasePageFromWrite(CacheEntry cacheEntry) throws IOException;

  long filledUpTo(long fileId);

  FileHandler addFile(String fileName) throws IOException;

  FileHandler loadFile(String fileName) throws IOException;

  void deleteFile(long fileId) throws IOException;

  boolean isFileExists(String fileName);

  String fileNameById(long fileId);

  long fileIdByName(String name);

  void truncateFile(long fileId) throws IOException;

  boolean containsInLockedObjects(String lockName);

  void addLockedObject(String lockName);

  void rollbackInProgress();

  boolean isRollbackInProgress();

  LogSequenceNumber commitChanges(WriteAheadLog writeAheadLog) throws IOException;

  Iterable<String> lockedObjects();

  void addDeletedRecordPosition(final int collectionId, final int pageIndex,
      final int recordPosition);

  IntSet getBookedRecordPositions(final int collectionId, final int pageIndex);

  void incrementComponentOperations();

  void decrementComponentOperations();

  int getComponentOperations();
}
