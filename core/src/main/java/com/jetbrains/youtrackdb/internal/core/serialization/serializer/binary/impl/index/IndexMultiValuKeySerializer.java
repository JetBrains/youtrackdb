package com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.impl.index;

import com.jetbrains.youtrackdb.internal.common.comparator.DefaultComparator;
import com.jetbrains.youtrackdb.internal.common.serialization.BinaryConverter;
import com.jetbrains.youtrackdb.internal.common.serialization.BinaryConverterFactory;
import com.jetbrains.youtrackdb.internal.common.serialization.types.BinarySerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.BinaryTypeSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.ByteSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.IntegerSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.LongSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.ShortSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.UTF8Serializer;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.index.CompositeKey;
import com.jetbrains.youtrackdb.internal.core.index.IndexException;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.BinarySerializerFactory;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.impl.CompactedLinkSerializer;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALChanges;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import javax.annotation.Nullable;

public final class IndexMultiValuKeySerializer implements BinarySerializer<CompositeKey> {

  private static final BinaryConverter CONVERTER = BinaryConverterFactory.getConverter();

  @Override
  public int getObjectSize(BinarySerializerFactory serializerFactory, CompositeKey compositeKey,
      Object... hints) {
    final var types = (PropertyTypeInternal[]) hints;
    final var keys = compositeKey.getKeys();

    var size = 0;
    for (var i = 0; i < keys.size(); i++) {
      final var type = types[i];
      final var key = keys.get(i);

      size += ByteSerializer.BYTE_SIZE;
      if (key != null) {
        size += sizeOfKey(type, key, serializerFactory);
      }
    }

    return size + 2 * IntegerSerializer.INT_SIZE;
  }

  private static int sizeOfKey(final PropertyTypeInternal type, final Object key,
      BinarySerializerFactory serializerFactory) {
    return switch (type) {
      case BOOLEAN, BYTE -> 1;
      case DATE, DATETIME, DOUBLE, LONG -> LongSerializer.LONG_SIZE;
      case BINARY -> ((byte[]) key).length + IntegerSerializer.INT_SIZE;
      case DECIMAL -> {
        final var bigDecimal = ((BigDecimal) key);
        yield 2 * IntegerSerializer.INT_SIZE + bigDecimal.unscaledValue().toByteArray().length;
      }
      case FLOAT, INTEGER -> IntegerSerializer.INT_SIZE;
      case LINK -> CompactedLinkSerializer.INSTANCE.getObjectSize(serializerFactory, (RID) key);
      case SHORT -> ShortSerializer.SHORT_SIZE;
      case STRING -> UTF8Serializer.INSTANCE.getObjectSize(serializerFactory, (String) key);
      default -> throw new IndexException((String) null, "Unsupported key type " + type);
    };
  }

  @Override
  public void serialize(
      CompositeKey compositeKey, BinarySerializerFactory serializerFactory, byte[] stream,
      int startPosition, Object... hints) {
    final var buffer = ByteBuffer.wrap(stream);
    buffer.position(startPosition);

    serialize(compositeKey, buffer, (PropertyTypeInternal[]) hints, serializerFactory);
  }

  private static void serialize(CompositeKey compositeKey, ByteBuffer buffer,
      PropertyTypeInternal[] types, BinarySerializerFactory serializerFactory) {
    final var keys = compositeKey.getKeys();
    final var startPosition = buffer.position();
    buffer.position(startPosition + IntegerSerializer.INT_SIZE);

    buffer.putInt(types.length);

    for (var i = 0; i < types.length; i++) {
      final var type = types[i];
      final var key = keys.get(i);

      if (key == null) {
        buffer.put((byte) -(type.getId() + 1));
      } else {
        buffer.put((byte) type.getId());
        serializeKeyToByteBuffer(buffer, type, key, serializerFactory);
      }
    }

    buffer.putInt(startPosition, buffer.position() - startPosition);
  }

