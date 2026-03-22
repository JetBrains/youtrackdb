package com.jetbrains.youtrackdb.internal.common.directmemory;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator.Intention;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.experimental.categories.Category;

@Category(SequentialTest.class)
public class ByteBufferPoolTest {

  @BeforeClass
  public static void beforeClass() {
    GlobalConfiguration.DIRECT_MEMORY_TRACK_MODE.setValue(true);
  }

  @AfterClass
  public static void afterClass() {
    GlobalConfiguration.DIRECT_MEMORY_TRACK_MODE.setValue(false);
  }

  @Test
  public void testByteBufferAllocationZeroPool() {
    final var allocator = new DirectMemoryAllocator();
    final var byteBufferPool = new ByteBufferPool(42, allocator, 0);

    final var pointerOne = byteBufferPool.acquireDirect(false, Intention.TEST);
    Assert.assertEquals(42, pointerOne.getNativeByteBuffer().capacity());
    Assert.assertEquals(42, allocator.getMemoryConsumption());

    Assert.assertEquals(0, byteBufferPool.getPoolSize());

    final var pointerTwo = byteBufferPool.acquireDirect(true, Intention.TEST);
    Assert.assertEquals(42, pointerTwo.getNativeByteBuffer().capacity());
    Assert.assertEquals(84, allocator.getMemoryConsumption());

    assertBufferIsClear(pointerTwo.getNativeByteBuffer());

    byteBufferPool.release(pointerOne);
    Assert.assertEquals(0, byteBufferPool.getPoolSize());
    Assert.assertEquals(42, allocator.getMemoryConsumption());

    byteBufferPool.release(pointerTwo);
    Assert.assertEquals(0, byteBufferPool.getPoolSize());
    Assert.assertEquals(0, allocator.getMemoryConsumption());

    byteBufferPool.clear();
    byteBufferPool.checkMemoryLeaks();
  }

  @Test
  public void testByteBufferAllocationTwoPagesPool() {
    final var allocator = new DirectMemoryAllocator();
    final var byteBufferPool = new ByteBufferPool(42, allocator, 2);

    var pointerOne = byteBufferPool.acquireDirect(false, Intention.TEST);

    Assert.assertEquals(42, pointerOne.getNativeByteBuffer().capacity());
    Assert.assertEquals(0, byteBufferPool.getPoolSize());
    Assert.assertEquals(42, allocator.getMemoryConsumption());

    var pointerTwo = byteBufferPool.acquireDirect(true, Intention.TEST);
    Assert.assertEquals(42, pointerTwo.getNativeByteBuffer().capacity());
    Assert.assertEquals(0, byteBufferPool.getPoolSize());
    Assert.assertEquals(84, allocator.getMemoryConsumption());

    assertBufferIsClear(pointerTwo.getNativeByteBuffer());

    var pointerThree = byteBufferPool.acquireDirect(false, Intention.TEST);

    Assert.assertEquals(42, pointerThree.getNativeByteBuffer().capacity());
    Assert.assertEquals(0, byteBufferPool.getPoolSize());
    Assert.assertEquals(126, allocator.getMemoryConsumption());

    byteBufferPool.release(pointerOne);

    Assert.assertEquals(1, byteBufferPool.getPoolSize());
    Assert.assertEquals(126, allocator.getMemoryConsumption());

    byteBufferPool.release(pointerTwo);

    Assert.assertEquals(2, byteBufferPool.getPoolSize());
    Assert.assertEquals(126, allocator.getMemoryConsumption());

    byteBufferPool.release(pointerThree);

    Assert.assertEquals(2, byteBufferPool.getPoolSize());
    Assert.assertEquals(84, allocator.getMemoryConsumption());

    pointerOne = byteBufferPool.acquireDirect(true, Intention.TEST);

    Assert.assertEquals(42, pointerOne.getNativeByteBuffer().capacity());
    Assert.assertEquals(1, byteBufferPool.getPoolSize());
    Assert.assertEquals(84, allocator.getMemoryConsumption());

    assertBufferIsClear(pointerOne.getNativeByteBuffer());

    pointerTwo = byteBufferPool.acquireDirect(true, Intention.TEST);

    Assert.assertEquals(42, pointerTwo.getNativeByteBuffer().capacity());
    Assert.assertEquals(0, byteBufferPool.getPoolSize());
    Assert.assertEquals(84, allocator.getMemoryConsumption());

    assertBufferIsClear(pointerTwo.getNativeByteBuffer());

    pointerThree = byteBufferPool.acquireDirect(false, Intention.TEST);

    Assert.assertEquals(42, pointerThree.getNativeByteBuffer().capacity());
    Assert.assertEquals(0, byteBufferPool.getPoolSize());
    Assert.assertEquals(126, allocator.getMemoryConsumption());

    byteBufferPool.release(pointerThree);

    Assert.assertEquals(1, byteBufferPool.getPoolSize());
    Assert.assertEquals(126, allocator.getMemoryConsumption());

    pointerThree = byteBufferPool.acquireDirect(true, Intention.TEST);

    Assert.assertEquals(42, pointerThree.getNativeByteBuffer().capacity());
    Assert.assertEquals(0, byteBufferPool.getPoolSize());
    Assert.assertEquals(126, allocator.getMemoryConsumption());

    assertBufferIsClear(pointerThree.getNativeByteBuffer());

    byteBufferPool.release(pointerThree);

    Assert.assertEquals(1, byteBufferPool.getPoolSize());
    Assert.assertEquals(126, allocator.getMemoryConsumption());

    byteBufferPool.release(pointerOne);

    Assert.assertEquals(2, byteBufferPool.getPoolSize());
    Assert.assertEquals(126, allocator.getMemoryConsumption());

    Assert.assertEquals(2, byteBufferPool.getPoolSize());
    Assert.assertEquals(126, allocator.getMemoryConsumption());

    byteBufferPool.release(pointerTwo);

    Assert.assertEquals(2, byteBufferPool.getPoolSize());
    Assert.assertEquals(84, allocator.getMemoryConsumption());

    byteBufferPool.clear();

    Assert.assertEquals(0, allocator.getMemoryConsumption());
    Assert.assertEquals(0, byteBufferPool.getPoolSize());

    byteBufferPool.checkMemoryLeaks();
  }

