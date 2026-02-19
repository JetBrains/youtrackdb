package com.jetbrains.youtrackdb.internal.core.gremlin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.jetbrains.youtrackdb.api.gremlin.embedded.YTDBProperty;
import org.junit.Test;

/**
 * Tests for YTDBEmptyProperty singleton, verifying equals/hashCode contract
 * and basic property behavior.
 */
public class YTDBEmptyPropertyTest {

  /** Verify hashCode() is consistent across calls and between instances. */
  @Test
  public void testHashCodeConsistency() {
    YTDBProperty<String> prop1 = YTDBEmptyProperty.instance();
    YTDBProperty<String> prop2 = YTDBEmptyProperty.instance();

    assertEquals(prop1.hashCode(), prop2.hashCode());
    assertEquals(0, prop1.hashCode());
  }

  /** Verify equals() identifies instances correctly. */
  @Test
  public void testEquals() {
    YTDBProperty<String> prop1 = YTDBEmptyProperty.instance();
    YTDBProperty<String> prop2 = YTDBEmptyProperty.instance();

    assertEquals(prop1, prop2);
  }

  /** Verify isPresent() returns false for empty property. */
  @Test
  public void testIsNotPresent() {
    YTDBProperty<String> prop = YTDBEmptyProperty.instance();
    assertFalse(prop.isPresent());
  }
}
