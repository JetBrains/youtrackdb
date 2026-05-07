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

package com.jetbrains.youtrackdb.internal.core.storage.memory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.internal.core.storage.cache.CachePointer;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
   * {@link CachePointer} with a clean buffer stamped {@code LSN(-1, -1)}. Mirrors the
   * disk engine's extend-branch contract on the in-memory engine.
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
   * loop runs exactly twice (over pages 1 and 2). Verifies the inclusive
   * {@code [currentSize, pageIndex]} range against an off-by-one bug at the boundary
   * (a smaller test than the wide gap-fill above so an off-by-one in either direction
   * shows up clearly).
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
   * Concurrent installers on the same {@code (fileId, pageIndex)}: N threads call
   * {@code loadOrAdd} on a single freshly-opened file, all targeting the same page index.
   * Exactly one entry must end up in the per-file map (verified by all returned pointers
   * being identity-equal and the watermark being 1). This is the install-then-publish
   * atomicity contract from the Phase A review note 5: only one thread wins the
   * {@code computeIfAbsent} race; the others observe the already-published entry.
   *
   * <p>Without atomic install-then-publish, this test would fail intermittently with
   * either a duplicate-pointer return (two threads materialised pointers and both saw a
   * miss) or a torn entry (one thread saw the entry mid-construction).
   */
  @Test
  public void concurrentLoadOrAddOnSameIndexInstallsExactlyOneEntry() throws Exception {
    final int threads = 16;
    final var pool = Executors.newFixedThreadPool(threads);
    try {
      final var startGate = new CountDownLatch(1);
      final var doneGate = new CountDownLatch(threads);
      final List<CachePointer> returned = new ArrayList<>(threads);
      final var failure = new AtomicReference<Throwable>();
      for (int i = 0; i < threads; i++) {
        pool.submit(
            () -> {
              try {
                startGate.await();
                final var p = cache.loadOrAdd(fileId, 0L, false);
                synchronized (returned) {
                  returned.add(p);
                }
              } catch (final Throwable t) {
                failure.compareAndSet(null, t);
              } finally {
                doneGate.countDown();
              }
            });
      }
      startGate.countDown();
      assertTrue(
          "all installers must finish within the bounded wait window",
          doneGate.await(10, TimeUnit.SECONDS));
      if (failure.get() != null) {
        fail("concurrent loadOrAdd surfaced unexpected exception: " + failure.get());
      }

      try {
        assertEquals("every thread must receive a non-null pointer", threads, returned.size());
        // Identity equality across all returned pointers proves only one entry was installed:
        // computeIfAbsent's mapping function ran exactly once even though N threads raced.
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
   * Concurrent installers on distinct gap pages: thread A targets {@code pageIndex == 5}
   * (forces gap-fill of pages 0..5), thread B targets {@code pageIndex == 3} (potential
   * mid-gap install). The two operations must converge to a consistent state: every
   * index in 0..5 has exactly one installed entry, and the watermark is 6 — proving that
   * concurrent gap-fills do not double-install on a contended index. Without atomic
   * {@code computeIfAbsent}, thread B could install a fresh entry over the one thread A
   * was about to publish, yielding two pointers for index 3 (the second one would leak
   * its referrer count).
   */
  @Test
  public void concurrentGapFillsConvergeWithoutDoubleInstall() throws Exception {
    final var pool = Executors.newFixedThreadPool(2);
    try {
      final var startGate = new CountDownLatch(1);
      final var doneGate = new CountDownLatch(2);
      final var pointersByIndex = new ConcurrentHashMap<Long, Set<CachePointer>>();
      final var failure = new AtomicReference<Throwable>();
      for (final long target : new long[] {5L, 3L}) {
        pool.submit(
            () -> {
              try {
                startGate.await();
                final var p = cache.loadOrAdd(fileId, target, false);
                pointersByIndex
                    .computeIfAbsent(target, k -> ConcurrentHashMap.newKeySet())
                    .add(p);
              } catch (final Throwable t) {
                failure.compareAndSet(null, t);
              } finally {
                doneGate.countDown();
              }
            });
      }
      startGate.countDown();
      assertTrue(
          "both gap-fill threads must finish within the bounded wait window",
          doneGate.await(10, TimeUnit.SECONDS));
      if (failure.get() != null) {
        fail("concurrent gap-fill surfaced unexpected exception: " + failure.get());
      }

      // After both calls return, the watermark must be 6 (high water = max(5, 3) + 1).
      // Lower target's gap-fill could install pages 0..3 and the higher target's call
      // installs whichever pages are still missing in 0..5 — convergence is order-
      // independent because computeIfAbsent is atomic per key.
      assertEquals(
          "watermark must converge to max-target + 1 regardless of thread interleaving",
          6L,
          cache.getFilledUpTo(fileId));

      // Each index 0..5 must have exactly one entry. Probe via a load-branch loadOrAdd
      // (which returns the installed pointer); the returned pointer must be stable across
      // multiple probes — proving no torn / duplicate install on any contended index.
      try {
        for (int i = 0; i < 6; i++) {
          final var probe1 = cache.loadOrAdd(fileId, i, false);
          final var probe2 = cache.loadOrAdd(fileId, i, false);
          try {
            assertSame(
                "page " + i + " must be installed exactly once (load-branch identity check)",
                probe1,
                probe2);
          } finally {
            probe1.decrementReadersReferrer();
            probe2.decrementReadersReferrer();
          }
        }
      } finally {
        for (final var s : pointersByIndex.values()) {
          for (final var p : s) {
            p.decrementReadersReferrer();
          }
        }
      }
    } finally {
      pool.shutdownNow();
      assertTrue(
          "executor must terminate cleanly", pool.awaitTermination(5, TimeUnit.SECONDS));
    }
  }
}
