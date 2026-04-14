package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.common.profiler.metrics.Ratio;
import com.jetbrains.youtrackdb.internal.common.profiler.metrics.TimeRate;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.index.Index;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.IndexSearchDescriptor;
import com.jetbrains.youtrackdb.internal.core.sql.executor.RidFilterDescriptor;
import com.jetbrains.youtrackdb.internal.core.sql.executor.RidFilterDescriptor.DirectRid;
import com.jetbrains.youtrackdb.internal.core.sql.executor.RidFilterDescriptor.EdgeRidLookup;
import com.jetbrains.youtrackdb.internal.core.sql.executor.RidFilterDescriptor.IndexLookup;
import com.jetbrains.youtrackdb.internal.core.sql.executor.RidSet;
import com.jetbrains.youtrackdb.internal.core.sql.executor.TraversalPreFilterHelper;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchPathItem;
import java.util.List;
import org.assertj.core.data.Offset;
import org.junit.Test;

/**
 * Unit tests for the RidSet caching mechanism on {@link EdgeTraversal}.
 *
 * <p>Uses Mockito mocks of the concrete {@link EdgeRidLookup} record
 * (a permitted type of the sealed {@link RidFilterDescriptor}) to
 * verify cache hit/miss behaviour without requiring a database context.
 */
public class EdgeTraversalCacheTest {

  /**
   * A large link bag size that ensures the ratio check passes for
   * small RidSets. Tests that focus on cache behaviour (not ratio
   * checking) use this value.
   */
  private static final int LARGE_LINKBAG = 1_000_000;

  private EdgeTraversal createEdgeTraversal() {
    var nodeA = new PatternNode();
    nodeA.alias = "a";
    var nodeB = new PatternNode();
    nodeB.alias = "b";
    nodeA.addEdge(new SQLMatchPathItem(-1), nodeB);
    var patternEdge = nodeA.out.iterator().next();
    return new EdgeTraversal(patternEdge, true);
  }

  private RidSet singletonRidSet(int cluster, long pos) {
    var set = new RidSet();
    set.add(new RecordId(cluster, pos));
    return set;
  }

  /**
   * Stubs the mock descriptor to return a small estimatedSize and pass
   * the selectivity check so that resolveWithCache proceeds to resolution
   * when called with a large linkBagSize.
   */
  private void stubSmallEstimate(RidFilterDescriptor mock) {
    when(mock.estimatedSize(any(), any())).thenReturn(1);
    when(mock.passesSelectivityCheck(anyInt(), anyInt(), any()))
        .thenReturn(true);
  }

  /**
   * When the cache key is non-null and unchanged between calls,
   * resolve() is called only once and the cached RidSet is reused.
   */
  @Test
  public void resolveWithCache_sameKey_returnsCachedRidSet() {
    var et = createEdgeTraversal();
    var desc = mock(EdgeRidLookup.class);
    var key = new RecordId(5, 1);
    var ridSet = singletonRidSet(10, 1);

    when(desc.cacheKey(any())).thenReturn(key);
    when(desc.resolve(any(), any())).thenReturn(ridSet);
    stubSmallEstimate(desc);
    et.setIntersectionDescriptor(desc);
    var ctx = new BasicCommandContext();

    var first = et.resolveWithCache(ctx, LARGE_LINKBAG);
    var second = et.resolveWithCache(ctx, LARGE_LINKBAG);

    verify(desc, times(1)).resolve(any(), any());
    assertThat(second).isSameAs(first);
  }

  /**
   * When the cache key changes between calls, the cache stores both
   * entries and resolve() is called once per unique key.
   */
  @Test
  public void resolveWithCache_differentKey_rebuildsRidSet() {
    var et = createEdgeTraversal();
    var desc = mock(EdgeRidLookup.class);
    var key1 = new RecordId(5, 1);
    var key2 = new RecordId(5, 2);
    var ridSet1 = singletonRidSet(10, 1);
    var ridSet2 = singletonRidSet(10, 2);

    when(desc.cacheKey(any())).thenReturn(key1, key2);
    when(desc.resolve(any(), any())).thenReturn(ridSet1, ridSet2);
    stubSmallEstimate(desc);
    et.setIntersectionDescriptor(desc);
    var ctx = new BasicCommandContext();

    var first = et.resolveWithCache(ctx, LARGE_LINKBAG);
    var second = et.resolveWithCache(ctx, LARGE_LINKBAG);

    verify(desc, times(2)).resolve(any(), any());
    assertThat(second).isNotSameAs(first);
  }

  /**
   * When the cache key is null, resolve() is called
   * every time — no caching.
   */
  @Test
  public void resolveWithCache_nullKey_noCaching() {
    var et = createEdgeTraversal();
    var desc = mock(EdgeRidLookup.class);
    var ridSet1 = singletonRidSet(10, 1);
    var ridSet2 = singletonRidSet(10, 2);

    when(desc.cacheKey(any())).thenReturn(null);
    when(desc.resolve(any(), any())).thenReturn(ridSet1, ridSet2);
    stubSmallEstimate(desc);
    et.setIntersectionDescriptor(desc);
    var ctx = new BasicCommandContext();

    var first = et.resolveWithCache(ctx, LARGE_LINKBAG);
    var second = et.resolveWithCache(ctx, LARGE_LINKBAG);

    verify(desc, times(2)).resolve(any(), any());
    assertThat(second).isNotSameAs(first);
  }

  /**
   * When no intersection descriptor is set, resolveWithCache() returns
   * null without error.
   */
  @Test
  public void resolveWithCache_noDescriptor_returnsNull() {
    var et = createEdgeTraversal();
    var ctx = new BasicCommandContext();

    assertThat(et.resolveWithCache(ctx, LARGE_LINKBAG)).isNull();
  }

  /**
   * copy() must not carry over the cached RidSet to avoid stale data
   * when a plan is reused.
   */
  @Test
  public void copy_resetsCachedRidSet() {
    var et = createEdgeTraversal();
    var desc = mock(EdgeRidLookup.class);
    var key = new RecordId(5, 1);
    var ridSet1 = singletonRidSet(10, 1);
    var ridSet2 = singletonRidSet(10, 2);

    when(desc.cacheKey(any())).thenReturn(key);
    when(desc.resolve(any(), any())).thenReturn(ridSet1, ridSet2);
    stubSmallEstimate(desc);
    et.setIntersectionDescriptor(desc);
    var ctx = new BasicCommandContext();

    et.resolveWithCache(ctx, LARGE_LINKBAG);

    var copy = et.copy();
    var fromCopy = copy.resolveWithCache(ctx, LARGE_LINKBAG);

    verify(desc, times(2)).resolve(any(), any());
    assertThat(fromCopy).isSameAs(ridSet2);
  }

  /**
   * When resolve() returns null (e.g. absolute cap exceeded), the null
   * result IS cached — retrying with the same key would hit the same
   * cap, so there is no point rebuilding.
   */
  @Test
  public void resolveWithCache_nullResult_isCached() {
    var et = createEdgeTraversal();
    var desc = mock(EdgeRidLookup.class);
    var key = new RecordId(5, 1);

    when(desc.cacheKey(any())).thenReturn(key);
    when(desc.resolve(any(), any())).thenReturn(null);
    stubSmallEstimate(desc);
    et.setIntersectionDescriptor(desc);
    var ctx = new BasicCommandContext();

    var first = et.resolveWithCache(ctx, LARGE_LINKBAG);
    assertThat(first).isNull();

    var second = et.resolveWithCache(ctx, LARGE_LINKBAG);
    assertThat(second).isNull();

    verify(desc, times(1)).resolve(any(), any());
  }

  // =========================================================================
  // DirectRid — cacheKey(), resolve(), and estimatedSize()
  // =========================================================================

  /**
   * {@link DirectRid#cacheKey} always returns null — singleton sets are
   * trivial to rebuild and not worth caching.
   */
  @Test
  public void directRid_cacheKey_returnsNull() {
    var expr = mock(SQLExpression.class);
    var desc = new DirectRid(expr);
    assertThat(desc.cacheKey(new BasicCommandContext())).isNull();
  }

  /** {@link DirectRid#estimatedSize} always returns 1. */
  @Test
  public void directRid_estimatedSize_returnsOne() {
    var expr = mock(SQLExpression.class);
    var desc = new DirectRid(expr);
    assertThat(desc.estimatedSize(new BasicCommandContext(), null)).isEqualTo(1);
  }

  /**
   * {@link DirectRid#resolve} returns a singleton set when the expression
   * evaluates to a valid RID.
   */
  @Test
  public void directRid_resolve_validRid_returnsSingleton() {
    var expr = mock(SQLExpression.class);
    var rid = new RecordId(10, 5);
    when(expr.execute(nullable(Result.class), any())).thenReturn(rid);

    var desc = new DirectRid(expr);
    var result = desc.resolve(new BasicCommandContext(), null);

    assertThat(result).isNotNull();
    assertThat(result.size()).isEqualTo(1);
    assertThat(result.contains(rid)).isTrue();
  }

  /**
   * {@link DirectRid#resolve} returns null when the expression evaluates
   * to a non-RID value (e.g. a String).
   */
  @Test
  public void directRid_resolve_nonRid_returnsNull() {
    var expr = mock(SQLExpression.class);
    when(expr.execute(nullable(Result.class), any())).thenReturn("not-a-rid");

    var desc = new DirectRid(expr);
    var result = desc.resolve(new BasicCommandContext(), null);

    assertThat(result).isNull();
  }

  /**
   * {@link DirectRid#resolve} returns null when the expression evaluates
   * to null.
   */
  @Test
  public void directRid_resolve_nullValue_returnsNull() {
    var expr = mock(SQLExpression.class);
    when(expr.execute(nullable(Result.class), any())).thenReturn(null);

    var desc = new DirectRid(expr);
    var result = desc.resolve(new BasicCommandContext(), null);

    assertThat(result).isNull();
  }

  // =========================================================================
  // EdgeRidLookup — cacheKey()
  // =========================================================================

  /**
   * {@link EdgeRidLookup#cacheKey} returns the resolved RID when the
   * expression evaluates to a RID.
   */
  @Test
  public void edgeRidLookup_cacheKey_validRid_returnsRid() {
    var expr = mock(SQLExpression.class);
    var rid = new RecordId(5, 1);
    when(expr.execute(nullable(Result.class), any())).thenReturn(rid);

    var desc = new EdgeRidLookup("KNOWS", "out", expr, false);
    var key = desc.cacheKey(new BasicCommandContext());

    assertThat(key).isEqualTo(rid);
  }

  /**
   * {@link EdgeRidLookup#cacheKey} returns null when the expression
   * evaluates to a non-RID.
   */
  @Test
  public void edgeRidLookup_cacheKey_nonRid_returnsNull() {
    var expr = mock(SQLExpression.class);
    when(expr.execute(nullable(Result.class), any())).thenReturn(42);

    var desc = new EdgeRidLookup("KNOWS", "out", expr, false);
    var key = desc.cacheKey(new BasicCommandContext());

    assertThat(key).isNull();
  }

  // =========================================================================
  // IndexLookup — cacheKey()
  // =========================================================================

  /**
   * {@link IndexLookup#cacheKey} returns the index name, which uniquely
   * identifies the result within a single query execution.
   */
  @Test
  public void indexLookup_cacheKey_returnsIndexName() {
    var index = mock(Index.class);
    when(index.getName()).thenReturn("Post.creationDate");
    var indexDesc = mock(IndexSearchDescriptor.class);
    when(indexDesc.getIndex()).thenReturn(index);
    var desc = new IndexLookup(indexDesc);
    var key = desc.cacheKey(new BasicCommandContext());

    assertThat(key).isEqualTo("Post.creationDate");
  }

  /**
   * Two calls to {@link IndexLookup#cacheKey} return equal objects,
   * confirming that the key is stable.
   */
  @Test
  public void indexLookup_cacheKey_isStable() {
    var index = mock(Index.class);
    when(index.getName()).thenReturn("Post.creationDate");
    var indexDesc = mock(IndexSearchDescriptor.class);
    when(indexDesc.getIndex()).thenReturn(index);
    var desc = new IndexLookup(indexDesc);
    var ctx = new BasicCommandContext();

    assertThat(desc.cacheKey(ctx)).isEqualTo(desc.cacheKey(ctx));
  }

