package com.jetbrains.youtrackdb.internal.core.storage.cache.local;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.common.util.RawPair;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import org.junit.Test;

/**
 * Tests the package-private {@code Callable}/{@code Runnable} task wrappers in
 * {@code storage.cache.local} — {@link DeleteFileTask}, {@link EnsurePageIsValidInFileTask},
 * {@link ExclusiveFlushTask}, {@link FileFlushTask}, {@link FlushTillSegmentTask},
 * {@link FindMinDirtySegment}, {@link RemoveFilePagesTask}, {@link PeriodicFlushTask}.
 *
 * <p>Each wrapper is a thin delegator that forwards to a specific {@code WOWCache} method
 * with the constructor-captured arguments. The tests verify both directions:
 * <ul>
 *   <li>The wrapper invokes the documented {@code WOWCache} method exactly once.
 *   <li>The constructor-captured arguments arrive at the delegate unchanged (specifically
 *       {@code externalFileId}, {@code segmentId}, {@code fileIds} set contents, etc.).
 *   <li>The return value (when the wrapper has one) propagates through unchanged.
 * </ul>
 *
 * <p>Wrappers are package-private; this test must live in the same package. WOWCache is
 * mocked — the tests assert the delegation contract, not the underlying implementation.
 */
public class CacheLocalTaskWrappersTest {

  /** Verifies {@link DeleteFileTask#call()} delegates to {@code executeDeleteFile} with the
   * captured externalFileId and propagates the returned RawPair back to the caller. */
  @Test
  public void testDeleteFileTaskDelegatesToExecuteDeleteFile() throws Exception {
    var cache = mock(WOWCache.class);
    var expected = new RawPair<>("logical.tst", "physical.tst");
    when(cache.executeDeleteFile(123L)).thenReturn(expected);

    var task = new DeleteFileTask(cache, 123L);
    var result = task.call();

    // Identity (assertSame) — the wrapper must not wrap or copy the result
    assertSame("Wrapper must propagate WOWCache.executeDeleteFile result unchanged",
        expected, result);
    verify(cache, times(1)).executeDeleteFile(123L);
  }

  /** Verifies that an exception thrown by {@code executeDeleteFile} propagates out of
   * {@link DeleteFileTask#call()} unchanged — so the executor's future records the failure. */
  @Test
  public void testDeleteFileTaskPropagatesExceptionFromDelegate() throws Exception {
    var cache = mock(WOWCache.class);
    var boom = new RuntimeException("simulated failure");
    when(cache.executeDeleteFile(99L)).thenThrow(boom);

    var task = new DeleteFileTask(cache, 99L);
    try {
      task.call();
      org.junit.Assert.fail("Expected exception to propagate");
    } catch (RuntimeException e) {
      assertSame(boom, e);
    }
  }

  /** Verifies {@link EnsurePageIsValidInFileTask#run()} forwards the captured (internalFileId,
   * pageIndex) pair to {@code writeValidPageInFile} exactly once. */
  @Test
  public void testEnsurePageIsValidInFileTaskDelegatesToWriteValidPageInFile() throws Exception {
    var cache = mock(WOWCache.class);
    var task = new EnsurePageIsValidInFileTask(7, 42, cache);

    task.run();

    verify(cache, times(1)).writeValidPageInFile(7, 42);
    // No other method on the cache should be called by this wrapper. executeDeleteFile is
    // declared with checked exceptions; verify(...) on it requires a throws Exception in the
    // test signature even though Mockito's verify never actually invokes the real method.
    verify(cache, never()).executeDeleteFile(any(Long.class));
  }

