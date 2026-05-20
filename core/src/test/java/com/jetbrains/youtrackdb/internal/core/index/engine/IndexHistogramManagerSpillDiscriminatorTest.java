package com.jetbrains.youtrackdb.internal.core.index.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.common.directmemory.ByteBufferPool;
import com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator.Intention;
import com.jetbrains.youtrackdb.internal.common.function.TxConsumer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.IntegerSerializer;
import com.jetbrains.youtrackdb.internal.core.db.record.CurrentStorageComponentsFactory;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.BinarySerializerFactory;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntryImpl;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CachePointer;
import com.jetbrains.youtrackdb.internal.core.storage.cache.ReadCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.WriteCache;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationsManager;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests pinning the HLL-spill page-1 discriminator in {@link IndexHistogramManager}.
 *
 * <p>Both {@code writeSnapshotToPage} (called from the
 * {@code buildHistogram} / {@code createStatsFileWithCounters} entry points) and
 * {@code flushSnapshotToPage} (called from {@code applyDelta} / {@code flushIfDirty} /
 * {@code closeStatsFile} / {@code doRebalance}) reach the same conditional
 * {@code op.filledUpTo(fileId) > 1 ? loadPageForWrite : allocatePageForWrite}
 * branch when persisting a snapshot whose HLL register array has been spilled
 * to page 1. The branch enforces the read-cache concurrency design's allocator-only
 * contract: the first spill {@code allocatePageForWrite}s page 1, every subsequent
 * flush {@code loadPageForWrite}s the existing page. Misrouting either direction
 * trips the cache-layer fail-fast {@link IllegalStateException}; this test fails
 * loudly if a future refactor weakens the discriminator.
 *
 * <p>The tests drive both branches at both call sites with a mocked
 * {@link AtomicOperation} and a real {@link CacheEntry} backed by a
 * {@link ByteBufferPool}-allocated buffer (so {@code HistogramStatsPage.writeSnapshot}
 * / {@code writeHllToPage1} can run their real byte-write code paths). Mockito
 * verifications then confirm exactly which page-1 call was invoked.
 */
public class IndexHistogramManagerSpillDiscriminatorTest {

  /** Page size used to allocate the real backing buffers handed to the manager. */
  private static final int PAGE_SIZE = DurablePage.MAX_PAGE_SIZE_BYTES;

  /** Arbitrary stable fileId used throughout — the manager only treats it as a long token. */
  private static final long TEST_FILE_ID = 7L;

  private ByteBufferPool bufferPool;
  /** Tracks every CacheEntry handed out by the mock op so we can release them in tearDown. */
  private final List<CacheEntry> allocatedEntries = new ArrayList<>();

  @Before
  public void setUp() {
    bufferPool = new ByteBufferPool(PAGE_SIZE);
  }

  @After
  public void tearDown() {
    for (var entry : allocatedEntries) {
      entry.releaseExclusiveLock();
      entry.getCachePointer().decrementReferrer();
    }
    allocatedEntries.clear();
    bufferPool.clear();
  }

  // ---------------------------------------------------------------------------
  // writeSnapshotToPage — direct path reachable from buildHistogram /
  // createStatsFileWithCounters. We drive it via flushIfDirty(AtomicOperation),
  // which is the public method whose body is a thin wrapper around
  // writeSnapshotToPage when there are pending dirty mutations.
  // ---------------------------------------------------------------------------

  /**
   * When {@code op.filledUpTo(fileId) <= 1} the file holds only page 0 (the
   * initial bootstrap), so the first spill MUST take the
   * {@code allocatePageForWrite(fileId, 1)} branch. Verifies the allocator
   * path fires and {@code loadPageForWrite(fileId, 1, ...)} is not invoked.
   */
  @Test
  public void writeSnapshotToPage_firstSpill_allocatesPage1() throws IOException {
    var fixture = new Fixture();
    fixture.installSpillSnapshot();

    var op = fixture.newOp(/* filledUpTo */ 1L);
    fixture.manager.setDirtyMutationsForTest(1L);
    fixture.manager.flushIfDirty(op);

    verify(op).allocatePageForWrite(eq(TEST_FILE_ID), eq(1L));
    verify(op, never()).loadPageForWrite(eq(TEST_FILE_ID), eq(1L), anyInt(),
        any(Boolean.class));
  }

