package com.jetbrains.youtrackdb.internal.core.gql.executor.resultset;

import java.util.Map;
import java.util.function.Function;

/// Stream that maps results using a function.
public final class MappingGqlExecutionStream implements GqlExecutionStream {

  private final GqlExecutionStream source;
  private final Function<Map<String, Object>, Map<String, Object>> mapper;

  public MappingGqlExecutionStream(
      GqlExecutionStream source,
      Function<Map<String, Object>, Map<String, Object>> mapper) {
    this.source = source;
    this.mapper = mapper;
  }

  @Override
  public boolean hasNext() {
    return source.hasNext();
  }

  @Override
  public Map<String, Object> next() {
    return mapper.apply(source.next());
  }

  @Override
  public void close() {
    source.close();
  }
}
