package com.jetbrains.youtrackdb.internal.core.gremlin.domain.schema;

import com.jetbrains.youtrackdb.internal.core.gremlin.domain.tokens.YTDBOutTokenInternal;
import java.util.Iterator;
import org.apache.tinkerpop.gremlin.structure.Vertex;

public enum YTDBSchemaClassOutTokenInternal implements YTDBOutTokenInternal<YTDBSchemaClassImpl> {
  superClass {
    @Override
    public Iterator<Vertex> apply(YTDBSchemaClassImpl ytdbSchemaClass) {
      //noinspection unchecked,rawtypes
      return (Iterator) ytdbSchemaClass.superClasses();
    }
  },
  declaredProperty {
    @Override
    public Iterator<Vertex> apply(YTDBSchemaClassImpl ytdbSchemaClass) {
      //noinspection unchecked,rawtypes
      return (Iterator) ytdbSchemaClass.declaredProperty();
    }
  }
}
