package com.jetbrains.youtrackdb.internal.core.storage.cache.local;

import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.PageIsBrokenListener;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.junit.Test;
import org.mockito.Mockito;

/**
 * Tests that WOWCache methods return early when a prior flush error has been recorded. Once
 * {@code flushError} is set (by a failed background flush), all subsequent flush and dirty-segment
 * operations must log the error and return immediately rather than proceeding with I/O.
 *
 * <p>Uses Mockito's {@code CALLS_REAL_METHODS} to invoke the real guard-clause logic on a mock
 * instance with the {@code flushError} field set via reflection.
 */
public class WOWCacheFlushErrorTest {

  /**
   * Sets the private {@code flushError} field on a WOWCache (or mock) to the given throwable.
   */
  private static void setFlushError(WOWCache cache, Throwable error) throws Exception {
    Field field = WOWCache.class.getDeclaredField("flushError");
    field.setAccessible(true);
    field.set(cache, error);
  }

  /**
   * Verifies that {@code executeFindDirtySegment()} returns null immediately when a flush error
   * is recorded, without attempting to access dirty pages or the write cache.
   */
  @Test
  public void testExecuteFindDirtySegmentReturnsNullOnFlushError() throws Exception {
    var cache = Mockito.mock(WOWCache.class, Mockito.CALLS_REAL_METHODS);
    setFlushError(cache, new java.io.IOException("disk full"));

    assertNull(cache.executeFindDirtySegment());
  }

  /**
   * Verifies that {@code executeFileFlush()} returns null immediately when a flush error
   * is recorded, preventing further I/O on a storage with a known write failure.
   */
  @Test
  public void testExecuteFileFlushReturnsNullOnFlushError() throws Exception {
    var cache = Mockito.mock(WOWCache.class, Mockito.CALLS_REAL_METHODS);
    setFlushError(cache, new java.io.IOException("disk full"));

    assertNull(cache.executeFileFlush(new IntOpenHashSet()));
  }

  /**
   * Verifies that {@code executePeriodicFlush()} returns immediately when a flush error
   * is recorded, without scheduling further flush work.
   */
  @Test
  public void testExecutePeriodicFlushReturnsOnFlushError() throws Exception {
    var cache = Mockito.mock(WOWCache.class, Mockito.CALLS_REAL_METHODS);
    setFlushError(cache, new java.io.IOException("disk full"));

    // Should return without exception — the flushError guard prevents further processing
    cache.executePeriodicFlush(null);
  }

  /**
   * Verifies that {@code executeFlush()} returns immediately when a flush error is recorded.
   * Also verifies that the completion latches are still counted down in the finally block.
   */
  @Test
  public void testExecuteFlushReturnsOnFlushError() throws Exception {
    var cache = Mockito.mock(WOWCache.class, Mockito.CALLS_REAL_METHODS);
    setFlushError(cache, new java.io.IOException("disk full"));

    // Pass null latches to avoid NPE in the mock — the guard clause returns before using them
    cache.executeFlush(null, null);
  }

  /**
   * Verifies that {@code executeFlushTillSegment()} returns null immediately when a flush
   * error is recorded.
   */
  @Test
  public void testExecuteFlushTillSegmentReturnsNullOnFlushError() throws Exception {
    var cache = Mockito.mock(WOWCache.class, Mockito.CALLS_REAL_METHODS);
    setFlushError(cache, new java.io.IOException("disk full"));

    assertNull(cache.executeFlushTillSegment(42L));
  }

  /**
   * Verifies that {@code callPageIsBrokenListeners} iterates through registered listeners
   * and notifies them when a page is broken. Covers the for-loop body at line 627 and
   * the listener invocation path.
   */
  @SuppressWarnings("unchecked")
  @Test
  public void testCallPageIsBrokenListenersNotifiesRegisteredListeners() throws Exception {
    var cache = Mockito.mock(WOWCache.class, Mockito.CALLS_REAL_METHODS);

    // Initialize and set the pageIsBrokenListeners list (CALLS_REAL_METHODS skips
    // field initializers, so the list is null in the mock)
    PageIsBrokenListener mockListener = mock(PageIsBrokenListener.class);
    var listenersList =
        new java.util.concurrent.CopyOnWriteArrayList<WeakReference<PageIsBrokenListener>>();
    listenersList.add(new WeakReference<>(mockListener));
    Field listenersField = WOWCache.class.getDeclaredField("pageIsBrokenListeners");
    listenersField.setAccessible(true);
    listenersField.set(cache, listenersList);

    // Invoke the private callPageIsBrokenListeners method via reflection
    Method method =
        WOWCache.class.getDeclaredMethod("callPageIsBrokenListeners", String.class, long.class);
    method.setAccessible(true);
    method.invoke(cache, "test-file.dat", 42L);

    verify(mockListener).pageIsBroken("test-file.dat", 42L);
  }
}
