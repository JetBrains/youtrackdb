package com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary;

import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.RecordSerializer;

public interface RecordSerializerNetwork extends RecordSerializer {

  byte[] serializeValue(DatabaseSessionInternal db, Object value, PropertyTypeInternal type);

  Object deserializeValue(DatabaseSessionInternal db, byte[] val, PropertyTypeInternal type);
}
