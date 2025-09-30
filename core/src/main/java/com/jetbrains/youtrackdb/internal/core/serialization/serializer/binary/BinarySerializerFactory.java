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

package com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary;

import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.common.serialization.types.BinarySerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.BinaryTypeSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.BooleanSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.ByteSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.CharSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.DateSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.DateTimeSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.DecimalSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.DoubleSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.FloatSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.IntegerSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.LongSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.NullSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.ShortSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.StringSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.UTF8Serializer;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.exception.StorageException;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.impl.CompactedLinkSerializer;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.impl.LinkSerializer;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.impl.index.CompositeKeySerializer;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.stream.StreamSerializerRID;
import it.unimi.dsi.fastutil.bytes.Byte2ObjectArrayMap;
import java.util.EnumMap;
import javax.annotation.Nonnull;

/**
 * This class is responsible for obtaining OBinarySerializer realization, by it's id of type of
 * object that should be serialized.
 */
public class BinarySerializerFactory {

  /**
   * Size of the type identifier block size
   */
  public static final int TYPE_IDENTIFIER_SIZE = 1;

  public static final byte CURRENT_BINARY_FORMAT_VERSION = 13;

  private final Byte2ObjectArrayMap<BinarySerializer<?>> serializerIdMap = new Byte2ObjectArrayMap<>();
  private final Byte2ObjectArrayMap<Class<? extends BinarySerializer>> serializerClassesIdMap =
      new Byte2ObjectArrayMap<>();
  private final EnumMap<PropertyTypeInternal, BinarySerializer<?>> serializerTypeMap = new EnumMap<>(
      PropertyTypeInternal.class);


  private BinarySerializerFactory() {
  }

  public static byte currentBinaryFormatVersion() {
    return CURRENT_BINARY_FORMAT_VERSION;
  }

  public static BinarySerializerFactory create(int binaryFormatVersion) {
    if (binaryFormatVersion != CURRENT_BINARY_FORMAT_VERSION) {
      throw new StorageException(null,
          "Binary format version " + binaryFormatVersion + " is not supported");
    }

    final var factory = new BinarySerializerFactory();

    // STATELESS SERIALIER
    factory.registerSerializer(new NullSerializer(), null);

    factory.registerSerializer(BooleanSerializer.INSTANCE, PropertyTypeInternal.BOOLEAN);
    factory.registerSerializer(IntegerSerializer.INSTANCE, PropertyTypeInternal.INTEGER);
    factory.registerSerializer(ShortSerializer.INSTANCE, PropertyTypeInternal.SHORT);
    factory.registerSerializer(LongSerializer.INSTANCE, PropertyTypeInternal.LONG);
    factory.registerSerializer(FloatSerializer.INSTANCE, PropertyTypeInternal.FLOAT);
    factory.registerSerializer(DoubleSerializer.INSTANCE, PropertyTypeInternal.DOUBLE);
    factory.registerSerializer(DateTimeSerializer.INSTANCE, PropertyTypeInternal.DATETIME);
    factory.registerSerializer(CharSerializer.INSTANCE, null);
    factory.registerSerializer(StringSerializer.INSTANCE, PropertyTypeInternal.STRING);

    factory.registerSerializer(ByteSerializer.INSTANCE, PropertyTypeInternal.BYTE);
    factory.registerSerializer(DateSerializer.INSTANCE, PropertyTypeInternal.DATE);
    factory.registerSerializer(LinkSerializer.INSTANCE, PropertyTypeInternal.LINK);
    factory.registerSerializer(CompositeKeySerializer.INSTANCE, null);
    factory.registerSerializer(StreamSerializerRID.INSTANCE, null);
    factory.registerSerializer(BinaryTypeSerializer.INSTANCE, PropertyTypeInternal.BINARY);
    factory.registerSerializer(DecimalSerializer.INSTANCE, PropertyTypeInternal.DECIMAL);


    factory.registerSerializer(CompactedLinkSerializer.INSTANCE, null);
    factory.registerSerializer(UTF8Serializer.INSTANCE, null);

    //used for spatial indexes
    factory.registerSerializer(MockSerializer.INSTANCE, PropertyTypeInternal.EMBEDDED);
    return factory;
  }

  public static BinarySerializerFactory getInstance(@Nonnull DatabaseSessionInternal session) {
    return session.getSerializerFactory();
  }

  public void registerSerializer(final BinarySerializer<?> iInstance,
      final PropertyTypeInternal iType) {
    if (serializerIdMap.containsKey(iInstance.getId())) {
      throw new IllegalArgumentException(
          "Binary serializer with id " + iInstance.getId() + " has been already registered.");
    }

    serializerIdMap.put(iInstance.getId(), iInstance);
    if (iType != null) {
      serializerTypeMap.put(iType, iInstance);
    }
  }

  @SuppressWarnings({"rawtypes"})
  public void registerSerializer(final byte iId,
      final Class<? extends BinarySerializer> iClass) {
    if (serializerClassesIdMap.containsKey(iId)) {
      throw new IllegalStateException(
          "Serializer with id " + iId + " has been already registered.");
    }

    serializerClassesIdMap.put(iId, iClass);
  }

  /**
   * Obtain OBinarySerializer instance by it's id.
   *
   * @param identifier is serializes identifier.
   * @return OBinarySerializer instance.
   */
  public BinarySerializer<?> getObjectSerializer(final byte identifier) {
    var impl = serializerIdMap.get(identifier);
    if (impl == null) {
      final var cls = serializerClassesIdMap.get(identifier);
      if (cls != null) {
        try {
          impl = cls.newInstance();
        } catch (Exception e) {
          LogManager.instance()
              .error(
                  this,
                  "Cannot create an instance of class %s invoking the empty constructor",
                  e,
                  cls);
        }
      }
    }
    return impl;
  }

  /**
   * Obtain OBinarySerializer realization for the PropertyType
   *
   * @param type is the PropertyType to obtain serializer algorithm for
   * @return OBinarySerializer instance
   */
  @SuppressWarnings("unchecked")
  public <T> BinarySerializer<T> getObjectSerializer(final PropertyTypeInternal type) {
    return (BinarySerializer<T>) serializerTypeMap.get(type);
  }
}
