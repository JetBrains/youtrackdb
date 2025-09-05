package com.jetbrains.youtrackdb.api.gremlin.tokens.schema;

import com.jetbrains.youtrackdb.api.gremlin.embedded.schema.YTDBSchemaClass;
import com.jetbrains.youtrackdb.api.gremlin.tokens.YTDBDomainObjectObjectOutToken;
import java.util.Iterator;
import org.apache.tinkerpop.gremlin.structure.Vertex;

public enum YTDBSchemaClassOutToken implements YTDBDomainObjectObjectOutToken<YTDBSchemaClass> {
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
      //noinspection unchecked,rawtypes
      return (Iterator) ytdbSchemaClass.declaredProperty();
    }
  }
}
