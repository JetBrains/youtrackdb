package com.jetbrains.youtrackdb.internal.core.gremlin.translator.step;

import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.HasContainer;
import org.apache.tinkerpop.gremlin.structure.Direction;
import org.apache.tinkerpop.gremlin.structure.Vertex;

/**
 * Post-plan stage for ordered expand:
 * <ul>
 *   <li>{@code order().by(...).out|in|both(...).[has...].limit|range} — MATCH returns sorted
 *       sources only; this stage expands, optionally filters neighbours with native YTDB predicates,
 *       then applies the positional cut;
 *   <li>{@code order().by(...).limit|skip|range.out|in|both(...).[has...]} — MATCH applies statement
 *       top-N on sources; this stage expands (and optionally filters / projects) with unbounded
 *       cut ({@code limit == -1}).
 * </ul>
 *
 * <p>Matching native membership (including mid-hub cuts on the hop-then-slice spelling).
 *
 * <p>Optional {@code values(key)} after the hop/slice is folded in: the cut counts neighbour vertices
 * that survived {@code has} (as native {@code .has().limit(n).values(k)} does), then absent
 * properties drop without filling the quota from later neighbours.
 *
 * <h2>Per-call state</h2>
 *
 * Iterators and counters live inside the returned iterator, not in fields — same contract as
 * {@link TailListShapingOp}: {@link ListShapingOp#apply} may run again after {@code reset()}, and
 * cloned steps share this instance by reference.
 */
public final class OrderedExpandSliceListShapingOp implements ListShapingOp {

  private final Direction direction;
  /** Edge labels for the hop, or {@code null} for all edge types. */
  @Nullable private final String[] edgeLabels;
  private final long skip;
  /** Rows to emit after skip; {@code -1} means unbounded (skip-only). */
  private final long limit;
  /** When non-null, emit that property of each neighbour (drop when absent), else emit the vertex. */
  @Nullable private final String propertyKey;
  /** Original containers retained for cache identity and future literal binding. */
  private final List<HasContainer> hasContainers;
  private final boolean polymorphic;
  /** Each size preserves one native HasStep boundary for label OR/step AND. */
  private final List<Integer> hasStepSizes;
  /** Native label predicates combine with OR within their step, then steps combine with AND. */
  private final List<NeighbourFilter> filters;

  public OrderedExpandSliceListShapingOp(
      @Nonnull Direction direction,
      @Nullable String[] edgeLabels,
      long skip,
      long limit,
      @Nullable String propertyKey,
      @Nonnull List<HasContainer> hasContainers,
      @Nonnull List<Integer> hasStepSizes,
      boolean polymorphic) {
    if (skip < 0) {
      throw new IllegalArgumentException("skip must not be negative: " + skip);
    }
    if (limit < -1) {
      throw new IllegalArgumentException("limit must be >= -1: " + limit);
    }
    this.direction = direction;
    this.edgeLabels = edgeLabels == null ? null : edgeLabels.clone();
    this.skip = skip;
    this.limit = limit;
    this.propertyKey = propertyKey;
    this.hasContainers = List.copyOf(hasContainers);
    this.polymorphic = polymorphic;
    this.hasStepSizes = List.copyOf(hasStepSizes);
    this.filters = NeighbourFilter.fromContainers(
        this.hasContainers, this.hasStepSizes, polymorphic);
  }

  /** A direct caller supplies one HasStep worth of filters. */
  public OrderedExpandSliceListShapingOp(
      @Nonnull Direction direction,
      @Nullable String[] edgeLabels,
      long skip,
      long limit,
      @Nullable String propertyKey,
      @Nonnull List<HasContainer> hasContainers,
      boolean polymorphic) {
    this(direction, edgeLabels, skip, limit, propertyKey, hasContainers,
        List.of(hasContainers.size()), polymorphic);
  }

  /** Direct callers without a traversal use exact-label semantics. */
  public OrderedExpandSliceListShapingOp(
      @Nonnull Direction direction,
      @Nullable String[] edgeLabels,
      long skip,
      long limit,
      @Nullable String propertyKey,
      @Nonnull List<HasContainer> hasContainers) {
    this(direction, edgeLabels, skip, limit, propertyKey, hasContainers, false);
  }

  @Nonnull
  public Direction direction() {
    return direction;
  }

  @Nullable public String[] edgeLabels() {
    return edgeLabels == null ? null : edgeLabels.clone();
  }

  public long skip() {
    return skip;
  }

  public long limit() {
    return limit;
  }

  @Nullable public String propertyKey() {
    return propertyKey;
  }

  @Nonnull
  public List<HasContainer> hasContainers() {
    return hasContainers;
  }

  public boolean polymorphic() {
    return polymorphic;
  }

  public List<Integer> hasStepSizes() {
    return hasStepSizes;
  }

  @Override
  public Iterator<Object> apply(Iterator<Object> upstream) {
    return new Iterator<>() {
      private Iterator<Vertex> neighbours = java.util.Collections.emptyIterator();
      private long dropped;
      /** Neighbours accepted past skip toward the limit quota (after has, before property drop). */
      private long accepted;
      private Object buffered;
      private boolean hasBuffered;
      private boolean exhausted;

      @Override
      public boolean hasNext() {
        if (hasBuffered) {
          return true;
        }
        if (exhausted) {
          return false;
        }
        while (true) {
          while (!neighbours.hasNext()) {
            if (!upstream.hasNext()) {
              exhausted = true;
              return false;
            }
            // VertexStep casts each payload to Vertex before expanding it. Projected select
            // scalars/maps must raise the same ClassCastException, while a nonproductive select
            // has already dropped its source in the boundary projection.
            neighbours = expand((Vertex) upstream.next());
          }
          var neighbour = neighbours.next();
          // Native label and collated-property checks run before the positional cut.
          if (!matches(neighbour)) {
            continue;
          }
          // Native RangeGlobalStep is a FilterStep: it pulls the next surviving traverser
          // before checking its high bound. Even an empty cut must expand (and cast) first.
          if (limit >= 0 && accepted >= limit) {
            exhausted = true;
            return false;
          }
          if (dropped < skip) {
            dropped++;
            continue;
          }
          accepted++;
          var payload = project(neighbour);
          if (payload == ABSENT) {
            // Native limit(n).values(k) still consumes the vertex slot, then drops absent keys.
            continue;
          }
          buffered = payload;
          hasBuffered = true;
          return true;
        }
      }

      @Override
      public Object next() {
        if (!hasBuffered && !hasNext()) {
          throw new NoSuchElementException();
        }
        var payload = buffered;
        buffered = null;
        hasBuffered = false;
        return payload;
      }

      private Iterator<Vertex> expand(Vertex vertex) {
        if (edgeLabels == null) {
          return vertex.vertices(direction);
        }
        return vertex.vertices(direction, edgeLabels);
      }

      private Object project(Vertex neighbour) {
        if (propertyKey == null) {
          return neighbour;
        }
        var property = neighbour.property(propertyKey);
        if (!property.isPresent()) {
          return ABSENT;
        }
        return property.value();
      }
    };
  }

  /** Stop at the first failed filter, as native HasStep does for collated property containers. */
  private boolean matches(Vertex neighbour) {
    for (var filter : filters) {
      if (!filter.test(neighbour)) {
        return false;
      }
    }
    return true;
  }

  /** Sentinel for an absent property under drop-on-absent values projection. */
  private static final Object ABSENT = new Object();
}
