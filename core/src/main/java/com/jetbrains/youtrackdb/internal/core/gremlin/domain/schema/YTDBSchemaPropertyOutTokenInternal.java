package com.jetbrains.youtrackdb.internal.core.gremlin.domain.schema;

import com.jetbrains.youtrackdb.api.gremlin.embedded.domain.YTDBDomainVertex;
import com.jetbrains.youtrackdb.api.gremlin.embedded.domain.YTDBSchemaClass;
import com.jetbrains.youtrackdb.internal.core.gremlin.domain.YTDBDomainEdgeImpl;
import com.jetbrains.youtrackdb.internal.core.gremlin.domain.tokens.YTDBOutTokenInternal;
import java.util.Iterator;
import javax.annotation.Nonnull;
import org.apache.commons.collections4.IteratorUtils;


public enum YTDBSchemaPropertyOutTokenInternal implements
    YTDBOutTokenInternal<YTDBSchemaPropertyImpl> {
  linkedClass {
    @Override
    public Iterator<YTDBSchemaClass> apply(YTDBSchemaPropertyImpl ytdbSchemaProperty) {
      return IteratorUtils.singletonIterator(ytdbSchemaProperty.linkedClass());
    }


    @Override
    public <I extends YTDBDomainVertex> YTDBDomainEdgeImpl<YTDBSchemaPropertyImpl, I> add(
        YTDBSchemaPropertyImpl outVertex, I inVertex) {
      var ytdbSchemaClass = checkInVertexType(inVertex);

      var currentLinkedClass = outVertex.linkedClass();
      if (currentLinkedClass != null && !currentLinkedClass.equals(ytdbSchemaClass)) {
        throw new IllegalStateException(
            "Property " + outVertex.fullName() + " already has linkedClass " + currentLinkedClass
                + " defined.");
      }

      outVertex.linkedClass(ytdbSchemaClass);

      return new YTDBDomainEdgeImpl<>(outVertex.getGraph(), outVertex, inVertex, this);
    }

    @Override
    public void remove(YTDBSchemaPropertyImpl outVertex, YTDBDomainVertex inVertex) {
      var ytdbSchemaClass = checkInVertexType(inVertex);
      var currentLinkedClass = outVertex.linkedClass();
      if (currentLinkedClass != null && !currentLinkedClass.equals(ytdbSchemaClass)) {
        return;
      }

      outVertex.linkedClass(null);
    }

    @Nonnull
    private YTDBSchemaClass checkInVertexType(YTDBDomainVertex inVertex) {
      if (!(inVertex instanceof YTDBSchemaClass ytdbSchemaClass)) {
        throw new IllegalArgumentException(
            "Incoming vertex for " + name() + " edge should implement "
                + YTDBSchemaClass.class.getSimpleName());
      }

      return ytdbSchemaClass;
    }
  }
}
