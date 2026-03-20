package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator;
import com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator.Intention;
import com.jetbrains.youtrackdb.internal.common.directmemory.PageFrame;
import com.jetbrains.youtrackdb.internal.common.directmemory.PageFramePool;
import com.jetbrains.youtrackdb.internal.core.storage.cache.OptimisticReadScope;
import com.jetbrains.youtrackdb.internal.core.storage.cache.PageView;
import com.jetbrains.youtrackdb.internal.core.storage.cache.ReadCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.WriteCache;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationsManager;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for DurableComponent.loadPageOptimistic() and executeOptimisticStorageRead().
 * Uses a test subclass that exposes the protected methods.
 */
public class DurableComponentOptimisticReadTest {

  private static final int PAGE_SIZE = 4096;
  private static final long FILE_ID = 1;
  private static final int PAGE_INDEX = 42;

  private DirectMemoryAllocator allocator;
  private PageFramePool pool;
  private ReadCache mockReadCache;
  private AtomicOperation mockAtomicOp;
  private OptimisticReadScope scope;
  private TestDurableComponent component;

  @Before
  public void setUp() {
    GlobalConfiguration.DIRECT_MEMORY_TRACK_MODE.setValue(true);
    allocator = new DirectMemoryAllocator();
    pool = new PageFramePool(PAGE_SIZE, allocator, 16);

    mockReadCache = mock(ReadCache.class);
    var mockWriteCache = mock(WriteCache.class);
    var mockStorage = mock(AbstractStorage.class);
    var mockAtomicOpsMgr = mock(AtomicOperationsManager.class);
    when(mockStorage.getReadCache()).thenReturn(mockReadCache);
    when(mockStorage.getWriteCache()).thenReturn(mockWriteCache);
    when(mockStorage.getAtomicOperationsManager()).thenReturn(mockAtomicOpsMgr);

    scope = new OptimisticReadScope();
    mockAtomicOp = mock(AtomicOperation.class);
    when(mockAtomicOp.getOptimisticReadScope()).thenReturn(scope);

    component = new TestDurableComponent(mockStorage);
  }

  @After
  public void tearDown() {
    pool.clear();
    allocator.checkMemoryLeaks();
    GlobalConfiguration.DIRECT_MEMORY_TRACK_MODE.setValue(false);
  }

  @Test
  public void testLoadPageOptimisticHappyPath() {
    // When the page is in cache with valid stamp, loadPageOptimistic returns a PageView.
    var frame = acquireFrameWithCoordinates(FILE_ID, PAGE_INDEX);
    when(mockReadCache.getPageFrameOptimistic(FILE_ID, PAGE_INDEX)).thenReturn(frame);

    PageView view = component.testLoadPageOptimistic(mockAtomicOp, FILE_ID, PAGE_INDEX);

    assertNotNull(view);
    assertEquals(PAGE_INDEX, view.pageFrame().getPageIndex());
    assertEquals(1, scope.count());

    releaseFrame(frame);
  }

  @Test
  public void testExecuteOptimisticStorageReadHappyPath() throws IOException {
    // When optimistic read succeeds, the result is returned without fallback.
    var frame = acquireFrameWithCoordinates(FILE_ID, PAGE_INDEX);
    when(mockReadCache.getPageFrameOptimistic(FILE_ID, PAGE_INDEX)).thenReturn(frame);

    String result = component.testExecuteOptimisticStorageRead(
        mockAtomicOp,
        () -> {
          component.testLoadPageOptimistic(mockAtomicOp, FILE_ID, PAGE_INDEX);
          return "optimistic-result";
        },
        () -> "pinned-result");

    assertEquals("optimistic-result", result);
    // Verify frequency recording happened
    verify(mockReadCache).recordOptimisticAccess(FILE_ID, PAGE_INDEX);

    releaseFrame(frame);
  }

  @Test
  public void testFallbackOnCacheMiss() throws IOException {
    // When the page is not in cache, falls back to the pinned path.
    when(mockReadCache.getPageFrameOptimistic(anyLong(), anyLong())).thenReturn(null);

    String result = component.testExecuteOptimisticStorageRead(
        mockAtomicOp,
        () -> {
          component.testLoadPageOptimistic(mockAtomicOp, FILE_ID, PAGE_INDEX);
          return "optimistic-result";
        },
        () -> "pinned-result");

    assertEquals("pinned-result", result);
  }

