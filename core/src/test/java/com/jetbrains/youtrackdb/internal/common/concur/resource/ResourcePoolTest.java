package com.jetbrains.youtrackdb.internal.common.concur.resource;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.exception.AcquireTimeoutException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/**
 * Tests for {@link ResourcePool} covering acquire/return cycle, pool reuse, exhaustion
 * with timeout, metric getters, remove, close, and getAllResources.
 */
public class ResourcePoolTest {

  /** Simple listener that creates String resources with an incrementing counter. */
  private static class StringPoolListener
      implements ResourcePoolListener<String, String> {

    private final AtomicInteger counter = new AtomicInteger();

    @Override
    public String createNewResource(String key, Object... additionalArgs) {
      return "resource-" + counter.incrementAndGet();
    }

    @Override
    public boolean reuseResource(String key, Object[] additionalArgs, String value) {
      return true;
    }
  }

  /** Listener that rejects reuse — forces new resource creation each time. */
  private static class NonReusableListener
      implements ResourcePoolListener<String, String> {

    private final AtomicInteger counter = new AtomicInteger();

    @Override
    public String createNewResource(String key, Object... additionalArgs) {
      return "resource-" + counter.incrementAndGet();
    }

    @Override
    public boolean reuseResource(String key, Object[] additionalArgs, String value) {
      return false;
    }
  }

  // --- Basic acquire/return ---

  /** Acquire a resource and verify it is created by the listener. */
  @Test
  public void testAcquireResource() {
    var pool = new ResourcePool<>(5, new StringPoolListener());
    var resource = pool.getResource("key", -1);
    assertNotNull("Should acquire a resource", resource);
    assertTrue("Resource should be created by listener", resource.startsWith("resource-"));
    pool.returnResource(resource);
  }

  /** Return a resource to the pool and verify it is reused on next acquire. */
  @Test
  public void testReturnedResourceIsReused() {
    var pool = new ResourcePool<>(5, new StringPoolListener());
    var r1 = pool.getResource("key", -1);
    pool.returnResource(r1);
    var r2 = pool.getResource("key", -1);
    assertEquals("Returned resource should be reused", r1, r2);
    pool.returnResource(r2);
  }

  /**
   * When reuse is rejected by the listener, the returned resource is discarded
   * and a new one is created on next acquire.
   */
  @Test
  public void testNonReusableResourceDiscarded() {
    var pool = new ResourcePool<>(5, new NonReusableListener());
    var r1 = pool.getResource("key", -1);
    pool.returnResource(r1);
    var r2 = pool.getResource("key", -1);
    assertFalse("New resource should be created when reuse is rejected",
        r1.equals(r2));
    pool.returnResource(r2);
  }

  // --- Pool exhaustion and timeout ---

  /**
   * When all resources are checked out and a new acquire is attempted with a timeout,
   * AcquireTimeoutException is thrown.
   */
  @Test(timeout = 10_000, expected = AcquireTimeoutException.class)
  public void testAcquireTimeoutOnExhaustedPool() {
    var pool = new ResourcePool<>(1, new StringPoolListener());
    var r1 = pool.getResource("key", -1);
    assertNotNull(r1);
    // Pool is exhausted (max=1), this should timeout
    pool.getResource("key", 100);
  }

  // --- Metric getters ---

  /** getMaxResources returns the configured maximum. */
  @Test
  public void testGetMaxResources() {
    var pool = new ResourcePool<>(10, new StringPoolListener());
    assertEquals(10, pool.getMaxResources());
  }

  /** getAvailableResources reflects semaphore permits. */
  @Test
  public void testGetAvailableResources() {
    var pool = new ResourcePool<>(3, new StringPoolListener());
    assertEquals(3, pool.getAvailableResources());
    var r1 = pool.getResource("key", -1);
    assertEquals(2, pool.getAvailableResources());
    pool.returnResource(r1);
    assertEquals(3, pool.getAvailableResources());
  }

  /** getCreatedInstances tracks number of resources created. */
  @Test
  public void testGetCreatedInstances() {
    var pool = new ResourcePool<>(5, new StringPoolListener());
    assertEquals(0, pool.getCreatedInstances());
    var r1 = pool.getResource("key", -1);
    assertEquals(1, pool.getCreatedInstances());
    var r2 = pool.getResource("key", -1);
    assertEquals(2, pool.getCreatedInstances());
    pool.returnResource(r1);
    pool.returnResource(r2);
    // Reuse: no new creation
    pool.getResource("key", -1);
    assertEquals("Reused resource should not increment created count",
        2, pool.getCreatedInstances());
  }

  /** getInPoolResources tracks resources in the pool queue. */
  @Test
  public void testGetInPoolResources() {
    var pool = new ResourcePool<>(5, new StringPoolListener());
    assertEquals(0, pool.getInPoolResources());
    var r1 = pool.getResource("key", -1);
    assertEquals(0, pool.getInPoolResources());
    pool.returnResource(r1);
    assertEquals(1, pool.getInPoolResources());
  }

