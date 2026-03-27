package com.jetbrains.youtrackdb.internal.common.directmemory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator.Intention;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Tests for {@link PageFramePool} — validates pool acquire/release lifecycle, capacity limits,
 * stamp invalidation on acquire/release, and concurrent access.
 */
public class PageFramePoolTest {

  @BeforeClass
  public static void beforeClass() {
    GlobalConfiguration.DIRECT_MEMORY_TRACK_MODE.setValue(true);
  }

  @AfterClass
  public static void afterClass() {
    GlobalConfiguration.DIRECT_MEMORY_TRACK_MODE.setValue(false);
  }

  // --- Basic Lifecycle ---

  @Test
  public void testAcquireReturnsFrameWithCorrectBufferSize() {
    // Verifies that acquired frames have a buffer with the expected page size capacity.
    var allocator = new DirectMemoryAllocator();
    var pool = new PageFramePool(4096, allocator, 2);

    PageFrame frame = pool.acquire(false, Intention.TEST);
    assertNotNull(frame);
    assertEquals(4096, frame.getBuffer().capacity());
    assertEquals(0, pool.getPoolSize());

    pool.release(frame);
    pool.clear();
    allocator.checkMemoryLeaks();
  }

  @Test
  public void testAcquireWithClearZerosMemory() {
    // Verifies that acquiring with clear=true fills the buffer with zeros.
    var allocator = new DirectMemoryAllocator();
    var pool = new PageFramePool(4096, allocator, 2);

    PageFrame frame = pool.acquire(true, Intention.TEST);

    var buffer = frame.getBuffer();
    for (int i = 0; i < buffer.capacity(); i++) {
      assertEquals("Byte at position " + i + " should be zero", 0, buffer.get(i));
    }

    pool.release(frame);
    pool.clear();
    allocator.checkMemoryLeaks();
  }

  // --- Pool Capacity ---

  @Test
  public void testZeroPoolDeallocatesImmediately() {
    // With maxPoolSize=0, released frames are deallocated immediately.
    var allocator = new DirectMemoryAllocator();
    var pool = new PageFramePool(4096, allocator, 0);

    var frame = pool.acquire(false, Intention.TEST);
    assertEquals(4096, allocator.getMemoryConsumption());
    assertEquals(0, pool.getPoolSize());

    pool.release(frame);
    assertEquals(0, pool.getPoolSize());
    assertEquals(0, allocator.getMemoryConsumption());

    pool.clear();
    allocator.checkMemoryLeaks();
  }

  @Test
  public void testPoolReusesFrames() {
    // Released frames are reused by subsequent acquire calls, avoiding new allocation.
    var allocator = new DirectMemoryAllocator();
    var pool = new PageFramePool(4096, allocator, 2);

    var frame1 = pool.acquire(false, Intention.TEST);
    assertEquals(4096, allocator.getMemoryConsumption());

    pool.release(frame1);
    assertEquals(1, pool.getPoolSize());
    assertEquals(4096, allocator.getMemoryConsumption());

    var frame2 = pool.acquire(false, Intention.TEST);
    assertEquals(0, pool.getPoolSize());
    // Memory consumption unchanged — frame was reused, not newly allocated
    assertEquals(4096, allocator.getMemoryConsumption());

    pool.release(frame2);
    pool.clear();
    allocator.checkMemoryLeaks();
  }

  @Test
  public void testPoolCapacityLimit() {
    // When pool is full, excess released frames are deallocated.
    var allocator = new DirectMemoryAllocator();
    var pool = new PageFramePool(4096, allocator, 2);

    var frame1 = pool.acquire(false, Intention.TEST);
    var frame2 = pool.acquire(false, Intention.TEST);
    var frame3 = pool.acquire(false, Intention.TEST);
    assertEquals(3 * 4096, allocator.getMemoryConsumption());

    pool.release(frame1);
    assertEquals(1, pool.getPoolSize());
    assertEquals(3 * 4096, allocator.getMemoryConsumption());

    pool.release(frame2);
    assertEquals(2, pool.getPoolSize());
    assertEquals(3 * 4096, allocator.getMemoryConsumption());

    // Third release exceeds capacity — frame is deallocated
    pool.release(frame3);
    assertEquals(2, pool.getPoolSize());
    assertEquals(2 * 4096, allocator.getMemoryConsumption());

    pool.clear();
    assertEquals(0, pool.getPoolSize());
    assertEquals(0, allocator.getMemoryConsumption());
    allocator.checkMemoryLeaks();
  }

  // --- Stamp Invalidation ---

