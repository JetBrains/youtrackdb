package com.jetbrains.youtrackdb.api.gremlin.tokens;

import com.jetbrains.youtrackdb.api.gremlin.embedded.YTDBDomainObject;
import java.util.Iterator;
import java.util.function.Function;
import org.apache.tinkerpop.gremlin.structure.Vertex;

public interface YTDBDomainObjectVertexToken<T extends YTDBDomainObject> extends
    Function<T, Iterator<Vertex>> {

  String name();

  default String getAccessor() {
    return name();
  }
}
