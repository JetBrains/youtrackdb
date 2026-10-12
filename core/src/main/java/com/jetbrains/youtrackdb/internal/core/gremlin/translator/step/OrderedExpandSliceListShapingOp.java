package com.jetbrains.youtrackdb.internal.core.gremlin.translator.step;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.apache.tinkerpop.gremlin.process.traversal.TraversalSideEffects;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.HasContainer;
import org.apache.tinkerpop.gremlin.structure.Direction;
import org.apache.tinkerpop.gremlin.structure.Vertex;

/**
 * Post-plan execution of a sliced ordered hop. MATCH returns sorted source rows and labelled-path
 * identities. The source-merge stage combines only native-equal traversers, subject to sack
 * mergeability. Each following stage runs in traversal order over bulk groups: a source slice
 * precedes select projection and expansion, while a post-hop slice follows the admitted filters
 * and barriers at its native position. Barriers retain their distinct-entry window sizes and
 * first-seen order. Each {@link #apply} owns its iterators and counters, including after reset and
 * reopen.
 */
public final class OrderedExpandSliceListShapingOp implements ListShapingOp {

  private final List<OrderedHopStage> stages;
  private final Direction direction;
  @Nullable private final String[] edgeLabels;
  private final long skip;
  private final long limit;
  @Nullable private final String propertyKey;
  private final List<HasContainer> hasContainers;
  private final List<Integer> hasStepSizes;
  private final boolean polymorphic;
  private final OrderedHopStage.MergeKey mergeKey;

  /** The translator supplies the native-order stages, including the source merge. */
  public OrderedExpandSliceListShapingOp(@Nonnull List<OrderedHopStage> stages) {
    this.stages = List.copyOf(stages);
    if (stages.isEmpty() || !(stages.getFirst() instanceof OrderedHopStage.SourceMerge merge)) {
      throw new IllegalArgumentException("The first ordered-hop stage must merge sources");
    }
    mergeKey = merge.key();
    var expand = stages.stream().filter(OrderedHopStage.Expand.class::isInstance)
        .map(OrderedHopStage.Expand.class::cast).findFirst().orElseThrow();
    direction = expand.direction();
    edgeLabels = expand.edgeLabels();
    var slice = stages.stream().filter(OrderedHopStage.Slice.class::isInstance)
        .map(OrderedHopStage.Slice.class::cast).findFirst().orElse(null);
    skip = slice == null ? 0 : slice.skip();
    limit = slice == null ? -1 : slice.limit();
    propertyKey = stages.stream().filter(OrderedHopStage.Values.class::isInstance)
        .map(OrderedHopStage.Values.class::cast).map(OrderedHopStage.Values::propertyKey)
        .findFirst().orElse(null);
    var containers = new ArrayList<HasContainer>();
    var sizes = new ArrayList<Integer>();
    boolean poly = false;
    for (var stage : stages) {
      if (stage instanceof OrderedHopStage.Filter filter) {
        containers.addAll(filter.containers());
        sizes.add(filter.containers().size());
        poly = filter.polymorphic();
      }
    }
    hasContainers = List.copyOf(containers);
    hasStepSizes = List.copyOf(sizes);
    polymorphic = poly;
  }

  /** Builds one filter stage per native HasStep. An unbounded cut has no slice stage. */
  public static List<OrderedHopStage> stagesFor(
      Direction direction, @Nullable String[] edgeLabels, long skip, long limit,
      @Nullable String propertyKey, List<HasContainer> containers, List<Integer> stepSizes,
      boolean polymorphic, OrderedHopStage.MergeKey mergeKey) {
    if (skip < 0) {
      throw new IllegalArgumentException("skip must not be negative: " + skip);
    }
    if (limit < -1) {
      throw new IllegalArgumentException("limit must be >= -1: " + limit);
    }
    var stages = new ArrayList<OrderedHopStage>();
    stages.add(new OrderedHopStage.SourceMerge(mergeKey));
    stages.add(new OrderedHopStage.Expand(direction, edgeLabels));
    int offset = 0;
    for (int size : stepSizes) {
      if (size < 0 || offset + size > containers.size()) {
        throw new IllegalArgumentException("Invalid ordered-hop HasStep size: " + size);
      }
      stages
          .add(new OrderedHopStage.Filter(containers.subList(offset, offset + size), polymorphic));
      offset += size;
    }
    if (offset != containers.size()) {
      throw new IllegalArgumentException("HasStep sizes must cover every neighbour container");
    }
    if (skip != 0 || limit >= 0) {
      stages.add(new OrderedHopStage.Slice(skip, limit));
    }
    if (propertyKey != null) {
      stages.add(new OrderedHopStage.Values(propertyKey));
    }
    return List.copyOf(stages);
  }

