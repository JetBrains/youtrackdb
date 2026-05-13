package com.jetbrains.youtrackdb.internal.common.comparator;

import com.jetbrains.youtrackdb.internal.core.index.CompositeKey;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import java.util.Comparator;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests for {@link DefaultComparator} ordering behavior with various key types.
 *
 * @since 11.07.12
 */
public class DefaultComparatorTest {

  private final DefaultComparator comparator = DefaultComparator.INSTANCE;

  @Test
  public void testCompareStrings() {
    final var keyOne = new CompositeKey("name4", PropertyType.STRING);
    final var keyTwo = new CompositeKey("name5", PropertyType.STRING);

    assertCompareTwoKeys(comparator, keyOne, keyTwo);
  }

  private void assertCompareTwoKeys(
      final Comparator<Object> comparator, final Object keyOne, final Object keyTwo) {
    Assert.assertTrue(comparator.compare(keyOne, keyTwo) < 0);
    Assert.assertTrue(comparator.compare(keyTwo, keyOne) > 0);
    Assert.assertEquals(0, comparator.compare(keyTwo, keyTwo));
  }

  // Verifies that comparing two null values returns zero (both null treated as equal).
  @Test
  public void testBothNullReturnsZero() {
    Assert.assertEquals(0, comparator.compare(null, null));
  }

  // Verifies that null is treated as smaller than any non-null value.
  @Test
  public void testNullFirstIsSmaller() {
    Assert.assertTrue(comparator.compare(null, "abc") < 0);
  }

  // Verifies that a non-null value is treated as larger than null.
  @Test
  public void testNullSecondIsLarger() {
    Assert.assertTrue(comparator.compare("abc", null) > 0);
  }

  // Verifies the fast-path: same reference returns zero immediately.
  @Test
  public void testSameReferenceReturnsZero() {
    Object obj = new Object() {
      @Override
      public boolean equals(Object o) {
        return this == o;
      }
    };
    Assert.assertEquals(0, comparator.compare(obj, obj));
  }

  // Verifies that Comparable objects are compared via compareTo.
  @Test
  public void testComparablePathDelegates() {
    Assert.assertTrue(comparator.compare("apple", "banana") < 0);
    Assert.assertTrue(comparator.compare("banana", "apple") > 0);
    Assert.assertEquals(0, comparator.compare("hello", "hello"));
  }

  // Verifies that non-Comparable objects with a ComparatorFactory entry are compared.
  @Test
  public void testNonComparableUsesFactory() {
    byte[] a = new byte[] {1, 2, 3};
    byte[] b = new byte[] {1, 2, 4};
    byte[] aCopy = new byte[] {1, 2, 3};
    Assert.assertTrue(comparator.compare(a, b) < 0);
    Assert.assertTrue(comparator.compare(b, a) > 0);
    // Use distinct but equal arrays to exercise the factory path, not identity.
    Assert.assertEquals(0, comparator.compare(a, aCopy));
  }

  // Verifies that non-Comparable objects without a factory mapping throw IllegalStateException.
  @Test(expected = IllegalStateException.class)
  public void testNonComparableWithoutFactoryThrowsException() {
    Object a = new Object() {
    };
    Object b = new Object() {
    };
    comparator.compare(a, b);
  }
}
