package com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.wal;

import com.jetbrains.youtrack.db.internal.core.YouTrackDBEnginesManager;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Random;
import org.assertj.core.api.Assertions;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * @since 8/19/2015
 */
public class WALPageV2ChangesPortionTest {

  @Before
  public void before() {
    YouTrackDBEnginesManager.instance();
  }

  @Test
  public void testSingleLongValueInStartChunk() {
    var data = new byte[1024];
    var pointer = ByteBuffer.wrap(data).order(ByteOrder.nativeOrder());

    pointer.putLong(64, 31);

    var changesCollector = new WALPageChangesPortion(1024);
    changesCollector.setLongValue(pointer, 42, 64);
    Assert.assertEquals(changesCollector.getLongValue(pointer, 64), 42);

    changesCollector.applyChanges(pointer);
    Assert.assertEquals(42, pointer.getLong(64));
  }

  @Test
  public void testSingleLongValuesInMiddleOfChunk() {
    var data = new byte[1024];
    var pointer = ByteBuffer.wrap(data).order(ByteOrder.nativeOrder());
    pointer.putLong(60, 31);

    var changesCollector = new WALPageChangesPortion(1024);
    changesCollector.setLongValue(pointer, 42, 60);
    Assert.assertEquals(changesCollector.getLongValue(pointer, 60), 42);

    changesCollector.applyChanges(pointer);
    Assert.assertEquals(42, pointer.getLong(60));
  }

  @Test
  public void testSingleIntValue() {
    var data = new byte[1024];
    var pointer = ByteBuffer.wrap(data).order(ByteOrder.nativeOrder());
    pointer.putLong(64, 31);

    var changesCollector = new WALPageChangesPortion(1024);
    changesCollector.setIntValue(pointer, 42, 64);
    Assert.assertEquals(changesCollector.getIntValue(pointer, 64), 42);

    changesCollector.applyChanges(pointer);
    Assert.assertEquals(42, pointer.getInt(64));
  }

  @Test
  public void testSingleShortValue() {
    var data = new byte[1024];
    var pointer = ByteBuffer.wrap(data).order(ByteOrder.nativeOrder());

    pointer.putShort(64, (short) 31);

    var changesCollector = new WALPageChangesPortion(1024);
    changesCollector.setShortValue(pointer, (short) 42, 64);
    Assert.assertEquals(changesCollector.getShortValue(pointer, 64), 42);

    changesCollector.applyChanges(pointer);
    Assert.assertEquals((short) 42, pointer.getShort(64));
  }

  @Test
  public void testSingleByteValue() {
    var data = new byte[1024];
    var pointer = ByteBuffer.wrap(data).order(ByteOrder.nativeOrder());
    pointer.put(64, (byte) 31);

    var changesCollector = new WALPageChangesPortion(1024);
    changesCollector.setByteValue(pointer, (byte) 42, 64);
    Assert.assertEquals(changesCollector.getByteValue(pointer, 64), 42);

    changesCollector.applyChanges(pointer);
    Assert.assertEquals((byte) 42, pointer.get(64));
  }

  @Test
  public void testMoveData() {
    var data = new byte[1024];
    var pointer = ByteBuffer.wrap(data).order(ByteOrder.nativeOrder());

    pointer.position(64);
    pointer.put(new byte[]{11, 12, 13, 14});

    pointer.position(74);
    pointer.put(new byte[]{21, 22, 23, 24});

    var changesCollector = new WALPageChangesPortion(1024);
    var values = new byte[]{1, 2, 3, 4};

    changesCollector.setBinaryValue(pointer, values, 64);
    changesCollector.moveData(pointer, 64, 74, 4);

    Assertions.assertThat(changesCollector.getBinaryValue(pointer, 64, 4)).isEqualTo(values);
    Assertions.assertThat(changesCollector.getBinaryValue(pointer, 74, 4)).isEqualTo(values);

    changesCollector.applyChanges(pointer);

    var result = new byte[4];
    pointer.position(64);
    pointer.get(result);

    Assertions.assertThat(result).isEqualTo(values);

    pointer.position(74);
    pointer.get(result);
    Assertions.assertThat(result).isEqualTo(values);
  }

