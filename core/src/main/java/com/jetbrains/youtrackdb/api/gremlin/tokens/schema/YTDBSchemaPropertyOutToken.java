package com.jetbrains.youtrackdb.api.gremlin.tokens.schema;

import com.jetbrains.youtrackdb.api.gremlin.embedded.schema.YTDBSchemaProperty;
import com.jetbrains.youtrackdb.api.gremlin.tokens.YTDBDomainObjectObjectOutToken;
import java.util.Iterator;
import org.apache.commons.collections4.IteratorUtils;
import org.apache.tinkerpop.gremlin.structure.Vertex;

public enum YTDBSchemaPropertyOutToken implements
    YTDBDomainObjectObjectOutToken<YTDBSchemaProperty> {
  linkedClass {
    @Override
    public Iterator<Vertex> apply(YTDBSchemaProperty ytdbSchemaProperty) {
      return IteratorUtils.singletonIterator(ytdbSchemaProperty.linkedClass());
    }
  }
}
