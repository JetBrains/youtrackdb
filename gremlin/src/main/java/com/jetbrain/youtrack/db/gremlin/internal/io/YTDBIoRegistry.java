package com.jetbrain.youtrack.db.gremlin.internal.io;

import com.jetbrain.youtrack.db.gremlin.api.YTDBVertexPropertyId;
import com.jetbrain.youtrack.db.gremlin.internal.io.graphson.YTDBGraphSONV3;
import com.jetbrain.youtrack.db.gremlin.internal.io.gryo.LinkBagGyroSerializer;
import com.jetbrain.youtrack.db.gremlin.internal.io.gryo.RecordIdGyroSerializer;
import com.jetbrain.youtrack.db.gremlin.internal.io.gryo.YTDBVertexPropertyIdGyroSerializer;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrack.db.internal.core.db.record.ridbag.LinkBag;
import com.jetbrains.youtrack.db.internal.core.id.ChangeableRecordId;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import java.util.Map;
import javax.annotation.Nullable;
import org.apache.tinkerpop.gremlin.structure.io.AbstractIoRegistry;
import org.apache.tinkerpop.gremlin.structure.io.graphson.GraphSONIo;
import org.apache.tinkerpop.gremlin.structure.io.gryo.GryoIo;

public final class YTDBIoRegistry extends AbstractIoRegistry {

  public static final String COLLECTION_ID = "collectionId";
  public static final String COLLECTION_POSITION = "collectionPosition";
  public static final String PROPERTY_KEY = "propertyKey";

  public YTDBIoRegistry(DatabaseSessionEmbedded session) {
    register(GryoIo.class, RecordId.class, RecordIdGyroSerializer.INSTANCE);
    register(GryoIo.class, ChangeableRecordId.class, RecordIdGyroSerializer.INSTANCE);
    register(GryoIo.class, YTDBVertexPropertyId.class, YTDBVertexPropertyIdGyroSerializer.INSTANCE);

    register(GraphSONIo.class, RecordId.class, YTDBGraphSONV3.INSTANCE);
    register(GryoIo.class, LinkBag.class, new LinkBagGyroSerializer(session));
  }


  @Nullable
  public static Object newYTDBId(final Object obj) {
    return switch (obj) {
      case null -> null;
      case RecordId recordId -> recordId;

      case Map<?, ?> map -> {
        var rid = new RecordId(((Number) map.get(COLLECTION_ID)).intValue(),
            ((Number) map.get(COLLECTION_POSITION)).longValue());

        if (map.containsKey(PROPERTY_KEY)) {
          yield new YTDBVertexPropertyId(rid, (String) map.get(PROPERTY_KEY));
        }

        yield rid;

      }
      default -> throw new IllegalArgumentException(
          "Unable to convert unknow type to ORecordId " + obj.getClass());
    };

  }

  public static boolean isYTDBRecord(final Object result) {
    if (!(result instanceof Map)) {
      return false;
    }

    @SuppressWarnings("unchecked") final var map = (Map<String, Number>) result;
    return map.containsKey(COLLECTION_ID) && map.containsKey(COLLECTION_POSITION);
  }
}
