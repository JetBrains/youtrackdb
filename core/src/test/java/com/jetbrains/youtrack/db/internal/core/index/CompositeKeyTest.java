package com.jetbrains.youtrack.db.internal.core.index;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.binary.impl.index.CompositeKeySerializer;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.RecordSerializerNetworkV37;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.wal.WALChanges;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.wal.WALPageChangesPortion;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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
  public void testEqualSame() {
    final var compositeKey = new CompositeKey();
    //noinspection EqualsWithItself
    assertEquals(compositeKey, compositeKey);
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

  @Test
  public void testNetworkSerialization() throws IOException {
    var k1 = "key";
    var k2 = new CompositeKey(null, new CompositeKey("user1", 12.5));
    var compositeKey = new CompositeKey(k1, k2);
    var serializer = RecordSerializerNetworkV37.INSTANCE;
    var outStream = new ByteArrayOutputStream();
    var out = new DataOutputStream(outStream);
    compositeKey.toStream(session, serializer, out);
    var inStream = new ByteArrayInputStream(outStream.toByteArray());
    var in = new DataInputStream(inStream);
    var deserializedCompositeKey = new CompositeKey();
    deserializedCompositeKey.fromStream(session, serializer, in);
    assertEquals(compositeKey, deserializedCompositeKey);
  }
}
