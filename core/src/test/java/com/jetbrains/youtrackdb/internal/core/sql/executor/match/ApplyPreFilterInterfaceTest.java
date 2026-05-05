package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.record.impl.PreFilterableLinkBagIterable;
import com.jetbrains.youtrackdb.internal.core.sql.executor.RidFilterDescriptor.EdgeRidLookup;
import com.jetbrains.youtrackdb.internal.core.sql.executor.RidSet;
import com.jetbrains.youtrackdb.internal.core.sql.executor.TraversalPreFilterHelper;
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

  // =========================================================================
  // Counter recording in applyPreFilter
  // =========================================================================

  private RidSet singletonRidSet(int cluster, long pos) {
    var set = new RidSet();
    set.add(new RecordId(cluster, pos));
    return set;
  }

  /**
   * When linkBagSize is below minLinkBagSize, applyPreFilter records
   * LINKBAG_TOO_SMALL and returns the original iterable unfiltered.
   */
  @Test
  public void applyPreFilter_linkBagTooSmall_recordsSkip() {
    var edge = createEdgeTraversal();
    var desc = mock(EdgeRidLookup.class);
    edge.setIntersectionDescriptor(desc);
    var traverser = createTraverser(edge);
    var ctx = new BasicCommandContext();

    // size=1, below the default minLinkBagSize (50)
    var stub = new StubPreFilterable(1);
    var result = traverser.applyPreFilter(stub, ctx);

    assertThat(edge.getLastSkipReason())
        .isEqualTo(PreFilterSkipReason.LINKBAG_TOO_SMALL);
    assertThat(edge.getPreFilterSkippedCount()).isEqualTo(1);
    assertThat(edge.getPreFilterAppliedCount()).isZero();
    assertThat(result).isSameAs(stub);
  }

  /**
   * When resolveWithCache returns null (e.g. cap exceeded), the skip
   * reason was already set by resolveWithCache. applyPreFilter preserves
   * it and returns the iterable unfiltered.
   */
  @Test
  public void applyPreFilter_resolveReturnsNull_preservesSkipReason() {
    var edge = createEdgeTraversal();
    var desc = mock(EdgeRidLookup.class);
    var key = new RecordId(5, 1);
    when(desc.cacheKey(any())).thenReturn(key);
    // Exceeds cap → resolveWithCache sets CAP_EXCEEDED and returns null
    when(desc.estimatedSize(any(), any()))
        .thenReturn(TraversalPreFilterHelper.maxRidSetSize() + 1);
    edge.setIntersectionDescriptor(desc);
    var traverser = createTraverser(edge);
    var ctx = new BasicCommandContext();

    // size=1000, above minLinkBagSize (50)
    var stub = new StubPreFilterable(1000);
    var result = traverser.applyPreFilter(stub, ctx);

    assertThat(edge.getLastSkipReason())
        .isEqualTo(PreFilterSkipReason.CAP_EXCEEDED);
    assertThat(edge.getPreFilterSkippedCount()).isEqualTo(1);
    assertThat(edge.getPreFilterTotalProbed()).isZero();
    assertThat(((StubPreFilterable) result).appliedRidFilter).isNull();
  }

  /**
   * When EdgeRidLookup's passesSelectivityCheck returns false in
   * applyPreFilter (actual ridSet.size() vs linkBagSize), it records
   * OVERLAP_RATIO_TOO_HIGH.
   */
  @Test
  public void applyPreFilter_overlapTooHigh_recordsSkip() {
    var edge = createEdgeTraversal();
    var desc = mock(EdgeRidLookup.class);
    var key = new RecordId(5, 1);
    var ridSet = singletonRidSet(10, 1);

    when(desc.cacheKey(any())).thenReturn(key);
    when(desc.estimatedSize(any(), any())).thenReturn(1);
    when(desc.passesSelectivityCheck(anyInt(), anyInt(), any()))
        .thenReturn(true) // passes in resolveWithCache (estimate)
        .thenReturn(false); // fails in applyPreFilter (actual size)
    when(desc.resolve(any(), any())).thenReturn(ridSet);
    edge.setIntersectionDescriptor(desc);
    var traverser = createTraverser(edge);
    var ctx = new BasicCommandContext();

    // size=1000 above minLinkBagSize (50)
    var stub = new StubPreFilterable(1000);
    var result = traverser.applyPreFilter(stub, ctx);

    assertThat(edge.getLastSkipReason())
        .isEqualTo(PreFilterSkipReason.OVERLAP_RATIO_TOO_HIGH);
    assertThat(edge.getPreFilterSkippedCount()).isEqualTo(1);
    assertThat(edge.getPreFilterTotalProbed()).isZero();
    assertThat(((StubPreFilterable) result).appliedRidFilter).isNull();
  }

  /**
   * On the EdgeRidLookup success path, applyPreFilter records
   * preFilterTotalProbed and preFilterTotalFiltered. The RidFilter is
   * applied to the iterable.
   */
  @Test
  public void applyPreFilter_success_recordsCounters() {
    var edge = createEdgeTraversal();
    var desc = mock(EdgeRidLookup.class);
    var key = new RecordId(5, 1);
    var ridSet = singletonRidSet(10, 1); // size = 1

    when(desc.cacheKey(any())).thenReturn(key);
    when(desc.estimatedSize(any(), any())).thenReturn(1);
    when(desc.passesSelectivityCheck(anyInt(), anyInt(), any()))
        .thenReturn(true);
    when(desc.resolve(any(), any())).thenReturn(ridSet);
    edge.setIntersectionDescriptor(desc);
    var traverser = createTraverser(edge);
    var ctx = new BasicCommandContext();

    // size=1000 above minLinkBagSize (50)
    var stub = new StubPreFilterable(1000);
    var result = traverser.applyPreFilter(stub, ctx);

    assertThat(edge.getPreFilterAppliedCount()).isEqualTo(1);
    assertThat(edge.getPreFilterTotalProbed()).isEqualTo(1000);
    assertThat(edge.getPreFilterTotalFiltered()).isEqualTo(999);
    assertThat(edge.getPreFilterSkippedCount()).isZero();
    assertThat(edge.getLastSkipReason()).isEqualTo(PreFilterSkipReason.NONE);
    assertThat(((StubPreFilterable) result).appliedRidFilter)
        .containsExactly(new RecordId(10, 1));
  }

  /**
   * IndexLookup success path: applyPreFilter skips passesSelectivityCheck
   * for IndexLookup descriptors (selectivity was already checked in
   * resolveWithCache at the class level). Counters still record the
   * application.
   */
  @Test
  public void applyPreFilter_indexLookup_skipsRechecks_recordsCounters() {
    var edge = createEdgeTraversal();
    var indexDesc = mock(
        com.jetbrains.youtrackdb.internal.core.sql.executor.IndexSearchDescriptor.class);
    when(indexDesc.estimateSelectivity(any())).thenReturn(0.1);
    when(indexDesc.estimateHits(any())).thenReturn(10L);
    var index = mock(
        com.jetbrains.youtrackdb.internal.core.index.Index.class);
    when(index.getName()).thenReturn("Post.date");
    when(indexDesc.getIndex()).thenReturn(index);

    var desc = mock(
        com.jetbrains.youtrackdb.internal.core.sql.executor.RidFilterDescriptor.IndexLookup.class);
    when(desc.indexDescriptor()).thenReturn(indexDesc);
    var ridSet = singletonRidSet(10, 1);
    when(desc.cacheKey(any())).thenReturn("Post.date");
    when(desc.estimatedSize(any(), any())).thenReturn(10);
    when(desc.resolve(any(), any())).thenReturn(ridSet);
    edge.setIntersectionDescriptor(desc);
    var traverser = createTraverser(edge);
    var ctx = new BasicCommandContext();

    var stub = new StubPreFilterable(1000);
    var result = traverser.applyPreFilter(stub, ctx);

    assertThat(edge.getPreFilterTotalProbed()).isEqualTo(1000);
    assertThat(edge.getPreFilterTotalFiltered()).isEqualTo(999);
    assertThat(((StubPreFilterable) result).appliedRidFilter)
        .containsExactly(new RecordId(10, 1));
  }

  /**
   * Multiple applyPreFilter calls accumulate preFilterTotalProbed and
   * preFilterTotalFiltered across vertices. The second vertex's stats
   * add to the first's (cache hit for ridSet).
   */
  @Test
  public void applyPreFilter_multipleVertices_accumulatesCounters() {
    var edge = createEdgeTraversal();
    var desc = mock(EdgeRidLookup.class);
    var key = new RecordId(5, 1);
    var ridSet = singletonRidSet(10, 1); // size = 1

    when(desc.cacheKey(any())).thenReturn(key);
    when(desc.estimatedSize(any(), any())).thenReturn(1);
    when(desc.passesSelectivityCheck(anyInt(), anyInt(), any()))
        .thenReturn(true);
    when(desc.resolve(any(), any())).thenReturn(ridSet);
    edge.setIntersectionDescriptor(desc);
    var traverser = createTraverser(edge);
    var ctx = new BasicCommandContext();

    // Vertex 1: linkBagSize=200
    traverser.applyPreFilter(new StubPreFilterable(200), ctx);
    assertThat(edge.getPreFilterTotalProbed()).isEqualTo(200);
    assertThat(edge.getPreFilterTotalFiltered()).isEqualTo(199);

    // Vertex 2: linkBagSize=300 (cache hit for ridSet)
    traverser.applyPreFilter(new StubPreFilterable(300), ctx);
    assertThat(edge.getPreFilterAppliedCount()).isEqualTo(2);
    assertThat(edge.getPreFilterTotalProbed()).isEqualTo(500); // 200+300
    assertThat(edge.getPreFilterTotalFiltered()).isEqualTo(498); // 199+299
  }

  /**
   * When ridSetSize exceeds linkBagSize (e.g. IndexLookup returns more
   * hits than the vertex has neighbors), preFilterTotalFiltered stays at
   * zero via the Math.max(0, ...) floor in recordPreFilterApplied.
   */
  @Test
  public void applyPreFilter_ridSetLargerThanLinkBag_filteredStaysZero() {
    var edge = createEdgeTraversal();
    var indexDesc = mock(
        com.jetbrains.youtrackdb.internal.core.sql.executor.IndexSearchDescriptor.class);
    when(indexDesc.estimateSelectivity(any())).thenReturn(0.1);
    when(indexDesc.estimateHits(any())).thenReturn(10L);
    var index = mock(
        com.jetbrains.youtrackdb.internal.core.index.Index.class);
    when(index.getName()).thenReturn("Post.date");
    when(indexDesc.getIndex()).thenReturn(index);

    var desc = mock(
        com.jetbrains.youtrackdb.internal.core.sql.executor.RidFilterDescriptor.IndexLookup.class);
    when(desc.indexDescriptor()).thenReturn(indexDesc);
    // RidSet has 500 entries, link bag only has 100
    var ridSet = new RidSet();
    for (int i = 0; i < 500; i++) {
      ridSet.add(new RecordId(10, i));
    }
    when(desc.cacheKey(any())).thenReturn("Post.date");
    when(desc.estimatedSize(any(), any())).thenReturn(500);
    when(desc.resolve(any(), any())).thenReturn(ridSet);
    edge.setIntersectionDescriptor(desc);
    var traverser = createTraverser(edge);
    var ctx = new BasicCommandContext();

    var stub = new StubPreFilterable(100);
    traverser.applyPreFilter(stub, ctx);

    assertThat(edge.getPreFilterTotalProbed()).isEqualTo(100);
    assertThat(edge.getPreFilterTotalFiltered()).isZero();
    assertThat(edge.getPreFilterAppliedCount()).isEqualTo(1);
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
