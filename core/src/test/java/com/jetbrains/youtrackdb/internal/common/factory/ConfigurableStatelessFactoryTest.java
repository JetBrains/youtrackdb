package com.jetbrains.youtrackdb.internal.common.factory;

import java.util.Set;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for {@link ConfigurableStatelessFactory} — register, unregister, getImplementation, and
 * default implementation handling.
 */
public class ConfigurableStatelessFactoryTest {

  private ConfigurableStatelessFactory<String, String> factory;

  @Before
  public void setUp() {
    factory = new ConfigurableStatelessFactory<>();
  }

  // Verifies that registerImplementation stores and getImplementation retrieves correctly.
  @Test
  public void testRegisterAndGet() {
    factory.registerImplementation("key1", "value1");
    Assert.assertEquals("value1", factory.getImplementation("key1"));
  }

  // Verifies that unregisterImplementation removes the entry so it returns null.
  @Test
  public void testUnregister() {
    factory.registerImplementation("key1", "value1");
    factory.unregisterImplementation("key1");
    Assert.assertNull(factory.getImplementation("key1"));
  }

  // Verifies that a null key returns the default implementation.
  @Test
  public void testNullKeyReturnsDefault() {
    factory.setDefaultImplementation("defaultVal");
    Assert.assertEquals("defaultVal", factory.getImplementation(null));
  }

  // Verifies that a null key with no default returns null.
  @Test
  public void testNullKeyWithNoDefaultReturnsNull() {
    Assert.assertNull(factory.getImplementation(null));
  }

  // Verifies that an unregistered key returns null (not the default).
  @Test
  public void testUnregisteredKeyReturnsNull() {
    factory.setDefaultImplementation("defaultVal");
    Assert.assertNull(factory.getImplementation("missing"));
  }

  // Verifies getRegisteredImplementationNames returns the correct key set.
  @Test
  public void testGetRegisteredImplementationNames() {
    factory.registerImplementation("alpha", "a");
    factory.registerImplementation("beta", "b");
    Set<String> names = factory.getRegisteredImplementationNames();
    Assert.assertEquals(2, names.size());
    Assert.assertTrue(names.contains("alpha"));
    Assert.assertTrue(names.contains("beta"));
  }

  // Verifies that unregisterAllImplementations clears the registry entirely.
  @Test
  public void testUnregisterAllImplementations() {
    factory.registerImplementation("x", "1");
    factory.registerImplementation("y", "2");
    factory.unregisterAllImplementations();
    Assert.assertTrue(factory.getRegisteredImplementationNames().isEmpty());
  }

  // Verifies that setDefaultImplementation/getDefaultImplementation round-trips.
  @Test
  public void testGetDefaultImplementation() {
    Assert.assertNull(factory.getDefaultImplementation());
    factory.setDefaultImplementation("myDefault");
    Assert.assertEquals("myDefault", factory.getDefaultImplementation());
  }

  // Verifies that register overwrites a previous value for the same key.
  @Test
  public void testRegisterOverwritesPreviousValue() {
    factory.registerImplementation("key1", "oldValue");
    factory.registerImplementation("key1", "newValue");
    Assert.assertEquals("newValue", factory.getImplementation("key1"));
  }
}
