package com.jetbrains.youtrackdb.internal.core.gremlin.io.graphson;

import com.jetbrains.youtrackdb.api.gremlin.YTDBVertexPropertyId;
import com.jetbrains.youtrackdb.internal.core.id.ChangeableRecordId;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import java.io.IOException;
import org.apache.tinkerpop.shaded.jackson.core.JsonParser;
import org.apache.tinkerpop.shaded.jackson.databind.DeserializationContext;
import org.apache.tinkerpop.shaded.jackson.databind.deser.std.StdDeserializer;

public class YTDBVertexPropertyIdJacksonDeserializer extends StdDeserializer<YTDBVertexPropertyId> {

  public YTDBVertexPropertyIdJacksonDeserializer() {
    super(YTDBVertexPropertyId.class);
  }

  @Override
  public YTDBVertexPropertyId deserialize(JsonParser jsonParser,
      DeserializationContext deserializationContext) throws IOException {
    var value = deserializationContext.readValue(jsonParser, String.class);
    var parts = value.split(":", -1);
    var collectionId = Integer.parseInt(parts[0]);
    var collectionPosition = Long.parseLong(parts[1]);
    var key = parts[2];

    if (collectionPosition < 0) {
      return new YTDBVertexPropertyId(new ChangeableRecordId(collectionId, collectionPosition),
          key);
    }

    return new YTDBVertexPropertyId(new RecordId(collectionId, collectionPosition), key);
  }
}
