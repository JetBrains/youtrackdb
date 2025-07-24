package com.jetbrains.youtrack.db.api.gremlin.tokens.schema;

import com.jetbrains.youtrack.db.api.gremlin.embedded.schema.YTDBSchemaClass;
import com.jetbrains.youtrack.db.api.gremlin.tokens.YTDBDomainObjectObjectOutToken;
import java.util.Iterator;
import org.apache.tinkerpop.gremlin.structure.Vertex;

public enum YTDBSchemaClassOutToken implements YTDBDomainObjectObjectOutToken<YTDBSchemaClass> {
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
      //noinspection unchecked,rawtypes
      return (Iterator) ytdbSchemaClass.declaredProperty();
    }
  }
}
