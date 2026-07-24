package com.jetbrains.youtrackdb.internal.core.storage.cache.chm;

import com.jetbrains.youtrackdb.internal.common.directmemory.ByteBufferPool;
import com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator;
import com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator.Intention;
import com.jetbrains.youtrackdb.internal.common.types.ModifiableBoolean;
import com.jetbrains.youtrackdb.internal.core.command.CommandOutputListener;
import com.jetbrains.youtrackdb.internal.core.exception.StorageException;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CachePointer;
import com.jetbrains.youtrackdb.internal.core.storage.cache.PageDataVerificationError;
import com.jetbrains.youtrackdb.internal.core.storage.cache.WriteCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.local.BackgroundExceptionListener;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.PageIsBrokenListener;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for file-lifecycle methods of {@link LockFreeReadCache}:
 * {@code truncateFile()}, {@code closeFile()}, {@code deleteFile()},
 * {@code changeMaximumAmountOfMemory()}, and {@code silentLoadForRead()}.
 *
 * <p>These methods delegate to {@code clearFile()} internally and route to the WriteCache
 * for the final file-system operation. The tests verify:
 * <ul>
 *   <li>Cache entries for the target file are evicted from the read cache.</li>
 *   <li>Entries for other files remain untouched.</li>
 *   <li>The WriteCache receives the correct file-system call.</li>
 *   <li>{@code changeMaximumAmountOfMemory()} updates the eviction policy's max-size.</li>
 *   <li>{@code silentLoadForRead()} returns an entry on miss (loads from WriteCache) and
 *       null when the WriteCache returns null for the page.</li>
 * </ul>
 */
public class LockFreeReadCacheFileOpsTest {

  private static final int PAGE_SIZE = 4 * 1024;

  private DirectMemoryAllocator allocator;
  private ByteBufferPool bufferPool;
  private LockFreeReadCache readCache;
  private TrackingWriteCache writeCache;

  @Before
  public void setUp() {
    allocator = new DirectMemoryAllocator();
    bufferPool = new ByteBufferPool(PAGE_SIZE, allocator, 256);
    readCache = new LockFreeReadCache(1024L * PAGE_SIZE, PAGE_SIZE);
    writeCache = new TrackingWriteCache(bufferPool);
  }

  @After
  public void tearDown() {
    try {
      readCache.clear();
    } catch (StorageException ignored) {
      // Tolerate pinned-entry failures in tearDown to keep tests self-isolating.
    }
  }

  // ---- changeMaximumAmountOfMemory ----

  /**
   * {@code changeMaximumAmountOfMemory(newMax)} must update the eviction policy's maximum size
   * so subsequent loads are evicted once the new threshold is crossed.
   *
   * <p>{@link WTinyLFUPolicy#setMaxSize(int)} throws {@link IllegalStateException} when the
   * requested limit is smaller than the number of entries already present. Therefore the test
   * reduces the limit only after ensuring the current entry count does not exceed the new limit:
   * load 2 pages, reduce the maximum to 2 pages (2 ≤ 2 — allowed), then load 20 additional
   * distinct pages to drive eviction and confirm that used memory stays at or below the new limit.
   */
  @Test
  public void testChangeMaximumAmountOfMemoryReducesLimit() {
    // Pre-load 2 pages — exactly at the new limit, so setMaxSize(2) is permitted.
    for (int i = 0; i < 2; i++) {
      final var entry = readCache.loadForRead(0, i, writeCache, false);
      readCache.releaseFromRead(entry);
    }
    Assert.assertEquals("sanity: 2 pages must be in cache before resize",
        2L * PAGE_SIZE, readCache.getUsedMemory());

    // Reduce the maximum to 2 pages (current count == new limit — just within the allowed range).
    readCache.changeMaximumAmountOfMemory(2L * PAGE_SIZE);

    // Load 20 more distinct pages to drive eviction well past the new limit.
    for (int i = 2; i < 22; i++) {
      final var entry = readCache.loadForRead(0, i, writeCache, false);
      readCache.releaseFromRead(entry);
    }

    // The used memory must not exceed 2 pages now that the policy limit has been applied.
    Assert.assertTrue(
        "After changeMaximumAmountOfMemory(2 pages) + 20 additional loads, "
            + "used memory must be ≤ 2 pages; actual=" + readCache.getUsedMemory(),
        readCache.getUsedMemory() <= 2L * PAGE_SIZE);

    readCache.assertSize();
    readCache.assertConsistency();
  }

  /**
   * {@code changeMaximumAmountOfMemory()} with a value larger than the current cache size must
   * be accepted without error and must not evict any existing entries.
   */
  @Test
  public void testChangeMaximumAmountOfMemoryIncreaseIsAccepted() {
    // Load 10 pages.
    for (int i = 0; i < 10; i++) {
      final var entry = readCache.loadForRead(0, i, writeCache, false);
      readCache.releaseFromRead(entry);
    }
    final long usedBefore = readCache.getUsedMemory();
    Assert.assertEquals("sanity: 10 pages loaded", 10L * PAGE_SIZE, usedBefore);

    // Increase the limit — must not throw and must not evict.
    readCache.changeMaximumAmountOfMemory(2048L * PAGE_SIZE);
    Assert.assertEquals(
        "changeMaximumAmountOfMemory(larger) must not evict existing entries",
        usedBefore, readCache.getUsedMemory());

    readCache.assertSize();
    readCache.assertConsistency();
  }

