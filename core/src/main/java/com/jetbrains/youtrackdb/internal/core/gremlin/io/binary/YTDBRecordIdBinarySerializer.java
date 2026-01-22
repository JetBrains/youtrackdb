package com.jetbrains.youtrackdb.internal.core.gremlin.io.binary;

import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import java.util.Map;
import org.apache.tinkerpop.gremlin.structure.io.Buffer;
import org.apache.tinkerpop.gremlin.structure.io.binary.GraphBinaryReader;
import org.apache.tinkerpop.gremlin.structure.io.binary.GraphBinaryWriter;

public final class YTDBRecordIdBinarySerializer extends YTDBAbstractCustomTypeSerializer<RecordId> {

  public static final YTDBRecordIdBinarySerializer INSTANCE = new YTDBRecordIdBinarySerializer();

  public YTDBRecordIdBinarySerializer() {
    super(false);
  }

  @Override
  protected RecordId doReadValue(Buffer buffer, GraphBinaryReader context) {
    return new RecordId(buffer.readShort(), buffer.readLong());
  }

  @Override
  protected int getSerializedLength(RecordId value, Map<String, Object> context) {
    return Short.BYTES + Long.BYTES;
  }

  @Override
  protected void doWriteValue(RecordId value, Buffer buffer, GraphBinaryWriter context,
      Map<String, Object> contextMap) {
    buffer.writeShort(value.getCollectionId());
    buffer.writeLong(value.getCollectionPosition());
  }

  @Override
  public String getTypeName() {
    return "ytdb.RecordId";
  }
}
