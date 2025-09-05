package com.jetbrains.youtrackdb.internal.core.gremlin.io.graphson;

import com.jetbrains.youtrackdb.api.record.RID;
import com.jetbrains.youtrackdb.internal.core.id.ChangeableRecordId;
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
    var strRid = deserializationContext.readValue(jsonParser, String.class);
    return ChangeableRecordId.deserialize(strRid);
  }
}
