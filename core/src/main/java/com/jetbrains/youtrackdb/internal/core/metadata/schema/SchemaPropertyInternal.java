package com.jetbrains.youtrackdb.internal.core.metadata.schema;

import com.jetbrains.youtrackdb.api.DatabaseSession;
import com.jetbrains.youtrackdb.api.schema.SchemaProperty;
import com.jetbrains.youtrackdb.internal.core.index.Index;
import java.util.Collection;

public interface SchemaPropertyInternal extends SchemaProperty {

  /**
   * @return All indexes in which this property participates.
   */
  Collection<String> getAllIndexes();

  Collection<Index> getAllIndexesInternal();

  DatabaseSession getBoundToSession();

  PropertyTypeInternal getTypeInternal();
}
