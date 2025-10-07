package com.jetbrains.youtrackdb.internal.core.gremlin.domain.schema;

import com.jetbrains.youtrackdb.api.gremlin.embedded.domain.YTDBDomainVertex;
import com.jetbrains.youtrackdb.internal.core.gremlin.domain.YTDBDomainEdgeImpl;
import com.jetbrains.youtrackdb.internal.core.gremlin.domain.tokens.YTDBOutTokenInternal;
import java.util.Iterator;
import javax.annotation.Nonnull;
import org.apache.commons.collections4.IteratorUtils;

public enum YTDBSchemaIndexOutTokenInternal implements YTDBOutTokenInternal<YTDBSchemaIndexImpl> {
  classToIndex {
    @Override
    public Iterator<? extends YTDBDomainVertex> apply(YTDBSchemaIndexImpl ytdbSchemaIndex) {
      return IteratorUtils.singletonIterator(ytdbSchemaIndex.classToIndex());
    }

    @Override
    public <I extends YTDBDomainVertex> YTDBDomainEdgeImpl<YTDBSchemaIndexImpl, I> add(
        YTDBSchemaIndexImpl outVertex, I inVertex) {
      throw edgeCanNotBeModified(this);
    }

    @Override
    public <I extends YTDBDomainVertex> void remove(YTDBSchemaIndexImpl outVertex, I inVertex) {
      throw edgeCanNotBeModified(this);
    }
  },

  propertyToIndex {
    @Override
    public Iterator<? extends YTDBDomainVertex> apply(YTDBSchemaIndexImpl ytdbSchemaIndex) {
      return ytdbSchemaIndex.propertiesToIndex();
    }

    @Override
    public <I extends YTDBDomainVertex> YTDBDomainEdgeImpl<YTDBSchemaIndexImpl, I> add(
        YTDBSchemaIndexImpl outVertex, I inVertex) {
      throw edgeCanNotBeModified(this);
    }

    @Override
    public <I extends YTDBDomainVertex> void remove(YTDBSchemaIndexImpl outVertex, I inVertex) {
      throw edgeCanNotBeModified(this);
    }
  };

  @Nonnull
  private static UnsupportedOperationException edgeCanNotBeModified(
      YTDBSchemaIndexOutTokenInternal edge) {
    return new UnsupportedOperationException(edge.name()
        + " edge can not be modified. If you need to change it you need to create new index");
  }
}
