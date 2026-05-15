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

package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base;

import com.jetbrains.youtrackdb.internal.common.concur.resource.SharedResourceAbstract;
import com.jetbrains.youtrackdb.internal.common.directmemory.PageFrame;
import com.jetbrains.youtrackdb.internal.common.function.TxConsumer;
import com.jetbrains.youtrackdb.internal.common.function.TxFunction;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrackdb.internal.core.storage.cache.OptimisticReadFailedException;
import com.jetbrains.youtrackdb.internal.core.storage.cache.OptimisticReadScope;
import com.jetbrains.youtrackdb.internal.core.storage.cache.PageView;
import com.jetbrains.youtrackdb.internal.core.storage.cache.ReadCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.WriteCache;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationsManager;
import java.io.IOException;
import javax.annotation.Nonnull;

/**
 * Base class for all storage-backed data structures that participate in the page cache lifecycle.
 * Durable components have their state restored from WAL after a crash; non-durable components are
 * deleted on crash recovery and recreated on next open.
     *
     * <h2>Cross-transaction discovery contract</h2>
     *
     * <p>Storage components must answer "is this page already on disk?" without leaking the cache's
     * in-flight allocation state across transactions. The convention enforced from this class:
     *
     * <ul>
     *   <li><b>Logical surface (preferred).</b> A component that owns an {@code EntryPoint} metadata
     *       page reads cross-transaction page-existence facts from {@code entryPoint.pagesSize} /
     *       {@code entryPoint.fileSize}. These counters advance only inside the same WAL atomic unit
     *       that performed the corresponding allocator call, so they are durably ordered with the
     *       allocator's effect — no in-flight {@code pageIndex} can leak.</li>
     *   <li><b>Physical surface (gated).</b> Where no {@code EntryPoint} exists or the call is a
     *       bootstrap / recovery-rebuild / defensive-probe shape that pre-dates the logical surface,
     *       physical-size reads from inside a {@code StorageComponent} route through
     *       {@link #physicalSize(AtomicOperation, long, PhysicalReadIntent)}. The intent argument is
     *       unused at runtime; it exists as an audit-grep anchor so the surviving consumer set stays
     *       enumerable. Direct calls to {@code WriteCache.getFilledUpTo} from storage components are
     *       not on the public discovery path.</li>
     * </ul>
     *
     * <p>The backup-snapshot iterator on the cache layer has its own named entry point
     * ({@code WriteCache.physicalSizeForBackupSnapshot}); it is a parallel gated surface for the one
     * call site that reads physical size outside any {@code StorageComponent}.
     *
     * @since 8/27/13
     */
public abstract class StorageComponent extends SharedResourceAbstract {
  protected final AtomicOperationsManager atomicOperationsManager;
  protected final AbstractStorage storage;
  protected final ReadCache readCache;
  protected final WriteCache writeCache;

  private volatile String name;
  private volatile String fullName;

  private final String extension;

  private final String lockName;

  private final boolean durable;

  public StorageComponent(
      @Nonnull final AbstractStorage storage,
      @Nonnull final String name,
      final String extension,
      final String lockName,
      final boolean durable) {
    super();

    this.extension = extension;
    this.storage = storage;
    this.fullName = name + extension;
    this.name = name;
    this.atomicOperationsManager = storage.getAtomicOperationsManager();
    this.readCache = storage.getReadCache();
    this.writeCache = storage.getWriteCache();
    this.lockName = lockName;
    this.durable = durable;
  }

  /** Returns {@code true} if this component participates in WAL crash recovery. */
  public boolean isDurable() {
    return durable;
  }

  public String getLockName() {
    return lockName;
  }

  /**
   * Acquires the exclusive lock on this component for use by
   * {@link AtomicOperationsManager}. Delegates to the protected
   * {@link com.jetbrains.youtrackdb.internal.common.concur.resource.SharedResourceAbstract#acquireExclusiveLock()}.
   */
  public void lockExclusive() {
    acquireExclusiveLock();
  }

  /**
   * Releases the exclusive lock on this component. Delegates to the protected
   * {@link com.jetbrains.youtrackdb.internal.common.concur.resource.SharedResourceAbstract#releaseExclusiveLock()}.
   */
  public void unlockExclusive() {
    releaseExclusiveLock();
  }

  public String getName() {
    return name;
  }

  public void setName(final String name) {
    this.name = name;
    this.fullName = name + extension;
  }

  public String getFullName() {
    return fullName;
  }

  public String getExtension() {
    return extension;
  }

  protected <T> T calculateInsideComponentOperation(
      @Nonnull final AtomicOperation atomicOperation, final TxFunction<T> function) {
    return atomicOperationsManager.calculateInsideComponentOperation(
        atomicOperation, this, function);
  }

