package com.jetbrains.youtrackdb.internal.lucene.query;

import com.jetbrains.youtrackdb.internal.lucene.collections.LuceneCompositeKey;
import java.util.Map;

/**
 *
 */
public record LuceneKeyAndMetadata(LuceneCompositeKey key, Map<String, ?> metadata) {

}
