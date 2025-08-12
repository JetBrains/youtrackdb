package com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.impl.index;

import com.jetbrains.youtrackdb.api.record.Identifiable;
import com.jetbrains.youtrackdb.api.record.RID;
import com.jetbrains.youtrackdb.internal.common.serialization.types.BinarySerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.ByteSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.IntegerSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.LongSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.ShortSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.UTF8Serializer;
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
        buffer.put((byte) (-(type.getId() + 1)));
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
      case BINARY:
        final var array = (byte[]) key;
        buffer.putInt(array.length);
        buffer.put(array);
        return;
      case BOOLEAN:
        buffer.put((Boolean) key ? (byte) 1 : 0);
        return;
      case BYTE:
        buffer.put((Byte) key);
        return;
      case DATE:
      case DATETIME:
        buffer.putLong(((Date) key).getTime());
        return;
      case DECIMAL:
        final var decimal = (BigDecimal) key;
        buffer.putInt(decimal.scale());
        final var unscaledValue = decimal.unscaledValue().toByteArray();
        buffer.putInt(unscaledValue.length);
        buffer.put(unscaledValue);
        return;
      case DOUBLE:
        buffer.putLong(Double.doubleToLongBits((Double) key));
        return;
      case FLOAT:
        buffer.putInt(Float.floatToIntBits((Float) key));
        return;
      case INTEGER:
        buffer.putInt((Integer) key);
        return;
      case LINK:
        CompactedLinkSerializer.INSTANCE.serializeInByteBufferObject(serializerFactory, (RID) key,
            buffer);
        return;
      case LONG:
        buffer.putLong((Long) key);
        return;
      case SHORT:
        buffer.putShort((Short) key);
        return;
      case STRING:
        UTF8Serializer.INSTANCE.serializeInByteBufferObject(serializerFactory, (String) key,
            buffer);
        return;
      default:
        throw new IndexException((String) null, "Unsupported index type " + type);
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
    switch (type) {
      case BINARY:
        final var len = buffer.getInt();
        final var array = new byte[len];
        buffer.get(array);
        return array;
      case BOOLEAN:
        return buffer.get() > 0;
      case BYTE:
        return buffer.get();
      case DATE:
      case DATETIME:
        return new Date(buffer.getLong());
      case DECIMAL:
        final var scale = buffer.getInt();
        final var unscaledValueLen = buffer.getInt();
        final var unscaledValue = new byte[unscaledValueLen];
        buffer.get(unscaledValue);
        return new BigDecimal(new BigInteger(unscaledValue), scale);
      case DOUBLE:
        return Double.longBitsToDouble(buffer.getLong());
      case FLOAT:
        return Float.intBitsToFloat(buffer.getInt());
      case INTEGER:
        return buffer.getInt();
      case LINK:
        return CompactedLinkSerializer.INSTANCE.deserializeFromByteBufferObject(serializerFactory,
            buffer);
      case LONG:
        return buffer.getLong();
      case SHORT:
        return buffer.getShort();
      case STRING:
        return UTF8Serializer.INSTANCE.deserializeFromByteBufferObject(serializerFactory, buffer);
      default:
        throw new IndexException((String) null, "Unsupported index type " + type);
    }
  }

  private static Object deserializeKeyFromByteBuffer(
      int offset, final ByteBuffer buffer, final PropertyTypeInternal type,
      BinarySerializerFactory serializerFactory) {
    switch (type) {
      case BINARY:
        final var len = buffer.getInt(offset);
        offset += Integer.BYTES;

        final var array = new byte[len];
        buffer.get(offset, array);
        return array;
      case BOOLEAN:
        return buffer.get(offset) > 0;
      case BYTE:
        return buffer.get(offset);
      case DATE:
      case DATETIME:
        return new Date(buffer.getLong(offset));
      case DECIMAL:
        final var scale = buffer.getInt(offset);
        offset += Integer.BYTES;

        final var unscaledValueLen = buffer.getInt(offset);
        offset += Integer.BYTES;

        final var unscaledValue = new byte[unscaledValueLen];
        buffer.get(offset, unscaledValue);

        return new BigDecimal(new BigInteger(unscaledValue), scale);
      case DOUBLE:
        return Double.longBitsToDouble(buffer.getLong(offset));
      case FLOAT:
        return Float.intBitsToFloat(buffer.getInt(offset));
      case INTEGER:
        return buffer.getInt(offset);
      case LINK:
        return CompactedLinkSerializer.INSTANCE.deserializeFromByteBufferObject(serializerFactory,
            offset, buffer);
      case LONG:
        return buffer.getLong(offset);
      case SHORT:
        return buffer.getShort(offset);
      case STRING:
        return UTF8Serializer.INSTANCE.deserializeFromByteBufferObject(serializerFactory, offset,
            buffer);
      default:
        throw new IndexException((String) null, "Unsupported index type " + type);
    }
  }

  private static int getKeySizeInByteBuffer(int offset, final ByteBuffer buffer,
      final PropertyTypeInternal type, BinarySerializerFactory serializerFactory) {
    switch (type) {
      case BINARY:
        final var len = buffer.getInt(offset);
        return Integer.BYTES + len;
      case BOOLEAN, BYTE:
        return Byte.BYTES;
      case DATE:
      case DATETIME, DOUBLE, LONG:
        return Long.BYTES;
      case DECIMAL:
        offset += Integer.BYTES;
        final var unscaledValueLen = buffer.getInt(offset);
        return 2 * Integer.BYTES + unscaledValueLen;
      case FLOAT, INTEGER:
        return Integer.BYTES;
      case LINK:
        return CompactedLinkSerializer.INSTANCE.getObjectSizeInByteBuffer(serializerFactory, offset,
            buffer);
      case SHORT:
        return Short.BYTES;
      case STRING:
        return UTF8Serializer.INSTANCE.getObjectSizeInByteBuffer(serializerFactory, offset, buffer);
      default:
        throw new IndexException((String) null, "Unsupported index type " + type);
    }
  }

  private static Object deserializeKeyFromByteBuffer(
      final int offset, final ByteBuffer buffer, final PropertyTypeInternal type,
      final WALChanges walChanges, BinarySerializerFactory serializerFactory) {
    switch (type) {
      case BINARY:
        final var len = walChanges.getIntValue(buffer, offset);
        return walChanges.getBinaryValue(buffer, offset + IntegerSerializer.INT_SIZE, len);
      case BOOLEAN:
        return walChanges.getByteValue(buffer, offset) > 0;
      case BYTE:
        return walChanges.getByteValue(buffer, offset);
      case DATE:
      case DATETIME:
        return new Date(walChanges.getLongValue(buffer, offset));
      case DECIMAL:
        final var scale = walChanges.getIntValue(buffer, offset);
        final var unscaledValueLen =
            walChanges.getIntValue(buffer, offset + IntegerSerializer.INT_SIZE);
        final var unscaledValue =
            walChanges.getBinaryValue(
                buffer, offset + 2 * IntegerSerializer.INT_SIZE, unscaledValueLen);
        return new BigDecimal(new BigInteger(unscaledValue), scale);
      case DOUBLE:
        return Double.longBitsToDouble(walChanges.getLongValue(buffer, offset));
      case FLOAT:
        return Float.intBitsToFloat(walChanges.getIntValue(buffer, offset));
      case INTEGER:
        return walChanges.getIntValue(buffer, offset);
      case LINK:
        return CompactedLinkSerializer.INSTANCE.deserializeFromByteBufferObject(serializerFactory,
            buffer, walChanges, offset);
      case LONG:
        return walChanges.getLongValue(buffer, offset);
      case SHORT:
        return walChanges.getShortValue(buffer, offset);
      case STRING:
        return UTF8Serializer.INSTANCE.deserializeFromByteBufferObject(serializerFactory, buffer,
            walChanges,
            offset);
      default:
        throw new IndexException((String) null, "Unsupported index type " + type);
    }
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
    return ((ByteBuffer)
        ByteBuffer.wrap(stream).order(ByteOrder.nativeOrder()).position(startPosition))
        .getInt();
  }

  @Override
  public void serializeNativeObject(
      CompositeKey compositeKey, BinarySerializerFactory serializerFactory, byte[] stream,
      int startPosition, Object... hints) {
    @SuppressWarnings("RedundantCast") final var buffer =
        (ByteBuffer) ByteBuffer.wrap(stream).order(ByteOrder.nativeOrder()).position(startPosition);
    serialize(compositeKey, buffer, (PropertyTypeInternal[]) hints, serializerFactory);
  }

  @Override
  public CompositeKey deserializeNativeObject(BinarySerializerFactory serializerFactory,
      byte[] stream, int startPosition) {
    @SuppressWarnings("RedundantCast") final var buffer =
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

  @Nullable
  @Override
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
}
