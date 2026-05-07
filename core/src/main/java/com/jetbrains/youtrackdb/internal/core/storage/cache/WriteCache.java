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

  /**
   * @deprecated Use {@link #loadOrAdd(long, long, boolean)} instead. This method is the legacy
   *     allocator that exposes the new {@code pageIndex} before the cache entry is installed,
   *     creating the race documented in the read-cache concurrency fix design. Final deletion
   *     is deferred until the write-side API collapse that migrates all replay-loop callers
   *     to {@link ReadCache#loadOrAddForWrite}.
   */
  @Deprecated
  int allocateNewPage(final long fileId) throws IOException;

  /**
   * @deprecated Use {@link #loadIfPresent(long, long, boolean)} for silent (non-extending) reads
   *     or {@link #loadOrAdd(long, long, boolean)} for allocating reads. This method carries the
   *     {@code cacheHit} out-parameter that the new primitives drop; it has no remaining
   *     production callers as of the read-cache concurrency fix and will be deleted alongside
   *     {@link #allocateNewPage} in the write-side API collapse.
   */
  @Deprecated
  CachePointer load(
      long fileId, long startPageIndex, ModifiableBoolean cacheHit, boolean verifyChecksums)
      throws IOException;

  /**
   * Non-extending probe: returns the existing on-disk (or dirty-write-cache) page for
   * {@code (fileId, pageIndex)} if one exists, otherwise {@code null}. Unlike {@link #load} this
   * method drops the {@code cacheHit} out-parameter (the silent read path does not track cache
   * hits) and unlike {@link #loadOrAdd} it never extends the file or stamps a fresh empty buffer
   * on miss &mdash; it is the read-only probe primitive used by
   * {@code LockFreeReadCache.silentLoadForRead} for diagnostic readers (e.g. backup,
   * restore-mode probes) that must observe a page only if it already exists. Implementations
   * must preserve the dirty-page-priority order documented on {@link #load}: a more recent
   * dirty pointer in the write cache shadows an older on-disk version.
   *
   * @param fileId external file id of the target page
   * @param pageIndex zero-based page index inside that file
   * @param verifyChecksums whether checksum verification is enforced on the load branch
   * @return a {@link CachePointer} positioned at the target page, or {@code null} if the page
   *     does not exist on disk and is not in the dirty-write map
   * @throws IOException if the underlying disk I/O fails
   */
  CachePointer loadIfPresent(long fileId, long pageIndex, boolean verifyChecksums)
      throws IOException;

  /**
   * Total page-access primitive: returns a usable {@link CachePointer} for the given
   * {@code (fileId, pageIndex)} regardless of whether the page already exists on disk.
   *
   * <p>The method dispatches to one of three branches depending on whether
   * {@code pageIndex} is less than, equal to, or greater than the current in-memory file
   * size (in pages):
   * <ul>
   *   <li><b>Load existing</b> ({@code pageIndex < currentSize}) &mdash; returns the
   *       existing on-disk (or dirty-write-cache) page. On the disk engine this acquires
   *       the per-{@link com.jetbrains.youtrackdb.internal.core.storage.cache.local.PageKey}
   *       shared {@code lockManager} lock (same policy as the legacy {@code load});
   *       the in-memory engine has no equivalent lock.</li>
   *   <li><b>One-page extend</b> ({@code pageIndex == currentSize}) &mdash; atomically
   *       extends the file by one page via {@code AsyncFile.allocateSpace}, submits an
   *       {@link com.jetbrains.youtrackdb.internal.core.storage.cache.local.EnsurePageIsValidInFileTask}
   *       on the single-threaded FIFO {@code wowCacheFlushExecutor}, and returns a
   *       freshly-allocated magic-stamped empty {@link CachePointer}.
   *       The {@code lockManager} shared lock is <b>not</b> taken on this branch: the
   *       freshly-installed pointer is published only after the caller's outer
   *       {@code data.compute} segment write lock releases it.</li>
   *   <li><b>Multi-page gap-fill</b> ({@code pageIndex > currentSize}; recovery only)
   *       &mdash; allocates space for the entire gap in one batched call, submits one
   *       {@code EnsurePageIsValidInFileTask} per gap page in {@code [currentSize,
   *       pageIndex]}, and returns only the target page's {@link CachePointer}. Gap-fill
   *       is the recovery-only branch; callers under normal operation always target
   *       {@code pagesSize + 1} (from the component's {@code entryPoint}), so
   *       {@code pageIndex > currentSize} can only occur during WAL replay.
   *       The {@code lockManager} shared lock is not taken here either, for the same
   *       reason as the extend branch.</li>
   * </ul>
   *
   * <p><b>Caller precondition.</b> For the disk engine: the segment write lock for the
   * {@code (fileId, pageIndex)} key must be held by the caller &mdash; that is, the call
   * must originate from inside a {@code LockFreeReadCache.data.compute} lambda. The
   * in-memory engine ({@code DirectMemoryOnlyDiskCache}) enforces its own install-or-fetch
   * atomicity via {@code MemoryFile.loadOrAddPage} without a segment lock.
   *
   * <p><b>Totality contract.</b> The method never returns {@code null} for any open,
   * non-deleted file. An {@link IllegalArgumentException} is thrown &mdash; not a null
   * return &mdash; if the file has been concurrently deleted or was never registered;
   * that is a caller-bug signal. Any {@link java.io.IOException} from the underlying disk
   * I/O propagates raw.
   *
   * <p><b>Runtime invariant.</b> Under normal (non-recovery) operation the caller computes
   * the target {@code pageIndex} from {@code entryPoint.pagesSize + 1}: the logical page
   * count on the component's {@code EntryPoint} metadata page, which is bumped only on
   * commit inside the same WAL atomic unit. This guarantees {@code pageIndex ==
   * currentSize} on the extend branch; the gap-fill branch is structurally unreachable
   * until the write-side API collapse rewires the WAL replay loops.
   *
   * <p><b>FIFO + monotonic submission.</b> {@code EnsurePageIsValidInFileTask} submissions
   * for a given {@code fileId} are monotonically increasing in {@code pageIndex} by
   * construction (the per-component lock serializes concurrent allocators). Combined with
   * the single-threaded FIFO executor, this guarantees that every gap page in
   * {@code [old_size, pageIndex]} is stamped in order; no interior zero page can survive
   * across a crash. See design.md §"Crash safety" for the three crash scenarios and
   * their walk-throughs.
   *
   * @param fileId external file id of the target page
   * @param pageIndex zero-based page index inside that file; must be &ge; 0
   * @param verifyChecksums whether checksum verification is enforced on the load branch
   * @return a non-null {@link CachePointer} positioned at the target page
   * @throws IllegalArgumentException if {@code pageIndex < 0} or the file is deleted /
   *     never registered
   * @throws IOException if the underlying disk I/O fails
   */
  CachePointer loadOrAdd(long fileId, long pageIndex, boolean verifyChecksums) throws IOException;

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

  /**
   * Pauses the background <i>periodic</i> page-flush task and drains any
   * in-flight page write started by it. After this returns: every async
   * page write started by the previous periodic-flush invocation has been
   * awaited, no scheduled periodic flush will fire until
   * {@link #resumeBackgroundFlush()} is called, and direct calls to
   * {@link #flush()} from any thread are still allowed (so the storage's
   * freeze / checkpoint paths keep working).
   *
   * <p>Note: only the periodic flush task is gated. Foreground flush paths
   * (explicit {@link #flush()}, {@link #flushTillSegment(long)},
   * {@code checkCacheOverflow}) and on-demand tasks (file creation,
   * page allocation) are <i>not</i> gated. Callers must therefore avoid
   * triggering writes from other threads while paused, otherwise pages
   * may still be written to disk during the pause window.
   *
   * <p>Intended for tests that need to read raw storage files without racing
   * with the periodic flusher (torn-page hazard). Not part of any
   * production-runtime contract. The default is a no-op for in-memory and
   * mock implementations that have no background flusher.
   */
  default void pauseBackgroundFlush() {
  }

  /**
   * Re-enables background flushing previously paused via
   * {@link #pauseBackgroundFlush()}. Calling without a prior pause is a no-op,
   * so it is safe to call unconditionally in a {@code finally} block on the
   * same thread that called pause. The default is a no-op for in-memory and
   * mock implementations.
   */
  default void resumeBackgroundFlush() {
  }

  String getStorageName();
}
