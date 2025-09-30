package com.jetbrains.youtrackdb.internal.core.metadata.schema;

import com.jetbrains.youtrackdb.internal.core.index.IndexDefinition;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaManager.INDEX_TYPE;

public interface SchemaIndex {

  IndexDefinition getIndexDefinition();

  int getId();

  String getName();

  INDEX_TYPE getType();
}
