package com.jetbrain.youtrack.db.gremlin.internal.io.graphson;

import com.jetbrains.youtrack.db.api.record.RID;
import java.io.IOException;
import com.jetbrain.youtrack.db.gremlin.internal.io.YTDBIoRegistry;
import org.apache.tinkerpop.shaded.jackson.core.JsonGenerator;
import org.apache.tinkerpop.shaded.jackson.databind.JsonSerializer;
import org.apache.tinkerpop.shaded.jackson.databind.SerializerProvider;
import org.apache.tinkerpop.shaded.jackson.databind.jsontype.TypeSerializer;

/** Created by Enrico Risa on 06/09/2017. */
public final class RecordIdJacksonSerializer extends JsonSerializer<RID> {

  @Override
  public void serialize(RID value, JsonGenerator jgen, SerializerProvider provider)
      throws IOException {
    jgen.writeStartObject();
    jgen.writeFieldName(YTDBIoRegistry.COLLECTION_ID);
    jgen.writeNumber(value.getCollectionId());
    jgen.writeFieldName(YTDBIoRegistry.COLLECTION_POSITION);
    jgen.writeNumber(value.getCollectionPosition());
    jgen.writeEndObject();
  }

  @Override
  public void serializeWithType(
      RID value, JsonGenerator jgen, SerializerProvider serializers, TypeSerializer typeSer)
      throws IOException {
    typeSer.writeTypePrefixForScalar(value, jgen);
    jgen.writeString(value.toString());
    typeSer.writeTypeSuffixForScalar(value, jgen);
  }
}