  // ---- truncateFile ----

  /**
   * {@code truncateFile(fileId, writeCache)} must:
   * <ol>
   *   <li>Remove all cached pages for {@code fileId} from the read cache.</li>
   *   <li>Leave cached pages for other file IDs untouched.</li>
   *   <li>Invoke {@code writeCache.truncateFile(fileId)} exactly once.</li>
   * </ol>
   */
  @Test
  public void testTruncateFileEvictsCacheEntriesForTargetFile() throws IOException {
    // Load 3 pages for file 0 and 2 pages for file 1.
    for (int i = 0; i < 3; i++) {
      readCache.releaseFromRead(readCache.loadForRead(0, i, writeCache, false));
    }
    for (int i = 0; i < 2; i++) {
      readCache.releaseFromRead(readCache.loadForRead(1, i, writeCache, false));
    }
    Assert.assertEquals("sanity: 5 pages in cache", 5L * PAGE_SIZE, readCache.getUsedMemory());
    Assert.assertEquals("sanity: truncateFile not called yet", 0, writeCache.truncateCount.get());

    // Truncate file 0.
    readCache.truncateFile(0, writeCache);

    // File 0's 3 pages must be evicted; file 1's 2 pages must remain.
    Assert.assertEquals(
        "After truncateFile(0), only file 1's 2 pages must remain in cache",
        2L * PAGE_SIZE, readCache.getUsedMemory());
    Assert.assertEquals(
        "truncateFile must invoke writeCache.truncateFile exactly once",
        1, writeCache.truncateCount.get());

    readCache.assertSize();
    readCache.assertConsistency();
  }

  // ---- closeFile ----

  /**
   * {@code closeFile(fileId, flush, writeCache)} must:
   * <ol>
   *   <li>Remove all cached pages for {@code fileId}.</li>
   *   <li>Invoke {@code writeCache.close(fileId, flush)} exactly once.</li>
   *   <li>Leave other files' cached pages untouched.</li>
   * </ol>
   */
  @Test
  public void testCloseFileEvictsCacheEntriesAndDelegatesClose() {
    // Load 2 pages for file 10 and 3 pages for file 11.
    for (int i = 0; i < 2; i++) {
      readCache.releaseFromRead(readCache.loadForRead(10, i, writeCache, false));
    }
    for (int i = 0; i < 3; i++) {
      readCache.releaseFromRead(readCache.loadForRead(11, i, writeCache, false));
    }
    Assert.assertEquals("sanity: 5 pages in cache", 5L * PAGE_SIZE, readCache.getUsedMemory());

    // Close file 10 (flush=true is passed through).
    readCache.closeFile(10, true, writeCache);

    // File 10's 2 pages must be evicted; file 11's 3 pages must remain.
    Assert.assertEquals(
        "After closeFile(10), only file 11's 3 pages must remain",
        3L * PAGE_SIZE, readCache.getUsedMemory());
    Assert.assertEquals("writeCache.close(fileId, flush) must be called once",
        1, writeCache.closeFileCount.get());
    // Pin the exact fileId — a regression that passed any non-negative ID (e.g., always 0)
    // would have slipped through the previous weak >= 0 check.
    Assert.assertEquals("closeFile must pass the fileId that was used (10)",
        10L, writeCache.lastClosedFileId);

    readCache.assertSize();
    readCache.assertConsistency();
  }

  // ---- deleteFile ----

  /**
   * {@code deleteFile(fileId, writeCache)} must:
   * <ol>
   *   <li>Remove all cached pages for {@code fileId}.</li>
   *   <li>Invoke {@code writeCache.deleteFile(fileId)} exactly once.</li>
   *   <li>Leave other files' cached pages untouched.</li>
   * </ol>
   */
  @Test
  public void testDeleteFileEvictsCacheEntriesAndDelegatesDelete() throws IOException {
    // Load 4 pages for file 20, 1 page for file 21.
    for (int i = 0; i < 4; i++) {
      readCache.releaseFromRead(readCache.loadForRead(20, i, writeCache, false));
    }
    readCache.releaseFromRead(readCache.loadForRead(21, 0, writeCache, false));
    Assert.assertEquals("sanity: 5 pages in cache", 5L * PAGE_SIZE, readCache.getUsedMemory());

    readCache.deleteFile(20, writeCache);

    Assert.assertEquals(
        "After deleteFile(20), only file 21's 1 page must remain",
        1L * PAGE_SIZE, readCache.getUsedMemory());
    Assert.assertEquals("writeCache.deleteFile() must be called exactly once",
        1, writeCache.deleteFileCount.get());

    readCache.assertSize();
    readCache.assertConsistency();
  }

  /**
   * {@code deleteFile()} on a file with no cached pages must still invoke
   * {@code writeCache.deleteFile()} exactly once (the file-system operation must always run).
   */
  @Test
  public void testDeleteFileOnUncachedFileStillDelegates() throws IOException {
    Assert.assertEquals("sanity: cache is empty", 0, readCache.getUsedMemory());

    readCache.deleteFile(99, writeCache);

    Assert.assertEquals(
        "deleteFile on an uncached file must still invoke writeCache.deleteFile()",
        1, writeCache.deleteFileCount.get());
    Assert.assertEquals("cache must remain empty", 0, readCache.getUsedMemory());
  }

  // ---- shrinkFile ----

