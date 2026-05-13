package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.common.profiler.metrics.Ratio;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
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
   * On the EdgeRidLookup success path, applyPreFilter applies the RidFilter
   * to the iterable; once the resulting iterable is drained, the lazy
   * effectiveness flush updates {@code preFilterTotalProbed} and
   * {@code preFilterTotalFiltered}. The link-bag has 1000 entries with
   * exactly one ({@code 10:1}) matching the RidSet, so the true
   * intersection size is 1 → probed=1000, filtered=999.
   */
  @Test
  public void applyPreFilter_success_recordsCounters() {
    var edge = createEdgeTraversal();
    var desc = mock(EdgeRidLookup.class);
    var key = new RecordId(5, 1);
    var ridMatch = new RecordId(10, 1);
    var ridSet = singletonRidSet(10, 1); // size = 1, contains 10:1

    when(desc.cacheKey(any())).thenReturn(key);
    when(desc.estimatedSize(any(), any())).thenReturn(1);
    when(desc.passesSelectivityCheck(anyInt(), anyInt(), any())).thenReturn(true);
    when(desc.resolve(any(), any())).thenReturn(ridSet);
    edge.setIntersectionDescriptor(desc);
    var traverser = createTraverser(edge);
    var ctx = new BasicCommandContext();

    // size=1000 above minLinkBagSize (50); seed one overlap with the RidSet.
    var stub = new StubPreFilterable(linkBagWithOverlap(1000, List.of(ridMatch)));
    var result = drainAndCast(traverser.applyPreFilter(stub, ctx));

    assertThat(edge.getPreFilterAppliedCount()).isEqualTo(1);
    assertThat(edge.getPreFilterTotalProbed()).isEqualTo(1000);
    assertThat(edge.getPreFilterTotalFiltered()).isEqualTo(999);
    assertThat(edge.getPreFilterSkippedCount()).isZero();
    assertThat(edge.getLastSkipReason()).isEqualTo(PreFilterSkipReason.NONE);
    assertThat(result.appliedRidFilter).containsExactly(ridMatch);
  }

  /**
   * IndexLookup success path: applyPreFilter skips passesSelectivityCheck
   * for IndexLookup descriptors (selectivity was already checked in
   * resolveWithCache at the class level). Counters are updated once the
   * iterable is drained — the link-bag has 1000 entries, exactly one of
   * which matches the singleton RidSet, so the true intersection size is 1.
   */
  @Test
  public void applyPreFilter_indexLookup_skipsRechecks_recordsCounters() {
    var edge = createEdgeTraversal();
    var indexDesc =
        mock(com.jetbrains.youtrackdb.internal.core.sql.executor.IndexSearchDescriptor.class);
    when(indexDesc.estimateSelectivity(any())).thenReturn(0.1);
    when(indexDesc.estimateHits(any())).thenReturn(10L);
    var index = mock(com.jetbrains.youtrackdb.internal.core.index.Index.class);
    when(index.getName()).thenReturn("Post.date");
    when(indexDesc.getIndex()).thenReturn(index);

    var desc =
        mock(
            com.jetbrains.youtrackdb.internal.core.sql.executor.RidFilterDescriptor.IndexLookup.class);
    when(desc.indexDescriptor()).thenReturn(indexDesc);
    var ridMatch = new RecordId(10, 1);
    var ridSet = singletonRidSet(10, 1);
    when(desc.cacheKey(any())).thenReturn("Post.date");
    when(desc.estimatedSize(any(), any())).thenReturn(10);
    when(desc.resolve(any(), any())).thenReturn(ridSet);
    edge.setIntersectionDescriptor(desc);
    var traverser = createTraverser(edge);
    var ctx = new BasicCommandContext();

    var stub = new StubPreFilterable(linkBagWithOverlap(1000, List.of(ridMatch)));
    var result = drainAndCast(traverser.applyPreFilter(stub, ctx));

    assertThat(edge.getPreFilterTotalProbed()).isEqualTo(1000);
    assertThat(edge.getPreFilterTotalFiltered()).isEqualTo(999);
    assertThat(result.appliedRidFilter).containsExactly(ridMatch);
  }

  /**
   * Multiple applyPreFilter calls accumulate preFilterTotalProbed and
   * preFilterTotalFiltered across vertices once each vertex's iterable
   * is drained. The second vertex's stats add to the first's (cache hit
   * for ridSet). Each vertex's link-bag seeds one overlap with the
   * RidSet so the true intersection size is 1 per vertex.
   */
  @Test
  public void applyPreFilter_multipleVertices_accumulatesCounters() {
    var edge = createEdgeTraversal();
    var desc = mock(EdgeRidLookup.class);
    var key = new RecordId(5, 1);
    var ridMatch = new RecordId(10, 1);
    var ridSet = singletonRidSet(10, 1); // size = 1

    when(desc.cacheKey(any())).thenReturn(key);
    when(desc.estimatedSize(any(), any())).thenReturn(1);
    when(desc.passesSelectivityCheck(anyInt(), anyInt(), any())).thenReturn(true);
    when(desc.resolve(any(), any())).thenReturn(ridSet);
    edge.setIntersectionDescriptor(desc);
    var traverser = createTraverser(edge);
    var ctx = new BasicCommandContext();

    // Vertex 1: linkBagSize=200, one overlap with the RidSet.
    drainAndCast(traverser.applyPreFilter(
        new StubPreFilterable(linkBagWithOverlap(200, List.of(ridMatch))), ctx));
    assertThat(edge.getPreFilterTotalProbed()).isEqualTo(200);
    assertThat(edge.getPreFilterTotalFiltered()).isEqualTo(199);

    // Vertex 2: linkBagSize=300 (cache hit for ridSet), one overlap again.
    drainAndCast(traverser.applyPreFilter(
        new StubPreFilterable(linkBagWithOverlap(300, List.of(ridMatch))), ctx));
    assertThat(edge.getPreFilterAppliedCount()).isEqualTo(2);
    assertThat(edge.getPreFilterTotalProbed()).isEqualTo(500); // 200+300
    assertThat(edge.getPreFilterTotalFiltered()).isEqualTo(498); // 199+299
  }

  /**
   * When every link-bag entry is also in the RidSet (e.g. IndexLookup
   * returns a superset including all neighbors), the true intersection
   * equals the link-bag size — so {@code preFilterTotalFiltered} is zero
   * after the iterable is drained. This pins the corrected semantics:
   * the count reflects real intersection, not the obsolete
   * {@code Math.max(0, linkBagSize − ridSet.size())} floor that PR #973
   * originally used.
   */
  @Test
  public void applyPreFilter_ridSetSupersetOfLinkBag_filteredStaysZero() {
    var edge = createEdgeTraversal();
    var indexDesc =
        mock(com.jetbrains.youtrackdb.internal.core.sql.executor.IndexSearchDescriptor.class);
    when(indexDesc.estimateSelectivity(any())).thenReturn(0.1);
    when(indexDesc.estimateHits(any())).thenReturn(10L);
    var index = mock(com.jetbrains.youtrackdb.internal.core.index.Index.class);
    when(index.getName()).thenReturn("Post.date");
    when(indexDesc.getIndex()).thenReturn(index);

    var desc =
        mock(
            com.jetbrains.youtrackdb.internal.core.sql.executor.RidFilterDescriptor.IndexLookup.class);
    when(desc.indexDescriptor()).thenReturn(indexDesc);
    // RidSet contains 500 entries; the link bag's 100 entries are all in it.
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

    // Link-bag content matches the first 100 entries of the RidSet → every
    // probed entry is found in the set, filtered=0.
    var linkBag = new ArrayList<RID>(100);
    for (int i = 0; i < 100; i++) {
      linkBag.add(new RecordId(10, i));
    }
    drainAndCast(traverser.applyPreFilter(new StubPreFilterable(linkBag), ctx));

    assertThat(edge.getPreFilterTotalProbed()).isEqualTo(100);
    assertThat(edge.getPreFilterTotalFiltered()).isZero();
    assertThat(edge.getPreFilterAppliedCount()).isEqualTo(1);
  }

  /**
   * Lightweight stub implementing PreFilterableLinkBagIterable for testing
   * filter dispatch without requiring a real LinkBag or database session.
   * Records which filters were applied so tests can assert on them.
   *
   * <p>When constructed with concrete link-bag content, the iterator yielded
   * by {@link #iterator()} walks those RIDs, counts probed/filtered against
   * the captured {@code appliedRidFilter}, and flushes the true counts to
   * the captured {@code Ratio} on exhaustion — mirroring the production
   * iterator's lazy-flush behaviour so tests can exercise the end-to-end
   * {@code applyPreFilter} → counter pipeline.
   *
   * <p>When constructed via {@link #StubPreFilterable(int)}, synthetic RIDs
   * disjoint from any RidSet built by the tests are used; this remains the
   * right fixture for tests that only care about skip paths.
   */
  static class StubPreFilterable implements PreFilterableLinkBagIterable {
    final int reportedSize;
    final List<RID> linkBagContent;
    IntSet appliedClassFilter;
    Set<RID> appliedRidFilter;
    Ratio capturedEffectivenessMetric;

    StubPreFilterable(int size) {
      this(syntheticContent(size));
    }

    StubPreFilterable(List<RID> content) {
      this.linkBagContent = List.copyOf(content);
      this.reportedSize = content.size();
    }

    private StubPreFilterable(
        List<RID> content,
        IntSet classFilter,
        Set<RID> ridFilter,
        Ratio effectivenessMetric) {
      this.linkBagContent = content;
      this.reportedSize = content.size();
      this.appliedClassFilter = classFilter;
      this.appliedRidFilter = ridFilter;
      this.capturedEffectivenessMetric = effectivenessMetric;
    }

    /**
     * Returns an iterator that mirrors the production
     * {@code EdgeFromLinkBagIterator} probe/filter accounting: it counts
     * link-bag entries that survive the (no-op here) class check, tests each
     * against {@code appliedRidFilter}, increments {@code filteredCount} on
     * mismatch, and flushes both counts to the captured Ratio on exhaustion.
     */
    @Nonnull
    @Override
    public Iterator<?> iterator() {
      if (appliedRidFilter == null) {
        // No RID filter applied → no metric to flush; behave as before.
        return Collections.emptyIterator();
      }
      return new Iterator<RID>() {
        final Iterator<RID> source = linkBagContent.iterator();
        long probed;
        long filtered;
        boolean flushed;
        RID nextSurvivor;

        @Override
        public boolean hasNext() {
          while (nextSurvivor == null && source.hasNext()) {
            var rid = source.next();
            probed++;
            if (appliedRidFilter.contains(rid)) {
              nextSurvivor = rid;
            } else {
              filtered++;
            }
          }
          if (nextSurvivor == null && !flushed) {
            flushed = true;
            if (probed > 0) {
              capturedEffectivenessMetric.record(filtered, probed);
            }
          }
          return nextSurvivor != null;
        }

        @Override
        public RID next() {
          if (!hasNext()) {
            throw new NoSuchElementException();
          }
          var out = nextSurvivor;
          nextSurvivor = null;
          return out;
        }
      };
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
          linkBagContent,
          collectionIds,
          appliedRidFilter,
          capturedEffectivenessMetric);
    }

    @Nonnull
    @Override
    public PreFilterableLinkBagIterable withRidFilter(
        @Nonnull Set<RID> ridSet,
        @Nonnull Ratio effectivenessMetric) {
      return new StubPreFilterable(
          linkBagContent, appliedClassFilter, ridSet, effectivenessMetric);
    }

    /**
     * Synthetic link-bag content of {@code size} entries in a cluster (99)
     * that no test's RidSet uses — keeps the {@code linkBag ∩ ridSet}
     * intersection empty when the test does not explicitly seed overlap.
     */
    private static List<RID> syntheticContent(int size) {
      var list = new ArrayList<RID>(size);
      for (int i = 0; i < size; i++) {
        list.add(new RecordId(99, i));
      }
      return list;
    }
  }

  // =========================================================================
  // Iteration-driven counter helpers
  //
  // The production iterator flushes preFilterTotalProbed/Filtered lazily on
  // exhaustion, so tests asserting on those counters must drain the iterable
  // returned by applyPreFilter.  These helpers keep the boilerplate out of
  // each individual test.
  // =========================================================================

  /**
   * Builds a link-bag of {@code totalSize} RIDs that includes {@code overlap}
   * RIDs which the test will also place in its RidSet, padded with synthetic
   * RIDs in a disjoint cluster.  Used to seed a known true-intersection size
   * so post-iteration counters are deterministic.
   */
  private static List<RID> linkBagWithOverlap(int totalSize, List<RID> overlap) {
    if (overlap.size() > totalSize) {
      throw new IllegalArgumentException(
          "Overlap size " + overlap.size() + " exceeds total " + totalSize);
    }
    var list = new ArrayList<RID>(totalSize);
    list.addAll(overlap);
    for (int i = overlap.size(); i < totalSize; i++) {
      list.add(new RecordId(99, i));
    }
    return list;
  }

  /**
   * Drains the iterable returned by {@code applyPreFilter} so the lazy
   * effectiveness flush fires. Returns the stub cast back for inspection.
   */
  private static StubPreFilterable drainAndCast(Object result) {
    var stub = (StubPreFilterable) result;
    var it = stub.iterator();
    while (it.hasNext()) {
      it.next();
    }
    return stub;
  }
}
