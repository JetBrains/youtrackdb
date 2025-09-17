package com.jetbrains.youtrackdb.internal.core.gremlin.domain.tokens;

import com.jetbrains.youtrackdb.api.gremlin.embedded.domain.YTDBDomainVertex;
import com.jetbrains.youtrackdb.api.gremlin.domain.tokens.YTDBPToken;
import java.util.function.Function;

public interface YTDBPTokenInternal<T extends YTDBDomainVertex> extends
    Function<T, Object>, YTDBPToken<T> {
  @Override
  String name();

  Object fetch(T domainObject);

  default void update(T domainObject, Object value) {
    if (!isMutable()) {
      throw new UnsupportedOperationException("Token " + name() + " is read only.");
    }
  }

  Class<?> expectedValueType();

  default boolean isMutable() {
    return false;
  }

  @Override
  default Object apply(T domainObject) {
    return fetch(domainObject);
  }

  default void validateValue(Object value) {
    if (value == null) {
      return;
    }

    var expectedValueType = expectedValueType();
    if (value.getClass().isAssignableFrom(expectedValueType)) {
      return;
    }

    throw new IllegalArgumentException(
        "Passed in value type " + value.getClass() + " does not match expected type "
            + expectedValueType);
  }
}
