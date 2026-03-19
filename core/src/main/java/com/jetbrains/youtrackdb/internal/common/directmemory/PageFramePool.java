package com.jetbrains.youtrackdb.internal.common.directmemory;

import com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator.Intention;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pool of {@link PageFrame} objects. Frames are recycled, not deallocated during normal
 * operation (protective memory allocation). This guarantees that native memory stays mapped
 * even when frames are pooled, so any speculative read from a stale PageFrame hits valid
 * memory.
 *
 * <p>When pool capacity is exceeded, excess frames MAY be deallocated — but only after
 * acquiring the exclusive lock (invalidating all outstanding optimistic stamps). The
 * {@link java.util.concurrent.locks.StampedLock} itself lives on the Java heap, so
 * {@link PageFrame#validate(long)} is always safe to call regardless of native memory state.
 *
 * <p><b>Critical ordering:</b> readers MUST call {@code validate()} before ANY read from the
 * buffer after taking the stamp. The StampedLock's acquire fence ensures the stamp check is
 * visible before any subsequent memory access.
 */
public final class PageFramePool {

  private final ConcurrentLinkedQueue<PageFrame> pool;
  private final AtomicInteger poolSize;
  private final int maxPoolSize;
  private final int pageSize;
  private final DirectMemoryAllocator allocator;

  /**
   * Creates a new PageFramePool.
   *
   * @param pageSize    size of each page frame in bytes
   * @param allocator   direct memory allocator for new frame allocation and deallocation
   * @param maxPoolSize maximum number of frames to keep in the pool
   */
  public PageFramePool(int pageSize, DirectMemoryAllocator allocator, int maxPoolSize) {
    assert pageSize > 0 : "Page size must be positive";
    assert allocator != null : "Allocator must not be null";
    assert maxPoolSize >= 0 : "Max pool size must be non-negative";

    this.pageSize = pageSize;
    this.allocator = allocator;
    this.maxPoolSize = maxPoolSize;
    this.pool = new ConcurrentLinkedQueue<>();
    this.poolSize = new AtomicInteger();
  }

  /**
   * Acquires a PageFrame from the pool or allocates a new one.
   *
   * <p>When a frame is reused from the pool, an exclusive lock is acquired and released
   * to invalidate any stale stamps and establish a happens-before edge with previous users
   * of this frame.
   *
   * @param clear     whether to fill the frame's memory with zeros
   * @param intention allocation intention for memory profiling
   * @return a PageFrame ready for use
   */
  public PageFrame acquire(boolean clear, Intention intention) {
    PageFrame frame = pool.poll();

    if (frame != null) {
      poolSize.decrementAndGet();

      // Acquire+release exclusive lock to invalidate any stale stamps
      // and establish happens-before with previous users of this frame.
      long stamp = frame.acquireExclusiveLock();
      try {
        if (clear) {
          frame.clear();
        }
      } finally {
        frame.releaseExclusiveLock(stamp);
      }

      return frame;
    }

    // Allocate new frame
    Pointer ptr = allocator.allocate(pageSize, clear, intention);
    return new PageFrame(ptr);
  }

  /**
   * Releases a PageFrame back to the pool, or deallocates it if the pool is full.
   *
   * <p>Acquires the exclusive lock BEFORE pooling — this invalidates all outstanding
   * optimistic stamps. Any optimistic reader holding a stamp from before this point will
   * see {@link PageFrame#validate(long)} return false. This is the critical safety barrier.
   *
   * @param frame the frame to release
   */
  public void release(PageFrame frame) {
    assert frame != null : "Frame must not be null";

    // Acquire+release exclusive lock BEFORE pooling — invalidates all outstanding stamps.
    long stamp = frame.acquireExclusiveLock();
    try {
      frame.setPageCoordinates(-1, -1);
    } finally {
      frame.releaseExclusiveLock(stamp);
    }

    // Add to queue first, then increment counter. This avoids the race where
    // poolSize is incremented but the frame is not yet visible in the queue,
    // which could cause acquire() to see a non-zero size but poll() null.
    pool.add(frame);
    if (poolSize.incrementAndGet() > maxPoolSize) {
      // Over capacity — trim one frame (may not be the one just added)
      PageFrame excess = pool.poll();
      if (excess != null) {
        poolSize.decrementAndGet();
        // Exclusive lock above already invalidated all stamps on the released frame.
        // If we're deallocating a different frame here, it was already pooled with
        // invalidated stamps from its own release cycle.
        allocator.deallocate(excess.getPointer());
      }
    }
  }

  /**
   * Returns the current number of frames in the pool.
   */
  public int getPoolSize() {
    return poolSize.get();
  }

  /**
   * Clears the pool, deallocating all pooled frames. Must only be called during shutdown
   * when no concurrent access to the pool or its frames is possible.
   */
  public void clear() {
    PageFrame frame;
    while ((frame = pool.poll()) != null) {
      poolSize.decrementAndGet();
      allocator.deallocate(frame.getPointer());
    }
  }
}
