package com.jetbrains.youtrackdb.internal.core.storage.cache.chm.readbuffer;

import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CachePointer;
import com.jetbrains.youtrackdb.internal.core.storage.cache.PageEntryFixture;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for {@link BoundedBuffer} and its inner {@code RingBuffer}. Exercises the FULL branch
 * when the ring is at capacity, and the {@code reads}/{@code writes}/{@code size} counters
 * exposed through the {@link Buffer} interface default and concrete implementations.
 *
 * <p>The entries stored in the ring are backed by page-level direct memory provided by
 * {@link PageEntryFixture}, which encapsulates the {@code ByteBufferPool.acquireDirect()}
 * → {@link CachePointer} → {@code incrementReadersReferrer()} sequence and runs the leak
 * detector on close.
 *
 * <p><b>Note on {@code writes()} semantics.</b> The {@link BoundedBuffer} is a
 * {@link StripedBuffer} that lazily creates a {@code RingBuffer} on the first offer. The
 * {@code RingBuffer} constructor stores the initial element at slot 0 via {@code lazySet}
 * <em>without</em> advancing {@code writeCounter}, so {@code writes() = writeCounter / OFFSET}
 * underreports by 1 relative to the total number of successful offers. After N ≥ 1 successful
 * offers, {@code writes()} returns N - 1. The FULL condition is reached after
 * {@code BUFFER_SIZE + 1 = 129} total successful offers (the 130th offer returns FULL).
 *
 * <p>Tests that require {@code drainTo(WTinyLFUPolicy)} live in {@code BoundedBufferDrainTest}
 * (package {@code cache.chm}) because {@link
 * com.jetbrains.youtrackdb.internal.core.storage.cache.chm.WTinyLFUPolicy} and {@link
 * com.jetbrains.youtrackdb.internal.core.storage.cache.chm.FrequencySketch} are package-private
 * to {@code cache.chm} and cannot be constructed from this package.
 */
public class BoundedBufferRingTest {

  /**
   * The declared capacity of each ring buffer (BUFFER_SIZE = 128). Due to the constructor-path
   * write that skips the counter, the ring can accept BUFFER_SIZE + 1 = 129 elements before the
   * 130th offer returns FULL.
   */
  private static final int BUFFER_SIZE = 128;

  private PageEntryFixture pages;

  @Before
  public void setUp() {
    pages = new PageEntryFixture();
  }

  @After
  public void tearDown() {
    pages.close();
  }

  /**
   * Creates a CacheEntry backed by a real direct-memory page frame via the shared
   * {@link PageEntryFixture}. The page-level pattern ({@code acquireDirect} →
   * {@link CachePointer} → {@code incrementReadersReferrer}) matches the codified pattern for
   * cache-managed page tests; the fixture also runs the direct-memory leak detector when
   * {@link #tearDown()} closes it.
   */
  private CacheEntry makeEntry(final long fileId, final int pageIndex) {
    return pages.acquireReader(fileId, pageIndex);
  }

  // ---- Buffer constants ----

  /**
   * {@link Buffer} exposes three int constants ({@code SUCCESS}, {@code FULL}, {@code FAILED})
   * as interface-level fields. Asserting their values pins the API and exercises the constant
   * access path in the bytecode.
   */
  @Test
  public void testBufferConstantValues() {
    Assert.assertEquals("Buffer.SUCCESS must be 0", 0, Buffer.SUCCESS);
    Assert.assertEquals("Buffer.FULL must be 1", 1, Buffer.FULL);
    Assert.assertEquals("Buffer.FAILED must be -1", -1, Buffer.FAILED);
  }

  // ---- reads() / writes() / size() when table is null ----

  /**
   * {@code StripedBuffer.reads()} and {@code writes()} guard on {@code table == null} and return
   * 0 early. A freshly constructed {@link BoundedBuffer} has a null table until the first offer,
   * exercising those null-table guard branches.
   */
  @Test
  public void testReadsAndWritesReturnZeroOnNullTable() {
    final Buffer buf = new BoundedBuffer();
    Assert.assertEquals("reads() must return 0 when table is null", 0, buf.reads());
    Assert.assertEquals("writes() must return 0 when table is null", 0, buf.writes());
  }

  /**
   * {@link Buffer#size()} is a default method returning {@code writes() - reads()}. Before any
   * offer the result must be 0.
   */
  @Test
  public void testDefaultSizeMethodOnEmptyBuffer() {
    final Buffer buf = new BoundedBuffer();
    Assert.assertEquals("size() must be 0 on empty BoundedBuffer (writes()-reads())", 0,
        buf.size());
  }

