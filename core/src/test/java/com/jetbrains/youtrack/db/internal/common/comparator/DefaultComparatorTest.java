package com.jetbrains.youtrack.db.internal.common.comparator;

import com.jetbrains.youtrack.db.internal.core.index.CompositeKey;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
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
