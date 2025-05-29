package com.jetbrain.youtrack.db.gremlin.internal.io;

import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrack.db.internal.core.db.record.ridbag.LinkBag;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;


import java.util.Map;

import javax.annotation.Nullable;
import com.jetbrain.youtrack.db.gremlin.internal.io.graphson.YTDBGraphSONV3;
import com.jetbrain.youtrack.db.gremlin.internal.io.gryo.RecordIdGyroSerializer;
import com.jetbrain.youtrack.db.gremlin.internal.io.gryo.LinkBagGyroSerializer;
import org.apache.tinkerpop.gremlin.structure.io.AbstractIoRegistry;
import org.apache.tinkerpop.gremlin.structure.io.graphson.GraphSONIo;
import org.apache.tinkerpop.gremlin.structure.io.gryo.GryoIo;

public final class YTDBIoRegistry extends AbstractIoRegistry {

  public static final String COLLECTION_ID = "collectionId";
  public static final String COLLECTION_POSITION = "collectionPosition";

  public YTDBIoRegistry(DatabaseSessionEmbedded session) {
    register(GryoIo.class, RecordId.class, RecordIdGyroSerializer.INSTANCE);
    register(GraphSONIo.class, RecordId.class, YTDBGraphSONV3.INSTANCE);

    register(GryoIo.class, LinkBag.class, new LinkBagGyroSerializer(session));
  }


  @Nullable
  public static RecordId newYTDBRecordId(final Object obj) {
    return switch (obj) {
      case null -> null;
      case RecordId recordId -> recordId;
      case Map<?, ?> map -> new RecordId((Integer) map.get(COLLECTION_ID),
          (Long) map.get(COLLECTION_POSITION));
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
