package com.jetbrains.youtrackdb.api.gremlin.domain.tokens.schema;

import com.jetbrains.youtrackdb.api.gremlin.domain.tokens.YTDBInToken;
import com.jetbrains.youtrackdb.api.gremlin.embedded.domain.YTDBSchemaClass;

public enum YTDBSchemaClassInToken implements YTDBInToken<YTDBSchemaClass> {
  parentClass,
  linkedClass,
  classToIndex
}
