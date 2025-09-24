package com.jetbrains.youtrackdb.internal.core.gremlin.io.gryo;

import com.jetbrains.youtrackdb.api.gremlin.YTDBVertexPropertyId;
import com.jetbrains.youtrackdb.internal.core.id.ChangeableRecordId;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import org.apache.tinkerpop.shaded.kryo.Kryo;
import org.apache.tinkerpop.shaded.kryo.Serializer;
import org.apache.tinkerpop.shaded.kryo.io.Input;
import org.apache.tinkerpop.shaded.kryo.io.Output;


public class YTDBVertexPropertyIdGyroSerializer extends Serializer<YTDBVertexPropertyId> {

  public static final YTDBVertexPropertyIdGyroSerializer INSTANCE =
      new YTDBVertexPropertyIdGyroSerializer();

  @Override
  public void write(Kryo kryo, Output output, YTDBVertexPropertyId ytdbVertexPropertyId) {
    var rid = ytdbVertexPropertyId.rid();
    output.writeString(String.format("%d:%d:%s", rid.getCollectionId(),
        rid.getCollectionPosition(), ytdbVertexPropertyId.key()));
  }

  @Override
  public YTDBVertexPropertyId read(Kryo kryo, Input input, Class<YTDBVertexPropertyId> aClass) {
    var value = input.readString();
    var parts = value.split(":");
    var collectionId = Integer.parseInt(parts[0]);
    var collectionPosition = Long.parseLong(parts[1]);
    var key = parts[2];

    RecordIdInternal rid;
    if (collectionPosition < 0) {
      rid = new ChangeableRecordId(collectionId, collectionPosition);
    } else {
      rid = new RecordId(collectionId, collectionPosition);
    }

    return new YTDBVertexPropertyId(rid, key);
  }
}
