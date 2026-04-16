package com.jetbrains.youtrackdb.internal.common.concur.resource;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/**
 * Tests for {@link ReentrantResourcePool} covering reentrant acquisition (same key/thread),
 * counter tracking with full unwinding, independent keys, shutdown/startup lifecycle, and
 * remove. The constructor calls YouTrackDBEnginesManager.instance() which triggers engine
 * startup if needed.
 */
public class ReentrantResourcePoolTest {

  private static class StringListener
      implements ResourcePoolListener<String, String> {

    private final AtomicInteger counter = new AtomicInteger();

    @Override
    public String createNewResource(String key, Object... args) {
      return "res-" + counter.incrementAndGet();
    }

    @Override
    public boolean reuseResource(String key, Object[] args, String value) {
      return true;
    }
  }

  /**
   * First acquire creates a new resource. Second acquire with the same key on the
   * same thread returns the SAME resource (reentrant) without consuming a semaphore
   * permit.
   */
  @Test
  public void testReentrantAcquisitionReturnsSameResource() {
    var pool = new ReentrantResourcePool<>(5, new StringListener());
    var r1 = pool.getResource("key1", -1);
    assertNotNull(r1);

    // Same key, same thread: should return the same resource
    var r2 = pool.getResource("key1", -1);
    assertSame("Reentrant acquire should return the same resource", r1, r2);

    pool.returnResource(r2);
    pool.returnResource(r1);
  }

  /**
   * Full reentrant unwinding: acquire 3 times, return 3 times. Only the final
   * return (counter→0) actually returns the resource to the parent pool and
   * restores the semaphore permit.
   */
  @Test
  public void testFullReentrantUnwindReturnsResourceToPool() {
    var pool = new ReentrantResourcePool<>(2, new StringListener());
    var r = pool.getResource("key1", -1);
    pool.getResource("key1", -1);
    pool.getResource("key1", -1);
    assertEquals(3, pool.getConnectionsInCurrentThread("key1"));
    assertEquals(1, pool.getAvailableResources());

    // First two returns: counter 3→2→1, resource NOT returned to parent
    pool.returnResource(r);
    assertEquals(2, pool.getConnectionsInCurrentThread("key1"));
    assertEquals(1, pool.getAvailableResources());

    pool.returnResource(r);
    assertEquals(1, pool.getConnectionsInCurrentThread("key1"));
    assertEquals(1, pool.getAvailableResources());

    // Final return: counter 1→0, resource IS returned to parent pool
    pool.returnResource(r);
    assertEquals(0, pool.getConnectionsInCurrentThread("key1"));
    assertEquals(2, pool.getAvailableResources());
  }

  /**
   * Different keys on the same thread produce independent (different) resources
   * with independent connection tracking.
   */
  @Test
  public void testDifferentKeysAreIndependent() {
    var pool = new ReentrantResourcePool<>(5, new StringListener());
    var r1 = pool.getResource("key1", -1);
    var r2 = pool.getResource("key2", -1);
    assertNotNull(r1);
    assertNotNull(r2);
    assertNotSame("Different keys must produce different resources", r1, r2);
    assertEquals(1, pool.getConnectionsInCurrentThread("key1"));
    assertEquals(1, pool.getConnectionsInCurrentThread("key2"));
    pool.returnResource(r1);
    pool.returnResource(r2);
  }

  /**
   * onShutdown nullifies thread-local; onStartup re-creates it. After
   * shutdown+startup, connection tracking is reset to zero.
   */
  @Test
  public void testShutdownStartupLifecycle() {
    var pool = new ReentrantResourcePool<>(5, new StringListener());
    var r1 = pool.getResource("key1", -1);
    assertEquals(1, pool.getConnectionsInCurrentThread("key1"));
    pool.returnResource(r1);

    pool.onShutdown();
    pool.onStartup();

    // After restart, thread-local state should be cleared
    assertEquals("Connection tracking should be reset after shutdown/startup",
        0, pool.getConnectionsInCurrentThread("key1"));
    var r2 = pool.getResource("key1", -1);
    assertNotNull(r2);
    assertEquals(1, pool.getConnectionsInCurrentThread("key1"));
    pool.returnResource(r2);
  }

  /** remove() cleans up thread-local tracking and restores semaphore permit. */
  @Test
  public void testRemoveCleansUpThreadLocal() {
    var pool = new ReentrantResourcePool<>(5, new StringListener());
    var r1 = pool.getResource("key1", -1);
    assertEquals(1, pool.getConnectionsInCurrentThread("key1"));
    assertEquals(4, pool.getAvailableResources());

    pool.remove(r1);
    assertEquals("Thread-local tracking should be cleared",
        0, pool.getConnectionsInCurrentThread("key1"));
    assertEquals("Semaphore permit should be restored after remove",
        5, pool.getAvailableResources());
  }
}
