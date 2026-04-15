package com.jetbrains.youtrackdb.internal.common.comparator;

import org.junit.Assert;
import org.junit.Test;

/**
 * Tests for {@link CaseInsentiveComparator} case-insensitive string comparison.
 */
public class CaseInsensitiveComparatorTest {

  private final CaseInsentiveComparator comparator = new CaseInsentiveComparator();

  // Verifies that two strings differing only in case are treated as equal.
  @Test
  public void testEqualStringsDifferingInCase() {
    Assert.assertEquals(0, comparator.compare("Hello", "hello"));
    Assert.assertEquals(0, comparator.compare("ABC", "abc"));
    Assert.assertEquals(0, comparator.compare("mixedCase", "MIXEDCASE"));
  }

  // Verifies that case-insensitive ordering is correct (alphabetical regardless of case).
  @Test
  public void testCaseInsensitiveOrdering() {
    Assert.assertTrue(comparator.compare("apple", "Banana") < 0);
    Assert.assertTrue(comparator.compare("APPLE", "banana") < 0);
  }

  // Verifies reversed comparison direction.
  @Test
  public void testReversedComparison() {
    Assert.assertTrue(comparator.compare("banana", "Apple") > 0);
    Assert.assertTrue(comparator.compare("BANANA", "apple") > 0);
  }

  // Verifies that identical strings (same case) compare as equal.
  @Test
  public void testExactSameStrings() {
    Assert.assertEquals(0, comparator.compare("same", "same"));
  }

  // Verifies comparison of empty strings.
  @Test
  public void testEmptyStrings() {
    Assert.assertEquals(0, comparator.compare("", ""));
    Assert.assertTrue(comparator.compare("", "a") < 0);
    Assert.assertTrue(comparator.compare("a", "") > 0);
  }
}
