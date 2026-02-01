/*
 *
 *  *  Copyright YouTrackDB
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
package com.jetbrains.youtrackdb.internal.common.directmemory;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator.Intention;
import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.core.config.ContextConfiguration;
import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Object of this class works at the same time as factory for <code>DirectByteBuffer</code> objects
 * and pool for <code>DirectByteBuffer</code> objects which were used and now are free to be reused
 * by other parts of the code. All <code>DirectByteBuffer</code> objects have the same size which is
 * specified in objects constructor as "page size".
 *
 * @see DirectMemoryAllocator
 */
public final class ByteBufferPool implements ByteBufferPoolMXBean {

  /**
   * Whether we should track memory leaks during application execution
   */
  private static final boolean TRACK =
      GlobalConfiguration.DIRECT_MEMORY_TRACK_MODE.getValueAsBoolean();

  /**
   * Holder for singleton instance. We use {@link AtomicReference} instead of static constructor to
   * avoid throwing of exceptions in static initializers.
   */
  private static final AtomicReference<ByteBufferPool> INSTANCE_HOLDER = new AtomicReference<>();

  /**
   * Limit of direct memory pointers are hold inside of the pool
   */
  private final int poolSize;

  /**
   * @return Singleton instance
   */
  public static ByteBufferPool instance(ContextConfiguration contextConfiguration) {
    final var instance = INSTANCE_HOLDER.get();
    if (instance != null) {
      return instance;
    }

    int bufferSize;
    if (contextConfiguration != null) {
      bufferSize =
          contextConfiguration.getValueAsInteger(GlobalConfiguration.DISK_CACHE_PAGE_SIZE);
    } else {
      bufferSize = GlobalConfiguration.DISK_CACHE_PAGE_SIZE.getValueAsInteger();
    }

    final var newInstance = new ByteBufferPool(bufferSize * 1024);
    if (INSTANCE_HOLDER.compareAndSet(null, newInstance)) {
      return newInstance;
    }

    return INSTANCE_HOLDER.get();
  }

  /**
   * Size of single page in bytes.
   */
  private final int pageSize;

  /**
   * {@link ByteBuffer}s can not be extended, so to keep mapping between pointers and buffers we use
   * concurrent hash map.
   */
  private final ConcurrentHashMap<Pointer, PointerTracker> pointerMapping =
      new ConcurrentHashMap<>();

  /**
   * Pool of already allocated pages.
   */
  private final ConcurrentLinkedQueue<Pointer> pointersPool = new ConcurrentLinkedQueue<>();

  /**
   * Size of the pool of pages is kept in separate counter because it is slow to ask pool itself and
   * count all links in the pool.
   */
  private final AtomicInteger pointersPoolSize = new AtomicInteger();

  /**
   * Direct memory allocator.
   */
  private final DirectMemoryAllocator allocator;

  /**
   * @param pageSize Size of single page (instance of <code>DirectByteBuffer</code>) returned by
   *                 pool.
   */
  public ByteBufferPool(int pageSize) {
    this.pageSize = pageSize;
    this.allocator = DirectMemoryAllocator.instance();
    this.poolSize = GlobalConfiguration.DIRECT_MEMORY_POOL_LIMIT.getValueAsInteger();
  }

  /**
   * @param allocator Direct memory allocator to use.
   * @param pageSize  Size of single page (instance of <code>DirectByteBuffer</code>) returned by
   *                  pool.
   * @param poolSize  Size of the page pool
   */
  public ByteBufferPool(int pageSize, DirectMemoryAllocator allocator, int poolSize) {
    this.pageSize = pageSize;
    this.allocator = allocator;
    this.poolSize = poolSize;
  }

  /**
   * Acquires direct memory buffer with native byte order. If there is free (already released)
   * direct memory page we reuse it, otherwise new memory chunk is allocated from direct memory.
   *
   * @param clear     Whether returned buffer should be filled with zeros before return.
   * @param intention Why this memory is allocated. This parameter is used for memory profiling.
   * @return Direct memory buffer instance.
   */
  public Pointer acquireDirect(boolean clear, Intention intention) {
    Pointer pointer;

    pointer = pointersPool.poll();

    if (pointer != null) {
      pointersPoolSize.decrementAndGet();

      if (clear) {
        pointer.clear();
      }
    } else {
      pointer = allocator.allocate(pageSize, clear, intention);
    }

    pointer.getNativeByteBuffer().position(0);

    if (TRACK) {
      pointerMapping.put(pointer, generatePointer());
    }

    return pointer;
  }

  /**
   * Put buffer which is not used any more back to the pool or frees direct memory if pool is full.
   *
   * @param pointer Not used instance of buffer.
   * @see GlobalConfiguration#DIRECT_MEMORY_POOL_LIMIT
   */
  public void release(Pointer pointer) {
    if (TRACK) {
      pointerMapping.remove(pointer);
    }

    long poolSize = pointersPoolSize.incrementAndGet();
    if (poolSize > this.poolSize) {
      pointersPoolSize.decrementAndGet();
      allocator.deallocate(pointer);
    } else {
      pointersPool.add(pointer);
    }
  }

  /**
   * @inheritDoc
   */
  @Override
  public int getPoolSize() {
    return pointersPoolSize.get();
  }

  /**
   * Checks whether there are not released buffers in the pool
   */
  public void checkMemoryLeaks() {
    var detected = false;
    if (TRACK) {
      for (var entry : pointerMapping.entrySet()) {
        final var iAdditionalArgs = new Object[]{System.identityHashCode(entry.getKey())};
        LogManager.instance()
            .error(
                this,
                "DIRECT-TRACK: unreleased direct memory pointer `%X` detected.",
                entry.getValue().allocation,
                iAdditionalArgs);
        detected = true;
      }
    }

    assert !detected;
  }

  /**
   * Clears pool and dealocates memory.
   */
  public void clear() {
    for (var pointer : pointersPool) {
      allocator.deallocate(pointer);
    }

    pointersPool.clear();
    pointersPoolSize.set(0);

    for (var pointer : pointerMapping.keySet()) {
      allocator.deallocate(pointer);
    }

    pointerMapping.clear();
  }

  /**
     * Holder which contains if memory tracking is enabled stack trace for the first allocation.
     */
    private record PointerTracker(Exception allocation) {

  }

  private PointerTracker generatePointer() {
    return new PointerTracker(new Exception());
  }
}