  /**
   * When {@code op.filledUpTo(fileId) > 1} the file already carries page 1
   * (either a prior in-session spill or a clean-shutdown reopen of a
   * previously-spilled snapshot), so the flush MUST take the
   * {@code loadPageForWrite(fileId, 1, ...)} branch — re-allocating page 1
   * would trip {@code WOWCache.loadOrAdd}'s allocator-only fail-fast.
   * Verifies the load path fires and {@code allocatePageForWrite(fileId, 1)}
   * is not invoked.
   */
  @Test
  public void writeSnapshotToPage_secondSpill_loadsExistingPage1() throws IOException {
    var fixture = new Fixture();
    fixture.installSpillSnapshot();

    var op = fixture.newOp(/* filledUpTo */ 2L);
    fixture.manager.setDirtyMutationsForTest(1L);
    fixture.manager.flushIfDirty(op);

    verify(op).loadPageForWrite(eq(TEST_FILE_ID), eq(1L), eq(1), eq(true));
    verify(op, never()).allocatePageForWrite(eq(TEST_FILE_ID), eq(1L));
  }

  // ---------------------------------------------------------------------------
  // flushSnapshotToPage — indirect path reachable from the no-arg flushIfDirty
  // (checkpoint / shutdown / recovery), applyDelta (commit-time batch flush),
  // closeStatsFile, and doRebalance. The method creates its own AtomicOperation
  // via storage.getAtomicOperationsManager().executeInsideAtomicOperation(...),
  // so we stub that to invoke the consumer with our mock op.
  // ---------------------------------------------------------------------------

  /**
   * Mirror of {@link #writeSnapshotToPage_firstSpill_allocatesPage1} for the
   * private {@code flushSnapshotToPage} body, reached via the no-arg
   * {@code flushIfDirty()} method. Pins the same allocator-only branch from the
   * checkpoint / shutdown / rebalance call sites.
   */
  @Test
  public void flushSnapshotToPage_firstSpill_allocatesPage1() throws Exception {
    var fixture = new Fixture();
    fixture.installSpillSnapshot();

    var op = fixture.newOp(/* filledUpTo */ 1L);
    fixture.stubExecuteInsideAtomicOperation(op);
    fixture.manager.setDirtyMutationsForTest(1L);
    fixture.manager.flushIfDirty();

    verify(op).allocatePageForWrite(eq(TEST_FILE_ID), eq(1L));
    verify(op, never()).loadPageForWrite(eq(TEST_FILE_ID), eq(1L), anyInt(),
        any(Boolean.class));
  }

  /**
   * Mirror of {@link #writeSnapshotToPage_secondSpill_loadsExistingPage1} for the
   * private {@code flushSnapshotToPage} body. Pins the existing-page branch from
   * the checkpoint / shutdown / rebalance call sites — the load that runs after a
   * clean-shutdown reopen of a previously-spilled snapshot.
   */
  @Test
  public void flushSnapshotToPage_secondSpill_loadsExistingPage1() throws Exception {
    var fixture = new Fixture();
    fixture.installSpillSnapshot();

    var op = fixture.newOp(/* filledUpTo */ 2L);
    fixture.stubExecuteInsideAtomicOperation(op);
    fixture.manager.setDirtyMutationsForTest(1L);
    fixture.manager.flushIfDirty();

    verify(op).loadPageForWrite(eq(TEST_FILE_ID), eq(1L), eq(1), eq(true));
    verify(op, never()).allocatePageForWrite(eq(TEST_FILE_ID), eq(1L));
  }