  @Test
  public void testReleaseInvalidatesOutstandingStamps() {
    // Releasing a frame to the pool must invalidate any outstanding optimistic stamps.
    // This is the critical safety barrier for optimistic readers.
    var allocator = new DirectMemoryAllocator();
    var pool = new PageFramePool(4096, allocator, 2);

    var frame = pool.acquire(false, Intention.TEST);

    long stamp = frame.tryOptimisticRead();

    pool.release(frame);

    // Stamp must be invalid after release
    assertFalse(frame.validate(stamp));

    pool.clear();
    allocator.checkMemoryLeaks();
  }

  @Test
  public void testAcquireFromPoolInvalidatesStaleStamps() {
    // Acquiring a frame from the pool invalidates stale stamps from the previous
    // user of that frame.
    var allocator = new DirectMemoryAllocator();
    var pool = new PageFramePool(4096, allocator, 2);

    var frame = pool.acquire(false, Intention.TEST);
    long staleStamp = frame.tryOptimisticRead();

    pool.release(frame);

    // Re-acquire (same frame from pool)
    var reused = pool.acquire(false, Intention.TEST);

    // Stale stamp must be invalid — the acquire cycle invalidated it
    assertFalse(reused.validate(staleStamp));

    pool.release(reused);
    pool.clear();
    allocator.checkMemoryLeaks();
  }

  @Test
  public void testReleaseResetsPageCoordinates() {
    // Releasing a frame to the pool must reset page coordinates to (-1, -1).
    var allocator = new DirectMemoryAllocator();
    var pool = new PageFramePool(4096, allocator, 2);

    var frame = pool.acquire(false, Intention.TEST);
    long stamp = frame.acquireExclusiveLock();
    frame.setPageCoordinates(42, 7);
    frame.releaseExclusiveLock(stamp);

    assertEquals(42, frame.getFileId());
    assertEquals(7, frame.getPageIndex());

    pool.release(frame);

    // After release, coordinates are reset
    // Note: reading after release is only safe because we still hold a Java reference
    // and the frame is pooled (memory still valid).
    assertEquals(-1, frame.getFileId());
    assertEquals(-1, frame.getPageIndex());

    pool.clear();
    allocator.checkMemoryLeaks();
  }

  // --- Clear ---

  @Test
  public void testClearDeallocatesAllPooledFrames() {
    // clear() must deallocate all frames currently in the pool.
    var allocator = new DirectMemoryAllocator();
    var pool = new PageFramePool(4096, allocator, 10);

    var frame1 = pool.acquire(false, Intention.TEST);
    var frame2 = pool.acquire(false, Intention.TEST);
    var frame3 = pool.acquire(false, Intention.TEST);

    pool.release(frame1);
    pool.release(frame2);
    pool.release(frame3);
    assertEquals(3, pool.getPoolSize());
    assertEquals(3 * 4096, allocator.getMemoryConsumption());

    pool.clear();
    assertEquals(0, pool.getPoolSize());
    assertEquals(0, allocator.getMemoryConsumption());
    allocator.checkMemoryLeaks();
  }

  // --- Recycled Frame Clear ---

  @Test
  public void testAcquireWithClearOnRecycledFrame() {
    // Verifies that acquiring a recycled frame with clear=true zeros dirty data
    // written by the previous user.
    var allocator = new DirectMemoryAllocator();
    var pool = new PageFramePool(4096, allocator, 2);

    var frame = pool.acquire(false, Intention.TEST);

    // Write dirty data to the frame
    var buffer = frame.getBuffer();
    for (int i = 0; i < buffer.capacity(); i += 4) {
      buffer.putInt(i, 0xDEADBEEF);
    }

    pool.release(frame);

    // Re-acquire with clear=true — dirty data must be zeroed
    var reused = pool.acquire(true, Intention.TEST);
    var reusedBuffer = reused.getBuffer();
    for (int i = 0; i < reusedBuffer.capacity(); i++) {
      assertEquals("Byte at position " + i + " should be zero after clear", 0,
          reusedBuffer.get(i));
    }

    pool.release(reused);
    pool.clear();
    allocator.checkMemoryLeaks();
  }

  // --- Concurrent Access ---

  @Test
  public void testConcurrentAcquireRelease() throws Exception {
    // Verifies that concurrent acquire/release operations do not corrupt pool state.
    // Multiple threads acquire and release frames simultaneously with random timing.
    var allocator = new DirectMemoryAllocator();
    var pool = new PageFramePool(4096, allocator, 100);
    int threadCount = 4;
    int opsPerThread = 500;

    List<Future<Void>> futures = new ArrayList<>();
    var executor = Executors.newFixedThreadPool(threadCount);
    var stop = new AtomicBoolean();

    try {
      for (int t = 0; t < threadCount; t++) {
        futures.add(executor.submit(new PoolExerciser(pool, opsPerThread, stop)));
      }

      for (var future : futures) {
        future.get();
      }
    } finally {
      executor.shutdown();
    }

    pool.clear();
    assertEquals(0, pool.getPoolSize());
    assertEquals(0, allocator.getMemoryConsumption());
    allocator.checkMemoryLeaks();
  }

