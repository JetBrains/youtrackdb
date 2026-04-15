package com.jetbrains.youtrackdb.internal.common.comparator;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import java.util.Comparator;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests for {@link ComparatorFactory} comparator lookup logic.
 */
public class ComparatorFactoryTest {

  private final ComparatorFactory factory = ComparatorFactory.INSTANCE;

  // Verifies that getComparator(byte[].class) returns UnsafeByteArrayComparator when unsafe is
  // enabled (which is the default, since sun.misc.Unsafe is present on our JDK).
  @Test
  public void testByteArrayWithUnsafeEnabled() {
    boolean original = GlobalConfiguration.MEMORY_USE_UNSAFE.getValueAsBoolean();
    try {
      GlobalConfiguration.MEMORY_USE_UNSAFE.setValue(true);
      Comparator<byte[]> comp = factory.getComparator(byte[].class);
      Assert.assertNotNull(comp);
      Assert.assertSame(UnsafeByteArrayComparator.INSTANCE, comp);
    } finally {
      GlobalConfiguration.MEMORY_USE_UNSAFE.setValue(original);
    }
  }

  // Verifies that getComparator(byte[].class) falls back to ByteArrayComparator when unsafe is
  // disabled.
  @Test
  public void testByteArrayWithUnsafeDisabled() {
    boolean original = GlobalConfiguration.MEMORY_USE_UNSAFE.getValueAsBoolean();
    try {
      GlobalConfiguration.MEMORY_USE_UNSAFE.setValue(false);
      Comparator<byte[]> comp = factory.getComparator(byte[].class);
      Assert.assertNotNull(comp);
      Assert.assertSame(ByteArrayComparator.INSTANCE, comp);
    } finally {
      GlobalConfiguration.MEMORY_USE_UNSAFE.setValue(original);
    }
  }

  // Verifies that getComparator returns null for a class that has no registered comparator.
  @Test
  public void testNonByteArrayClassReturnsNull() {
    Comparator<String> comp = factory.getComparator(String.class);
    Assert.assertNull(comp);
  }

  // Verifies null is returned for Integer.class (another non-byte[] type).
  @Test
  public void testIntegerClassReturnsNull() {
    Comparator<Integer> comp = factory.getComparator(Integer.class);
    Assert.assertNull(comp);
  }
}