  /**
   * {@link IndexLookup#estimatedSize} delegates to
   * {@code IndexSearchDescriptor.estimateHits()}.
   */
  @Test
  public void indexLookup_estimatedSize_delegatesToEstimateHits() {
    var indexDesc = mock(IndexSearchDescriptor.class);
    when(indexDesc.estimateHits(any())).thenReturn(42L);
    var desc = new IndexLookup(indexDesc);

    assertThat(desc.estimatedSize(new BasicCommandContext(), null)).isEqualTo(42);
  }

  /**
   * {@link IndexLookup#estimatedSize} returns -1 when estimateHits
   * returns -1 (unknown).
   */
  @Test
  public void indexLookup_estimatedSize_unknownReturnsNegative() {
    var indexDesc = mock(IndexSearchDescriptor.class);
    when(indexDesc.estimateHits(any())).thenReturn(-1L);
    var desc = new IndexLookup(indexDesc);

    assertThat(desc.estimatedSize(new BasicCommandContext(), null)).isEqualTo(-1);
  }

  // =========================================================================
  // addIntersectionDescriptor — accumulation and Composite wrapping
  // =========================================================================

  /**
   * Adding a single descriptor sets it directly (no Composite wrapper).
   */
  @Test
  public void addIntersectionDescriptor_single_setsDirectly() {
    var et = createEdgeTraversal();
    var desc = mock(EdgeRidLookup.class);
    et.addIntersectionDescriptor(desc);

    assertThat(et.getIntersectionDescriptor()).isSameAs(desc);
  }

  /**
   * Adding a second descriptor wraps both in a Composite.
   */
  @Test
  public void addIntersectionDescriptor_two_createsComposite() {
    var et = createEdgeTraversal();
    var desc1 = mock(EdgeRidLookup.class);
    var desc2 = mock(EdgeRidLookup.class);
    et.addIntersectionDescriptor(desc1);
    et.addIntersectionDescriptor(desc2);

    assertThat(et.getIntersectionDescriptor())
        .isInstanceOf(RidFilterDescriptor.Composite.class);
    var composite = (RidFilterDescriptor.Composite) et.getIntersectionDescriptor();
    assertThat(composite.descriptors()).containsExactly(desc1, desc2);
  }

  // =========================================================================
  // Composite — resolve, cacheKey, and estimatedSize
  // =========================================================================

  /**
   * Composite.resolve() intersects the results of both descriptors.
   */
  @Test
  public void composite_resolve_intersectsResults() {
    var desc1 = mock(EdgeRidLookup.class);
    var desc2 = mock(EdgeRidLookup.class);

    var set1 = new RidSet();
    set1.add(new RecordId(10, 1));
    set1.add(new RecordId(10, 2));

    var set2 = new RidSet();
    set2.add(new RecordId(10, 2));
    set2.add(new RecordId(10, 3));

    when(desc1.resolve(any(), any())).thenReturn(set1);
    when(desc2.resolve(any(), any())).thenReturn(set2);

    var composite = new RidFilterDescriptor.Composite(
        List.of(desc1, desc2));
    var result = composite.resolve(new BasicCommandContext(), null);

    assertThat(result).isNotNull();
    assertThat(result.size()).isEqualTo(1);
    assertThat(result.contains(new RecordId(10, 2))).isTrue();
  }

  /**
   * Composite.resolve() returns the other set when one descriptor
   * returns null (cap exceeded).
   */
  @Test
  public void composite_resolve_oneNull_returnsOther() {
    var desc1 = mock(EdgeRidLookup.class);
    var desc2 = mock(EdgeRidLookup.class);

    var set1 = singletonRidSet(10, 1);
    when(desc1.resolve(any(), any())).thenReturn(set1);
    when(desc2.resolve(any(), any())).thenReturn(null);

    var composite = new RidFilterDescriptor.Composite(
        List.of(desc1, desc2));
    var result = composite.resolve(new BasicCommandContext(), null);

    assertThat(result).isSameAs(set1);
  }

  /**
   * Composite.cacheKey() returns a list of the inner descriptors' keys.
   */
  @Test
  public void composite_cacheKey_returnsList() {
    var desc1 = mock(EdgeRidLookup.class);
    var desc2 = mock(EdgeRidLookup.class);
    var key1 = new RecordId(5, 1);
    var key2 = new RecordId(5, 2);
    when(desc1.cacheKey(any())).thenReturn(key1);
    when(desc2.cacheKey(any())).thenReturn(key2);

    var composite = new RidFilterDescriptor.Composite(
        List.of(desc1, desc2));
    var key = composite.cacheKey(new BasicCommandContext());

    assertThat(key).isEqualTo(List.of(key1, key2));
  }

  /**
   * Composite.estimatedSize() returns the minimum of child estimates.
   */
  @Test
  public void composite_estimatedSize_returnsMinimum() {
    var desc1 = mock(EdgeRidLookup.class);
    var desc2 = mock(EdgeRidLookup.class);
    when(desc1.estimatedSize(any(), any())).thenReturn(500);
    when(desc2.estimatedSize(any(), any())).thenReturn(100);

    var composite = new RidFilterDescriptor.Composite(
        List.of(desc1, desc2));
    assertThat(composite.estimatedSize(new BasicCommandContext(), null))
        .isEqualTo(100);
  }

  /**
   * Composite.estimatedSize() ignores children that return -1.
   */
  @Test
  public void composite_estimatedSize_ignoresUnknown() {
    var desc1 = mock(EdgeRidLookup.class);
    var desc2 = mock(EdgeRidLookup.class);
    when(desc1.estimatedSize(any(), any())).thenReturn(-1);
    when(desc2.estimatedSize(any(), any())).thenReturn(200);

    var composite = new RidFilterDescriptor.Composite(
        List.of(desc1, desc2));
    assertThat(composite.estimatedSize(new BasicCommandContext(), null))
        .isEqualTo(200);
  }

  /**
   * Composite.estimatedSize() returns -1 when all children are unknown.
   */
  @Test
  public void composite_estimatedSize_allUnknown_returnsNegative() {
    var desc1 = mock(EdgeRidLookup.class);
    var desc2 = mock(EdgeRidLookup.class);
    when(desc1.estimatedSize(any(), any())).thenReturn(-1);
    when(desc2.estimatedSize(any(), any())).thenReturn(-1);

    var composite = new RidFilterDescriptor.Composite(
        List.of(desc1, desc2));
    assertThat(composite.estimatedSize(new BasicCommandContext(), null))
        .isEqualTo(-1);
  }

  // =========================================================================
  // Multi-entry cache — multiple distinct keys are retained
  // =========================================================================

  /**
   * The multi-entry cache retains results for multiple distinct keys,
   * avoiding redundant resolve() calls when keys recur.
   */
  @Test
  public void resolveWithCache_multipleKeys_allCached() {
    var et = createEdgeTraversal();
    var desc = mock(EdgeRidLookup.class);
    var key1 = new RecordId(5, 1);
    var key2 = new RecordId(5, 2);
    var ridSet1 = singletonRidSet(10, 1);
    var ridSet2 = singletonRidSet(10, 2);

    when(desc.cacheKey(any()))
        .thenReturn(key1, key2, key1, key2);
    when(desc.resolve(any(), any()))
        .thenReturn(ridSet1, ridSet2);
    stubSmallEstimate(desc);
    et.setIntersectionDescriptor(desc);
    var ctx = new BasicCommandContext();

    // First calls — cache misses
    assertThat(et.resolveWithCache(ctx, LARGE_LINKBAG)).isSameAs(ridSet1);
    assertThat(et.resolveWithCache(ctx, LARGE_LINKBAG)).isSameAs(ridSet2);
    // Second calls — cache hits
    assertThat(et.resolveWithCache(ctx, LARGE_LINKBAG)).isSameAs(ridSet1);
    assertThat(et.resolveWithCache(ctx, LARGE_LINKBAG)).isSameAs(ridSet2);

    // resolve() called only twice (once per unique key)
    verify(desc, times(2)).resolve(any(), any());
  }

  // =========================================================================
  // Lazy resolution — three-way decision tests
  // =========================================================================

  /**
   * Scenario A: estimatedSize exceeds maxRidSetSize → null is cached
   * permanently. resolve() is never called.
   */
  @Test
  public void resolveWithCache_estimateExceedsCap_cachesNull() {
    var et = createEdgeTraversal();
    var desc = mock(EdgeRidLookup.class);
    var key = new RecordId(5, 1);

    when(desc.cacheKey(any())).thenReturn(key);
    when(desc.estimatedSize(any(), any()))
        .thenReturn(TraversalPreFilterHelper.maxRidSetSize() + 1);
    et.setIntersectionDescriptor(desc);
    var ctx = new BasicCommandContext();

    // Both calls return null
    assertThat(et.resolveWithCache(ctx, LARGE_LINKBAG)).isNull();
    assertThat(et.resolveWithCache(ctx, LARGE_LINKBAG)).isNull();

    // resolve() never called — estimate rejected it
    verify(desc, never()).resolve(any(), any());
  }

  /**
   * Scenario B: estimatedSize is small but linkBagSize is too small for
   * the ratio check → null returned but NOT cached. A later call with
   * a larger linkBagSize triggers resolution.
   */
  @Test
  public void resolveWithCache_smallLinkBag_defersResolution() {
    var et = createEdgeTraversal();
    var desc = mock(EdgeRidLookup.class);
    var key = new RecordId(5, 1);
    var ridSet = singletonRidSet(10, 1);

    when(desc.cacheKey(any())).thenReturn(key);
    // estimatedSize = 500, which fails selectivity check for linkBag=50
    // (500/50 = 10.0 > edgeLookupMaxRatio)
    when(desc.estimatedSize(any(), any())).thenReturn(500);
    // Selectivity check: fails for small linkBag (estimate=500),
    // passes for large linkBag (estimate=500 vs LARGE_LINKBAG)
    when(desc.passesSelectivityCheck(eq(500), eq(50), any()))
        .thenReturn(false);
    when(desc.passesSelectivityCheck(eq(500), eq(LARGE_LINKBAG), any()))
        .thenReturn(true);
    when(desc.resolve(any(), any())).thenReturn(ridSet);
    et.setIntersectionDescriptor(desc);
    var ctx = new BasicCommandContext();

    // Small link bag → selectivity check fails → null, not cached
    assertThat(et.resolveWithCache(ctx, 50)).isNull();
    verify(desc, never()).resolve(any(), any());

    // Large link bag → selectivity check passes → resolve and cache
    assertThat(et.resolveWithCache(ctx, LARGE_LINKBAG)).isSameAs(ridSet);
    verify(desc, times(1)).resolve(any(), any());

    // Verify passesSelectivityCheck was called for both attempts — the
    // EdgeRidLookup rejection is NOT cached (unlike IndexLookup), so
    // each resolveWithCache call re-checks selectivity.
    verify(desc, times(2))
        .passesSelectivityCheck(anyInt(), anyInt(), any());

    // Subsequent call with same key → cache hit
    assertThat(et.resolveWithCache(ctx, LARGE_LINKBAG)).isSameAs(ridSet);
    verify(desc, times(1)).resolve(any(), any());
  }

  /**
   * Scenario C: estimatedSize is unknown (-1) → ratio check is skipped,
   * resolution proceeds unconditionally (conservative behavior).
   */
  @Test
  public void resolveWithCache_unknownEstimate_proceedsToResolve() {
    var et = createEdgeTraversal();
    var desc = mock(EdgeRidLookup.class);
    var key = new RecordId(5, 1);
    var ridSet = singletonRidSet(10, 1);

    when(desc.cacheKey(any())).thenReturn(key);
    when(desc.estimatedSize(any(), any())).thenReturn(-1);
    when(desc.resolve(any(), any())).thenReturn(ridSet);
    et.setIntersectionDescriptor(desc);
    var ctx = new BasicCommandContext();

    // Unknown estimate → skip ratio check → resolve
    assertThat(et.resolveWithCache(ctx, 50)).isSameAs(ridSet);
    verify(desc, times(1)).resolve(any(), any());
  }

