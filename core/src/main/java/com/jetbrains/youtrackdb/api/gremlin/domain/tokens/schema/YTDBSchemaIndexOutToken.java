package com.jetbrains.youtrackdb.api.gremlin.domain.tokens.schema;

import com.jetbrains.youtrackdb.api.gremlin.domain.tokens.YTDBOutToken;
import com.jetbrains.youtrackdb.api.gremlin.embedded.domain.YTDBSchemaIndex;

public enum YTDBSchemaIndexOutToken implements YTDBOutToken<YTDBSchemaIndex> {
  classToIndex,
  propertyToIndex
}
