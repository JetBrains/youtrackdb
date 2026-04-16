package com.jetbrains.youtrackdb.internal.core.command;

import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import java.util.List;
import java.util.Objects;

/**
 * Immutable key for {@link TraversalCache}. Uniquely identifies a graph traversal result by the
 * source entity, the traversal function name (e.g. "out", "in", "both"), and the edge-class labels
 * passed as arguments.
 *
 * <p>The labels list is defensively copied to an unmodifiable list in the compact constructor, so
 * callers cannot break HashMap invariants by mutating the list after key construction.
 */
public record TraversalCacheKey(RID sourceRid, String functionName, List<String> labels) {

  public TraversalCacheKey {
    labels = List.copyOf(labels);
  }

  /**
   * Converts raw parameter values (edge-class label arguments) to a list of strings for use as the
   * labels component of a cache key. Null entries (no label filter) are omitted so that {@code
   * out()} and {@code out(null)} produce the same key.
   */
  public static List<String> toStringLabels(List<Object> paramValues) {
    return paramValues.stream()
        .filter(Objects::nonNull)
        .map(Object::toString)
        .toList();
  }
}
