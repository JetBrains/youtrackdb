package com.jetbrains.youtrack.db.internal.common.serialization.types;

import com.jetbrains.youtrack.db.internal.core.serialization.serializer.binary.BinarySerializerFactory;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.wal.WALChanges;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.wal.WALPageChangesPortion;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

public class UTFSerializerTest {

  byte[] stream;
  private static String OBJECT;
  private static UTF8Serializer stringSerializer;
  private static BinarySerializerFactory serializerFactory;

  @BeforeClass
  public static void beforeClass() {
    OBJECT =
        "asd d astasf sdfrete 5678b sdf adfas ase sdf aas  t sdf ts d s e34523 sdf gsd 63 sdfs ыа ы"
            + " кы афц3м  ыпаыву  s sf s sdf asd asfsd w assf tet ы ц к к йцкуаыфв ыфафаф фаываыфав"
            + " а фв аs  asf s sdfsa dscas  s as asdf sfsr43r344 1tasdf asa  asdfa fgwe treqr3"
            + " qadfasf аывфыфцк у фыва ые унпваыва  вайк ыавыфвауц";
    stringSerializer = new UTF8Serializer();
    serializerFactory = BinarySerializerFactory.create(
        BinarySerializerFactory.currentBinaryFormatVersion());
  }

  @Test
  public void testSerialize() {
    stream = new byte[stringSerializer.getObjectSize(serializerFactory, OBJECT) + 7];
    stringSerializer.serialize(OBJECT, serializerFactory, stream, 7);
    Assert.assertEquals(stringSerializer.deserialize(serializerFactory, stream, 7), OBJECT);
  }

  @Test
  public void testSerializeNative() {
    stream = new byte[stringSerializer.getObjectSize(serializerFactory, OBJECT) + 7];
    stringSerializer.serializeNativeObject(OBJECT, serializerFactory, stream, 7);
    Assert.assertEquals(stringSerializer.deserializeNativeObject(serializerFactory, stream, 7),
        OBJECT);
  }

  @Test
  public void testSerializeNativeAsWhole() {
    stream = stringSerializer.serializeNativeAsWhole(serializerFactory, OBJECT);
    Assert.assertEquals(stringSerializer.deserializeNativeObject(serializerFactory, stream, 0),
        OBJECT);
  }

  @Test
  public void testNativeDirectMemoryCompatibility() {
    stream = new byte[stringSerializer.getObjectSize(serializerFactory, OBJECT) + 7];
    stringSerializer.serializeNativeObject(OBJECT, serializerFactory, stream, 7);

    var buffer = ByteBuffer.allocateDirect(stream.length).order(ByteOrder.nativeOrder());
    buffer.put(stream);
    buffer.position(7);

    Assert.assertEquals(stringSerializer.deserializeFromByteBufferObject(serializerFactory, buffer),
        OBJECT);
  }

  @Test
  public void testNativeDirectMemoryCompatibilityAsWhole() {
    stream = stringSerializer.serializeNativeAsWhole(serializerFactory, OBJECT);

    var buffer = ByteBuffer.allocateDirect(stream.length).order(ByteOrder.nativeOrder());
    buffer.put(stream);
    buffer.position(0);

    Assert.assertEquals(stringSerializer.deserializeFromByteBufferObject(serializerFactory, buffer),
        OBJECT);
  }

  @Test
  public void testSerializeInByteBuffer() {
    final var serializationOffset = 5;
    final var buffer =
        ByteBuffer.allocate(
            stringSerializer.getObjectSize(serializerFactory, OBJECT) + serializationOffset);

    buffer.position(serializationOffset);
    stringSerializer.serializeInByteBufferObject(serializerFactory, OBJECT, buffer);

    final var binarySize = buffer.position() - serializationOffset;
    Assert.assertEquals(binarySize, stringSerializer.getObjectSize(serializerFactory, OBJECT));

    buffer.position(serializationOffset);
    Assert.assertEquals(
        stringSerializer.getObjectSizeInByteBuffer(serializerFactory, buffer),
        stringSerializer.getObjectSize(serializerFactory, OBJECT));

    buffer.position(serializationOffset);
    Assert.assertEquals(stringSerializer.deserializeFromByteBufferObject(serializerFactory, buffer),
        OBJECT);

    Assert.assertEquals(
        buffer.position() - serializationOffset,
        stringSerializer.getObjectSize(serializerFactory, OBJECT));
  }

  @Test
  public void testSerializeInImmutableByteBufferPosition() {
    final var serializationOffset = 5;
    final var buffer =
        ByteBuffer.allocate(
            stringSerializer.getObjectSize(serializerFactory, OBJECT) + serializationOffset);

    buffer.position(serializationOffset);
    stringSerializer.serializeInByteBufferObject(serializerFactory, OBJECT, buffer);

    final var binarySize = buffer.position() - serializationOffset;
    Assert.assertEquals(binarySize, stringSerializer.getObjectSize(serializerFactory, OBJECT));

    buffer.position(0);
    Assert.assertEquals(
        stringSerializer.getObjectSizeInByteBuffer(serializerFactory, serializationOffset, buffer),
        stringSerializer.getObjectSize(serializerFactory, OBJECT));
    Assert.assertEquals(0, buffer.position());

    Assert.assertEquals(
        stringSerializer.deserializeFromByteBufferObject(serializerFactory, serializationOffset,
            buffer), OBJECT);
    Assert.assertEquals(0, buffer.position());
  }

  @Test
  public void testSerializeWALChanges() {
    final var serializationOffset = 5;
    final var buffer =
        ByteBuffer.allocateDirect(
                stringSerializer.getObjectSize(serializerFactory, OBJECT)
                    + serializationOffset
                    + WALPageChangesPortion.PORTION_BYTES)
            .order(ByteOrder.nativeOrder());

    final var data = new byte[stringSerializer.getObjectSize(serializerFactory, OBJECT)];
    stringSerializer.serializeNativeObject(OBJECT, serializerFactory, data, 0);

    WALChanges walChanges = new WALPageChangesPortion();
    walChanges.setBinaryValue(buffer, data, serializationOffset);

    Assert.assertEquals(
        stringSerializer.getObjectSizeInByteBuffer(buffer, walChanges, serializationOffset),
        stringSerializer.getObjectSize(serializerFactory, OBJECT));
    Assert.assertEquals(
        stringSerializer.deserializeFromByteBufferObject(serializerFactory, buffer, walChanges,
            serializationOffset),
        OBJECT);

    Assert.assertEquals(0, buffer.position());
  }
}
