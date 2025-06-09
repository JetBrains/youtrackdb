package com.jetbrains.youtrack.db.internal.core.metadata.schema;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.schema.SchemaProperty;
import com.jetbrains.youtrack.db.internal.core.index.Index;
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
