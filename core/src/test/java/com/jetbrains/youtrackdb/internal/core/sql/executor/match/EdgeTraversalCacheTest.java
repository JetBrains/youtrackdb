package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
   * Stubs the mock descriptor to return a small estimatedSize so that
   * the ratio check in resolveWithCache passes when called with a large
   * linkBagSize.
   */
  private void stubSmallEstimate(RidFilterDescriptor mock) {
    when(mock.estimatedSize(any(), any())).thenReturn(1);
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
        java.util.List.of(desc1, desc2));
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
        java.util.List.of(desc1, desc2));
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
        java.util.List.of(desc1, desc2));
    var key = composite.cacheKey(new BasicCommandContext());

    assertThat(key).isEqualTo(java.util.List.of(key1, key2));
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
        java.util.List.of(desc1, desc2));
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
        java.util.List.of(desc1, desc2));
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
        java.util.List.of(desc1, desc2));
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
    // estimatedSize = 500, which fails ratio check for linkBag=50
    // (500/50 = 10.0 > maxSelectivityRatio)
    when(desc.estimatedSize(any(), any())).thenReturn(500);
    when(desc.resolve(any(), any())).thenReturn(ridSet);
    et.setIntersectionDescriptor(desc);
    var ctx = new BasicCommandContext();

    // Small link bag → ratio fails → null, not cached
    assertThat(et.resolveWithCache(ctx, 50)).isNull();
    verify(desc, never()).resolve(any(), any());

    // Large link bag → ratio passes → resolve and cache
    assertThat(et.resolveWithCache(ctx, LARGE_LINKBAG)).isSameAs(ridSet);
    verify(desc, times(1)).resolve(any(), any());

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
    // resolve() is called in order: first for keyY (vertex 2), then keyX (vertex 3)
    when(desc.resolve(any(), any())).thenReturn(ridSetY, ridSetX);
    et.setIntersectionDescriptor(desc);
    var ctx = new BasicCommandContext();

    // Vertex 1: target=X, small linkBag → deferred (no resolve)
    assertThat(et.resolveWithCache(ctx, 50)).isNull();

    // Vertex 2: target=Y, large linkBag → first resolve() → ridSetY
    assertThat(et.resolveWithCache(ctx, LARGE_LINKBAG)).isSameAs(ridSetY);

    // Vertex 3: target=X, large linkBag → second resolve() → ridSetX
    assertThat(et.resolveWithCache(ctx, LARGE_LINKBAG)).isSameAs(ridSetX);

    // Vertex 4: target=Y → cache hit → ridSetY
    assertThat(et.resolveWithCache(ctx, LARGE_LINKBAG)).isSameAs(ridSetY);

    verify(desc, times(2)).resolve(any(), any());
  }
}