  /**
   * Scenario D (mixed keys): vertex 1 (target=X, small linkBag) defers;
   * vertex 2 (target=Y, large linkBag) resolves Y and caches;
   * vertex 3 (target=X, large linkBag) resolves X and caches;
   * vertex 4 (target=Y) hits cache.
   */
  @Test
  public void resolveWithCache_mixedKeys_independentResolution() {
    var et = createEdgeTraversal();
    var desc = mock(EdgeRidLookup.class);
    var keyX = new RecordId(5, 1);
    var keyY = new RecordId(5, 2);
    var ridSetX = singletonRidSet(10, 1);
    var ridSetY = singletonRidSet(10, 2);

    when(desc.cacheKey(any()))
        .thenReturn(keyX, keyY, keyX, keyY);
    when(desc.estimatedSize(any(), any())).thenReturn(500);
    // Selectivity check: fails for small linkBag, passes for large
    when(desc.passesSelectivityCheck(eq(500), eq(50), any()))
        .thenReturn(false);
    when(desc.passesSelectivityCheck(eq(500), eq(LARGE_LINKBAG), any()))
        .thenReturn(true);
    // resolve() is called in order: first for keyY (vertex 2), then keyX (vertex 3)
    when(desc.resolve(any(), any())).thenReturn(ridSetY, ridSetX);
    et.setIntersectionDescriptor(desc);
    var ctx = new BasicCommandContext();

    // Vertex 1: target=X, small linkBag → selectivity fails → deferred
    assertThat(et.resolveWithCache(ctx, 50)).isNull();

    // Vertex 2: target=Y, large linkBag → first resolve() → ridSetY
    assertThat(et.resolveWithCache(ctx, LARGE_LINKBAG)).isSameAs(ridSetY);

    // Vertex 3: target=X, large linkBag → second resolve() → ridSetX
    assertThat(et.resolveWithCache(ctx, LARGE_LINKBAG)).isSameAs(ridSetX);

    // Vertex 4: target=Y → cache hit → ridSetY
    assertThat(et.resolveWithCache(ctx, LARGE_LINKBAG)).isSameAs(ridSetY);

    verify(desc, times(2)).resolve(any(), any());
  }

  // =========================================================================
  // passesSelectivityCheck — DirectRid (always true)
  // =========================================================================

  /** DirectRid always passes regardless of inputs. */
  @Test
  public void directRid_passesSelectivityCheck_alwaysTrue() {
    var expr = mock(SQLExpression.class);
    var desc = new DirectRid(expr);
    var ctx = new BasicCommandContext();

    assertThat(desc.passesSelectivityCheck(0, 0, ctx)).isTrue();
    assertThat(desc.passesSelectivityCheck(Integer.MAX_VALUE, 1, ctx)).isTrue();
    assertThat(desc.passesSelectivityCheck(100, 0, ctx)).isTrue();
  }

  // =========================================================================
  // passesSelectivityCheck — EdgeRidLookup (overlap ratio)
  // =========================================================================

  /** Small ratio (highly selective) passes the overlap check. */
  @Test
  public void edgeRidLookup_passesSelectivityCheck_smallRatio_passes() {
    var expr = mock(SQLExpression.class);
    var desc = new EdgeRidLookup("KNOWS", "out", expr, false);
    var ctx = new BasicCommandContext();

    // 100 / 10000 = 0.01, well below 0.8
    assertThat(desc.passesSelectivityCheck(100, 10_000, ctx)).isTrue();
  }

  /** Ratio at exact boundary (0.8) passes (uses <=). */
  @Test
  public void edgeRidLookup_passesSelectivityCheck_atBoundary_passes() {
    var expr = mock(SQLExpression.class);
    var desc = new EdgeRidLookup("KNOWS", "out", expr, false);
    var ctx = new BasicCommandContext();

    // 80 / 100 = 0.8 = edgeLookupMaxRatio
    assertThat(desc.passesSelectivityCheck(80, 100, ctx)).isTrue();
  }

  /** Ratio above boundary fails. */
  @Test
  public void edgeRidLookup_passesSelectivityCheck_aboveBoundary_fails() {
    var expr = mock(SQLExpression.class);
    var desc = new EdgeRidLookup("KNOWS", "out", expr, false);
    var ctx = new BasicCommandContext();

    // 81 / 100 = 0.81 > 0.8
    assertThat(desc.passesSelectivityCheck(81, 100, ctx)).isFalse();
  }

  /** Zero linkBagSize passes conservatively (avoids division by zero). */
  @Test
  public void edgeRidLookup_passesSelectivityCheck_zeroLinkBag_passes() {
    var expr = mock(SQLExpression.class);
    var desc = new EdgeRidLookup("KNOWS", "out", expr, false);
    var ctx = new BasicCommandContext();

    assertThat(desc.passesSelectivityCheck(100, 0, ctx)).isTrue();
  }

  /** Negative linkBagSize passes conservatively. */
  @Test
  public void edgeRidLookup_passesSelectivityCheck_negativeLinkBag_passes() {
    var expr = mock(SQLExpression.class);
    var desc = new EdgeRidLookup("KNOWS", "out", expr, false);
    var ctx = new BasicCommandContext();

    assertThat(desc.passesSelectivityCheck(100, -1, ctx)).isTrue();
  }

  /** Zero resolvedSize always passes (most selective). */
  @Test
  public void edgeRidLookup_passesSelectivityCheck_zeroResolved_passes() {
    var expr = mock(SQLExpression.class);
    var desc = new EdgeRidLookup("KNOWS", "out", expr, false);
    var ctx = new BasicCommandContext();

    assertThat(desc.passesSelectivityCheck(0, 1000, ctx)).isTrue();
  }

  /** Negative resolvedSize (-1 = unknown estimate) passes conservatively. */
  @Test
  public void edgeRidLookup_passesSelectivityCheck_negativeResolved_passes() {
    var expr = mock(SQLExpression.class);
    var desc = new EdgeRidLookup("KNOWS", "out", expr, false);
    var ctx = new BasicCommandContext();

    assertThat(desc.passesSelectivityCheck(-1, 100, ctx)).isTrue();
  }

  // =========================================================================
  // passesSelectivityCheck — IndexLookup (class-level selectivity)
  // =========================================================================

  /** Low selectivity (highly selective) passes. */
  @Test
  public void indexLookup_passesSelectivityCheck_lowSelectivity_passes() {
    var indexDesc = mock(IndexSearchDescriptor.class);
    when(indexDesc.estimateSelectivity(any())).thenReturn(0.03);
    var desc = new IndexLookup(indexDesc);
    var ctx = new BasicCommandContext();

    assertThat(desc.passesSelectivityCheck(100, 200, ctx)).isTrue();
  }

  /** Selectivity at exact threshold (0.95) passes (uses <=). */
  @Test
  public void indexLookup_passesSelectivityCheck_atThreshold_passes() {
    var indexDesc = mock(IndexSearchDescriptor.class);
    when(indexDesc.estimateSelectivity(any())).thenReturn(0.95);
    var desc = new IndexLookup(indexDesc);
    var ctx = new BasicCommandContext();

    assertThat(desc.passesSelectivityCheck(0, 0, ctx)).isTrue();
  }

  /** Selectivity above threshold fails. */
  @Test
  public void indexLookup_passesSelectivityCheck_aboveThreshold_fails() {
    var indexDesc = mock(IndexSearchDescriptor.class);
    when(indexDesc.estimateSelectivity(any())).thenReturn(0.96);
    var desc = new IndexLookup(indexDesc);
    var ctx = new BasicCommandContext();

    assertThat(desc.passesSelectivityCheck(0, 0, ctx)).isFalse();
  }

  /** Unknown selectivity (-1.0) passes conservatively. */
  @Test
  public void indexLookup_passesSelectivityCheck_unknownSelectivity_passes() {
    var indexDesc = mock(IndexSearchDescriptor.class);
    when(indexDesc.estimateSelectivity(any())).thenReturn(-1.0);
    var desc = new IndexLookup(indexDesc);
    var ctx = new BasicCommandContext();

    assertThat(desc.passesSelectivityCheck(0, 0, ctx)).isTrue();
  }

  /** Zero selectivity (perfect filter) passes. */
  @Test
  public void indexLookup_passesSelectivityCheck_zeroSelectivity_passes() {
    var indexDesc = mock(IndexSearchDescriptor.class);
    when(indexDesc.estimateSelectivity(any())).thenReturn(0.0);
    var desc = new IndexLookup(indexDesc);
    var ctx = new BasicCommandContext();

    assertThat(desc.passesSelectivityCheck(0, 0, ctx)).isTrue();
  }

  /** IndexLookup ignores resolvedSize and linkBagSize — same result. */
  @Test
  public void indexLookup_passesSelectivityCheck_ignoresParameters() {
    var indexDesc = mock(IndexSearchDescriptor.class);
    when(indexDesc.estimateSelectivity(any())).thenReturn(0.5);
    var desc = new IndexLookup(indexDesc);
    var ctx = new BasicCommandContext();

    assertThat(desc.passesSelectivityCheck(1, 1, ctx))
        .as("resolvedSize=1, linkBagSize=1").isTrue();
    assertThat(desc.passesSelectivityCheck(100_000, 1, ctx))
        .as("resolvedSize=100000, linkBagSize=1").isTrue();
    assertThat(desc.passesSelectivityCheck(1, 100_000, ctx))
        .as("resolvedSize=1, linkBagSize=100000").isTrue();
  }

  // =========================================================================
  // passesSelectivityCheck — Composite (any child passes)
  // =========================================================================

  /** Composite passes if at least one child passes. */
  @Test
  public void composite_passesSelectivityCheck_oneChildPasses_returnsTrue() {
    // Use EdgeRidLookup mocks (permitted type of sealed interface)
    var failing = mock(EdgeRidLookup.class);
    when(failing.passesSelectivityCheck(anyInt(), anyInt(), any()))
        .thenReturn(false);
    var passing = mock(EdgeRidLookup.class);
    when(passing.passesSelectivityCheck(anyInt(), anyInt(), any()))
        .thenReturn(true);
    var composite = new RidFilterDescriptor.Composite(
        List.of(failing, passing));
    var ctx = new BasicCommandContext();

    assertThat(composite.passesSelectivityCheck(100, 1000, ctx)).isTrue();
  }

  /** Composite fails when all children fail. */
  @Test
  public void composite_passesSelectivityCheck_allFail_returnsFalse() {
    var f1 = mock(EdgeRidLookup.class);
    when(f1.passesSelectivityCheck(anyInt(), anyInt(), any()))
        .thenReturn(false);
    var f2 = mock(EdgeRidLookup.class);
    when(f2.passesSelectivityCheck(anyInt(), anyInt(), any()))
        .thenReturn(false);
    var composite = new RidFilterDescriptor.Composite(
        List.of(f1, f2));
    var ctx = new BasicCommandContext();

    assertThat(composite.passesSelectivityCheck(100, 1000, ctx)).isFalse();
  }

  /** Empty composite returns false. */
  @Test
  public void composite_passesSelectivityCheck_empty_returnsFalse() {
    var composite = new RidFilterDescriptor.Composite(List.of());
    var ctx = new BasicCommandContext();

    assertThat(composite.passesSelectivityCheck(100, 1000, ctx)).isFalse();
  }

  // =========================================================================
  // findIndexLookup — Composite helper
  // =========================================================================

  /** findIndexLookup returns the first IndexLookup child. */
  @Test
  public void composite_findIndexLookup_present_returnsIt() {
    var edgeLookup = mock(RidFilterDescriptor.EdgeRidLookup.class);
    var indexLookup = mock(RidFilterDescriptor.IndexLookup.class);
    var composite = new RidFilterDescriptor.Composite(
        List.of(edgeLookup, indexLookup));

    assertThat(composite.findIndexLookup()).isSameAs(indexLookup);
  }

  /** findIndexLookup returns null when no IndexLookup child exists. */
  @Test
  public void composite_findIndexLookup_absent_returnsNull() {
    var edgeLookup1 = mock(RidFilterDescriptor.EdgeRidLookup.class);
    var edgeLookup2 = mock(RidFilterDescriptor.EdgeRidLookup.class);
    var composite = new RidFilterDescriptor.Composite(
        List.of(edgeLookup1, edgeLookup2));

    assertThat(composite.findIndexLookup()).isNull();
  }

  /** findIndexLookup returns null for empty composite. */
  @Test
  public void composite_findIndexLookup_empty_returnsNull() {
    var composite = new RidFilterDescriptor.Composite(List.of());

    assertThat(composite.findIndexLookup()).isNull();
  }

  // =========================================================================
  // passesSelectivityCheck — boundary values (ratio=1.0, selectivity=1.0)
  // =========================================================================

