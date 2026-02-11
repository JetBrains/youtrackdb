package com.jetbrains.youtrackdb.internal.core.metadata.schema;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.index.Index;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaProperty;
import java.util.Collection;

public interface SchemaPropertyInternal extends SchemaProperty {

  /**
   * @return All indexes in which this property participates.
   */
  Collection<String> getAllIndexes();

  Collection<Index> getAllIndexesInternal();

  DatabaseSessionEmbedded getBoundToSession();

  PropertyTypeInternal getTypeInternal();
}
