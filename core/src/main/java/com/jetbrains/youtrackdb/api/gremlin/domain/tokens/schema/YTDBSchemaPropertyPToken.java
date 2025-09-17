package com.jetbrains.youtrackdb.api.gremlin.domain.tokens.schema;

import com.jetbrains.youtrackdb.api.gremlin.embedded.domain.YTDBSchemaProperty;
import com.jetbrains.youtrackdb.api.gremlin.domain.tokens.YTDBPToken;

public enum YTDBSchemaPropertyPToken implements YTDBPToken<YTDBSchemaProperty> {
  name,
  fullName,
  type,
  linkedType,
  notNull,
  collateName,
  mandatory,
  readonly,
  min,
  max,
  defaultValue,
  regexp
}
