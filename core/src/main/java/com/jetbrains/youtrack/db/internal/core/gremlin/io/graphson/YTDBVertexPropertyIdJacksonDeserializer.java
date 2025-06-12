package com.jetbrains.youtrack.db.internal.core.gremlin.io.graphson;

import com.jetbrains.youtrack.db.api.gremlin.YTDBVertexPropertyId;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
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
    var parts = value.split(":");
    var collectionId = Integer.parseInt(parts[0]);
    var collectionPosition = Long.parseLong(parts[1]);
    var key = parts[2];

    return new YTDBVertexPropertyId(new RecordId(collectionId, collectionPosition), key);
  }
}
