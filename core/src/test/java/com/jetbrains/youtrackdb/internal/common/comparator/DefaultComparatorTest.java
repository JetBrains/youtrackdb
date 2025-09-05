package com.jetbrains.youtrackdb.internal.common.comparator;

import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.index.CompositeKey;
import java.util.Comparator;
import org.junit.Assert;
import org.junit.Test;

/**
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
}
