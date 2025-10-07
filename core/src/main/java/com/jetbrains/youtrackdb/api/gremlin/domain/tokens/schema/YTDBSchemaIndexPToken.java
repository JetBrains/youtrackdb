package com.jetbrains.youtrackdb.api.gremlin.domain.tokens.schema;

import com.jetbrains.youtrackdb.api.gremlin.domain.tokens.YTDBPToken;
import com.jetbrains.youtrackdb.api.gremlin.embedded.domain.YTDBSchemaIndex;

public enum YTDBSchemaIndexPToken implements YTDBPToken<YTDBSchemaIndex> {
  name,
  nullValuesIgnored,
  metadata,
  indexBy,
  indexType
}
