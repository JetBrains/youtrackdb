package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
 * Tests for StorageComponent.loadPageOptimistic() and executeOptimisticStorageRead().
 * Uses a test subclass that exposes the protected methods.
 */
public class StorageComponentOptimisticReadTest {

  private static final int PAGE_SIZE = 4096;
  private static final long FILE_ID = 1;
  private static final int PAGE_INDEX = 42;

  private DirectMemoryAllocator allocator;
  private PageFramePool pool;
  private ReadCache mockReadCache;
  private AtomicOperation mockAtomicOp;
  private OptimisticReadScope scope;
  private TestStorageComponent component;

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

    component = new TestStorageComponent(mockStorage);
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
    // Verifies that executeOptimisticStorageRead resets the scope before each call.
    // Strategy: first call records frame1, then we invalidate frame1's stamp between
    // calls. If the scope is properly reset, the second call (using frame2 only)
    // succeeds. If reset is missing, the stale frame1 stamp fails validation.
    var frame1 = acquireFrameWithCoordinates(FILE_ID, PAGE_INDEX);
    var frame2 = acquireFrameWithCoordinates(FILE_ID, PAGE_INDEX + 1);
    when(mockReadCache.getPageFrameOptimistic(FILE_ID, PAGE_INDEX)).thenReturn(frame1);
    when(mockReadCache.getPageFrameOptimistic(FILE_ID, PAGE_INDEX + 1)).thenReturn(frame2);

    // First call — records frame1
    String result1 = component.testExecuteOptimisticStorageRead(
        mockAtomicOp,
        () -> {
          component.testLoadPageOptimistic(mockAtomicOp, FILE_ID, PAGE_INDEX);
          return "result1";
        },
        () -> "fallback1");
    assertEquals("result1", result1);

    // Invalidate frame1's stamp (exclusive lock bump from another thread)
    var latch = new CountDownLatch(1);
    new Thread(() -> {
      long ws = frame1.acquireExclusiveLock();
      frame1.releaseExclusiveLock(ws);
      latch.countDown();
    }).start();
    try {
      latch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }

    // Second call — uses frame2 only. If scope still holds stale frame1,
    // validateOrThrow() would fail because frame1's stamp is now invalid.
    String result2 = component.testExecuteOptimisticStorageRead(
        mockAtomicOp,
        () -> {
          component.testLoadPageOptimistic(mockAtomicOp, FILE_ID, PAGE_INDEX + 1);
          return "result2";
        },
        () -> "fallback2");
    assertEquals("result2", result2);