  /** getResourcesOutCount tracks resources currently checked out. */
  @Test
  public void testGetResourcesOutCount() {
    var pool = new ResourcePool<>(5, new StringPoolListener());
    assertEquals(0, pool.getResourcesOutCount());
    var r1 = pool.getResource("key", -1);
    assertEquals(1, pool.getResourcesOutCount());
    var r2 = pool.getResource("key", -1);
    assertEquals(2, pool.getResourcesOutCount());
    pool.returnResource(r1);
    assertEquals(1, pool.getResourcesOutCount());
    pool.returnResource(r2);
    assertEquals(0, pool.getResourcesOutCount());
  }

  // --- remove ---

  /** remove() deallocates a resource and releases a semaphore permit. */
  @Test
  public void testRemoveResource() {
    var pool = new ResourcePool<>(2, new StringPoolListener());
    var r1 = pool.getResource("key", -1);
    assertEquals(1, pool.getAvailableResources());
    pool.remove(r1);
    assertEquals("Semaphore permit should be released after remove",
        2, pool.getAvailableResources());
  }

  // --- close ---

  /** close() drains semaphore permits so no new resources can be acquired. */
  @Test(timeout = 10_000, expected = AcquireTimeoutException.class)
  public void testClosePreventsFurtherAcquisition() {
    var pool = new ResourcePool<>(5, new StringPoolListener());
    pool.close();
    // After close, no permits remain — should timeout immediately
    pool.getResource("key", 100);
  }

  // --- getAllResources ---

  /** getAllResources returns both in-pool and checked-out resources. */
  @Test
  public void testGetAllResources() {
    var pool = new ResourcePool<>(5, new StringPoolListener());
    var r1 = pool.getResource("key", -1);
    var r2 = pool.getResource("key", -1);
    pool.returnResource(r1);
    // r1 is in pool, r2 is out
    var allResources = pool.getAllResources();
    assertEquals("Should include both in-pool and checked-out",
        2, allResources.size());
    assertTrue("Should contain r1", allResources.contains(r1));
    assertTrue("Should contain r2", allResources.contains(r2));
    pool.returnResource(r2);
  }

  // --- Multi-threaded: concurrent acquire/return ---

  /** Multiple threads can acquire and return resources concurrently. */
  @Test(timeout = 10_000)
  public void testConcurrentAcquireReturn() throws Exception {
    var pool = new ResourcePool<>(4, new StringPoolListener());
    int threadCount = 4;
    var allAcquired = new CountDownLatch(threadCount);
    var canReturn = new CountDownLatch(1);
    var success = new AtomicBoolean(true);

    var threads = new Thread[threadCount];
    for (int i = 0; i < threadCount; i++) {
      threads[i] = new Thread(() -> {
        try {
          var res = pool.getResource("key", 5000);
          allAcquired.countDown();
          canReturn.await(10, TimeUnit.SECONDS);
          pool.returnResource(res);
        } catch (Exception e) {
          success.set(false);
        }
      });
      threads[i].start();
    }

    assertTrue("All threads should acquire resources",
        allAcquired.await(5, TimeUnit.SECONDS));
    assertEquals("All permits should be consumed",
        0, pool.getAvailableResources());

    canReturn.countDown();
    for (var t : threads) {
      t.join(5_000);
    }
    assertTrue("All threads should succeed", success.get());
    assertEquals("All permits should be restored",
        4, pool.getAvailableResources());
  }

  // --- Constructor with min resources ---

  /** Constructor with min > 0 pre-allocates min resources into the pool. */
  @Test
  public void testMinMaxConstructorPreAllocates() {
    var pool = new ResourcePool<>(2, 5, new StringPoolListener());
    assertEquals(5, pool.getMaxResources());
    assertEquals("min=2 should pre-allocate 2 resources",
        2, pool.getCreatedInstances());
    assertEquals("Pre-allocated resources should be in pool",
        2, pool.getInPoolResources());
    assertEquals("No permits should be consumed by pre-allocation",
        5, pool.getAvailableResources());
  }

  // --- Constructor validation ---

  /** Constructor rejects max < 1. */
  @Test(expected = IllegalArgumentException.class)
  public void testConstructorRejectsZeroMax() {
    new ResourcePool<>(0, new StringPoolListener());
  }

  /** Constructor rejects negative max. */
  @Test(expected = IllegalArgumentException.class)
  public void testConstructorRejectsNegativeMax() {
    new ResourcePool<>(-1, new StringPoolListener());
  }

  // --- Creation failure ---

  /**
   * When createNewResource throws, the semaphore permit must be released so
   * the pool doesn't permanently lose capacity.
   */
  @Test
  public void testSemaphoreReleasedWhenCreateNewResourceThrows() {
    var failingListener = new ResourcePoolListener<String, String>() {
      @Override
      public String createNewResource(String key, Object... args) {
        throw new RuntimeException("simulated creation failure");
      }

      @Override
      public boolean reuseResource(String key, Object[] args, String value) {
        return true;
      }
    };
    var pool = new ResourcePool<>(2, failingListener);
    assertEquals(2, pool.getAvailableResources());
    try {
      pool.getResource("key", -1);
    } catch (RuntimeException expected) {
      // expected
    }
    assertEquals("Permit must be restored after creation failure",
        2, pool.getAvailableResources());
  }
}
