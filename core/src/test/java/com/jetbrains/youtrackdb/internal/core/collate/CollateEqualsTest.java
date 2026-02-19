package com.jetbrains.youtrackdb.internal.core.collate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

/**
 * Tests for the equals() and hashCode() methods of Collate implementations.
 * Verifies that equals() correctly identifies same-type instances as equal
 * and different-type instances as not equal.
 */
public class CollateEqualsTest {

  @Test
  public void testDefaultCollateEqualsSameType() {
    var collate1 = new DefaultCollate();
    var collate2 = new DefaultCollate();
    assertEquals(collate1, collate2);
    assertEquals(collate1.hashCode(), collate2.hashCode());
  }

  @Test
  public void testDefaultCollateNotEqualsDifferentType() {
    var defaultCollate = new DefaultCollate();
    assertNotEquals(defaultCollate, new CaseInsensitiveCollate());
    assertNotEquals(defaultCollate, null);
    // Intentionally test equals() against an unrelated type to verify the contract
    assertNotEquals(defaultCollate, "not a collate");
  }

  @Test
  public void testCaseInsensitiveCollateEqualsSameType() {
    var collate1 = new CaseInsensitiveCollate();
    var collate2 = new CaseInsensitiveCollate();
    assertEquals(collate1, collate2);
    assertEquals(collate1.hashCode(), collate2.hashCode());
  }

  @Test
  public void testCaseInsensitiveCollateNotEqualsDifferentType() {
    var ciCollate = new CaseInsensitiveCollate();
    assertNotEquals(ciCollate, new DefaultCollate());
    assertNotEquals(ciCollate, null);
    // Intentionally test equals() against an unrelated type to verify the contract
    assertNotEquals(ciCollate, "not a collate");
  }

  @Test
  public void testDifferentCollateTypesNotEqual() {
    var defaultCollate = new DefaultCollate();
    var ciCollate = new CaseInsensitiveCollate();
    assertNotEquals(defaultCollate, ciCollate);
    assertNotEquals(ciCollate, defaultCollate);
  }
}