  public OrderedExpandSliceListShapingOp(
      @Nonnull Direction direction, @Nullable String[] edgeLabels, long skip, long limit,
      @Nullable String propertyKey, @Nonnull List<HasContainer> hasContainers,
      @Nonnull List<Integer> hasStepSizes, boolean polymorphic) {
    this(stagesFor(direction, edgeLabels, skip, limit, propertyKey, hasContainers, hasStepSizes,
        polymorphic, OrderedHopStage.MergeKey.ELEMENT));
  }

  public OrderedExpandSliceListShapingOp(
      @Nonnull Direction direction, @Nullable String[] edgeLabels, long skip, long limit,
      @Nullable String propertyKey, @Nonnull List<HasContainer> hasContainers,
      boolean polymorphic) {
    this(direction, edgeLabels, skip, limit, propertyKey, hasContainers,
        List.of(hasContainers.size()), polymorphic);
  }

  public OrderedExpandSliceListShapingOp(
      @Nonnull Direction direction, @Nullable String[] edgeLabels, long skip, long limit,
      @Nullable String propertyKey, @Nonnull List<HasContainer> hasContainers) {
    this(direction, edgeLabels, skip, limit, propertyKey, hasContainers, false);
  }

  @Nonnull
  public List<OrderedHopStage> stages() {
    return stages;
  }

