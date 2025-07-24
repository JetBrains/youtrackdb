package com.jetbrains.youtrack.db.api.gremlin.tokens.schema;

import com.jetbrains.youtrack.db.api.gremlin.embedded.schema.YTDBSchemaClass;
import com.jetbrains.youtrack.db.api.gremlin.tokens.YTDBDomainObjectInToken;
import java.util.Iterator;
import org.apache.tinkerpop.gremlin.structure.Vertex;

public enum YTDBSchemaClassInToken implements YTDBDomainObjectInToken<YTDBSchemaClass> {
  superClasses {
    @Override
    public Iterator<Vertex> apply(YTDBSchemaClass ytdbSchemaClass) {
      //noinspection unchecked,rawtypes
      return (Iterator) ytdbSchemaClass.superClasses();
    }
  },

  declaredProperties {
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
