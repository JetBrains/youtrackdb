package com.jetbrains.youtrackdb.internal.core.gremlin.domain.schema;

import com.jetbrains.youtrackdb.api.gremlin.embedded.domain.YTDBSchemaClass;
import com.jetbrains.youtrackdb.internal.core.gremlin.domain.tokens.YTDBInTokenInternal;
import java.util.Iterator;
import org.apache.commons.collections4.IteratorUtils;

public enum YTDBSchemaPropertyInTokenInternal implements
    YTDBInTokenInternal<YTDBSchemaPropertyImpl> {
  declaredProperty {
    @Override
    public Iterator<YTDBSchemaClass> apply(YTDBSchemaPropertyImpl ytdbSchemaProperty) {
      return IteratorUtils.singletonIterator(ytdbSchemaProperty.declaringClass());
    }
  }
}