  /** Verifies {@link ExclusiveFlushTask#run()} forwards the captured cacheBoundaryLatch and
   * completionLatch to {@code executeFlush} (identity, not equals — the latches must reach
   * the flush method as the same instances or the surrounding {@code triggeredTasks} map
   * cannot match them on completion). */
  @Test
  public void testExclusiveFlushTaskForwardsLatchesByIdentity() {
    var cache = mock(WOWCache.class);
    var boundary = new CountDownLatch(1);
    var completion = new CountDownLatch(1);

    var task = new ExclusiveFlushTask(cache, boundary, completion);
    task.run();

    verify(cache, times(1)).executeFlush(eq(boundary), eq(completion));
    // Latches must be the same instances — eq() on identity-equal CountDownLatch passes
    // by ==, so a copying wrapper would break the verify above.
  }

  /** Verifies {@link FileFlushTask#call()} copies the constructor's {@code Collection<Integer>}
   * into a new {@code IntOpenHashSet} containing the same element set, and forwards that set
   * to {@code executeFileFlush}. */
  @Test
  public void testFileFlushTaskDelegatesWithCopiedFileIdSet() throws Exception {
    var cache = mock(WOWCache.class);
    var inputIds = Arrays.asList(5, 10, 15);

    var task = new FileFlushTask(cache, inputIds);
    task.call();

    var captor = org.mockito.ArgumentCaptor.forClass(IntOpenHashSet.class);
    verify(cache, times(1)).executeFileFlush(captor.capture());
    var captured = captor.getValue();
    assertEquals("Captured set must contain exactly the input IDs", 3, captured.size());
    org.junit.Assert.assertTrue(captured.contains(5));
    org.junit.Assert.assertTrue(captured.contains(10));
    org.junit.Assert.assertTrue(captured.contains(15));
  }

  /** Verifies that mutating the input collection AFTER constructing the task does NOT affect
   * the set the task will pass to {@code executeFileFlush} — the constructor must defensively
   * copy. Without the defensive copy, a caller that reuses the input collection between tasks
   * would corrupt in-flight flush sets. */
  @Test
  public void testFileFlushTaskCopiesInputDefensively() throws Exception {
    var cache = mock(WOWCache.class);
    var mutableInput = new java.util.ArrayList<Integer>();
    mutableInput.add(5);
    var task = new FileFlushTask(cache, mutableInput);

    // Mutate the original collection AFTER construction — the wrapper must have copied
    mutableInput.add(99);

    task.call();

    var captor = org.mockito.ArgumentCaptor.forClass(IntOpenHashSet.class);
    verify(cache).executeFileFlush(captor.capture());
    org.junit.Assert.assertTrue(
        "Defensively copied set must still contain 5", captor.getValue().contains(5));
    org.junit.Assert.assertFalse(
        "Defensively copied set must NOT contain post-construction additions",
        captor.getValue().contains(99));
  }

  /** Verifies {@link FlushTillSegmentTask#call()} delegates with the captured segmentId and
   * propagates the return value (always {@code null} per the current contract because
   * {@code executeFlushTillSegment} returns {@code Void}, but a refactor that returns a
   * non-null wrapper would be caught). */
  @Test
  public void testFlushTillSegmentTaskDelegatesWithSegmentId() throws Exception {
    var cache = mock(WOWCache.class);
    // Void-returning methods are stubbed via doReturn; default Mockito return is null.

    var task = new FlushTillSegmentTask(cache, 99L);
    var result = task.call();

    assertNull("FlushTillSegmentTask must propagate the delegate's null return", result);
    verify(cache, times(1)).executeFlushTillSegment(99L);
  }

  /** Verifies {@link FindMinDirtySegment#call()} delegates to {@code executeFindDirtySegment}
   * and propagates the returned segment ID back to the caller. */
  @Test
  public void testFindMinDirtySegmentDelegatesAndPropagatesValue() throws Exception {
    var cache = mock(WOWCache.class);
    when(cache.executeFindDirtySegment()).thenReturn(42L);

    var task = new FindMinDirtySegment(cache);
    var result = task.call();

    assertEquals(Long.valueOf(42L), result);
    verify(cache, times(1)).executeFindDirtySegment();
  }

