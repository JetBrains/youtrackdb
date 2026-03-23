package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.sql.executor.RidFilterDescriptor;
import com.jetbrains.youtrackdb.internal.core.sql.executor.RidSet;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLRid;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import it.unimi.dsi.fastutil.ints.IntSet;
import java.util.List;
import javax.annotation.Nullable;

/**
 * A scheduled traversal of a single {@link PatternEdge} in a specific direction.
 * <p>
 * While {@link PatternEdge} records the **syntactic** direction of an edge as written in
 * the `MATCH` expression, the topological scheduler (see
 * {@code MatchExecutionPlanner#getTopologicalSortedSchedule()}) may decide to traverse the
 * edge in either direction depending on cost estimates and dependency constraints.
 * `EdgeTraversal` captures this decision:
 * <p>
 * - **{@link #out} = `true`**  → traverse from `edge.out` to `edge.in` (forward / as
 *   written).
 * - **{@link #out} = `false`** → traverse from `edge.in` to `edge.out` (reverse).
 *
 * <pre>
 *   Syntactic:     (p) ──out('Knows')──→ (f)
 *                       PatternEdge.out=p, PatternEdge.in=f
 *
 *   Scheduled forward:  EdgeTraversal.out=true   → start at p, traverse to f
 *   Scheduled reverse:  EdgeTraversal.out=false  → start at f, traverse to p
 * </pre>
 *
 * Additionally, the planner annotates each `EdgeTraversal` with the **source node's**
 * class, RID, and `WHERE` filter (the "left" constraints). These are used by
 * {@link MatchReverseEdgeTraverser} to validate the target records when traversing in
 * reverse.
 *
 * @see MatchStep
 * @see MatchEdgeTraverser
 * @see MatchReverseEdgeTraverser
 */
public class EdgeTraversal {

  /**
   * The runtime traversal direction. `true` means forward (from `edge.out` to
   * `edge.in`); `false` means reverse (from `edge.in` to `edge.out`).
   */
  protected boolean out;

  /** The pattern edge being traversed. */
  public PatternEdge edge;

  /** Schema class constraint on the source (left-hand) node, or `null` if unconstrained. */
  private String leftClass;

  /** RID constraint on the source (left-hand) node, or `null` if unconstrained. */
  private SQLRid leftRid;

  /** `WHERE` filter on the source (left-hand) node, or `null` if unconstrained. */
  private SQLWhereClause leftFilter;

  /**
   * Pre-filter descriptor for adjacency list intersection. When set, the
   * traverser resolves this descriptor at runtime to produce a {@link
   * com.jetbrains.youtrackdb.internal.core.sql.executor.RidSet} that is
   * applied to the edge traversal results, skipping vertices not in the set.
   */
  @Nullable private RidFilterDescriptor intersectionDescriptor;

  /**
   * Cached RidSet from the most recent {@link #resolveWithCache} call.
   * Only the absolute cap applies; per-vertex ratio checks are done
   * by the caller.
   */
  @Nullable private RidSet cachedRidSet;

  /** Cache key corresponding to {@link #cachedRidSet}. */
  @Nullable private Object cachedResolveKey;

  /**
   * Collection IDs for the target node's class constraint. When set,
   * the traverser applies a zero-I/O class filter to the link bag,
   * skipping vertices whose collection ID does not match.
   */
  @Nullable private IntSet acceptedCollectionIds;

  /**
   * @param edge the pattern edge to traverse
   * @param out  `true` for forward traversal, `false` for reverse
   */
  public EdgeTraversal(PatternEdge edge, boolean out) {
    assert MatchAssertions.validateEdgeTraversalArgs(edge);
    this.edge = edge;
    this.out = out;
  }

  public void setLeftClass(String leftClass) {
    this.leftClass = leftClass;
  }

  public void setLeftFilter(SQLWhereClause leftFilter) {
    this.leftFilter = leftFilter;
  }

  public String getLeftClass() {
    return leftClass;
  }

  public SQLRid getLeftRid() {
    return leftRid;
  }

  public void setLeftRid(SQLRid leftRid) {
    this.leftRid = leftRid;
  }

  public SQLWhereClause getLeftFilter() {
    return leftFilter;
  }

  @Nullable public RidFilterDescriptor getIntersectionDescriptor() {
    return intersectionDescriptor;
  }

  public void setIntersectionDescriptor(
      @Nullable RidFilterDescriptor intersectionDescriptor) {
    this.intersectionDescriptor = intersectionDescriptor;
  }

  /**
   * Adds a descriptor to this edge. If a descriptor is already set,
   * wraps both in a {@link RidFilterDescriptor.Composite} that intersects
   * their results at the bitmap level.
   */
  public void addIntersectionDescriptor(RidFilterDescriptor descriptor) {
    if (intersectionDescriptor == null) {
      intersectionDescriptor = descriptor;
    } else {
      intersectionDescriptor = new RidFilterDescriptor.Composite(
          List.of(intersectionDescriptor, descriptor));
    }
  }

  @Nullable public IntSet getAcceptedCollectionIds() {
    return acceptedCollectionIds;
  }

  public void setAcceptedCollectionIds(@Nullable IntSet acceptedCollectionIds) {
    this.acceptedCollectionIds = acceptedCollectionIds;
  }

  /**
   * Resolves the intersection descriptor with caching. If the descriptor's
   * {@link RidFilterDescriptor#cacheKey cache key} matches the previous
   * call, the cached RidSet is returned without rebuilding.
   *
   * <p>Resolution uses only the absolute cap ({@link
   * com.jetbrains.youtrackdb.internal.core.sql.executor.TraversalPreFilterHelper#maxRidSetSize()})
   * to bound materialization. The caller must perform a per-vertex
   * {@link com.jetbrains.youtrackdb.internal.core.sql.executor.TraversalPreFilterHelper#passesRatioCheck
   * ratio check} using the actual link bag size.
   *
   * @return the cached or freshly built RidSet, or {@code null} if the
   *     descriptor resolves to too many entries (absolute cap exceeded)
   */
  @Nullable public RidSet resolveWithCache(CommandContext ctx) {
    var desc = intersectionDescriptor;
    if (desc == null) {
      return null;
    }
    var key = desc.cacheKey(ctx);
    if (key != null && key.equals(cachedResolveKey)) {
      return cachedRidSet;
    }
    var ridSet = desc.resolve(ctx);
    if (key != null) {
      cachedResolveKey = key;
      cachedRidSet = ridSet;
    }
    return ridSet;
  }

  @Override
  public String toString() {
    return edge.toString();
  }

  /** Returns a shallow copy with deep-copied mutable fields (filter, RID). */
  public EdgeTraversal copy() {
    var copy = new EdgeTraversal(edge, out);

    if (leftClass != null) {
      copy.leftClass = leftClass;
    }
    if (leftFilter != null) {
      copy.leftFilter = leftFilter.copy();
    }
    if (leftRid != null) {
      copy.leftRid = leftRid.copy();
    }
    copy.intersectionDescriptor = intersectionDescriptor;
    copy.acceptedCollectionIds = acceptedCollectionIds;
    // Cache is intentionally not copied — stale data from a previous
    // execution must not leak into a new plan instance.
    return copy;
  }
}
