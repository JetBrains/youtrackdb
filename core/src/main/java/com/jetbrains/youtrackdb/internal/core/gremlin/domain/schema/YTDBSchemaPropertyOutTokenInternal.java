package com.jetbrains.youtrackdb.internal.core.gremlin.domain.schema;

import com.jetbrains.youtrackdb.api.gremlin.embedded.domain.YTDBDomainVertex;
import com.jetbrains.youtrackdb.internal.core.gremlin.domain.tokens.YTDBOutTokenInternal;
import java.util.Iterator;
import org.apache.commons.collections4.IteratorUtils;
import org.apache.tinkerpop.gremlin.structure.Vertex;


public enum YTDBSchemaPropertyOutTokenInternal implements
    YTDBOutTokenInternal<YTDBSchemaPropertyImpl> {
  linkedClass {
    @Override
    public Iterator<Vertex> apply(YTDBSchemaPropertyImpl ytdbSchemaProperty) {
      return IteratorUtils.singletonIterator(ytdbSchemaProperty.linkedClass());
    }

    @Override
    public void add(YTDBDomainVertex inVertex) {

    }

    @Override
    public void remove(YTDBSchemaPropertyImpl outVertex, YTDBDomainVertex inVertex) {

    }
  }
}