  private static void serializeKeyToByteBuffer(
      final ByteBuffer buffer, final PropertyTypeInternal type, final Object key,
      BinarySerializerFactory serializerFactory) {
    switch (type) {
      case BINARY -> {
        final var array = (byte[]) key;
        buffer.putInt(array.length);
        buffer.put(array);
      }
      case BOOLEAN -> buffer.put((Boolean) key ? (byte) 1 : 0);
      case BYTE -> buffer.put((Byte) key);
      case DATE, DATETIME -> buffer.putLong(((Date) key).getTime());
      case DECIMAL -> {
        final var decimal = (BigDecimal) key;
        buffer.putInt(decimal.scale());
        final var unscaledValue = decimal.unscaledValue().toByteArray();
        buffer.putInt(unscaledValue.length);
        buffer.put(unscaledValue);
      }
      case DOUBLE -> buffer.putLong(Double.doubleToLongBits((Double) key));
      case FLOAT -> buffer.putInt(Float.floatToIntBits((Float) key));
      case INTEGER -> buffer.putInt((Integer) key);
      case LINK ->
          CompactedLinkSerializer.INSTANCE.serializeInByteBufferObject(serializerFactory, (RID) key,
              buffer);
      case LONG -> buffer.putLong((Long) key);
      case SHORT -> buffer.putShort((Short) key);
      case STRING ->
          UTF8Serializer.INSTANCE.serializeInByteBufferObject(serializerFactory, (String) key,
              buffer);
      default -> throw new IndexException((String) null, "Unsupported index type " + type);
    }
  }

  @Override
  public CompositeKey deserialize(BinarySerializerFactory serializerFactory, byte[] stream,
      int startPosition) {
    final var buffer = ByteBuffer.wrap(stream);
    buffer.position(startPosition);

    return deserialize(buffer, serializerFactory);
  }

  private static CompositeKey deserialize(ByteBuffer buffer,
      BinarySerializerFactory serializerFactory) {
    buffer.position(buffer.position() + IntegerSerializer.INT_SIZE);

    final var keyLen = buffer.getInt();
    var keys = new CompositeKey(keyLen);
    for (var i = 0; i < keyLen; i++) {
      final var typeId = buffer.get();
      if (typeId < 0) {
        keys.addKey(null);
      } else {
        final var type = PropertyTypeInternal.getById(typeId);
        assert type != null;
        keys.addKey(deserializeKeyFromByteBuffer(buffer, type, serializerFactory));
      }
    }

    return keys;
  }

  private static CompositeKey deserialize(int offset, ByteBuffer buffer,
      BinarySerializerFactory serializerFactory) {
    offset += Integer.BYTES;
    final var keyLen = buffer.getInt(offset);
    offset += IntegerSerializer.INT_SIZE;

    var keys = new CompositeKey(keyLen);
    for (var i = 0; i < keyLen; i++) {
      final var typeId = buffer.get(offset);
      offset++;

      if (typeId < 0) {
        keys.addKey(null);
      } else {
        final var type = PropertyTypeInternal.getById(typeId);
        assert type != null;
        var delta = getKeySizeInByteBuffer(offset, buffer, type, serializerFactory);
        keys.addKey(deserializeKeyFromByteBuffer(offset, buffer, type, serializerFactory));
        offset += delta;
      }
    }

    return keys;
  }

  private static Object deserializeKeyFromByteBuffer(final ByteBuffer buffer,
      final PropertyTypeInternal type, BinarySerializerFactory serializerFactory) {
    return switch (type) {
      case BINARY -> {
        final var len = buffer.getInt();
        final var array = new byte[len];
        buffer.get(array);
        yield array;
      }
      case BOOLEAN -> buffer.get() > 0;
      case BYTE -> buffer.get();
      case DATE, DATETIME -> new Date(buffer.getLong());
      case DECIMAL -> {
        final var scale = buffer.getInt();
        final var unscaledValueLen = buffer.getInt();
        final var unscaledValue = new byte[unscaledValueLen];
        buffer.get(unscaledValue);
        yield new BigDecimal(new BigInteger(unscaledValue), scale);
      }
      case DOUBLE -> Double.longBitsToDouble(buffer.getLong());
      case FLOAT -> Float.intBitsToFloat(buffer.getInt());
      case INTEGER -> buffer.getInt();
      case LINK ->
          CompactedLinkSerializer.INSTANCE.deserializeFromByteBufferObject(serializerFactory,
              buffer);
      case LONG -> buffer.getLong();
      case SHORT -> buffer.getShort();
      case STRING ->
          UTF8Serializer.INSTANCE.deserializeFromByteBufferObject(serializerFactory, buffer);
      default -> throw new IndexException((String) null, "Unsupported index type " + type);
    };
  }