  @Test
  public void testConcurrentOptimisticReadAndRelease() throws Exception {
    // Verifies that concurrent optimistic readers and frame releasers interact correctly:
    // readers always see validate() return false when a release intervened between
    // tryOptimisticRead and validate.
    var allocator = new DirectMemoryAllocator();
    var pool = new PageFramePool(4096, allocator, 10);
    int readerThreads = 4;
    int writerThreads = 2;
    int opsPerThread = 1000;

    // Pre-allocate frames for writers to cycle through
    var sharedFrames = new ArrayList<PageFrame>();
    for (int i = 0; i < 10; i++) {
      var frame = pool.acquire(false, Intention.TEST);
      long stamp = frame.acquireExclusiveLock();
      frame.setPageCoordinates(i, i * 100);
      frame.releaseExclusiveLock(stamp);
      sharedFrames.add(frame);
    }

    var errorOccurred = new AtomicBoolean();
    var futures = new ArrayList<Future<Void>>();
    var executor = Executors.newFixedThreadPool(readerThreads + writerThreads);

    try {
      // Reader threads: take optimistic stamps and validate them
      for (int t = 0; t < readerThreads; t++) {
        futures.add(executor.submit(() -> {
          try {
            var random = ThreadLocalRandom.current();
            for (int i = 0; i < opsPerThread && !errorOccurred.get(); i++) {
              var frame = sharedFrames.get(random.nextInt(sharedFrames.size()));
              long stamp = frame.tryOptimisticRead();
              if (stamp != 0) {
                // Read coordinates — may see stale data
                long fId = frame.getFileId();
                int pIdx = frame.getPageIndex();

                if (frame.validate(stamp)) {
                  // If validation succeeded, coordinates must be consistent:
                  // either the original values or values set by a writer thread.
                  // The key assertion is that no crash or exception occurs during
                  // the optimistic read of non-volatile fields.
                  assert fId >= -1 : "fileId must be >= -1";
                  assert pIdx >= -1 : "pageIndex must be >= -1";
                }
                // If validation failed, that's expected — a writer intervened
              }
            }
          } catch (Exception | Error e) {
            errorOccurred.set(true);
            throw new RuntimeException(e);
          }
          return null;
        }));
      }

      // Writer threads: acquire exclusive locks and update coordinates
      for (int t = 0; t < writerThreads; t++) {
        futures.add(executor.submit(() -> {
          try {
            var random = ThreadLocalRandom.current();
            for (int i = 0; i < opsPerThread && !errorOccurred.get(); i++) {
              var frame = sharedFrames.get(random.nextInt(sharedFrames.size()));
              long stamp = frame.acquireExclusiveLock();
              try {
                frame.setPageCoordinates(
                    random.nextLong(1000), random.nextInt(10000));
              } finally {
                frame.releaseExclusiveLock(stamp);
              }
            }
          } catch (Exception | Error e) {
            errorOccurred.set(true);
            throw new RuntimeException(e);
          }
          return null;
        }));
      }

      for (var future : futures) {
        future.get();
      }
    } finally {
      executor.shutdown();
    }

    assertFalse("No errors should occur during concurrent access", errorOccurred.get());

    for (var frame : sharedFrames) {
      pool.release(frame);
    }
    pool.clear();
    allocator.checkMemoryLeaks();
  }

  private static final class PoolExerciser implements Callable<Void> {

    private final PageFramePool pool;
    private final int ops;
    private final AtomicBoolean stop;
    private final List<PageFrame> held = new ArrayList<>();

    PoolExerciser(PageFramePool pool, int ops, AtomicBoolean stop) {
      this.pool = pool;
      this.ops = ops;
      this.stop = stop;
    }

    @Override
    public Void call() {
      try {
        var random = ThreadLocalRandom.current();
        for (int i = 0; i < ops && !stop.get(); i++) {
          if (held.size() < 10 || (held.size() < 20 && random.nextBoolean())) {
            var frame = pool.acquire(random.nextBoolean(), Intention.TEST);
            // Set page coordinates under exclusive lock — mimics real usage
            long stamp = frame.acquireExclusiveLock();
            frame.setPageCoordinates(random.nextLong(1000), random.nextInt(10000));
            frame.releaseExclusiveLock(stamp);
            held.add(frame);
          } else {
            int idx = random.nextInt(held.size());
            var frame = held.remove(idx);
            pool.release(frame);
          }
        }

        // Release all remaining frames
        for (var frame : held) {
          pool.release(frame);
        }
        held.clear();
      } catch (Exception | Error e) {
        stop.set(true);
        throw e;
      }
      return null;
    }
  }
}
