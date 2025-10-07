package com.jetbrains.youtrackdb.internal.core.gremlin.domain.schema;

import com.jetbrains.youtrackdb.api.gremlin.embedded.domain.YTDBDomainVertex;
import com.jetbrains.youtrackdb.internal.core.gremlin.domain.tokens.YTDBInTokenInternal;
import java.util.Iterator;

public enum YTDBSchemaIndexInTokenInternal implements
    YTDBInTokenInternal<YTDBSchemaPropertyImpl> {
  ;

  @Override
  public Iterator<? extends YTDBDomainVertex> apply(YTDBSchemaPropertyImpl ytdbSchemaProperty) {
    throw new UnsupportedOperationException();
  }
}