  /**
   * {@code shrinkFile(fileId, targetBytes, writeCache)} must:
   * <ol>
   *   <li>Invoke {@code writeCache.shrinkFile(fileId, targetBytes)} exactly once with the
   *       supplied target.</li>
   *   <li>Purge cache entries at {@code pageIndex >= targetBytes / pageSize} for {@code fileId}
   *       only.</li>
   *   <li>Preserve entries below the cutoff for {@code fileId}, plus all entries for other
   *       files.</li>
   * </ol>
   *
   * <p>Sets up file 30 with 6 cached pages and file 31 with 2 cached pages, shrinks file 30 to
   * 3 pages, and verifies the cache holds exactly {3 + 2 = 5} pages afterwards — 3 below-cutoff
   * pages for file 30, plus all 2 pages for file 31.
   *
   * <p><b>Order pinning</b>: the orchestrator must call {@code writeCache.shrinkFile} BEFORE
   * the LFRC range purge. The range purge invokes {@code writeCache.checkCacheOverflow} once
   * per evicted entry (see {@code LockFreeReadCache.clearFile}'s freeze/onRemove/
   * checkCacheOverflow loop) — those calls are observable from the {@code TrackingWriteCache}
   * mock and fire only during the LFRC purge phase. We assert
   * {@code shrinkFile} appears in the captured event list strictly BEFORE the first
   * {@code clearFile.checkCacheOverflow} event. A regression that swapped the production
   * order (purge cache first, then call writeCache.shrinkFile) would surface as
   * {@code shrinkFile} appearing AFTER the first {@code checkCacheOverflow} event, failing the
   * index comparison. An order-counter approach (which the earlier iteration used) is too
   * lax: it only pins "writeCache.shrinkFile ran at some point during the orchestrator's
   * invocation" rather than "writeCache.shrinkFile ran before the LFRC range purge."
   */
  @Test
  public void testShrinkFileDropsAboveTargetEntriesAndDelegates() throws IOException {
    for (int i = 0; i < 6; i++) {
      readCache.releaseFromRead(readCache.loadForRead(30, i, writeCache, false));
    }
    for (int i = 0; i < 2; i++) {
      readCache.releaseFromRead(readCache.loadForRead(31, i, writeCache, false));
    }
    Assert.assertEquals("sanity: 8 pages in cache", 8L * PAGE_SIZE, readCache.getUsedMemory());
    Assert.assertEquals("sanity: shrinkFile not called yet", 0, writeCache.shrinkFileCount.get());

    // Capture distinguishable events into a thread-safe list. TrackingWriteCache records
    // "writeCache.shrinkFile" on its shrinkFile entry, and (via the new tracker hook)
    // "clearFile.checkCacheOverflow" each time clearFile's per-evicted-entry
    // loop invokes checkCacheOverflow. The two are mutually exclusive on the production
    // call path (shrinkFile runs once; checkCacheOverflow runs once per evicted entry
    // during the LFRC purge that follows), so their ordering in the list is the
    // orchestrator's call order.
    final var callOrder =
        java.util.Collections.synchronizedList(new java.util.ArrayList<String>());
    writeCache.attachCallOrderTracker(callOrder);

    final long targetBytes = 3L * PAGE_SIZE;
    readCache.shrinkFile(30, targetBytes, writeCache);

    Assert.assertEquals(
        "writeCache.shrinkFile must be invoked exactly once",
        1, writeCache.shrinkFileCount.get());
    Assert.assertEquals(
        "writeCache.shrinkFile must receive the orchestrator's targetBytes verbatim",
        targetBytes, writeCache.lastShrinkTargetBytes);

    // Snapshot the captured order list under its own lock so the assertions below see a
    // consistent view even if a future test variant spawns concurrent callers.
    final java.util.List<String> capturedOrder;
    synchronized (callOrder) {
      capturedOrder = new java.util.ArrayList<>(callOrder);
    }
    final int shrinkIdx = capturedOrder.indexOf("writeCache.shrinkFile");
    final int checkOverflowIdx =
        capturedOrder.indexOf("clearFile.checkCacheOverflow");
    Assert.assertTrue(
        "writeCache.shrinkFile must appear in the captured order list (capturedOrder="
            + capturedOrder + ")",
        shrinkIdx >= 0);
    Assert.assertTrue(
        "clearFile.checkCacheOverflow must appear in the captured order list — the"
            + " LFRC range purge invokes it once per evicted entry; missing means the purge"
            + " never ran or never evicted (capturedOrder="
            + capturedOrder + ")",
        checkOverflowIdx >= 0);
    Assert.assertTrue(
        "writeCache.shrinkFile must run BEFORE the LFRC range purge (shrinkIdx="
            + shrinkIdx + ", checkOverflowIdx=" + checkOverflowIdx + ", capturedOrder="
            + capturedOrder + ")",
        shrinkIdx < checkOverflowIdx);

    Assert.assertEquals(
        "After shrinkFile(30, 3 * PAGE_SIZE), 3 pages for file 30 + 2 pages for file 31 remain",
        5L * PAGE_SIZE, readCache.getUsedMemory());

    readCache.assertSize();
    readCache.assertConsistency();
  }

