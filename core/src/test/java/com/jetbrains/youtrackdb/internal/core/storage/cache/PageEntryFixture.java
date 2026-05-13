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
package com.jetbrains.youtrackdb.internal.core.storage.cache;

import com.jetbrains.youtrackdb.internal.common.directmemory.ByteBufferPool;
import com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator;
import com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator.Intention;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared test utility that encapsulates the direct-memory page-level setup/teardown pattern
 * that storage and cache unit tests share. Centralises the
 * {@code ByteBufferPool.acquireDirect()} → {@link CachePointer} → reference-count increment
 * sequence and provides a single tear-down hook ({@link #close()}) that performs the
 * symmetric decrement, a {@link ByteBufferPool#clear()}, and a
 * {@link DirectMemoryAllocator#checkMemoryLeaks()} call. This guarantees every page frame
 * acquired via the fixture is returned to the pool and that the per-test direct-memory
 * accounting is clean.
 *
 * <h2>Why this exists</h2>
 *
 * <p>Cache- and B-tree-bucket-level unit tests historically copy-pasted four to five lines
 * of boilerplate per test class to set up direct memory. The boilerplate has two flavours:
 *
 * <ol>
 *   <li><b>Reader-style</b> — used by {@code chm}-package cache tests
 *       ({@code CacheEntryImplTest}, {@code BoundedBufferRingTest},
 *       {@code BoundedBufferDrainTest}, {@code LockFreeReadCacheFileOpsTest}). The pointer
 *       is registered as a reader via {@link CachePointer#incrementReadersReferrer()} and
 *       released via {@link CachePointer#decrementReadersReferrer()}.
 *   <li><b>Exclusive-style</b> — used by B-tree bucket tests
 *       ({@code SBTreeLeafBucketV2Test} and friends). The pointer is registered as a plain
 *       referrer via {@link CachePointer#incrementReferrer()} and the cache entry
 *       additionally acquires an exclusive lock for in-place page mutations; tear-down
 *       releases the lock, then calls {@link CachePointer#decrementReferrer()}.
 * </ol>
 *
 * <p>The fixture exposes both flavours and tracks every acquisition so that {@link #close()}
 * is the single tear-down call, regardless of how many entries were created or in which
 * mode. The mandatory {@link DirectMemoryAllocator#checkMemoryLeaks()} assertion is built
 * into {@link #close()}, ensuring every consumer enforces the project-wide direct-memory
 * cleanup invariant without having to remember to add it explicitly.
 *
 * <h2>Usage — reader-style</h2>
 *
 * <pre>{@code
 * private PageEntryFixture pages;
 *
 * @Before public void setUp() {
 *   pages = new PageEntryFixture(); // 4 KiB pages, untracked allocator
 * }
 *
 * @After public void tearDown() {
 *   pages.close();
 * }
 *
 * @Test public void example() {
 *   var entry = pages.acquireReader(0L, 0);
 *   // ... interact with entry / its pointer ...
 * }
 * }</pre>
 *
 * <h2>Usage — exclusive-style (B-tree bucket round-trip)</h2>
 *
 * <pre>{@code
 * @Test public void bucketRoundTrip() {
 *   try (var pages = new PageEntryFixture()) {
 *     var entry = pages.acquireExclusive(0L, 0);
 *     var bucket = new SBTreeBucketV2<Long, Identifiable>(entry);
 *     bucket.init(true);
 *     // ... assert against the bucket ...
 *   } // close() releases lock + decrements referrers + clears pool + checks leaks
 * }
 * }</pre>
 *
 * <p>The fixture is {@link AutoCloseable} so try-with-resources is always safe. It is not
 * thread-safe — each test thread should construct its own fixture.
 */
public final class PageEntryFixture implements AutoCloseable {

  /** Default page size used by direct-memory cache tests when no explicit size is needed. */
  public static final int DEFAULT_PAGE_SIZE = 4 * 1024;

  /** Internal record describing one acquisition so {@link #close()} can release symmetrically. */
  private static final class Acquisition {
    final CacheEntry entry;
    final Mode mode;

    Acquisition(final CacheEntry entry, final Mode mode) {
      this.entry = entry;
      this.mode = mode;
    }
  }

  /** Acquisition mode — selects which reference-counting path the fixture must reverse. */
  private enum Mode {
    READER, EXCLUSIVE
  }

  private final DirectMemoryAllocator allocator;
  private final ByteBufferPool bufferPool;
  private final List<Acquisition> acquisitions = new ArrayList<>();
  private boolean closed;

  /**
   * Creates a fixture with {@link #DEFAULT_PAGE_SIZE} pages and a zero-sized pool (no
   * pre-allocation). Callers that need a different page size or pool capacity should use
   * {@link #PageEntryFixture(int, int)}.
   */
  public PageEntryFixture() {
    this(DEFAULT_PAGE_SIZE, 0);
  }

  /**
   * Creates a fixture with an explicit page size and pool capacity. The pool capacity is the
   * number of pre-allocated buffers; it does not cap acquisitions.
   *
   * @param pageSize  size in bytes of each direct-memory page (must be &gt; 0)
   * @param poolSize  number of pre-allocated pages cached inside the pool
   */
  public PageEntryFixture(final int pageSize, final int poolSize) {
    if (pageSize <= 0) {
      throw new IllegalArgumentException("pageSize must be > 0");
    }
    if (poolSize < 0) {
      throw new IllegalArgumentException("poolSize must be >= 0");
    }
    this.allocator = new DirectMemoryAllocator();
    this.bufferPool = new ByteBufferPool(pageSize, allocator, poolSize);
  }

  /** Returns the underlying allocator (for tests that need to inspect tracking state). */
  public DirectMemoryAllocator allocator() {
    return allocator;
  }

  /** Returns the underlying byte-buffer pool (for tests that need to construct
   * additional pointers outside the fixture's tracking; such pointers are NOT released
   * by {@link #close()}, so callers are responsible for symmetric cleanup). */
  public ByteBufferPool bufferPool() {
    return bufferPool;
  }

  /**
   * Acquires a fresh page frame, wraps it in a {@link CachePointer} registered as a
   * <em>reader</em> via {@link CachePointer#incrementReadersReferrer()}, and returns a
   * {@link CacheEntryImpl} positioned at {@code (fileId, pageIndex)}. The pointer's
   * matching {@link CachePointer#decrementReadersReferrer()} is invoked by {@link #close()}.
   *
   * <p>Use this acquisition mode when the test does not mutate the page through the
   * exclusive-lock path — for example, cache-policy tests that only need a real
   * direct-memory-backed entry to exercise hash / equals / state-machine code.
   */
  public CacheEntry acquireReader(final long fileId, final int pageIndex) {
    requireOpen();
    final var pointer =
        new CachePointer(bufferPool.acquireDirect(true, Intention.TEST), bufferPool, fileId,
            pageIndex);
    pointer.incrementReadersReferrer();
    final CacheEntry entry = new CacheEntryImpl(fileId, pageIndex, pointer, false, null);
    acquisitions.add(new Acquisition(entry, Mode.READER));
    return entry;
  }

  /**
   * Acquires a fresh page frame, wraps it in a {@link CachePointer} registered as a generic
   * <em>referrer</em> via {@link CachePointer#incrementReferrer()}, and returns a
   * {@link CacheEntryImpl} with its exclusive lock already held — the canonical setup for
   * B-tree bucket round-trip tests that mutate page bytes in place. {@link #close()}
   * releases the exclusive lock and matches with {@link CachePointer#decrementReferrer()}.
   *
   * <p>Use this acquisition mode for direct bucket / page-operation tests that construct a
   * bucket class around the entry and call mutating methods such as {@code init} or
   * {@code addEntry}.
   */
  public CacheEntry acquireExclusive(final long fileId, final int pageIndex) {
    requireOpen();
    final var pointer =
        new CachePointer(bufferPool.acquireDirect(true, Intention.TEST), bufferPool, fileId,
            pageIndex);
    pointer.incrementReferrer();
    final CacheEntry entry = new CacheEntryImpl(fileId, pageIndex, pointer, false, null);
    entry.acquireExclusiveLock();
    acquisitions.add(new Acquisition(entry, Mode.EXCLUSIVE));
    return entry;
  }

  /** Returns the number of currently-tracked acquisitions (mostly for self-tests). */
  public int acquisitionCount() {
    return acquisitions.size();
  }

  /**
   * Releases every acquisition recorded by this fixture (in reverse order so that the
   * most recently acquired entry is released first), clears the {@link ByteBufferPool},
   * and asserts via {@link DirectMemoryAllocator#checkMemoryLeaks()} that no direct
   * memory is leaked.
   *
   * <p>Idempotent: a second call is a no-op so that combining {@code @After}-driven
   * cleanup with try-with-resources does not double-release.
   */
  @Override
  public void close() {
    if (closed) {
      return;
    }
    closed = true;
    // Reverse iteration mirrors the typical LIFO usage pattern; the order does not matter
    // for correctness because each acquisition is independent, but reversing keeps stack
    // traces from a leak-detector failure pointing at the most recent test interaction.
    for (var i = acquisitions.size() - 1; i >= 0; i--) {
      final var acq = acquisitions.get(i);
      final var pointer = acq.entry.getCachePointer();
      switch (acq.mode) {
        case READER -> pointer.decrementReadersReferrer();
        case EXCLUSIVE -> {
          acq.entry.releaseExclusiveLock();
          pointer.decrementReferrer();
        }
      }
    }
    acquisitions.clear();
    bufferPool.clear();
    allocator.checkMemoryLeaks();
  }

  private void requireOpen() {
    if (closed) {
      throw new IllegalStateException("PageEntryFixture is already closed");
    }
  }
}
