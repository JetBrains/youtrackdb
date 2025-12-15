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
package com.jetbrains.youtrackdb.internal.core.serialization;

import com.jetbrains.youtrackdb.internal.core.exception.BaseException;
import com.jetbrains.youtrackdb.internal.core.exception.SerializationException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.io.InvalidClassException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;
import java.io.Serializable;
import javax.annotation.Nullable;

/**
 * Helper class to serialize Streamable objects.
 */
public class StreamableHelper {

  static final byte NULL = 0;
  static final byte STREAMABLE = 1;
  static final byte SERIALIZABLE = 2;

  static final byte STRING = 10;
  static final byte INTEGER = 11;
  static final byte SHORT = 12;
  static final byte LONG = 13;
  static final byte BOOLEAN = 14;

  private static ClassLoader streamableClassLoader;


  /**
   * Hook for validating classes during deserialization.
   * Default implementation allows all classes to preserve
   * existing behavior.
   */
  protected static boolean isDeserializableClassAllowed(String className) {
    return true;
  }

  /**
   * Set the preferred {@link ClassLoader} used to load streamable types.
   */
  public static void setStreamableClassLoader(
      /* @Nullable */ final ClassLoader streamableClassLoader) {
    StreamableHelper.streamableClassLoader = streamableClassLoader;
  }

  public static void toStream(final DataOutput out, final Object object) throws IOException {
    switch (object) {
      case null -> out.writeByte(NULL);
      case Streamable streamable -> {
        out.writeByte(STREAMABLE);
        out.writeUTF(object.getClass().getName());
        streamable.toStream(out);
      }
      case String s -> {
        out.writeByte(STRING);
        out.writeUTF(s);
      }
      case Integer i -> {
        out.writeByte(INTEGER);
        out.writeInt(i);
      }
      case Short i -> {
        out.writeByte(SHORT);
        out.writeShort(i);
      }
      case Long l -> {
        out.writeByte(LONG);
        out.writeLong(l);
      }
      case Boolean b -> {
        out.writeByte(BOOLEAN);
        out.writeBoolean(b);
      }
      case Serializable serializable -> {
        out.writeByte(SERIALIZABLE);
        final var mem = new ByteArrayOutputStream();
        try (mem; var oos = new ObjectOutputStream(mem)) {
          oos.writeObject(object);
          oos.flush();
          final var buffer = mem.toByteArray();
          out.writeInt(buffer.length);
          out.write(buffer);
        }
      }
      default -> throw new SerializationException("Object not supported: " + object);
    }
  }

  @Nullable
  public static Object fromStream(final DataInput in) throws IOException {
    Object object = null;

    final var objectType = in.readByte();
    switch (objectType) {
      case NULL:
        return null;
      case STREAMABLE:
        final var payloadClassName = in.readUTF();
        try {
          if (streamableClassLoader != null) {
            object = streamableClassLoader.loadClass(payloadClassName).newInstance();
          } else {
            object = Class.forName(payloadClassName).newInstance();
          }
          ((Streamable) object).fromStream(in);
        } catch (Exception e) {
          throw BaseException.wrapException(
              new SerializationException("Cannot unmarshall object from distributed request"), e,
              (String) null);
        }
        break;
      case SERIALIZABLE:
        final var buffer = new byte[in.readInt()];
        in.readFully(buffer);
        final var mem = new ByteArrayInputStream(buffer);
        final ObjectInputStream ois;
        if (streamableClassLoader != null) {
          ois =
              new ObjectInputStream(mem) {
                @Override
                protected Class<?> resolveClass(ObjectStreamClass desc)
                    throws IOException, ClassNotFoundException {
                  final String className = desc.getName();
                  if (!isDeserializableClassAllowed(className)) {
                    throw new InvalidClassException(
                        "Class not allowed for deserialization", className);
                  }
                  return streamableClassLoader.loadClass(className);
                }
              };
        } else {
          ois = new ObjectInputStream(mem);
        }
        try {
          try {
            object = ois.readObject();
          } catch (ClassNotFoundException e) {
            throw BaseException.wrapException(
                new SerializationException("Cannot unmarshall object from distributed request"),
                e, (String) null);
          }
        } finally {
          ois.close();
          mem.close();
        }
        break;
      case STRING:
        return in.readUTF();
      case INTEGER:
        return in.readInt();
      case SHORT:
        return in.readShort();
      case LONG:
        return in.readLong();
      case BOOLEAN:
        return in.readBoolean();
      default:
        throw new SerializationException("Object type not supported: " + objectType);
    }
    return object;
  }
}