  @Test
  public void testBinaryValueTwoChunksFromStart() {
    final var originalData = new byte[1024];

    var random = new Random();
    random.nextBytes(originalData);

    var data = Arrays.copyOf(originalData, originalData.length);

    var pointer = ByteBuffer.wrap(data).order(ByteOrder.nativeOrder());

    var changesCollector = new WALPageChangesPortion(1024);
    var changes = new byte[128];

    random.nextBytes(changes);

    changesCollector.setBinaryValue(pointer, changes, 64);

    Assertions.assertThat(changesCollector.getBinaryValue(pointer, 64, 128)).isEqualTo(changes);

    changesCollector.applyChanges(pointer);
    var result = new byte[128];
    pointer.position(64);
    pointer.get(result);

    Assertions.assertThat(result).isEqualTo(changes);
  }

  @Test
  public void testBinaryValueTwoChunksInMiddle() {
    final var originalData = new byte[1024];

    var random = new Random();
    random.nextBytes(originalData);

    var data = Arrays.copyOf(originalData, originalData.length);

    var pointer = ByteBuffer.wrap(data).order(ByteOrder.nativeOrder());

    var changesCollector = new WALPageChangesPortion(1024);
    var changes = new byte[128];

    random.nextBytes(changes);

    changesCollector.setBinaryValue(pointer, changes, 32);
    Assertions.assertThat(changesCollector.getBinaryValue(pointer, 32, 128)).isEqualTo(changes);

    changesCollector.applyChanges(pointer);

    var result = new byte[128];
    pointer.position(32);
    pointer.get(result);

    Assertions.assertThat(result).isEqualTo(changes);
  }

  @Test
  public void testBinaryValueTwoChunksTwoPortionsInMiddle() {
    var random = new Random();

    var originalData = new byte[65536];
    random.nextBytes(originalData);

    var data = Arrays.copyOf(originalData, originalData.length);
    var pointer = ByteBuffer.wrap(data).order(ByteOrder.nativeOrder());

    var changesCollector = new WALPageChangesPortion(65536);
    var changes = new byte[1024];

    random.nextBytes(changes);

    changesCollector.setBinaryValue(pointer, changes, 1000);

    Assertions.assertThat(changesCollector.getBinaryValue(pointer, 1000, 1024)).isEqualTo(changes);

    changesCollector.applyChanges(pointer);

    var result = new byte[1024];

    pointer.position(1000);
    pointer.get(result);

    Assertions.assertThat(result).isEqualTo(changes);
  }

  @Test
  public void testSimpleApplyChanges() {
    var random = new Random();

    var originalData = new byte[1024];
    random.nextBytes(originalData);

    var data = Arrays.copyOf(originalData, originalData.length);
    var pointer = ByteBuffer.wrap(data).order(ByteOrder.nativeOrder());

    var changesCollector = new WALPageChangesPortion(1024);
    var changes = new byte[128];

    random.nextBytes(changes);

    changesCollector.setBinaryValue(pointer, changes, 32);

    Assertions.assertThat(changesCollector.getBinaryValue(pointer, 32, 128)).isEqualTo(changes);

    changesCollector.applyChanges(pointer);
    var res = new byte[128];
    pointer.position(32);
    pointer.get(res);

    Assertions.assertThat(res).isEqualTo(changes);
  }

  @Test
  public void testSerializationAndRestore() {
    var random = new Random();
    var originalData = new byte[1024];
    random.nextBytes(originalData);

    var data = Arrays.copyOf(originalData, originalData.length);

    var pointer = ByteBuffer.wrap(data).order(ByteOrder.nativeOrder());

    var changesCollector = new WALPageChangesPortion(1024);

    var changes = new byte[128];
    random.nextBytes(changes);

    changesCollector.setBinaryValue(pointer, changes, 32);

    Assertions.assertThat(changesCollector.getBinaryValue(pointer, 32, 128)).isEqualTo(changes);
    changesCollector.applyChanges(pointer);

    var newBuffer =
        ByteBuffer.wrap(Arrays.copyOf(originalData, originalData.length))
            .order(ByteOrder.nativeOrder());

    var size = changesCollector.serializedSize();
    var content = new byte[size];
    changesCollector.toStream(0, content);

    var changesCollectorRestored = new WALPageChangesPortion(1024);
    changesCollectorRestored.fromStream(0, content);
    changesCollectorRestored.applyChanges(newBuffer);

    newBuffer.position(0);
    pointer.position(0);
    Assert.assertEquals(pointer.compareTo(newBuffer), 0);
  }

