package com.jetbrains.youtrackdb.internal.core.gremlin.io.graphson;

import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.gremlin.io.YTDBIoRegistry;
import com.jetbrains.youtrackdb.internal.core.id.ChangeableRecordId;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import java.io.IOException;
import org.apache.tinkerpop.shaded.jackson.core.JsonGenerator;
import org.apache.tinkerpop.shaded.jackson.databind.JsonSerializer;
import org.apache.tinkerpop.shaded.jackson.databind.SerializerProvider;
import org.apache.tinkerpop.shaded.jackson.databind.jsontype.TypeSerializer;

public final class YTDBRecordIdJacksonSerializer extends JsonSerializer<RID> {

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
    if (value instanceof ChangeableRecordId changeableRecordId
        && changeableRecordId.isPersistent()) {
      value = new RecordId(changeableRecordId);
    }
    if (value.isNew() && (value instanceof RecordId)) {
      value = new ChangeableRecordId(value.getCollectionId(), value.getCollectionPosition());
    }

    typeSer.writeTypePrefixForScalar(value, jgen);
    jgen.writeString(value.toString());
    typeSer.writeTypeSuffixForScalar(value, jgen);
  }
}
