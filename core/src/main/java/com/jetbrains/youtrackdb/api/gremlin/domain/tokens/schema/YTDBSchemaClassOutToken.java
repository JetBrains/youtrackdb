package com.jetbrains.youtrackdb.api.gremlin.domain.tokens.schema;

import com.jetbrains.youtrackdb.api.gremlin.domain.tokens.YTDBOutToken;
import com.jetbrains.youtrackdb.api.gremlin.embedded.domain.YTDBSchemaClass;

public enum YTDBSchemaClassOutToken implements YTDBOutToken<YTDBSchemaClass> {
  parentClass,
  declaredProperty
}
