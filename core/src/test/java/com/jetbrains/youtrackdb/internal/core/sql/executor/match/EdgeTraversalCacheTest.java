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
    var desc = mock(IndexLookup.class);
    var key = "Post.creationDate";

    when(desc.cacheKey(any())).thenReturn(key);
    when(desc.estimatedSize(any(), any())).thenReturn(500);
    // Selectivity check fails (high class-level selectivity)
    when(desc.passesSelectivityCheck(anyInt(), anyInt(), any()))
        .thenReturn(false);
    et.setIntersectionDescriptor(desc);
    var ctx = new BasicCommandContext();

    // First call: selectivity fails → null, cached for IndexLookup
    assertThat(et.resolveWithCache(ctx, LARGE_LINKBAG)).isNull();

    // Second call: cache hit → null, passesSelectivityCheck NOT re-called
    assertThat(et.resolveWithCache(ctx, LARGE_LINKBAG)).isNull();

    // passesSelectivityCheck called only once (second call is cache hit)
    verify(desc, times(1))
        .passesSelectivityCheck(anyInt(), anyInt(), any());
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
   * minNeighbors = 100_000 / (100 * 0.5) = 2000. A linkBag of 10_000
   * exceeds 2000, so the first call materializes.
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
  }

  /**
   * Multiple small vertices accumulate until the threshold is reached.
   * Vertices 1..N return null (deferred), vertex N+1 triggers materialization.
   *
   * <p>With selectivity=0.5, estimatedSize=100_000, ratio=100:
   * minNeighbors = 2000. With linkBagSize=500 per vertex, it takes 4
   * deferred calls (accumulated=2000) before the 4th call triggers
   * (ceil(2000) = 2000, accumulated 4*500=2000 >= 2000).
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

    // Large vertex — should trigger (not a cache-hit returning null)
    assertThat(et.resolveWithCache(ctx, 10_000)).isSameAs(ridSet);
    verify(desc, times(1)).resolve(any(), any());
  }

  /**
   * When estimateSelectivity returns -1.0 (unknown), the accumulator is
   * bypassed entirely and the RidSet is materialized immediately.
   */
  @Test
  public void resolveWithCache_indexLookup_unknownSelectivity_bypassesAccumulator() {
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
}