  protected void executeInsideComponentOperation(
      @Nonnull final AtomicOperation operation, final TxConsumer consumer) {
    atomicOperationsManager.executeInsideComponentOperation(operation, this, consumer);
  }

  protected long getFilledUpTo(@Nonnull final AtomicOperation atomicOperation, final long fileId) {
    assert atomicOperation != null;
    return atomicOperation.filledUpTo(fileId);
  }

  /**
   * Names the canonical call-site shape for each cross-transaction physical-size read that routes
   * through {@link #physicalSize(AtomicOperation, long, PhysicalReadIntent)}. The value is unused
   * at runtime — its only job is to anchor audit-grep for the question "where does this storage
   * component read physical size from, and why is the physical surface the right answer here?".
   *
   * <p>Each constant pins one of the five behavioural shapes enumerated by the surviving consumer
   * set. A new consumer should pick the closest matching shape; a shape that does not fit any
   * existing constant is a signal to revisit the cross-transaction discovery contract on the
   * enclosing class rather than introduce a sixth flavour without review.
   */
  public enum PhysicalReadIntent {
    /**
     * Bootstrap-time emptiness check on first-time component initialisation. Pattern:
     * {@code if physicalSize(...) == 0 then writeBootstrapPage() else readBootstrapPage()}. The
     * logical surface does not exist yet because the {@code EntryPoint} page is what bootstrap
     * is about to write.
     */
    BOOTSTRAP_EMPTINESS_CHECK,

    /**
     * Recovery-time linear scan over the committed pages of an {@code EntryPoint}-equipped
     * component to rebuild an in-memory derived structure (e.g., a free-space map after a crash).
     * Pattern: {@code for (pageIndex = 0; pageIndex < physicalSize(...); pageIndex++) load(pageIndex)}.
     * The logical surface is unavailable until the rebuild completes.
     */
    RECOVERY_REBUILD,

    /**
     * Defensive presence probe before reading a fixed metadata page (typically page 1) on a file
     * whose first physical page may or may not exist yet. Pattern:
     * {@code if physicalSize(...) > metadataPageIndex then load(metadataPageIndex)}. The
     * discriminator is on the physical surface because the metadata page IS the logical surface
     * the component would otherwise consult.
     */
    DEFENSIVE_PRESENCE,

    /**
     * Pure-physical sizing read on an {@code EntryPoint}-less component. The component has no
     * logical counter — physical size is the only size it tracks. Always read under the
     * per-component exclusive lock so concurrent allocator activity is serialised.
     */
    EP_LESS_PURE_SIZING,

    /**
     * Pre-read before a growth loop that walks
     * {@code for (i = physicalSize(...); i <= target; i++) allocate(i)}. The loop establishes
     * the new logical extent under the per-component lock; the pre-read fixes the starting point
     * against current physical state without racing concurrent allocators inside the same
     * component (lock-held precondition).
     */
    GROWTH_LOOP_PRE_READ
  }

  /**
   * Reads the physical (in-memory) file size, in pages, of the given file via the atomic
   * operation. Routes through {@link AtomicOperation#filledUpTo(long)} so the per-fileId
   * {@code FileChanges} placeholder side-effect on {@code AtomicOperationBinaryTracking} is
   * preserved on first touch — bypassing the atomic-operation layer would change in-transaction
   * semantics for callers that depend on the placeholder being registered before the next
   * {@code filledUpTo} call sees the existing-entry arm.
   *
   * <p>This helper is the gated entry point for storage-component callers whose call-site shape
   * matches one of the {@link PhysicalReadIntent} constants. The {@code intent} parameter is
   * unused at runtime; it anchors the audit-grep target so "who reads physical size from inside
   * a storage component, and which shape?" stays an enumerable question (the alternative — a
   * free-form helper — would force every reviewer to re-derive the shape from the call site).
   * Direct {@link AtomicOperation#filledUpTo(long)} access from a storage component is not on
   * the public discovery path; the existing {@link #getFilledUpTo(AtomicOperation, long)} helper
   * is retained for the documented internal callers and is not part of the gated surface.
   *
   * <p>Locking: this method does not acquire any lock of its own. Callers that need allocator
   * serialisation must hold the relevant per-component lock when invoking the helper.
   *
   * @param atomicOperation the enclosing atomic operation
   * @param fileId          external file id of the target file
   * @param intent          audit-grep anchor naming the canonical call-site shape
   * @return current physical file size in pages
   */
  protected long physicalSize(
      @Nonnull final AtomicOperation atomicOperation,
      final long fileId,
      @Nonnull final PhysicalReadIntent intent) {
    assert atomicOperation != null;
    assert intent != null;
    return atomicOperation.filledUpTo(fileId);
  }

