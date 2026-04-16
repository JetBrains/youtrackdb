package com.jetbrains.youtrackdb.internal.common.concur.resource;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/**
 * Tests for {@link ResourcePoolFactory} covering pool creation/reuse, max pool size,
 * max partitions, getPools, and close lifecycle.
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
    assertTrue("Different keys should create different pools", pool1 != pool2);
  }

  // --- getPools() ---

  /** getPools returns all created pools. */
  @Test
  public void testGetPools() {
    var factory = new ResourcePoolFactory<>(FACTORY);
    factory.get("key1");
    factory.get("key2");
    assertEquals("Should have 2 pools", 2, factory.getPools().size());
  }

  // --- getMaxPoolSize / setMaxPoolSize ---

  /** Default max pool size is 64. */
  @Test
  public void testDefaultMaxPoolSize() {
    var factory = new ResourcePoolFactory<>(FACTORY);
    assertEquals(64, factory.getMaxPoolSize());
  }

  /** setMaxPoolSize changes the pool size. */
  @Test
  public void testSetMaxPoolSize() {
    var factory = new ResourcePoolFactory<>(FACTORY);
    factory.setMaxPoolSize(128);
    assertEquals(128, factory.getMaxPoolSize());
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
  public void testClosePreventsFurtherAccess() {
    var factory = new ResourcePoolFactory<>(FACTORY);
    factory.get("key1");
    factory.close();
    factory.get("key2"); // Should throw IllegalStateException
  }

  // --- Constructor with capacity ---

  /** Constructor with custom capacity. */
  @Test
  public void testConstructorWithCapacity() {
    var factory = new ResourcePoolFactory<>(FACTORY, 50);
    assertNotNull(factory.get("key1"));
  }
}
