package com.jetbrains.youtrackdb.internal.core.storage.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.common.collection.ConcurrentLongIntHashMap;
import com.jetbrains.youtrackdb.internal.common.directmemory.ByteBufferPool;
import com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator;
import com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator.Intention;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for {@link CacheEntryImpl} equals/hashCode contract and hash consistency
 * with {@link ConcurrentLongIntHashMap#hashForFrequencySketch(long, int)}.
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
}
