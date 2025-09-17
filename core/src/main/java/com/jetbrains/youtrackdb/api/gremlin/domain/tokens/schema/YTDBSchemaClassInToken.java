package com.jetbrains.youtrackdb.api.gremlin.domain.tokens.schema;

import com.jetbrains.youtrackdb.api.gremlin.embedded.domain.YTDBSchemaClass;
import com.jetbrains.youtrackdb.api.gremlin.domain.tokens.YTDBInToken;

public enum YTDBSchemaClassInToken implements YTDBInToken<YTDBSchemaClass> {
  superClass,
  linkedClass
}