  @Test
  @Ignore
  public void mtTest() throws Exception {
    final var allocator = new DirectMemoryAllocator();
    final var byteBufferPool = new ByteBufferPool(42, allocator, 600 * 8);
    final List<Future<Void>> futures = new ArrayList<>();
    final var stop = new AtomicBoolean();

    final var executorService = Executors.newCachedThreadPool();
    for (var i = 0; i < 8; i++) {
      futures.add(executorService.submit(new Allocator(byteBufferPool, stop)));
    }

    Thread.sleep(5 * 60 * 1000);

    stop.set(true);

    for (var future : futures) {
      future.get();
    }

    byteBufferPool.clear();

    byteBufferPool.checkMemoryLeaks();
    allocator.checkMemoryLeaks();
  }

  // --- pageFramePool() auto-sizing tests (Q1, Q2, Q5 from track-level review) ---

  @Test
  public void testPageFramePoolAutoSizeDerivedFromDiskCacheSize() {
    // Verify that auto-sizing (PAGE_FRAME_POOL_LIMIT = -1) derives maxPoolSize
    // from 2 * DISK_CACHE_SIZE / pageSize.
    var origLimit = GlobalConfiguration.PAGE_FRAME_POOL_LIMIT.getValue();
    var origCacheSize = GlobalConfiguration.DISK_CACHE_SIZE.getValue();
    try {
      GlobalConfiguration.PAGE_FRAME_POOL_LIMIT.setValue(-1);
      GlobalConfiguration.DISK_CACHE_SIZE.setValue(16); // 16 MB

      int pageSize = 8192;
      var allocator = new DirectMemoryAllocator();
      var pool = new ByteBufferPool(pageSize, allocator, 0);

      PageFramePool framePool = pool.pageFramePool();
      Assert.assertNotNull(framePool);

      // Expected: 2 * (16 MB / 8192) = 2 * 2048 = 4096 frames
      // Verify by filling the pool beyond the limit and checking excess is deallocated
      var frames = new ArrayList<PageFrame>();
      for (int i = 0; i < 4097; i++) {
        frames.add(framePool.acquire(false, Intention.TEST));
      }
      for (var f : frames) {
        framePool.release(f);
      }
      // Pool should hold at most 4096 frames (the auto-computed limit)
      Assert.assertEquals(4096, framePool.getPoolSize());

      framePool.clear();
      pool.clear();
      allocator.checkMemoryLeaks();
    } finally {
      GlobalConfiguration.PAGE_FRAME_POOL_LIMIT.setValue(origLimit);
      GlobalConfiguration.DISK_CACHE_SIZE.setValue(origCacheSize);
    }
  }

  @Test
  public void testPageFramePoolExplicitLimitOverridesAutoSize() {
    // Verify that an explicit PAGE_FRAME_POOL_LIMIT overrides the auto-sizing.
    // Set DISK_CACHE_SIZE to 16 MB so auto-size would be 4096 — the explicit
    // limit of 5 clearly overrides it.
    var origLimit = GlobalConfiguration.PAGE_FRAME_POOL_LIMIT.getValue();
    var origCacheSize = GlobalConfiguration.DISK_CACHE_SIZE.getValue();
    try {
      GlobalConfiguration.PAGE_FRAME_POOL_LIMIT.setValue(5);
      GlobalConfiguration.DISK_CACHE_SIZE.setValue(16);

      int pageSize = 8192;
      var allocator = new DirectMemoryAllocator();
      var pool = new ByteBufferPool(pageSize, allocator, 0);

      PageFramePool framePool = pool.pageFramePool();

      var frames = new ArrayList<PageFrame>();
      for (int i = 0; i < 7; i++) {
        frames.add(framePool.acquire(false, Intention.TEST));
      }
      for (var f : frames) {
        framePool.release(f);
      }
      // Explicit limit of 5 should cap the pool (not auto-size of 4096)
      Assert.assertEquals(5, framePool.getPoolSize());

      framePool.clear();
      pool.clear();
      allocator.checkMemoryLeaks();
    } finally {
      GlobalConfiguration.PAGE_FRAME_POOL_LIMIT.setValue(origLimit);
      GlobalConfiguration.DISK_CACHE_SIZE.setValue(origCacheSize);
    }
  }

