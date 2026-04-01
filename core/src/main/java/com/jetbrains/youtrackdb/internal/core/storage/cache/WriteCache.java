/*
 *
 *
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *
 *
 */

package com.jetbrains.youtrackdb.internal.core.storage.cache;

import com.jetbrains.youtrackdb.internal.common.types.ModifiableBoolean;
import com.jetbrains.youtrackdb.internal.core.command.CommandOutputListener;
import com.jetbrains.youtrackdb.internal.core.storage.cache.local.BackgroundExceptionListener;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.PageIsBrokenListener;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

public interface WriteCache {

  /**
   * Adds listener which is called by cache if corruption of file page is detected.
   */
  void addPageIsBrokenListener(PageIsBrokenListener listener);

  /**
   * Removes listener which is called by cache if corruption of file page is detected.
   */
  @SuppressWarnings("unused")
  void removePageIsBrokenListener(PageIsBrokenListener listener);

  long bookFileId(String fileName);

  /**
   * Registers new file in write cache and returns file id assigned to this file.
   *
   * <p>File id consist of two parts:
   *
   * <ol>
   *   <li>Internal id is permanent and can not be changed during life of storage {@link
   *       #internalFileId(long)}
   *   <li>Write cache id which is changed between storage open/close cycles
   * </ol>
   *
   * <p>If file with the same name is deleted and then new file is created this file with have the
   * same internal id.
   *
   * @param fileName Name of file to register inside storage.
   * @return Id of registered file
   */
  long loadFile(String fileName) throws IOException;

  long addFile(String fileName) throws IOException;

  long addFile(String fileName, long fileId) throws IOException;

  /**
   * Registers a new file in the write cache with an explicit non-durability flag.
   *
   * <p>Non-durable files participate in the normal page cache lifecycle (load, store, flush to
   * disk, evict) but opt out of WAL logging, double-write log protection, and fsync. They are
   * automatically deleted on crash recovery.
   *
   * @param fileName Name of file to register inside storage.
   * @param fileId Id to assign to the file.
   * @param nonDurable If true, the file is registered as non-durable.
   * @return Id of registered file
   */
  default long addFile(String fileName, long fileId, boolean nonDurable) throws IOException {
    return addFile(fileName, fileId);
  }

  /**
   * Returns whether the given file is registered as non-durable.
   *
   * <p>Non-durable files opt out of WAL logging, double-write log protection, and fsync.
   *
   * @param fileId External file id to check.
   * @return true if the file is non-durable, false otherwise.
   */
  default boolean isNonDurable(long fileId) {
    return false;
  }

  /**
   * Returns id associated with given file or value &lt; 0 if such file does not exist.
   *
   * @param fileName File name id of which has to be returned.
   * @return id associated with given file or value &lt; 0 if such file does not exist.
   */
  long fileIdByName(String fileName);

  boolean checkLowDiskSpace() throws IOException;

  void syncDataFiles(long segmentId) throws IOException;

  void flushTillSegment(long segmentId);

  boolean exists(String fileName);

  boolean exists(long fileId);

  void restoreModeOn() throws IOException;

  void restoreModeOff();

  void store(long fileId, long pageIndex, CachePointer dataPointer);

  void checkCacheOverflow() throws InterruptedException;

  int allocateNewPage(final long fileId) throws IOException;

  CachePointer load(
      long fileId, long startPageIndex, ModifiableBoolean cacheHit, boolean verifyChecksums)
      throws IOException;

  void flush(long fileId);

  void flush();

  long getFilledUpTo(long fileId);

  long getExclusiveWriteCachePagesSize();

  void deleteFile(long fileId) throws IOException;

  void truncateFile(long fileId) throws IOException;

  void renameFile(long fileId, String newFileName) throws IOException;

  long[] close() throws IOException;

  void close(long fileId, boolean flush);

  PageDataVerificationError[] checkStoredPages(CommandOutputListener commandOutputListener);

  long[] delete() throws IOException;

  String fileNameById(long fileId);

  /**
   * Obtains native file name by the given file id.
   *
   * <p>Native file name is a file name of a "physical" on-disk file, it may differ from the
   * "virtual" logical file name.
   *
   * @param fileId the file id to obtain the native file name of.
   * @return the obtained native file name or {@code null} if the passed file id doesn't correspond
   * to any file.
   */
  String nativeFileNameById(long fileId);

  int getId();

  Map<String, Long> files();

  /**
   * DO NOT DELETE THIS METHOD IT IS USED IN ENTERPRISE STORAGE
   *
   * @return Size of page inside of cache.
   */
  int pageSize();

  /**
   * Finds if there was file in write cache with given id which is deleted right now. If such file
   * exists it creates new file with the same name at it was in deleted file.
   *
   * @param fileId If of file which should be restored
   * @return Name of restored file or <code>null</code> if such name does not exist
   */
  String restoreFileById(long fileId) throws IOException;

  /**
   * Adds listener which is triggered if exception is cast inside background flush data thread.
   *
   * @param listener Listener to trigger
   */
  void addBackgroundExceptionListener(BackgroundExceptionListener listener);

  /**
   * Removes listener which is triggered if exception is cast inside background flush data thread.
   *
   * @param listener Listener to remove
   */
  void removeBackgroundExceptionListener(BackgroundExceptionListener listener);

  /**
   * Directory which contains all files managed by write cache.
   *
   * @return Directory which contains all files managed by write cache or <code>null</code> in case
   * of in memory database.
   */
  Path getRootDirectory();

  /**
   * Returns internal file id which is unique and always the same for given file in contrary to
   * external id which changes over close/open cycle of cache.
   *
   * @param fileId External file id.
   * @return Internal file id.
   */
  int internalFileId(long fileId);

  /**
   * Converts unique internal file id to external one. External id is combination of internal id and
   * write cache id, which changes every time when cache is closed and opened again.
   *
   * @param fileId Internal file id.
   * @return External file id.
   * @see #internalFileId(long)
   * @see #getId()
   */
  long externalFileId(int fileId);

  /**
   * DO NOT DELETE THIS METHOD IT IS USED IN ENTERPRISE STORAGE
   *
   * <p>Takes two ids and checks whether they are equal from point of view of write cache. In other
   * words methods checks whether two ids in reality contain the same internal ids.
   */
  boolean fileIdsAreEqual(long firsId, long secondId);

  Long getMinimalNotFlushedSegment();

  void updateDirtyPagesTable(CachePointer pointer, LogSequenceNumber startLSN);

  void create() throws IOException;

  void open() throws IOException;

  void replaceFileId(long fileId, long newFileId) throws IOException;

  /**
   * Deletes all non-durable files from both read and write caches during crash recovery.
   *
   * <p>Called before WAL replay in {@code AbstractStorage.recoverIfNeeded()}. The method reads
   * the persisted non-durable file IDs (already loaded at startup), deletes each file from
   * both caches, removes their name-id map entries, clears the in-memory registry, and deletes
   * the side files. Returns the set of deleted internal file IDs so WAL replay can skip records
   * referencing them.
   *
   * @param readCache The read cache from which to delete non-durable files.
   * @return Set of internal file IDs that were deleted, for use by WAL replay skip logic.
   *     Empty if no non-durable files existed.
   */
  default IntOpenHashSet deleteNonDurableFilesOnRecovery(ReadCache readCache) throws IOException {
    return new IntOpenHashSet();
  }

  String getStorageName();
}
