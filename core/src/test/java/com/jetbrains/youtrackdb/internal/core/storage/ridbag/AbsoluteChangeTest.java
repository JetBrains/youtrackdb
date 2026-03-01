package com.jetbrains.youtrackdb.internal.core.storage.ridbag;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.common.serialization.types.ByteSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.IntegerSerializer;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import org.junit.Test;

/**
 * Tests AbsoluteChange counter behavior: increment with cap, decrement to zero floor,
 * clear returning the old value, applyTo semantics, and binary serialization format.
 */
public class AbsoluteChangeTest {

  /**
   * Increment should increase the counter by 1 when below the max cap.
   * Returns true when the value actually increased.
   */
  @Test
  public void testIncrementBelowCapIncreasesValueByOne() {
    var change = new AbsoluteChange(3, new RecordId(1, 1));

    assertTrue(change.increment(10));
    assertEquals(4, change.getValue());
  }

  /**
   * Increment at the max cap should not change the value.
   * Returns false since the value didn't actually increase.
   */
  @Test
  public void testIncrementAtCapDoesNotExceedMaximum() {
    var change = new AbsoluteChange(10, new RecordId(1, 1));

    assertFalse(change.increment(10));
    assertEquals(10, change.getValue());
  }

  /**
   * Decrement should decrease the counter by 1 and return true when the previous
   * value was positive.
   */
  @Test
  public void testDecrementPositiveValueReturnsTrueAndDecreases() {
    var change = new AbsoluteChange(3, new RecordId(1, 1));

    assertTrue(change.decrement());
    assertEquals(2, change.getValue());
  }

  /**
   * Decrement from zero returns false (was not positive) and the value stays at zero
   * because the internal checkPositive() clamps negative values.
   */
  @Test
  public void testDecrementFromZeroReturnsFalseAndClampsToZero() {
    var change = new AbsoluteChange(0, new RecordId(1, 1));

    assertFalse(change.decrement());
    assertEquals(0, change.getValue());
  }

  /**
   * Clear resets the counter to zero and returns the old value.
   */
  @Test
  public void testClearResetsToZeroAndReturnsOldValue() {
    var change = new AbsoluteChange(7, new RecordId(1, 1));

    assertEquals(7, change.clear());
    assertEquals(0, change.getValue());
  }

  /**
   * applyTo returns the stored counter value (ignoring the input value), since
   * AbsoluteChange replaces rather than merges.
   */
  @Test
  public void testApplyToReturnsStoredValueIgnoringInput() {
    var change = new AbsoluteChange(5, new RecordId(1, 1));

    assertEquals(5, change.applyTo(100, 10));
  }

  /**
   * Serialize produces the expected binary format: 1-byte type marker followed by
   * 4-byte int value.
   */
  @Test
  public void testSerializeProducesCorrectBinaryFormat() {
    var change = new AbsoluteChange(42, new RecordId(1, 1));
    var stream = new byte[AbsoluteChange.SIZE + 10];
    int offset = 5;

    int written = change.serialize(stream, offset);

    assertEquals(ByteSerializer.BYTE_SIZE + IntegerSerializer.INT_SIZE, written);
    assertEquals(AbsoluteChange.TYPE, stream[offset]);

    var deserializedValue =
        IntegerSerializer.deserializeLiteral(stream, offset + ByteSerializer.BYTE_SIZE);
    assertEquals(42, deserializedValue);
  }

  /**
   * The secondary RID set during construction must be retrievable, and updating it
   * via setSecondaryRid should replace the stored value.
   */
  @Test
  public void testSecondaryRidIsStoredAndUpdatable() {
    var rid1 = new RecordId(1, 1);
    var change = new AbsoluteChange(1, rid1);
    assertEquals(rid1, change.getSecondaryRid());

    var rid2 = new RecordId(2, 2);
    change.setSecondaryRid(rid2);
    assertEquals(rid2, change.getSecondaryRid());
  }
}