  @Test
  public void testPageFramePoolExplicitZeroLimitCreatesZeroCapacityPool() {
    // Verify that PAGE_FRAME_POOL_LIMIT = 0 creates a pool where every release
    // immediately deallocates.
    var origLimit = GlobalConfiguration.PAGE_FRAME_POOL_LIMIT.getValue();
    try {
      GlobalConfiguration.PAGE_FRAME_POOL_LIMIT.setValue(0);

      int pageSize = 8192;
      var allocator = new DirectMemoryAllocator();
      var pool = new ByteBufferPool(pageSize, allocator, 0);

      PageFramePool framePool = pool.pageFramePool();

      var frame = framePool.acquire(false, Intention.TEST);
      framePool.release(frame);
      // With limit 0, nothing stays pooled
      Assert.assertEquals(0, framePool.getPoolSize());
      Assert.assertEquals(0, allocator.getMemoryConsumption());

      pool.clear();
      allocator.checkMemoryLeaks();
    } finally {
      GlobalConfiguration.PAGE_FRAME_POOL_LIMIT.setValue(origLimit);
    }
  }

  @Test
  public void testPageFramePoolAutoSizeWithZeroDiskCacheSize() {
    // Verify that DISK_CACHE_SIZE = 0 produces maxFrames = 0 (immediate deallocation).
    var origLimit = GlobalConfiguration.PAGE_FRAME_POOL_LIMIT.getValue();
    var origCacheSize = GlobalConfiguration.DISK_CACHE_SIZE.getValue();
    try {
      GlobalConfiguration.PAGE_FRAME_POOL_LIMIT.setValue(-1);
      GlobalConfiguration.DISK_CACHE_SIZE.setValue(0); // 0 MB

      var allocator = new DirectMemoryAllocator();
      var pool = new ByteBufferPool(8192, allocator, 0);
      PageFramePool framePool = pool.pageFramePool();

      var frame = framePool.acquire(false, Intention.TEST);
      framePool.release(frame);
      // With 0 MB cache, auto-size produces maxFrames = 0
      Assert.assertEquals(0, framePool.getPoolSize());
      Assert.assertEquals(0, allocator.getMemoryConsumption());

      pool.clear();
      allocator.checkMemoryLeaks();
    } finally {
      GlobalConfiguration.PAGE_FRAME_POOL_LIMIT.setValue(origLimit);
      GlobalConfiguration.DISK_CACHE_SIZE.setValue(origCacheSize);
    }
  }

  @Test
  public void testPageFramePoolReturnsSameInstance() {
    // Verify the double-checked locking: two calls return the same instance.
    var allocator = new DirectMemoryAllocator();
    var pool = new ByteBufferPool(8192, allocator, 0);
    var fp1 = pool.pageFramePool();
    var fp2 = pool.pageFramePool();
    Assert.assertSame(fp1, fp2);
    fp1.clear();
    pool.clear();
    allocator.checkMemoryLeaks();
  }

  private void assertBufferIsClear(ByteBuffer bufferTwo) {
    while (bufferTwo.position() < bufferTwo.capacity()) {
      Assert.assertEquals(0, bufferTwo.get());
    }
  }

  private static final class Allocator implements Callable<Void> {

    private final ByteBufferPool pool;
    private final ThreadLocalRandom random = ThreadLocalRandom.current();
    private final AtomicBoolean stop;
    private final List<Pointer> allocatedPointers = new ArrayList<>();

    private Allocator(ByteBufferPool pool, AtomicBoolean stop) {
      this.pool = pool;
      this.stop = stop;
    }

    @Override
    public Void call() {
      try {
        while (!stop.get()) {
          if (allocatedPointers.size() < 500) {
            var pointer = pool.acquireDirect(false, Intention.TEST);
            allocatedPointers.add(pointer);
          } else if (allocatedPointers.size() < 1000) {
            if (random.nextDouble() <= 0.5) {
              var pointer = pool.acquireDirect(false, Intention.TEST);
              allocatedPointers.add(pointer);
            } else {
              final var bufferToRemove = random.nextInt(allocatedPointers.size());
              final var pointer = allocatedPointers.remove(bufferToRemove);
              pool.release(pointer);
            }
          } else {
            if (random.nextDouble() <= 0.4) {
              var pointer = pool.acquireDirect(false, Intention.TEST);
              allocatedPointers.add(pointer);
            } else {
              final var bufferToRemove = random.nextInt(allocatedPointers.size());
              final var pointer = allocatedPointers.remove(bufferToRemove);
              pool.release(pointer);
            }
          }
        }

        System.out.println("Allocated buffers " + allocatedPointers.size());
        for (var pointer : allocatedPointers) {
          pool.release(pointer);
        }
      } catch (Exception | Error e) {
        e.printStackTrace();
        throw e;
      }

      return null;
    }
  }
}