  protected static CacheEntry loadPageForWrite(
      @Nonnull final AtomicOperation atomicOperation,
      final long fileId,
      final long pageIndex,
      final boolean verifyCheckSum)
      throws IOException {
    assert atomicOperation != null;
    return atomicOperation.loadPageForWrite(fileId, pageIndex, 1, verifyCheckSum);
  }

  protected CacheEntry allocatePageForWrite(
      @Nonnull final AtomicOperation atomicOperation, final long fileId, final long pageIndex)
      throws IOException {
    assert atomicOperation != null;
    // The caller states the target pageIndex up front. AtomicOperation's
    // allocatePageForWrite bottoms out on the cache-layer load-or-add primitive,
    // so we no longer fall back to the legacy pageIndex-by-allocateSpace addPage
    // path — the prior loadPageForWrite-then-addPage shape exposed an in-flight
    // pageIndex via the cache's allocator before the page was published, which is
    // the race vector this fix structurally removes.
    return atomicOperation.allocatePageForWrite(fileId, pageIndex);
  }

  protected CacheEntry loadPageForRead(
      @Nonnull final AtomicOperation atomicOperation, final long fileId, final long pageIndex)
      throws IOException {
    assert atomicOperation != null;
    return atomicOperation.loadPageForRead(fileId, pageIndex);
  }

  protected void releasePageFromWrite(
      @Nonnull final AtomicOperation atomicOperation, final CacheEntry cacheEntry)
      throws IOException {
    assert atomicOperation != null;
    atomicOperation.releasePageFromWrite(cacheEntry);
  }

  protected void releasePageFromRead(
      @Nonnull final AtomicOperation atomicOperation, final CacheEntry cacheEntry) {
    assert atomicOperation != null;
    atomicOperation.releasePageFromRead(cacheEntry);
  }

  protected long addFile(@Nonnull final AtomicOperation atomicOperation, final String fileName)
      throws IOException {
    assert atomicOperation != null;
    return atomicOperation.addFile(fileName, !durable);
  }

  protected long openFile(@Nonnull final AtomicOperation atomicOperation, final String fileName)
      throws IOException {
    assert atomicOperation != null;
    return atomicOperation.loadFile(fileName);
  }

  protected void deleteFile(@Nonnull final AtomicOperation atomicOperation, final long fileId)
      throws IOException {
    assert atomicOperation != null;
    atomicOperation.deleteFile(fileId);
  }

  protected boolean isFileExists(
      @Nonnull final AtomicOperation atomicOperation, final String fileName) {
    assert atomicOperation != null;
    return atomicOperation.isFileExists(fileName);
  }

  protected void truncateFile(@Nonnull final AtomicOperation atomicOperation, final long filedId)
      throws IOException {
    assert atomicOperation != null;
    atomicOperation.truncateFile(filedId);
  }

  // --- Optimistic read infrastructure ---

  /**
   * Functional interface for the optimistic read lambda. Must support IOException.
   */
  @FunctionalInterface
  protected interface OptimisticReadFunction<T> {
    T apply() throws IOException;
  }

  /**
   * Functional interface for the void variant of optimistic read lambda.
   */
  @FunctionalInterface
  protected interface OptimisticReadAction {
    void run() throws IOException;
  }

  /**
   * Functional interface for the pinned (fallback) read lambda.
   */
  @FunctionalInterface
  protected interface PinnedReadFunction<T> {
    T apply() throws IOException;
  }

  /**
   * Functional interface for the void variant of the pinned (fallback) read lambda.
   */
  @FunctionalInterface
  protected interface PinnedReadAction {
    void run() throws IOException;
  }

