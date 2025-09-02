package com.jetbrains.youtrackdb.internal.lucene.collections;

import com.jetbrains.youtrackdb.api.record.RID;
import com.jetbrains.youtrackdb.internal.common.util.RawPair;
import java.util.stream.Stream;

/**
 *
 */
public final class LuceneIndexTransformer {

  public static Stream<RawPair<Object, RID>> transformToStream(
      LuceneResultSet resultSet, Object key) {
    return resultSet.stream()
        .map((identifiable -> new RawPair<>(key, identifiable.getIdentity())));
  }
}
