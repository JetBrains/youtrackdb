package com.jetbrains.youtrackdb.internal.lucene.query;

import com.jetbrains.youtrackdb.internal.lucene.collections.LuceneCompositeKey;
import java.util.Map;

/**
 *
 */
public class LuceneKeyAndMetadata {

  public final LuceneCompositeKey key;
  public final Map<String, ?> metadata;

  public LuceneKeyAndMetadata(final LuceneCompositeKey key, final Map<String, ?> metadata) {
    this.key = key;
    this.metadata = metadata;
  }
}
