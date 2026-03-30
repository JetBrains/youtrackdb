package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

/**
 * Specifies the join semantics for {@link HashJoinMatchStep}.
 *
 * <ul>
 *   <li>{@link #ANTI_JOIN} — discard upstream rows whose key exists in the build side
 *       (used for NOT patterns)</li>
 *   <li>{@link #SEMI_JOIN} — keep upstream rows whose key exists in the build side
 *       (used for EXISTS-style filters)</li>
 *   <li>{@link #INNER_JOIN} — enrich upstream rows with matching build-side rows
 *       (used for multi-branch MATCH joins; not yet implemented — deferred to Track 4)</li>
 * </ul>
 */
enum JoinMode {
  ANTI_JOIN, SEMI_JOIN, INNER_JOIN
}
