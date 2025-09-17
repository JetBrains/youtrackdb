package com.jetbrains.youtrackdb.api.gremlin.domain.tokens.schema;

import com.jetbrains.youtrackdb.api.gremlin.embedded.domain.YTDBSchemaClass;
import com.jetbrains.youtrackdb.api.gremlin.domain.tokens.YTDBOutToken;

public enum YTDBSchemaClassOutToken implements YTDBOutToken<YTDBSchemaClass> {
  superClass,
  declaredProperty
}
