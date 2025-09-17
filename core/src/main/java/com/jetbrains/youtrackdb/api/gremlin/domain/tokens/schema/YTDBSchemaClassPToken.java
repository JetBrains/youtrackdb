package com.jetbrains.youtrackdb.api.gremlin.domain.tokens.schema;

import com.jetbrains.youtrackdb.api.gremlin.embedded.domain.YTDBSchemaClass;
import com.jetbrains.youtrackdb.api.gremlin.domain.tokens.YTDBPToken;

public enum YTDBSchemaClassPToken implements YTDBPToken<YTDBSchemaClass> {
  abstractClass,
  strictMode,
  hasSuperClasses,
  name,
  description,
  collectionIds,
  polymorphicCollectionIds,
  isEdgeType,
  isVertexType,
}
