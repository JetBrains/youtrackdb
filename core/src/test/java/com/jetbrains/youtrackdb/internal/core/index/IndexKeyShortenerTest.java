package com.jetbrains.youtrackdb.internal.core.index;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.junit.Test;

public class IndexKeyShortenerTest {

  @Test
  public void prefixBudgetsIncludeAllHeadersAndSmallestHiddenParts() {
    var string = new PropertyTypeInternal[] {PropertyTypeInternal.STRING};
    var binary = new PropertyTypeInternal[] {PropertyTypeInternal.BINARY};
    assertEquals(80, IndexKeyShortener.prefixLength(string, true, 100));
    assertEquals(76, IndexKeyShortener.prefixLength(string, false, 100));
    assertEquals(78, IndexKeyShortener.prefixLength(binary, true, 100));
    assertEquals(74, IndexKeyShortener.prefixLength(binary, false, 100));
    assertEquals(-1, IndexKeyShortener.prefixLength(string, true, 0));
    assertEquals(-1, IndexKeyShortener.prefixLength(string, true, -1));
    assertEquals(-1, IndexKeyShortener.prefixLength(null, true, 100));
    assertEquals(-1, IndexKeyShortener.prefixLength(new PropertyTypeInternal[0], true, 100));
    assertEquals(-1, IndexKeyShortener.prefixLength(
        new PropertyTypeInternal[] {PropertyTypeInternal.INTEGER}, true, 100));
    assertEquals(-1, IndexKeyShortener.prefixLength(
        new PropertyTypeInternal[] {PropertyTypeInternal.STRING, PropertyTypeInternal.STRING},
        true, 100));
  }

  @Test
  public void recordedLimitsAreReadFreshAndNonpositiveLimitsDisablePreparation() {
    var storage = mock(AbstractStorage.class);
    when(storage.getRecordedMaxIndexKeySize()).thenReturn(-1, 0, 100, 200);
    var index = new IndexNotUnique(storage);
    index.im = new IndexMetadata("idx",
        new SimpleKeyIndexDefinition(PropertyTypeInternal.STRING), Set.of(),
        "NOTUNIQUE", "BTREE", 1, null);
    String value = "x".repeat(1000);
    assertSame(value, index.getCollatingValue(value));
    assertSame(value, index.getCollatingValue(value));
    assertEquals("x".repeat(76), index.getCollatingValue(value));
    assertEquals("x".repeat(176), index.getCollatingValue(value));
  }

  @Test
  public void cutsKeepWholeCodePointsAndPreserveValuesWithinBudget() {
    for (String unit : new String[] {"a", "é", "€", "😀", "\uD800"}) {
      for (int prefix = 0; prefix <= 12; prefix++) {
        String value = unit.repeat(15);
        String result = (String) IndexKeyShortener.shorten(value, prefix);
        assertTrue(value.startsWith(result));
        assertTrue(result.getBytes(StandardCharsets.UTF_8).length <= prefix);
        assertEquals(0, result.length() % unit.length());
        assertSame(result, IndexKeyShortener.shorten(result, prefix));
      }
    }
    for (int budget = 0; budget <= 12; budget++) {
      assertEquals("é".repeat(budget / 2), IndexKeyShortener.shorten("é".repeat(15), budget));
      assertEquals("€".repeat(budget / 3), IndexKeyShortener.shorten("€".repeat(15), budget));
    }
    for (String value : new String[] {"é", "éé", "€", "€€", "é€"}) {
      int bytes = value.getBytes(StandardCharsets.UTF_8).length;
      assertSame(value, IndexKeyShortener.shorten(value, bytes));
      assertSame(value, IndexKeyShortener.shorten(value, bytes + 1));
    }
    assertSame("abc", IndexKeyShortener.shorten("abc", -1));
    assertNull(IndexKeyShortener.shorten(null, 10));
    assertEquals(42, IndexKeyShortener.shorten(42, 10));
    byte[] bytes = {1, 2, 3};
    assertSame(bytes, IndexKeyShortener.shorten(bytes, 3));
    assertArrayEquals(new byte[] {1, 2}, (byte[]) IndexKeyShortener.shorten(bytes, 2));
    assertArrayEquals(new byte[] {1, 2, 3}, bytes);
  }

  @Test
  public void lowerBoundsBecomeInclusiveOnlyWhereAStoredPrefixCanMeetThem() {
    for (int length = 0; length <= 12; length++) {
      assertEquals(length >= 7,
          IndexKeyShortener.inclusiveLowerBound("a".repeat(length), 10, false));
      assertEquals(length >= 10,
          IndexKeyShortener.inclusiveLowerBound(new byte[length], 10, false));
    }
    assertEquals("a".repeat(7), IndexKeyShortener.lowerBoundKey("a".repeat(10), 10));
    assertEquals("é".repeat(3), IndexKeyShortener.lowerBoundKey("é".repeat(5), 10));
    assertEquals("€".repeat(2), IndexKeyShortener.lowerBoundKey("€".repeat(3), 10));
    assertEquals("", IndexKeyShortener.lowerBoundKey("a", 2));
    assertEquals("long", IndexKeyShortener.lowerBoundKey("long", -1));
    assertEquals(42, IndexKeyShortener.lowerBoundKey(42, 10));
    assertNull(IndexKeyShortener.lowerBoundKey(null, 10));
    assertFalse(IndexKeyShortener.inclusiveLowerBound("long", -1, false));
    assertTrue(IndexKeyShortener.inclusiveLowerBound("long", -1, true));
    assertTrue(IndexKeyShortener.inclusiveLowerBound(null, 10, true));
    assertFalse(IndexKeyShortener.inclusiveLowerBound(42, 10, false));
    assertTrue(IndexKeyShortener.inclusiveLowerBound("a", 10, true));
  }
}
