package com.jetbrains.youtrackdb.internal.common.factory;

import com.jetbrains.youtrackdb.internal.common.exception.SystemException;
import java.util.Set;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for {@link ConfigurableStatefulFactory} — register, unregister, newInstance, default class,
 * and error handling.
 */
public class ConfigurableStatefulFactoryTest {

  // Concrete test implementation that the factory can instantiate.
  public static class TestImpl implements Runnable {
    @Override
    public void run() {
      // no-op
    }
  }

  // Another implementation to verify registration of multiple keys.
  public static class AnotherImpl implements Runnable {
    @Override
    public void run() {
      // no-op
    }
  }

  // Implementation whose default constructor always throws, to test error wrapping.
  public static class FailingImpl implements Runnable {
    public FailingImpl() {
      throw new RuntimeException("construction failed");
    }

    @Override
    public void run() {
      // never reached
    }
  }

  private ConfigurableStatefulFactory<String, Runnable> factory;

  @Before
  public void setUp() {
    factory = new ConfigurableStatefulFactory<>();
  }

  // Verifies that register/unregister correctly updates the registry.
  @Test
  public void testRegisterAndUnregister() {
    factory.register("key1", TestImpl.class);
    Assert.assertSame(TestImpl.class, factory.get("key1"));

    factory.unregister("key1");
    Assert.assertNull(factory.get("key1"));
  }

  // Verifies that newInstance creates a fresh object of the registered class.
  @Test
  public void testNewInstanceWithValidKey() {
    factory.register("key1", TestImpl.class);
    Runnable instance = factory.newInstance("key1");
    Assert.assertNotNull(instance);
    Assert.assertTrue(instance instanceof TestImpl);
  }

  // Verifies that null key falls back to the default class when one is set.
  @Test
  public void testNewInstanceNullKeyWithDefault() {
    factory.setDefaultClass(TestImpl.class);
    Runnable instance = factory.newInstance(null);
    Assert.assertNotNull(instance);
    Assert.assertTrue(instance instanceof TestImpl);
  }

  // Verifies that null key without a default class throws IllegalArgumentException.
  @Test(expected = IllegalArgumentException.class)
  public void testNewInstanceNullKeyWithoutDefaultThrows() {
    factory.newInstance(null);
  }

  // Verifies that an instantiation failure wraps the cause in a SystemException.
  @Test
  public void testInstantiationFailureWrapsException() {
    factory.register("fail", FailingImpl.class);
    try {
      factory.newInstance("fail");
      Assert.fail("Expected SystemException to be thrown");
    } catch (SystemException e) {
      Assert.assertTrue(
          "Message should reference the class name",
          e.getMessage().contains("FailingImpl"));
    }
  }

  // Verifies that newInstanceOfDefaultClass returns null when no default is set.
  @Test
  public void testNewInstanceOfDefaultClassNullDefault() {
    Assert.assertNull(factory.newInstanceOfDefaultClass());
  }

  // Verifies that newInstanceOfDefaultClass creates an instance when a default is set.
  @Test
  public void testNewInstanceOfDefaultClassWithDefault() {
    factory.setDefaultClass(AnotherImpl.class);
    Runnable instance = factory.newInstanceOfDefaultClass();
    Assert.assertNotNull(instance);
    Assert.assertTrue(instance instanceof AnotherImpl);
  }

  // Verifies that getRegisteredNames returns the correct set of keys.
  @Test
  public void testGetRegisteredNames() {
    factory.register("alpha", TestImpl.class);
    factory.register("beta", AnotherImpl.class);
    Set<String> names = factory.getRegisteredNames();
    Assert.assertEquals(2, names.size());
    Assert.assertTrue(names.contains("alpha"));
    Assert.assertTrue(names.contains("beta"));
  }

  // Verifies that unregisterAll clears all entries.
  @Test
  public void testUnregisterAll() {
    factory.register("a", TestImpl.class);
    factory.register("b", AnotherImpl.class);
    factory.unregisterAll();
    Assert.assertTrue(factory.getRegisteredNames().isEmpty());
  }

  // Verifies that setDefaultClass/getDefaultClass round-trips correctly.
  @Test
  public void testGetDefaultClass() {
    Assert.assertNull(factory.getDefaultClass());
    factory.setDefaultClass(TestImpl.class);
    Assert.assertSame(TestImpl.class, factory.getDefaultClass());
  }

  // Verifies that when a key is not registered but a default class exists,
  // newInstance falls through to the default class.
  @Test
  public void testNewInstanceUnregisteredKeyFallsToDefault() {
    factory.setDefaultClass(TestImpl.class);
    Runnable instance = factory.newInstance("nonexistent");
    Assert.assertNotNull(instance);
    Assert.assertTrue(instance instanceof TestImpl);
  }

  // Verifies that instantiation failure in default class is also wrapped.
  @Test
  public void testDefaultClassInstantiationFailureWrapsException() {
    factory.setDefaultClass(FailingImpl.class);
    try {
      factory.newInstanceOfDefaultClass();
      Assert.fail("Expected SystemException to be thrown");
    } catch (SystemException e) {
      Assert.assertTrue(
          "Message should reference the default class name",
          e.getMessage().contains("FailingImpl"));
    }
  }

  // Verifies that newInstance with an unregistered key and no default
  // returns null rather than throwing.
  @Test
  public void testNewInstanceUnregisteredKeyNoDefaultReturnsNull() {
    Runnable instance = factory.newInstance("nonexistent");
    Assert.assertNull(instance);
  }
}
