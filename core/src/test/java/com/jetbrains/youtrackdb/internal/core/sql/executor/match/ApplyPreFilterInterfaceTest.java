package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.record.impl.PreFilterableLinkBagIterable;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchPathItem;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import java.util.Collections;
import java.util.Iterator;
import java.util.Set;
import javax.annotation.Nonnull;
import org.junit.Test;

/**
 * Verifies that {@link MatchEdgeTraverser#applyPreFilter} dispatches on
 * {@link PreFilterableLinkBagIterable} rather than the concrete
 * {@code VertexFromLinkBagIterable} type, so both vertex and edge
 * link-bag iterables receive class and RID filters.
 *
 * <p>Uses a lightweight stub that implements the interface directly,
 * avoiding the need for a real database session or link bag.
 */
public class ApplyPreFilterInterfaceTest {

  /**
   * When applyPreFilter receives a PreFilterableLinkBagIterable (which
   * both vertex and edge iterables implement), it should apply the class
   * filter from the edge's acceptedCollectionIds. Before the interface
   * change, only VertexFromLinkBagIterable was handled — any other
   * implementor would pass through unfiltered.
   */
  @Test
  public void applyPreFilterAcceptsAnyPreFilterableViaInterface() {
    var collectionIds = IntOpenHashSet.of(20);
    var edge = createEdgeTraversalWithClassFilter(collectionIds);
    var traverser = createTraverser(edge);
    var ctx = new BasicCommandContext();

    var stub = new StubPreFilterable(100);
    var result = traverser.applyPreFilter(stub, ctx);

    assertThat(result).isInstanceOf(StubPreFilterable.class);
    var filtered = (StubPreFilterable) result;
    assertThat(filtered.appliedClassFilter).isEqualTo(collectionIds);
    assertThat(filtered.appliedRidFilter).isNull();
  }

  /**
   * When applyPreFilter receives a non-PreFilterableLinkBagIterable object
   * (e.g. a plain String), it returns the object unchanged — no filtering
   * is applied.
   */
  @Test
  public void applyPreFilterPassesThroughNonInterfaceType() {
    var edge = createEdgeTraversalWithClassFilter(IntOpenHashSet.of(10));
    var traverser = createTraverser(edge);
    var ctx = new BasicCommandContext();

    var plainObject = "not a PreFilterableLinkBagIterable";
    var result = traverser.applyPreFilter(plainObject, ctx);

    assertThat(result).isSameAs(plainObject);
  }

  /**
   * When the edge has no class filter and no intersection descriptor,
   * applyPreFilter returns the iterable unchanged (no-op).
   */
  @Test
  public void applyPreFilterNoOpWhenNoFiltersConfigured() {
    var edge = createEdgeTraversal();
    // No acceptedCollectionIds, no intersectionDescriptor
    var traverser = createTraverser(edge);
    var ctx = new BasicCommandContext();

    var stub = new StubPreFilterable(100);
    var result = traverser.applyPreFilter(stub, ctx);

    // Same instance returned: no filters were applied
    assertThat(result).isSameAs(stub);
  }

  /**
   * When edge is null (path-item constructor), applyPreFilter returns the
   * input unchanged regardless of its type.
   */
  @Test
  public void applyPreFilterReturnsUnchangedWhenEdgeIsNull() {
    var traverser = createTraverserWithNullEdge();
    var ctx = new BasicCommandContext();
    var stub = new StubPreFilterable(100);

    var result = traverser.applyPreFilter(stub, ctx);

    assertThat(result).isSameAs(stub);
  }

  // =========================================================================
  // Helpers
  // =========================================================================

  private EdgeTraversal createEdgeTraversal() {
    var nodeA = new PatternNode();
    nodeA.alias = "a";
    var nodeB = new PatternNode();
    nodeB.alias = "b";
    nodeA.addEdge(new SQLMatchPathItem(-1), nodeB);
    var patternEdge = nodeA.out.iterator().next();
    return new EdgeTraversal(patternEdge, true);
  }

  private EdgeTraversal createEdgeTraversalWithClassFilter(IntSet ids) {
    var et = createEdgeTraversal();
    et.setAcceptedCollectionIds(ids);
    return et;
  }

  private MatchEdgeTraverser createTraverser(EdgeTraversal edge) {
    return new MatchEdgeTraverser(null, edge);
  }

  private MatchEdgeTraverser createTraverserWithNullEdge() {
    return new MatchEdgeTraverser(null, new SQLMatchPathItem(-1));
  }

  /**
   * Lightweight stub implementing PreFilterableLinkBagIterable for testing
   * filter dispatch without requiring a real LinkBag or database session.
   * Records which filters were applied so tests can assert on them.
   */
  static class StubPreFilterable implements PreFilterableLinkBagIterable {
    final int reportedSize;
    IntSet appliedClassFilter;
    Set<RID> appliedRidFilter;

    StubPreFilterable(int size) {
      this.reportedSize = size;
    }

    private StubPreFilterable(
        int size, IntSet classFilter, Set<RID> ridFilter) {
      this.reportedSize = size;
      this.appliedClassFilter = classFilter;
      this.appliedRidFilter = ridFilter;
    }

    @Nonnull
    @Override
    public Iterator<?> iterator() {
      return Collections.emptyIterator();
    }

    @Override
    public int size() {
      return reportedSize;
    }

    @Override
    public boolean isSizeable() {
      return true;
    }

    @Nonnull
    @Override
    public PreFilterableLinkBagIterable withClassFilter(
        @Nonnull IntSet collectionIds) {
      return new StubPreFilterable(
          reportedSize, collectionIds, appliedRidFilter);
    }

    @Nonnull
    @Override
    public PreFilterableLinkBagIterable withRidFilter(
        @Nonnull Set<RID> ridSet) {
      return new StubPreFilterable(
          reportedSize, appliedClassFilter, ridSet);
    }
  }
}