  /** When resolvedSize equals linkBagSize (ratio = 1.0), filter is useless. */
  @Test
  public void edgeRidLookup_passesSelectivityCheck_fullOverlap_fails() {
    var expr = mock(SQLExpression.class);
    var desc = new EdgeRidLookup("KNOWS", "out", expr, false);
    var ctx = new BasicCommandContext();

    // 1000 / 1000 = 1.0 > 0.8
    assertThat(desc.passesSelectivityCheck(1000, 1000, ctx)).isFalse();
  }

  /** Selectivity 1.0 (all records match) fails — no filtering benefit. */
  @Test
  public void indexLookup_passesSelectivityCheck_fullSelectivity_fails() {
    var indexDesc = mock(IndexSearchDescriptor.class);
    when(indexDesc.estimateSelectivity(any())).thenReturn(1.0);
    var desc = new IndexLookup(indexDesc);
    var ctx = new BasicCommandContext();

    assertThat(desc.passesSelectivityCheck(0, 0, ctx)).isFalse();
  }

  // =========================================================================
  // resolveWithCache — IndexLookup selectivity rejection caching
  // =========================================================================

  /**
   * When IndexLookup fails the selectivity check, the rejection is cached
   * permanently (selectivity is class-level, constant per query). Subsequent
   * calls with the same key return null without re-checking selectivity.
   * Contrast with {@link #resolveWithCache_smallLinkBag_defersResolution}
   * where EdgeRidLookup rejections are NOT cached.
   */
  @Test
  public void resolveWithCache_indexLookupSelectivityFails_cachesNull() {
    var et = createEdgeTraversal();
    // High selectivity (0.96 > 0.95 threshold) — the IndexLookup path
    // in resolveWithCache caches this selectivity and rejects permanently.
    var indexDesc = mock(IndexSearchDescriptor.class);
    when(indexDesc.estimateSelectivity(any())).thenReturn(0.96);
    var desc = mock(IndexLookup.class);
    when(desc.indexDescriptor()).thenReturn(indexDesc);
    var key = "Post.creationDate";

    when(desc.cacheKey(any())).thenReturn(key);
    when(desc.estimatedSize(any(), any())).thenReturn(500);
    et.setIntersectionDescriptor(desc);
    var ctx = new BasicCommandContext();

    // First call: selectivity too high → null, cached for IndexLookup
    assertThat(et.resolveWithCache(ctx, LARGE_LINKBAG)).isNull();

    // Second call: cache hit → null, estimateSelectivity NOT re-called
    assertThat(et.resolveWithCache(ctx, LARGE_LINKBAG)).isNull();

    // estimateSelectivity called only once (second call is cache hit)
    verify(indexDesc, times(1)).estimateSelectivity(any());
    // resolve() never called
    verify(desc, never()).resolve(any(), any());
  }

  // =========================================================================
  // computeMinNeighborsForBuild — build amortization formula
  // =========================================================================

  /**
   * Normal case: estimatedSize=100_000, loadToScanRatio=100, selectivity=0.5.
   * Expected: 100_000 / (100 * 0.5) = 2000.0
   */
  @Test
  public void computeMinNeighborsForBuild_normalCase() {
    double result = EdgeTraversal.computeMinNeighborsForBuild(
        100_000, 100.0, 0.5);
    assertThat(result).isEqualTo(2000.0);
  }

  /** Zero estimatedSize → 0.0 (build immediately — trivially small). */
  @Test
  public void computeMinNeighborsForBuild_zeroEstimatedSize_returnsZero() {
    assertThat(EdgeTraversal.computeMinNeighborsForBuild(0, 100.0, 0.5))
        .isEqualTo(0.0);
  }

  /** Negative estimatedSize → 0.0 (unknown — build immediately). */
  @Test
  public void computeMinNeighborsForBuild_negativeEstimatedSize_returnsZero() {
    assertThat(EdgeTraversal.computeMinNeighborsForBuild(-1, 100.0, 0.5))
        .isEqualTo(0.0);
  }

  /**
   * Selectivity = 0.0 (perfect filter — every scan saves a load).
   * Expected: estimatedSize / loadToScanRatio = 100_000 / 100 = 1000.0
   */
  @Test
  public void computeMinNeighborsForBuild_zeroSelectivity_perfectFilter() {
    double result = EdgeTraversal.computeMinNeighborsForBuild(
        100_000, 100.0, 0.0);
    assertThat(result).isEqualTo(1000.0);
  }

  /**
   * Selectivity = 0.99 (nearly all records match — filter saves very little).
   * Expected: 100_000 / (100 * 0.01) = 100_000.0 — very large threshold,
   * effectively deferring the build until many neighbors are accumulated.
   */
  @Test
  public void computeMinNeighborsForBuild_highSelectivity_defersBuilds() {
    double result = EdgeTraversal.computeMinNeighborsForBuild(
        100_000, 100.0, 0.99);
    assertThat(result).isCloseTo(100_000.0, Offset.offset(1e-6));
  }

  /**
   * Selectivity >= 1.0 → Double.MAX_VALUE (never build — no filtering
   * benefit when all records match).
   */
  @Test
  public void computeMinNeighborsForBuild_selectivityOne_neverBuilds() {
    assertThat(EdgeTraversal.computeMinNeighborsForBuild(
        100_000, 100.0, 1.0)).isEqualTo(Double.MAX_VALUE);
  }

  /** Selectivity > 1.0 is treated the same as 1.0 → Double.MAX_VALUE. */
  @Test
  public void computeMinNeighborsForBuild_selectivityAboveOne_neverBuilds() {
    assertThat(EdgeTraversal.computeMinNeighborsForBuild(
        100_000, 100.0, 1.5)).isEqualTo(Double.MAX_VALUE);
  }

  /**
   * Unknown selectivity (-1.0) → 0.0 (build immediately to be conservative,
   * per review finding T1).
   */
  @Test
  public void computeMinNeighborsForBuild_unknownSelectivity_buildsImmediately() {
    assertThat(EdgeTraversal.computeMinNeighborsForBuild(
        100_000, 100.0, -1.0)).isEqualTo(0.0);
  }

  /**
   * Verifies that the loadToScanRatio parameter actually influences the result.
   * With ratio=50 instead of 100, the threshold doubles:
   * 100_000 / (50 * 0.5) = 4000.0
   */
  @Test
  public void computeMinNeighborsForBuild_differentLoadToScanRatio() {
    double result = EdgeTraversal.computeMinNeighborsForBuild(
        100_000, 50.0, 0.5);
    assertThat(result).isEqualTo(4000.0);
  }

  /**
   * estimatedSize = 1 is the smallest positive value — boundary just above
   * the early-return guard. Verifies it enters the normal formula path.
   * Expected: 1 / (100.0 * 0.5) = 0.02
   */
  @Test
  public void computeMinNeighborsForBuild_estimatedSizeOne_usesFormula() {
    double result = EdgeTraversal.computeMinNeighborsForBuild(
        1, 100.0, 0.5);
    assertThat(result).isEqualTo(0.02);
  }

  /**
   * Integer.MAX_VALUE is the largest input from IndexLookup.estimatedSize().
   * Verifies the formula produces a finite, reasonable double at the int
   * boundary without overflow. Expected: 2_147_483_647 / (100 * 0.5) =
   * 42_949_672.94
   */
  @Test
  public void computeMinNeighborsForBuild_maxEstimatedSize_finiteResult() {
    double result = EdgeTraversal.computeMinNeighborsForBuild(
        Integer.MAX_VALUE, 100.0, 0.5);
    assertThat(result).isCloseTo(42_949_672.94, Offset.offset(0.01));
    assertThat(Double.isFinite(result)).isTrue();
  }

  /** Change detector — the default SSD-calibrated ratio must be 100. */
  @Test
  public void defaultLoadToScanRatio_isOneHundred() {
    assertThat(EdgeTraversal.DEFAULT_LOAD_TO_SCAN_RATIO).isEqualTo(100.0);
  }

  // =========================================================================
  // Build amortization guard — accumulate-and-trigger for IndexLookup
  // =========================================================================

  /**
   * Creates an IndexLookup descriptor with the given selectivity and
   * estimated hits. The descriptor's resolve() returns the given RidSet.
   */
  private IndexLookup stubIndexLookup(
      double selectivity, long estimatedHits, String indexName, RidSet ridSet) {
    var indexDesc = mock(IndexSearchDescriptor.class);
    when(indexDesc.estimateSelectivity(any())).thenReturn(selectivity);
    when(indexDesc.estimateHits(any())).thenReturn(estimatedHits);
    var index = mock(Index.class);
    when(index.getName()).thenReturn(indexName);
    when(indexDesc.getIndex()).thenReturn(index);

    var lookup = mock(IndexLookup.class);
    when(lookup.indexDescriptor()).thenReturn(indexDesc);
    when(lookup.cacheKey(any())).thenReturn(indexName);
    when(lookup.estimatedSize(any(), any()))
        .thenReturn((int) Math.min(estimatedHits, Integer.MAX_VALUE));
    when(lookup.passesSelectivityCheck(anyInt(), anyInt(), any()))
        .thenReturn(true);
    when(lookup.resolve(any(), any())).thenReturn(ridSet);
    return lookup;
  }

  /**
   * A single large vertex whose linkBagSize exceeds the amortization
   * threshold triggers materialization immediately on the first call.
   *
   * <p>With selectivity=0.5, estimatedSize=100_000, ratio=100:
   * minNeighbors = 100_000 / (100 * (1 - 0.5)) = 2000. A linkBag of
   * 10_000 exceeds 2000, so the first call materializes.
   */
  @Test
  public void resolveWithCache_indexLookup_singleLargeVertex_triggersImmediately() {
    var et = createEdgeTraversal();
    var ridSet = singletonRidSet(10, 1);
    var desc = stubIndexLookup(0.5, 100_000, "Post.date", ridSet);
    et.setIntersectionDescriptor(desc);
    var ctx = new BasicCommandContext();

    // linkBagSize=10_000 > threshold 2000 → materialize
    var result = et.resolveWithCache(ctx, 10_000);
    assertThat(result).isSameAs(ridSet);
    verify(desc, times(1)).resolve(any(), any());
    // Verify the accumulator path was entered (selectivity was fetched)
    verify(desc.indexDescriptor(), times(1)).estimateSelectivity(any());
  }

  /**
   * Multiple small vertices accumulate until the threshold is reached.
   * Vertices 1..N return null (deferred), vertex N+1 triggers materialization.
   *
   * <p>With selectivity=0.5, estimatedSize=100_000, ratio=100:
   * minNeighbors = 100_000 / (100 * (1 - 0.5)) = 2000. With
   * linkBagSize=500 per vertex, the first 3 calls are deferred
   * (accumulated = 500, 1000, 1500 &lt; 2000) and the 4th call
   * triggers (accumulated = 2000 >= 2000).
   */
  @Test
  public void resolveWithCache_indexLookup_multipleSmallVertices_accumulate() {
    var et = createEdgeTraversal();
    var ridSet = singletonRidSet(10, 1);
    var desc = stubIndexLookup(0.5, 100_000, "Post.date", ridSet);
    et.setIntersectionDescriptor(desc);
    var ctx = new BasicCommandContext();

    // Vertices 1-3: accumulated = 500, 1000, 1500 — all < 2000
    assertThat(et.resolveWithCache(ctx, 500)).isNull();
    assertThat(et.resolveWithCache(ctx, 500)).isNull();
    assertThat(et.resolveWithCache(ctx, 500)).isNull();
    // resolve() never called during deferral
    verify(desc, never()).resolve(any(), any());

    // Vertex 4: accumulated = 2000 >= ceil(2000) → triggers
    assertThat(et.resolveWithCache(ctx, 500)).isSameAs(ridSet);
    verify(desc, times(1)).resolve(any(), any());
  }

  /**
   * After the accumulator triggers materialization and caches the RidSet,
   * subsequent calls are cache hits — resolve() is not called again.
   */
  @Test
  public void resolveWithCache_indexLookup_afterTrigger_cacheHit() {
    var et = createEdgeTraversal();
    var ridSet = singletonRidSet(10, 1);
    var desc = stubIndexLookup(0.5, 100_000, "Post.date", ridSet);
    et.setIntersectionDescriptor(desc);
    var ctx = new BasicCommandContext();

    // Trigger on first call (large vertex)
    assertThat(et.resolveWithCache(ctx, 10_000)).isSameAs(ridSet);
    // Subsequent call — cache hit
    assertThat(et.resolveWithCache(ctx, 10_000)).isSameAs(ridSet);

    verify(desc, times(1)).resolve(any(), any());
  }