  /**
   * {@code shrinkFile()} with {@code targetBytes = 0} drops every cached page for the target
   * file — equivalent shape to {@code truncateFile} but exercised through the new orchestrator.
   * Catches a regression where the range filter mishandles the {@code minPageIndex == 0} edge
   * case (which should match every page).
   */
  @Test
  public void testShrinkFileToZeroDropsEverything() throws IOException {
    for (int i = 0; i < 4; i++) {
      readCache.releaseFromRead(readCache.loadForRead(40, i, writeCache, false));
    }
    Assert.assertEquals("sanity: 4 pages in cache", 4L * PAGE_SIZE, readCache.getUsedMemory());

    readCache.shrinkFile(40, 0L, writeCache);

    Assert.assertEquals(
        "Every cached page for file 40 must be evicted by shrinkFile(0)",
        0, readCache.getUsedMemory());
    Assert.assertEquals(
        "writeCache.shrinkFile must still be invoked once on the zero-target path",
        1, writeCache.shrinkFileCount.get());

    readCache.assertSize();
    readCache.assertConsistency();
  }

  /**
   * {@code shrinkFile(fileId, targetBytes, writeCache)} with {@code targetBytes} at or above the
   * current physical size is a no-op at the write-back layer: {@code WriteCache.shrinkFile}
   * truncates nothing and reports {@code false}. The orchestrator must then SKIP the read-cache
   * purge entirely, leaving every cached page for the file in place — there is nothing to evict
   * because no page was physically dropped (invariant: the purge runs iff the write-cache layer
   * truncated).
   *
   * <p><b>Non-vacuity</b> comes from seeding pages that fall INSIDE the range an un-gated purge
   * would touch — not merely from a non-empty cache. The range purge evicts entries at
   * {@code pageIndex >= minPageIndex}, where {@code minPageIndex = targetBytes / pageSize}. A
   * regression that deleted the {@code if (truncated)} guard would restore the unconditional
   * purge; for the test to catch that, {@code minPageIndex} must sit at or below a seeded page
   * index so the un-gated purge WOULD evict at least one seeded page. Here {@code targetBytes =
   * 3 * PAGE_SIZE} gives {@code minPageIndex = 3} against 6 seeded pages (indices 0–5), so an
   * un-gated purge would drop pages 3, 4, 5. {@link TrackingWriteCache#setShrinkFileReturnValue}
   * returns the controlled boolean independent of {@code targetBytes}, so the target value is
   * free to choose; it is chosen to sit below the seeded range precisely so the gate's removal
   * is observable.
   *
   * <p>Driving {@code setShrinkFileReturnValue(false)} makes the skip observable three ways:
   * (i) {@code getUsedMemory()} is unchanged at 6 pages (no eviction — an un-gated purge would
   * have dropped pages 3, 4, 5 and left only 3 pages), (ii) the captured call-order list records
   * {@code writeCache.shrinkFile} but NO {@code clearFile.checkCacheOverflow} event (the LFRC
   * range purge never ran — it invokes {@code checkCacheOverflow} once per evicted entry), and
   * (iii) {@code shrinkFileCount == 1} (the write-back delegate still fires exactly once).
   * Together with the genuine-truncate tests above (which drive the {@code true} branch), this
   * gives 100% branch coverage of the new conditional purge. The falsifiability of assertion
   * (i) was confirmed during implementation by temporarily deleting the {@code if (truncated)}
   * guard and observing this test fail (used memory dropped to 3 pages), then restoring it.
   */
  @Test
  public void testShrinkFileNoOpSkipsPurgeAndPreservesCacheEntries() throws IOException {
    // Seed 6 live pages (indices 0–5) for file 70 so the skipped-purge branch is non-vacuous:
    // the chosen target's minPageIndex (3) falls INSIDE this range, so an un-gated purge would
    // evict pages 3, 4, 5 — observable as a used-memory drop the gate must prevent.
    for (int i = 0; i < 6; i++) {
      readCache.releaseFromRead(readCache.loadForRead(70, i, writeCache, false));
    }
    Assert.assertEquals("sanity: 6 pages in cache", 6L * PAGE_SIZE, readCache.getUsedMemory());
    Assert.assertEquals("sanity: shrinkFile not called yet", 0, writeCache.shrinkFileCount.get());

    // Model the WriteCache pre-flight no-op: targetBytes >= current size truncates nothing,
    // so shrinkFile reports false and the orchestrator must skip the read-cache purge.
    writeCache.setShrinkFileReturnValue(false);

    final var callOrder =
        java.util.Collections.synchronizedList(new java.util.ArrayList<String>());
    writeCache.attachCallOrderTracker(callOrder);

    // targetBytes = 3 * PAGE_SIZE → minPageIndex = 3, which sits AT/BELOW the seeded range
    // (pages 0–5): an un-gated purge WOULD evict pages 3, 4, 5. The value is page-aligned,
    // non-negative, and free of overflow, so the orchestrator's argument guards pass and the
    // call reaches the gated delegate; setShrinkFileReturnValue(false) controls the boolean
    // independent of this target.
    final long targetBytes = 3L * PAGE_SIZE;
    readCache.shrinkFile(70, targetBytes, writeCache);

    // (iii) The write-back delegate still fires exactly once, with the supplied target.
    Assert.assertEquals(
        "writeCache.shrinkFile must be invoked exactly once even on the no-op path",
        1, writeCache.shrinkFileCount.get());
    Assert.assertEquals(
        "writeCache.shrinkFile must receive the orchestrator's targetBytes verbatim",
        targetBytes, writeCache.lastShrinkTargetBytes);

    // (i) No eviction occurred — all 6 seeded pages remain cached. An un-gated purge would have
    // evicted pages 3, 4, 5 (minPageIndex=3) and left only 3 pages (3 * PAGE_SIZE), so this
    // assertion discriminates "gate skipped the purge" from "purge ran".
    Assert.assertEquals(
        "After a no-op shrink (truncated=false), the read cache must be untouched: all 6"
            + " seeded pages for file 70 must remain (an un-gated purge would have dropped"
            + " pages 3,4,5)",
        6L * PAGE_SIZE, readCache.getUsedMemory());

    // (ii) The LFRC range purge never ran: writeCache.shrinkFile is recorded, but no
    // clearFile.checkCacheOverflow event (which the purge emits once per evicted entry).
    final java.util.List<String> capturedOrder;
    synchronized (callOrder) {
      capturedOrder = new java.util.ArrayList<>(callOrder);
    }
    Assert.assertTrue(
        "writeCache.shrinkFile must be recorded on the no-op path (capturedOrder="
            + capturedOrder + ")",
        capturedOrder.contains("writeCache.shrinkFile"));
    Assert.assertFalse(
        "the read-cache purge must be SKIPPED on a no-op shrink — no"
            + " clearFile.checkCacheOverflow event may appear (capturedOrder="
            + capturedOrder + ")",
        capturedOrder.contains("clearFile.checkCacheOverflow"));

    readCache.assertSize();
    readCache.assertConsistency();
  }

