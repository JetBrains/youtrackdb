package com.jetbrains.youtrackdb.internal.core.metadata.schema;

import com.jetbrains.youtrackdb.internal.core.index.IndexDefinition;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.ImmutableSchema.IndexType;

public interface SchemaIndex {
  IndexDefinition getIndexDefinition();

  int getId();

  String getName();

  IndexType getType();
}
