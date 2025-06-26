package com.jetbrains.youtrack.db.internal.core.gremlin.io.graphson;

import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import java.io.IOException;
import org.apache.tinkerpop.shaded.jackson.core.JsonParser;
import org.apache.tinkerpop.shaded.jackson.databind.DeserializationContext;
import org.apache.tinkerpop.shaded.jackson.databind.deser.std.StdDeserializer;

public class YTDBRecordIdJacksonDeserializer extends StdDeserializer<RID> {

  protected YTDBRecordIdJacksonDeserializer() {
    super(RID.class);
  }

  @Override
  public RID deserialize(JsonParser jsonParser, DeserializationContext deserializationContext)
      throws IOException {
    var rid = deserializationContext.readValue(jsonParser, String.class);
    return new RecordId(rid);
  }
}
