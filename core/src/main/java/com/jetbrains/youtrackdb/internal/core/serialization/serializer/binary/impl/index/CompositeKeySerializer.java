/*
 *
 *
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *
 *
 */

package com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.impl.index;

import com.jetbrains.youtrackdb.internal.common.serialization.types.BinarySerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.IntegerSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.NullSerializer;
import com.jetbrains.youtrackdb.internal.common.util.CommonConst;
import com.jetbrains.youtrackdb.internal.core.index.CompositeKey;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.BinarySerializerFactory;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALChanges;
import java.nio.ByteBuffer;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Serializer that is used for serialization of {@link CompositeKey} keys in index.
 *
 * @since 29.07.11
 */
public class CompositeKeySerializer implements BinarySerializer<CompositeKey> {

  public static final CompositeKeySerializer INSTANCE = new CompositeKeySerializer();
  public static final byte ID = 14;

  @Override
  public int getObjectSize(BinarySerializerFactory serializerFactory, CompositeKey compositeKey,
      Object... hints) {
    final var types = getKeyTypes(hints);

    final var keys = compositeKey.getKeys();

    var size = 2 * IntegerSerializer.INT_SIZE;

    for (var i = 0; i < keys.size(); i++) {
      final var key = keys.get(i);

      if (key != null) {
        final PropertyTypeInternal type;
        if (types.length > i) {
          type = types[i];
        } else {
          type = PropertyTypeInternal.getTypeByClass(key.getClass());
        }

        size +=
            BinarySerializerFactory.TYPE_IDENTIFIER_SIZE
                + serializerFactory.getObjectSerializer(type).getObjectSize(serializerFactory, key);
      } else {
        size +=
            BinarySerializerFactory.TYPE_IDENTIFIER_SIZE
                + NullSerializer.INSTANCE.getObjectSize(serializerFactory, null);
      }
    }

    return size;
  }

  @Override
  public void serialize(
      CompositeKey compositeKey, BinarySerializerFactory serializerFactory, byte[] stream,
      int startPosition, Object... hints) {
    final var types = getKeyTypes(hints);

    final var keys = compositeKey.getKeys();
    final var keysSize = keys.size();

    final var oldStartPosition = startPosition;

    startPosition += IntegerSerializer.INT_SIZE;

    IntegerSerializer.serializeLiteral(keysSize, stream, startPosition);

    startPosition += IntegerSerializer.INT_SIZE;

    for (var i = 0; i < keys.size(); i++) {
      final var key = keys.get(i);

      BinarySerializer<Object> binarySerializer;
      if (key != null) {
        final PropertyTypeInternal type;
        if (types.length > i) {
          type = types[i];
        } else {
          type = PropertyTypeInternal.getTypeByClass(key.getClass());
        }

        binarySerializer = serializerFactory.getObjectSerializer(type);
      } else {
        binarySerializer = NullSerializer.INSTANCE;
      }

      stream[startPosition] = binarySerializer.getId();
      startPosition += BinarySerializerFactory.TYPE_IDENTIFIER_SIZE;

      binarySerializer.serialize(key, serializerFactory, stream, startPosition);
      startPosition += binarySerializer.getObjectSize(serializerFactory, key);
    }

    IntegerSerializer.serializeLiteral(
        (startPosition - oldStartPosition), stream, oldStartPosition);
  }

  @Override
  @SuppressWarnings("unchecked")
  public CompositeKey deserialize(BinarySerializerFactory serializerFactory, byte[] stream,
      int startPosition) {
    final var compositeKey = new CompositeKey();

    startPosition += IntegerSerializer.INT_SIZE;

    final var keysSize = IntegerSerializer.deserializeLiteral(stream, startPosition);
    startPosition += IntegerSerializer.INSTANCE.getObjectSize(serializerFactory, stream, keysSize);

    for (var i = 0; i < keysSize; i++) {
      final var serializerId = stream[startPosition];
      startPosition += BinarySerializerFactory.TYPE_IDENTIFIER_SIZE;

      var binarySerializer =
          (BinarySerializer<Object>) serializerFactory.getObjectSerializer(serializerId);
      final var key = binarySerializer.deserialize(serializerFactory, stream, startPosition);
      compositeKey.addKey(key);

      startPosition += binarySerializer.getObjectSize(serializerFactory, key);
    }

    return compositeKey;
  }

