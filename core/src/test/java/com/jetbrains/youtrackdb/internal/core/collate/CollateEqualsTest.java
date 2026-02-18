package com.jetbrains.youtrackdb.internal.core.collate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

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
    assertTrue(collate1.equals(collate2));
    assertEquals(collate1.hashCode(), collate2.hashCode());
  }

  @Test
  public void testDefaultCollateNotEqualsDifferentType() {
    var defaultCollate = new DefaultCollate();
    assertFalse(defaultCollate.equals(new CaseInsensitiveCollate()));
    assertFalse(defaultCollate.equals(null));
    assertFalse(defaultCollate.equals("not a collate"));
  }

  @Test
  public void testCaseInsensitiveCollateEqualsSameType() {
    var collate1 = new CaseInsensitiveCollate();
    var collate2 = new CaseInsensitiveCollate();
    assertTrue(collate1.equals(collate2));
    assertEquals(collate1.hashCode(), collate2.hashCode());
  }

  @Test
  public void testCaseInsensitiveCollateNotEqualsDifferentType() {
    var ciCollate = new CaseInsensitiveCollate();
    assertFalse(ciCollate.equals(new DefaultCollate()));
    assertFalse(ciCollate.equals(null));
    assertFalse(ciCollate.equals("not a collate"));
  }

  @Test
  public void testDifferentCollateTypesNotEqual() {
    var defaultCollate = new DefaultCollate();
    var ciCollate = new CaseInsensitiveCollate();
    assertNotEquals(defaultCollate, ciCollate);
    assertNotEquals(ciCollate, defaultCollate);
  }
}
