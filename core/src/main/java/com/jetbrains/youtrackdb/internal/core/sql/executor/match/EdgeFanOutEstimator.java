package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Direction;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.ImmutableSchema;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaClassInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;

/**
 * Estimates the average number of adjacent vertices reachable from one vertex of a
 * given class by traversing edges of a given type in a given direction.
 *
 * <p>Uses {@link SchemaClassInternal#approximateCount(DatabaseSessionEmbedded)}
 * (O(1), polymorphic — includes subclasses) for all cardinality lookups.
 *
 * <p>Direction semantics:
 * <ul>
 *   <li><b>OUT</b>: each source vertex has on average
 *       {@code edgeCount / sourceCount} outgoing edges.</li>
 *   <li><b>IN</b>: each source vertex has on average
 *       {@code edgeCount / sourceCount} incoming edges.</li>
 *   <li><b>BOTH</b>: the source vertex participates as both OUT and IN endpoint.
 *       Fan-out is the sum of outgoing and incoming estimates, but each direction
 *       only contributes if the source class is (or extends) the vertex class on
 *       that side of the edge. This avoids overestimation for directed edges where
 *       the source class only appears on one end (e.g., {@code Person→Works→Company}
 *       from Person: OUT fan-out = {@code edgeCount / personCount}, IN fan-out = 0
 *       because Person is not the IN vertex of Works).</li>
 * </ul>
 *
 * @see MatchExecutionPlanner
 */
public final class EdgeFanOutEstimator {

  /**
   * Returns the default fan-out when schema metadata is unavailable (e.g.,
   * edge or source class not found). Reads from
   * {@link GlobalConfiguration#QUERY_STATS_DEFAULT_FAN_OUT} at each call so
   * runtime changes take effect without restart.
   */
  static double defaultFanOut() {
    return GlobalConfiguration.QUERY_STATS_DEFAULT_FAN_OUT
        .getValueAsDouble();
  }

  private EdgeFanOutEstimator() {
  }

  /**
   * Estimates the average fan-out for traversing edges of {@code edgeClassName}
   * from a vertex of {@code sourceClassName} in the given direction.
   *
   * @param session          database session for schema and count access
   * @param edgeClassName    edge class name (e.g., "Knows")
   * @param sourceClassName  vertex class we are traversing FROM — the class whose
   *                         count becomes the denominator. Caller resolves this
   *                         based on traversal direction:
   *                         forward ({@code EdgeTraversal.out=true}) →
   *                         {@code PatternEdge.out}'s class;
   *                         reverse ({@code EdgeTraversal.out=false}) →
   *                         {@code PatternEdge.in}'s class
   * @param direction        syntactic direction from the MATCH pattern
   *                         (OUT, IN, BOTH)
   * @param outVertexClass   the OUT vertex class of the edge type (from schema),
   *                         or {@code null} if unknown
   * @param inVertexClass    the IN vertex class of the edge type (from schema),
   *                         or {@code null} if unknown
   * @return estimated average number of target vertices per source vertex,
   *         or the value from
   *         {@link GlobalConfiguration#QUERY_STATS_DEFAULT_FAN_OUT}
   *         if schema metadata is unavailable
   */
  public static double estimateFanOut(
      DatabaseSessionEmbedded session,
      String edgeClassName,
      String sourceClassName,
      Direction direction,
      String outVertexClass,
      String inVertexClass) {
    assert MatchAssertions.checkNotNull(session, "session");
    assert MatchAssertions.checkNotNull(direction, "direction");

    var schema = session.getMetadata().getImmutableSchemaSnapshot();
    if (schema == null) {
      return defaultFanOut();
    }

    var edgeClass = edgeClassName != null
        ? schema.getClassInternal(edgeClassName) : null;
    var sourceClass = sourceClassName != null
        ? schema.getClassInternal(sourceClassName) : null;

    if (edgeClass == null || sourceClass == null) {
      return defaultFanOut();
    }

    long edgeCount = edgeClass.approximateCount(session);
    long sourceCount = sourceClass.approximateCount(session);

    if (sourceCount == 0) {
      return 0.0;
    }

    if (direction == Direction.BOTH) {
      return estimateBothFanOut(
          session, schema, edgeCount, sourceClassName,
          outVertexClass, inVertexClass);
    }

    return (double) edgeCount / sourceCount;
  }

  /**
   * For BOTH direction, computes OUT and IN fan-out separately using the actual
   * vertex classes from the edge schema. Each side only contributes if the source
   * class is (or extends) the vertex class on that side.
   */
  private static double estimateBothFanOut(
      DatabaseSessionEmbedded session,
      ImmutableSchema schema,
      long edgeCount,
      String sourceClassName,
      String outVertexClass,
      String inVertexClass) {
    double outFanOut = 0.0;
    double inFanOut = 0.0;

    if (outVertexClass != null && isSubclassOrEqual(schema, sourceClassName,
        outVertexClass)) {
      var outClass = schema.getClassInternal(outVertexClass);
      if (outClass != null) {
        long outCount = outClass.approximateCount(session);
        outFanOut = outCount > 0 ? (double) edgeCount / outCount : 0.0;
      }
    }

    if (inVertexClass != null && isSubclassOrEqual(schema, sourceClassName,
        inVertexClass)) {
      var inClass = schema.getClassInternal(inVertexClass);
      if (inClass != null) {
        long inCount = inClass.approximateCount(session);
        inFanOut = inCount > 0 ? (double) edgeCount / inCount : 0.0;
      }
    }

    return outFanOut + inFanOut;
  }

  /**
   * Returns {@code true} if {@code className} is the same as, or a subclass of,
   * {@code potentialSuperClass}.
   */
  private static boolean isSubclassOrEqual(
      ImmutableSchema schema,
      String className,
      String potentialSuperClass) {
    SchemaClass clazz = schema.getClass(className);
    return clazz != null && clazz.isSubClassOf(potentialSuperClass);
  }
}
