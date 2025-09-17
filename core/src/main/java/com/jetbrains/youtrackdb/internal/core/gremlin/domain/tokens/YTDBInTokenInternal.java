package com.jetbrains.youtrackdb.internal.core.gremlin.domain.tokens;

import com.jetbrains.youtrackdb.api.gremlin.embedded.domain.YTDBDomainVertex;

public interface YTDBInTokenInternal<T extends YTDBDomainVertex> extends
    YTDBDomainTokenInternal<T> {
  interface Exceptions {
    static UnsupportedOperationException trackingOfIncomingEdgesNotSupported(
        YTDBInTokenInternal<?> inToken) {
      return new UnsupportedOperationException(
          "Tracking of incoming edges for edge with '" + inToken.name() +
              "' label is not yet supported");
    }
  }
}