  /**
   * Deferred builds do NOT cache null. Verify by checking that subsequent
   * calls still go through the selectivity + accumulator path (not a
   * cache hit returning null).
   */
  @Test
  public void resolveWithCache_indexLookup_deferredBuild_doesNotCacheNull() {
    var et = createEdgeTraversal();
    var ridSet = singletonRidSet(10, 1);
    var desc = stubIndexLookup(0.5, 100_000, "Post.date", ridSet);
    et.setIntersectionDescriptor(desc);
    var ctx = new BasicCommandContext();

    // Small vertex — deferred (null, not cached)
    assertThat(et.resolveWithCache(ctx, 100)).isNull();
    verify(desc, never()).resolve(any(), any());

    // Large vertex — should trigger (not a cache-hit returning null)
    assertThat(et.resolveWithCache(ctx, 10_000)).isSameAs(ridSet);
    verify(desc, times(1)).resolve(any(), any());
  }

  /**
   * When estimateSelectivity returns -1.0 (unknown), the RidSet is
   * materialized immediately even with a tiny linkBag — the accumulator
   * is bypassed because unknown selectivity means we cannot compute a
   * meaningful build threshold.
   */
  @Test
  public void resolveWithCache_indexLookup_unknownSelectivity_materializesImmediately() {
    var et = createEdgeTraversal();
    var ridSet = singletonRidSet(10, 1);
    // selectivity=-1.0 (unknown), estimatedSize=100_000
    var desc = stubIndexLookup(-1.0, 100_000, "Post.date", ridSet);
    et.setIntersectionDescriptor(desc);
    var ctx = new BasicCommandContext();

    // Even with tiny linkBag, unknown selectivity → immediate materialization
    var result = et.resolveWithCache(ctx, 1);
    assertThat(result).isSameAs(ridSet);
    verify(desc, times(1)).resolve(any(), any());
  }

  /**
   * estimateSelectivity is called exactly once across multiple
   * resolveWithCache calls — the result is cached in
   * indexLookupSelectivity. Regression guard for the NaN-sentinel
   * caching mechanism.
   */
  @Test
  public void resolveWithCache_indexLookup_selectivityCachedAcrossCalls() {
    var et = createEdgeTraversal();
    var ridSet = singletonRidSet(10, 1);
    var desc = stubIndexLookup(0.5, 100_000, "Post.date", ridSet);
    et.setIntersectionDescriptor(desc);
    var ctx = new BasicCommandContext();

    // Three calls: deferred, deferred, trigger
    et.resolveWithCache(ctx, 500);
    et.resolveWithCache(ctx, 500);
    et.resolveWithCache(ctx, 10_000);

    // estimateSelectivity must be called exactly once (cached after first)
    verify(desc.indexDescriptor(), times(1)).estimateSelectivity(any());
  }

  // =========================================================================
  // Build amortization — regression and edge case tests
  // =========================================================================

  /**
   * Regression: EdgeRidLookup still uses per-vertex ratio check, not the
   * accumulator. Multiple calls with varying linkBag sizes should NOT
   * accumulate — each call is independently evaluated via
   * passesSelectivityCheck (per R1).
   */
  @Test
  public void resolveWithCache_edgeRidLookup_noAccumulation_perVertexRatio() {
    var et = createEdgeTraversal();
    var desc = mock(EdgeRidLookup.class);
    var key = new RecordId(5, 1);
    var ridSet = singletonRidSet(10, 1);

    when(desc.cacheKey(any())).thenReturn(key);
    // estimatedSize = 500 (small enough to be under maxRidSetSize)
    when(desc.estimatedSize(any(), any())).thenReturn(500);
    // Selectivity check: fails for linkBag=50, passes for linkBag=1000
    when(desc.passesSelectivityCheck(eq(500), eq(50), any()))
        .thenReturn(false);
    when(desc.passesSelectivityCheck(eq(500), eq(1000), any()))
        .thenReturn(true);
    when(desc.resolve(any(), any())).thenReturn(ridSet);
    et.setIntersectionDescriptor(desc);
    var ctx = new BasicCommandContext();

    // Call 1: small linkBag → ratio check fails → null (no accumulation)
    assertThat(et.resolveWithCache(ctx, 50)).isNull();
    // Call 2: small linkBag again → still fails (no accumulation helped)
    assertThat(et.resolveWithCache(ctx, 50)).isNull();
    // Call 3: large linkBag → ratio check passes → resolve
    assertThat(et.resolveWithCache(ctx, 1000)).isSameAs(ridSet);

    // Each call re-checks selectivity independently (not accumulated)
    verify(desc, times(3))
        .passesSelectivityCheck(anyInt(), anyInt(), any());
    verify(desc, times(1)).resolve(any(), any());
  }

  /**
   * Unknown estimatedSize (-1) with IndexLookup proceeds directly to
   * materialization (per R2/T2). The negative estimatedSize passes
   * through the accumulator but {@code computeMinNeighborsForBuild}
   * returns 0.0 for non-positive sizes, so the threshold is trivially
   * met on the first call.
   */
  @Test
  public void resolveWithCache_indexLookup_unknownEstimatedSize_proceedsToResolve() {
    var et = createEdgeTraversal();
    var ridSet = singletonRidSet(10, 1);
    // estimatedHits=-1 (unknown), selectivity=0.5
    var desc = stubIndexLookup(0.5, -1, "Post.date", ridSet);
    et.setIntersectionDescriptor(desc);
    var ctx = new BasicCommandContext();

    // Unknown estimate → bypass ratio check and accumulator → resolve
    var result = et.resolveWithCache(ctx, 1);
    assertThat(result).isSameAs(ridSet);
    verify(desc, times(1)).resolve(any(), any());
  }

  /**
   * copy() resets accumulator state: accumulatedLinkBagTotal = 0 (Java
   * default) and indexLookupSelectivity = NaN (field initializer). The
   * copied instance re-accumulates from scratch (per T3).
   *
   * <p>Discriminating test: the original accumulates 1500 (below
   * threshold 2000). If the copy leaked state, 1500+500=2000 would
   * trigger; if reset, 0+500=500 defers.
   */
  @Test
  public void copy_resetsAccumulatorState() {
    var et = createEdgeTraversal();
    var ridSet = singletonRidSet(10, 1);
    var desc = stubIndexLookup(0.5, 100_000, "Post.date", ridSet);
    et.setIntersectionDescriptor(desc);
    var ctx = new BasicCommandContext();

    // Accumulate 1500 on the original (below threshold of 2000)
    assertThat(et.resolveWithCache(ctx, 500)).isNull();
    assertThat(et.resolveWithCache(ctx, 500)).isNull();
    assertThat(et.resolveWithCache(ctx, 500)).isNull(); // accumulated=1500

    // Copy must start fresh — 500 should defer (not trigger at 1500+500=2000)
    var copy = et.copy();
    assertThat(copy.resolveWithCache(ctx, 500)).isNull();

    // Verify no resolve() was called (neither original nor copy triggered)
    verify(desc, never()).resolve(any(), any());

    // Verify the copy re-fetched selectivity (reset to NaN, not inherited)
    // Original call + copy call = 2 total
    verify(desc.indexDescriptor(), times(2)).estimateSelectivity(any());
  }

  /**
   * Threshold exactly met: accumulated == ceil(minNeighborsForBuild)
   * triggers materialization.
   *
   * <p>With selectivity=0.5, estimatedSize=100_000, ratio=100:
   * minNeighbors = 100_000 / (100 * (1 - 0.5)) = 2000.0, ceil = 2000.
   * A single call with linkBagSize=2000 triggers immediately.
   */
  @Test
  public void resolveWithCache_indexLookup_thresholdExactlyMet_triggers() {
    var et = createEdgeTraversal();
    var ridSet = singletonRidSet(10, 1);
    var desc = stubIndexLookup(0.5, 100_000, "Post.date", ridSet);
    et.setIntersectionDescriptor(desc);
    var ctx = new BasicCommandContext();

    // Exactly at threshold → triggers
    assertThat(et.resolveWithCache(ctx, 2000)).isSameAs(ridSet);
    verify(desc, times(1)).resolve(any(), any());
  }

  /**
   * Threshold off-by-one: accumulated = ceil(minNeighborsForBuild) - 1
   * defers materialization.
   *
   * <p>With selectivity=0.5, estimatedSize=100_000, ratio=100:
   * minNeighbors = 2000. linkBagSize=1999 → accumulated=1999 < 2000.
   */
  @Test
  public void resolveWithCache_indexLookup_thresholdOffByOne_defers() {
    var et = createEdgeTraversal();
    var ridSet = singletonRidSet(10, 1);
    var desc = stubIndexLookup(0.5, 100_000, "Post.date", ridSet);
    et.setIntersectionDescriptor(desc);
    var ctx = new BasicCommandContext();

    // One below threshold → defers
    assertThat(et.resolveWithCache(ctx, 1999)).isNull();
    verify(desc, never()).resolve(any(), any());

    // One more to push over → triggers
    assertThat(et.resolveWithCache(ctx, 1)).isSameAs(ridSet);
    verify(desc, times(1)).resolve(any(), any());
  }

  /**
   * estimatedSize = 0 with IndexLookup: computeMinNeighborsForBuild
   * returns 0.0, so the threshold is immediately met — materialization
   * on the first call regardless of linkBagSize.
   */
  @Test
  public void resolveWithCache_indexLookup_zeroEstimatedSize_triggersImmediately() {
    var et = createEdgeTraversal();
    var ridSet = singletonRidSet(10, 1);
    // estimatedHits=0 → minNeighbors=0 → triggers immediately
    var desc = stubIndexLookup(0.5, 0, "Post.date", ridSet);
    et.setIntersectionDescriptor(desc);
    var ctx = new BasicCommandContext();

    assertThat(et.resolveWithCache(ctx, 1)).isSameAs(ridSet);
    verify(desc, times(1)).resolve(any(), any());
  }

  /**
   * Composite descriptor WITHOUT an IndexLookup child does NOT use the
   * accumulator — findIndexLookup() returns null, so the code falls
   * through to the passesSelectivityCheck path. Verifies that
   * EdgeRidLookup-only Composites retain per-vertex ratio checks.
   */
  @Test
  public void resolveWithCache_compositeWithoutIndexLookup_noAccumulation() {
    var et = createEdgeTraversal();
    var ridSet = singletonRidSet(10, 1);

    // Mock a Composite with no IndexLookup child — findIndexLookup()
    // returns null by default, so the accumulator is NOT used.
    var composite = mock(RidFilterDescriptor.Composite.class);
    when(composite.cacheKey(any())).thenReturn("composite-key");
    when(composite.estimatedSize(any(), any())).thenReturn(100_000);
    // passesSelectivityCheck fails for small linkBag
    when(composite.passesSelectivityCheck(eq(100_000), eq(50), any()))
        .thenReturn(false);
    when(composite.passesSelectivityCheck(eq(100_000), eq(LARGE_LINKBAG), any()))
        .thenReturn(true);
    when(composite.resolve(any(), any())).thenReturn(ridSet);
    et.setIntersectionDescriptor(composite);
    var ctx = new BasicCommandContext();

    // Small linkBag → passesSelectivityCheck fails → null (per-vertex, no
    // accumulation)
    assertThat(et.resolveWithCache(ctx, 50)).isNull();
    assertThat(et.resolveWithCache(ctx, 50)).isNull();

    // Large linkBag → passesSelectivityCheck passes → resolve
    assertThat(et.resolveWithCache(ctx, LARGE_LINKBAG)).isSameAs(ridSet);

    // Each call re-checks selectivity (no accumulation shortcut)
    verify(composite, times(3))
        .passesSelectivityCheck(anyInt(), anyInt(), any());
  }

