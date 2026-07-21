package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
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
import com.jetbrains.youtrackdb.internal.core.config.ContextConfiguration;
import com.jetbrains.youtrackdb.internal.core.storage.cache.ApplyPhaseEpoch;
import com.jetbrains.youtrackdb.internal.core.storage.cache.OptimisticReadFailedException;
import com.jetbrains.youtrackdb.internal.core.storage.cache.OptimisticReadScope;
import com.jetbrains.youtrackdb.internal.core.storage.cache.OptimisticReadStats;
import com.jetbrains.youtrackdb.internal.core.storage.cache.PageView;
import com.jetbrains.youtrackdb.internal.core.storage.cache.ReadCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.WriteCache;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationsManager;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.junit.After;
import org.junit.Assume;
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
  private ErrorCapturingStorageComponent component;

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

    component = new ErrorCapturingStorageComponent(mockStorage);
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

  @Test
  public void testDurableFlagStoredCorrectlyWhenTrue() {
    // The default TestStorageComponent passes durable=true.
    assertEquals(true, component.isDurable());
  }

  @Test
  public void testDurableFlagStoredCorrectlyWhenFalse() {
    // A non-durable component must report isDurable()=false.
    var nonDurableComponent = new TestStorageComponent(
        component.storage, false);
    assertEquals(false, nonDurableComponent.isDurable());
  }

  @Test
  public void testAddFilePassesNonDurableFlagFromComponent() throws IOException {
    // When a non-durable StorageComponent calls addFile(), it must call the 2-arg
    // addFile(name, true) directly — not the 1-arg default which would pass false.
    var nonDurableComponent = new TestStorageComponent(
        component.storage, false);
    var op = mock(AtomicOperation.class);
    when(op.addFile("test-file.dat", true)).thenReturn(42L);

    long fileId = nonDurableComponent.testAddFile(op, "test-file.dat");

    assertEquals(42L, fileId);
    verify(op).addFile("test-file.dat", true);
    // Ensure the 1-arg overload was NOT called (would silently pass false via default)
    verify(op, never()).addFile("test-file.dat");
  }

  @Test
  public void testAddFilePassesDurableFlagFromComponent() throws IOException {
    // When a durable StorageComponent (default) calls addFile(), it should pass
    // nonDurable=false to the atomic operation.
    var op = mock(AtomicOperation.class);
    when(op.addFile("test-file.dat", false)).thenReturn(99L);

    long fileId = component.testAddFile(op, "test-file.dat");

    assertEquals(99L, fileId);
    verify(op).addFile("test-file.dat", false);
  }

  // --- Apply-phase epoch fallback and nesting detection (YTDB-1178) ---

  @Test
  public void testFallbackToPinnedWhenApplyPhaseInFlightAtCapture() throws IOException {
    // A commit apply phase already in flight when the read starts must fail epoch
    // validation (the capture sees enterSeq != exitSeq) and run the pinned lambda —
    // even though the page's stamp stays valid the whole time.
    var epoch = new ApplyPhaseEpoch();
    scope = new OptimisticReadScope(epoch);
    when(mockAtomicOp.getOptimisticReadScope()).thenReturn(scope);

    var frame = acquireFrameWithCoordinates(FILE_ID, PAGE_INDEX);
    when(mockReadCache.getPageFrameOptimistic(FILE_ID, PAGE_INDEX)).thenReturn(frame);

    epoch.enterApplyPhase();
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
      epoch.exitApplyPhase();
    }

    // A failed validation must not bump the eviction frequency sketch.
    verify(mockReadCache, never()).recordOptimisticAccess(FILE_ID, PAGE_INDEX);
    releaseFrame(frame);
  }

  @Test
  public void testFallbackToPinnedWhenApplyPhaseBeginsMidRead() throws IOException {
    // Epoch quiescent at capture, but a writer enters (and even completes) an apply
    // phase between the page read and validation. The live enterSeq no longer matches
    // the captured value → epoch check fails → pinned fallback runs.
    var epoch = new ApplyPhaseEpoch();
    scope = new OptimisticReadScope(epoch);
    when(mockAtomicOp.getOptimisticReadScope()).thenReturn(scope);

    var frame = acquireFrameWithCoordinates(FILE_ID, PAGE_INDEX);
    when(mockReadCache.getPageFrameOptimistic(FILE_ID, PAGE_INDEX)).thenReturn(frame);

    String result = component.testExecuteOptimisticStorageRead(
        mockAtomicOp,
        () -> {
          component.testLoadPageOptimistic(mockAtomicOp, FILE_ID, PAGE_INDEX);
          // Writer commits a full apply phase before this read validates.
          epoch.enterApplyPhase();
          epoch.exitApplyPhase();
          return "optimistic-result";
        },
        () -> "pinned-result");

    assertEquals("pinned-result", result);
    releaseFrame(frame);
  }

  @Test
  public void testNestedOptimisticReadSurfacesAssertionError() throws IOException {
    // Nested executeOptimisticStorageRead calls are a programming error: the inner
    // reset() wipes the outer scope's stamps, silently voiding the outer validation.
    // Detection is -ea-only and involves TWO AssertionErrors: the INNER call's
    // enterAttempt() assert ("... attempt ...") fires first, but it is thrown inside
    // the outer optimistic lambda and swallowed by the outer fallback catch; the
    // violation latched in the scope then fails the OUTER catch's exitAttempt() assert,
    // and THAT error ("... detected ...") is what escapes to the caller — before the
    // outer pinned lambda gets a chance to run.
    var assertionsEnabled = false;
    // Intentional side effect in assert — standard idiom to detect whether -ea is on.
    assert assertionsEnabled = true;
    Assume.assumeTrue("Nesting detection requires -ea", assertionsEnabled);

    var frame = acquireFrameWithCoordinates(FILE_ID, PAGE_INDEX);
    when(mockReadCache.getPageFrameOptimistic(FILE_ID, PAGE_INDEX)).thenReturn(frame);

    final boolean[] outerPinnedRan = {false};
    try {
      component.testExecuteOptimisticStorageRead(
          mockAtomicOp,
          () -> component.testExecuteOptimisticStorageRead(
              mockAtomicOp,
              () -> "inner-optimistic",
              () -> "inner-pinned"),
          () -> {
            outerPinnedRan[0] = true;
            return "outer-pinned";
          });
      fail("Expected AssertionError from nested optimistic read detection");
    } catch (AssertionError e) {
      assertTrue(
          "Expected the outer 'detected' assert to escape, got: " + e.getMessage(),
          String.valueOf(e.getMessage()).contains("Nested optimistic read detected"));
    }
    assertEquals(false, outerPinnedRan[0]);

    // exitAttempt() clears the detection state: a subsequent well-formed read on the
    // same scope must work normally again.
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
  public void testCheckedIOExceptionPropagatesAndDoesNotPoisonNextRead() throws IOException {
    // Regression: a checked IOException escaping the optimistic lambda is
    // NOT handled by the fallback catch (only RuntimeException | AssertionError route
    // to the pinned path) — it must propagate to the caller. Before the fix, this exit
    // path skipped exitAttempt(), leaving the -ea-only nesting state machine latched
    // (attemptActive=true), so the NEXT read on the same scope failed with a spurious
    // "Nested optimistic read attempt" AssertionError. The finally-based cleanup must
    // close the attempt on this path too.
    var frame = acquireFrameWithCoordinates(FILE_ID, PAGE_INDEX);
    when(mockReadCache.getPageFrameOptimistic(FILE_ID, PAGE_INDEX)).thenReturn(frame);

    try {
      component.testExecuteOptimisticStorageRead(
          mockAtomicOp,
          () -> {
            throw new IOException("checked failure from optimistic lambda");
          },
          () -> "pinned-result");
      fail("Expected the checked IOException to propagate (not swallowed into fallback)");
    } catch (IOException expected) {
      assertEquals("checked failure from optimistic lambda", expected.getMessage());
    }

    // The next read on the same scope must run normally — no spurious nesting error.
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
  public void testCheckedIOExceptionDoesNotPoisonNextReadVoidVariant() throws IOException {
    // Void-overload twin of the test above — both overloads carry their own copy of
    // the enter/exit attempt protocol and must both be exception-complete.
    var frame = acquireFrameWithCoordinates(FILE_ID, PAGE_INDEX);
    when(mockReadCache.getPageFrameOptimistic(FILE_ID, PAGE_INDEX)).thenReturn(frame);

    try {
      component.testExecuteOptimisticStorageReadVoid(
          mockAtomicOp,
          () -> {
            throw new IOException("checked failure from optimistic lambda");
          },
          () -> fail("pinned fallback must not run for a checked exception"));
      fail("Expected the checked IOException to propagate");
    } catch (IOException expected) {
      assertEquals("checked failure from optimistic lambda", expected.getMessage());
    }

    final boolean[] optimisticRan = {false};
    component.testExecuteOptimisticStorageReadVoid(
        mockAtomicOp,
        () -> {
          component.testLoadPageOptimistic(mockAtomicOp, FILE_ID, PAGE_INDEX);
          optimisticRan[0] = true;
        },
        () -> fail("should not fall back — previous failure must not poison this read"));
    assertEquals(true, optimisticRan[0]);

    releaseFrame(frame);
  }

  // --- Validated-null pinned re-check decision core (belt-and-braces, YTDB-1178) ---

  @Test
  public void testValidatedNullTriggersExactlyOnePinnedRecheckAndStaysSilent()
      throws IOException {
    // A cleanly validated optimistic NULL triggers exactly one pinned re-check; when the
    // pinned run confirms the miss (agreement), the null is returned silently — no report.
    final int[] pinnedRuns = {0};

    String result = component.testExecuteOptimisticStorageReadWithNullRecheck(
        mockAtomicOp,
        () -> null,
        () -> {
          pinnedRuns[0]++;
          return null;
        },
        Objects::isNull,
        () -> "key=42");

    assertNull(result);
    assertEquals(1, pinnedRuns[0]);
    assertEquals(0, component.nullRecheckErrorCount);
  }

  @Test
  public void testNullRecheckDisagreementReturnsPinnedResultAndReportsError() throws IOException {
    // Disagreement: validated optimistic null, but the pinned re-check finds an entry.
    // The pinned result is authoritative and returned; exactly one rate-limited ERROR is emitted with
    // the lookup coordinates.
    final int[] pinnedRuns = {0};

    String result = component.testExecuteOptimisticStorageReadWithNullRecheck(
        mockAtomicOp,
        () -> null,
        () -> {
          pinnedRuns[0]++;
          return "pinned-found";
        },
        Objects::isNull,
        () -> "key=42");

    assertEquals("pinned-found", result);
    assertEquals(1, pinnedRuns[0]);
    assertEquals(1, component.nullRecheckErrorCount);
    assertEquals("key=42", component.lastNullRecheckErrorDescription);
  }

  @Test
  public void testNonNullValidatedResultSkipsRecheck() throws IOException {
    // A cleanly validated non-null result must not pay the re-check: the pinned lambda
    // never runs.
    final int[] pinnedRuns = {0};
    var frame = acquireFrameWithCoordinates(FILE_ID, PAGE_INDEX);
    when(mockReadCache.getPageFrameOptimistic(FILE_ID, PAGE_INDEX)).thenReturn(frame);

    String result = component.testExecuteOptimisticStorageReadWithNullRecheck(
        mockAtomicOp,
        () -> {
          component.testLoadPageOptimistic(mockAtomicOp, FILE_ID, PAGE_INDEX);
          return "optimistic-hit";
        },
        () -> {
          pinnedRuns[0]++;
          return "pinned";
        },
        Objects::isNull,
        () -> "key=42");

    assertEquals("optimistic-hit", result);
    assertEquals(0, pinnedRuns[0]);
    assertEquals(0, component.nullRecheckErrorCount);
    releaseFrame(frame);
  }

  @Test
  public void testFallbackPathPerformsNoRecheck() throws IOException {
    // When the optimistic attempt fails and the pinned fallback runs, its result is
    // already authoritative: even a null must NOT trigger a re-check — the pinned
    // lambda executes exactly once (a legitimate miss must not pay the pinned cost
    // twice more).
    final int[] pinnedRuns = {0};

    String result = component.testExecuteOptimisticStorageReadWithNullRecheck(
        mockAtomicOp,
        () -> {
          throw OptimisticReadFailedException.INSTANCE;
        },
        () -> {
          pinnedRuns[0]++;
          return null;
        },
        Objects::isNull,
        () -> "key=42");

    assertNull(result);
    assertEquals(1, pinnedRuns[0]);
    assertEquals(0, component.nullRecheckErrorCount);
  }

  @Test
  public void testNullRecheckFallbackCountsInOptimisticReadStats() throws IOException {
    // The null-recheck wrapper's fallback must bump the global FALLBACKS diagnostic
    // counter exactly like the plain wrappers do (review finding BG-3), so the
    // counter's "every optimistic attempt that fell back" contract also holds for the
    // LinkBag/edge read path that uses this wrapper. The assertion is delta-based with
    // >= because the counter is JVM-global and other test classes running concurrently
    // (parallel=classes) may legitimately increment it as well.
    long fallbacksBefore = OptimisticReadStats.fallbacks();

    String result = component.testExecuteOptimisticStorageReadWithNullRecheck(
        mockAtomicOp,
        () -> {
          throw OptimisticReadFailedException.INSTANCE;
        },
        () -> "pinned",
        Objects::isNull,
        () -> "key=42");

    assertEquals("pinned", result);
    assertTrue("null-recheck fallback must increment the global fallback counter",
        OptimisticReadStats.fallbacks() >= fallbacksBefore + 1);
  }

  @Test
  public void testEqualNonNullResultsDoNotReport() throws IOException {
    // findVisibleEntry shape: the trigger can fire even when the composed optimistic
    // result is non-null (inner tree-null with a snapshot-index hit). An equal pinned
    // result is agreement, not disagreement — no report.
    String result = component.testExecuteOptimisticStorageReadWithNullRecheck(
        mockAtomicOp,
        () -> List.of("same"),
        () -> List.of("same"), // equal by value, distinct instance
        r -> true,
        () -> "key=42").get(0);

    assertEquals("same", result);
    assertEquals(0, component.nullRecheckErrorCount);
  }

  @Test
  public void testPinnedNullOptimisticNonNullReturnsOptimisticSilently() throws IOException {
    // Reverse-direction disagreement: the trigger fires on a non-null composed optimistic
    // result (the findVisibleEntry snapshot-hit shape) and the pinned re-check returns
    // null. The optimistic value is the SI-correct answer for this operation's snapshot
    // and must be returned; this direction is deliberately silent — exactly one pinned
    // run, no disagreement signal, no emission.
    final int[] pinnedRuns = {0};

    String result = component.testExecuteOptimisticStorageReadWithNullRecheck(
        mockAtomicOp,
        () -> "optimistic-snapshot-hit",
        () -> {
          pinnedRuns[0]++;
          return null;
        },
        r -> true,
        () -> "key=42");

    assertEquals("optimistic-snapshot-hit", result);
    assertEquals(1, pinnedRuns[0]);
    assertEquals(0, component.nullRecheckErrorCount);
  }

  @Test
  public void testProductionErrorEmissionIsRateLimited() throws IOException {
    // Uses a component WITHOUT the capturing override so the PRODUCTION emission body of
    // errorOptimisticNullRecheckDisagreement runs end-to-end: the first disagreement
    // passes the rate limiter and logs through LogManager (building the lazy
    // description); an immediate second disagreement takes the limiter's suppression
    // return. Both lookups must still return the authoritative pinned result.
    var plainComponent = new TestStorageComponent(component.storage);
    for (var i = 0; i < 2; i++) {
      String result = plainComponent.testExecuteOptimisticStorageReadWithNullRecheck(
          mockAtomicOp,
          () -> null,
          () -> "pinned-found",
          Objects::isNull,
          () -> "key=7");
      assertEquals("pinned-found", result);
    }
  }

  @Test
  public void testNullRecheckErrorRateLimiterHonorsConfiguredInterval() {
    // The rate-limit interval is configurable via
    // GlobalConfiguration.STORAGE_OPTIMISTIC_READ_NULL_RECHECK_REPORT_INTERVAL_SECS and is
    // read through the storage's per-database ContextConfiguration. Injecting a 2-second
    // interval through the mock storage (per-instance injection — no global configuration
    // mutation) must shrink the suppression window from the default 60 seconds.
    var contextConfiguration = new ContextConfiguration();
    contextConfiguration.setValue(
        GlobalConfiguration.STORAGE_OPTIMISTIC_READ_NULL_RECHECK_REPORT_INTERVAL_SECS, 2);
    when(component.storage.getContextConfiguration()).thenReturn(contextConfiguration);

    long t0 = 1L;
    assertTrue("first emission must be allowed",
        component.tryAcquireNullRecheckErrorSlot(t0));
    assertFalse("repeat within the configured 2s interval must be suppressed",
        component.tryAcquireNullRecheckErrorSlot(t0 + TimeUnit.SECONDS.toNanos(1)));
    assertTrue("emission after the configured 2s interval must be allowed",
        component.tryAcquireNullRecheckErrorSlot(t0 + TimeUnit.SECONDS.toNanos(3)));
  }

  @Test
  public void testNullRecheckErrorRateLimiterSuppressesRepeats() {
    // Default interval (60s): no ContextConfiguration is stubbed on the mock storage, so
    // the limiter takes the GlobalConfiguration-default fallback branch; tested with
    // controlled timestamps against the package-private seam.
    long t0 = 1L;
    assertTrue("first emission must be allowed",
        component.tryAcquireNullRecheckErrorSlot(t0));
    assertFalse("immediate repeat must be suppressed",
        component.tryAcquireNullRecheckErrorSlot(t0 + TimeUnit.MILLISECONDS.toNanos(1)));
    assertFalse("repeat within the interval must be suppressed",
        component.tryAcquireNullRecheckErrorSlot(t0 + TimeUnit.SECONDS.toNanos(59)));
    assertTrue("emission after the interval must be allowed",
        component.tryAcquireNullRecheckErrorSlot(t0 + TimeUnit.SECONDS.toNanos(61)));
    assertFalse("the fresh emission must restart the interval",
        component.tryAcquireNullRecheckErrorSlot(t0 + TimeUnit.SECONDS.toNanos(62)));
  }

  @Test
  public void testNullRecheckVariantCheckedExceptionDoesNotPoisonNextRead()
      throws IOException {
    // The variant carries the same exception-completeness contract as the plain wrapper:
    // a checked IOException from the optimistic lambda propagates (no fallback, no
    // re-check) and must not latch the -ea nesting state machine for the next read.
    try {
      component.testExecuteOptimisticStorageReadWithNullRecheck(
          mockAtomicOp,
          () -> {
            throw new IOException("checked failure from optimistic lambda");
          },
          () -> "pinned",
          Objects::isNull,
          () -> "key");
      fail("Expected the checked IOException to propagate");
    } catch (IOException expected) {
      assertEquals("checked failure from optimistic lambda", expected.getMessage());
    }

    final int[] pinnedRuns = {0};
    assertNull(component.testExecuteOptimisticStorageReadWithNullRecheck(
        mockAtomicOp,
        () -> null,
        () -> {
          pinnedRuns[0]++;
          return null;
        },
        Objects::isNull,
        () -> "key"));
    assertEquals(1, pinnedRuns[0]);
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
      this(storage, true);
    }

    TestStorageComponent(AbstractStorage storage, boolean durable) {
      super(storage, "test", ".tst", "test.lock", durable);
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

    long testAddFile(AtomicOperation op, String fileName) throws IOException {
      return addFile(op, fileName);
    }

    <T> T testExecuteOptimisticStorageReadWithNullRecheck(
        AtomicOperation op,
        OptimisticReadFunction<T> optimistic,
        PinnedReadFunction<T> pinned,
        Predicate<T> recheckTrigger,
        Supplier<String> lookupDescription) throws IOException {
      return executeOptimisticStorageReadWithNullRecheck(
          op, optimistic, pinned, recheckTrigger, lookupDescription);
    }
  }

  /**
   * Test subclass whose report override replaces the LogManager sink so tests can assert
   * emission counts and descriptions; the production emission body is exercised
   * separately through the plain {@link TestStorageComponent} in
   * {@link #testProductionErrorEmissionIsRateLimited()}, and the rate limiter through its
   * package-private seam.
   */
  private static class ErrorCapturingStorageComponent extends TestStorageComponent {

    int nullRecheckErrorCount;
    String lastNullRecheckErrorDescription;

    ErrorCapturingStorageComponent(AbstractStorage storage) {
      super(storage);
    }

    @Override
    protected void errorOptimisticNullRecheckDisagreement(
        Supplier<String> lookupDescription) {
      nullRecheckErrorCount++;
      lastNullRecheckErrorDescription = lookupDescription.get();
    }
  }
}