  /**
   * Performs an optimistic cache lookup: returns a {@link PageView} for the given page
   * without CAS-based pinning. The caller must read data from the PageView's buffer and
   * validate the stamp afterward (via {@link OptimisticReadScope#validateOrThrow()} or
   * {@link OptimisticReadScope#validateLastOrThrow()}).
   *
   * <p>Throws {@link OptimisticReadFailedException} on cache miss, stamp == 0 (exclusive
   * lock held), or coordinate mismatch (frame reused for a different page).
   *
   * @param atomicOperation the current atomic operation (provides the scope)
   * @param fileId          the file ID of the page
   * @param pageIndex       the page index within the file
   * @return a PageView with the speculative buffer, frame, and stamp
   * @throws OptimisticReadFailedException if the page cannot be read optimistically
   */
  protected PageView loadPageOptimistic(
      @Nonnull final AtomicOperation atomicOperation,
      final long fileId,
      final long pageIndex) {
    assert atomicOperation != null;
    assert pageIndex >= 0 && pageIndex <= Integer.MAX_VALUE
        : "pageIndex out of int range: " + pageIndex;

    // If the current transaction has uncommitted WAL changes for this page,
    // the optimistic path would return stale committed data. Force fallback.
    if (atomicOperation.hasChangesForPage(fileId, pageIndex)) {
      throw OptimisticReadFailedException.INSTANCE;
    }

    final PageFrame frame = readCache.getPageFrameOptimistic(fileId, pageIndex);
    if (frame == null) {
      throw OptimisticReadFailedException.INSTANCE;
    }

    final long stamp = frame.tryOptimisticRead();
    if (stamp == 0) {
      // Exclusive lock is held — cannot read optimistically
      throw OptimisticReadFailedException.INSTANCE;
    }

    // Verify coordinates match to detect frame reuse (frame may have been
    // recycled for a different page between the CHM lookup and stamp acquisition)
    if (frame.getFileId() != fileId || frame.getPageIndex() != (int) pageIndex) {
      throw OptimisticReadFailedException.INSTANCE;
    }

    final OptimisticReadScope scope = atomicOperation.getOptimisticReadScope();
    scope.record(frame, stamp);

    return new PageView(frame.getBuffer(), frame, stamp);
  }

  /**
   * Executes a read operation using the optimistic (no-CAS) path, falling back to the
   * CAS-pinned path if validation fails.
   *
   * <p>The optimistic lambda reads data without pinning pages. If any page is evicted or
   * modified during the read, the scope validation fails and the pinned lambda runs
   * instead (under the component's shared lock).
   *
   * <p>Reentrancy note: acquireSharedLock() is safe even if the current thread already
   * holds the exclusive lock on this component — SharedResourceAbstract's lock tracking
   * handles this by skipping the read lock acquisition when the thread owns the write lock.
   *
   * @param atomicOperation the current atomic operation
   * @param optimistic      lambda that reads data via loadPageOptimistic()
   * @param pinned          lambda that reads data via the existing loadPageForRead() path
   * @return the result from whichever path succeeds
   */
  protected <T> T executeOptimisticStorageRead(
      @Nonnull final AtomicOperation atomicOperation,
      final OptimisticReadFunction<T> optimistic,
      final PinnedReadFunction<T> pinned) throws IOException {
    assert atomicOperation != null;

    final OptimisticReadScope scope = atomicOperation.getOptimisticReadScope();
    scope.reset();

    try {
      final T result = optimistic.apply();
      scope.validateOrThrow();

      // Record successful access for all pages in the scope, so the eviction
      // policy's frequency sketch stays accurate.
      recordOptimisticAccesses(scope);

      return result;
    } catch (final RuntimeException | AssertionError e) {
      // Catch RuntimeException and AssertionError because speculative reads from
      // stale/reused PageFrames can produce arbitrary exceptions (AIOOBE, NPE, etc.)
      // and assertion failures (e.g., position-consistency checks seeing stale data)
      // before stamp validation has a chance to detect the inconsistency.
      // All such errors are safe to swallow here — the pinned fallback path will
      // produce the correct result.
      acquireSharedLock();
      try {
        return pinned.apply();
      } finally {
        releaseSharedLock();
      }
    }
  }

  /**
   * Void variant of {@link #executeOptimisticStorageRead(AtomicOperation,
   * OptimisticReadFunction, PinnedReadFunction)}.
   */
  protected void executeOptimisticStorageRead(
      @Nonnull final AtomicOperation atomicOperation,
      final OptimisticReadAction optimistic,
      final PinnedReadAction pinned) throws IOException {
    assert atomicOperation != null;

    final OptimisticReadScope scope = atomicOperation.getOptimisticReadScope();
    scope.reset();

    try {
      optimistic.run();
      scope.validateOrThrow();

      recordOptimisticAccesses(scope);
    } catch (final RuntimeException | AssertionError e) {
      // See comment in the T-returning overload above.
      acquireSharedLock();
      try {
        pinned.run();
      } finally {
        releaseSharedLock();
      }
    }
  }

  /**
   * Records successful optimistic accesses for all pages tracked in the scope.
   * Must be called after scope validation succeeds, before scope.reset().
   *
   * <p>Note: there is a benign TOCTOU between validateOrThrow() and reading frame
   * coordinates here — an evictor could reassign the frame between validation and
   * the coordinate reads. This would cause a frequency bump for the wrong page,
   * which is harmless: it only slightly skews eviction heuristics.
   */
  private void recordOptimisticAccesses(final OptimisticReadScope scope) {
    for (int i = 0; i < scope.count(); i++) {
      final PageFrame frame = scope.getFrame(i);
      readCache.recordOptimisticAccess(frame.getFileId(), frame.getPageIndex());
    }
  }
}
