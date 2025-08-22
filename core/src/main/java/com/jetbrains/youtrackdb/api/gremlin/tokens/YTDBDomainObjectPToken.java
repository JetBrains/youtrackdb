package com.jetbrains.youtrackdb.api.gremlin.tokens;

import com.jetbrains.youtrackdb.api.gremlin.embedded.YTDBDomainObject;
import java.util.function.Function;

public interface YTDBDomainObjectPToken<T extends YTDBDomainObject> extends Function<T, Object> {

  default String getAccessor() {
    return name();
  }

  String name();

  Object fetch(T domainObject);

  default void update(T domainObject, Object value) {
    if (!isMutableInGraphTraversal()) {
      throw new UnsupportedOperationException("Token " + getAccessor() + " is read only.");
    }

    throw new UnsupportedOperationException("Token " + getAccessor()
        + " is read only, use dedicated GraphTraversal steps to change its value.");
  }

  Class<?> expectedValueType();

  default boolean isMutableInGraphTraversal() {
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
