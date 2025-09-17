package com.jetbrains.youtrackdb.internal.core.gremlin.domain.schema;

import com.jetbrains.youtrackdb.internal.core.gremlin.domain.tokens.YTDBInTokenInternal;
import java.util.Iterator;
import org.apache.tinkerpop.gremlin.structure.Vertex;

public enum YTDBSchemaPropertyInTokenInternal implements YTDBInTokenInternal<YTDBSchemaPropertyImpl> {
  declaredProperty {
    @Override
    public Iterator<Vertex> apply(YTDBSchemaPropertyImpl ytdbSchemaProperty) {
      return null;
    }
  }
}
