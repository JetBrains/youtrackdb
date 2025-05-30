package com.jetbrain.youtrack.db.gremlin.internal.io.gryo;

import com.jetbrain.youtrack.db.gremlin.api.YTDBVertexPropertyId;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
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

    return new YTDBVertexPropertyId(new RecordId(collectionId, collectionPosition), key);
  }
}
