package com.jetbrains.youtrackdb.internal.common.concur.resource;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/**
 * Tests for {@link ReentrantResourcePool} covering reentrant acquisition (same key/thread),
 * counter tracking, and resource lifecycle. The constructor calls
 * YouTrackDBEnginesManager.instance() which triggers engine startup if needed.
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

  /** getConnectionsInCurrentThread tracks the acquisition count per key. */
  @Test
  public void testGetConnectionsInCurrentThread() {
    var pool = new ReentrantResourcePool<>(5, new StringListener());
    assertEquals(0, pool.getConnectionsInCurrentThread("key1"));

    pool.getResource("key1", -1);
    assertEquals(1, pool.getConnectionsInCurrentThread("key1"));

    pool.getResource("key1", -1);
    assertEquals(2, pool.getConnectionsInCurrentThread("key1"));

    pool.returnResource(pool.getResource("key1", -1));
    // After return of last acquired, count drops by 1
    assertEquals(2, pool.getConnectionsInCurrentThread("key1"));

    pool.returnResource(pool.getResource("key1", -1));
    assertEquals(2, pool.getConnectionsInCurrentThread("key1"));
  }

  /** Different keys on the same thread are independent resources. */
  @Test
  public void testDifferentKeysAreIndependent() {
    var pool = new ReentrantResourcePool<>(5, new StringListener());
    var r1 = pool.getResource("key1", -1);
    var r2 = pool.getResource("key2", -1);
    assertNotNull(r1);
    assertNotNull(r2);
    // Different resources for different keys
    assertNotNull(r1);
    assertNotNull(r2);
    pool.returnResource(r1);
    pool.returnResource(r2);
  }

  /** onShutdown nullifies thread-local; onStartup re-creates it. */
  @Test
  public void testShutdownStartupLifecycle() {
    var pool = new ReentrantResourcePool<>(5, new StringListener());
    var r1 = pool.getResource("key1", -1);
    pool.returnResource(r1);

    pool.onShutdown();
    pool.onStartup();

    // After restart, should be able to acquire again
    var r2 = pool.getResource("key1", -1);
    assertNotNull(r2);
    pool.returnResource(r2);
  }

  /** remove() cleans up the thread-local tracking state. */
  @Test
  public void testRemoveCleansUpThreadLocal() {
    var pool = new ReentrantResourcePool<>(5, new StringListener());
    var r1 = pool.getResource("key1", -1);
    assertEquals(1, pool.getConnectionsInCurrentThread("key1"));

    pool.remove(r1);
    // After remove, connection count should be 0
    assertEquals(0, pool.getConnectionsInCurrentThread("key1"));
  }
}
