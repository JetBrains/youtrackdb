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
 *       sources only; this stage expands, optionally filters neighbours with {@link HasContainer},
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
  /** AND-ed neighbour filters; evaluated with {@link HasContainer#testAll}. */
  private final List<HasContainer> hasContainers;

  public OrderedExpandSliceListShapingOp(
      @Nonnull Direction direction,
      @Nullable String[] edgeLabels,
      long skip,
      long limit,
      @Nullable String propertyKey,
      @Nonnull List<HasContainer> hasContainers) {
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
          if (limit >= 0 && accepted >= limit) {
            exhausted = true;
            return false;
          }
          while (!neighbours.hasNext()) {
            if (!upstream.hasNext()) {
              exhausted = true;
              return false;
            }
            var source = upstream.next();
            if (!(source instanceof Vertex vertex)) {
              throw new IllegalStateException(
                  "OrderedExpandSlice expects Vertex sources, got "
                      + (source == null ? "null" : source.getClass().getName()));
            }
            neighbours = expand(vertex);
          }
          var neighbour = neighbours.next();
          // Native order().hop().has().limit filters before the positional cut — same containers.
          if (!hasContainers.isEmpty() && !HasContainer.testAll(neighbour, hasContainers)) {
            continue;
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

  /** Sentinel for an absent property under drop-on-absent values projection. */
  private static final Object ABSENT = new Object();
}
