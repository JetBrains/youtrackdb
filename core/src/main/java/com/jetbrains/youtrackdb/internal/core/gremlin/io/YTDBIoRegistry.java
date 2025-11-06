package com.jetbrains.youtrackdb.internal.core.gremlin.io;

import com.jetbrains.youtrackdb.api.gremlin.YTDBVertexPropertyId;
import com.jetbrains.youtrackdb.internal.core.db.record.ridbag.LinkBag;
import com.jetbrains.youtrackdb.internal.core.gremlin.io.graphson.YTDBGraphSONV3;
import com.jetbrains.youtrackdb.internal.core.gremlin.io.gryo.LinkBagGyroSerializer;
import com.jetbrains.youtrackdb.internal.core.gremlin.io.gryo.RecordIdGyroSerializer;
import com.jetbrains.youtrackdb.internal.core.gremlin.io.gryo.YTDBVertexPropertyIdGyroSerializer;
import com.jetbrains.youtrackdb.internal.core.id.ChangeableRecordId;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import java.util.Map;
import javax.annotation.Nullable;
import org.apache.tinkerpop.gremlin.structure.io.AbstractIoRegistry;
import org.apache.tinkerpop.gremlin.structure.io.graphson.GraphSONIo;
import org.apache.tinkerpop.gremlin.structure.io.gryo.GryoIo;

public final class YTDBIoRegistry extends AbstractIoRegistry {

  public static final YTDBIoRegistry INSTANCE = new YTDBIoRegistry();

  public static final String COLLECTION_ID = "collectionId";
  public static final String COLLECTION_POSITION = "collectionPosition";
  public static final String PROPERTY_KEY = "propertyKey";

  public static YTDBIoRegistry instance() {
    return INSTANCE;
  }

  public YTDBIoRegistry() {
    register(GryoIo.class, RecordId.class, RecordIdGyroSerializer.INSTANCE);
    register(GryoIo.class, ChangeableRecordId.class, RecordIdGyroSerializer.INSTANCE);
    register(GryoIo.class, YTDBVertexPropertyId.class, YTDBVertexPropertyIdGyroSerializer.INSTANCE);

    register(GraphSONIo.class, RecordIdInternal.class, YTDBGraphSONV3.INSTANCE);
    register(GryoIo.class, LinkBag.class, LinkBagGyroSerializer.INSTANCE);
  }


  @Nullable
  public static Object newYTdbId(final Object obj) {
    return switch (obj) {
      case null -> null;
      case RecordIdInternal recordId -> recordId;

      case Map<?, ?> map -> {
        var collectionId = ((Number) map.get(COLLECTION_ID)).intValue();
        var collectionPosition = ((Number) map.get(COLLECTION_POSITION)).longValue();
        RecordIdInternal rid;
        if (collectionPosition < 0) {
          rid = new ChangeableRecordId(collectionId, collectionPosition);
        } else {
          rid = new RecordId(collectionId, collectionPosition);
        }

        if (map.containsKey(PROPERTY_KEY)) {
          yield new YTDBVertexPropertyId(rid, (String) map.get(PROPERTY_KEY));
        }

        yield rid;

      }
      case String stringId -> RecordIdInternal.fromString(stringId, true);
      default -> throw new IllegalArgumentException(
          "Unable to convert unknow type to RecordId " + obj.getClass());
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
