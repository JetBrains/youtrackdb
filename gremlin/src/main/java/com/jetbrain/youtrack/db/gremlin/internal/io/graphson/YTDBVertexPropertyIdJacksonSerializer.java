package com.jetbrain.youtrack.db.gremlin.internal.io.graphson;

import com.jetbrain.youtrack.db.gremlin.api.YTDBVertexPropertyId;
import com.jetbrain.youtrack.db.gremlin.internal.io.YTDBIoRegistry;
import java.io.IOException;
import org.apache.tinkerpop.shaded.jackson.core.JsonGenerator;
import org.apache.tinkerpop.shaded.jackson.databind.JsonSerializer;
import org.apache.tinkerpop.shaded.jackson.databind.SerializerProvider;
import org.apache.tinkerpop.shaded.jackson.databind.jsontype.TypeSerializer;

public class YTDBVertexPropertyIdJacksonSerializer extends JsonSerializer<YTDBVertexPropertyId> {

  @Override
  public void serialize(YTDBVertexPropertyId ytdbVertexPropertyId, JsonGenerator jgen,
      SerializerProvider serializerProvider) throws IOException {
    jgen.writeStartObject();
    jgen.writeFieldName(YTDBIoRegistry.COLLECTION_ID);
    var rid = ytdbVertexPropertyId.rid();
    jgen.writeNumber(rid.getCollectionId());
    jgen.writeFieldName(YTDBIoRegistry.COLLECTION_POSITION);
    jgen.writeNumber(rid.getCollectionPosition());
    jgen.writeFieldName(YTDBIoRegistry.PROPERTY_KEY);
    jgen.writeString(ytdbVertexPropertyId.key());
    jgen.writeEndObject();
  }

  @Override
  public void serializeWithType(YTDBVertexPropertyId value, JsonGenerator jgen,
      SerializerProvider serializers, TypeSerializer typeSer) throws IOException {
    typeSer.writeTypePrefixForScalar(value, jgen);
    var rid = value.rid();
    jgen.writeString(String.format("%d:%d:%s", rid.getCollectionId(),
        rid.getCollectionPosition(), value.key()));
    typeSer.writeTypeSuffixForScalar(value, jgen);
  }
}