  /**
   * Composite descriptor WITH an IndexLookup child uses the build
   * amortization accumulator — findIndexLookup() returns the child,
   * so selectivity is cached and the accumulator defers materialization
   * until the threshold is met. selectivity=0.5, estimatedSize=100_000
   * → threshold = 100_000 / (100 * 0.5) = 2000.
   */
  @Test
  public void resolveWithCache_compositeWithIndexLookup_usesAccumulation() {
    var et = createEdgeTraversal();
    var ridSet = singletonRidSet(10, 1);

    // Build a real Composite with an IndexLookup child.
    var indexLookup = stubIndexLookup(0.5, 100_000, "Post.date", ridSet);
    var edgeLookup = mock(RidFilterDescriptor.EdgeRidLookup.class);
    when(edgeLookup.estimatedSize(any(), any())).thenReturn(500);
    when(edgeLookup.cacheKey(any())).thenReturn(new RecordId(10, 1));
    when(edgeLookup.resolve(any(), any())).thenReturn(ridSet);

    var composite = new RidFilterDescriptor.Composite(
        List.of(edgeLookup, indexLookup));
    et.setIntersectionDescriptor(composite);
    var ctx = new BasicCommandContext();

    // Composite.estimatedSize() = min(500, 100_000) = 500.
    // IndexLookup selectivity = 0.5.
    // threshold = 500 / (100 * 0.5) = 10.
    // linkBag=5 → accumulated=5 < 10 → defers (BUILD_NOT_AMORTIZED)
    assertThat(et.resolveWithCache(ctx, 5)).isNull();

    // linkBag=4 → accumulated=9 < 10 → still defers
    assertThat(et.resolveWithCache(ctx, 4)).isNull();

    // linkBag=1 → accumulated=10 >= 10 → triggers materialization
    assertThat(et.resolveWithCache(ctx, 1)).isNotNull();
  }

  /**
   * Composite with an IndexLookup child whose selectivity exceeds the
   * threshold (0.96 > 0.95) should be rejected by the selectivity check
   * (step 4b) and cache null permanently — same as standalone IndexLookup.
   */
  @Test
  public void resolveWithCache_compositeWithIndexLookup_selectivityTooHigh_rejected() {
    var et = createEdgeTraversal();
    var ridSet = singletonRidSet(10, 1);

    var indexLookup = stubIndexLookup(0.96, 100_000, "Post.date", ridSet);
    var edgeLookup = mock(RidFilterDescriptor.EdgeRidLookup.class);
    when(edgeLookup.estimatedSize(any(), any())).thenReturn(500);
    when(edgeLookup.cacheKey(any())).thenReturn(new RecordId(10, 1));

    var composite = new RidFilterDescriptor.Composite(
        List.of(edgeLookup, indexLookup));
    et.setIntersectionDescriptor(composite);
    var ctx = new BasicCommandContext();

    // selectivity 0.96 > threshold 0.95 → rejected, cached null
    assertThat(et.resolveWithCache(ctx, LARGE_LINKBAG)).isNull();

    // Second call returns cached null immediately (no re-evaluation)
    assertThat(et.resolveWithCache(ctx, LARGE_LINKBAG)).isNull();
  }

  /**
   * Composite with an IndexLookup child caches the selectivity value
   * across calls — estimateSelectivity() is called once, not per vertex.
   */
  @Test
  public void resolveWithCache_compositeWithIndexLookup_selectivityCached() {
    var et = createEdgeTraversal();
    var ridSet = singletonRidSet(10, 1);

    var indexLookup = stubIndexLookup(0.5, 100_000, "Post.date", ridSet);
    var edgeLookup = mock(RidFilterDescriptor.EdgeRidLookup.class);
    when(edgeLookup.estimatedSize(any(), any())).thenReturn(500);
    when(edgeLookup.cacheKey(any())).thenReturn(new RecordId(10, 1));
    when(edgeLookup.resolve(any(), any())).thenReturn(ridSet);

    var composite = new RidFilterDescriptor.Composite(
        List.of(edgeLookup, indexLookup));
    et.setIntersectionDescriptor(composite);
    var ctx = new BasicCommandContext();

    // Three calls — selectivity should be computed only once.
    et.resolveWithCache(ctx, 3);
    et.resolveWithCache(ctx, 3);
    et.resolveWithCache(ctx, LARGE_LINKBAG);

    verify(indexLookup.indexDescriptor(), times(1))
        .estimateSelectivity(any());
  }

  /**
   * Fractional threshold: with selectivity=0.3, minNeighbors =
   * 100_000 / (100 * (1 - 0.3)) = 1428.571..., ceil = 1429.
   * Verifies that Math.ceil rounds up correctly — accumulated 1428
   * defers, accumulated 1429 triggers.
   */
  @Test
  public void resolveWithCache_indexLookup_fractionalThreshold_ceilRoundsUp() {
    var et = createEdgeTraversal();
    var ridSet = singletonRidSet(10, 1);
    var desc = stubIndexLookup(0.3, 100_000, "Post.date", ridSet);
    et.setIntersectionDescriptor(desc);
    var ctx = new BasicCommandContext();

    // 1428 < ceil(1428.571) = 1429 → defers
    assertThat(et.resolveWithCache(ctx, 1428)).isNull();
    verify(desc, never()).resolve(any(), any());

    // +1 → accumulated = 1429 >= 1429 → triggers
    assertThat(et.resolveWithCache(ctx, 1)).isSameAs(ridSet);
    verify(desc, times(1)).resolve(any(), any());
  }

  /**
   * IndexLookup with selectivity exactly at the threshold (0.95) should
   * NOT be rejected by the inline check (step 4b uses strict '>'),
   * proceeding to the accumulator instead.
   */
  @Test
  public void resolveWithCache_indexLookup_selectivityAtThreshold_proceedsToAccumulator() {
    var et = createEdgeTraversal();
    var ridSet = singletonRidSet(10, 1);
    // selectivity=0.95 (at threshold), estimatedSize=100_000
    // minNeighbors = 100_000 / (100 * 0.05) = 20_000
    var desc = stubIndexLookup(0.95, 100_000, "Post.date", ridSet);
    et.setIntersectionDescriptor(desc);
    var ctx = new BasicCommandContext();

    // Small linkBag: should defer via accumulator (NOT permanently reject)
    assertThat(et.resolveWithCache(ctx, 100)).isNull();
    // Large linkBag: should trigger via accumulator
    assertThat(et.resolveWithCache(ctx, 20_000)).isSameAs(ridSet);
    verify(desc, times(1)).resolve(any(), any());
  }

  /**
   * Zero-neighbor vertices contribute nothing to the accumulator. Interspersed
   * with a threshold-meeting vertex, the zeros are harmlessly skipped and the
   * final non-zero linkBag triggers materialization.
   */
  @Test
  public void resolveWithCache_indexLookup_zeroLinkBag_thenPositive_triggers() {
    var et = createEdgeTraversal();
    var ridSet = singletonRidSet(10, 1);
    // selectivity=0.5, estimatedSize=100_000 → threshold=2000
    var desc = stubIndexLookup(0.5, 100_000, "Post.date", ridSet);
    et.setIntersectionDescriptor(desc);
    var ctx = new BasicCommandContext();

    // Zeros don't advance the accumulator
    assertThat(et.resolveWithCache(ctx, 0)).isNull();
    assertThat(et.resolveWithCache(ctx, 0)).isNull();
    verify(desc, never()).resolve(any(), any());

    // 2000 >= threshold 2000 → triggers
    assertThat(et.resolveWithCache(ctx, 2000)).isSameAs(ridSet);
    verify(desc, times(1)).resolve(any(), any());
  }

  /**
   * The accumulator is shared across all cache keys: volume accumulated for
   * key A counts toward triggering key B. This is intentional — the
   * accumulator measures total traversal volume, not per-key volume.
   *
   * <p>In practice, IndexLookup produces a single stable key per query, but
   * this test documents the shared-accumulator design property.
   */
  @Test
  public void resolveWithCache_indexLookup_sharedAccumulator_acrossKeys() {
    var et = createEdgeTraversal();
    var ridSet = singletonRidSet(10, 1);
    // selectivity=0.5, estimatedSize=100_000 → threshold=2000
    var desc = stubIndexLookup(0.5, 100_000, "Post.date", ridSet);
    // Return different keys on successive calls to simulate two distinct keys
    when(desc.cacheKey(any()))
        .thenReturn("keyA", "keyA", "keyA", "keyB");
    et.setIntersectionDescriptor(desc);
    var ctx = new BasicCommandContext();

    // Accumulate 1500 with keyA (below threshold 2000)
    assertThat(et.resolveWithCache(ctx, 500)).isNull();
    assertThat(et.resolveWithCache(ctx, 500)).isNull();
    assertThat(et.resolveWithCache(ctx, 500)).isNull();
    verify(desc, never()).resolve(any(), any());

    // keyB arrives with linkBag=500: accumulated becomes 2000 >= 2000
    // → triggers because the shared accumulator already has 1500
    assertThat(et.resolveWithCache(ctx, 500)).isSameAs(ridSet);
    verify(desc, times(1)).resolve(any(), any());
  }

  // =========================================================================
  // PreFilterSkipReason enum
  // =========================================================================

  /**
   * All six expected enum values are defined with the correct names.
   */
  @Test
  public void preFilterSkipReason_allValuesExist() {
    assertThat(PreFilterSkipReason.values())
        .containsExactly(
            PreFilterSkipReason.NONE,
            PreFilterSkipReason.CAP_EXCEEDED,
            PreFilterSkipReason.SELECTIVITY_TOO_LOW,
            PreFilterSkipReason.BUILD_NOT_AMORTIZED,
            PreFilterSkipReason.LINKBAG_TOO_SMALL,
            PreFilterSkipReason.OVERLAP_RATIO_TOO_HIGH);
  }

  // =========================================================================
  // Pre-filter counter defaults and copy semantics
  // =========================================================================

  /**
   * A freshly constructed EdgeTraversal has all pre-filter counters at
   * their default values: zero for numeric fields, NONE for the skip
   * reason.
   */
  @Test
  public void newEdgeTraversal_countersAtDefaults() {
    var et = createEdgeTraversal();

    assertThat(et.getPreFilterAppliedCount()).isZero();
    assertThat(et.getPreFilterSkippedCount()).isZero();
    assertThat(et.getPreFilterTotalProbed()).isZero();
    assertThat(et.getPreFilterTotalFiltered()).isZero();
    assertThat(et.getPreFilterBuildTimeNanos()).isZero();
    assertThat(et.getPreFilterRidSetSize()).isZero();
    assertThat(et.getLastSkipReason()).isEqualTo(PreFilterSkipReason.NONE);
  }

  /**
   * copy() resets all pre-filter counters to their default values —
   * the copy must not inherit counters from a previous query execution.
   * Uses reflection to set non-default values on the original (no
   * public setters exist yet) and verifies the copy has defaults.
   */
  @Test
  public void copy_resetsPreFilterCounters() throws Exception {
    var et = createEdgeTraversal();

    // Force non-default counter values via reflection (no setters exist
    // yet — recording logic is added in Steps 2-3).
    setField(et, "preFilterAppliedCount", 42);
    setField(et, "preFilterSkippedCount", 7);
    setField(et, "preFilterTotalProbed", 999L);
    setField(et, "preFilterTotalFiltered", 500L);
    setField(et, "preFilterBuildTimeNanos", 123456L);
    setField(et, "preFilterRidSetSize", 200);
    setField(et, "lastSkipReason", PreFilterSkipReason.CAP_EXCEEDED);

    // Sanity: verify the original has non-default values.
    assertThat(et.getPreFilterAppliedCount()).isEqualTo(42);
    assertThat(et.getLastSkipReason()).isEqualTo(PreFilterSkipReason.CAP_EXCEEDED);

    var copy = et.copy();

    // copy() must reset all counters to defaults.
    assertThat(copy.getPreFilterAppliedCount()).isZero();
    assertThat(copy.getPreFilterSkippedCount()).isZero();
    assertThat(copy.getPreFilterTotalProbed()).isZero();
    assertThat(copy.getPreFilterTotalFiltered()).isZero();
    assertThat(copy.getPreFilterBuildTimeNanos()).isZero();
    assertThat(copy.getPreFilterRidSetSize()).isZero();
    assertThat(copy.getLastSkipReason()).isEqualTo(PreFilterSkipReason.NONE);
  }

  private static void setField(Object target, String fieldName, Object value)
      throws Exception {
    var field = target.getClass().getDeclaredField(fieldName);
    field.setAccessible(true);
    field.set(target, value);
  }

  // =========================================================================
  // Counter recording in resolveWithCache
  // =========================================================================

