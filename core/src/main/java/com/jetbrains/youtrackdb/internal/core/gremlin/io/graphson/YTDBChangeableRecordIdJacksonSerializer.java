package com.jetbrains.youtrackdb.internal.core.gremlin.io.graphson;

import com.jetbrains.youtrackdb.internal.core.id.ChangeableRecordId;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import java.io.IOException;
import org.apache.tinkerpop.shaded.jackson.core.JacksonException;
import org.apache.tinkerpop.shaded.jackson.core.JsonParser;
import org.apache.tinkerpop.shaded.jackson.databind.DeserializationContext;
import org.apache.tinkerpop.shaded.jackson.databind.deser.std.StdDeserializer;

public class YTDBChangeableRecordIdJacksonSerializer extends StdDeserializer<ChangeableRecordId> {

  protected YTDBChangeableRecordIdJacksonSerializer() {
    super(ChangeableRecordId.class);
  }

  @Override
  public ChangeableRecordId deserialize(JsonParser jsonParser,
      DeserializationContext deserializationContext) throws IOException {
    var strRid = deserializationContext.readValue(jsonParser, String.class);

    var result = RecordIdInternal.fromString(strRid, true);
    if (result instanceof ChangeableRecordId changeableRecordId) {
      return changeableRecordId;
    }

    throw new IllegalStateException("Record id is not new: " + strRid);
  }
}