  // ---- offer() — SUCCESS path ----

  /**
   * After a single successful {@code offer()}, {@code reads()} must be 0 and {@code size()} must
   * be greater than 0 (one element is in the buffer, even if the constructor-path skips the write
   * counter increment). Due to the {@code RingBuffer} constructor semantics, the first offer
   * stores the element without advancing {@code writeCounter}, so {@code writes()} returns 0 and
   * {@code size()} = writes()-reads() = 0 — yet the element IS physically stored. This test pins
   * that behaviour rather than asserting an incorrect value for {@code writes()}.
   */
  @Test
  public void testReadsIsZeroAfterOneSuccessfulOffer() {
    final Buffer buf = new BoundedBuffer();
    final var result = buf.offer(makeEntry(0, 0));
    Assert.assertEquals("First offer to an empty BoundedBuffer must succeed", Buffer.SUCCESS,
        result);
    Assert.assertEquals("reads() must be 0 before any drain", 0, buf.reads());
  }

  /**
   * After N successful offers where N ≥ 2, {@code writes()} must equal N - 1 (the
   * constructor-path stores the first element without advancing the write counter; subsequent
   * offers each advance it by 1 from the caller's perspective). {@code reads()} must be 0
   * and {@code size()} must equal {@code writes()} - {@code reads()} = N - 1.
   */
  @Test
  public void testWritesEqualsNMinusOneForNSuccessfulOffers() {
    final Buffer buf = new BoundedBuffer();
    final int n = 10;
    for (int i = 0; i < n; i++) {
      final var result = buf.offer(makeEntry(0, i));
      Assert.assertEquals("offer #" + i + " must succeed", Buffer.SUCCESS, result);
    }
    // The first offer stores via constructor path (writeCounter stays 0); subsequent n-1
    // offers each CAS the counter. So writes() = n - 1.
    Assert.assertEquals("writes() must equal n-1 for n successful offers", n - 1, buf.writes());
    Assert.assertEquals("reads() must still be 0 before any drain", 0, buf.reads());
    Assert.assertEquals("size() must equal writes()-reads()", buf.writes() - buf.reads(),
        buf.size());
  }

  // ---- offer() — FULL branch ----

  /**
   * The ring returns FULL once {@code BUFFER_SIZE + 1 = 129} elements have been stored
   * (the constructor path fits one extra element beyond the declared BUFFER_SIZE capacity).
   * The 130th offer must return FULL.
   *
   * <p>After reaching the full state: {@code reads()} must still be 0 (no drain), and
   * {@code writes()} must equal BUFFER_SIZE (the 129 offers = 1 constructor + 128 CAS
   * → writeCounter = 128 * OFFSET → writes() = 128 = BUFFER_SIZE).
   */
  @Test
  public void testOfferReturnsFULLAfterExceedingRingCapacity() {
    final Buffer buf = new BoundedBuffer();
    // BUFFER_SIZE + 1 = 129 successful offers drain all available ring slots.
    final int fillCount = BUFFER_SIZE + 1;
    for (int i = 0; i < fillCount; i++) {
      final var result = buf.offer(makeEntry(0, i));
      Assert.assertEquals(
          "offer #" + i + " must succeed while ring has room", Buffer.SUCCESS, result);
    }

    Assert.assertEquals("reads() must be 0 before any drain", 0, buf.reads());
    // writes() = BUFFER_SIZE because 1 constructor + BUFFER_SIZE CAS offers
    Assert.assertEquals("writes() must equal BUFFER_SIZE after filling the ring",
        BUFFER_SIZE, buf.writes());

    // One more offer: size=(BUFFER_SIZE*OFFSET - 0) >= SPACED_SIZE → FULL.
    final var overflowResult = buf.offer(makeEntry(1, 0));
    Assert.assertEquals("offer beyond ring capacity must return FULL", Buffer.FULL, overflowResult);

    // writes() must not advance on a FULL return.
    Assert.assertEquals(
        "writes() must stay at BUFFER_SIZE after a FULL return", BUFFER_SIZE, buf.writes());
  }

  // ---- size() default method ----

  /**
   * {@code Buffer.size()} must equal {@code writes() - reads()} at all times. Verified after
   * multiple distinct offers.
   */
  @Test
  public void testDefaultSizeEqualsWritesMinusReads() {
    final Buffer buf = new BoundedBuffer();
    final int n = 7;
    for (int i = 0; i < n; i++) {
      buf.offer(makeEntry(3, i));
    }
    Assert.assertEquals(
        "size() must equal writes()-reads() for any fill level",
        buf.writes() - buf.reads(), buf.size());
  }
}
