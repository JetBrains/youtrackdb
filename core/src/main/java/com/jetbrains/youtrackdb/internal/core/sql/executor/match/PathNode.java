package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import com.jetbrains.youtrackdb.internal.core.query.Result;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Immutable cons-cell list representing the path of records visited during a recursive
 * MATCH WHILE traversal. Each node holds a single {@link Result}, a pointer to the
 * previous node, and the current depth — sharing structure with all ancestor paths.
 *
 * <p>This replaces the previous pattern of copying an {@code ArrayList<Result>} at each
 * recursion level. Appending a new element is O(1) instead of O(depth), and paths that
 * share a common prefix share the same node chain in memory.
 *
 * <p>Materialization to a {@code List<Result>} (required when the user declares a
 * {@code pathAlias}) is deferred to {@link #toList()}, which is only called when the
 * path is actually needed.
 */
record PathNode(@Nonnull Result value, @Nullable PathNode prev, int depth) {

  /**
   * Materializes the path into an unmodifiable {@link List} in traversal order
   * (oldest element first). Only called when the user declares a {@code pathAlias}
   * and the path must be exposed as a property.
   */
  List<Result> toList() {
    var results = new Result[depth + 1];
    var current = this;
    for (int i = depth; i >= 0; i--) {
      results[i] = current.value;
      current = current.prev;
    }
    return List.of(results);
  }

  /**
   * Returns an empty immutable list, used when the path is empty (depth 0, no
   * predecessors). This avoids allocating a PathNode node for the starting point
   * which has no path yet.
   */
  static List<Result> emptyPath() {
    return Collections.emptyList();
  }
}
