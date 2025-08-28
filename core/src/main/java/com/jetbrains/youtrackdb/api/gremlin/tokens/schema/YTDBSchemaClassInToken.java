package com.jetbrains.youtrackdb.api.gremlin.tokens.schema;

import com.jetbrains.youtrackdb.api.gremlin.embedded.schema.YTDBSchemaClass;
import com.jetbrains.youtrackdb.api.gremlin.tokens.YTDBDomainObjectInToken;
import java.util.Iterator;
import org.apache.tinkerpop.gremlin.structure.Vertex;

public enum YTDBSchemaClassInToken implements YTDBDomainObjectInToken<YTDBSchemaClass> {
  superClass {
    @Override
    public Iterator<Vertex> apply(YTDBSchemaClass ytdbSchemaClass) {
      //noinspection unchecked,rawtypes
      return (Iterator) ytdbSchemaClass.superClasses();
    }
  },

  declaredProperty {
    @Override
    public Iterator<Vertex> apply(YTDBSchemaClass ytdbSchemaClass) {
      throw Exceptions.trackingOfIncomingEdgesNotSupported(
          this);
    }
  },

  ownerClass {
    @Override
    public Iterator<Vertex> apply(YTDBSchemaClass ytdbSchemaClass) {
      throw Exceptions.trackingOfIncomingEdgesNotSupported(
          this);
    }
  }
}