  /** Verifies {@link FindMinDirtySegment#call()} propagates a {@code null} return (the
   * documented "no dirty segment" sentinel). Without this test a refactor that wrapped
   * {@code null} in {@code Optional} or threw would silently break callers. */
  @Test
  public void testFindMinDirtySegmentPropagatesNullReturn() throws Exception {
    var cache = mock(WOWCache.class);
    when(cache.executeFindDirtySegment()).thenReturn(null);

    var task = new FindMinDirtySegment(cache);
    assertNull("FindMinDirtySegment must propagate the no-dirty-segment null sentinel",
        task.call());
  }

  /** Verifies {@link RemoveFilePagesTask#call()} forwards the captured fileId to
   * {@code doRemoveCachePages} and returns {@code null} as documented. */
  @Test
  public void testRemoveFilePagesTaskDelegatesToDoRemoveCachePages() throws Exception {
    var cache = mock(WOWCache.class);
    var task = new RemoveFilePagesTask(cache, 31);
    var result = task.call();

    assertNull("RemoveFilePagesTask.call() must always return null", result);
    verify(cache, times(1)).doRemoveCachePages(31);
  }

  /** Verifies {@link PeriodicFlushTask#run()} forwards itself as the {@code PeriodicFlushTask}
   * argument to {@code executePeriodicFlush}, allowing the cache to reschedule the same task
   * instance. The reschedule contract relies on identity, so {@code assertSame} is the
   * load-bearing check. */
  @Test
  public void testPeriodicFlushTaskPassesSelfToExecutePeriodicFlush() {
    var cache = mock(WOWCache.class);
    var task = new PeriodicFlushTask(cache);

    task.run();

    var captor = org.mockito.ArgumentCaptor.forClass(PeriodicFlushTask.class);
    verify(cache, times(1)).executePeriodicFlush(captor.capture());
    assertSame(
        "PeriodicFlushTask must forward itself (not a copy) so executePeriodicFlush can"
            + " reschedule the same instance",
        task, captor.getValue());
  }

  // --- Cross-wrapper sanity: distinct wrappers do not share state ---

  /**
   * Two {@link FileFlushTask} instances built from the same input list must each carry their
   * own copy of the file-id set, so calling one does not leak state into the other. This is
   * the multi-instance variant of the defensive-copy test.
   */
  @Test
  public void testFileFlushTaskInstancesAreIndependent() throws Exception {
    var cache = mock(WOWCache.class);
    var input = Arrays.asList(1, 2, 3);

    var taskA = new FileFlushTask(cache, input);
    var taskB = new FileFlushTask(cache, input);

    taskA.call();
    taskB.call();

    var captor = org.mockito.ArgumentCaptor.forClass(IntOpenHashSet.class);
    verify(cache, times(2)).executeFileFlush(captor.capture());
    var captures = captor.getAllValues();
    org.junit.Assert.assertNotSame(
        "Each wrapper must own its IntOpenHashSet — they cannot share storage",
        captures.get(0), captures.get(1));
    assertEquals(captures.get(0), captures.get(1));
  }

  /**
   * {@link DeleteFileTask} captures the externalFileId at construction; modifying any
   * external long after construction is impossible (long is a primitive), but verify the
   * arrival value is the construction-time value and that two distinct tasks deliver
   * distinct IDs.
   */
  @Test
  public void testDeleteFileTaskCarriesDistinctIdsForDistinctInstances() throws Exception {
    var cache = mock(WOWCache.class);
    // mock() returns null by default — no when() stubbing needed for the void-equivalent
    // RawPair return.

    var taskA = new DeleteFileTask(cache, 100L);
    var taskB = new DeleteFileTask(cache, 200L);

    taskA.call();
    taskB.call();

    verify(cache, times(1)).executeDeleteFile(100L);
    verify(cache, times(1)).executeDeleteFile(200L);
  }
}
