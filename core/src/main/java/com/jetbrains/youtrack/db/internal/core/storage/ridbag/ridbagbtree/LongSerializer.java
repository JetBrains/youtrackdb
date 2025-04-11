package com.jetbrains.youtrack.db.internal.core.storage.ridbag.ridbagbtree;

import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.wal.WALChanges;
import java.nio.ByteBuffer;

public final class LongSerializer {

  public static int getObjectSize(long value) {
    final var zeroBits = Long.numberOfLeadingZeros(value);
    final var zerosTillFullByte = zeroBits & 7;
    final var numberSize = 8 - (zeroBits - zerosTillFullByte) / 8;
    return numberSize + 1;
  }

  public static int getObjectSize(final byte[] stream, final int offset) {
    return stream[offset] + 1;
  }

  public static int getObjectSize(final ByteBuffer buffer) {
    return buffer.get() + 1;
  }

  public static int getObjectSize(final ByteBuffer buffer, int offset) {
    return buffer.get(offset) + 1;
  }

  public static int getObjectSize(
      final ByteBuffer buffer, final WALChanges changes, final int position) {
    return changes.getByteValue(buffer, position) + 1;
  }

  public static int serialize(long value, final byte[] stream, int position) {
    final var zeroBits = Long.numberOfLeadingZeros(value);
    final var zerosTillFullByte = zeroBits & 7;
    final var numberSize = 8 - (zeroBits - zerosTillFullByte) / 8;

    stream[position] = (byte) numberSize;
    position++;

    for (var i = 0; i < numberSize; i++) {
      stream[position + i] = (byte) ((0xFF) & value);
      value = value >>> 8;
    }

    return position + numberSize;
  }

  public static void serialize(long value, final ByteBuffer buffer) {
    final var zeroBits = Long.numberOfLeadingZeros(value);
    final var zerosTillFullByte = zeroBits & 7;
    final var numberSize = 8 - (zeroBits - zerosTillFullByte) / 8;

    buffer.put((byte) numberSize);

    for (var i = 0; i < numberSize; i++) {
      buffer.put((byte) ((0xFF) & value));
      value = value >>> 8;
    }
  }

  public static long deserialize(final ByteBuffer buffer) {
    final int numberSize = buffer.get();

    long value = 0;
    for (var i = 0; i < numberSize; i++) {
      value = value | ((0xFFL & buffer.get()) << (i * 8));
    }

    return value;
  }

  public static long deserialize(final ByteBuffer buffer, int offset) {
    final int numberSize = buffer.get(offset);
    offset++;

    long value = 0;
    for (var i = 0; i < numberSize; i++) {
      value = value | ((0xFFL & buffer.get(offset)) << (i * 8));
      offset++;
    }

    return value;
  }

  public static long deserialize(final byte[] stream, int startPosition) {
    final int numberSize = stream[startPosition];
    startPosition++;

    long value = 0;
    for (var i = 0; i < numberSize; i++) {
      value = value | ((0xFFL & stream[startPosition + i]) << (i * 8));
    }

    return value;
  }

  public static long deserialize(
      final ByteBuffer buffer, final WALChanges changes, int startPosition) {
    final int numberSize = changes.getByteValue(buffer, startPosition);
    startPosition++;

    long value = 0;
    for (var i = 0; i < numberSize; i++) {
      value = value | ((0xFFL & changes.getByteValue(buffer, startPosition + i)) << (i * 8));
    }

    return value;
  }
}