  private static Object deserializeKeyFromByteBuffer(
      int offset, final ByteBuffer buffer, final PropertyTypeInternal type,
      BinarySerializerFactory serializerFactory) {
    return switch (type) {
      case BINARY -> {
        final var len = buffer.getInt(offset);
        offset += Integer.BYTES;

        final var array = new byte[len];
        buffer.get(offset, array);
        yield array;
      }
      case BOOLEAN -> buffer.get(offset) > 0;
      case BYTE -> buffer.get(offset);
      case DATE, DATETIME -> new Date(buffer.getLong(offset));
      case DECIMAL -> {
        final var scale = buffer.getInt(offset);
        offset += Integer.BYTES;

        final var unscaledValueLen = buffer.getInt(offset);
        offset += Integer.BYTES;

        final var unscaledValue = new byte[unscaledValueLen];
        buffer.get(offset, unscaledValue);

        yield new BigDecimal(new BigInteger(unscaledValue), scale);
      }
      case DOUBLE -> Double.longBitsToDouble(buffer.getLong(offset));
      case FLOAT -> Float.intBitsToFloat(buffer.getInt(offset));
      case INTEGER -> buffer.getInt(offset);
      case LINK ->
          CompactedLinkSerializer.INSTANCE.deserializeFromByteBufferObject(serializerFactory,
              offset, buffer);
      case LONG -> buffer.getLong(offset);
      case SHORT -> buffer.getShort(offset);
      case STRING ->
          UTF8Serializer.INSTANCE.deserializeFromByteBufferObject(serializerFactory, offset,
              buffer);
      default -> throw new IndexException((String) null, "Unsupported index type " + type);
    };
  }

  private static int getKeySizeInByteBuffer(int offset, final ByteBuffer buffer,
      final PropertyTypeInternal type, BinarySerializerFactory serializerFactory) {
    return switch (type) {
      case BINARY -> {
        final var len = buffer.getInt(offset);
        yield Integer.BYTES + len;
      }
      case BOOLEAN, BYTE -> Byte.BYTES;
      case DATE, DATETIME, DOUBLE, LONG -> Long.BYTES;
      case DECIMAL -> {
        offset += Integer.BYTES;
        final var unscaledValueLen = buffer.getInt(offset);
        yield 2 * Integer.BYTES + unscaledValueLen;
      }
      case FLOAT, INTEGER -> Integer.BYTES;
      case LINK ->
          CompactedLinkSerializer.INSTANCE.getObjectSizeInByteBuffer(serializerFactory, offset,
              buffer);
      case SHORT -> Short.BYTES;
      case STRING ->
          UTF8Serializer.INSTANCE.getObjectSizeInByteBuffer(serializerFactory, offset, buffer);
      default -> throw new IndexException((String) null, "Unsupported index type " + type);
    };
  }

  private static Object deserializeKeyFromByteBuffer(
      final int offset, final ByteBuffer buffer, final PropertyTypeInternal type,
      final WALChanges walChanges, BinarySerializerFactory serializerFactory) {
    return switch (type) {
      case BINARY -> {
        final var len = walChanges.getIntValue(buffer, offset);
        yield walChanges.getBinaryValue(buffer, offset + IntegerSerializer.INT_SIZE, len);
      }
      case BOOLEAN -> walChanges.getByteValue(buffer, offset) > 0;
      case BYTE -> walChanges.getByteValue(buffer, offset);
      case DATE, DATETIME -> new Date(walChanges.getLongValue(buffer, offset));
      case DECIMAL -> {
        final var scale = walChanges.getIntValue(buffer, offset);
        final var unscaledValueLen =
            walChanges.getIntValue(buffer, offset + IntegerSerializer.INT_SIZE);
        final var unscaledValue =
            walChanges.getBinaryValue(
                buffer, offset + 2 * IntegerSerializer.INT_SIZE, unscaledValueLen);
        yield new BigDecimal(new BigInteger(unscaledValue), scale);
      }
      case DOUBLE -> Double.longBitsToDouble(walChanges.getLongValue(buffer, offset));
      case FLOAT -> Float.intBitsToFloat(walChanges.getIntValue(buffer, offset));
      case INTEGER -> walChanges.getIntValue(buffer, offset);
      case LINK ->
          CompactedLinkSerializer.INSTANCE.deserializeFromByteBufferObject(serializerFactory,
              buffer, walChanges, offset);
      case LONG -> walChanges.getLongValue(buffer, offset);
      case SHORT -> walChanges.getShortValue(buffer, offset);
      case STRING ->
          UTF8Serializer.INSTANCE.deserializeFromByteBufferObject(serializerFactory, buffer,
              walChanges,
              offset);
      default -> throw new IndexException((String) null, "Unsupported index type " + type);
    };
  }

