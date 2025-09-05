package com.jetbrains.youtrackdb.api.gremlin.tokens.schema;

import com.jetbrains.youtrackdb.api.gremlin.embedded.schema.YTDBSchemaProperty;
import com.jetbrains.youtrackdb.api.gremlin.tokens.YTDBDomainObjectInToken;
import java.util.Iterator;
import org.apache.commons.collections4.IteratorUtils;
import org.apache.tinkerpop.gremlin.structure.Vertex;

public enum YTDBSchemaPropertyInToken implements YTDBDomainObjectInToken<YTDBSchemaProperty> {
  declaredProperty {
    @Override
    public Iterator<Vertex> apply(YTDBSchemaProperty ytdbSchemaProperty) {
      return IteratorUtils.singletonIterator(ytdbSchemaProperty.ownerClass());
    }
  }
}