  @Test
  public void testSerializationAndRestoreFromBuffer() {
    var random = new Random();
    var originalData = new byte[1024];
    random.nextBytes(originalData);

    var data = Arrays.copyOf(originalData, originalData.length);

    var pointer = ByteBuffer.wrap(data).order(ByteOrder.nativeOrder());

    var changesCollector = new WALPageChangesPortion(1024);

    var changes = new byte[128];
    random.nextBytes(changes);

    changesCollector.setBinaryValue(pointer, changes, 32);

    Assertions.assertThat(changesCollector.getBinaryValue(pointer, 32, 128)).isEqualTo(changes);
    changesCollector.applyChanges(pointer);

    var newBuffer =
        ByteBuffer.wrap(Arrays.copyOf(originalData, originalData.length))
            .order(ByteOrder.nativeOrder());

    var size = changesCollector.serializedSize();
    var content = new byte[size];
    changesCollector.toStream(0, content);

    var changesCollectorRestored = new WALPageChangesPortion(1024);
    changesCollectorRestored.fromStream(ByteBuffer.wrap(content).order(ByteOrder.nativeOrder()));
    changesCollectorRestored.applyChanges(newBuffer);

    newBuffer.position(0);
    pointer.position(0);
    Assert.assertEquals(pointer.compareTo(newBuffer), 0);
  }

  @Test
  public void testEmptyChanges() {
    var changesCollector = new WALPageChangesPortion(1024);
    var size = changesCollector.serializedSize();
    var bytes = new byte[size];
    changesCollector.toStream(0, bytes);
    var changesCollectorRestored = new WALPageChangesPortion(1024);
    changesCollectorRestored.fromStream(0, bytes);

    Assert.assertEquals(size, changesCollectorRestored.serializedSize());
  }

  @Test
  public void testReadNoChanges() {
    var data = new byte[1024];
    data[0] = 1;
    data[1] = 2;
    var pointer = ByteBuffer.wrap(data);

    var changesCollector = new WALPageChangesPortion(1024);
    var bytes = changesCollector.getBinaryValue(pointer, 0, 2);
    Assert.assertEquals(bytes[0], 1);
    Assert.assertEquals(bytes[1], 2);
  }

  @Test
  public void testGetCrossChanges() {
    var random = new Random();

    var originalData = new byte[1024];
    random.nextBytes(originalData);

    var data = Arrays.copyOf(originalData, originalData.length);

    var pointer = ByteBuffer.wrap(data).order(ByteOrder.nativeOrder());

    var changesCollector = new WALPageChangesPortion(1024);

    var changes = new byte[32];
    random.nextBytes(changes);

    changesCollector.setBinaryValue(pointer, changes, 32);
    changesCollector.setBinaryValue(pointer, changes, 128);

    var content = changesCollector.getBinaryValue(pointer, 32, 128);

    var expected = Arrays.copyOfRange(originalData, 32, 160);
    System.arraycopy(changes, 0, expected, 0, 32);
    System.arraycopy(changes, 0, expected, 96, 32);

    Assertions.assertThat(content).isEqualTo(expected);

    changesCollector.applyChanges(pointer);
    var result = new byte[128];
    pointer.position(32);
    pointer.get(result);

    Assertions.assertThat(result).isEqualTo(expected);
  }

  @Test
  public void testMultiPortionReadIfFirstPortionIsNotChanged() {
    var random = new Random();

    final var originalData = new byte[WALPageChangesPortion.PORTION_BYTES * 4];
    random.nextBytes(originalData);

    final var data = Arrays.copyOf(originalData, originalData.length);

    final var pointer = ByteBuffer.wrap(data).order(ByteOrder.nativeOrder());
    final var changes = new WALPageChangesPortion(data.length);

    final var smallChange = new byte[32];
    random.nextBytes(smallChange);

    changes.setBinaryValue(pointer, smallChange, WALPageChangesPortion.PORTION_BYTES + 37);

    final var actual =
        changes.getBinaryValue(pointer, 0, WALPageChangesPortion.PORTION_BYTES * 2);

    final var expected =
        Arrays.copyOfRange(originalData, 0, WALPageChangesPortion.PORTION_BYTES * 2);
    System.arraycopy(
        smallChange, 0, expected, WALPageChangesPortion.PORTION_BYTES + 37, smallChange.length);

    Assertions.assertThat(actual).isEqualTo(expected);

    changes.applyChanges(pointer);

    var result = new byte[WALPageChangesPortion.PORTION_BYTES * 2];
    pointer.position(0);
    pointer.get(result);

    Assertions.assertThat(result).isEqualTo(expected);
  }
}