  public OrderedHopStage.MergeKey mergeKey() {
    return mergeKey;
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

  public List<Integer> hasStepSizes() {
    return hasStepSizes;
  }

  public boolean polymorphic() {
    return polymorphic;
  }

  @Override
  public Iterator<Object> apply(Iterator<Object> upstream) {
    return apply(upstream, null);
  }

  @Override
  public Iterator<Object> apply(Iterator<Object> upstream, TraversalSideEffects effects) {
    Supplier<Object> supplier = effects == null ? null : effects.getSackInitialValue();
    UnaryOperator<Object> splitter = effects == null ? null : effects.getSackSplitter();
    var sourceMerge = (OrderedHopStage.SourceMerge) stages.getFirst();
    Iterator<Group> groups = new Iterator<>() {
      @Override
      public boolean hasNext() {
        return upstream.hasNext();
      }

      @Override
      public Group next() {
        var value = upstream.next();
        Object sack = sourceMerge.sackGated() && supplier != null ? supplier.get() : null;
        for (int i = 0; i < sourceMerge.preOrderSplits(); i++) {
          sack = splitSack(sack, splitter);
        }
        return value instanceof OrderedSourceRow row
            ? new Group(row, row.source(), row.path(), 1, sack)
            : new Group(value, 1, sack);
      }
    };
    for (var stage : stages) {
      groups = switch (stage) {
        case OrderedHopStage.SourceMerge merge -> mergeSources(groups, merge);
        case OrderedHopStage.Project project -> project(groups, project, splitter);
        case OrderedHopStage.Expand expand -> expand(groups, expand, splitter);
        case OrderedHopStage.Filter filter -> filter(groups, filter);
        case OrderedHopStage.Slice slice -> slice(groups, slice);
        case OrderedHopStage.Barrier barrier -> barrier(groups, barrier);
        case OrderedHopStage.Values values -> values(groups, values.propertyKey());
      };
    }
    Iterator<Group> result = groups;
    return new Iterator<>() {
      private Group current;
      private long remaining;

      @Override
      public boolean hasNext() {
        if (remaining > 0) {
          return true;
        }
        if (!result.hasNext()) {
          return false;
        }
        current = result.next();
        remaining = current.bulk();
        return true;
      }

      @Override
      public Object next() {
        if (!hasNext()) {
          throw new NoSuchElementException();
        }
        remaining--;
        return current.payload();
      }
    };
  }

  private record Group(Object payload, Object source, List<Object> path, long bulk, Object sack) {

    Group(Object payload, long bulk, Object sack) {
      this(payload, payload, List.of(), bulk, sack);
    }

    Group withPayload(Object value) {
      return new Group(value, source, path, bulk, sack);
    }

    Group withBulk(long value) {
      return new Group(payload, source, path, value, sack);
    }
  }

  private static Object splitSack(Object sack, UnaryOperator<Object> splitter) {
    return sack != null && splitter != null ? splitter.apply(sack) : sack;
  }

  private record BarrierIdentity(Object payload, Object path) {
  }

  private static Iterator<Group> project(Iterator<Group> upstream,
      OrderedHopStage.Project project, UnaryOperator<Object> splitter) {
    return selecting(upstream, group -> {
      if (!(group.payload() instanceof OrderedSourceRow row)) {
        return group;
      }
      var projection = row.projection().get();
      if (!projection.productive()) {
        return null;
      }
      return new Group(projection.payload(), group.source(), group.path(), group.bulk(),
          project.splits() ? splitSack(group.sack(), splitter) : group.sack());
    });
  }

  /** RID tie-breaking makes equal sorted elements adjacent, so only a single run is buffered. */
  private static Iterator<Group> mergeSources(
      Iterator<Group> upstream, OrderedHopStage.SourceMerge stage) {
    var key = stage.key();
    if (key == OrderedHopStage.MergeKey.NONE) {
      return upstream;
    }
    return new Iterator<>() {
      private Group pending;
      private Iterator<Group> run = Collections.emptyIterator();

      @Override
      public boolean hasNext() {
        return run.hasNext() || pending != null || upstream.hasNext();
      }

      @Override
      public Group next() {
        if (!hasNext()) {
          throw new NoSuchElementException();
        }
        if (!run.hasNext()) {
          var first = pending != null ? pending : upstream.next();
          pending = null;
          // RID tie-breaking makes equal sources adjacent. Within a source run, preserve the
          // first-seen order of distinct paths while merging native-equal traversers.
          var merged = new LinkedHashMap<Object, Group>();
          merged.put(sourceIdentity(first, stage), first);
          while (upstream.hasNext()) {
            var next = upstream.next();
            if (!Objects.equals(first.source(), next.source())) {
              pending = next;
              break;
            }
            Object identity = sourceIdentity(next, stage);
            var old = merged.get(identity);
            merged.put(identity, old == null ? next : old.withBulk(old.bulk() + next.bulk()));
          }
          run = merged.values().iterator();
        }
        return run.next();
      }
    };
  }

  private static Object sourceIdentity(Group group, OrderedHopStage.SourceMerge stage) {
    if (stage.sackGated() && group.sack() != null) {
      return new Object();
    }
    return stage.key() == OrderedHopStage.MergeKey.ELEMENT_AND_PATH
        ? group.path() : group.source();
  }

  /** VertexStep splits one source group into one group per neighbour, retaining its bulk. */
  private static Iterator<Group> expand(Iterator<Group> upstream, OrderedHopStage.Expand hop,
      UnaryOperator<Object> splitter) {
    return new Iterator<>() {
      private Iterator<Vertex> neighbours = Collections.emptyIterator();
      private long bulk;
      private Object source;
      private List<Object> path;
      private Object sack;

      @Override
      public boolean hasNext() {
        while (!neighbours.hasNext() && upstream.hasNext()) {
          var group = upstream.next();
          bulk = group.bulk();
          source = group.source();
          path = group.path();
          sack = group.sack();
          // Native VertexStep casts before asking for neighbours, even if the next slice is empty.
          Vertex vertex = (Vertex) group.payload();
          neighbours = hop.edgeLabels() == null
              ? vertex.vertices(hop.direction())
              : vertex.vertices(hop.direction(), hop.edgeLabels());
        }
        return neighbours.hasNext();
      }

      @Override
      public Group next() {
        if (!hasNext()) {
          throw new NoSuchElementException();
        }
        return new Group(neighbours.next(), source, path, bulk, splitSack(sack, splitter));
      }
    };
  }

  private static Iterator<Group> filter(Iterator<Group> upstream, OrderedHopStage.Filter stage) {
    var predicates = NeighbourFilter.fromStep(stage.containers(), stage.polymorphic());
    return selecting(upstream, group -> {
      for (var predicate : predicates) {
        if (!predicate.test((Vertex) group.payload())) {
          return null;
        }
      }
      return group;
    });
  }

  /** RangeGlobalStep pulls a group before checking high, even after the quota is consumed. */
  private static Iterator<Group> slice(Iterator<Group> upstream, OrderedHopStage.Slice range) {
    return new Iterator<>() {
      private long seen;
      private Group buffered;
      private boolean exhausted;

      @Override
      public boolean hasNext() {
        if (buffered != null) {
          return true;
        }
        if (exhausted) {
          return false;
        }
        long high = range.limit() < 0 ? -1 : range.skip() + range.limit();
        while (upstream.hasNext()) {
          var group = upstream.next();
          if (high >= 0 && seen >= high) {
            exhausted = true;
            return false;
          }
          long skipped = seen < range.skip()
              ? Math.min(group.bulk(), range.skip() - seen) : 0;
          long selected = group.bulk() - skipped;
          if (high >= 0) {
            selected = Math.min(selected, high - seen - skipped);
          }
          // Native advances by skipped + emitted bulk, not by the trimmed part of a group.
          seen += skipped + selected;
          if (selected > 0) {
            buffered = group.withBulk(selected);
            return true;
          }
        }
        exhausted = true;
        return false;
      }

      @Override
      public Group next() {
        if (!hasNext()) {
          throw new NoSuchElementException();
        }
        var result = buffered;
        buffered = null;
        return result;
      }
    };
  }

  /** A barrier fills one window of distinct traversers, then emits merged groups in insertion order. */
  private static Iterator<Group> barrier(
      Iterator<Group> upstream, OrderedHopStage.Barrier stage) {
    return new Iterator<>() {
      private Iterator<Group> window = Collections.emptyIterator();

      @Override
      public boolean hasNext() {
        if (window.hasNext()) {
          return true;
        }
        if (!upstream.hasNext()) {
          return false;
        }
        var merged = new LinkedHashMap<Object, Group>();
        int ordinal = 0;
        while (merged.size() < stage.maxSize() && upstream.hasNext()) {
          var group = upstream.next();
          // NONE gives every traverser a distinct identity, even when its payload is equal.
          Object key = stage.sackGated() && group.sack() != null ? ordinal++
              : switch (stage.key()) {
                case NONE -> ordinal++;
                case ELEMENT -> group.payload();
                case ELEMENT_AND_PATH -> new BarrierIdentity(group.payload(),
                    stage.pathIndexes().isEmpty() ? group.path()
                        : stage.pathIndexes().stream().map(group.path()::get).toList());
              };
          var old = merged.get(key);
          merged.put(key,
              old == null ? group : old.withBulk(old.bulk() + group.bulk()));
        }
        window = merged.values().iterator();
        return window.hasNext();
      }

      @Override
      public Group next() {
        if (!hasNext()) {
          throw new NoSuchElementException();
        }
        return window.next();
      }
    };
  }

  private static Iterator<Group> values(Iterator<Group> upstream, String key) {
    return selecting(upstream, group -> {
      var property = ((Vertex) group.payload()).property(key);
      return property.isPresent() ? group.withPayload(property.value()) : null;
    });
  }

  private static Iterator<Group> selecting(
      Iterator<Group> upstream, java.util.function.Function<Group, Group> selection) {
    return new Iterator<>() {
      private Group buffered;

      @Override
      public boolean hasNext() {
        if (buffered != null) {
          return true;
        }
        while (upstream.hasNext()) {
          buffered = selection.apply(upstream.next());
          if (buffered != null) {
            return true;
          }
        }
        return false;
      }

      @Override
      public Group next() {
        if (!hasNext()) {
          throw new NoSuchElementException();
        }
        var result = buffered;
        buffered = null;
        return result;
      }
    };
  }
}
