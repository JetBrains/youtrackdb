package com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.RecordSerializer;

public interface RecordSerializerNetwork extends RecordSerializer {

  byte[] serializeValue(DatabaseSessionEmbedded db, Object value, PropertyTypeInternal type);

  Object deserializeValue(DatabaseSessionEmbedded db, byte[] val, PropertyTypeInternal type);
}