  @Override
  public int getObjectSize(BinarySerializerFactory serializerFactory, byte[] stream,
      int startPosition) {
    //noinspection RedundantCast
    return ((ByteBuffer) ByteBuffer.wrap(stream).position(startPosition)).getInt();
  }

  @Override
  public byte getId() {
    return -1;
  }

  @Override
  public int getObjectSizeNative(BinarySerializerFactory serializerFactory, byte[] stream,
      int startPosition) {
    //noinspection RedundantCast
    return ((ByteBuffer) ByteBuffer.wrap(stream).order(ByteOrder.nativeOrder())
        .position(startPosition))
        .getInt();
  }

  @Override
  public void serializeNativeObject(
      CompositeKey compositeKey, BinarySerializerFactory serializerFactory, byte[] stream,
      int startPosition, Object... hints) {
    @SuppressWarnings("RedundantCast")
    final var buffer =
        (ByteBuffer) ByteBuffer.wrap(stream).order(ByteOrder.nativeOrder()).position(startPosition);
    serialize(compositeKey, buffer, (PropertyTypeInternal[]) hints, serializerFactory);
  }

  @Override
  public CompositeKey deserializeNativeObject(BinarySerializerFactory serializerFactory,
      byte[] stream, int startPosition) {
    @SuppressWarnings("RedundantCast")
    final var buffer =
        (ByteBuffer) ByteBuffer.wrap(stream).order(ByteOrder.nativeOrder()).position(startPosition);
    return deserialize(buffer, serializerFactory);
  }

  @Override
  public boolean isFixedLength() {
    return false;
  }

  @Override
  public int getFixedLength() {
    return 0;
  }

