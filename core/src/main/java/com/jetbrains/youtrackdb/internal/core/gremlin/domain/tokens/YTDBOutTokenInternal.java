package com.jetbrains.youtrackdb.internal.core.gremlin.domain.tokens;

import com.jetbrains.youtrackdb.api.gremlin.embedded.domain.YTDBDomainVertex;

public interface YTDBOutTokenInternal<T extends YTDBDomainVertex> extends
    YTDBDomainTokenInternal<T> {
  void add(YTDBDomainVertex inVertex);

  void remove(YTDBDomainVertex outVertex, YTDBDomainVertex inVertex);
}
