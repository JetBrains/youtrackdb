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

package com.jetbrains.youtrack.db.internal.common.serialization.types;

import com.jetbrains.youtrack.db.internal.core.serialization.serializer.binary.BinarySerializerFactory;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.wal.WALChanges;
import java.nio.ByteBuffer;

/**
 * This interface is used for serializing YouTrackDB datatypes in binary format. Serialized content
 * is written into buffer that will contain not only given object presentation but all binary
 * content. Such approach prevents creation of separate byte array for each object and decreased GC
 * overhead.
 */
public interface BinarySerializer<T> {

  /**
   * Obtain size of the serialized object Size is the amount of bites that required for storing
   * object (for example: for storing integer we need 4 bytes)
   *
   * @param serializerFactory
   * @param object            is the object to measure its size
   * @param hints             List of parameters which may be used to choose appropriate
   *                          serialization approach.
   * @return size of the serialized object
   */
  int getObjectSize(BinarySerializerFactory serializerFactory, T object, Object... hints);

  /**
   * Return size serialized presentation of given object.
   *
   * @param serializerFactory
   * @param stream            Serialized content.
   * @param startPosition     Position from which serialized presentation of given object is
   *                          stored.
   * @return Size serialized presentation of given object in bytes.
   */
  int getObjectSize(BinarySerializerFactory serializerFactory, byte[] stream, int startPosition);

  /**
   * Writes object to the stream starting from the startPosition
   *
   * @param object            is the object to serialize
   * @param serializerFactory
   * @param stream            is the stream where object will be written
   * @param hints             List of parameters which may be used to choose appropriate
   *                          serialization approach.
   */
  void serialize(T object, BinarySerializerFactory serializerFactory, byte[] stream,
      int startPosition, Object... hints);

  /**
   * Reads object from the stream starting from the startPosition
   *
   * @param serializerFactory
   * @param stream            is the stream from object will be read
   * @param startPosition     is the position to start reading from
   * @return instance of the deserialized object
   */
  T deserialize(BinarySerializerFactory serializerFactory, byte[] stream, int startPosition);

  /**
   * @return Identifier of given serializer.
   */
  byte getId();

  /**
   * @return <code>true</code> if binary presentation of object always has the same length.
   */
  boolean isFixedLength();

  /**
   * @return Length of serialized data if {@link #isFixedLength()} method returns <code>true</code>.
   * If {@link #isFixedLength()} method return <code>false</code> returned value is undefined.
   */
  int getFixedLength();

  /**
   * Writes object to the stream starting from the startPosition using native acceleration.
   * Serialized object presentation is platform dependant.
   *
   * @param object            is the object to serialize
   * @param serializerFactory
   * @param stream            is the stream where object will be written
   * @param hints             List of parameters which may be used to choose appropriate
   *                          serialization approach.
   */
  void serializeNativeObject(T object, BinarySerializerFactory serializerFactory, byte[] stream,
      int startPosition, Object... hints);

  /**
   * Reads object from the stream starting from the startPosition, in case there were serialized
   * using {@link #serializeNativeObject(Object, BinarySerializerFactory, byte[], int, Object...)}
   * method.
   *
   * @param serializerFactory
   * @param stream            is the stream from object will be read
   * @param startPosition     is the position to start reading from
   * @return instance of the deserialized object
   */
  T deserializeNativeObject(BinarySerializerFactory serializerFactory, byte[] stream,
      int startPosition);

  /**
   * Return size serialized presentation of given object, if it was serialized using
   * {@link #serializeNativeObject(Object, BinarySerializerFactory, byte[], int, Object...)}
   * method.
   *
   * @param serializerFactory
   * @param stream            Serialized content.
   * @param startPosition     Position from which serialized presentation of given object is
   *                          stored.
   * @return Size serialized presentation of given object in bytes.
   */
  int getObjectSizeNative(BinarySerializerFactory serializerFactory, byte[] stream,
      int startPosition);

  T preprocess(BinarySerializerFactory serializerFactory, T value, Object... hints);

  /**
   * Serializes binary presentation of object to {@link ByteBuffer}. Position of buffer should be
   * set before calling of given method. Serialization result is compatible with result of call of
   * {@link #serializeNativeObject(Object, BinarySerializerFactory, byte[], int, Object...)} method.
   * So if we call: <code> buffer.position(10); binarySerializer.serializeInByteBufferObject(object,
   * buffer);
   * </code> and then <code>
   * byte[] stream = new byte[serializedSize + 10]; buffer.position(10); buffer.get(stream);
   * </code> following assert should pass <code>
   * assert object.equals(binarySerializer.deserializeNativeObject(stream, 10))
   * </code> Final position of <code>ByteBuffer</code> will be changed and will be equal to sum of
   * buffer start position and value returned by method
   * {@link #getObjectSize(BinarySerializerFactory, Object, Object...)}
   *
   * @param serializerFactory
   * @param object            Object to serialize.
   * @param buffer            Buffer which will contain serialized presentation of buffer.
   * @param hints             Type (types in case of composite object) of object.
   */
  void serializeInByteBufferObject(BinarySerializerFactory serializerFactory, T object,
      ByteBuffer buffer, Object... hints);

