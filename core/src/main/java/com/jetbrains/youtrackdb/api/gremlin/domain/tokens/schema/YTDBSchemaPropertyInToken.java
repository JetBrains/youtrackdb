package com.jetbrains.youtrackdb.api.gremlin.domain.tokens.schema;

import com.jetbrains.youtrackdb.api.gremlin.domain.tokens.YTDBInToken;
import com.jetbrains.youtrackdb.api.gremlin.embedded.domain.YTDBSchemaProperty;

public enum YTDBSchemaPropertyInToken implements YTDBInToken<YTDBSchemaProperty> {
  declaredProperty,
  propertyToIndex
}