    releaseFrame(frame1);
    releaseFrame(frame2);
  }

  @Test
  public void testFallbackOnStampZero() throws IOException {
    // When the exclusive lock is held on the frame at the time of tryOptimisticRead(),
    // the stamp is 0, loadPageOptimistic throws, and we fall back to the pinned path.
    var frame = acquireFrameWithCoordinates(FILE_ID, PAGE_INDEX);
    when(mockReadCache.getPageFrameOptimistic(FILE_ID, PAGE_INDEX)).thenReturn(frame);

    // Hold exclusive lock on this thread — tryOptimisticRead() will return 0
    long exclusiveStamp = frame.acquireExclusiveLock();
    try {
      String result = component.testExecuteOptimisticStorageRead(
          mockAtomicOp,
          () -> {
            component.testLoadPageOptimistic(mockAtomicOp, FILE_ID, PAGE_INDEX);
            return "optimistic-result";
          },
          () -> "pinned-result");

      assertEquals("pinned-result", result);
    } finally {
      frame.releaseExclusiveLock(exclusiveStamp);
    }
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
  public void testFallbackOnLocalWalChanges() throws IOException {
    // When the current AtomicOperation has local WAL changes for the requested page,
    // loadPageOptimistic forces a fallback to the pinned path to avoid reading stale
    // committed data.
    var frame = acquireFrameWithCoordinates(FILE_ID, PAGE_INDEX);
    when(mockReadCache.getPageFrameOptimistic(FILE_ID, PAGE_INDEX)).thenReturn(frame);
    when(mockAtomicOp.hasChangesForPage(FILE_ID, PAGE_INDEX)).thenReturn(true);

    String result = component.testExecuteOptimisticStorageRead(
        mockAtomicOp,
        () -> {
          component.testLoadPageOptimistic(mockAtomicOp, FILE_ID, PAGE_INDEX);
          return "optimistic-result";
        },
        () -> "pinned-result");

    assertEquals("pinned-result", result);
    // Cache must never be consulted when hasChangesForPage returns true —
    // the guard must short-circuit before the cache lookup.
    verify(mockReadCache, never()).getPageFrameOptimistic(FILE_ID, PAGE_INDEX);
    releaseFrame(frame);
  }

  @Test
  public void testOptimisticSucceedsWhenNoLocalChanges() throws IOException {
    // When the current AtomicOperation has no local WAL changes for the page,
    // the optimistic path proceeds normally.
    var frame = acquireFrameWithCoordinates(FILE_ID, PAGE_INDEX);
    when(mockReadCache.getPageFrameOptimistic(FILE_ID, PAGE_INDEX)).thenReturn(frame);
    when(mockAtomicOp.hasChangesForPage(FILE_ID, PAGE_INDEX)).thenReturn(false);

    String result = component.testExecuteOptimisticStorageRead(
        mockAtomicOp,
        () -> {
          component.testLoadPageOptimistic(mockAtomicOp, FILE_ID, PAGE_INDEX);
          return "optimistic-result";
        },
        () -> "pinned-result");

    assertEquals("optimistic-result", result);
    releaseFrame(frame);
  }

  @Test
  public void testFallbackOnLocalChangesCheckedBeforeCacheLookup() throws IOException {
    // The hasChangesForPage check must happen before the cache lookup —
    // even if the page is in cache with a valid frame, local changes must force fallback.
    // We verify this by NOT setting up a frame in the cache (null return),
    // but since hasChangesForPage returns true, we should still fall back
    // without hitting the null-frame path.
    when(mockAtomicOp.hasChangesForPage(FILE_ID, PAGE_INDEX)).thenReturn(true);
    // Do NOT set up a frame — the cache lookup should never be reached

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

  // --- Tests for RuntimeException catch widening (Q1) ---
  // After widening the catch clause from OptimisticReadFailedException to RuntimeException,
  // these tests verify that arbitrary RuntimeExceptions thrown by the optimistic lambda
  // (simulating speculative reads from stale/reused PageFrames) correctly trigger fallback
  // to the pinned path.

  @Test
  public void testFallbackOnArrayIndexOutOfBoundsFromStaleRead() throws IOException {
    // AIOOBE from a speculative read on a stale/reused PageFrame should trigger fallback.
    String result = component.testExecuteOptimisticStorageRead(
        mockAtomicOp,
        () -> {
          throw new ArrayIndexOutOfBoundsException("stale frame data");
        },
        () -> "pinned-result");

    assertEquals("pinned-result", result);
  }

  @Test
  public void testFallbackOnNullPointerExceptionFromStaleRead() throws IOException {
    // NPE from a speculative read on a stale frame should trigger fallback.
    String result = component.testExecuteOptimisticStorageRead(
        mockAtomicOp,
        () -> {
          throw new NullPointerException("stale pointer");
        },
        () -> "pinned-result");

    assertEquals("pinned-result", result);
  }

  @Test
  public void testFallbackOnIllegalStateExceptionFromStaleRead() throws IOException {
    // IllegalStateException from stale frame should trigger fallback.
    String result = component.testExecuteOptimisticStorageRead(
        mockAtomicOp,
        () -> {
          throw new IllegalStateException("stale state");
        },
        () -> "pinned-result");

    assertEquals("pinned-result", result);
  }

  @Test
  public void testVoidVariantFallbackOnArbitraryRuntimeException() throws IOException {
    // Void variant should also catch RuntimeException and fall back.
    final boolean[] pinnedRan = {false};
    component.testExecuteOptimisticStorageReadVoid(
        mockAtomicOp,
        () -> {
          throw new IllegalStateException("stale data");
        },
        () -> pinnedRan[0] = true);

    assertEquals(true, pinnedRan[0]);
  }

  @Test
  public void testMultiPageOptimisticReadRecordsAllAccesses() throws IOException {
    // A real BTree.get() reads multiple pages (root + internal nodes + leaf).
    // Verify that all pages in a single optimistic read are tracked in the scope
    // and that recordOptimisticAccess is called for each.
    var frame1 = acquireFrameWithCoordinates(FILE_ID, 0);
    var frame2 = acquireFrameWithCoordinates(FILE_ID, 1);
    var frame3 = acquireFrameWithCoordinates(FILE_ID, 2);
    when(mockReadCache.getPageFrameOptimistic(FILE_ID, 0)).thenReturn(frame1);
    when(mockReadCache.getPageFrameOptimistic(FILE_ID, 1)).thenReturn(frame2);
    when(mockReadCache.getPageFrameOptimistic(FILE_ID, 2)).thenReturn(frame3);

    String result = component.testExecuteOptimisticStorageRead(
        mockAtomicOp,
        () -> {
          component.testLoadPageOptimistic(mockAtomicOp, FILE_ID, 0);
          component.testLoadPageOptimistic(mockAtomicOp, FILE_ID, 1);
          component.testLoadPageOptimistic(mockAtomicOp, FILE_ID, 2);
          return "multi-page-result";
        },
        () -> "pinned-result");

    assertEquals("multi-page-result", result);
    verify(mockReadCache).recordOptimisticAccess(FILE_ID, 0);
    verify(mockReadCache).recordOptimisticAccess(FILE_ID, 1);
    verify(mockReadCache).recordOptimisticAccess(FILE_ID, 2);

    releaseFrame(frame1);
    releaseFrame(frame2);
    releaseFrame(frame3);
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
   * Test subclass that exposes protected StorageComponent methods.
   */
  private static class TestStorageComponent extends StorageComponent {
    TestStorageComponent(AbstractStorage storage) {
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