  @Override
  public int getObjectSize(BinarySerializerFactory serializerFactory, byte[] stream,
      int startPosition) {
    return IntegerSerializer.deserializeLiteral(stream, startPosition);
  }

  @Override
  public byte getId() {
    return ID;
  }

  @Override
  public int getObjectSizeNative(BinarySerializerFactory serializerFactory, byte[] stream,
      int startPosition) {
    return IntegerSerializer.deserializeNative(stream, startPosition);
  }

  @Override
  public void serializeNativeObject(
      CompositeKey compositeKey, BinarySerializerFactory serializerFactory, byte[] stream,
      int startPosition, Object... hints) {
    final var types = getKeyTypes(hints);

    final var keys = compositeKey.getKeys();
    final var keysSize = keys.size();

    final var oldStartPosition = startPosition;

    startPosition += IntegerSerializer.INT_SIZE;

    IntegerSerializer.serializeNative(keysSize, stream, startPosition);

    startPosition += IntegerSerializer.INT_SIZE;

    for (var i = 0; i < keys.size(); i++) {
      final var key = keys.get(i);
      BinarySerializer<Object> binarySerializer;
      if (key != null) {
        final PropertyTypeInternal type;
        if (types.length > i) {
          type = types[i];
        } else {
          type = PropertyTypeInternal.getTypeByClass(key.getClass());
        }

        binarySerializer = serializerFactory.getObjectSerializer(type);
      } else {
        binarySerializer = NullSerializer.INSTANCE;
      }

      stream[startPosition] = binarySerializer.getId();
      startPosition += BinarySerializerFactory.TYPE_IDENTIFIER_SIZE;

      binarySerializer.serializeNativeObject(key, serializerFactory, stream, startPosition);
      startPosition += binarySerializer.getObjectSize(serializerFactory, key);
    }

    IntegerSerializer.serializeNative(
        (startPosition - oldStartPosition), stream, oldStartPosition);
  }

  @Override
  public CompositeKey deserializeNativeObject(BinarySerializerFactory serializerFactory,
      byte[] stream, int startPosition) {
    final var compositeKey = new CompositeKey();

    startPosition += IntegerSerializer.INT_SIZE;

    final var keysSize = IntegerSerializer.deserializeNative(stream, startPosition);
    startPosition += IntegerSerializer.INSTANCE.getObjectSize(serializerFactory, keysSize);

    for (var i = 0; i < keysSize; i++) {
      final var serializerId = stream[startPosition];
      startPosition += BinarySerializerFactory.TYPE_IDENTIFIER_SIZE;

      @SuppressWarnings("unchecked")
      var binarySerializer =
          (BinarySerializer<Object>) serializerFactory.getObjectSerializer(serializerId);
      final var key = binarySerializer.deserializeNativeObject(serializerFactory, stream,
          startPosition);
      compositeKey.addKey(key);

      startPosition += binarySerializer.getObjectSize(serializerFactory, key);
    }

    return compositeKey;
  }

