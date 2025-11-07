package com.jetbrains.youtrackdb.internal.core.gremlin.io.binary;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;
import org.apache.tinkerpop.gremlin.structure.io.Buffer;
import org.apache.tinkerpop.gremlin.structure.io.binary.DataType;
import org.apache.tinkerpop.gremlin.structure.io.binary.GraphBinaryReader;
import org.apache.tinkerpop.gremlin.structure.io.binary.GraphBinaryWriter;
import org.apache.tinkerpop.gremlin.structure.io.binary.types.CustomTypeSerializer;
import org.apache.tinkerpop.gremlin.util.ser.SerializationException;

public abstract class YTDBAbstractCustomTypeSerializer<T> implements CustomTypeSerializer<T> {

  private static final byte[] typeInfoBuffer = new byte[]{0, 0, 0, 0};
  private final boolean nullable;

  protected YTDBAbstractCustomTypeSerializer(final boolean nullable) {
    this.nullable = nullable;
  }

  @Override
  public DataType getDataType() {
    return DataType.CUSTOM;
  }

  @Override
  public T read(Buffer buffer, GraphBinaryReader context) throws IOException {
    // No custom_type_info
    if (buffer.readInt() != 0) {
      throw new SerializationException(
          "{custom_type_info} should not be provided for " + getTypeName());
    }

    return readValue(buffer, context, nullable);
  }

  @Override
  @Nullable
  public T readValue(Buffer buffer, GraphBinaryReader context, boolean nullable)
      throws IOException {
    if (nullable) {
      final var valueFlag = buffer.readByte();
      if ((valueFlag & 1) == 1) {
        return null;
      }
    }

    // Read the byte length of the value bytes
    final var valueLength = buffer.readInt();

    if (valueLength <= 0) {
      throw new SerializationException(String.format("Unexpected value length: %d", valueLength));
    }

    if (valueLength > buffer.readableBytes()) {
      throw new SerializationException(
          String.format("Not enough readable bytes: %d (expected %d)", valueLength,
              buffer.readableBytes()));
    }

    return doReadValue(buffer, context);
  }

  protected abstract T doReadValue(Buffer buffer, GraphBinaryReader context) throws IOException;

  protected abstract int getSerializedLength(T value, Map<String, Object> context);

  @Override
  public void write(T value, Buffer buffer, GraphBinaryWriter context) throws IOException {
    buffer.writeBytes(typeInfoBuffer);

    writeValue(value, buffer, context, nullable);
  }

  @Override
  public void writeValue(T value, Buffer buffer, GraphBinaryWriter context, boolean nullable)
      throws IOException {
    if (value == null) {
      if (!nullable) {
        throw new SerializationException("Unexpected null value when nullable is false");
      }

      context.writeValueFlagNull(buffer);
      return;
    }

    if (nullable) {
      context.writeValueFlagNone(buffer);
    }

    var contextMap = new HashMap<String, Object>();
    buffer.writeInt(getSerializedLength(value, contextMap));

    doWriteValue(value, buffer, context, contextMap);
  }

  protected abstract void doWriteValue(T value, Buffer buffer, GraphBinaryWriter context,
      Map<String, Object> contextMap)
      throws IOException;
}
