package com.jetbrains.youtrackdb.internal.core.gremlin.translator.step;

import java.util.List;
import javax.annotation.Nullable;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.HasContainer;
import org.apache.tinkerpop.gremlin.structure.Direction;

/** Native-order stages of a sliced ordered hop. Later stages may add barrier and path carriers. */
public sealed interface OrderedHopStage
    permits OrderedHopStage.SourceMerge, OrderedHopStage.Expand, OrderedHopStage.Filter,
    OrderedHopStage.Slice, OrderedHopStage.Values, OrderedHopStage.Barrier,
    OrderedHopStage.Project {

  /** NONE preserves every source separately when native traverser equality cannot be represented. */
  enum MergeKey {
    NONE, ELEMENT, ELEMENT_AND_PATH
  }

  /** The cached stage records split count, never a sack supplier or splitter instance. */
  record SourceMerge(MergeKey key, boolean sackGated, int preOrderSplits)
      implements OrderedHopStage {

    public SourceMerge(MergeKey key) {
      this(key, false, 0);
    }
  }

  record Expand(Direction direction, @Nullable String[] edgeLabels) implements OrderedHopStage {

    public Expand {
      edgeLabels = edgeLabels == null ? null : edgeLabels.clone();
    }

    @Override
    public String[] edgeLabels() {
      return edgeLabels == null ? null : edgeLabels.clone();
    }
  }

  /** One native HasStep. Its predicates are evaluated once for each bulk group. */
  record Filter(List<HasContainer> containers, boolean polymorphic) implements OrderedHopStage {

    public Filter {
      containers = List.copyOf(containers);
    }
  }

  /** Limit counts surviving bulk after skip, or -1 for an unbounded high range. */
  record Slice(long skip, long limit) implements OrderedHopStage {

    public Slice {
      if (skip < 0 || limit < -1) {
        throw new IllegalArgumentException("Invalid ordered-hop slice: " + skip + ", " + limit);
      }
    }
  }

  /** A native NoOpBarrierStep window counts distinct traversers before emitting in first-seen order. */
  record Barrier(int maxSize, MergeKey key, List<Integer> pathIndexes, boolean sackGated)
      implements OrderedHopStage {

    public Barrier(int maxSize, MergeKey key) {
      this(maxSize, key, List.of(), false);
    }

    public Barrier(int maxSize, MergeKey key, List<Integer> pathIndexes) {
      this(maxSize, key, pathIndexes, false);
    }

    public Barrier {
      if (maxSize < 1) {
        throw new IllegalArgumentException("Barrier window must be positive: " + maxSize);
      }
      pathIndexes = List.copyOf(pathIndexes);
    }
  }

  /** Native select projection runs after a source slice, not during MATCH row projection. */
  record Project(boolean splits) implements OrderedHopStage {

    public Project() {
      this(false);
    }
  }

  record Values(String propertyKey) implements OrderedHopStage {
  }
}
