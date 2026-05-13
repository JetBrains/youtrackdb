package com.jetbrains.youtrackdb.internal.core.index;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.id.IdentityChangeListener;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.impl.index.CompositeKeySerializer;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALChanges;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALPageChangesPortion;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class CompositeKeyTest extends DbTestBase {
  @Test
  public void testEqualSameKeys() {
    final var compositeKey = new CompositeKey();

    compositeKey.addKey("a");
    compositeKey.addKey("b");

    final var anotherCompositeKey = new CompositeKey();
    anotherCompositeKey.addKey("a");
    anotherCompositeKey.addKey("b");

    assertEquals(compositeKey, anotherCompositeKey);
    assertEquals(compositeKey.hashCode(), anotherCompositeKey.hashCode());
  }

  @Test
  public void testEqualNotSameKeys() {
    final var compositeKey = new CompositeKey();

    compositeKey.addKey("a");
    compositeKey.addKey("b");

    final var anotherCompositeKey = new CompositeKey();
    anotherCompositeKey.addKey("a");
    anotherCompositeKey.addKey("b");
    anotherCompositeKey.addKey("c");

    assertNotEquals(compositeKey, anotherCompositeKey);
  }

  @Test
  public void testEqualNull() {
    final var compositeKey = new CompositeKey();
    assertNotEquals(null, compositeKey);
  }

  @Test
  public void testEqualEquivalent() {
    final var compositeKey1 = new CompositeKey();
    final var compositeKey2 = new CompositeKey();
    assertEquals(compositeKey1, compositeKey2);
    assertEquals(compositeKey1.hashCode(), compositeKey2.hashCode());
  }

  @Test
  public void testEqualDiffClass() {
    final var compositeKey = new CompositeKey();
    assertNotEquals("1", compositeKey);
  }

  @Test
  public void testAddKeyComparable() {
    final var compositeKey = new CompositeKey();

    compositeKey.addKey("a");

    assertEquals(1, compositeKey.getKeys().size());
    assertTrue(compositeKey.getKeys().contains("a"));
  }

  @Test
  public void testAddKeyComposite() {
    final var compositeKey = new CompositeKey();

    compositeKey.addKey("a");

    final var compositeKeyToAdd = new CompositeKey();
    compositeKeyToAdd.addKey("a");
    compositeKeyToAdd.addKey("b");

    compositeKey.addKey(compositeKeyToAdd);

    assertEquals(3, compositeKey.getKeys().size());
    assertTrue(compositeKey.getKeys().contains("a"));
    assertTrue(compositeKey.getKeys().contains("b"));
  }

  @Test
  public void testCompareToSame() {
    final var compositeKey = new CompositeKey();
    compositeKey.addKey("a");
    compositeKey.addKey("b");

    final var anotherCompositeKey = new CompositeKey();
    anotherCompositeKey.addKey("a");
    anotherCompositeKey.addKey("b");

    assertEquals(0, compositeKey.compareTo(anotherCompositeKey));
  }

  @Test
  public void testCompareToPartiallyOneCase() {
    final var compositeKey = new CompositeKey();
    compositeKey.addKey("a");
    compositeKey.addKey("b");

    final var anotherCompositeKey = new CompositeKey();
    anotherCompositeKey.addKey("a");
    anotherCompositeKey.addKey("b");
    anotherCompositeKey.addKey("c");

    assertEquals(0, compositeKey.compareTo(anotherCompositeKey));
  }

  @Test
  public void testCompareToPartiallySecondCase() {
    final var compositeKey = new CompositeKey();
    compositeKey.addKey("a");
    compositeKey.addKey("b");
    compositeKey.addKey("c");

    final var anotherCompositeKey = new CompositeKey();
    anotherCompositeKey.addKey("a");
    anotherCompositeKey.addKey("b");

    assertEquals(0, compositeKey.compareTo(anotherCompositeKey));
  }

  @Test
  public void testCompareToGT() {
    final var compositeKey = new CompositeKey();
    compositeKey.addKey("b");

    final var anotherCompositeKey = new CompositeKey();
    anotherCompositeKey.addKey("a");
    anotherCompositeKey.addKey("b");

    assertEquals(1, compositeKey.compareTo(anotherCompositeKey));
  }

  @Test
  public void testCompareToLT() {
    final var compositeKey = new CompositeKey();
    compositeKey.addKey("a");
    compositeKey.addKey("b");

    final var anotherCompositeKey = new CompositeKey();

    anotherCompositeKey.addKey("b");

    assertEquals(-1, compositeKey.compareTo(anotherCompositeKey));
  }

  @Test
  public void testCompareStringsToLT() {
    final var compositeKey = new CompositeKey();
    compositeKey.addKey("name4");
    final var anotherCompositeKey = new CompositeKey();
    anotherCompositeKey.addKey("name5");
    assertEquals(-1, compositeKey.compareTo(anotherCompositeKey));
  }

  @Test
  public void testCompareToSymmetryOne() {
    final var compositeKeyOne = new CompositeKey();
    compositeKeyOne.addKey(1);
    compositeKeyOne.addKey(2);

    final var compositeKeyTwo = new CompositeKey();
    compositeKeyTwo.addKey(1);
    compositeKeyTwo.addKey(3);
    compositeKeyTwo.addKey(1);

    assertEquals(-1, compositeKeyOne.compareTo(compositeKeyTwo));
    assertEquals(1, compositeKeyTwo.compareTo(compositeKeyOne));
  }

  @Test
  public void testCompareToSymmetryTwo() {
    final var compositeKeyOne = new CompositeKey();
    compositeKeyOne.addKey(1);
    compositeKeyOne.addKey(2);

    final var compositeKeyTwo = new CompositeKey();
    compositeKeyTwo.addKey(1);
    compositeKeyTwo.addKey(2);
    compositeKeyTwo.addKey(3);

    assertEquals(0, compositeKeyOne.compareTo(compositeKeyTwo));
    assertEquals(0, compositeKeyTwo.compareTo(compositeKeyOne));
  }

  @Test
  public void testCompareNullAtTheEnd() {
    final var compositeKeyOne = new CompositeKey();
    compositeKeyOne.addKey(2);
    compositeKeyOne.addKey(2);

    final var compositeKeyTwo = new CompositeKey();
    compositeKeyTwo.addKey(2);
    compositeKeyTwo.addKey(null);

    final var compositeKeyThree = new CompositeKey();
    compositeKeyThree.addKey(2);
    compositeKeyThree.addKey(null);

    assertEquals(1, compositeKeyOne.compareTo(compositeKeyTwo));
    assertEquals(-1, compositeKeyTwo.compareTo(compositeKeyOne));
    assertEquals(0, compositeKeyTwo.compareTo(compositeKeyThree));
  }

  @Test
  public void testCompareNullAtTheMiddle() {
    final var compositeKeyOne = new CompositeKey();
    compositeKeyOne.addKey(2);
    compositeKeyOne.addKey(2);
    compositeKeyOne.addKey(3);

    final var compositeKeyTwo = new CompositeKey();
    compositeKeyTwo.addKey(2);
    compositeKeyTwo.addKey(null);
    compositeKeyTwo.addKey(3);

    final var compositeKeyThree = new CompositeKey();
    compositeKeyThree.addKey(2);
    compositeKeyThree.addKey(null);
    compositeKeyThree.addKey(3);

    assertEquals(1, compositeKeyOne.compareTo(compositeKeyTwo));
    assertEquals(-1, compositeKeyTwo.compareTo(compositeKeyOne));
    assertEquals(0, compositeKeyTwo.compareTo(compositeKeyThree));
  }

  @Test
  public void testDocumentSerializationCompositeKeyNull() {
    final var compositeKeyOne = new CompositeKey();
    compositeKeyOne.addKey(1);
    compositeKeyOne.addKey(null);
    compositeKeyOne.addKey(2);

    var document = compositeKeyOne.toEntity(session);

    final var compositeKeyTwo = new CompositeKey();
    compositeKeyTwo.fromDocument(document);

    assertEquals(compositeKeyOne, compositeKeyTwo);
    assertNotSame(compositeKeyOne, compositeKeyTwo);
  }

  @Test
  public void testNativeBinarySerializationCompositeKeyNull() {
    final var compositeKeyOne = new CompositeKey();
    compositeKeyOne.addKey(1);
    compositeKeyOne.addKey(null);
    compositeKeyOne.addKey(2);

    var serializerFactory = session.getSerializerFactory();
    var len = CompositeKeySerializer.INSTANCE.getObjectSize(serializerFactory, compositeKeyOne);
    var data = new byte[len];
    CompositeKeySerializer.INSTANCE.serializeNativeObject(compositeKeyOne, serializerFactory, data,
        0);

    final var compositeKeyTwo =
        CompositeKeySerializer.INSTANCE.deserializeNativeObject(serializerFactory, data, 0);

    assertEquals(compositeKeyOne, compositeKeyTwo);
    assertNotSame(compositeKeyOne, compositeKeyTwo);
  }

  @Test
  public void testByteBufferBinarySerializationCompositeKeyNull() {
    final var serializationOffset = 5;

    final var compositeKeyOne = new CompositeKey();
    compositeKeyOne.addKey(1);
    compositeKeyOne.addKey(null);
    compositeKeyOne.addKey(2);

    var serializerFactory = session.getSerializerFactory();
    final var len = CompositeKeySerializer.INSTANCE.getObjectSize(serializerFactory,
        compositeKeyOne);

    final var buffer = ByteBuffer.allocate(len + serializationOffset);
    buffer.position(serializationOffset);

    CompositeKeySerializer.INSTANCE.serializeInByteBufferObject(serializerFactory, compositeKeyOne,
        buffer);

    final var binarySize = buffer.position() - serializationOffset;
    assertEquals(binarySize, len);

    buffer.position(serializationOffset);
    assertEquals(
        CompositeKeySerializer.INSTANCE.getObjectSizeInByteBuffer(serializerFactory, buffer), len);

    buffer.position(serializationOffset);
    final var compositeKeyTwo =
        CompositeKeySerializer.INSTANCE.deserializeFromByteBufferObject(serializerFactory, buffer);

    assertEquals(compositeKeyOne, compositeKeyTwo);
    assertNotSame(compositeKeyOne, compositeKeyTwo);

    assertEquals(buffer.position() - serializationOffset, len);
  }

  // ---- asCompositeKey -------------------------------------------------------

  /**
   * Verifies that asCompositeKey returns the same instance when given a CompositeKey,
   * avoiding an unnecessary copy on the read-only path.
   */
  @Test
  public void testAsCompositeKeyReturnsSameInstance() {
    var key = new CompositeKey("a", "b");
    assertSame(key, CompositeKey.asCompositeKey(key));
  }

  /**
   * Verifies that asCompositeKey wraps a non-CompositeKey value in a new single-element
   * CompositeKey containing that value.
   */
  @Test
  public void testAsCompositeKeyWrapsNonCompositeKey() {
    var result = CompositeKey.asCompositeKey("hello");
    assertEquals(1, result.getKeys().size());
    assertEquals("hello", result.getKeys().get(0));
  }

  // ---- reset ---------------------------------------------------------------

  /**
   * Verifies that reset() clears all keys so the composite key can be reused,
   * and that getKeys() afterwards returns an empty list.
   */
  @Test
  public void testResetClearsAllKeys() {
    var key = new CompositeKey();
    key.addKey("x");
    key.addKey("y");
    assertEquals(2, key.getKeys().size());

    key.reset();

    assertEquals(0, key.getKeys().size());
  }

  // ---- addKeyDirect ---------------------------------------------------------

  /**
   * Verifies that addKeyDirect bypasses ChangeableIdentity tracking and simply appends
   * the element to the key list — canChangeIdentity stays false.
   */
  @Test
  public void testAddKeyDirectDoesNotTrackIdentity() {
    var key = new CompositeKey();
    key.addKeyDirect("immutableValue");

    assertEquals(1, key.getKeys().size());
    assertEquals("immutableValue", key.getKeys().get(0));
    assertFalse("addKeyDirect must not trigger identity tracking", key.canChangeIdentity());
  }

  // ---- constructors ---------------------------------------------------------

  /**
   * Verifies the List-based constructor copies all elements in order.
   */
  @Test
  public void testListConstructorCopiesElements() {
    List<Object> elements = Arrays.asList("p", "q", "r");
    var key = new CompositeKey(elements);
    assertEquals(elements, key.getKeys());
  }

  /**
   * Verifies the varargs constructor copies all elements in order.
   */
  @Test
  public void testVarargsConstructorCopiesElements() {
    var key = new CompositeKey(1, 2, 3);
    assertEquals(Arrays.asList(1, 2, 3), key.getKeys());
  }

  /**
   * Verifies the capacity constructor produces an empty key ready to accept addKey calls.
   */
  @Test
  public void testCapacityConstructorProducesEmptyKey() {
    var key = new CompositeKey(8);
    assertEquals(0, key.getKeys().size());
    key.addKey("x");
    assertEquals(1, key.getKeys().size());
  }

  // ---- canChangeIdentity / identity-change listeners ------------------------

  /**
   * Verifies that a CompositeKey with only plain (non-ChangeableIdentity) values
   * reports canChangeIdentity() == false.
   */
  @Test
  public void testCanChangeIdentityFalseForPlainValues() {
    var key = new CompositeKey("a", 42);
    assertFalse(key.canChangeIdentity());
  }

  /**
   * Verifies that addIdentityChangeListener and removeIdentityChangeListener are
   * no-ops when canChangeIdentity() is false — the listener is not registered and
   * the remove does not throw.
   */
  @Test
  public void testAddRemoveIdentityChangeListenerNoOpWhenNotChangeable() {
    var key = new CompositeKey("stable");
    AtomicInteger callCount = new AtomicInteger(0);
    IdentityChangeListener listener = new IdentityChangeListener() {
      @Override
      public void onBeforeIdentityChange(Object source) {
        callCount.incrementAndGet();
      }

      @Override
      public void onAfterIdentityChange(Object source) {
        callCount.incrementAndGet();
      }
    };

    // Should be a no-op — key is not changeable
    key.addIdentityChangeListener(listener);
    key.removeIdentityChangeListener(listener);

    assertEquals("No listener calls expected for a non-changeable key", 0, callCount.get());
  }

  // ---- toString -------------------------------------------------------------

  /**
   * Verifies that toString returns a non-null string that mentions the keys.
   */
  @Test
  public void testToStringContainsKeys() {
    var key = new CompositeKey("alpha", "beta");
    var str = key.toString();
    assertTrue(str.contains("alpha"));
    assertTrue(str.contains("beta"));
  }

  @Test
  public void testWALChangesBinarySerializationCompositeKeyNull() {
    final var serializationOffset = 5;

    final var compositeKey = new CompositeKey();
    compositeKey.addKey(1);
    compositeKey.addKey(null);
    compositeKey.addKey(2);

    var serializerFactory = session.getSerializerFactory();
    final var len = CompositeKeySerializer.INSTANCE.getObjectSize(serializerFactory, compositeKey);
    final var buffer =
        ByteBuffer.allocateDirect(len + serializationOffset + WALPageChangesPortion.PORTION_BYTES)
            .order(ByteOrder.nativeOrder());
    final var data = new byte[len];

    CompositeKeySerializer.INSTANCE.serializeNativeObject(compositeKey, serializerFactory, data, 0);
    final WALChanges walChanges = new WALPageChangesPortion();
    walChanges.setBinaryValue(buffer, data, serializationOffset);

    assertEquals(
        CompositeKeySerializer.INSTANCE.getObjectSizeInByteBuffer(
            buffer, walChanges, serializationOffset),
        len);
    assertEquals(
        CompositeKeySerializer.INSTANCE.deserializeFromByteBufferObject(serializerFactory,
            buffer, walChanges, serializationOffset),
        compositeKey);
  }
}