  /**
   * Single-page-evict boundary test: on a genuine truncate (truncated=true), {@code minPageIndex}
   * equal to the highest cached page index must evict EXACTLY that highest page and preserve every
   * page below it. This pins the range filter's {@code pageIndex >= minPageIndex} boundary — a
   * regression to a strict {@code >} would leave the highest page cached, and a regression to
   * {@code <=} / off-by-one would over-evict the page below.
   *
   * <p>Seeds 4 pages (indices 0–3) for file 41 and shrinks to {@code 3 * PAGE_SIZE} →
   * {@code minPageIndex = 3}. Only page 3 is at or above the cutoff, so exactly one page is
   * evicted and pages 0, 1, 2 survive (3 * PAGE_SIZE remain).
   */
  @Test
  public void testShrinkFileEvictsExactlyTheHighestPageAtBoundary() throws IOException {
    for (int i = 0; i < 4; i++) {
      readCache.releaseFromRead(readCache.loadForRead(41, i, writeCache, false));
    }
    Assert.assertEquals("sanity: 4 pages in cache", 4L * PAGE_SIZE, readCache.getUsedMemory());

    // Genuine truncate (default return is true); minPageIndex = 3 equals the highest seeded
    // index, so only page 3 falls in the >= 3 range.
    readCache.shrinkFile(41, 3L * PAGE_SIZE, writeCache);

    Assert.assertEquals(
        "writeCache.shrinkFile must be invoked exactly once",
        1, writeCache.shrinkFileCount.get());
    Assert.assertEquals(
        "At the >= boundary, only the highest page (index 3) is evicted; pages 0,1,2 survive",
        3L * PAGE_SIZE, readCache.getUsedMemory());

    readCache.assertSize();
    readCache.assertConsistency();
  }

  /**
   * {@code shrinkFile()} on a file with no cached pages must still invoke
   * {@code writeCache.shrinkFile()} exactly once — the file-system call is the
   * orchestrator's responsibility regardless of cache state, and a clean shutdown
   * (no pages cached) is a valid call shape for the recovery pass.
   */
  @Test
  public void testShrinkFileOnUncachedFileStillDelegates() throws IOException {
    Assert.assertEquals("sanity: cache is empty", 0, readCache.getUsedMemory());

    readCache.shrinkFile(99, 4L * PAGE_SIZE, writeCache);

    Assert.assertEquals(
        "shrinkFile on an uncached file must still invoke writeCache.shrinkFile()",
        1, writeCache.shrinkFileCount.get());
    Assert.assertEquals("cache must remain empty", 0, readCache.getUsedMemory());
  }

  /**
   * Negative {@code targetBytes} is rejected at the orchestrator entry point with
   * {@link IllegalArgumentException}. The guard closes the
   * {@link com.jetbrains.youtrackdb.internal.core.storage.cache.chm.DirectMemoryOnlyDiskCache#shrinkFile}
   * no-op slip on the in-memory engine — without it a negative target would flow through to
   * the {@code (int)} cast for {@code minPageIndex} and the downstream range filter would
   * purge every cached page for the file. The delegate must NOT be invoked when the guard
   * fires, so the read-cache + write-cache pair stay in their pre-call state.
   */
  @Test
  public void testShrinkFileRejectsNegativeTarget() {
    for (int i = 0; i < 3; i++) {
      readCache.releaseFromRead(readCache.loadForRead(50, i, writeCache, false));
    }
    Assert.assertEquals("sanity: 3 pages in cache", 3L * PAGE_SIZE, readCache.getUsedMemory());

    Assert.assertThrows(IllegalArgumentException.class,
        () -> readCache.shrinkFile(50, -1L, writeCache));

    // Delegate must not have been called — the LFRC-level guard fires before
    // writeCache.shrinkFile.
    Assert.assertEquals(
        "writeCache.shrinkFile must NOT be invoked when the LFRC guard rejects the call",
        0, writeCache.shrinkFileCount.get());
    // Cache contents must be unperturbed.
    Assert.assertEquals(
        "cache state must be unchanged when the LFRC guard rejects the call",
        3L * PAGE_SIZE, readCache.getUsedMemory());
  }

