package com.jetbrains.youtrackdb.internal.core.gremlin.io.binary;

import com.jetbrains.youtrackdb.api.gremlin.YTDBVertexPropertyId;
import com.jetbrains.youtrackdb.internal.core.id.ChangeableRecordId;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.apache.tinkerpop.gremlin.structure.io.Buffer;
import org.apache.tinkerpop.gremlin.structure.io.binary.GraphBinaryReader;
import org.apache.tinkerpop.gremlin.structure.io.binary.GraphBinaryWriter;

public class YTDBVertexPropertyIdBinarySerializer extends
    YTDBAbstractCustomTypeSerializer<YTDBVertexPropertyId> {

  public static final YTDBVertexPropertyIdBinarySerializer INSTANCE =
      new YTDBVertexPropertyIdBinarySerializer();

  private static final String PROPERTY_BYTE_KEY = "key";

  public YTDBVertexPropertyIdBinarySerializer() {
    super(false);
  }

  @Override
  protected YTDBVertexPropertyId doReadValue(Buffer buffer, GraphBinaryReader context) {
    var collectionId = buffer.readShort();
    var collectionPosition = buffer.readLong();

    var byteKyLength = buffer.readShort();
    var byteKey = new byte[byteKyLength];
    buffer.readBytes(byteKey);

    var key = new String(byteKey, StandardCharsets.UTF_8);

    if (collectionPosition < 0) {
      return new YTDBVertexPropertyId(new ChangeableRecordId(collectionId, collectionPosition),
          key);
    }

    return new YTDBVertexPropertyId(new RecordId(collectionId, collectionPosition), key);
  }

  @Override
  protected Map<String, Object> initWriteContextMap(YTDBVertexPropertyId value) {
    var key = value.key();
    var byteKey = key.getBytes(StandardCharsets.UTF_8);

    return Map.of(PROPERTY_BYTE_KEY, byteKey);
  }

  @Override
  protected int getSerializedLength(YTDBVertexPropertyId value, Map<String, Object> context) {
    var byteKey = (byte[]) context.get(PROPERTY_BYTE_KEY);
    return byteKey.length + 2 * Short.BYTES + Long.BYTES;
  }

  @Override
  protected void doWriteValue(YTDBVertexPropertyId value, Buffer buffer, GraphBinaryWriter context,
      Map<String, Object> contextMap) {
    var rid = value.rid();
    var byteKey = (byte[]) contextMap.get(PROPERTY_BYTE_KEY);

    buffer.writeShort(rid.getCollectionId());
    buffer.writeLong(rid.getCollectionPosition());

    buffer.writeShort(byteKey.length);
    buffer.writeBytes(byteKey);
  }

  @Override
  public String getTypeName() {
    return "ytdb.VertexPropertyId";
  }
}
