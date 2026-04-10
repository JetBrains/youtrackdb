package com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.impl.index;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.index.CompositeKey;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.BinarySerializerFactory;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALChanges;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALPageChangesPortion;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Date;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Tests for {@link IndexMultiValuKeySerializer#compareInByteBuffer} and {@link
 * IndexMultiValuKeySerializer#compareInByteBufferWithWALChanges}. Verifies that zero-deserialization
 * field-by-field comparison produces the same result as {@link CompositeKey#compareTo}.
 */
public class IndexMultiValuKeySerializerCompareTest {

  private static final IndexMultiValuKeySerializer SERIALIZER = new IndexMultiValuKeySerializer();

  private static BinarySerializerFactory serializerFactory;

  @BeforeClass
  public static void setUp() {
    serializerFactory =
        BinarySerializerFactory.create(BinarySerializerFactory.currentBinaryFormatVersion());
  }

  // --- Helper methods ---

  /**
   * Serializes a CompositeKey into a native byte array using the given type hints.
   */
  private byte[] serialize(CompositeKey key, PropertyTypeInternal... types) {
    var preprocessed = SERIALIZER.preprocess(serializerFactory, key, (Object[]) types);
    var size = SERIALIZER.getObjectSize(serializerFactory, preprocessed, (Object[]) types);
    var stream = new byte[size];
    SERIALIZER.serializeNativeObject(
        preprocessed, serializerFactory, stream, 0, (Object[]) types);
    return stream;
  }

  /**
   * Compares two serialized keys via compareInByteBuffer and verifies the result matches
   * CompositeKey.compareTo. Returns the comparison result.
   */
  private int compareAndVerify(
      CompositeKey key1, CompositeKey key2, PropertyTypeInternal... types) {
    var preprocessed1 = SERIALIZER.preprocess(serializerFactory, key1, (Object[]) types);
    var preprocessed2 = SERIALIZER.preprocess(serializerFactory, key2, (Object[]) types);
    var bytes1 = serialize(key1, types);
    var bytes2 = serialize(key2, types);

    // Put bytes1 into a ByteBuffer (page buffer), bytes2 is the search key
    var buffer = ByteBuffer.wrap(bytes1).order(ByteOrder.nativeOrder());
    var result =
        SERIALIZER.compareInByteBuffer(serializerFactory, 0, buffer, bytes2, 0);

    // Verify sign matches CompositeKey.compareTo
    var expected = preprocessed1.compareTo(preprocessed2);
    assertEquals(
        "Sign mismatch for " + key1 + " vs " + key2,
        Integer.signum(expected),
        Integer.signum(result));

    return result;
  }

  /**
   * Compares two serialized keys via compareInByteBufferWithWALChanges and verifies the
   * result matches CompositeKey.compareTo.
   */
  private int compareAndVerifyWAL(
      CompositeKey key1, CompositeKey key2, PropertyTypeInternal... types) {
    var preprocessed1 = SERIALIZER.preprocess(serializerFactory, key1, (Object[]) types);
    var preprocessed2 = SERIALIZER.preprocess(serializerFactory, key2, (Object[]) types);
    var bytes1 = serialize(key1, types);
    var bytes2 = serialize(key2, types);

    // Set up WAL overlay with bytes1 as page data
    var serializationOffset = 5;
    var buffer =
        ByteBuffer.allocateDirect(bytes1.length + serializationOffset
            + WALPageChangesPortion.PORTION_BYTES)
            .order(ByteOrder.nativeOrder());
    WALChanges walChanges = new WALPageChangesPortion();
    walChanges.setBinaryValue(buffer, bytes1, serializationOffset);

    var result = SERIALIZER.compareInByteBufferWithWALChanges(
        serializerFactory, buffer, walChanges, serializationOffset, bytes2, 0);

    var expected = preprocessed1.compareTo(preprocessed2);
    assertEquals(
        "WAL sign mismatch for " + key1 + " vs " + key2,
        Integer.signum(expected),
        Integer.signum(result));

    return result;
  }

  private static CompositeKey key(Object... keys) {
    var ck = new CompositeKey();
    for (var k : keys) {
      ck.addKey(k);
    }
    return ck;
  }

  // --- Tests for individual field types ---

  /** LONG field: basic ordering. */
  @Test
  public void testLongField() {
    var types = new PropertyTypeInternal[] {PropertyTypeInternal.LONG, PropertyTypeInternal.LONG};
    compareAndVerify(key(100L, 1L), key(200L, 1L), types);
    compareAndVerify(key(200L, 1L), key(100L, 1L), types);
    compareAndVerify(key(100L, 1L), key(100L, 1L), types);
    // Negative values
    compareAndVerify(key(-100L, 1L), key(100L, 1L), types);
    compareAndVerify(key(Long.MIN_VALUE, 1L), key(Long.MAX_VALUE, 1L), types);
  }

  /** INTEGER field: basic ordering. */
  @Test
  public void testIntegerField() {
    var types =
        new PropertyTypeInternal[] {PropertyTypeInternal.INTEGER, PropertyTypeInternal.LONG};
    compareAndVerify(key(42, 1L), key(99, 1L), types);
    compareAndVerify(key(99, 1L), key(42, 1L), types);
    compareAndVerify(key(42, 1L), key(42, 1L), types);
    compareAndVerify(key(-1, 1L), key(1, 1L), types);
  }

  /** SHORT field: basic ordering. */
  @Test
  public void testShortField() {
    var types = new PropertyTypeInternal[] {PropertyTypeInternal.SHORT, PropertyTypeInternal.LONG};
    compareAndVerify(key((short) 10, 1L), key((short) 20, 1L), types);
    compareAndVerify(key((short) 20, 1L), key((short) 10, 1L), types);
    compareAndVerify(key((short) 10, 1L), key((short) 10, 1L), types);
  }

  /** BOOLEAN field: false < true. */
  @Test
  public void testBooleanField() {
    var types =
        new PropertyTypeInternal[] {PropertyTypeInternal.BOOLEAN, PropertyTypeInternal.LONG};
    compareAndVerify(key(false, 1L), key(true, 1L), types);
    compareAndVerify(key(true, 1L), key(false, 1L), types);
    compareAndVerify(key(true, 1L), key(true, 1L), types);
  }

  /** BYTE field: basic ordering. */
  @Test
  public void testByteField() {
    var types = new PropertyTypeInternal[] {PropertyTypeInternal.BYTE, PropertyTypeInternal.LONG};
    compareAndVerify(key((byte) 1, 1L), key((byte) 2, 1L), types);
    compareAndVerify(key((byte) 2, 1L), key((byte) 1, 1L), types);
    compareAndVerify(key((byte) 0, 1L), key((byte) 0, 1L), types);
  }

  /** STRING field: lexicographic ordering. */
  @Test
  public void testStringField() {
    var types = new PropertyTypeInternal[] {PropertyTypeInternal.STRING, PropertyTypeInternal.LONG};
    compareAndVerify(key("abc", 1L), key("xyz", 1L), types);
    compareAndVerify(key("xyz", 1L), key("abc", 1L), types);
    compareAndVerify(key("abc", 1L), key("abc", 1L), types);
    compareAndVerify(key("", 1L), key("a", 1L), types);
  }

  /**
   * FLOAT field: ordering preserves NaN and -0.0 semantics.
   * Float.compare(-0.0f, 0.0f) < 0 and Float.compare(NaN, anything) > 0.
   */
  @Test
  public void testFloatField() {
    var types = new PropertyTypeInternal[] {PropertyTypeInternal.FLOAT, PropertyTypeInternal.LONG};
    compareAndVerify(key(1.0f, 1L), key(2.0f, 1L), types);
    compareAndVerify(key(-1.0f, 1L), key(1.0f, 1L), types);
    compareAndVerify(key(1.0f, 1L), key(1.0f, 1L), types);
    // -0.0 < +0.0 per Float.compare
    compareAndVerify(key(-0.0f, 1L), key(0.0f, 1L), types);
    // NaN > everything per Float.compare
    compareAndVerify(key(Float.NaN, 1L), key(Float.MAX_VALUE, 1L), types);
    // Negative values
    compareAndVerify(key(-100.5f, 1L), key(-50.5f, 1L), types);
  }

  /**
   * DOUBLE field: ordering preserves NaN and -0.0 semantics.
   */
  @Test
  public void testDoubleField() {
    var types = new PropertyTypeInternal[] {PropertyTypeInternal.DOUBLE, PropertyTypeInternal.LONG};
    compareAndVerify(key(1.0, 1L), key(2.0, 1L), types);
    compareAndVerify(key(-1.0, 1L), key(1.0, 1L), types);
    compareAndVerify(key(1.0, 1L), key(1.0, 1L), types);
    // -0.0 < +0.0 per Double.compare
    compareAndVerify(key(-0.0, 1L), key(0.0, 1L), types);
    // NaN > everything per Double.compare
    compareAndVerify(key(Double.NaN, 1L), key(Double.MAX_VALUE, 1L), types);
    // Negative values
    compareAndVerify(key(-100.5, 1L), key(-50.5, 1L), types);
  }

  /** DATE field: ordering by time millis. */
  @Test
  public void testDateField() {
    var types = new PropertyTypeInternal[] {PropertyTypeInternal.DATE, PropertyTypeInternal.LONG};
    var d1 = new Date(1000000000L);
    var d2 = new Date(2000000000L);
    compareAndVerify(key(d1, 1L), key(d2, 1L), types);
    compareAndVerify(key(d2, 1L), key(d1, 1L), types);
    compareAndVerify(key(d1, 1L), key(d1, 1L), types);
  }

  /** DATETIME field: ordering by time millis. */
  @Test
  public void testDateTimeField() {
    var types =
        new PropertyTypeInternal[] {PropertyTypeInternal.DATETIME, PropertyTypeInternal.LONG};
    var d1 = new Date(1000000000L);
    var d2 = new Date(2000000000L);
    compareAndVerify(key(d1, 1L), key(d2, 1L), types);
    compareAndVerify(key(d2, 1L), key(d1, 1L), types);
  }

  /** LINK field: compare clusterId then clusterPosition. */
  @Test
  public void testLinkField() {
    var types = new PropertyTypeInternal[] {PropertyTypeInternal.LINK, PropertyTypeInternal.LONG};
    // Different cluster IDs
    compareAndVerify(key(new RecordId(1, 100), 1L), key(new RecordId(2, 100), 1L), types);
    // Same cluster ID, different positions
    compareAndVerify(key(new RecordId(1, 100), 1L), key(new RecordId(1, 200), 1L), types);
    // Equal
    compareAndVerify(key(new RecordId(1, 100), 1L), key(new RecordId(1, 100), 1L), types);
    // Large cluster position (exercises variable-length encoding)
    compareAndVerify(
        key(new RecordId(1, 1_000_000L), 1L), key(new RecordId(1, 2_000_000L), 1L), types);
  }

  /** BINARY field: unsigned lexicographic comparison. */
  @Test
  public void testBinaryField() {
    var types = new PropertyTypeInternal[] {PropertyTypeInternal.BINARY, PropertyTypeInternal.LONG};
    compareAndVerify(key(new byte[] {1, 2, 3}, 1L), key(new byte[] {1, 2, 4}, 1L), types);
    compareAndVerify(key(new byte[] {1, 2, 3}, 1L), key(new byte[] {1, 2, 3}, 1L), types);
    compareAndVerify(key(new byte[] {1, 2}, 1L), key(new byte[] {1, 2, 3}, 1L), types);
  }

  /** DECIMAL field: BigDecimal ordering. */
  @Test
  public void testDecimalField() {
    var types =
        new PropertyTypeInternal[] {PropertyTypeInternal.DECIMAL, PropertyTypeInternal.LONG};
    compareAndVerify(key(new BigDecimal("1.23"), 1L), key(new BigDecimal("4.56"), 1L), types);
    compareAndVerify(key(new BigDecimal("-1.23"), 1L), key(new BigDecimal("1.23"), 1L), types);
    compareAndVerify(key(new BigDecimal("1.23"), 1L), key(new BigDecimal("1.23"), 1L), types);
  }

  // --- Null field handling ---

  /** Null vs non-null: null compares less than any non-null value. */
  @Test
  public void testNullVsNonNull() {
    var types =
        new PropertyTypeInternal[] {PropertyTypeInternal.STRING, PropertyTypeInternal.LONG};
    var nullKey = new CompositeKey();
    nullKey.addKey(null);
    nullKey.addKey(1L);

    var nonNullKey = key("hello", 1L);

    assertTrue(
        "null should be less than non-null",
        compareAndVerify(nullKey, nonNullKey, types) < 0);
    assertTrue(
        "non-null should be greater than null",
        compareAndVerify(nonNullKey, nullKey, types) > 0);
  }

  /** Both null: should compare as equal for that field. */
  @Test
  public void testBothNull() {
    var types =
        new PropertyTypeInternal[] {PropertyTypeInternal.STRING, PropertyTypeInternal.LONG};
    var nullKey1 = new CompositeKey();
    nullKey1.addKey(null);
    nullKey1.addKey(1L);

    var nullKey2 = new CompositeKey();
    nullKey2.addKey(null);
    nullKey2.addKey(2L);

    // Both first fields are null, so comparison falls through to the LONG version field
    compareAndVerify(nullKey1, nullKey2, types);
  }

  /** Non-null vs null: non-null should compare greater. */
  @Test
  public void testNonNullVsNull() {
    var types =
        new PropertyTypeInternal[] {PropertyTypeInternal.INTEGER, PropertyTypeInternal.LONG};
    var nonNullKey = key(42, 1L);
    var nullKey = new CompositeKey();
    nullKey.addKey(null);
    nullKey.addKey(1L);

    assertTrue(compareAndVerify(nonNullKey, nullKey, types) > 0);
  }

  // --- Prefix comparison (different key counts) ---

  /**
   * When the search key has fewer elements than the page key, prefix matching should
   * return negative (search < page) because fewer keys = smaller.
   */
  @Test
  public void testPrefixComparison() {
    var types2 =
        new PropertyTypeInternal[] {PropertyTypeInternal.STRING, PropertyTypeInternal.LONG};
    var types1 = new PropertyTypeInternal[] {PropertyTypeInternal.STRING};

    var fullKey = key("abc", 1L);
    var prefixKey = key("abc");

    var fullBytes = serialize(fullKey, types2);
    var prefixBytes = serialize(prefixKey, types1);

    var buffer = ByteBuffer.wrap(fullBytes).order(ByteOrder.nativeOrder());
    var result =
        SERIALIZER.compareInByteBuffer(serializerFactory, 0, buffer, prefixBytes, 0);

    // Full key (2 fields) > prefix key (1 field) when the shared fields are equal
    assertTrue("Full key should be greater than prefix key", result > 0);

    // Reverse: prefix in page, full as search
    buffer = ByteBuffer.wrap(prefixBytes).order(ByteOrder.nativeOrder());
    result = SERIALIZER.compareInByteBuffer(serializerFactory, 0, buffer, fullBytes, 0);
    assertTrue("Prefix key should be less than full key", result < 0);
  }

  /** Equal keys should return 0. */
  @Test
  public void testEqualKeys() {
    var types = new PropertyTypeInternal[] {PropertyTypeInternal.STRING, PropertyTypeInternal.LONG};
    assertEquals(0, compareAndVerify(key("hello", 42L), key("hello", 42L), types));
  }

  // --- Multi-field composite keys with mixed types ---

  /** Multi-field key with STRING + INTEGER + LONG. */
  @Test
  public void testMixedTypeComposite() {
    var types = new PropertyTypeInternal[] {
        PropertyTypeInternal.STRING, PropertyTypeInternal.INTEGER, PropertyTypeInternal.LONG};
    // First field equal, second field different
    compareAndVerify(key("abc", 10, 1L), key("abc", 20, 1L), types);
    // First field different
    compareAndVerify(key("abc", 10, 1L), key("def", 10, 1L), types);
    // All equal
    assertEquals(0, compareAndVerify(key("abc", 10, 1L), key("abc", 10, 1L), types));
  }

  // --- WAL-aware comparison tests ---

  /** WAL: LONG field ordering. */
  @Test
  public void testWALLongField() {
    var types = new PropertyTypeInternal[] {PropertyTypeInternal.LONG, PropertyTypeInternal.LONG};
    compareAndVerifyWAL(key(100L, 1L), key(200L, 1L), types);
    compareAndVerifyWAL(key(200L, 1L), key(100L, 1L), types);
    compareAndVerifyWAL(key(100L, 1L), key(100L, 1L), types);
  }

  /** WAL: INTEGER field ordering. */
  @Test
  public void testWALIntegerField() {
    var types =
        new PropertyTypeInternal[] {PropertyTypeInternal.INTEGER, PropertyTypeInternal.LONG};
    compareAndVerifyWAL(key(42, 1L), key(99, 1L), types);
    compareAndVerifyWAL(key(99, 1L), key(42, 1L), types);
    compareAndVerifyWAL(key(42, 1L), key(42, 1L), types);
  }

  /** WAL: SHORT field ordering. */
  @Test
  public void testWALShortField() {
    var types = new PropertyTypeInternal[] {PropertyTypeInternal.SHORT, PropertyTypeInternal.LONG};
    compareAndVerifyWAL(key((short) 10, 1L), key((short) 20, 1L), types);
    compareAndVerifyWAL(key((short) 10, 1L), key((short) 10, 1L), types);
  }

  /** WAL: FLOAT field with NaN and -0.0. */
  @Test
  public void testWALFloatField() {
    var types = new PropertyTypeInternal[] {PropertyTypeInternal.FLOAT, PropertyTypeInternal.LONG};
    compareAndVerifyWAL(key(1.0f, 1L), key(2.0f, 1L), types);
    compareAndVerifyWAL(key(-0.0f, 1L), key(0.0f, 1L), types);
    compareAndVerifyWAL(key(Float.NaN, 1L), key(Float.MAX_VALUE, 1L), types);
  }

  /** WAL: DOUBLE field with NaN and -0.0. */
  @Test
  public void testWALDoubleField() {
    var types = new PropertyTypeInternal[] {PropertyTypeInternal.DOUBLE, PropertyTypeInternal.LONG};
    compareAndVerifyWAL(key(1.0, 1L), key(2.0, 1L), types);
    compareAndVerifyWAL(key(-0.0, 1L), key(0.0, 1L), types);
    compareAndVerifyWAL(key(Double.NaN, 1L), key(Double.MAX_VALUE, 1L), types);
  }

  /** WAL: BOOLEAN field. */
  @Test
  public void testWALBooleanField() {
    var types =
        new PropertyTypeInternal[] {PropertyTypeInternal.BOOLEAN, PropertyTypeInternal.LONG};
    compareAndVerifyWAL(key(false, 1L), key(true, 1L), types);
    compareAndVerifyWAL(key(true, 1L), key(true, 1L), types);
  }

  /** WAL: BYTE field. */
  @Test
  public void testWALByteField() {
    var types = new PropertyTypeInternal[] {PropertyTypeInternal.BYTE, PropertyTypeInternal.LONG};
    compareAndVerifyWAL(key((byte) 1, 1L), key((byte) 2, 1L), types);
    compareAndVerifyWAL(key((byte) 1, 1L), key((byte) 1, 1L), types);
  }

  /** WAL: STRING field (deserialization fallback in WAL path). */
  @Test
  public void testWALStringField() {
    var types = new PropertyTypeInternal[] {PropertyTypeInternal.STRING, PropertyTypeInternal.LONG};
    compareAndVerifyWAL(key("abc", 1L), key("xyz", 1L), types);
    compareAndVerifyWAL(key("abc", 1L), key("abc", 1L), types);
  }

  /** WAL: LINK field (deserialization fallback in WAL path). */
  @Test
  public void testWALLinkField() {
    var types = new PropertyTypeInternal[] {PropertyTypeInternal.LINK, PropertyTypeInternal.LONG};
    compareAndVerifyWAL(
        key(new RecordId(1, 100), 1L), key(new RecordId(2, 100), 1L), types);
    compareAndVerifyWAL(
        key(new RecordId(1, 100), 1L), key(new RecordId(1, 100), 1L), types);
  }

  /** WAL: BINARY field (deserialization fallback in WAL path). */
  @Test
  public void testWALBinaryField() {
    var types = new PropertyTypeInternal[] {PropertyTypeInternal.BINARY, PropertyTypeInternal.LONG};
    compareAndVerifyWAL(key(new byte[] {1, 2, 3}, 1L), key(new byte[] {1, 2, 4}, 1L), types);
    compareAndVerifyWAL(key(new byte[] {1, 2, 3}, 1L), key(new byte[] {1, 2, 3}, 1L), types);
  }

  /** WAL: DECIMAL field (deserialization fallback in WAL path). */
  @Test
  public void testWALDecimalField() {
    var types =
        new PropertyTypeInternal[] {PropertyTypeInternal.DECIMAL, PropertyTypeInternal.LONG};
    compareAndVerifyWAL(key(new BigDecimal("1.23"), 1L), key(new BigDecimal("4.56"), 1L), types);
    compareAndVerifyWAL(key(new BigDecimal("1.23"), 1L), key(new BigDecimal("1.23"), 1L), types);
  }

  /** WAL: null handling. */
  @Test
  public void testWALNullHandling() {
    var types =
        new PropertyTypeInternal[] {PropertyTypeInternal.STRING, PropertyTypeInternal.LONG};
    var nullKey = new CompositeKey();
    nullKey.addKey(null);
    nullKey.addKey(1L);

    var nonNullKey = key("hello", 1L);

    assertTrue(compareAndVerifyWAL(nullKey, nonNullKey, types) < 0);
    assertTrue(compareAndVerifyWAL(nonNullKey, nullKey, types) > 0);
  }

  /** WAL: mixed type composite. */
  @Test
  public void testWALMixedTypeComposite() {
    var types = new PropertyTypeInternal[] {
        PropertyTypeInternal.STRING, PropertyTypeInternal.INTEGER, PropertyTypeInternal.LONG};
    compareAndVerifyWAL(key("abc", 10, 1L), key("abc", 20, 1L), types);
    assertEquals(0, compareAndVerifyWAL(key("abc", 10, 1L), key("abc", 10, 1L), types));
  }

  /** WAL: prefix comparison with different key counts. */
  @Test
  public void testWALPrefixComparison() {
    var types2 =
        new PropertyTypeInternal[] {PropertyTypeInternal.STRING, PropertyTypeInternal.LONG};
    var types1 = new PropertyTypeInternal[] {PropertyTypeInternal.STRING};

    var fullKey = key("abc", 1L);
    var prefixKey = key("abc");

    var fullBytes = serialize(fullKey, types2);
    var prefixBytes = serialize(prefixKey, types1);

    // Set up WAL overlay with fullKey as page data
    var serializationOffset = 5;
    var buffer =
        ByteBuffer.allocateDirect(fullBytes.length + serializationOffset
            + WALPageChangesPortion.PORTION_BYTES)
            .order(ByteOrder.nativeOrder());
    WALChanges walChanges = new WALPageChangesPortion();
    walChanges.setBinaryValue(buffer, fullBytes, serializationOffset);

    var result = SERIALIZER.compareInByteBufferWithWALChanges(
        serializerFactory, buffer, walChanges, serializationOffset, prefixBytes, 0);
    assertTrue("Full key should be greater than prefix key in WAL path", result > 0);
  }

  /** WAL: DATE field ordering. */
  @Test
  public void testWALDateField() {
    var types = new PropertyTypeInternal[] {PropertyTypeInternal.DATE, PropertyTypeInternal.LONG};
    var d1 = new Date(1000000000L);
    var d2 = new Date(2000000000L);
    compareAndVerifyWAL(key(d1, 1L), key(d2, 1L), types);
    compareAndVerifyWAL(key(d1, 1L), key(d1, 1L), types);
  }
}