  /**
   * Non-page-aligned {@code targetBytes} is rejected at the orchestrator entry point with
   * {@link IllegalArgumentException}. {@code (int) (targetBytes / pageSize)} would otherwise
   * silently floor to a page boundary and the LFRC range purge would only drop entries past
   * the rounded-down index — leaving the half-page region inconsistent between cache and
   * disk. The delegate must NOT be invoked when the guard fires.
   */
  @Test
  public void testShrinkFileRejectsNonPageAlignedTarget() {
    for (int i = 0; i < 3; i++) {
      readCache.releaseFromRead(readCache.loadForRead(51, i, writeCache, false));
    }
    Assert.assertEquals("sanity: 3 pages in cache", 3L * PAGE_SIZE, readCache.getUsedMemory());

    final long unaligned = 3L * PAGE_SIZE + 1L;
    Assert.assertThrows(IllegalArgumentException.class,
        () -> readCache.shrinkFile(51, unaligned, writeCache));

    Assert.assertEquals(
        "writeCache.shrinkFile must NOT be invoked when the LFRC guard rejects the call",
        0, writeCache.shrinkFileCount.get());
    Assert.assertEquals(
        "cache state must be unchanged when the LFRC guard rejects the call",
        3L * PAGE_SIZE, readCache.getUsedMemory());
  }

  /**
   * Overflow {@code targetBytes} — a target whose {@code targetBytes / pageSize} exceeds
   * {@link Integer#MAX_VALUE} would wrap the {@code (int)} cast for {@code minPageIndex} to
   * a negative value; the downstream {@code pageIndex >= minPageIndex} filter would then
   * match every cached entry for the file. The orchestrator guard fires before any I/O so
   * the delegate must NOT be invoked.
   */
  @Test
  public void testShrinkFileRejectsOverflowTarget() {
    for (int i = 0; i < 2; i++) {
      readCache.releaseFromRead(readCache.loadForRead(52, i, writeCache, false));
    }
    Assert.assertEquals("sanity: 2 pages in cache", 2L * PAGE_SIZE, readCache.getUsedMemory());

    final long overflow = ((long) Integer.MAX_VALUE + 1L) * PAGE_SIZE;
    Assert.assertThrows(IllegalArgumentException.class,
        () -> readCache.shrinkFile(52, overflow, writeCache));

    Assert.assertEquals(
        "writeCache.shrinkFile must NOT be invoked when the LFRC guard rejects the call",
        0, writeCache.shrinkFileCount.get());
    Assert.assertEquals(
        "cache state must be unchanged when the LFRC guard rejects the call",
        2L * PAGE_SIZE, readCache.getUsedMemory());
  }

  // ---- silentLoadForRead ----

  /**
   * {@code silentLoadForRead()} must return a valid cache entry for a page that exists in the
   * WriteCache. The returned entry must be acquirable (its use-count is incremented by the call),
   * and the fileId / pageIndex getters must match the requested coordinates.
   *
   * <p>Unlike {@code loadForRead()}, {@code silentLoadForRead()} does NOT add the entry to the
   * eviction policy — it stores the new entry in a separate array and returns it directly.
   * The test just verifies the entry is non-null and has the correct coordinates.
   */
  @Test
  public void testSilentLoadForReadReturnsCacheEntryOnMiss() {
    // silentLoadForRead takes (extFileId, pageIndex, writeCache, verifyChecksums).
    // The extFileId must be the external file ID (composed with writeCache.getId() = 0).
    final var entry = readCache.silentLoadForRead(0, 5, writeCache, false);

    Assert.assertNotNull("silentLoadForRead must return a non-null entry for a valid page", entry);
    Assert.assertEquals("returned entry must have the requested page index",
        5, entry.getPageIndex());

    // Release the entry to prevent pinned-entry tearDown failures.
    readCache.releaseFromRead(entry);
  }

  /**
   * {@code silentLoadForRead()} on a page for which the WriteCache returns null (the page does
   * not exist in the storage) must return null without throwing.
   */
  @Test
  public void testSilentLoadForReadReturnsNullWhenWriteCacheReturnsNull() {
    // Use a negative pageIndex as a sentinel — TrackingWriteCache.load() returns null for it.
    final var entry = readCache.silentLoadForRead(0, TrackingWriteCache.NULL_PAGE_INDEX,
        writeCache, false);
    Assert.assertNull(
        "silentLoadForRead must return null when WriteCache.load() returns null", entry);
  }

  // ---- Minimal WriteCache stub ----

  /**
   * A WriteCache stub that tracks which file-lifecycle methods were called and allows
   * {@code load()} to return a page frame for valid page indices or null for the sentinel
   * {@link #NULL_PAGE_INDEX}.
   */
  private static final class TrackingWriteCache implements WriteCache {

    /** Page index sentinel that causes {@code load()} to return {@code null}. */
    static final int NULL_PAGE_INDEX = Integer.MIN_VALUE;

    private final ByteBufferPool byteBufferPool;

