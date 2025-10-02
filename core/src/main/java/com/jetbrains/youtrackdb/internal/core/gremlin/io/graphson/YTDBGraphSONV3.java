package com.jetbrains.youtrackdb.internal.core.gremlin.io.graphson;

import static com.jetbrains.youtrackdb.internal.core.gremlin.io.YTDBIoRegistry.isYTDBRecord;
import static com.jetbrains.youtrackdb.internal.core.gremlin.io.YTDBIoRegistry.newYTdbId;

import com.jetbrains.youtrackdb.api.gremlin.YTDBVertexPropertyId;
import com.jetbrains.youtrackdb.api.record.RID;
import com.jetbrains.youtrackdb.internal.core.id.ChangeableRecordId;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.io.graphson.AbstractObjectDeserializer;
import org.apache.tinkerpop.gremlin.structure.io.graphson.GraphSONTokens;
import org.apache.tinkerpop.gremlin.structure.util.detached.DetachedEdge;
import org.apache.tinkerpop.gremlin.structure.util.detached.DetachedVertex;
import org.apache.tinkerpop.shaded.jackson.core.JsonParser;
import org.apache.tinkerpop.shaded.jackson.core.JsonToken;
import org.apache.tinkerpop.shaded.jackson.databind.DeserializationContext;
import org.apache.tinkerpop.shaded.jackson.databind.JsonDeserializer;

/**
 * Created by Enrico Risa on 06/09/2017.
 */
public class YTDBGraphSONV3 extends YTDBGraphSON {

  public static final YTDBGraphSONV3 INSTANCE = new YTDBGraphSONV3();

  @SuppressWarnings("rawtypes")
  protected static final Map<Class, String> TYPES =
      Collections.unmodifiableMap(
          new LinkedHashMap<>() {
            {
              put(RecordId.class, "RecordId");
              put(ChangeableRecordId.class, "NRecordId");
              put(YTDBVertexPropertyId.class, "VertexPropertyId");
            }
          });

  public YTDBGraphSONV3() {
    super("ytdb-graphson-v3");
    addSerializer(RID.class, new YTDBRecordIdJacksonSerializer());

    addDeserializer(RecordId.class, new YTDBImmutableRecordIdJacksonDeserializer());
    addDeserializer(ChangeableRecordId.class, new YTDBChangeableRecordIdJacksonSerializer());

    addSerializer(YTDBVertexPropertyId.class, new YTDBVertexPropertyIdJacksonSerializer());
    addDeserializer(YTDBVertexPropertyId.class, new YTDBVertexPropertyIdJacksonDeserializer());

    addDeserializer(Edge.class, new EdgeJacksonDeserializer());
    addDeserializer(Vertex.class, new VertexJacksonDeserializer());
    //noinspection rawtypes,unchecked
    addDeserializer(Map.class, (JsonDeserializer) new YTDBIdDeserializer());
  }

  @SuppressWarnings("rawtypes")
  @Override
  public Map<Class, String> getTypeDefinitions() {
    return TYPES;
  }

  /**
   * Created by Enrico Risa on 06/09/2017.
   */
  public static class EdgeJacksonDeserializer extends AbstractObjectDeserializer<Edge> {

    public EdgeJacksonDeserializer() {
      super(Edge.class);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Override
    public Edge createObject(final Map<String, Object> edgeData) {
      return new DetachedEdge(
          newYTdbId(edgeData.get(GraphSONTokens.ID)),
          edgeData.get(GraphSONTokens.LABEL).toString(),
          (Map) edgeData.get(GraphSONTokens.PROPERTIES),
          newYTdbId(edgeData.get(GraphSONTokens.OUT)),
          edgeData.get(GraphSONTokens.OUT_LABEL).toString(),
          newYTdbId(edgeData.get(GraphSONTokens.IN)),
          edgeData.get(GraphSONTokens.IN_LABEL).toString());
    }
  }

  /**
   * Created by Enrico Risa on 06/09/2017.
   */
  public static class VertexJacksonDeserializer extends AbstractObjectDeserializer<Vertex> {

    public VertexJacksonDeserializer() {
      super(Vertex.class);
    }

    @SuppressWarnings("unchecked")
    @Override
    public Vertex createObject(final Map<String, Object> vertexData) {
      return new DetachedVertex(
          newYTdbId(vertexData.get(GraphSONTokens.ID)),
          vertexData.get(GraphSONTokens.LABEL).toString(),
          (Map<String, Object>) vertexData.get(GraphSONTokens.PROPERTIES));
    }
  }

  static final class YTDBIdDeserializer extends AbstractObjectDeserializer<Object> {

    public YTDBIdDeserializer() {
      super(Object.class);
    }

    @Override
    public Object deserialize(JsonParser jsonParser, DeserializationContext deserializationContext)
        throws IOException {
      var keyString = true;
      if (jsonParser.currentToken() == JsonToken.START_OBJECT) {
        var m = deserializationContext.readValue(jsonParser, HashMap.class);
        //noinspection unchecked
        return createObject(m);
      } else {
        final var m = new HashMap<>();
        while (jsonParser.nextToken() != JsonToken.END_ARRAY) {
          final var key = deserializationContext.readValue(jsonParser, Object.class);
          if (!(key instanceof String)) {
            keyString = false;
          }
          jsonParser.nextToken();
          final var val = deserializationContext.readValue(jsonParser, Object.class);
          m.put(key, val);
        }
        if (keyString) {
          //noinspection unchecked,rawtypes
          return createObject((Map<String, Object>) (Map) m);
        } else {
          return m;
        }
      }
    }

    @Override
    public Object createObject(Map<String, Object> data) {
      if (isYTDBRecord(data)) {
        return newYTdbId(data);
      }
      return data;
    }
  }
}