  /**
   * When estimatedSize exceeds maxRidSetSize, the counter records
   * CAP_EXCEEDED and increments the skipped count. Returns null.
   */
  @Test
  public void resolveWithCache_capExceeded_recordsCounter() {
    var et = createEdgeTraversal();
    var desc = mock(EdgeRidLookup.class);
    var key = new RecordId(5, 1);

    when(desc.cacheKey(any())).thenReturn(key);
    when(desc.estimatedSize(any(), any()))
        .thenReturn(TraversalPreFilterHelper.maxRidSetSize() + 1);
    et.setIntersectionDescriptor(desc);
    var ctx = new BasicCommandContext();

    var result = et.resolveWithCache(ctx, LARGE_LINKBAG);

    assertThat(result).isNull();
    assertThat(et.getLastSkipReason())
        .isEqualTo(PreFilterSkipReason.CAP_EXCEEDED);
    assertThat(et.getPreFilterSkippedCount()).isEqualTo(1);
    assertThat(et.getPreFilterAppliedCount()).isZero();
  }

  /**
   * When IndexLookup selectivity exceeds the threshold (0.96 > default
   * 0.95), the counter records SELECTIVITY_TOO_LOW. Returns null.
   */
  @Test
  public void resolveWithCache_selectivityTooLow_recordsCounter() {
    var et = createEdgeTraversal();
    var indexDesc = mock(IndexSearchDescriptor.class);
    // 0.96 > default threshold 0.95 → triggers SELECTIVITY_TOO_LOW
    when(indexDesc.estimateSelectivity(any())).thenReturn(0.96);
    var desc = mock(IndexLookup.class);
    when(desc.indexDescriptor()).thenReturn(indexDesc);
    when(desc.cacheKey(any())).thenReturn("Post.creationDate");
    when(desc.estimatedSize(any(), any())).thenReturn(500);
    et.setIntersectionDescriptor(desc);
    var ctx = new BasicCommandContext();

    var result = et.resolveWithCache(ctx, LARGE_LINKBAG);

    assertThat(result).isNull();
    assertThat(et.getLastSkipReason())
        .isEqualTo(PreFilterSkipReason.SELECTIVITY_TOO_LOW);
    assertThat(et.getPreFilterSkippedCount()).isEqualTo(1);
    assertThat(et.getPreFilterAppliedCount()).isZero();
  }

  /**
   * When the IndexLookup build amortization threshold is not yet met,
   * the counter records BUILD_NOT_AMORTIZED and increments the skipped
   * count. Each deferred call increments the count. Returns null.
   */
  @Test
  public void resolveWithCache_buildNotAmortized_recordsCounter() {
    var et = createEdgeTraversal();
    var ridSet = singletonRidSet(10, 1);
    // selectivity=0.5, estimatedSize=100_000 → threshold=2000
    var desc = stubIndexLookup(0.5, 100_000, "Post.date", ridSet);
    et.setIntersectionDescriptor(desc);
    var ctx = new BasicCommandContext();

    // linkBag=500 → accumulated=500 < 2000 → deferred
    var result = et.resolveWithCache(ctx, 500);

    assertThat(result).isNull();
    assertThat(et.getLastSkipReason())
        .isEqualTo(PreFilterSkipReason.BUILD_NOT_AMORTIZED);
    assertThat(et.getPreFilterSkippedCount()).isEqualTo(1);

    // Second deferral
    et.resolveWithCache(ctx, 500);
    assertThat(et.getPreFilterSkippedCount()).isEqualTo(2);
  }

  /**
   * When EdgeRidLookup's passesSelectivityCheck returns false (overlap
   * ratio too high), the counter records OVERLAP_RATIO_TOO_HIGH. Returns
   * null.
   */
  @Test
  public void resolveWithCache_overlapTooHigh_recordsCounter() {
    var et = createEdgeTraversal();
    var desc = mock(EdgeRidLookup.class);
    var key = new RecordId(5, 1);

    when(desc.cacheKey(any())).thenReturn(key);
    when(desc.estimatedSize(any(), any())).thenReturn(500);
    when(desc.passesSelectivityCheck(anyInt(), anyInt(), any()))
        .thenReturn(false);
    et.setIntersectionDescriptor(desc);
    var ctx = new BasicCommandContext();

    var result = et.resolveWithCache(ctx, 50);

    assertThat(result).isNull();
    assertThat(et.getLastSkipReason())
        .isEqualTo(PreFilterSkipReason.OVERLAP_RATIO_TOO_HIGH);
    assertThat(et.getPreFilterSkippedCount()).isEqualTo(1);
    assertThat(et.getPreFilterAppliedCount()).isZero();
  }

  /**
   * On the success path (materialization), the counter records
   * preFilterAppliedCount, preFilterRidSetSize, and
   * preFilterBuildTimeNanos. The lastSkipReason is NONE.
   */
  @Test
  public void resolveWithCache_success_recordsCounters() {
    var et = createEdgeTraversal();
    var desc = mock(EdgeRidLookup.class);
    var key = new RecordId(5, 1);
    var ridSet = singletonRidSet(10, 1);

    when(desc.cacheKey(any())).thenReturn(key);
    when(desc.resolve(any(), any())).thenReturn(ridSet);
    stubSmallEstimate(desc);
    et.setIntersectionDescriptor(desc);
    var ctx = new BasicCommandContext();

    et.resolveWithCache(ctx, LARGE_LINKBAG);

    // appliedCount is incremented by recordPreFilterApplied (in
    // applyPreFilter), not at materialization time in resolveWithCache.
    assertThat(et.getPreFilterAppliedCount()).isZero();
    assertThat(et.getPreFilterRidSetSize()).isEqualTo(1);
    assertThat(et.getPreFilterBuildTimeNanos()).isGreaterThan(0L);
    assertThat(et.getLastSkipReason()).isEqualTo(PreFilterSkipReason.NONE);
    assertThat(et.getPreFilterSkippedCount()).isZero();
  }

  /**
   * preFilterRidSetSize is set once at first materialization and not
   * overwritten on a second materialization with a different cache key
   * that produces a differently-sized RidSet (T8).
   */
  @Test
  public void resolveWithCache_ridSetSize_setOnceAtMaterialization() {
    var et = createEdgeTraversal();
    var desc = mock(EdgeRidLookup.class);
    var key1 = new RecordId(5, 1);
    var key2 = new RecordId(5, 2);
    var ridSet1 = singletonRidSet(10, 1); // size = 1

    var ridSet2 = new RidSet();
    ridSet2.add(new RecordId(10, 1));
    ridSet2.add(new RecordId(10, 2));
    ridSet2.add(new RecordId(10, 3)); // size = 3

    when(desc.cacheKey(any())).thenReturn(key1, key2);
    when(desc.resolve(any(), any())).thenReturn(ridSet1, ridSet2);
    stubSmallEstimate(desc);
    et.setIntersectionDescriptor(desc);
    var ctx = new BasicCommandContext();

    // First materialization — ridSetSize set to 1
    et.resolveWithCache(ctx, LARGE_LINKBAG);
    assertThat(et.getPreFilterRidSetSize()).isEqualTo(1);

    // Second materialization (different key, size=3) — ridSetSize must
    // remain 1, NOT change to 3
    et.resolveWithCache(ctx, LARGE_LINKBAG);
    // appliedCount stays 0 — incremented by recordPreFilterApplied, not here.
    assertThat(et.getPreFilterAppliedCount()).isZero();
    assertThat(et.getPreFilterRidSetSize()).isEqualTo(1);
  }

  /**
   * Cache-hit path (step 1 in resolveWithCache) returns early for a
   * non-null cached RidSet without touching counter fields. All counters
   * must reflect only the initial materialization.
   */
  @Test
  public void resolveWithCache_cacheHit_doesNotIncrementCounters() {
    var et = createEdgeTraversal();
    var desc = mock(EdgeRidLookup.class);
    var key = new RecordId(5, 1);
    var ridSet = singletonRidSet(10, 1);

    when(desc.cacheKey(any())).thenReturn(key);
    when(desc.resolve(any(), any())).thenReturn(ridSet);
    stubSmallEstimate(desc);
    et.setIntersectionDescriptor(desc);
    var ctx = new BasicCommandContext();

    // First call — materializes
    et.resolveWithCache(ctx, LARGE_LINKBAG);
    long buildTimeAfterFirst = et.getPreFilterBuildTimeNanos();

    // Second call — cache hit (non-null), no counter change
    et.resolveWithCache(ctx, LARGE_LINKBAG);

    // appliedCount stays 0 — incremented by recordPreFilterApplied, not here.
    assertThat(et.getPreFilterAppliedCount()).isZero();
    assertThat(et.getPreFilterSkippedCount()).isZero();
    assertThat(et.getLastSkipReason()).isEqualTo(PreFilterSkipReason.NONE);
    assertThat(et.getPreFilterBuildTimeNanos())
        .isEqualTo(buildTimeAfterFirst);
    assertThat(et.getPreFilterRidSetSize()).isEqualTo(1);
  }

  /**
   * Build amortization deferred, then threshold met — counters show both
   * the deferred skips and the eventual success.
   */
  @Test
  public void resolveWithCache_deferredThenTriggered_recordsCounters() {
    var et = createEdgeTraversal();
    var ridSet = singletonRidSet(10, 1);
    // selectivity=0.5, estimatedSize=100_000 → threshold=2000
    var desc = stubIndexLookup(0.5, 100_000, "Post.date", ridSet);
    et.setIntersectionDescriptor(desc);
    var ctx = new BasicCommandContext();

    // Defer 3 times (500 each = 1500 < 2000)
    et.resolveWithCache(ctx, 500);
    et.resolveWithCache(ctx, 500);
    et.resolveWithCache(ctx, 500);
    assertThat(et.getPreFilterSkippedCount()).isEqualTo(3);
    assertThat(et.getLastSkipReason())
        .isEqualTo(PreFilterSkipReason.BUILD_NOT_AMORTIZED);

    // 4th call pushes to 2000 → triggers materialization
    et.resolveWithCache(ctx, 500);
    // appliedCount stays 0 — incremented by recordPreFilterApplied, not here.
    assertThat(et.getPreFilterAppliedCount()).isZero();
    assertThat(et.getPreFilterSkippedCount()).isEqualTo(3);
    assertThat(et.getLastSkipReason()).isEqualTo(PreFilterSkipReason.NONE);
    assertThat(et.getPreFilterRidSetSize()).isEqualTo(1);
    assertThat(et.getPreFilterBuildTimeNanos()).isGreaterThan(0L);
  }

  /**
   * Cache-hit-null path increments preFilterSkippedCount for each vertex
   * that hits the cached-null entry (BC2 fix). The first call caches null
   * and records the skip reason; subsequent calls hit the cache and
   * increment the counter without re-computing the decision.
   */
  @Test
  public void resolveWithCache_cacheHitNull_incrementsSkippedCount() {
    var et = createEdgeTraversal();
    var desc = mock(EdgeRidLookup.class);
    var key = new RecordId(5, 1);

    when(desc.cacheKey(any())).thenReturn(key);
    when(desc.estimatedSize(any(), any()))
        .thenReturn(TraversalPreFilterHelper.maxRidSetSize() + 1);
    et.setIntersectionDescriptor(desc);
    var ctx = new BasicCommandContext();

    // First call — CAP_EXCEEDED, caches null
    et.resolveWithCache(ctx, LARGE_LINKBAG);
    assertThat(et.getPreFilterSkippedCount()).isEqualTo(1);

    // Second call — cache hit null, increments skipped count
    et.resolveWithCache(ctx, LARGE_LINKBAG);
    assertThat(et.getPreFilterSkippedCount()).isEqualTo(2);

    // Third call — still cache hit null
    et.resolveWithCache(ctx, LARGE_LINKBAG);
    assertThat(et.getPreFilterSkippedCount()).isEqualTo(3);
    assertThat(et.getPreFilterAppliedCount()).isZero();
    assertThat(et.getLastSkipReason())
        .isEqualTo(PreFilterSkipReason.CAP_EXCEEDED);
  }

  /**
   * When resolve() returns null on the materialization cold path (all
   * selectivity checks passed, but descriptor produced no RidSet),
   * preFilterBuildTimeNanos is accumulated (build cost was real) but
   * preFilterAppliedCount stays at 0 and no skip reason is set.
   */
  @Test
  public void resolveWithCache_resolveReturnsNull_buildTimeButNotApplied() {
    var et = createEdgeTraversal();
    var desc = mock(EdgeRidLookup.class);
    var key = new RecordId(5, 1);

    when(desc.cacheKey(any())).thenReturn(key);
    when(desc.resolve(any(), any())).thenReturn(null);
    stubSmallEstimate(desc);
    et.setIntersectionDescriptor(desc);
    var ctx = new BasicCommandContext();

    var result = et.resolveWithCache(ctx, LARGE_LINKBAG);

    assertThat(result).isNull();
    assertThat(et.getPreFilterBuildTimeNanos()).isGreaterThan(0L);
    assertThat(et.getPreFilterAppliedCount()).isZero();
    assertThat(et.getPreFilterRidSetSize()).isZero();
    // No explicit skip reason is set on this path — remains at NONE.
    assertThat(et.getLastSkipReason()).isEqualTo(PreFilterSkipReason.NONE);
  }

