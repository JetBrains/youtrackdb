package com.jetbrains.youtrackdb.internal.core.gremlin.io.graphson;


import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import java.io.IOException;
import org.apache.tinkerpop.shaded.jackson.core.JsonParser;
import org.apache.tinkerpop.shaded.jackson.databind.DeserializationContext;
import org.apache.tinkerpop.shaded.jackson.databind.deser.std.StdDeserializer;

public class YTDBImmutableRecordIdJacksonDeserializer extends StdDeserializer<RecordId> {

  protected YTDBImmutableRecordIdJacksonDeserializer() {
    super(RecordId.class);
  }

  @Override
  public RecordId deserialize(JsonParser jsonParser,
      DeserializationContext deserializationContext)
      throws IOException {
    var strRid = deserializationContext.readValue(jsonParser, String.class);

    var result = RecordIdInternal.fromString(strRid, true);
    if (result instanceof RecordId recordId) {
      return recordId;
    }

    throw new IllegalStateException("Record id is not persistent: " + strRid);
  }
}
