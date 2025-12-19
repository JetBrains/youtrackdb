package com.jetbrains.youtrackdb.internal.core.gremlin.io.binary;

import com.jetbrains.youtrackdb.internal.core.id.ChangeableRecordId;
import java.util.Map;
import org.apache.tinkerpop.gremlin.structure.io.Buffer;
import org.apache.tinkerpop.gremlin.structure.io.binary.GraphBinaryReader;
import org.apache.tinkerpop.gremlin.structure.io.binary.GraphBinaryWriter;

public class YTDBChangeableRecordIdBinarySerializer extends
    YTDBAbstractCustomTypeSerializer<ChangeableRecordId> {

  public static final YTDBChangeableRecordIdBinarySerializer INSTANCE = new YTDBChangeableRecordIdBinarySerializer();

  public YTDBChangeableRecordIdBinarySerializer() {
    super(false);
  }

  @Override
  protected ChangeableRecordId doReadValue(Buffer buffer, GraphBinaryReader context) {
    return new ChangeableRecordId(buffer.readShort(), buffer.readLong());
  }

  @Override
  protected int getSerializedLength(ChangeableRecordId value, Map<String, Object> context) {
    return Short.BYTES + Long.BYTES;
  }

  @Override
  protected void doWriteValue(ChangeableRecordId value, Buffer buffer, GraphBinaryWriter context,
      Map<String, Object> contextMap) {
    buffer.writeShort(value.getCollectionId());
    buffer.writeLong(value.getCollectionPosition());
  }

  @Override
  public String getTypeName() {
    return "ytdb.ChangeableRecordId";
  }
}