  // ---- Metric resolve fallback (null registry in unit test env) ----

  /**
   * resolveEffectivenessMetric() must return a functional Ratio that
   * either resolves from the live registry (Impl) or falls back to
   * NOOP. The cached reference is reused on subsequent calls, avoiding
   * repeated ConcurrentHashMap lookups.
   */
  @Test
  public void resolveEffectivenessMetricReturnsCachedFunctionalMetric() {
    var et = createEdgeTraversal();
    var metric = et.resolveEffectivenessMetric();
    assertThat(metric).isNotNull();
    assertThat(metric).isInstanceOfAny(Ratio.Impl.class, Ratio.NOOP.getClass());
    metric.record(50, 100);
    // Second call returns the same cached reference.
    assertThat(et.resolveEffectivenessMetric()).isSameAs(metric);
  }

  // ---- computeLiveCostRatio tests ----

  /**
   * Cold start: both rates are 0 → falls back to DEFAULT_LOAD_TO_SCAN_RATIO.
   */
  @Test
  public void liveCostRatio_coldStart_fallsBackToDefault() {
    assertThat(EdgeTraversal.computeLiveCostRatio(0, 0, 0))
        .isEqualTo(EdgeTraversal.DEFAULT_LOAD_TO_SCAN_RATIO);
  }

  /**
   * Warm cache (cacheHitPct=95, moderate scan speed): the ratio should
   * be lower than the default because loads are mostly from cache.
   * estimatedLoadNanos = 0.05 * 100000 + 0.95 * 500 = 5475
   * avgScanNanosPerEntry = 1000000 / 10000 = 100
   * ratio = 5475 / 100 = 54.75
   */
  @Test
  public void liveCostRatio_warmCache_lowRatio() {
    double ratio = EdgeTraversal.computeLiveCostRatio(
        1_000_000, 10_000, 95);
    assertThat(ratio).isCloseTo(54.75, Offset.offset(0.01));
  }

  /**
   * Cold storage (cacheHitPct=10, fast scans): the ratio should be
   * higher because most loads are cold SSD reads.
   * estimatedLoadNanos = 0.9 * 100000 + 0.1 * 500 = 90050
   * avgScanNanosPerEntry = 1000000 / 10000 = 100
   * ratio = 90050 / 100 = 900.5
   */
  @Test
  public void liveCostRatio_coldStorage_highRatio() {
    double ratio = EdgeTraversal.computeLiveCostRatio(
        1_000_000, 10_000, 10);
    assertThat(ratio).isCloseTo(900.5, Offset.offset(0.01));
  }

  /**
   * Asymmetric zero: scanNanos > 0 but scanEntries == 0 → fallback.
   */
  @Test
  public void liveCostRatio_scanNanosOnlyZeroEntries_fallback() {
    assertThat(EdgeTraversal.computeLiveCostRatio(1000, 0, 50))
        .isEqualTo(EdgeTraversal.DEFAULT_LOAD_TO_SCAN_RATIO);
  }

  /**
   * Asymmetric zero: scanEntries > 0 but scanNanos == 0 → fallback.
   */
  @Test
  public void liveCostRatio_zeroNanosPositiveEntries_fallback() {
    assertThat(EdgeTraversal.computeLiveCostRatio(0, 1000, 50))
        .isEqualTo(EdgeTraversal.DEFAULT_LOAD_TO_SCAN_RATIO);
  }

  /**
   * NaN input for scanNanosRate → fallback.
   */
  @Test
  public void liveCostRatio_nanInput_fallback() {
    assertThat(EdgeTraversal.computeLiveCostRatio(
        Double.NaN, 1000, 50))
        .isEqualTo(EdgeTraversal.DEFAULT_LOAD_TO_SCAN_RATIO);
  }

  /**
   * Infinity input for scanEntriesRate → fallback.
   */
  @Test
  public void liveCostRatio_infinityInput_fallback() {
    assertThat(EdgeTraversal.computeLiveCostRatio(
        1000, Double.POSITIVE_INFINITY, 50))
        .isEqualTo(EdgeTraversal.DEFAULT_LOAD_TO_SCAN_RATIO);
  }

  /**
   * Negative inputs → fallback (negative rates are nonsensical).
   */
  @Test
  public void liveCostRatio_negativeRates_fallback() {
    assertThat(EdgeTraversal.computeLiveCostRatio(-100, 1000, 50))
        .isEqualTo(EdgeTraversal.DEFAULT_LOAD_TO_SCAN_RATIO);
    assertThat(EdgeTraversal.computeLiveCostRatio(100, -1000, 50))
        .isEqualTo(EdgeTraversal.DEFAULT_LOAD_TO_SCAN_RATIO);
  }

  /**
   * Very fast scans with cold storage → ratio would exceed MAX,
   * so it gets clamped to MAX_LOAD_TO_SCAN_RATIO.
   * estimatedLoadNanos = 1.0 * 100000 = 100000 (0% cache hit)
   * avgScanNanosPerEntry = 10 / 10 = 1
   * raw ratio = 100000 → clamped to MAX (1000)
   */
  @Test
  public void liveCostRatio_clampedToMax() {
    double ratio = EdgeTraversal.computeLiveCostRatio(10, 10, 0);
    assertThat(ratio).isEqualTo(EdgeTraversal.MAX_LOAD_TO_SCAN_RATIO);
  }

  /**
   * Very slow scans with warm cache → ratio would be below MIN,
   * so it gets clamped to MIN_LOAD_TO_SCAN_RATIO.
   * estimatedLoadNanos = 0.0 * 100000 + 1.0 * 500 = 500 (100% cache hit)
   * avgScanNanosPerEntry = 100000000 / 1000 = 100000
   * raw ratio = 500 / 100000 = 0.005 → clamped to MIN (5)
   */
  @Test
  public void liveCostRatio_clampedToMin() {
    double ratio = EdgeTraversal.computeLiveCostRatio(
        100_000_000, 1000, 100);
    assertThat(ratio).isEqualTo(EdgeTraversal.MIN_LOAD_TO_SCAN_RATIO);
  }

  /**
   * cacheHitPct beyond [0, 100] is clamped before use.
   * cacheHitPct=100 → hitFraction=1.0 → estimatedLoad = 500
   * avgScanNanosPerEntry = 1_000_000/10_000 = 100
   * ratio = 500/100 = 5.0 → clamped to MIN (5.0)
   * A value of 200% should produce the same result as 100%.
   */
  @Test
  public void liveCostRatio_cacheHitPctClampedAbove100() {
    double ratioAt100 = EdgeTraversal.computeLiveCostRatio(
        1_000_000, 10_000, 100);
    assertThat(ratioAt100).isEqualTo(EdgeTraversal.MIN_LOAD_TO_SCAN_RATIO);

    double ratioAt200 = EdgeTraversal.computeLiveCostRatio(
        1_000_000, 10_000, 200);
    assertThat(ratioAt200).isEqualTo(EdgeTraversal.MIN_LOAD_TO_SCAN_RATIO);
  }

  /**
   * NaN cacheHitPct should fall back to the default, not silently
   * treat the cache as 0% hit (cold-storage assumption).
   */
  @Test
  public void liveCostRatio_nanCacheHitPct_fallback() {
    assertThat(EdgeTraversal.computeLiveCostRatio(1_000_000, 10_000, Double.NaN))
        .isEqualTo(EdgeTraversal.DEFAULT_LOAD_TO_SCAN_RATIO);
  }

  /**
   * Negative cacheHitPct should be treated as 0%.
   * cacheHitPct=0 → hitFraction=0 → estimatedLoad = 100000
   * avgScanNanosPerEntry = 1_000_000/10_000 = 100
   * ratio = 100000/100 = 1000 → clamped to MAX (1000)
   */
  @Test
  public void liveCostRatio_negativeCacheHitPctClampedToZero() {
    double ratioAt0 = EdgeTraversal.computeLiveCostRatio(
        1_000_000, 10_000, 0);
    assertThat(ratioAt0).isEqualTo(EdgeTraversal.MAX_LOAD_TO_SCAN_RATIO);

    double ratioAtNeg = EdgeTraversal.computeLiveCostRatio(
        1_000_000, 10_000, -50);
    assertThat(ratioAtNeg).isEqualTo(EdgeTraversal.MAX_LOAD_TO_SCAN_RATIO);
  }

  // ---- Infinity/NaN symmetric coverage for all parameters (TC3, TB3) ----

  /**
   * Infinity cacheHitPct should fall back to default, not silently
   * treat the cache as 100% hit (which would underestimate load cost).
   */
  @Test
  public void liveCostRatio_infinityCacheHitPct_fallback() {
    assertThat(EdgeTraversal.computeLiveCostRatio(
        1_000_000, 10_000, Double.POSITIVE_INFINITY))
        .isEqualTo(EdgeTraversal.DEFAULT_LOAD_TO_SCAN_RATIO);
    assertThat(EdgeTraversal.computeLiveCostRatio(
        1_000_000, 10_000, Double.NEGATIVE_INFINITY))
        .isEqualTo(EdgeTraversal.DEFAULT_LOAD_TO_SCAN_RATIO);
  }

  /** NaN in scanEntriesRate also falls back to default. */
  @Test
  public void liveCostRatio_nanScanEntries_fallback() {
    assertThat(EdgeTraversal.computeLiveCostRatio(1000, Double.NaN, 50))
        .isEqualTo(EdgeTraversal.DEFAULT_LOAD_TO_SCAN_RATIO);
  }

  /** POSITIVE_INFINITY in scanNanosRate also falls back to default. */
  @Test
  public void liveCostRatio_infinityScanNanos_fallback() {
    assertThat(EdgeTraversal.computeLiveCostRatio(
        Double.POSITIVE_INFINITY, 1000, 50))
        .isEqualTo(EdgeTraversal.DEFAULT_LOAD_TO_SCAN_RATIO);
  }

  /** NEGATIVE_INFINITY in either rate parameter → fallback. */
  @Test
  public void liveCostRatio_negativeInfinityRates_fallback() {
    assertThat(EdgeTraversal.computeLiveCostRatio(
        Double.NEGATIVE_INFINITY, 1000, 50))
        .isEqualTo(EdgeTraversal.DEFAULT_LOAD_TO_SCAN_RATIO);
    assertThat(EdgeTraversal.computeLiveCostRatio(
        1000, Double.NEGATIVE_INFINITY, 50))
        .isEqualTo(EdgeTraversal.DEFAULT_LOAD_TO_SCAN_RATIO);
  }

  // ---- copy() resets metric references and cached live ratio (TC1) ----

  /**
   * copy() must reset all metric reference fields and the cached live
   * ratio — stale metric data from a previous execution must not leak
   * into a new plan instance. If cachedLiveRatio leaked, the new query
   * would skip live ratio recomputation (NaN sentinel check at
   * resolveLoadToScanRatio).
   */
  @Test
  public void copy_resetsMetricReferencesAndCachedLiveRatio() throws Exception {
    var et = createEdgeTraversal();

    // Set non-default values via reflection.
    setField(et, "cachedScanNanos", TimeRate.NOOP);
    setField(et, "cachedScanEntries", TimeRate.NOOP);
    setField(et, "cachedCacheHitRatio", Ratio.NOOP);
    setField(et, "cachedLiveRatio", 42.0);
    setField(et, "cachedEffectiveness", Ratio.NOOP);

    var copy = et.copy();

    // All metric fields must be at their initial defaults.
    assertThat(getField(copy, "cachedScanNanos")).isNull();
    assertThat(getField(copy, "cachedScanEntries")).isNull();
    assertThat(getField(copy, "cachedCacheHitRatio")).isNull();
    assertThat((double) getField(copy, "cachedLiveRatio")).isNaN();
    assertThat(getField(copy, "cachedEffectiveness")).isNull();
  }

  private static Object getField(Object target, String fieldName)
      throws Exception {
    var field = target.getClass().getDeclaredField(fieldName);
    field.setAccessible(true);
    return field.get(target);
  }
}
