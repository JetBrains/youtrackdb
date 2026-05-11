package com.jetbrains.youtrackdb.internal.core.storage.memory;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.internal.common.directmemory.ByteBufferPool;
import com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CachePointer;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Smoke and concurrency coverage for {@link DirectMemoryOnlyDiskCache#loadOrAdd}, the
 * in-memory parallel of the disk engine's {@code WOWCache.loadOrAdd}.
 *
 * <p>Each test exercises the new total cache primitive against a real
 * {@link DirectMemoryOnlyDiskCache} (no on-disk storage). The tests cover the load /
 * extend / gap-fill branches that collapse to a single map operation in the in-memory
 * engine, plus the install-then-publish atomicity contract under concurrent installers.
 */
public class DirectMemoryOnlyDiskCacheLoadOrAddTest {

  private static final int PAGE_SIZE = 1024;
  private static final int STORAGE_ID = 17;
  private static final String STORAGE_NAME = "directMemoryLoadOrAddTestStorage";
  private static final String FILE_NAME = "loadOrAdd.dat";

  private DirectMemoryOnlyDiskCache cache;
  private long fileId;

  @Before
  public void setUp() {
    cache = new DirectMemoryOnlyDiskCache(PAGE_SIZE, STORAGE_ID, STORAGE_NAME);
    fileId = cache.addFile(FILE_NAME);
  }

  @After
  public void tearDown() {
    if (cache != null) {
      cache.delete();
      cache = null;
    }
  }

  /**
   * Extend branch: a fresh file has zero pages; calling {@code loadOrAdd(fileId, 0)} must
   * install a fresh empty page, advance the high-watermark to one, and return a non-null
   * {@link CachePointer} with a clean buffer stamped {@code LSN(-1, -1)}. The buffer beyond
   * the LSN region must also be zero-filled (the {@code framePool.acquire(true, ...)}
   * contract): a regression flipping the zero-fill flag would silently leave the page with
   * stale residue from a prior allocation, which would corrupt {@code DurablePage}'s
   * "all data is zero on a fresh page" assumption. Mirrors the disk engine's extend-branch
   * contract on the in-memory engine.
   */
  @Test
  public void extendBranchAllocatesAndReturnsMagicStampedPointer() {
    assertEquals("fresh file must start at 0 pages", 0L, cache.getFilledUpTo(fileId));

    final var pointer = cache.loadOrAdd(fileId, 0L, /* verifyChecksums= */ false);
    try {
      assertNotNull("loadOrAdd must never return null on the extend branch", pointer);
      final var buffer = pointer.getBuffer();
      assertNotNull("backing buffer must be non-null", buffer);
      assertEquals("buffer must be positioned at 0", 0, buffer.position());
      assertEquals(
          "magic-stamped empty buffer must carry LSN(-1,-1) on both engines uniformly",
          new LogSequenceNumber(-1, -1),
          DurablePage.getLogSequenceNumberFromPage(buffer));
      assertEquals(
          "the returned pointer must reference the requested page index",
          0,
          pointer.getPageIndex());
      // The post-LSN page region must be zero-filled. Without this assertion a regression
      // flipping framePool.acquire(true, ...) to acquire(false, ...) would not be caught by
      // the LSN assertion alone.
      assertPostLsnRegionIsZeroFilled(buffer);
    } finally {
      pointer.decrementReadersReferrer();
    }
    assertEquals(
        "extend branch must advance the in-memory high-watermark by one page",
        1L,
        cache.getFilledUpTo(fileId));
  }

  /**
   * Idempotent re-call (load branch): a second {@code loadOrAdd} on the same page index
   * must observe the previously-installed entry and return the same {@link CachePointer}
   * instance. The high-watermark must not advance further (no double-install).
   */
  @Test
  public void secondLoadOrAddOnSameIndexTakesLoadBranchAndDoesNotReExtend() {
    final var first = cache.loadOrAdd(fileId, 0L, false);
    final CachePointer second;
    try {
      assertEquals(
          "single extend advances size to 1 page", 1L, cache.getFilledUpTo(fileId));
      second = cache.loadOrAdd(fileId, 0L, false);
    } finally {
      first.decrementReadersReferrer();
    }
    try {
      assertSame(
          "load branch must observe the previously-installed page; identity equality "
              + "guarantees no double-install happened",
          first,
          second);
    } finally {
      second.decrementReadersReferrer();
    }
    assertEquals(
        "second loadOrAdd on an already-installed page must not advance the watermark",
        1L,
        cache.getFilledUpTo(fileId));
  }

  /**
   * Gap-fill branch: starting from a fresh file, calling {@code loadOrAdd(fileId, 5)}
   * must install pages 0..5 inclusive (six pages) and return the target pointer only.
   * The intermediate gap pages must be observable via subsequent {@code loadOrAdd} calls
   * (they are installed in the per-file map, just not returned by the gap-fill caller).
   * Each gap probe also asserts the returned pointer's page index matches the requested
   * index — a regression returning the wrong page would otherwise pass the LSN check.
   */
  @Test
  public void gapFillBranchAllocatesEntireGapAndReturnsTargetPointer() {
    assertEquals("fresh file must start at 0 pages", 0L, cache.getFilledUpTo(fileId));

    final var target = cache.loadOrAdd(fileId, 5L, false);
    try {
      assertNotNull("gap-fill must never return null", target);
      assertEquals(
          "the returned pointer must be the target index, not a gap page",
          5,
          target.getPageIndex());
      assertEquals(
          "gap-fill must stamp the target buffer with LSN(-1,-1)",
          new LogSequenceNumber(-1, -1),
          DurablePage.getLogSequenceNumberFromPage(target.getBuffer()));
    } finally {
      target.decrementReadersReferrer();
    }
    assertEquals(
        "gap-fill must advance the watermark to exactly pageIndex + 1 pages "
            + "(0..5 inclusive = 6 pages)",
        6L,
        cache.getFilledUpTo(fileId));

    // Each gap page must be subsequently observable via loadOrAdd: a load branch hit on
    // each index 0..4 must return a non-null pointer from the same per-file map. The
    // identity is also stable across calls (load branch returns the installed entry).
    for (int i = 0; i < 5; i++) {
      final var gapPointer = cache.loadOrAdd(fileId, i, false);
      try {
        assertNotNull("gap page " + i + " must be installed and observable", gapPointer);
        assertEquals(
            "gap page " + i + " must be returned for the requested index",
            i,
            gapPointer.getPageIndex());
        assertEquals(
            "gap page " + i + " must have LSN(-1,-1) like the target",
            new LogSequenceNumber(-1, -1),
            DurablePage.getLogSequenceNumberFromPage(gapPointer.getBuffer()));
      } finally {
        gapPointer.decrementReadersReferrer();
      }
    }
    assertEquals(
        "load branch hits on gap pages must not advance the watermark further",
        6L,
        cache.getFilledUpTo(fileId));
  }

  /**
   * Smallest non-trivial gap-fill: starting from {@code currentSize == 1} after one
   * extend, a {@code loadOrAdd(fileId, 2)} call exercises the gap-fill branch where the
   * loop runs exactly once (over page 1) and the target (page 2) is installed after the
   * loop. Verifies the inclusive {@code [currentSize, pageIndex]} post-state against an
   * off-by-one bug at the boundary (a smaller test than the wide gap-fill above so an
   * off-by-one in either direction shows up clearly).
   */
  @Test
  public void gapFillOfExactlyOnePageBoundary() {
    final var p0 = cache.loadOrAdd(fileId, 0L, false);
    p0.decrementReadersReferrer();
    assertEquals("single extend advances size to 1 page", 1L, cache.getFilledUpTo(fileId));

    final var target = cache.loadOrAdd(fileId, 2L, false);
    try {
      assertNotNull("smallest non-trivial gap-fill must return a usable pointer", target);
      assertEquals("returned pointer is the target index", 2, target.getPageIndex());
    } finally {
      target.decrementReadersReferrer();
    }
    assertEquals(
        "size must advance to exactly pageIndex + 1 pages (3) after a 1-page gap-fill",
        3L,
        cache.getFilledUpTo(fileId));
  }

  /**
   * Extend on a non-empty file: after seeding pages 0 and 1 via two extend calls, calling
   * {@code loadOrAdd(fileId, 2)} must take the extend branch (zero-iteration gap-fill loop)
   * and advance the high-watermark from 2 to 3. Mirrors the disk engine's
   * {@code WOWCacheLoadOrAddTest.extendBranchExtendsByOneOnNonEmptyFile} parity test so a
   * regression that broke the {@code currentSize == pageIndex} dispatch on a non-fresh file
   * would surface here.
   */
  @Test
  public void extendBranchOnNonEmptyFileAdvancesByOnePage() {
    cache.loadOrAdd(fileId, 0L, false).decrementReadersReferrer();
    cache.loadOrAdd(fileId, 1L, false).decrementReadersReferrer();
    assertEquals(
        "two extends on a fresh file must advance the watermark to 2 pages",
        2L,
        cache.getFilledUpTo(fileId));

    final var target = cache.loadOrAdd(fileId, 2L, false);
    try {
      assertNotNull("extend branch on a non-empty file must return a usable pointer", target);
      assertEquals("returned pointer is the requested target index", 2, target.getPageIndex());
      assertEquals(
          "extend on a non-empty file still stamps the new page with LSN(-1,-1)",
          new LogSequenceNumber(-1, -1),
          DurablePage.getLogSequenceNumberFromPage(target.getBuffer()));
    } finally {
      target.decrementReadersReferrer();
    }
    assertEquals(
        "extend branch must advance the watermark from 2 to 3 (zero-iteration gap-fill loop)",
        3L,
        cache.getFilledUpTo(fileId));
  }

  /**
   * Negative {@code pageIndex}: the dispatch prelude must reject a negative page index
   * before any side effects (no install, no watermark advance). Mirrors
   * {@code WOWCache.loadOrAdd}'s validation guard so callers see consistent behaviour
   * across both engines.
   */
  @Test
  public void loadOrAddRejectsNegativePageIndex() {
    final var thrown =
        assertThrows(
            IllegalArgumentException.class, () -> cache.loadOrAdd(fileId, -1L, false));
    assertNotNull("exception must carry a message", thrown.getMessage());
    assertTrue(
        "exception message must include the offending value",
        thrown.getMessage().contains("-1"));
    assertEquals(
        "rejected loadOrAdd must not advance the watermark",
        0L,
        cache.getFilledUpTo(fileId));
  }

  /**
   * Deleted-file caveat from the totality contract: the totality contract holds for any
   * open, non-deleted fileId, but a previously-deleted fileId surfaces an
   * {@link IllegalArgumentException} as a caller-bug signal. This matches
   * {@code WOWCache.loadOrAdd}'s symmetric behaviour on a deleted file so callers cannot
   * distinguish the two engines on this caveat.
   */
  @Test
  public void loadOrAddOnDeletedFilePropagatesIllegalArgumentExceptionRaw() {
    cache.loadOrAdd(fileId, 0L, false).decrementReadersReferrer();
    cache.deleteFile(fileId);
    assertThrows(
        "deleted fileId must surface IllegalArgumentException, not null",
        IllegalArgumentException.class,
        () -> cache.loadOrAdd(fileId, 0L, false));
  }

  /**
   * Never-registered fileId: a fabricated external fileId built from an arbitrary intId
   * that was never returned from {@code addFile} must surface an
   * {@link IllegalArgumentException} (caller-bug signal), and the message must include the
   * fabricated intId so the operator can correlate the exception with the offending caller.
   */
  @Test
  public void loadOrAddOnNeverRegisteredFileIdPropagatesIllegalArgumentException() {
    final int fabricatedIntId = 9999;
    final long fabricatedFileId =
        DirectMemoryOnlyDiskCache.composeFileId(STORAGE_ID, fabricatedIntId);
    final var thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> cache.loadOrAdd(fabricatedFileId, 0L, false));
    assertNotNull("exception must carry a message", thrown.getMessage());
    assertTrue(
        "exception message must include the fabricated intId for operator triage",
        thrown.getMessage().contains(Integer.toString(fabricatedIntId)));
  }

  /**
   * verifyChecksums flag: the in-memory engine has no on-disk image to verify, so the flag
   * is documented as ignored. Pin the contract: calling with {@code verifyChecksums == true}
   * on a fresh file must extend identically to {@code verifyChecksums == false} (same
   * watermark advance, same magic-stamped LSN). A regression that started honouring the
   * flag on the in-memory engine — for example by throwing or by short-circuiting the
   * extend — would surface here.
   */
  @Test
  public void verifyChecksumsTrueIsIgnoredOnInMemoryEngine() {
    final var pointer = cache.loadOrAdd(fileId, 0L, /* verifyChecksums= */ true);
    try {
      assertNotNull("verifyChecksums=true must not change the totality contract", pointer);
      assertEquals(
          "verifyChecksums flag must not affect the magic-stamped LSN",
          new LogSequenceNumber(-1, -1),
          DurablePage.getLogSequenceNumberFromPage(pointer.getBuffer()));
      assertEquals("returned pointer indexes the requested page", 0, pointer.getPageIndex());
    } finally {
      pointer.decrementReadersReferrer();
    }
    assertEquals(
        "verifyChecksums=true still extends to one page on the in-memory engine",
        1L,
        cache.getFilledUpTo(fileId));
  }

  /**
   * verifyChecksums parity on the load branch: after an extend with
   * {@code verifyChecksums=true}, a second {@code loadOrAdd} on the same index with
   * {@code verifyChecksums=true} must take the load branch — observe the already-installed
   * entry by identity and return without re-extending. The in-memory engine has no on-disk
   * checksum to verify, so honouring the flag on this branch would be a regression
   * (silent corruption or unexpected throw); pin the documented "flag is ignored" semantics
   * on the load branch the existing {@code verifyChecksumsTrueIsIgnoredOnInMemoryEngine}
   * test only covers on the extend branch.
   */
  @Test
  public void verifyChecksumsTrueIsIgnoredOnLoadBranch() {
    final var first = cache.loadOrAdd(fileId, 0L, /* verifyChecksums= */ true);
    final CachePointer second;
    try {
      assertEquals(
          "verifyChecksums=true on extend must still advance the watermark to 1",
          1L,
          cache.getFilledUpTo(fileId));
      second = cache.loadOrAdd(fileId, 0L, /* verifyChecksums= */ true);
    } finally {
      first.decrementReadersReferrer();
    }
    try {
      assertSame(
          "load branch with verifyChecksums=true must observe the previously-installed "
              + "page; identity equality guarantees the flag did not trigger a re-extend "
              + "or a silent install",
          first,
          second);
      assertEquals(
          "load branch must not flip the LSN on a verifyChecksums=true probe",
          new LogSequenceNumber(-1, -1),
          DurablePage.getLogSequenceNumberFromPage(second.getBuffer()));
    } finally {
      second.decrementReadersReferrer();
    }
    assertEquals(
        "verifyChecksums=true on the load branch must not advance the watermark further",
        1L,
        cache.getFilledUpTo(fileId));
  }

  /**
   * verifyChecksums parity on the gap-fill branch: calling {@code loadOrAdd(fileId, 5,
   * true)} on a fresh file must take the gap-fill branch identically to the
   * {@code verifyChecksums=false} sibling test ({@link
   * #gapFillBranchAllocatesEntireGapAndReturnsTargetPointer}). Same watermark advance,
   * same magic-stamped LSN on every gap page, no exception. A regression that started
   * honouring the flag on the gap-fill branch — for example by throwing on the
   * intermediate pages or by short-circuiting the loop — would surface here.
   */
  @Test
  public void verifyChecksumsTrueIsIgnoredOnGapFillBranch() {
    assertEquals("fresh file must start at 0 pages", 0L, cache.getFilledUpTo(fileId));

    final var target = cache.loadOrAdd(fileId, 5L, /* verifyChecksums= */ true);
    try {
      assertNotNull(
          "verifyChecksums=true must not change the gap-fill totality contract", target);
      assertEquals(
          "gap-fill with verifyChecksums=true must return the target index",
          5,
          target.getPageIndex());
      assertEquals(
          "gap-fill must stamp the target with LSN(-1,-1) regardless of the flag",
          new LogSequenceNumber(-1, -1),
          DurablePage.getLogSequenceNumberFromPage(target.getBuffer()));
    } finally {
      target.decrementReadersReferrer();
    }
    assertEquals(
        "verifyChecksums=true on gap-fill must advance the watermark to pageIndex + 1",
        6L,
        cache.getFilledUpTo(fileId));

    // Each gap page must be subsequently observable: a load-branch probe with
    // verifyChecksums=true must return a non-null pointer for every index in [0, 4] with
    // the magic-stamped LSN intact. A regression that wrote a non-magic LSN on
    // intermediate gap pages under verifyChecksums=true would surface here.
    for (int i = 0; i < 5; i++) {
      final var gapPointer = cache.loadOrAdd(fileId, i, /* verifyChecksums= */ true);
      try {
        assertNotNull(
            "verifyChecksums=true gap page " + i + " must be installed and observable",
            gapPointer);
        assertEquals(
            "verifyChecksums=true gap page " + i + " must carry LSN(-1,-1)",
            new LogSequenceNumber(-1, -1),
            DurablePage.getLogSequenceNumberFromPage(gapPointer.getBuffer()));
      } finally {
        gapPointer.decrementReadersReferrer();
      }
    }
    assertEquals(
        "verifyChecksums=true load-branch probes on gap pages must not advance the watermark",
        6L,
        cache.getFilledUpTo(fileId));
  }

  /**
   * {@code loadIfPresent} smoke contract: the in-memory engine deliberately does NOT
   * implement {@code loadIfPresent} — the read-cache surface ({@link
   * DirectMemoryOnlyDiskCache#loadForRead}) probes the {@link MemoryFile} map directly and
   * never delegates to a {@code WriteCache.loadIfPresent} call. Any future caller that
   * wires the in-memory engine into a code path that expects the silent-probe primitive
   * must surface the unwired call site as an {@link UnsupportedOperationException} rather
   * than silently returning {@code null} (which would mis-classify "page absent" as
   * "page absent on disk but already cached"). This test pins the documented contract so a
   * regression that returned {@code null} (or threw a different exception type) would fail
   * loudly here; the disk-engine parallel lives in {@code WOWCacheLoadIfPresentTest}.
   */
  @Test
  public void loadIfPresentThrowsUnsupportedOperationException() {
    assertThrows(
        "loadIfPresent on the in-memory engine must surface UnsupportedOperationException "
            + "as a fail-fast signal for an unwired caller",
        UnsupportedOperationException.class,
        () -> cache.loadIfPresent(fileId, 0L, /* verifyChecksums= */ false));
    // verifyChecksums=true must surface the same contract — the throw is unconditional and
    // happens before the flag is read.
    assertThrows(
        "loadIfPresent with verifyChecksums=true must surface UOE identically",
        UnsupportedOperationException.class,
        () -> cache.loadIfPresent(fileId, 0L, /* verifyChecksums= */ true));
  }

  /**
   * Cross-file isolation: a {@code loadOrAdd} against fileA must not install pages in fileB.
   * A regression that stored the per-file map at the cache level (instead of per
   * {@code MemoryFile}) would silently bleed pages between files and corrupt every caller
   * that distinguishes "this file has N pages" from "another file has M pages". This test
   * pins the per-file ownership contract.
   */
  @Test
  public void loadOrAddIsScopedToTheTargetFileOnly() {
    final var fileIdB = cache.addFile("loadOrAdd-other.dat");
    final var pA = cache.loadOrAdd(fileId, 7L, false);
    try {
      assertEquals(
          "fileA must extend to 8 pages after a gap-fill targeting index 7",
          8L,
          cache.getFilledUpTo(fileId));
      assertEquals(
          "fileB must remain at 0 pages — gap-fill on fileA must not touch fileB",
          0L,
          cache.getFilledUpTo(fileIdB));
    } finally {
      pA.decrementReadersReferrer();
    }
  }

  /**
   * Concurrent installers on the same {@code (fileId, pageIndex)}: N threads call
   * {@code loadOrAdd} on a single freshly-opened file, all targeting the same page index.
   * Exactly one entry must end up in the per-file map (verified by all returned pointers
   * being identity-equal and the watermark being 1). This is the install-then-publish
   * atomicity contract from the Phase A review note 5: only one thread wins the publish
   * race; the others observe the already-published entry and the loser threads release
   * their freshly-acquired pageFrames back to the pool inside {@code installEmptyPage}.
   *
   * <p>Without atomic install-then-publish, this test would fail intermittently with
   * either a duplicate-pointer return (two threads materialised pointers and both saw a
   * miss) or a torn entry (one thread saw the entry mid-construction).
   *
   * <p>The test uses {@code invokeAll} so every {@code Future} is in-hand before the
   * executor is shut down. A previous shape that pushed pointers into a list inside the
   * worker risked a race window where the {@code loadOrAdd} succeeded (incrementing the
   * readers referrer) but the worker was cancelled before the list-add ran — that would
   * leak a pointer into the process-wide {@code pageFramePool}.
   */
  @Test
  public void concurrentLoadOrAddOnSameIndexInstallsExactlyOneEntry() throws Exception {
    final int threads = 16;
    final var pool = Executors.newFixedThreadPool(threads);
    try {
      final var startGate = new CountDownLatch(1);
      // Submit tasks BEFORE releasing the latch so workers actually park on
      // startGate.await() and the publish race is genuinely contended. A previous shape
      // counted the latch down before invokeAll, which let workers ramp up sequentially
      // and drained most of the contention the test claims to exercise.
      final List<Future<CachePointer>> futures = new ArrayList<>(threads);
      for (int i = 0; i < threads; i++) {
        futures.add(
            pool.submit(
                (Callable<CachePointer>) () -> {
                  startGate.await();
                  return cache.loadOrAdd(fileId, 0L, false);
                }));
      }
      startGate.countDown();

      final List<CachePointer> returned = new ArrayList<>(threads);
      try {
        for (final var f : futures) {
          // Each Future is in-hand by virtue of pool.submit's contract — fetching the
          // value here can throw if the worker raised, in which case fail explicitly so
          // the executor's shutdownNow does not silently swallow the exception.
          returned.add(f.get(10, TimeUnit.SECONDS));
        }
        assertEquals("every thread must receive a non-null pointer", threads, returned.size());
        // Identity equality across all returned pointers proves only one entry was
        // installed: the publish race elected exactly one winner even though N threads
        // raced.
        final var first = returned.get(0);
        final Set<CachePointer> distinct = new HashSet<>();
        for (final var p : returned) {
          distinct.add(p);
          assertSame(
              "every concurrent loadOrAdd on the same index must return the same instance",
              first,
              p);
        }
        assertEquals(
            "exactly one CachePointer instance must be returned across all installers",
            1,
            distinct.size());
        assertEquals(
            "watermark must reflect a single installed page despite N concurrent installers",
            1L,
            cache.getFilledUpTo(fileId));
      } finally {
        for (final var p : returned) {
          p.decrementReadersReferrer();
        }
      }
    } finally {
      pool.shutdownNow();
      assertTrue(
          "executor must terminate cleanly", pool.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  /**
   * {@code framePool} leak accounting on the install-race loser side: under N-thread
   * contention on the same {@code (fileId, pageIndex)}, exactly one installer wins the
   * {@code putIfAbsent} race and (N-1) installers lose. Every loser MUST release its
   * freshly-acquired pageFrame back to the
   * {@link com.jetbrains.youtrackdb.internal.common.directmemory.PageFramePool} via
   * {@code cachePointer.decrementReferrer()} inside {@link MemoryFile#installEmptyPage}.
   * A regression that dropped the loser-side {@code decrementReferrer} would leak (N-1)
   * frames per call: the frames stay live, never return to the pool, and the
   * process-wide pool would slowly drain over time.
   *
   * <p><b>Why {@code getPoolSize()} alone is not enough.</b> The page-frame pool is a
   * JVM-singleton shared across every cache test in the same fork, so other tests in
   * this class (and tests run before this one in the same Surefire fork) may have left
   * the pool partially populated. Each call to {@code framePool.acquire} pops a frame
   * from the pool when available and only allocates fresh memory when the pool is empty.
   * A naive {@code poolSize-after-race vs poolSize-before-race} delta therefore depends
   * on how many of the {@code threads} acquires hit the pool vs allocated fresh — which
   * is non-deterministic and gives a moving target.
   *
   * <p><b>The right invariant.</b> Decompose every acquire into {@code freshAllocation}
   * (allocator hit, increments {@code memoryConsumption}) or {@code pooledAcquire}
   * (pool hit, no allocator change). Then:
   * <pre>
   *   totalAcquires      = threads
   *   freshAllocations   = (memAfter - memBefore) / pageSize     // from allocator delta
   *   pooledAcquires     = totalAcquires - freshAllocations
   *   poolNetDelta       = poolAfter - poolBefore                // observed
   *   releasesToPool     = poolNetDelta + pooledAcquires         // every pool pop must
   *                                                              // be matched by a push
   *                                                              // for the size to net out
   * </pre>
   * After the race (before the test releases the cache's referrer): every loser must
   * have released its frame, so {@code releasesToPool >= threads - 1}. After
   * {@code cache.deleteFile(fileId)}: the winner's frame is also released, so
   * {@code releasesToPool >= threads}.
   *
   * <p>The {@code >=} accommodates concurrent test infrastructure that may release
   * unrelated frames during the test, but the floor is exact and falsifiable: a
   * regression that drops the loser-side decrement would fail the first assertion
   * because {@code releasesToPool} would be 0 instead of {@code threads - 1}.
   */
  @Test
  public void framePoolLeakAccountingOnConcurrentInstallers() throws Exception {
    final int threads = 16;
    final var framePool = ByteBufferPool.instance(null).pageFramePool();
    final var allocator = DirectMemoryAllocator.instance();
    final int poolBefore = framePool.getPoolSize();
    final long memBefore = allocator.getMemoryConsumption();
    final var pool = Executors.newFixedThreadPool(threads);
    try {
      final var startGate = new CountDownLatch(1);
      final List<Future<CachePointer>> futures = new ArrayList<>(threads);
      for (int i = 0; i < threads; i++) {
        futures.add(
            pool.submit(
                (Callable<CachePointer>) () -> {
                  startGate.await();
                  return cache.loadOrAdd(fileId, 0L, false);
                }));
      }
      startGate.countDown();

      // Drain every Future before measuring pool state — Future.get is a happens-after
      // of the worker's loadOrAdd return, which is itself a happens-after of the
      // loser-side decrementReferrer call (the decrementReferrer happens inside
      // installEmptyPage, which is on the worker's call stack).
      final List<CachePointer> returned = new ArrayList<>(threads);
      try {
        for (final var f : futures) {
          returned.add(f.get(10, TimeUnit.SECONDS));
        }
        // Decompose the 16 acquires into "from pool" vs "fresh allocation" via the
        // allocator's memoryConsumption delta. PAGE_SIZE-aligned because every
        // acquire allocates exactly one page; a regression breaking this alignment
        // would surface as a divide-with-remainder, which the assertions below catch.
        final long memAfterRace = allocator.getMemoryConsumption();
        final long memDelta = memAfterRace - memBefore;
        assertEquals(
            "allocator delta must be a whole number of PAGE_SIZE allocations; got "
                + memDelta + " for pageSize=" + PAGE_SIZE,
            0L,
            memDelta % PAGE_SIZE);
        final long freshAllocations = memDelta / PAGE_SIZE;
        assertTrue(
            "fresh allocations cannot exceed total acquires (" + threads + "); got "
                + freshAllocations,
            freshAllocations >= 0 && freshAllocations <= threads);
        final long pooledAcquires = threads - freshAllocations;

        // (threads - 1) loser frames must have been released by now: the cache still
        // holds the winner's frame via the per-MemoryFile content map, but every
        // loser released its freshly-acquired frame back to the pool. A regression
        // dropping the loser-side decrementReferrer would leave releasesToPool == 0
        // here (the loser holds a live CachePointer that never enters the pool).
        final int poolAfterRace = framePool.getPoolSize();
        final long releasesToPoolAfterRace = poolAfterRace - poolBefore + pooledAcquires;
        assertTrue(
            "loser-side decrementReferrer must return (threads - 1) frames to the "
                + "pool; poolBefore=" + poolBefore + " poolAfterRace=" + poolAfterRace
                + " pooledAcquires=" + pooledAcquires + " freshAllocations="
                + freshAllocations + " releasesToPool=" + releasesToPoolAfterRace
                + " expected releasesToPool >= " + (threads - 1),
            releasesToPoolAfterRace >= threads - 1);
      } finally {
        for (final var p : returned) {
          p.decrementReadersReferrer();
        }
      }
    } finally {
      pool.shutdownNow();
      assertTrue(
          "executor must terminate cleanly", pool.awaitTermination(5, TimeUnit.SECONDS));
    }

    // After dropping the cache's reference via deleteFile, the winner's frame must
    // also return to the pool: total releasesToPool >= threads. deleteFile calls
    // MemoryFile.clear, which decrements every entry's referrer, which routes the
    // winner's frame back to the pool via CachePointer.decrementReferrer.
    cache.deleteFile(fileId);
    final long memAfterDelete = allocator.getMemoryConsumption();
    final long memDeltaDelete = memAfterDelete - memBefore;
    assertEquals(
        "post-delete allocator delta must remain PAGE_SIZE-aligned",
        0L,
        memDeltaDelete % PAGE_SIZE);
    final long freshAllocationsDelete = memDeltaDelete / PAGE_SIZE;
    // Note: the deleteFile path does not free direct memory (the pool retains the
    // frames), so freshAllocationsDelete may equal freshAllocations from earlier or
    // be larger if an excess-frame deallocation fired during release. Either way the
    // identity below holds: every pop must be matched by a push.
    final long pooledAcquiresDelete = threads - freshAllocationsDelete;
    final int poolAfterDelete = framePool.getPoolSize();
    final long releasesToPoolAfterDelete = poolAfterDelete - poolBefore + pooledAcquiresDelete;
    assertTrue(
        "cache.deleteFile must release the winner's frame; releasesToPool="
            + releasesToPoolAfterDelete + " (poolBefore=" + poolBefore
            + " poolAfterDelete=" + poolAfterDelete + " pooledAcquires="
            + pooledAcquiresDelete + ") expected >= " + threads,
        releasesToPoolAfterDelete >= threads);
  }

  /**
   * Stress harness for concurrent gap-fills with overlapping ranges across multiple
   * iterations. Each iteration opens a fresh file, fans out 6 worker threads with
   * overlapping target indices ({@code {3, 5, 7, 9, 11, 4}}), waits on a start gate, then
   * verifies that every index in {@code [0, 11]} has exactly one entry — every probe pair
   * returns the same {@link CachePointer} instance. The iteration cleans up the freshly-
   * added file via {@code deleteFile} so each pass exercises a fresh
   * {@link MemoryFile#loadOrAddPage} state machine.
   *
   * <p>Without the {@code putIfAbsent}-with-release-on-loss pattern in
   * {@link MemoryFile#installEmptyPage}, two threads racing on the same mid-gap index
   * could both pass the {@code computeIfAbsent} miss check and both materialise a
   * {@code CachePointer} (incrementing the referrer count of each pageFrame to 1) — only
   * one would publish, but the other's frame would leak into the process-wide
   * {@code pageFramePool}. The probe-pair {@code assertSame} catches the duplicate-publish
   * shape; running 60 iterations with overlapping targets stresses the install race far
   * harder than the prior 2-thread shape.
   */
  @Test
  public void concurrentGapFillsConvergeWithoutDoubleInstall() throws Exception {
    final int iterations = 60;
    final long[] targets = {3L, 5L, 7L, 9L, 11L, 4L};
    final long maxTarget = 11L;
    final ExecutorService pool = Executors.newFixedThreadPool(targets.length);
    try {
      for (int it = 0; it < iterations; it++) {
        final long iterFileId = cache.addFile("concurrent-gapfill-" + it + ".dat");
        try {
          final var startGate = new CountDownLatch(1);
          // Submit tasks BEFORE releasing the latch so workers actually park on
          // startGate.await() and the install race on overlapping indices is genuinely
          // contended (see same-index test above for the rationale).
          final List<Future<CachePointer>> futures = new ArrayList<>(targets.length);
          for (final long target : targets) {
            futures.add(
                pool.submit(
                    (Callable<CachePointer>) () -> {
                      startGate.await();
                      return cache.loadOrAdd(iterFileId, target, false);
                    }));
          }
          startGate.countDown();
          final List<CachePointer> workerPointers = new ArrayList<>(targets.length);
          try {
            for (final var f : futures) {
              workerPointers.add(f.get(10, TimeUnit.SECONDS));
            }
            assertEquals(
                "iteration " + it + ": watermark must converge to maxTarget + 1",
                maxTarget + 1,
                cache.getFilledUpTo(iterFileId));
            // Every index in [0, maxTarget] must be installed exactly once. Two probes
            // back-to-back must return the same instance — the load-branch identity check
            // is the cheap proof that no double-install happened on a contended index.
            for (long i = 0; i <= maxTarget; i++) {
              final var probe1 = cache.loadOrAdd(iterFileId, i, false);
              final var probe2 = cache.loadOrAdd(iterFileId, i, false);
              try {
                assertSame(
                    "iteration " + it + ", page " + i + ": double-install detected — "
                        + "back-to-back load probes must return the same instance",
                    probe1,
                    probe2);
              } finally {
                probe1.decrementReadersReferrer();
                probe2.decrementReadersReferrer();
              }
            }
          } finally {
            for (final var p : workerPointers) {
              p.decrementReadersReferrer();
            }
          }
        } finally {
          // Free the freshly-added file before the next iteration; deleteFile drops every
          // installed page and removes the file from the metadata maps.
          cache.deleteFile(iterFileId);
        }
      }
    } finally {
      pool.shutdownNow();
      assertTrue(
          "executor must terminate cleanly", pool.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  /**
   * Race between concurrent installers and a destructive {@code deleteFile} loop. N
   * installer threads loop calling {@code loadOrAdd} on the same {@code (fileId, 0)}
   * (each call decrements the readers referrer immediately on success), while one
   * destroyer thread loops {@code cache.deleteFile(...)} then re-adds the file under the
   * same name (rotating the int fileId on each cycle). The installers must never see an
   * exception other than {@link IllegalArgumentException} (raised when the file was
   * deleted between the metadata probe and the per-{@link MemoryFile} install). At the
   * end, the cache must be in a consistent state — the most recently-added file responds
   * correctly to {@code loadOrAdd}.
   *
   * <p>This test pins the {@code clearLock.readLock} discipline introduced by the SF1
   * fix: bumping the readers-referrer must happen INSIDE
   * {@link MemoryFile#loadOrAddPage} while the readLock is held, so a concurrent
   * {@link MemoryFile#clear()} cannot drop the in-cache referrer to zero (recycling the
   * frame to the pool) between publication and the increment. A regression that hoisted
   * the increment back outside the read-lock would surface here as either an
   * {@code IllegalStateException} ("Invalid direct memory state, number of readers
   * cannot be zero") or as a use-after-free on the recycled frame.
   */
  @Test
  public void clearAndLoadOrAddRaceLeavesCacheConsistent() throws Exception {
    final int installerThreads = 4;
    final int rotations = 50;
    final var pool = Executors.newFixedThreadPool(installerThreads + 1);
    try {
      final var stop = new AtomicBoolean(false);
      final var fileIdRef = new AtomicReference<>(fileId);
      final var unexpected = new ConcurrentLinkedQueue<Throwable>();
      final var startGate = new CountDownLatch(1);
      final var installerDone = new CountDownLatch(installerThreads);
      // Counts every successful loadOrAdd by the installers. Without this counter, a
      // destroyer that finishes before installers exit startGate.await() could let the
      // test pass vacuously — the assertion below pins a realistic floor.
      final var iterationCounter = new AtomicLong();

      // Installers: loop loadOrAdd / decrementReadersReferrer until the destroyer signals
      // stop. IAE on a deleted file is tolerated (the file may have been dropped between
      // our metadata read and our install); any other exception fails the test.
      for (int t = 0; t < installerThreads; t++) {
        pool.submit(
            () -> {
              try {
                startGate.await();
                while (!stop.get()) {
                  try {
                    final var p = cache.loadOrAdd(fileIdRef.get(), 0L, false);
                    iterationCounter.incrementAndGet();
                    p.decrementReadersReferrer();
                  } catch (final IllegalArgumentException ignored) {
                    // Tolerated: file was deleted between fileIdRef read and the install.
                  }
                }
              } catch (final Throwable t1) {
                unexpected.add(t1);
              } finally {
                installerDone.countDown();
              }
            });
      }

      // Destroyer: rotate deleteFile + addFile a fixed number of times. After the last
      // rotation the new fileId is the visible one; installers exit on stop.
      pool.submit(
          () -> {
            try {
              startGate.await();
              for (int r = 0; r < rotations; r++) {
                final var existing = fileIdRef.get();
                cache.deleteFile(existing);
                final var fresh = cache.addFile(FILE_NAME + "-rot-" + r);
                fileIdRef.set(fresh);
              }
            } catch (final Throwable t1) {
              unexpected.add(t1);
            } finally {
              stop.set(true);
            }
          });

      startGate.countDown();
      assertTrue(
          "installer threads must finish within the bounded wait window",
          installerDone.await(20, TimeUnit.SECONDS));
      assertTrue(
          "installers must execute the loadOrAdd loop body at least once per thread; "
              + "vacuous pass detected (iterations=" + iterationCounter.get() + ")",
          iterationCounter.get() >= installerThreads);

      if (!unexpected.isEmpty()) {
        final var first = unexpected.poll();
        fail("clear/loadOrAdd race surfaced unexpected exception: " + first);
      }

      // Final consistency probe: the surviving fileId must accept loadOrAdd cleanly.
      final var surviving = cache.loadOrAdd(fileIdRef.get(), 0L, false);
      try {
        assertNotNull("surviving file must accept loadOrAdd after the race", surviving);
        assertEquals(
            "surviving file's high-watermark must reflect the final extend",
            1L,
            cache.getFilledUpTo(fileIdRef.get()));
      } finally {
        surviving.decrementReadersReferrer();
      }
    } finally {
      pool.shutdownNow();
      assertTrue(
          "executor must terminate cleanly", pool.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  /**
   * Sibling of {@link #clearAndLoadOrAddRaceLeavesCacheConsistent} that uses
   * {@link DirectMemoryOnlyDiskCache#truncateFile(long)} instead of
   * {@code deleteFile + addFile}. {@code truncateFile} keeps the SAME {@code MemoryFile}
   * instance across rotations, so the {@code clearLock} read/write discipline is the
   * actual synchronization point being exercised — the {@code deleteFile + addFile}
   * shape always trips the {@code IllegalArgumentException} guard at the outer
   * {@code DirectMemoryOnlyDiskCache.loadOrAdd} entry on stale fileIds and never enters
   * the per-{@link MemoryFile} install path under the readLock window.
   *
   * <p>The fileId stays valid throughout, so installers never see
   * {@link IllegalArgumentException}; any exception fails the test. Like the sibling,
   * the {@code iterationCounter} pin guards against a vacuous pass where the destroyer
   * finishes before installers ramp up.
   */
  @Test
  public void truncateAndLoadOrAddRaceLeavesCacheConsistent() throws Exception {
    final int installerThreads = 4;
    final int rotations = 100;
    final var pool = Executors.newFixedThreadPool(installerThreads + 1);
    try {
      final var stop = new AtomicBoolean(false);
      final var unexpected = new ConcurrentLinkedQueue<Throwable>();
      final var startGate = new CountDownLatch(1);
      final var installerDone = new CountDownLatch(installerThreads);
      final var iterationCounter = new AtomicLong();

      // Installers: loop loadOrAdd / decrementReadersReferrer until the destroyer signals
      // stop. truncateFile keeps the SAME MemoryFile instance across rotations, so the
      // fileId stays valid — any exception fails the test.
      for (int t = 0; t < installerThreads; t++) {
        pool.submit(
            () -> {
              try {
                startGate.await();
                while (!stop.get()) {
                  final var p = cache.loadOrAdd(fileId, 0L, false);
                  iterationCounter.incrementAndGet();
                  p.decrementReadersReferrer();
                }
              } catch (final Throwable t1) {
                unexpected.add(t1);
              } finally {
                installerDone.countDown();
              }
            });
      }

      // Destroyer: rotate truncateFile a fixed number of times against the same fileId.
      pool.submit(
          () -> {
            try {
              startGate.await();
              for (int r = 0; r < rotations; r++) {
                cache.truncateFile(fileId);
              }
            } catch (final Throwable t1) {
              unexpected.add(t1);
            } finally {
              stop.set(true);
            }
          });

      startGate.countDown();
      assertTrue(
          "installer threads must finish within the bounded wait window",
          installerDone.await(30, TimeUnit.SECONDS));
      assertTrue(
          "installers must execute the loadOrAdd loop body at least once per thread; "
              + "vacuous pass detected (iterations=" + iterationCounter.get() + ")",
          iterationCounter.get() >= installerThreads);

      if (!unexpected.isEmpty()) {
        final var first = unexpected.poll();
        fail("truncate/loadOrAdd race surfaced unexpected exception: " + first);
      }

      // Final consistency probe: the same fileId must still accept loadOrAdd cleanly.
      final var surviving = cache.loadOrAdd(fileId, 0L, false);
      try {
        assertNotNull("surviving file must accept loadOrAdd after the race", surviving);
        assertEquals(
            "surviving file's high-watermark must reflect the final extend",
            1L,
            cache.getFilledUpTo(fileId));
      } finally {
        surviving.decrementReadersReferrer();
      }
    } finally {
      pool.shutdownNow();
      assertTrue(
          "executor must terminate cleanly", pool.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  /**
   * Asserts the page region beyond {@link DurablePage#NEXT_FREE_POSITION} is zero-filled
   * — the contract of a freshly-acquired pageFrame from the zero-fill pool. The first 24
   * bytes (magic / crc32 / LSN) are intentionally not checked here because the LSN field
   * is overwritten with the magic-stamp value before publication; callers that want the
   * full-page zero check should test the buffer before publication, which is unreachable
   * from a black-box test.
   */
  private static void assertPostLsnRegionIsZeroFilled(final ByteBuffer buffer) {
    final int from = DurablePage.NEXT_FREE_POSITION;
    final int len = buffer.capacity() - from;
    final byte[] actual = new byte[len];
    final var view = buffer.duplicate();
    view.position(from);
    view.get(actual);
    final byte[] expected = new byte[len];
    assertArrayEquals(
        "post-LSN page region must be zero-filled (regression catches a flipped "
            + "framePool.acquire(true, ...) zero-fill flag)",
        expected,
        actual);
  }
}
