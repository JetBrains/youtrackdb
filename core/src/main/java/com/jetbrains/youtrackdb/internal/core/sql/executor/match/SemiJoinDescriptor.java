package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

/**
 * Planner artifact describing a back-reference semi-join optimization. Attached to an
 * {@link EdgeTraversal} during {@code optimizeScheduleWithIntersections()} when the planner
 * detects a back-reference pattern ({@code @rid = $matched.X.@rid}) that qualifies for
 * hash-based evaluation.
 *
 * <p>Three variants cover the recognized patterns:
 * <ul>
 *   <li>{@link SingleEdgeSemiJoin} — Pattern A: single vertex-level edge with equality
 *       back-reference on the target.</li>
 *   <li>{@link ChainSemiJoin} — Pattern B: {@code .outE('E').inV()} chain where the
 *       {@code .inV()} target has the back-reference. Collapses two schedule edges into
 *       one {@link BackRefHashJoinStep}.</li>
 *   <li>{@link AntiSemiJoin} — Pattern D: {@code $currentMatch NOT IN $matched.X.out('E')}
 *       condition in a WHERE clause.</li>
 * </ul>
 *
 * <p>When a {@code SemiJoinDescriptor} is attached to an edge, no
 * {@link com.jetbrains.youtrackdb.internal.core.sql.executor.RidFilterDescriptor.EdgeRidLookup
 * EdgeRidLookup} is created for the same back-reference — they are mutually exclusive.
 *
 * @see BackRefHashJoinStep
 */
public sealed interface SemiJoinDescriptor
    permits SingleEdgeSemiJoin, ChainSemiJoin, AntiSemiJoin {

  /** The join mode: SEMI (keep on hit) or ANTI (discard on hit). */
  JoinMode joinMode();

  /** The alias whose value is used to build the hash table. */
  String backRefAlias();

  /** The alias whose binding is produced or validated by the hash join. */
  String targetAlias();
}