  /**
   * Converts binary presentation of object to object instance. Position of buffer should be set
   * before call of this method. Binary format of method is expected to be the same as binary format
   * of {@link #serializeNativeObject(Object, BinarySerializerFactory, byte[], int, Object...)} So
   * if we call <code> byte[] stream = new byte[serializedSize];
   * binarySerializer.serializeNativeObject(object, stream, 0);
   * </code> following assert should pass <code>
   * byteBuffer.position(10); byteBuffer.put(stream); byteBuffer.position(10); assert
   * object.equals(binarySerializer.deserializeFromByteBufferObject(buffer))
   * </code> Final position of <code>ByteBuffer</code> will be changed and will be equal to sum of
   * buffer start position and value returned by method
   * {@link #getObjectSize(BinarySerializerFactory, Object, Object...)}
   *
   * @param serializerFactory
   * @param buffer            Buffer which contains serialized presentation of object
   * @return Instance of object serialized in buffer.
   */
  T deserializeFromByteBufferObject(BinarySerializerFactory serializerFactory, ByteBuffer buffer);

  /**
   * Converts binary presentation of object to object instance. Binary format of method is expected
   * to be the same as binary format of
   * {@link #serializeNativeObject(Object, BinarySerializerFactory, byte[], int, Object...)}. So if
   * we call <code> byte[] stream = new byte[serializedSize];
   * binarySerializer.serializeNativeObject(object, stream, 0);
   * </code> following assert should pass <code>
   * byteBuffer.position(10); byteBuffer.put(stream); byteBuffer.position(10); assert
   * object.equals(binarySerializer.deserializeFromByteBufferObject(buffer, 10))
   * </code> Final position of <code>ByteBuffer</code> will NOT be changed during the call.
   *
   * @param serializerFactory
   * @param offset            offset inside the <code>ByteBuffer</code> from which deserialization
   *                          should be started.
   * @param buffer            Buffer which contains serialized presentation of object
   * @return Instance of object serialized in buffer.
   */
  T deserializeFromByteBufferObject(BinarySerializerFactory serializerFactory, int offset,
      ByteBuffer buffer);

  /**
   * Returns amount of bytes which is consumed by object which is already serialized in buffer.
   * Position of buffer should be set before call of this method. Result of call should be the same
   * as result of call of {@link #getObjectSize(BinarySerializerFactory, Object, Object...)} on
   * deserialized object.
   *
   * @param serializerFactory
   * @param buffer            Buffer which contains serialized version of object
   * @return Size of serialized object.
   */
  int getObjectSizeInByteBuffer(BinarySerializerFactory serializerFactory, ByteBuffer buffer);

  /**
   * Returns amount of bytes which is consumed by object which is already serialized in buffer.
   * Result of call should be the same as result of call of
   * {@link #getObjectSize(BinarySerializerFactory, Object, Object...)} on deserialized object.
   *
   * <p>Position of <code>ByteBuffer</code> is not changed after result of the call.
   *
   * @param serializerFactory
   * @param offset            offset inside the buffer since which object is serialized.
   * @param buffer            Buffer which contains serialized version of object
   * @return Size of serialized object.
   */
  int getObjectSizeInByteBuffer(BinarySerializerFactory serializerFactory, int offset,
      ByteBuffer buffer);

  /**
   * Converts binary presentation of object to object instance taking in account changes which are
   * done inside of atomic operation {@link AtomicOperation}. Binary format of method is expected to
   * be the same as binary format of method
   * {@link #serializeNativeObject(Object, BinarySerializerFactory, byte[], int, Object...)}. So if
   * we call: <code> byte[] stream = new byte[serializedSize];
   * binarySerializer.serializeNativeObject(object, stream, 0); walChanges.setBinaryValue(buffer,
   * stream, 10);
   * </code> Then following assert should pass <code>
   * assert object.equals(binarySerializer.deserializeFromByteBufferObject(buffer, walChanges,
   * 10));
   * </code>
   *
   * <p><code>ByteBuffer</code> position should <b>NOT</b> be changed during call of this method.
   *
   * @param serializerFactory
   * @param buffer            Buffer which will contain serialized changes.
   * @param walChanges        Changes are done during atomic operation.
   * @param offset            Offset of binary presentation of object inside of byte buffer/atomic
   *                          operations changes.
   * @return Instance of object serialized in buffer.
   */
  T deserializeFromByteBufferObject(BinarySerializerFactory serializerFactory, ByteBuffer buffer,
      WALChanges walChanges, int offset);

  /**
   * Returns amount of bytes which is consumed by object which is already serialized in buffer
   * taking in account changes which are done inside of atomic operation {@link AtomicOperation}.
   * Result of call should be the same as result of call of
   * {@link #getObjectSize(BinarySerializerFactory, Object, Object...)} on deserialized object.
   *
   * <p><code>ByteBuffer</code> position should <b>NOT</b> be changed during call of this method.
   *
   * @param buffer     Buffer which will contain serialized changes.
   * @param walChanges Changes are done during atomic operation.
   * @param offset     Offset of binary presentation of object inside of byte buffer/atomic
   *                   operations changes.
   * @return Size of serialized object.
   */
  int getObjectSizeInByteBuffer(ByteBuffer buffer, WALChanges walChanges, int offset);

  default byte[] serializeNativeAsWhole(BinarySerializerFactory serializerFactory, T object,
      Object... hints) {
    final var result = new byte[getObjectSize(serializerFactory, object, hints)];
    serializeNativeObject(object, serializerFactory, result, 0, hints);
    return result;
  }
}
