package com.jetbrains.youtrackdb.internal.core.gremlin.domain.tokens;

import com.jetbrains.youtrackdb.api.gremlin.embedded.domain.YTDBDomainVertex;
import java.util.Iterator;
import java.util.function.Function;
import org.apache.tinkerpop.gremlin.structure.Vertex;

public interface YTDBDomainTokenInternal<T extends YTDBDomainVertex> extends
    Function<T, Iterator<Vertex>> {
  String name();
}
