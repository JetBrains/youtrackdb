package com.jetbrains.youtrackdb.api.gremlin.domain.tokens.schema;

import com.jetbrains.youtrackdb.api.gremlin.domain.tokens.YTDBPToken;
import com.jetbrains.youtrackdb.api.gremlin.embedded.domain.YTDBSchemaProperty;

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
  regExp
}
