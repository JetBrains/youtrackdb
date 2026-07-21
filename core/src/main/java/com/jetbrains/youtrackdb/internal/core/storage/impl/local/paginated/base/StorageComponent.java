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

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.common.concur.resource.SharedResourceAbstract;
import com.jetbrains.youtrackdb.internal.common.directmemory.PageFrame;
import com.jetbrains.youtrackdb.internal.common.function.TxConsumer;
import com.jetbrains.youtrackdb.internal.common.function.TxFunction;
import com.jetbrains.youtrackdb.internal.common.log.LogManager;
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
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

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
   * the public discovery path.
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

  // --- Recovery-time orphan-truncation template method ---

  /**
   * Returns the disk-cache file id of this component's primary data file. The four
   * entry-point-equipped components (BTree, SharedLinkBagBTree, CollectionPositionMapV2,
   * PaginatedCollectionV2) override this with their stored {@code fileId} field; EP-less
   * components (FreeSpaceMap, CollectionDirtyPageBitSet, IndexHistogramManager) inherit the
   * default-throw because the recovery-time orphan-truncation pass never iterates over them.
   */
  protected long getFileId() {
    throw new UnsupportedOperationException(
        "getFileId() not implemented for " + getClass().getName());
  }

  /**
   * Recovery-time orphan-truncation pass invoked by
   * {@code AbstractStorage.truncateOrphansAfterRecovery()} after WAL replay has settled
   * logical state. Reads the entry-point's logical-page counter via the
   * {@link #readLogicalPageCountFromEntryPoint} hook, computes the expected physical file size
   * as {@code max(pageSize, (logicalPages + 1) * pageSize)}, and dispatches to
   * {@link ReadCache#shrinkFile(long, long, WriteCache)} to drop any physical pages beyond the
   * logical horizon (the partial-flush "orphan" pages that survive a crash between an
   * allocator-only physical extend and the corresponding logical-counter advance).
   *
   * <p>The {@code max(pageSize, ...)} floor preserves the EP page itself on the fresh-component
   * branch ({@code logicalPages == 0}): a healthy CPMV2 or PCV2 computes a target of
   * {@code 1 * pageSize}, which keeps the EP / state page and is a no-op against current
   * physical state. For BTree / SLBB whose {@code init()} sets the counter to {@code 1}, the
   * floor only matters defensively against the corruption shape ruled out by the guard below.
   *
   * <p>Corruption guard: if {@code logicalPages == 0} AND physical bytes exceed one EP page,
   * the pass skips the truncate with a WARN log rather than mask the inconsistency. The
   * combination covers two distinct shapes:
   *
   * <ul>
   *   <li>(a) a true {@code logical < physical} corruption shape that WAL replay is supposed
   *       to prevent — operators handle this through the storage-corruption runbook;</li>
   *   <li>(b) a fresh-create + partial-flush-orphan case: a brand-new component whose
   *       {@code create()} bumped physical via an allocation that reached AsyncFile but
   *       crashed before the commit landed on disk, so the EP counter is still its initial
   *       0 while physical now holds the partial-flushed orphan tail. The escape valve is
   *       the allocator-only {@code op.loadOrAddPageForWrite} contract — the next allocation
   *       observes the physical orphan via the {@code op.filledUpTo > knownIndex} check and
   *       throws {@code IllegalStateException} loudly, surfacing the issue at first write
   *       rather than silently absorbing it.</li>
   * </ul>
   *
   * <p>Both shapes are handled identically here: skip-with-WARN, leaving the truncate for
   * operator review. Asymmetry note: BTree / SLBB EPs {@code init()} their counter to
   * {@code 1}, so {@code logicalPages == 0} is itself anomalous; CPMV2 / PCV2 EPs
   * {@code init()} their counter to {@code 0}, which is the legitimate post-create state — the
   * {@code physicalBytes > pageSize} second clause is what discriminates a healthy fresh
   * CPMV2 / PCV2 (physical == {@code pageSize}, one EP page only) from a real
   * {@code logical < physical} shape.
   *
   * <p>EP-read failures (e.g., a corrupted EP page surfaced by
   * {@code checksumMode=StoreAndThrow}) propagate the {@link IOException} through this
   * method and abort the enclosing {@code open()} / incremental-restore flow. This is
   * intentional: without the EP we cannot compute the truncate target, and silently skipping
   * the component would re-introduce the partial-flush-orphan path the pass exists to fix.
   *
   * <p>After the primary truncate dispatches, {@link #verifyAndTruncateOrphansSiblings} runs
   * so components with embedded sibling files (PCV2's embedded CPM is the only current case)
   * can delegate their sibling's truncate without exposing the embedded reference to the
   * orchestrator.
   *
   * @param atomicOperation enclosing recovery-pass atomic operation
   * @param readCache       read cache the truncate dispatches through
   * @param writeCache      write cache backing the read cache
   * @throws IOException if the entry-point page cannot be read or the underlying shrink fails
   */
  public final void verifyAndTruncateOrphans(
      @Nonnull final AtomicOperation atomicOperation,
      @Nonnull final ReadCache readCache,
      @Nonnull final WriteCache writeCache)
      throws IOException {
    final long fileId = getFileId();
    final int logicalPages = readLogicalPageCountFromEntryPoint(atomicOperation);
    final int pageSize = writeCache.pageSize();
    // Physical size in pages, read via the gated StorageComponent.physicalSize helper
    // (RECOVERY_REBUILD intent — the recovery pass is a one-shot scan over committed
    // pages to decide whether to drop a physical-orphan tail).
    final long physicalPages =
        physicalSize(atomicOperation, fileId, PhysicalReadIntent.RECOVERY_REBUILD);
    final long physicalBytes = physicalPages * (long) pageSize;

    if (logicalPages == 0 && physicalBytes > pageSize) {
      // Format eagerly so the message does not collide with LogManager.warn's three-arg
      // dbName overload (which would consume the first String arg as a dbName prefix).
      final String msg =
          String.format(
              "Storage corruption signal: %s '%s' EP reports %s=0 but physical file holds"
                  + " %d pages (> 1). Skipping orphan-truncation; investigate manually.",
              getComponentTypeName(), getName(), getLogicalCountFieldName(), physicalPages);
      LogManager.instance().warn(this, msg);
      return;
    }

    final long targetBytes = Math.max((long) pageSize, ((long) logicalPages + 1L) * pageSize);
    readCache.shrinkFile(fileId, targetBytes, writeCache);

    // Hook for components with embedded sibling files (PCV2's embedded CPM is the only
    // current case). Default no-op; overridden where needed.
    verifyAndTruncateOrphansSiblings(atomicOperation, readCache, writeCache);
  }

  /**
   * Reads the logical-page counter from this component's entry-point page. Each EP-equipped
   * subclass loads its EP via {@link #loadPageForRead}, wraps the cache entry in the
   * appropriate EP wrapper class, returns the counter, and releases the page. The counter
   * binding is {@code pagesSize} for BTree / SharedLinkBagBTree and {@code fileSize} for
   * CollectionPositionMapV2 / PaginatedCollectionV2.
   *
   * <p>Default-throw because EP-less components ({@code FreeSpaceMap},
   * {@code CollectionDirtyPageBitSet}, {@code IndexHistogramManager}) cannot satisfy this
   * contract — they have no logical-page counter on disk. The orchestrator never invokes
   * {@code verifyAndTruncateOrphans} on EP-less components, so the default is unreachable
   * in production.
   *
   * @param atomicOperation the enclosing recovery-pass atomic operation
   * @return the logical-page counter stored in the entry-point
   * @throws IOException if the entry-point page cannot be read
   */
  protected int readLogicalPageCountFromEntryPoint(
      @Nonnull final AtomicOperation atomicOperation) throws IOException {
    throw new UnsupportedOperationException(
        "readLogicalPageCountFromEntryPoint not implemented for " + getClass().getName());
  }

  /**
   * Returns the human-readable component-type name used in the
   * {@link #verifyAndTruncateOrphans} corruption-guard WARN log
   * (e.g., {@code "BTree"}, {@code "CollectionPositionMapV2"}).
   */
  protected String getComponentTypeName() {
    throw new UnsupportedOperationException(
        "getComponentTypeName not implemented for " + getClass().getName());
  }

  /**
   * Returns the EP field name surfaced in the {@link #verifyAndTruncateOrphans}
   * corruption-guard WARN log so operators see which counter was read
   * (e.g., {@code "pagesSize"} for BTree / SLBB, {@code "fileSize"} for CPMV2 / PCV2).
   */
  protected String getLogicalCountFieldName() {
    throw new UnsupportedOperationException(
        "getLogicalCountFieldName not implemented for " + getClass().getName());
  }

  /**
   * Sibling-file truncation hook invoked at the tail of {@link #verifyAndTruncateOrphans}
   * for components that embed a second EP-equipped file (PCV2's embedded
   * {@code CollectionPositionMapV2} is the only current case). Default: no-op.
   *
   * @param atomicOperation the enclosing recovery-pass atomic operation
   * @param readCache       read cache the sibling truncate dispatches through
   * @param writeCache      write cache backing the read cache
   * @throws IOException if the sibling's EP read or shrink fails
   */
  protected void verifyAndTruncateOrphansSiblings(
      @Nonnull final AtomicOperation atomicOperation,
      @Nonnull final ReadCache readCache,
      @Nonnull final WriteCache writeCache)
      throws IOException {
    // default: no embedded siblings.
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
    // recycled for a different page between the CHM lookup and stamp acquisition).
    // Coordinates are only ever written inside a frame write-lock cycle (the
    // CachePointer constructor / PageFramePool.release), so if this check passes AND
    // the stamp later validates, the frame's buffer content for (fileId, pageIndex)
    // was fully published before the stamp was taken — a reader can never validate
    // a half-filled reload of a recycled frame.
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
    // -ea-only guard against nested optimistic read attempts: a nested
    // executeOptimisticStorageRead call from inside an optimistic lambda would wipe the
    // outer scope's stamps via reset(), silently voiding the outer validation. Kept
    // OUTSIDE the try below — the fallback catch swallows AssertionError, so an assert
    // placed inside the try could never surface. See OptimisticReadScope#enterAttempt.
    assert scope.enterAttempt() : "Nested optimistic read attempt on " + getLockName();

    // Tracks whether the fallback catch below already closed the attempt. The attempt
    // must be closed exactly once on EVERY exit path — including throwables the catch
    // does not handle (a checked IOException from the optimistic lambda, or a VM error)
    // — otherwise attemptActive stays latched and every subsequent optimistic read on
    // this AtomicOperation fails with a spurious "Nested optimistic read attempt"
    // AssertionError under -ea.
    var attemptClosedByFallback = false;
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
      // produce the correct result. Checked exceptions and other Errors are NOT
      // caught — they propagate to the caller (and the finally below still closes
      // the attempt).

      // Close the attempt before the pinned fallback runs: the fallback may itself
      // legitimately start fresh optimistic reads (e.g., via helper methods), which
      // must not trip a stale nesting flag. If a nested reset was detected during the
      // failed attempt, this assert surfaces it (the nested AssertionError itself was
      // swallowed by this catch).
      attemptClosedByFallback = true;
      assert scope.exitAttempt() : "Nested optimistic read detected on " + getLockName();

      acquireSharedLock();
      try {
        return pinned.apply();
      } finally {
        releaseSharedLock();
      }
    } finally {
      // Close the attempt on all remaining exit paths: normal return, and any
      // throwable the catch above does not handle. Skipped when the fallback already
      // closed it (exitAttempt is not idempotent — a second call reports ill-formed).
      if (!attemptClosedByFallback) {
        assert scope.exitAttempt() : "Nested optimistic read detected on " + getLockName();
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
    // See the nesting-guard comments in the T-returning overload above.
    assert scope.enterAttempt() : "Nested optimistic read attempt on " + getLockName();

    var attemptClosedByFallback = false;
    try {
      optimistic.run();
      scope.validateOrThrow();

      recordOptimisticAccesses(scope);
    } catch (final RuntimeException | AssertionError e) {
      // See comment in the T-returning overload above.
      attemptClosedByFallback = true;
      assert scope.exitAttempt() : "Nested optimistic read detected on " + getLockName();

      acquireSharedLock();
      try {
        pinned.run();
      } finally {
        releaseSharedLock();
      }
    } finally {
      if (!attemptClosedByFallback) {
        assert scope.exitAttempt() : "Nested optimistic read detected on " + getLockName();
      }
    }
  }

  // Rate limiter state for the null-recheck disagreement ERROR: nanoTime of the last
  // emitted message, 0 = never emitted. Per component instance, CAS-updated so
  // concurrent readers emit at most ~one message per interval.
  private final AtomicLong lastNullRecheckErrorNanos = new AtomicLong();

  /**
   * Variant of {@link #executeOptimisticStorageRead(AtomicOperation, OptimisticReadFunction,
   * PinnedReadFunction)} for point lookups whose NULL result is load-bearing — the
   * belt-and-braces hardening for the apply-phase epoch (YTDB-1178). The historical failure
   * mode this guards against is a <em>false null</em>: an optimistic B-tree descent composing
   * a per-page-valid but mutually inconsistent view and concluding "absent" for a present
   * key. The epoch closes that race; this re-check is an independent second line of defense
   * plus a live detector for any unmodeled anomaly.
   *
   * <p>Decision rules:
   *
   * <ul>
   *   <li>The re-check triggers ONLY when the optimistic attempt completed cleanly (per-page
   *       stamps AND epoch both validated) and {@code recheckTrigger} accepts the result
   *       (typically: the lookup came back null). It NEVER triggers after the pinned fallback
   *       ran — the fallback's result is already authoritative, and re-checking it would make
   *       every legitimate miss pay the pinned cost twice more.
   *   <li>On trigger, the pinned lambda runs once under the component shared lock. A non-null
   *       pinned result that differs from the optimistic one is a disagreement: a rate-limited
   *       ERROR is emitted and the pinned result is returned. If the pinned result is null too,
   *       the null is confirmed and returned silently.
   *   <li>The trigger fires only on the load-bearing NULL by design: stale validated
   *       NON-null results are guaranteed consistent by the apply-phase epoch, and
   *       re-verifying them would double the pinned cost of every successful lookup.
   * </ul>
   *
   * @param recheckTrigger    evaluated on the validated optimistic result; return true to
   *                          request the pinned re-check (e.g. {@code Objects::isNull}, or a
   *                          flag captured from an inner lookup when the composed result can
   *                          mask the load-bearing null)
   * @param lookupDescription lazily built key coordinates for the disagreement report
   */
  @Nullable protected <T> T executeOptimisticStorageReadWithNullRecheck(
      @Nonnull final AtomicOperation atomicOperation,
      final OptimisticReadFunction<T> optimistic,
      final PinnedReadFunction<T> pinned,
      final Predicate<T> recheckTrigger,
      final Supplier<String> lookupDescription) throws IOException {
    assert atomicOperation != null;

    final OptimisticReadScope scope = atomicOperation.getOptimisticReadScope();
    scope.reset();
    // See the nesting-guard comments in executeOptimisticStorageRead.
    assert scope.enterAttempt() : "Nested optimistic read attempt on " + getLockName();

    final T result;
    var attemptClosedByFallback = false;
    try {
      result = optimistic.apply();
      scope.validateOrThrow();

      recordOptimisticAccesses(scope);
    } catch (final RuntimeException | AssertionError e) {
      // Same fallback semantics as executeOptimisticStorageRead — and deliberately NO
      // re-check on this path: the pinned result below is already authoritative.
      attemptClosedByFallback = true;
      assert scope.exitAttempt() : "Nested optimistic read detected on " + getLockName();

      acquireSharedLock();
      try {
        return pinned.apply();
      } finally {
        releaseSharedLock();
      }
    } finally {
      if (!attemptClosedByFallback) {
        assert scope.exitAttempt() : "Nested optimistic read detected on " + getLockName();
      }
    }

    // Reaching here means the optimistic attempt completed cleanly (the catch above either
    // returned or threw). The trigger and the re-check run OUTSIDE the try/catch so that a
    // pinned-path failure cannot be misrouted into a second fallback.
    if (!recheckTrigger.test(result)) {
      return result;
    }

    final T pinnedResult;
    acquireSharedLock();
    try {
      pinnedResult = pinned.apply();
    } finally {
      releaseSharedLock();
    }

    if (pinnedResult != null && !Objects.equals(pinnedResult, result)) {
      errorOptimisticNullRecheckDisagreement(lookupDescription);
      return pinnedResult;
    }
    // Agreement (typically both null): confirm silently. The reverse-direction
    // disagreement — optimistic non-null, pinned null — is DELIBERATELY silent: that is
    // the snapshot-hit shape (the composed optimistic value came from the in-memory
    // snapshot index while the tree had nothing). The pinned run saw the same or newer
    // state, and the optimistic value is the SI-correct answer for this operation's
    // snapshot, so it is preferred and no anomaly is reported.
    return pinnedResult != null ? pinnedResult : result;
  }

  /**
   * Emits the rate-limited disagreement ERROR for the validated-null re-check. Protected so
   * unit tests can override it to capture emissions; the rate limiter itself lives in
   * {@link #tryAcquireNullRecheckErrorSlot(long)} and is tested directly.
   *
   * <p>ERROR (not WARN) because for the production wiring the miss cannot be explained by
   * benign timing: visibility is resolved against a fixed snapshot captured at operation
   * construction, so a concurrently committed insert would be filtered out anyway — a
   * disagreement here indicates a genuine optimistic-read anomaly.
   */
  protected void errorOptimisticNullRecheckDisagreement(
      final Supplier<String> lookupDescription) {
    if (!tryAcquireNullRecheckErrorSlot(System.nanoTime())) {
      return;
    }
    LogManager.instance()
        .error(
            this,
            "Pinned re-check in component '%s' found an entry that the validated optimistic"
                + " lookup missed (%s). The lookup self-healed — the pinned result was"
                + " returned — but this indicates a possible optimistic-read/epoch anomaly:"
                + " visibility is resolved against a fixed operation snapshot, so a"
                + " concurrently committed insert cannot explain the miss. Please report this"
                + " occurrence. At most one such message is logged per configured interval per"
                + " component (youtrackdb.storage.optimisticRead.nullRecheckReportIntervalSecs,"
                + " default 60s).",
            // The explicit null Throwable pins the single
            // error(requester, message, exception, Object...) overload — unlike warn, error
            // has no (requester, dbName, message, ...) sibling that two String arguments
            // could silently select.
            null,
            getName(),
            lookupDescription.get());
  }

  /**
   * Rate limiter for the disagreement ERROR: returns true if the caller won the right to
   * log at {@code nowNanos} (at most one win per configured interval, see
   * {@link GlobalConfiguration#STORAGE_OPTIMISTIC_READ_NULL_RECHECK_REPORT_INTERVAL_SECS}).
   * CAS-based so concurrent winners are unique. Package-private for direct unit testing
   * with controlled timestamps.
   */
  boolean tryAcquireNullRecheckErrorSlot(final long nowNanos) {
    final long intervalNanos = nullRecheckErrorIntervalNanos();
    while (true) {
      final long last = lastNullRecheckErrorNanos.get();
      // 0 is the "never logged" sentinel; a genuine nanoTime of exactly 0 would merely
      // allow one extra message, which is harmless.
      if (last != 0 && nowNanos - last < intervalNanos) {
        return false;
      }
      if (lastNullRecheckErrorNanos.compareAndSet(last, nowNanos)) {
        return true;
      }
    }
  }

  /**
   * Resolves the null-recheck report rate-limit interval in nanoseconds. Components read
   * configuration through the storage's per-database {@code ContextConfiguration} — the
   * dominant idiom at this layer (cf. {@code StaleTransactionMonitor}) — falling back to
   * the {@link GlobalConfiguration} default when no context configuration is available
   * (unit-test component doubles built around mock storages; production components exist
   * only for open storages, which always carry one). Resolved lazily on each limiter
   * check: the check runs only on the rare disagreement path, so the map lookup is
   * negligible and component construction stays free of configuration-lifecycle ordering
   * concerns.
   */
  private long nullRecheckErrorIntervalNanos() {
    final var contextConfiguration = storage.getContextConfiguration();
    final int intervalSecs =
        contextConfiguration != null
            ? contextConfiguration.getValueAsInteger(
                GlobalConfiguration.STORAGE_OPTIMISTIC_READ_NULL_RECHECK_REPORT_INTERVAL_SECS)
            : GlobalConfiguration.STORAGE_OPTIMISTIC_READ_NULL_RECHECK_REPORT_INTERVAL_SECS
                .getValueAsInteger();
    return TimeUnit.SECONDS.toNanos(intervalSecs);
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