  private static PropertyTypeInternal[] getKeyTypes(Object[] hints) {
    final PropertyTypeInternal[] types;

    if (hints != null && hints.length > 0) {
      types = (PropertyTypeInternal[]) hints;
    } else {
      types = CommonConst.EMPTY_TYPES_ARRAY;
    }
    return types;
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

    final var types = getKeyTypes(hints);

    final var keys = value.getKeys();
    final var compositeKey = new CompositeKey();

    for (var i = 0; i < keys.size(); i++) {
      var key = keys.get(i);

      if (key != null) {
        final PropertyTypeInternal type;
        if (types.length > i) {
          type = types[i];
        } else {
          type = PropertyTypeInternal.getTypeByClass(key.getClass());
        }

        var keySerializer = serializerFactory.getObjectSerializer(type);
        if (key instanceof Map
            && !(type == PropertyTypeInternal.EMBEDDEDMAP || type == PropertyTypeInternal.LINKMAP)
            && ((Map<?, ?>) key).size() == 1
            && ((Map<?, ?>) key)
            .keySet()
            .iterator()
            .next()
            .getClass()
            .isAssignableFrom(type.getDefaultJavaType())) {
          key = ((Map<?, ?>) key).keySet().iterator().next();
        }
        compositeKey.addKey(keySerializer.preprocess(serializerFactory, key));
      } else {
        compositeKey.addKey(key);
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
    final var types = getKeyTypes(hints);

    final var keys = object.getKeys();
    final var keysSize = keys.size();

    final var oldStartOffset = buffer.position();
    buffer.position(oldStartOffset + IntegerSerializer.INT_SIZE);

    buffer.putInt(keysSize);

    for (var i = 0; i < keys.size(); i++) {
      final var key = keys.get(i);

      BinarySerializer<Object> binarySerializer;
      if (key != null) {
        final PropertyTypeInternal type;
        if (types.length > i) {
          type = types[i];
        } else {
          type = PropertyTypeInternal.getTypeByClass(key.getClass());
        }

        binarySerializer = serializerFactory.getObjectSerializer(type);
      } else {
        binarySerializer = NullSerializer.INSTANCE;
      }

      buffer.put(binarySerializer.getId());
      binarySerializer.serializeInByteBufferObject(serializerFactory, key, buffer);
    }

    final var finalPosition = buffer.position();
    final var serializedSize = buffer.position() - oldStartOffset;

    buffer.position(oldStartOffset);
    buffer.putInt(serializedSize);

    buffer.position(finalPosition);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public CompositeKey deserializeFromByteBufferObject(BinarySerializerFactory serializerFactory,
      ByteBuffer buffer) {
    final var compositeKey = new CompositeKey();

    buffer.position(buffer.position() + IntegerSerializer.INT_SIZE);
    final var keysSize = buffer.getInt();

    for (var i = 0; i < keysSize; i++) {
      final var serializerId = buffer.get();
      @SuppressWarnings("unchecked")
      var binarySerializer =
          (BinarySerializer<Object>) serializerFactory.getObjectSerializer(serializerId);
      final var key = binarySerializer.deserializeFromByteBufferObject(serializerFactory, buffer);
      compositeKey.addKey(key);
    }

    return compositeKey;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public CompositeKey deserializeFromByteBufferObject(BinarySerializerFactory serializerFactory,
      int offset, ByteBuffer buffer) {
    final var compositeKey = new CompositeKey();

    offset += IntegerSerializer.INT_SIZE;
    final var keysSize = buffer.getInt(offset);
    offset += IntegerSerializer.INT_SIZE;

    for (var i = 0; i < keysSize; i++) {
      final var serializerId = buffer.get(offset);
      offset++;
      @SuppressWarnings("unchecked")
      var binarySerializer =
          (BinarySerializer<Object>) serializerFactory.getObjectSerializer(serializerId);

      var delta = binarySerializer.getObjectSizeInByteBuffer(serializerFactory, offset, buffer);
      final var key = binarySerializer.deserializeFromByteBufferObject(serializerFactory, offset,
          buffer);
      offset += delta;

      compositeKey.addKey(key);
    }

    return compositeKey;
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
    final var compositeKey = new CompositeKey();

    offset += IntegerSerializer.INT_SIZE;

    final var keysSize = walChanges.getIntValue(buffer, offset);
    offset += IntegerSerializer.INT_SIZE;

    for (var i = 0; i < keysSize; i++) {
      final var serializerId = walChanges.getByteValue(buffer, offset);
      offset += BinarySerializerFactory.TYPE_IDENTIFIER_SIZE;

      @SuppressWarnings("unchecked")
      var binarySerializer =
          (BinarySerializer<Object>) serializerFactory.getObjectSerializer(serializerId);
      final var key =
          binarySerializer.deserializeFromByteBufferObject(serializerFactory, buffer, walChanges,
              offset);
      compositeKey.addKey(key);

      offset += binarySerializer.getObjectSize(serializerFactory, key);
    }

    return compositeKey;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getObjectSizeInByteBuffer(ByteBuffer buffer, WALChanges walChanges, int offset) {
    return walChanges.getIntValue(buffer, offset);
  }
}
