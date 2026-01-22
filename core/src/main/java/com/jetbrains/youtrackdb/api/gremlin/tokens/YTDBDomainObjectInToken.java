package com.jetbrains.youtrackdb.api.gremlin.tokens;

import com.jetbrains.youtrackdb.api.gremlin.embedded.YTDBDomainObject;

public interface YTDBDomainObjectInToken<T extends YTDBDomainObject> extends
    YTDBDomainObjectVertexToken<T> {

  interface Exceptions {

    static UnsupportedOperationException trackingOfIncomingEdgesNotSupported(
        YTDBDomainObjectInToken<?> inToken) {
      return new UnsupportedOperationException(
          "Tracking of incoming edges for edge with '" + inToken.getAccessor() +
              "' label is not yet supported");
    }
  }
}