  @Nullable @Override
  public CompositeKey preprocess(BinarySerializerFactory serializerFactory, CompositeKey value,
      Object... hints) {
    if (value == null) {
      return null;
    }

    final var types = (PropertyTypeInternal[]) hints;
    final var keys = value.getKeys();

    var preprocess = false;
    for (var i = 0; i < keys.size(); i++) {
      final var type = types[i];

      if (type == PropertyTypeInternal.DATE || (type == PropertyTypeInternal.LINK && !(keys.get(
          i) instanceof RID))) {
        preprocess = true;
        break;
      }
    }

    if (!preprocess) {
      return value;
    }

    final var compositeKey = new CompositeKey();

    for (var i = 0; i < keys.size(); i++) {
      final var key = keys.get(i);
      final var type = types[i];
      if (key != null) {
        if (type == PropertyTypeInternal.DATE) {
          final var calendar = Calendar.getInstance();
          calendar.setTime((Date) key);
          calendar.set(Calendar.HOUR_OF_DAY, 0);
          calendar.set(Calendar.MINUTE, 0);
          calendar.set(Calendar.SECOND, 0);
          calendar.set(Calendar.MILLISECOND, 0);

          compositeKey.addKey(calendar.getTime());
        } else if (type == PropertyTypeInternal.LINK) {
          compositeKey.addKey(((Identifiable) key).getIdentity());
        } else {
          compositeKey.addKey(key);
        }
      } else {
        compositeKey.addKey(null);
      }
    }

    return compositeKey;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void serializeInByteBufferObject(
      BinarySerializerFactory serializerFactory, CompositeKey object, ByteBuffer buffer,
      Object... hints) {
    serialize(object, buffer, (PropertyTypeInternal[]) hints, serializerFactory);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public CompositeKey deserializeFromByteBufferObject(BinarySerializerFactory serializerFactory,
      ByteBuffer buffer) {
    return deserialize(buffer, serializerFactory);
  }

  @Override
  public CompositeKey deserializeFromByteBufferObject(BinarySerializerFactory serializerFactory,
      int offset, ByteBuffer buffer) {
    return deserialize(offset, buffer, serializerFactory);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getObjectSizeInByteBuffer(BinarySerializerFactory serializerFactory,
      ByteBuffer buffer) {
    return buffer.getInt();
  }

  @Override
  public int getObjectSizeInByteBuffer(BinarySerializerFactory serializerFactory, int offset,
      ByteBuffer buffer) {
    return buffer.getInt(offset);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public CompositeKey deserializeFromByteBufferObject(
      BinarySerializerFactory serializerFactory, ByteBuffer buffer, WALChanges walChanges,
      int offset) {
    offset += IntegerSerializer.INT_SIZE;

    final var keyLen = walChanges.getIntValue(buffer, offset);
    offset += IntegerSerializer.INT_SIZE;

    final List<Object> keys = new ArrayList<>(keyLen);
    for (var i = 0; i < keyLen; i++) {
      final var typeId = walChanges.getByteValue(buffer, offset);
      offset += ByteSerializer.BYTE_SIZE;

      if (typeId < 0) {
        keys.add(null);
      } else {
        final var type = PropertyTypeInternal.getById(typeId);
        assert type != null;
        final var key = deserializeKeyFromByteBuffer(offset, buffer, type, walChanges,
            serializerFactory);
        offset += sizeOfKey(type, key, serializerFactory);
        keys.add(key);
      }
    }

    return new CompositeKey(keys);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getObjectSizeInByteBuffer(ByteBuffer buffer, WALChanges walChanges, int offset) {
    return walChanges.getIntValue(buffer, offset);
  }

  /**
   * Zero-deserialization field-by-field comparison of two serialized CompositeKeys.
   * Compares directly in the page buffer and search key byte array without creating
   * CompositeKey objects. Delegates to per-type serializer compareInByteBuffer()
   * where formats match; uses inline comparison for FLOAT, DOUBLE, BOOLEAN, BYTE,
   * LINK, and DECIMAL.
   */
  @Override
  public int compareInByteBuffer(
      BinarySerializerFactory serializerFactory,
      int bufferOffset, ByteBuffer buffer,
      byte[] serializedKey, int keyOffset) {
    // Skip total size int in both
    bufferOffset += Integer.BYTES;
    keyOffset += Integer.BYTES;

    // Read number of keys from both
    final var pageKeysCount = buffer.getInt(bufferOffset);
    final var searchKeysCount =
        CONVERTER.getInt(serializedKey, keyOffset, ByteOrder.nativeOrder());

    bufferOffset += Integer.BYTES;
    keyOffset += Integer.BYTES;

    final var minKeys = Math.min(pageKeysCount, searchKeysCount);
    for (var i = 0; i < minKeys; i++) {
      final var pageTypeId = buffer.get(bufferOffset);
      final var searchTypeId = serializedKey[keyOffset];

      bufferOffset += Byte.BYTES;
      keyOffset += Byte.BYTES;

      // Both null — equal, continue to next field
      if (pageTypeId < 0 && searchTypeId < 0) {
        continue;
      }
      // Page null < any non-null
      if (pageTypeId < 0) {
        return -1;
      }
      // Any non-null > search null
      if (searchTypeId < 0) {
        return 1;
      }

      assert pageTypeId == searchTypeId
          : "Type mismatch at field " + i + ": page=" + pageTypeId + " search=" + searchTypeId;
      final var type = PropertyTypeInternal.getById(pageTypeId);
      assert type != null;

      final var cmp =
          compareField(type, serializerFactory, bufferOffset, buffer, serializedKey, keyOffset);
      if (cmp != 0) {
        return cmp;
      }

      // Advance both offsets past this field
      bufferOffset += getKeySizeInByteBuffer(bufferOffset, buffer, type, serializerFactory);
      keyOffset += getKeySizeNative(type, serializedKey, keyOffset, serializerFactory);
    }

    return Integer.compare(pageKeysCount, searchKeysCount);
  }

  /**
   * Compares a single field from the page buffer against the search key byte array.
   * Delegates to per-type serializer compareInByteBuffer() for types where the
   * on-disk format matches (LONG, DATE, DATETIME, INTEGER, SHORT, STRING, BINARY).
   * Uses inline comparison for FLOAT/DOUBLE (stored as int/long bits), BOOLEAN/BYTE,
   * LINK (compacted format), and DECIMAL (BigDecimal fallback).
   */
  private static int compareField(
      PropertyTypeInternal type, BinarySerializerFactory serializerFactory,
      int bufferOffset, ByteBuffer buffer,
      byte[] serializedKey, int keyOffset) {
    return switch (type) {
      case LONG, DATE, DATETIME ->
          LongSerializer.INSTANCE.compareInByteBuffer(
              serializerFactory, bufferOffset, buffer, serializedKey, keyOffset);
      case INTEGER ->
          IntegerSerializer.INSTANCE.compareInByteBuffer(
              serializerFactory, bufferOffset, buffer, serializedKey, keyOffset);
      case SHORT ->
          ShortSerializer.INSTANCE.compareInByteBuffer(
              serializerFactory, bufferOffset, buffer, serializedKey, keyOffset);
      case STRING ->
          UTF8Serializer.INSTANCE.compareInByteBuffer(
              serializerFactory, bufferOffset, buffer, serializedKey, keyOffset);
      case BINARY ->
          BinaryTypeSerializer.INSTANCE.compareInByteBuffer(
              serializerFactory, bufferOffset, buffer, serializedKey, keyOffset);
      case FLOAT -> Float.compare(
          Float.intBitsToFloat(buffer.getInt(bufferOffset)),
          Float.intBitsToFloat(
              CONVERTER.getInt(serializedKey, keyOffset, ByteOrder.nativeOrder())));
      case DOUBLE -> Double.compare(
          Double.longBitsToDouble(buffer.getLong(bufferOffset)),
          Double.longBitsToDouble(
              CONVERTER.getLong(serializedKey, keyOffset, ByteOrder.nativeOrder())));
      case BOOLEAN, BYTE -> Byte.compare(buffer.get(bufferOffset), serializedKey[keyOffset]);
      case LINK ->
          compareLinkInline(bufferOffset, buffer, serializedKey, keyOffset);
      case DECIMAL ->
          compareDecimalFallback(
              serializerFactory, bufferOffset, buffer, serializedKey, keyOffset);
      default -> throw new IndexException((String) null, "Unsupported index type " + type);
    };
  }

  /**
   * Inline comparison for LINK fields in compacted format:
   * [clusterId:2][numberSize:1][clusterPosition:numberSize bytes, little-endian].
   */
  private static int compareLinkInline(
      int bufferOffset, ByteBuffer buffer,
      byte[] serializedKey, int keyOffset) {
    // Compare cluster IDs
    final var pageClusterId = buffer.getShort(bufferOffset);
    final var searchClusterId =
        CONVERTER.getShort(serializedKey, keyOffset, ByteOrder.nativeOrder());
    final var clusterCmp = Short.compare(pageClusterId, searchClusterId);
    if (clusterCmp != 0) {
      return clusterCmp;
    }

    // Compare cluster positions (variable-length little-endian)
    final var pageNumberSize = buffer.get(bufferOffset + Short.BYTES);
    final var searchNumberSize = serializedKey[keyOffset + Short.BYTES];

    final var pagePosition =
        readCompactedPosition(buffer, bufferOffset + Short.BYTES + Byte.BYTES, pageNumberSize);
    final var searchPosition =
        readCompactedPositionNative(
            serializedKey, keyOffset + Short.BYTES + Byte.BYTES, searchNumberSize);

    return Long.compare(pagePosition, searchPosition);
  }

  /** Reconstructs a compacted cluster position from a page ByteBuffer. */
  private static long readCompactedPosition(ByteBuffer buffer, int offset, int numberSize) {
    long position = 0;
    for (var i = 0; i < numberSize; i++) {
      position = position | ((long) (0xFF & buffer.get(offset + i)) << (i * 8));
    }
    return position;
  }

  /** Reconstructs a compacted cluster position from a native byte array. */
  private static long readCompactedPositionNative(byte[] stream, int offset, int numberSize) {
    long position = 0;
    for (var i = 0; i < numberSize; i++) {
      position = position | ((long) (0xFF & stream[offset + i]) << (i * 8));
    }
    return position;
  }

  /**
   * Fallback comparison for DECIMAL fields — no meaningful byte-level ordering exists
   * for (scale, unscaledValue) pairs, so deserialize both sides.
   */
  private static int compareDecimalFallback(
      BinarySerializerFactory serializerFactory,
      int bufferOffset, ByteBuffer buffer,
      byte[] serializedKey, int keyOffset) {
    var pageVal = deserializeKeyFromByteBuffer(bufferOffset, buffer,
        PropertyTypeInternal.DECIMAL, serializerFactory);
    @SuppressWarnings("RedundantCast")
    var searchBuffer =
        (ByteBuffer) ByteBuffer.wrap(serializedKey).order(ByteOrder.nativeOrder());
    var searchVal = deserializeKeyFromByteBuffer(keyOffset, searchBuffer,
        PropertyTypeInternal.DECIMAL, serializerFactory);
    return DefaultComparator.INSTANCE.compare(pageVal, searchVal);
  }

  /**
   * Computes the serialized size of a single field in a native byte array.
   * Mirrors {@link #getKeySizeInByteBuffer} but reads from byte[] via BinaryConverter.
   */
  private static int getKeySizeNative(PropertyTypeInternal type, byte[] stream, int offset,
      BinarySerializerFactory serializerFactory) {
    return switch (type) {
      case BINARY -> {
        final var len =
            CONVERTER.getInt(stream, offset, ByteOrder.nativeOrder());
        yield Integer.BYTES + len;
      }
      case BOOLEAN, BYTE -> Byte.BYTES;
      case DATE, DATETIME, DOUBLE, LONG -> Long.BYTES;
      case DECIMAL -> {
        final var unscaledValueLen =
            CONVERTER.getInt(stream, offset + Integer.BYTES, ByteOrder.nativeOrder());
        yield 2 * Integer.BYTES + unscaledValueLen;
      }
      case FLOAT, INTEGER -> Integer.BYTES;
      case LINK ->
          CompactedLinkSerializer.INSTANCE.getObjectSizeNative(
              serializerFactory, stream, offset);
      case SHORT -> Short.BYTES;
      case STRING ->
          UTF8Serializer.INSTANCE.getObjectSizeNative(serializerFactory, stream, offset);
      default -> throw new IndexException((String) null, "Unsupported index type " + type);
    };
  }

  /**
   * WAL-aware variant of compareInByteBuffer. Reads page data through the WAL overlay.
   * Inlines primitive reads via walChanges methods for LONG, INTEGER, SHORT, FLOAT,
   * DOUBLE, BOOLEAN, BYTE, DATE, DATETIME. Falls through to deserialization for
   * STRING, LINK, BINARY, and DECIMAL.
   */
  @Override
  public int compareInByteBufferWithWALChanges(
      BinarySerializerFactory serializerFactory,
      ByteBuffer buffer, WALChanges walChanges, int pageOffset,
      byte[] serializedKey, int keyOffset) {
    // Skip total size int in both
    pageOffset += Integer.BYTES;
    keyOffset += Integer.BYTES;

    // Read number of keys from both (page via WAL overlay, search from byte[])
    final var pageKeysCount = walChanges.getIntValue(buffer, pageOffset);
    final var searchKeysCount =
        CONVERTER.getInt(serializedKey, keyOffset, ByteOrder.nativeOrder());

    pageOffset += Integer.BYTES;
    keyOffset += Integer.BYTES;

    final var minKeys = Math.min(pageKeysCount, searchKeysCount);
    for (var i = 0; i < minKeys; i++) {
      final var pageTypeId = walChanges.getByteValue(buffer, pageOffset);
      final var searchTypeId = serializedKey[keyOffset];

      pageOffset += Byte.BYTES;
      keyOffset += Byte.BYTES;

      // Both null — equal, continue to next field
      if (pageTypeId < 0 && searchTypeId < 0) {
        continue;
      }
      // Page null < any non-null
      if (pageTypeId < 0) {
        return -1;
      }
      // Any non-null > search null
      if (searchTypeId < 0) {
        return 1;
      }

      assert pageTypeId == searchTypeId
          : "Type mismatch at field " + i + ": page=" + pageTypeId + " search=" + searchTypeId;
      final var type = PropertyTypeInternal.getById(pageTypeId);
      assert type != null;

      final var cmp = compareFieldWAL(
          type, serializerFactory, buffer, walChanges, pageOffset, serializedKey, keyOffset);
      if (cmp != 0) {
        return cmp;
      }

      // Advance both offsets past this field
      pageOffset +=
          getKeySizeInByteBufferWithWAL(pageOffset, buffer, walChanges, type);
      keyOffset += getKeySizeNative(type, serializedKey, keyOffset, serializerFactory);
    }

    return Integer.compare(pageKeysCount, searchKeysCount);
  }

  /**
   * Compares a single field from the page buffer (with WAL overlay) against the search
   * key byte array. Inlines primitive reads via walChanges methods. Falls through to
   * deserialization for STRING, LINK, BINARY, and DECIMAL.
   */
  private static int compareFieldWAL(
      PropertyTypeInternal type, BinarySerializerFactory serializerFactory,
      ByteBuffer buffer, WALChanges walChanges, int pageOffset,
      byte[] serializedKey, int keyOffset) {
    return switch (type) {
      case LONG, DATE, DATETIME -> Long.compare(
          walChanges.getLongValue(buffer, pageOffset),
          CONVERTER.getLong(serializedKey, keyOffset, ByteOrder.nativeOrder()));
      case INTEGER -> Integer.compare(
          walChanges.getIntValue(buffer, pageOffset),
          CONVERTER.getInt(serializedKey, keyOffset, ByteOrder.nativeOrder()));
      case SHORT -> Short.compare(
          walChanges.getShortValue(buffer, pageOffset),
          CONVERTER.getShort(serializedKey, keyOffset, ByteOrder.nativeOrder()));
      case FLOAT -> Float.compare(
          Float.intBitsToFloat(walChanges.getIntValue(buffer, pageOffset)),
          Float.intBitsToFloat(
              CONVERTER.getInt(serializedKey, keyOffset, ByteOrder.nativeOrder())));
      case DOUBLE -> Double.compare(
          Double.longBitsToDouble(walChanges.getLongValue(buffer, pageOffset)),
          Double.longBitsToDouble(
              CONVERTER.getLong(serializedKey, keyOffset, ByteOrder.nativeOrder())));
      case BOOLEAN, BYTE -> Byte.compare(
          walChanges.getByteValue(buffer, pageOffset), serializedKey[keyOffset]);
      case STRING, LINK, BINARY, DECIMAL ->
          compareFieldWALFallback(
              type, serializerFactory, buffer, walChanges, pageOffset, serializedKey, keyOffset);
      default -> throw new IndexException((String) null, "Unsupported index type " + type);
    };
  }

  /**
   * Fallback comparison for complex types in WAL path: deserializes both sides and
   * compares via DefaultComparator. Used for STRING, LINK, BINARY, and DECIMAL.
   */
  private static int compareFieldWALFallback(
      PropertyTypeInternal type, BinarySerializerFactory serializerFactory,
      ByteBuffer buffer, WALChanges walChanges, int pageOffset,
      byte[] serializedKey, int keyOffset) {
    var pageVal =
        deserializeKeyFromByteBuffer(pageOffset, buffer, type, walChanges, serializerFactory);
    @SuppressWarnings("RedundantCast")
    var searchBuffer =
        (ByteBuffer) ByteBuffer.wrap(serializedKey).order(ByteOrder.nativeOrder());
    var searchVal =
        deserializeKeyFromByteBuffer(keyOffset, searchBuffer, type, serializerFactory);
    return DefaultComparator.INSTANCE.compare(pageVal, searchVal);
  }

  /**
   * WAL-aware variant of getKeySizeInByteBuffer — reads field sizes through the WAL
   * overlay for variable-length types.
   */
  private static int getKeySizeInByteBufferWithWAL(int offset, ByteBuffer buffer,
      WALChanges walChanges, PropertyTypeInternal type) {
    return switch (type) {
      case BINARY -> {
        final var len = walChanges.getIntValue(buffer, offset);
        yield Integer.BYTES + len;
      }
      case BOOLEAN, BYTE -> Byte.BYTES;
      case DATE, DATETIME, DOUBLE, LONG -> Long.BYTES;
      case DECIMAL -> {
        final var unscaledValueLen =
            walChanges.getIntValue(buffer, offset + Integer.BYTES);
        yield 2 * Integer.BYTES + unscaledValueLen;
      }
      case FLOAT, INTEGER -> Integer.BYTES;
      case LINK ->
          CompactedLinkSerializer.INSTANCE.getObjectSizeInByteBuffer(
              buffer, walChanges, offset);
      case SHORT -> Short.BYTES;
      case STRING ->
          UTF8Serializer.INSTANCE.getObjectSizeInByteBuffer(buffer, walChanges, offset);
      default -> throw new IndexException((String) null, "Unsupported index type " + type);
    };
  }
}