    final AtomicInteger truncateCount = new AtomicInteger();
    final AtomicInteger closeFileCount = new AtomicInteger();
    final AtomicInteger deleteFileCount = new AtomicInteger();
    final AtomicInteger shrinkFileCount = new AtomicInteger();
    volatile long lastShrinkTargetBytes = -1L;
    /**
     * Test-controlled return value for {@link #shrinkFile(long, long)}. Defaults to {@code true}
     * (genuine-truncate semantics) so the existing eviction tests, which assert the read-cache
     * purge runs, see the truncated branch. A test that needs the no-op branch (no physical
     * truncate, so the orchestrator skips its purge) flips this to {@code false} via
     * {@link #setShrinkFileReturnValue(boolean)}.
     */
    volatile boolean shrinkFileReturnValue = true;
    volatile long lastClosedFileId = -1;
    /**
     * Stamps the shrinkFile invocation against a sequence counter the test may install via
     * {@link #attachOrderCounter}, so an LFRC-level ordering check can verify that
     * {@code writeCache.shrinkFile} ran before whatever subsequent observation the test
     * stamps after the call. {@code -1} means the call has not been observed yet, or no
     * counter is attached.
     *
     * <p>Kept for the (currently un-used) order-counter pattern; the newer call-order
     * tracker (see {@link #attachCallOrderTracker}) is the preferred shape because it pins
     * the exact production-side event ordering rather than the vacuous "ran at some point"
     * shape an order counter captures.
     */
    volatile int shrinkFileCallOrder = -1;
    private volatile AtomicInteger sharedOrderCounter;
    /**
     * Captures distinguishable production-side events ({@code "writeCache.shrinkFile"} on
     * shrinkFile entry; {@code "clearFile.checkCacheOverflow"} on each
     * {@code checkCacheOverflow} call) so a test can pin the orchestrator's actual call
     * order. {@code null} means no tracker is attached.
     */
    private volatile java.util.List<String> callOrderTracker;

    void attachOrderCounter(final AtomicInteger counter) {
      this.sharedOrderCounter = counter;
    }

    /**
     * Override the boolean {@link #shrinkFile(long, long)} reports. Pass {@code false} to model
     * the WriteCache pre-flight no-op (a target at or above the current physical size, which
     * truncates nothing) so the orchestrator's purge-skip branch becomes observable.
     */
    void setShrinkFileReturnValue(final boolean value) {
      this.shrinkFileReturnValue = value;
    }

    /**
     * Attach a thread-safe list (the test should pass
     * {@code Collections.synchronizedList(new ArrayList<>())} or a {@code CopyOnWriteArrayList})
     * to capture the order of distinguishable production-side events. The list receives a
     * {@code "writeCache.shrinkFile"} entry on shrinkFile invocation and a
     * {@code "clearFile.checkCacheOverflow"} entry on each {@code checkCacheOverflow}
     * call (which the LFRC range purge invokes once per evicted entry).
     */
    void attachCallOrderTracker(final java.util.List<String> tracker) {
      this.callOrderTracker = tracker;
    }

    TrackingWriteCache(final ByteBufferPool byteBufferPool) {
      this.byteBufferPool = byteBufferPool;
    }

    @Override
    public CachePointer load(
        final long fileId,
        final long startPageIndex,
        final ModifiableBoolean cacheHit,
        final boolean verifyChecksums) {
      if ((int) startPageIndex == NULL_PAGE_INDEX) {
        return null;
      }
      final var pointer = byteBufferPool.acquireDirect(true, Intention.TEST);
      final var cachePointer =
          new CachePointer(pointer, byteBufferPool, fileId, (int) startPageIndex);
      cachePointer.incrementReadersReferrer();
      return cachePointer;
    }

    @Override
    public CachePointer loadIfPresent(
        final long fileId, final long pageIndex, final boolean verifyChecksums) {
      // Fixture-only collapse: production WriteCache.loadIfPresent returns null on a
      // miss while load() reads through (see WriteCache.loadIfPresent Javadoc for the
      // contract divergence). The TrackingWriteCache fixture has no on-disk state
      // distinction, so this stub mirrors load() semantics; future tests that need to
      // exercise the present-vs-absent split must add their own tracker rather than
      // rely on this stub.
      return load(fileId, pageIndex, new ModifiableBoolean(), verifyChecksums);
    }

    @Override
    public CachePointer loadOrAdd(
        final long fileId, final long pageIndex, final boolean verifyChecksums) {
      // Fixture-only collapse: production WriteCache.loadOrAdd is total (allocates on
      // miss) while load() reads through; the two diverge at the miss boundary (see
      // WriteCache.loadIfPresent Javadoc for the sibling contract). This fixture has
      // no on-disk state distinction, so the stub delegates to load() and always
      // returns a fresh CachePointer for non-sentinel page indices. Future tests that
      // need to exercise the miss-vs-hit asymmetry must add their own tracker.
      return load(fileId, pageIndex, new ModifiableBoolean(), verifyChecksums);
    }

    @Override
    public void truncateFile(final long fileId) {
      truncateCount.incrementAndGet();
    }

