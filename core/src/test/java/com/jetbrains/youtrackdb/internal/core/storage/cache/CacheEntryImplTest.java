package com.jetbrains.youtrackdb.internal.core.storage.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.common.collection.ConcurrentLongIntHashMap;
import com.jetbrains.youtrackdb.internal.common.directmemory.ByteBufferPool;
import com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator;
import com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator.Intention;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for {@link CacheEntryImpl}: equals/hashCode contract, hash consistency
 * with {@link ConcurrentLongIntHashMap#hashForFrequencySketch(long, int)}, and the
 * acquire/release/freeze/makeDead state machine.
 */
public class CacheEntryImplTest {

  private DirectMemoryAllocator allocator;
  private ByteBufferPool pool;
  private CachePointer pointer;

  @Before
  public void setUp() {
    allocator = new DirectMemoryAllocator();
    pool = new ByteBufferPool(1, allocator, 0);
    pointer = new CachePointer(pool.acquireDirect(true, Intention.TEST), pool, 1, 0);
    pointer.incrementReadersReferrer();
  }

  @After
  public void tearDown() {
    pointer.decrementReadersReferrer();
  }

  // ---- equals contract ----

  /** Two entries with same fileId and pageIndex are equal. */
  @Test
  public void equalsSameFileIdAndPageIndex() {
    var a = new CacheEntryImpl(5L, 10, pointer, false, null);
    var b = new CacheEntryImpl(5L, 10, pointer, false, null);
    assertThat(a).isEqualTo(b);
    assertThat(b).isEqualTo(a);
  }

  /** Entries with same fileId but different pageIndex are not equal. */
  @Test
  public void notEqualsDifferentPageIndex() {
    var a = new CacheEntryImpl(5L, 10, pointer, false, null);
    var b = new CacheEntryImpl(5L, 11, pointer, false, null);
    assertThat(a).isNotEqualTo(b);
  }

  /** Entries with different fileId but same pageIndex are not equal. */
  @Test
  public void notEqualsDifferentFileId() {
    var a = new CacheEntryImpl(5L, 10, pointer, false, null);
    var b = new CacheEntryImpl(6L, 10, pointer, false, null);
    assertThat(a).isNotEqualTo(b);
  }

  /** An entry is not equal to null. */
  @Test
  public void notEqualsNull() {
    var a = new CacheEntryImpl(5L, 10, pointer, false, null);
    assertThat(a).isNotEqualTo(null);
  }

  /** An entry is not equal to an object of a different type. */
  @Test
  public void notEqualsDifferentType() {
    var a = new CacheEntryImpl(5L, 10, pointer, false, null);
    assertThat(a.equals("not a cache entry")).isFalse();
  }

  /** An entry is equal to itself (reflexivity). */
  @Test
  public void equalsReflexive() {
    var a = new CacheEntryImpl(5L, 10, pointer, false, null);
    assertThat(a).isEqualTo(a);
  }

  // ---- hashCode contract ----

  /** Equal entries have equal hash codes. */
  @Test
  public void equalEntriesHaveEqualHashCodes() {
    var a = new CacheEntryImpl(5L, 10, pointer, false, null);
    var b = new CacheEntryImpl(5L, 10, pointer, false, null);
    assertThat(a.hashCode()).isEqualTo(b.hashCode());
  }

  // ---- hash consistency with frequency sketch ----

  /**
   * CacheEntryImpl.hashCode() must match hashForFrequencySketch for the same (fileId,
   * pageIndex). Both are documented as using Long.hashCode(fileId) * 31 + pageIndex.
   * This test pins the cross-class invariant so that a formula change in either class
   * is detected as a regression.
   */
  @Test
  public void hashCodeMatchesHashForFrequencySketch() {
    long[] fileIds = {0L, 1L, 42L, 123456789L, Long.MAX_VALUE};
    int[] pageIndices = {0, 1, 100, Integer.MAX_VALUE};

    for (long fileId : fileIds) {
      for (int pageIndex : pageIndices) {
        var entry = new CacheEntryImpl(fileId, pageIndex, pointer, false, null);
        assertThat(entry.hashCode())
            .as("hashCode mismatch for fileId=%d, pageIndex=%d", fileId, pageIndex)
            .isEqualTo(
                ConcurrentLongIntHashMap.hashForFrequencySketch(fileId, pageIndex));
      }
    }
  }

  // ---------------------------------------------------------------------------
  // State machine: acquireEntry / releaseEntry / isReleased / freeze / makeDead
  // ---------------------------------------------------------------------------

  /**
   * Verifies that acquireEntry() returns true when the entry is in the initial free state
   * (state == 0), incrementing the usage counter. releaseEntry() returns the state to 0.
   * isReleased() reflects the state correctly at each step.
   */
  @Test
  public void acquireAndReleaseEntry() {
    var entry = new CacheEntryImpl(2L, 5, pointer, false, null);

    // Initially released.
    assertTrue("New entry must be released (state==0)", entry.isReleased());
    assertTrue("isAlive must be true in initial state", entry.isAlive());

    // Acquire — state becomes 1.
    assertTrue("acquireEntry() must return true in initial state", entry.acquireEntry());
    assertFalse("isReleased() must be false after acquireEntry()", entry.isReleased());

    // Release — state returns to 0.
    entry.releaseEntry();
    assertTrue("isReleased() must be true after releaseEntry()", entry.isReleased());
  }

  /**
   * Verifies that freeze() transitions the entry from state==0 to FROZEN (-1), and that
   * subsequent acquireEntry() returns false (frozen entries cannot be acquired). Also verifies
   * that isAlive() returns false once frozen.
   */
  @Test
  public void freezeBlocksAcquire() {
    var entry = new CacheEntryImpl(3L, 6, pointer, false, null);

    // Freeze from released state.
    assertTrue("freeze() must succeed from state==0", entry.freeze());
    assertTrue("isFrozen() must be true after freeze()", entry.isFrozen());
    assertFalse("isAlive() must be false when frozen", entry.isAlive());

    // A second freeze attempt must fail (state is FROZEN, not 0).
    assertFalse("freeze() must fail when already frozen", entry.freeze());

    // acquireEntry() must fail on a frozen entry.
    assertFalse("acquireEntry() must return false on frozen entry", entry.acquireEntry());
  }

  /**
   * Verifies that makeDead() transitions a FROZEN entry to DEAD (-2), and that isDead()
   * reflects the new state. After makeDead() the entry is no longer alive.
   */
  @Test
  public void makeDeadFromFrozenState() {
    var entry = new CacheEntryImpl(4L, 7, pointer, false, null);

    entry.freeze();
    assertTrue("isFrozen() must be true before makeDead()", entry.isFrozen());

    entry.makeDead();
    assertTrue("isDead() must be true after makeDead()", entry.isDead());
    assertFalse("isAlive() must be false after makeDead()", entry.isAlive());
  }

  /**
   * Verifies that makeDead() throws IllegalStateException when the entry is not in the
   * FROZEN state (e.g., still in state==0 or acquired state). The transition ALIVE → DEAD
   * directly is invalid.
   */
  @Test(expected = IllegalStateException.class)
  public void makeDeadFromNonFrozenThrows() {
    var entry = new CacheEntryImpl(5L, 8, pointer, false, null);
    // State is 0 (not FROZEN) — makeDead must throw.
    entry.makeDead();
  }

  /**
   * Verifies that setInitialLSN() is a no-op (CacheEntryImpl returns NOT_TRACKED from
   * getInitialLSN() regardless of what was passed) and getEndLSN() delegates to the
   * CachePointer.
   */
  @Test
  public void setInitialLsnIsNoOpAndGetEndLsnDelegates() {
    var entry = new CacheEntryImpl(6L, 9, pointer, false, null);

    // setInitialLSN is a no-op — getInitialLSN still returns NOT_TRACKED.
    var lsn = new LogSequenceNumber(1, 100);
    entry.setInitialLSN(lsn);
    assertThat(entry.getInitialLSN()).isEqualTo(LogSequenceNumber.NOT_TRACKED);

    // getEndLSN delegates to the underlying CachePointer (null when no endLSN set).
    assertThat(entry.getEndLSN()).isNull();
  }

  /**
   * Verifies that toString() returns a non-null, non-empty string describing the entry.
   * The exact format is not contractual but must contain identifying information.
   */
  @Test
  public void toStringIsNonNull() {
    var entry = new CacheEntryImpl(7L, 10, pointer, true, null);
    var str = entry.toString();
    assertNotNull("toString() must not return null", str);
    assertTrue("toString() must not be empty", !str.isEmpty());
  }

  /**
   * Verifies that releaseEntry() throws IllegalStateException when called in state==0
   * (i.e., called more times than acquireEntry()). The count going below 1 is invalid.
   */
  @Test(expected = IllegalStateException.class)
  public void releaseEntryOnReleasedEntryThrows() {
    var entry = new CacheEntryImpl(8L, 11, pointer, false, null);
    // Releasing a never-acquired entry must throw.
    entry.releaseEntry();
  }
}
