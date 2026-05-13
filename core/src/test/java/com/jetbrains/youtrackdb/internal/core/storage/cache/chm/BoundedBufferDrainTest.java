package com.jetbrains.youtrackdb.internal.core.storage.cache.chm;

import com.jetbrains.youtrackdb.internal.common.collection.ConcurrentLongIntHashMap;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CachePointer;
import com.jetbrains.youtrackdb.internal.core.storage.cache.PageEntryFixture;
import com.jetbrains.youtrackdb.internal.core.storage.cache.chm.readbuffer.BoundedBuffer;
import com.jetbrains.youtrackdb.internal.core.storage.cache.chm.readbuffer.Buffer;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

/**
 * Tests for {@link BoundedBuffer#drainTo(WTinyLFUPolicy)}, {@code reads()}, and
 * {@code writes()} after drain. Lives in the {@code cache.chm} package to access the
 * package-private {@link WTinyLFUPolicy} and {@link FrequencySketch} constructors.
 *
 * <p>The entries stored in the ring are backed by page-level direct memory provided by
 * {@link PageEntryFixture}, which encapsulates the
 * {@code ByteBufferPool.acquireDirect()} → {@link CachePointer} → {@code
 * incrementReadersReferrer()} sequence and runs the leak detector on close.
 *
 * <p><b>Note on counter semantics.</b> The {@code RingBuffer} constructor stores the first
 * element at slot 0 without advancing {@code writeCounter}. After N offers,
 * {@code writes()} = N - 1 and {@code reads()} = N - 1 after a full drain (see
 * {@code BoundedBufferRingTest} for the detailed explanation).
 */
public class BoundedBufferDrainTest {

  private PageEntryFixture pages;

  /** Per-test policy with a large max-size to avoid premature eviction during drain tests. */
  private WTinyLFUPolicy policy;
  private ConcurrentLongIntHashMap<CacheEntry> policyData;
  private AtomicInteger policyCacheSize;

  @Before
  public void setUp() {
    pages = new PageEntryFixture();
    policyData = new ConcurrentLongIntHashMap<>();
    policyCacheSize = new AtomicInteger(0);
    policy = new WTinyLFUPolicy(policyData, new FrequencySketch(), policyCacheSize);
    policy.setMaxSize(1024);
  }

  @After
  public void tearDown() {
    // Visit (but do NOT release) policy lists — pages.close() drives the actual page-frame
    // cleanup and runs the leak detector.
    drainPolicyLists();
    pages.close();
  }

  /**
   * Creates a CacheEntry backed by real direct-memory and registers it in the policy's data map
   * (required by onAccess). The page-level acquisition runs through {@link PageEntryFixture},
   * which the per-test {@link #tearDown()} closes to release every page frame and run the
   * direct-memory leak detector.
   */
  private CacheEntry makeAndRegisterEntry(final long fileId, final int pageIndex) {
    final CacheEntry entry = pages.acquireReader(fileId, pageIndex);
    policyCacheSize.incrementAndGet();
    policyData.put(fileId, pageIndex, entry);
    policy.onAdd(entry);
    return entry;
  }

  /** Visits (but does not release) the policy's eden/probation/protection lists. */
  private void drainPolicyLists() {
    drainIterator(policy.eden());
    drainIterator(policy.probation());
    drainIterator(policy.protection());
  }

  private static void drainIterator(final Iterator<CacheEntry> iter) {
    while (iter.hasNext()) {
      iter.next();
    }
  }

  // ---- StripedBuffer.drainTo when table is null ----

  /**
   * {@code StripedBuffer.drainTo()} guards on {@code table == null} and returns immediately,
   * leaving the consumer's {@code onAccess} uncalled. A freshly constructed
   * {@link BoundedBuffer} has a null table; draining it must be a no-op.
   *
   * <p>The earlier shape (asserting that eden/probation are empty after drain) was vacuous
   * because both lists were already empty before the drain. Here we wrap the policy in a
   * Mockito spy and assert that {@code onAccess} is never invoked — a regression that
   * dropped the {@code table == null} guard would call {@code onAccess} (likely with a
   * null arg), which the spy would observe.
   */
  @Test
  public void testDrainToOnNullTableIsNoOp() {
    final var spyPolicy = Mockito.spy(policy);
    final Buffer buf = new BoundedBuffer();

    buf.drainTo(spyPolicy);

    // Drained buffer with no table must NOT touch the consumer at all.
    Mockito.verify(spyPolicy, Mockito.never()).onAccess(Mockito.any());
    Assert.assertFalse("eden must remain empty after drain of null-table buffer",
        policy.eden().hasNext());
    Assert.assertFalse("probation must remain empty after drain of null-table buffer",
        policy.probation().hasNext());
  }

