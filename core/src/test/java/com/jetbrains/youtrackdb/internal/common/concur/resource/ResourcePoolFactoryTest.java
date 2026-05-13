package com.jetbrains.youtrackdb.internal.common.concur.resource;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/**
 * Tests for {@link ResourcePoolFactory} covering pool creation/reuse, max pool size,
 * max partitions, getPools, close lifecycle, and post-close guards.
 */
public class ResourcePoolFactoryTest {

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

  private static final ResourcePoolFactory.ObjectFactoryFactory<String, String> FACTORY =
      key -> new StringListener();

  // --- get() ---

  /** get(key) creates a pool on first call and returns the same pool on subsequent calls. */
  @Test
  public void testGetCreatesAndReusesPool() {
    var factory = new ResourcePoolFactory<>(FACTORY);
    var pool1 = factory.get("key1");
    assertNotNull("Should create a pool", pool1);
    var pool2 = factory.get("key1");
    assertSame("Same key should return the same pool", pool1, pool2);
  }

  /** Different keys create different pools. */
  @Test
  public void testGetDifferentKeys() {
    var factory = new ResourcePoolFactory<>(FACTORY);
    var pool1 = factory.get("key1");
    var pool2 = factory.get("key2");
    assertNotNull(pool1);
    assertNotNull(pool2);
    assertNotSame("Different keys should create different pools", pool1, pool2);
  }

  // --- getPools() ---

  /** getPools returns all created pools with correct contents. */
  @Test
  public void testGetPools() {
    var factory = new ResourcePoolFactory<>(FACTORY);
    var pool1 = factory.get("key1");
    var pool2 = factory.get("key2");
    var pools = factory.getPools();
    assertEquals("Should have 2 pools", 2, pools.size());
    assertTrue("Should contain pool for key1", pools.contains(pool1));
    assertTrue("Should contain pool for key2", pools.contains(pool2));
  }

  // --- getMaxPoolSize / setMaxPoolSize ---

  /** Default max pool size is 64. */
  @Test
  public void testDefaultMaxPoolSize() {
    var factory = new ResourcePoolFactory<>(FACTORY);
    assertEquals(64, factory.getMaxPoolSize());
  }

  /** setMaxPoolSize changes the pool size and affects newly created pools. */
  @Test
  public void testSetMaxPoolSizeAffectsNewPools() {
    var factory = new ResourcePoolFactory<>(FACTORY);
    factory.setMaxPoolSize(3);
    assertEquals(3, factory.getMaxPoolSize());
    var pool = factory.get("key1");
    assertEquals("Pool should use the configured maxPoolSize",
        3, pool.getMaxResources());
  }

  // --- getMaxPartitions / setMaxPartitions ---

  /** getMaxPartitions returns the configured maximum. */
  @Test
  public void testGetMaxPartitions() {
    var factory = new ResourcePoolFactory<>(FACTORY);
    assertTrue("Max partitions should be positive", factory.getMaxPartitions() > 0);
  }

  /** setMaxPartitions changes the partition limit. */
  @Test
  public void testSetMaxPartitions() {
    var factory = new ResourcePoolFactory<>(FACTORY);
    factory.setMaxPartitions(32);
    assertEquals(32, factory.getMaxPartitions());
  }

  // --- close() ---

  /** close() closes all pools and prevents further get() calls. */
  @Test(expected = IllegalStateException.class)
  public void testClosePreventsFurtherGet() {
    var factory = new ResourcePoolFactory<>(FACTORY);
    factory.get("key1");
    factory.close();
    factory.get("key2");
  }

  /** setMaxPoolSize after close throws IllegalStateException. */
  @Test(expected = IllegalStateException.class)
  public void testSetMaxPoolSizeAfterCloseThrows() {
    var factory = new ResourcePoolFactory<>(FACTORY);
    factory.close();
    factory.setMaxPoolSize(128);
  }

  /** getPools after close throws IllegalStateException. */
  @Test(expected = IllegalStateException.class)
  public void testGetPoolsAfterCloseThrows() {
    var factory = new ResourcePoolFactory<>(FACTORY);
    factory.close();
    factory.getPools();
  }

  /** Double close is idempotent. */
  @Test
  public void testDoubleCloseIsIdempotent() {
    var factory = new ResourcePoolFactory<>(FACTORY);
    factory.get("key1");
    factory.close();
    factory.close(); // Should not throw
  }

  // --- Constructor with capacity ---

  /** Constructor with custom capacity creates a functional factory. */
  @Test
  public void testConstructorWithCapacity() {
    var factory = new ResourcePoolFactory<>(FACTORY, 50);
    var pool = factory.get("key1");
    assertNotNull("Should create a pool", pool);
    assertEquals("Factory should track the pool", 1, factory.getPools().size());
  }
}
