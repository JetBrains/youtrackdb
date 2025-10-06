package com.jetbrains.youtrackdb.internal.core.gremlin.domain.schema;

import com.jetbrains.youtrackdb.api.gremlin.embedded.domain.YTDBSchemaClass;
import com.jetbrains.youtrackdb.internal.core.gremlin.domain.tokens.YTDBInTokenInternal;
import java.util.Iterator;

public enum YTDBSchemaClassInTokenInternal implements YTDBInTokenInternal<YTDBSchemaClassImpl> {
  parentClass {
    @Override
    public Iterator<YTDBSchemaClass> apply(YTDBSchemaClassImpl ytdbSchemaClass) {
      return ytdbSchemaClass.childClasses();
    }
  },
}
