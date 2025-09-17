package com.jetbrains.youtrackdb.api.gremlin.domain.tokens;

import com.jetbrains.youtrackdb.api.gremlin.embedded.domain.YTDBDomainVertex;

public interface YTDBDomainToken<T extends YTDBDomainVertex> {
  String name();
}