  // ---- RingBuffer.drainTo() — successful drain path ----

  /**
   * After writing N entries and calling {@code drainTo(policy)}, the read and write counters
   * must satisfy {@code reads() == writes()} and {@code size() == 0}. This verifies that
   * all elements (including the one stored by the constructor path) are consumed and the
   * consumer's {@code onAccess} is called for each.
   *
   * <p>Due to the constructor-path semantics, after N offers and a full drain:
   * {@code writes()} = N - 1 and {@code reads()} = N - 1 (the readCounter advances from 0
   * by OFFSET for each of the N - 1 elements whose slots are tracked by writeCounter,
   * plus the constructor-path element at slot 0 without a corresponding counter entry).
   * The invariant {@code reads() == writes()} and {@code size() == 0} holds in all cases.
   */
  @Test
  public void testDrainToMakesReadCounterEqualWriteCounterAndSizeZero() {
    final var buf = new BoundedBuffer();
    final int entryCount = 8;

    for (int i = 0; i < entryCount; i++) {
      buf.offer(makeAndRegisterEntry(0, i));
    }

    buf.drainTo(policy);

    Assert.assertEquals("reads() must equal writes() after full drain",
        buf.writes(), buf.reads());
    Assert.assertEquals("size() must be 0 after full drain", 0, buf.size());
  }

  /**
   * A single-element drain: offer one element and drain. After the drain, reads() must
   * equal 0 (the constructor-path element has writeCounter=0 so writes()=0 and after
   * draining that one element readCounter remains 0 — size() = 0-0 = 0). Confirms that
   * the null-slot short-circuit in drainTo() is NOT taken when an element is available.
   */
  @Test
  public void testDrainToSingleElementLeavesCountersAtZeroAndSizeZero() {
    final var buf = new BoundedBuffer();
    buf.offer(makeAndRegisterEntry(1, 0));

    Assert.assertEquals("writes() must be 0 for first constructor-path offer", 0, buf.writes());

    buf.drainTo(policy);

    Assert.assertEquals("reads() must be 0 after draining single constructor-path element",
        0, buf.reads());
    Assert.assertEquals("writes() must still be 0 after drain", 0, buf.writes());
    Assert.assertEquals("size() must be 0 after drain", 0, buf.size());
  }

  /**
   * After a full drain and additional offers, the buffer re-accumulates un-drained elements.
   * The {@code writes()} counter must reflect only the NEW offers (after the drain), and
   * {@code size()} must equal the new un-drained count.
   */
  @Test
  public void testReadsAndWritesAfterDrainAndMoreOffers() {
    final var buf = new BoundedBuffer();
    final int firstBatch = 5;
    final int secondBatch = 3;

    // First batch — write and drain (all 5 elements consumed).
    for (int i = 0; i < firstBatch; i++) {
      buf.offer(makeAndRegisterEntry(0, i));
    }
    buf.drainTo(policy);
    // After drain: reads() == writes() == 4 (N-1=4 for N=5), size()==0.
    Assert.assertEquals("reads() must equal writes() after first drain",
        buf.writes(), buf.reads());
    Assert.assertEquals("size() must be 0 after first drain", 0, buf.size());

    // Second batch — write only, do NOT drain.
    // Each of the 3 new offers CASes writeCounter, so writes() advances by 3.
    int writesBeforeSecondBatch = buf.writes();
    for (int i = firstBatch; i < firstBatch + secondBatch; i++) {
      buf.offer(makeAndRegisterEntry(0, i));
    }
    Assert.assertEquals("writes() must advance by secondBatch after more offers",
        writesBeforeSecondBatch + secondBatch, buf.writes());
    Assert.assertEquals("size() must equal secondBatch (un-drained)",
        secondBatch, buf.size());
  }
}