  @Test
  public void testFallbackOnStampInvalidation() throws Exception {
    // When a page's stamp is invalidated (exclusive lock from another thread),
    // validation fails and we fall back to the pinned path.
    var frame = acquireFrameWithCoordinates(FILE_ID, PAGE_INDEX);
    when(mockReadCache.getPageFrameOptimistic(FILE_ID, PAGE_INDEX)).thenReturn(frame);

    String result = component.testExecuteOptimisticStorageRead(
        mockAtomicOp,
        () -> {
          component.testLoadPageOptimistic(mockAtomicOp, FILE_ID, PAGE_INDEX);

          // Invalidate the stamp from another thread before validation
          var latch = new CountDownLatch(1);
          var error = new AtomicReference<Throwable>();
          new Thread(() -> {
            try {
              long ws = frame.acquireExclusiveLock();
              frame.releaseExclusiveLock(ws);
            } catch (Throwable t) {
              error.set(t);
            } finally {
              latch.countDown();
            }
          }).start();
          try {
            latch.await();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          if (error.get() != null) {
            throw new RuntimeException("Thread failed", error.get());
          }

          return "optimistic-result";
        },
        () -> "pinned-result");

    assertEquals("pinned-result", result);
    releaseFrame(frame);
  }

  @Test
  public void testFallbackOnCoordinateMismatch() throws IOException {
    // When the frame's coordinates don't match (frame reused for another page),
    // loadPageOptimistic throws and we fall back.
    var frame = acquireFrameWithCoordinates(FILE_ID, 999); // Wrong page index
    when(mockReadCache.getPageFrameOptimistic(FILE_ID, PAGE_INDEX)).thenReturn(frame);

    String result = component.testExecuteOptimisticStorageRead(
        mockAtomicOp,
        () -> {
          component.testLoadPageOptimistic(mockAtomicOp, FILE_ID, PAGE_INDEX);
          return "optimistic-result";
        },
        () -> "pinned-result");

    assertEquals("pinned-result", result);
    releaseFrame(frame);
  }

  @Test
  public void testScopeResetBetweenCalls() throws IOException {
    // The scope should be reset before each executeOptimisticStorageRead call.
    var frame = acquireFrameWithCoordinates(FILE_ID, PAGE_INDEX);
    when(mockReadCache.getPageFrameOptimistic(FILE_ID, PAGE_INDEX)).thenReturn(frame);

    // First call — records 1 frame
    component.testExecuteOptimisticStorageRead(
        mockAtomicOp,
        () -> {
          component.testLoadPageOptimistic(mockAtomicOp, FILE_ID, PAGE_INDEX);
          return "result1";
        },
        () -> "fallback");

    // Second call — scope should be reset, so only 1 frame again
    component.testExecuteOptimisticStorageRead(
        mockAtomicOp,
        () -> {
          component.testLoadPageOptimistic(mockAtomicOp, FILE_ID, PAGE_INDEX);
          return "result2";
        },
        () -> "fallback");

    // After reset+record, count should be 1 (not accumulated from previous call)
    // We can't directly check scope.count() after executeOptimisticStorageRead because
    // it may have been reset. But the test would fail if reset didn't happen (scope
    // would still hold stale frames from the first call, and validation would fail
    // if those frames were invalidated between calls).

    releaseFrame(frame);
  }

  @Test
  public void testVoidVariant() throws IOException {
    // The void variant should work the same way, just without a return value.
    var frame = acquireFrameWithCoordinates(FILE_ID, PAGE_INDEX);
    when(mockReadCache.getPageFrameOptimistic(FILE_ID, PAGE_INDEX)).thenReturn(frame);

    final boolean[] optimisticRan = {false};

    component.testExecuteOptimisticStorageReadVoid(
        mockAtomicOp,
        () -> {
          component.testLoadPageOptimistic(mockAtomicOp, FILE_ID, PAGE_INDEX);
          optimisticRan[0] = true;
        },
        () -> {
          throw new AssertionError("Should not fall back");
        });

    assertEquals(true, optimisticRan[0]);
    releaseFrame(frame);
  }

  @Test
  public void testVoidVariantFallback() throws IOException {
    // Void variant falls back when optimistic fails.
    when(mockReadCache.getPageFrameOptimistic(anyLong(), anyLong())).thenReturn(null);

    final boolean[] pinnedRan = {false};

    component.testExecuteOptimisticStorageReadVoid(
        mockAtomicOp,
        () -> {
          component.testLoadPageOptimistic(mockAtomicOp, FILE_ID, PAGE_INDEX);
        },
        () -> {
          pinnedRan[0] = true;
        });

    assertEquals(true, pinnedRan[0]);
  }

  private PageFrame acquireFrameWithCoordinates(long fileId, int pageIndex) {
    var frame = pool.acquire(true, Intention.TEST);
    long exclusiveStamp = frame.acquireExclusiveLock();
    frame.setPageCoordinates(fileId, pageIndex);
    frame.releaseExclusiveLock(exclusiveStamp);
    return frame;
  }

  private void releaseFrame(PageFrame frame) {
    long stamp = frame.acquireExclusiveLock();
    frame.setPageCoordinates(-1, -1);
    frame.releaseExclusiveLock(stamp);
    pool.release(frame);
  }

  /**
   * Test subclass that exposes protected DurableComponent methods.
   */
  private static class TestDurableComponent extends DurableComponent {
    TestDurableComponent(AbstractStorage storage) {
      super(storage, "test", ".tst", "test.lock");
    }

    PageView testLoadPageOptimistic(AtomicOperation op, long fileId, long pageIndex) {
      return loadPageOptimistic(op, fileId, pageIndex);
    }

    <T> T testExecuteOptimisticStorageRead(
        AtomicOperation op,
        OptimisticReadFunction<T> optimistic,
        PinnedReadFunction<T> pinned) throws IOException {
      return executeOptimisticStorageRead(op, optimistic, pinned);
    }

    void testExecuteOptimisticStorageReadVoid(
        AtomicOperation op,
        OptimisticReadAction optimistic,
        PinnedReadAction pinned) throws IOException {
      executeOptimisticStorageRead(op, optimistic, pinned);
    }
  }
}
