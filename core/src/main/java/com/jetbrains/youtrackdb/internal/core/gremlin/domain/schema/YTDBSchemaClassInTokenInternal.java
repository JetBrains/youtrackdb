package com.jetbrains.youtrackdb.internal.core.gremlin.domain.schema;

import com.jetbrains.youtrackdb.internal.core.gremlin.domain.tokens.YTDBInTokenInternal;
import java.util.Iterator;
import org.apache.tinkerpop.gremlin.structure.Vertex;

public enum YTDBSchemaClassInTokenInternal implements YTDBInTokenInternal<YTDBSchemaClassImpl> {
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
      throw Exceptions.trackingOfIncomingEdgesNotSupported(
          this);
    }
  },

  ownerClass {
    @Override
    public Iterator<Vertex> apply(YTDBSchemaClassImpl ytdbSchemaClass) {
      throw Exceptions.trackingOfIncomingEdgesNotSupported(
          this);
    }
  }
}