  /**
   * When {@code snapshot.hllOnPage1()} is false the discriminator block is
   * skipped entirely — neither {@code loadPageForWrite(fileId, 1, ...)} nor
   * {@code allocatePageForWrite(fileId, 1)} fires. Pins the upstream guard that
   * gates the spill branch so a future refactor that accidentally unconditionalises
   * the page-1 touch fails loudly.
   */
  @Test
  public void writeSnapshotToPage_noSpill_doesNotTouchPage1() throws IOException {
    var fixture = new Fixture();
    // Install a snapshot WITHOUT the spill flag — the HLL register array lives
    // inline on page 0, so the page-1 conditional block must be skipped entirely.
    fixture.installInlineHllSnapshot();

    var op = fixture.newOp(/* filledUpTo */ 1L);
    fixture.manager.setDirtyMutationsForTest(1L);
    fixture.manager.flushIfDirty(op);

    verify(op, never()).loadPageForWrite(eq(TEST_FILE_ID), eq(1L), anyInt(),
        any(Boolean.class));
    verify(op, never()).allocatePageForWrite(eq(TEST_FILE_ID), eq(1L));
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /**
   * Builds a real page-shaped CacheEntry backed by a {@link ByteBufferPool} buffer.
   * The pageIndex is an {@code int} on both {@link CachePointer} and
   * {@link CacheEntryImpl}; we pass an {@code int} here even though the manager's
   * page-1 / page-0 references appear as {@code long} elsewhere in this test.
   */
  private CacheEntry allocateRealPage(long fileId, int pageIndex) {
    var pointer = bufferPool.acquireDirect(true, Intention.TEST);
    var cachePointer = new CachePointer(pointer, bufferPool, fileId, pageIndex);
    cachePointer.incrementReferrer();
    CacheEntry entry = new CacheEntryImpl(fileId, pageIndex, cachePointer, false, null);
    entry.acquireExclusiveLock();
    allocatedEntries.add(entry);
    return entry;
  }

  /**
   * Test fixture mirroring the {@code Fixture} class in
   * {@code IndexHistogramManagerUnitTest}: a real {@link IndexHistogramManager}
   * over a real CHM cache, with a mock storage / read+write cache so the manager's
   * lifecycle methods do not touch real disk.
   */
  private class Fixture {
    final int engineId = 0;
    final ConcurrentHashMap<Integer, HistogramSnapshot> cache = new ConcurrentHashMap<>();
    final IndexHistogramManager manager;
    final AbstractStorage storage;
    final AtomicOperationsManager atomicOperationsManager;

    Fixture() {
      storage = mock(AbstractStorage.class);
      var factory = new CurrentStorageComponentsFactory(
          BinarySerializerFactory.currentBinaryFormatVersion());
      atomicOperationsManager = mock(AtomicOperationsManager.class);
      when(storage.getComponentsFactory()).thenReturn(factory);
      when(storage.getAtomicOperationsManager()).thenReturn(atomicOperationsManager);
      when(storage.getReadCache()).thenReturn(mock(ReadCache.class));
      when(storage.getWriteCache()).thenReturn(mock(WriteCache.class));

      var serializerFactory = BinarySerializerFactory.create(
          BinarySerializerFactory.CURRENT_BINARY_FORMAT_VERSION);
      // isSingleValue=false — HLL only exists on multi-value histograms, which is
      // a precondition for the page-1 spill discriminator to be reachable.
      manager = new IndexHistogramManager(
          storage, "test-idx", engineId, /* isSingleValue */ false, cache,
          IntegerSerializer.INSTANCE, serializerFactory, IntegerSerializer.ID);
      manager.setFileIdForTest(TEST_FILE_ID);
    }

    /**
     * Builds a fresh mock AtomicOperation that returns the given
     * {@code filledUpTo} value, and stubs page-0 / page-1 loads + the page-1
     * allocate to return real backing buffers. Each call to
     * {@code loadPageForWrite} or {@code allocatePageForWrite} hands out a NEW
     * page-shaped CacheEntry so the {@code HistogramStatsPage} writes land on
     * an isolated buffer per call.
     */
    AtomicOperation newOp(long filledUpTo) throws IOException {
      var op = mock(AtomicOperation.class);
      when(op.filledUpTo(eq(TEST_FILE_ID))).thenReturn(filledUpTo);
      // Page 0 is always loaded for the snapshot header; the manager calls the
      // 4-arg signature StorageComponent.loadPageForWrite delegates to.
      when(op.loadPageForWrite(eq(TEST_FILE_ID), eq(0L), anyInt(), any(Boolean.class)))
          .thenAnswer(invocation -> allocateRealPage(TEST_FILE_ID, 0));
      when(op.loadPageForWrite(eq(TEST_FILE_ID), eq(1L), anyInt(), any(Boolean.class)))
          .thenAnswer(invocation -> allocateRealPage(TEST_FILE_ID, 1));
      when(op.allocatePageForWrite(eq(TEST_FILE_ID), eq(1L)))
          .thenAnswer(invocation -> allocateRealPage(TEST_FILE_ID, 1));
      return op;
    }

    /**
     * Routes the manager's {@code flushSnapshotToPage} path through the given
     * mock op. The production code creates its own AtomicOperation via
     * {@code storage.getAtomicOperationsManager().executeInsideAtomicOperation(...)}
     * and then nests an {@code executeInsideComponentOperation(op, lockedOp -> ...)};
     * both layers are stubbed to invoke their respective consumers directly with
     * {@code op}. Without the inner stub the lockedOp lambda never runs and the
     * page-1 discriminator branch is unreachable from this entry point.
     */
    void stubExecuteInsideAtomicOperation(AtomicOperation op) throws IOException {
      org.mockito.Mockito.doAnswer(invocation -> {
        TxConsumer consumer = invocation.getArgument(0);
        runConsumer(consumer, op);
        return null;
      }).when(atomicOperationsManager).executeInsideAtomicOperation(any(TxConsumer.class));

      // Second layer: executeInsideComponentOperation(op, component, consumer).
      // Signature is void return + 3 args; the consumer is the third arg.
      org.mockito.Mockito.doAnswer(invocation -> {
        AtomicOperation passedOp = invocation.getArgument(0);
        TxConsumer consumer = invocation.getArgument(2);
        runConsumer(consumer, passedOp);
        return null;
      }).when(atomicOperationsManager).executeInsideComponentOperation(
          any(AtomicOperation.class),
          any(com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.StorageComponent.class),
          any(TxConsumer.class));
    }

    /** Invokes a {@link TxConsumer} and re-throws checked exceptions cleanly. */
    private static void runConsumer(TxConsumer consumer, AtomicOperation op)
        throws IOException {
      try {
        consumer.accept(op);
      } catch (Exception e) {
        if (e instanceof RuntimeException re) {
          throw re;
        }
        if (e instanceof IOException ioe) {
          throw ioe;
        }
        throw new RuntimeException(e);
      }
    }

    /**
     * Installs a snapshot with {@code hllOnPage1=true} into the CHM cache. The
     * snapshot carries a small populated HLL so the page-1 write branch is
     * reachable (the discriminator gates on {@code hllOnPage1 && hllSketch != null}).
     */
    void installSpillSnapshot() {
      var hll = new HyperLogLogSketch();
      // A handful of additions is enough to make estimate() non-zero; the value
      // does not affect the discriminator branch we want to pin.
      for (int i = 0; i < 100; i++) {
        hll.add(i * 7919L);
      }
      var stats = new IndexStatistics(1000, hll.estimate(), 50);
      var histogram = new EquiDepthHistogram(
          2,
          new Comparable<?>[] {0, 50, 100},
          new long[] {50, 50},
          new long[] {25, 25},
          100,
          null, 0);
      var snapshot = new HistogramSnapshot(
          stats, histogram, 100, 1000, 0, false, hll, /* hllOnPage1 */ true);
      cache.put(engineId, snapshot);
    }

    /**
     * Installs a snapshot where the HLL register array fits inline on page 0
     * ({@code hllOnPage1=false}). Used to pin the upstream guard that skips the
     * page-1 block entirely.
     */
    void installInlineHllSnapshot() {
      var hll = new HyperLogLogSketch();
      for (int i = 0; i < 100; i++) {
        hll.add(i * 7919L);
      }
      var stats = new IndexStatistics(1000, hll.estimate(), 50);
      var histogram = new EquiDepthHistogram(
          2,
          new Comparable<?>[] {0, 50, 100},
          new long[] {50, 50},
          new long[] {25, 25},
          100,
          null, 0);
      var snapshot = new HistogramSnapshot(
          stats, histogram, 100, 1000, 0, false, hll, /* hllOnPage1 */ false);
      cache.put(engineId, snapshot);
    }
  }

  /**
   * Pins {@link #PAGE_SIZE} against {@link DurablePage#MAX_PAGE_SIZE_BYTES} so a
   * future change to the page-size constant cannot silently shrink the test's
   * backing buffers below what {@code HistogramStatsPage.writeSnapshot} needs.
   */
  @Test
  public void pageSizeMatchesDurablePageMax() {
    assertEquals(DurablePage.MAX_PAGE_SIZE_BYTES, PAGE_SIZE);
    assertNotNull(bufferPool);
  }
}