    /**
     * The production {@code ReadCache.shrinkFile} gate is reached only from
     * {@code AbstractStorage.truncateOrphansAfterRecovery}, which holds
     * {@code stateLock.writeLock()} during storage {@code open()}; it never runs concurrently
     * with client {@code loadForRead}/eviction. These shrinkFile tests are intentionally
     * single-threaded — a concurrent contention test would model no real production
     * interleaving.
     */
    @Override
    public boolean shrinkFile(final long fileId, final long targetBytes) {
      // Tracking variant — unlike the four other test mocks (which throw UOE because the
      // recovery-time orphan-truncation pass never reaches them), this mock is exercised
      // by the LockFreeReadCache.shrinkFile orchestration tests below and records the
      // delegate-dispatch counters they assert on.
      shrinkFileCount.incrementAndGet();
      lastShrinkTargetBytes = targetBytes;
      // Stamp the order against a test-supplied counter, if attached, so a subsequent
      // observation (e.g., another counter increment after readCache.shrinkFile returns)
      // proves writeCache.shrinkFile ran first.
      final var counter = sharedOrderCounter;
      if (counter != null) {
        shrinkFileCallOrder = counter.incrementAndGet();
      }
      final var tracker = callOrderTracker;
      if (tracker != null) {
        tracker.add("writeCache.shrinkFile");
      }
      // Report the test-controlled truncated/no-op outcome (default true) so the orchestrator's
      // conditional purge can be driven down both branches from the test.
      return shrinkFileReturnValue;
    }

    @Override
    public void close(final long fileId, final boolean flush) {
      closeFileCount.incrementAndGet();
      lastClosedFileId = fileId;
    }

    @Override
    public void deleteFile(final long fileId) {
      deleteFileCount.incrementAndGet();
    }

    @Override
    public int getId() {
      return 0;
    }

    @Override
    public void addPageIsBrokenListener(final PageIsBrokenListener listener) {
    }

    @Override
    public void removePageIsBrokenListener(final PageIsBrokenListener listener) {
    }

    @Override
    public long bookFileId(final String fileName) {
      return 0;
    }

    @Override
    public long loadFile(final String fileName) {
      return 0;
    }

    @Override
    public long addFile(final String fileName) {
      return 0;
    }

    @Override
    public long addFile(final String fileName, final long fileId) {
      return 0;
    }

    @Override
    public long fileIdByName(final String fileName) {
      return 0;
    }

    @Override
    public boolean checkLowDiskSpace() {
      return false;
    }

    @Override
    public void syncDataFiles(long segmentId) {
    }

    @Override
    public void flushTillSegment(final long segmentId) {
    }

    @Override
    public boolean exists(final String fileName) {
      return false;
    }

    @Override
    public boolean exists(final long fileId) {
      return false;
    }

    @Override
    public void restoreModeOn() {
    }

    @Override
    public void restoreModeOff() {
    }

    @Override
    public void store(
        final long fileId, final long pageIndex, final CachePointer dataPointer) {
    }

    @Override
    public void checkCacheOverflow() {
      // The LFRC range purge (LockFreeReadCache.clearFile) invokes
      // checkCacheOverflow once per evicted entry; on the shrinkFile orchestration tests
      // those calls fire AFTER writeCache.shrinkFile, so a captured-event list pins the
      // production order. Other LFRC paths (loadForRead, releaseFromRead, etc.) also
      // invoke checkCacheOverflow; the tests that pin order-of-events know to attach the
      // tracker only at the start of the orchestrated call so the captured list
      // represents only the shrinkFile invocation.
      final var tracker = callOrderTracker;
      if (tracker != null) {
        tracker.add("clearFile.checkCacheOverflow");
      }
    }

    @Override
    public void flush(final long fileId) {
    }

    @Override
    public void flush() {
    }

    @Override
    public long getFilledUpTo(final long fileId) {
      return 0;
    }

    @Override
    public long physicalSizeForBackupSnapshot(final long fileId) {
      // Mock parallel: delegates to getFilledUpTo for the cache-layer test fixture.
      return getFilledUpTo(fileId);
    }

    @Override
    public long getExclusiveWriteCachePagesSize() {
      return 0;
    }

    @Override
    public void renameFile(final long fileId, final String newFileName) {
    }

    @Override
    public long[] close() {
      return new long[0];
    }

    @Override
    public PageDataVerificationError[] checkStoredPages(
        final CommandOutputListener commandOutputListener) {
      return new PageDataVerificationError[0];
    }

    @Override
    public long[] delete() {
      return new long[0];
    }

    @Override
    public String fileNameById(final long fileId) {
      return null;
    }

    @Override
    public String nativeFileNameById(final long fileId) {
      return null;
    }

    @Override
    public Map<String, Long> files() {
      return null;
    }

    @Override
    public int pageSize() {
      return 0;
    }

    @Override
    public String restoreFileById(final long fileId) {
      return null;
    }

    @Override
    public void addBackgroundExceptionListener(final BackgroundExceptionListener listener) {
    }

    @Override
    public void removeBackgroundExceptionListener(
        final BackgroundExceptionListener listener) {
    }

    @Override
    public Path getRootDirectory() {
      return null;
    }

    @Override
    public int internalFileId(final long fileId) {
      return 0;
    }

    @Override
    public long externalFileId(final int fileId) {
      return 0;
    }

    @Override
    public boolean fileIdsAreEqual(long firsId, long secondId) {
      return false;
    }

    @Override
    public Long getMinimalNotFlushedSegment() {
      return null;
    }

    @Override
    public void updateDirtyPagesTable(
        final CachePointer pointer, final LogSequenceNumber startLSN) {
    }

    @Override
    public void create() {
    }

    @Override
    public void open() throws IOException {
    }

    @Override
    public void replaceFileId(long fileId, long newFileId) {
    }

    @Override
    public String getStorageName() {
      return null;
    }
  }
}
