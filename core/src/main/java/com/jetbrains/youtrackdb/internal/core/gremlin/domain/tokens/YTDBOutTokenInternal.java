package com.jetbrains.youtrackdb.internal.core.gremlin.domain.tokens;

import com.jetbrains.youtrackdb.api.gremlin.embedded.domain.YTDBDomainVertex;
import com.jetbrains.youtrackdb.internal.core.gremlin.domain.YTDBDomainEdgeImpl;

public interface YTDBOutTokenInternal<O extends YTDBDomainVertex> extends
    YTDBDomainTokenInternal<O> {

  <I extends YTDBDomainVertex> YTDBDomainEdgeImpl<O, I> add(O outVertex, I inVertex);

  <I extends YTDBDomainVertex> void remove(O outVertex, I inVertex);
}
