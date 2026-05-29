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
import org.junit.Ignore;
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

  /**
   * Pins {@code loadToScanRatio} to {@code 100.0} for the duration of this
   * test class to decouple test math from the production default. The
   * amortization-formula tests reason about specific {@code m} thresholds
   * (e.g. {@code 100K/(100·0.5) = 2000}); pinning at {@code 100.0} keeps
   * those constants stable across config re-calibrations.
   */
  @org.junit.Before
  public void pinLoadToScanRatio() {
    com.jetbrains.youtrackdb.api.config.GlobalConfiguration.QUERY_PREFILTER_LOAD_TO_SCAN_RATIO
        .setValue(100.0);
  }

  @org.junit.After
  public void resetLoadToScanRatio() {
    com.jetbrains.youtrackdb.api.config.GlobalConfiguration.QUERY_PREFILTER_LOAD_TO_SCAN_RATIO
        .resetToDefault();
  }

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
   * {@link IndexLookup#cacheKey} delegates to
   * {@link IndexSearchDescriptor#cacheFingerprint()}, which uniquely
   * identifies the result within a single query execution.
   */
  @Test
  public void indexLookup_cacheKey_returnsFingerprint() {
    var indexDesc = mock(IndexSearchDescriptor.class);
    when(indexDesc.cacheFingerprint()).thenReturn("Post.creationDate");
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
    var indexDesc = mock(IndexSearchDescriptor.class);
    when(indexDesc.cacheFingerprint()).thenReturn("Post.creationDate");
    var desc = new IndexLookup(indexDesc);
    var ctx = new BasicCommandContext();

    assertThat(desc.cacheKey(ctx)).isEqualTo(desc.cacheKey(ctx));
  }

  /**
   * Two {@link IndexLookup} descriptors on the same index but with
   * different key conditions must produce different cache keys. Without
   * the keyCondition fingerprint, both would collapse onto the index
   * name alone and the second descriptor would silently return the
   * first's resolved RidSet — a latent correctness trap one planner
   * change away from firing. Today the MATCH planner emits at most one
   * IndexLookup per edge, so this scenario is not reachable through
   * normal query execution; the test pins the contract regardless.
   */
  @Test
  public void indexLookup_cacheKey_distinctConditionsDoNotAlias() {
    var descA = mock(IndexSearchDescriptor.class);
    when(descA.cacheFingerprint())
        .thenReturn("Post.creationDate|creationDate >= '2020-01-01'|null");

    var descB = mock(IndexSearchDescriptor.class);
    when(descB.cacheFingerprint())
        .thenReturn("Post.creationDate|creationDate >= '2024-01-01'|null");

    var ctx = new BasicCommandContext();
    assertThat(new IndexLookup(descA).cacheKey(ctx))
        .as("same index, different keyCondition must not share cache key")
        .isNotEqualTo(new IndexLookup(descB).cacheKey(ctx));
  }

  /**
   * Conversely, two IndexLookups producing the same fingerprint still
   * share a cache key — the caching contract for "equivalent query,
   * equivalent result" is preserved. Pins the symmetry of the
   * fingerprint contract.
   */
  @Test
  public void indexLookup_cacheKey_identicalConditionsShareKey() {
    var fingerprint = "Post.creationDate|creationDate >= '2020-01-01'|null";

    var descA = mock(IndexSearchDescriptor.class);
    when(descA.cacheFingerprint()).thenReturn(fingerprint);

    var descB = mock(IndexSearchDescriptor.class);
    when(descB.cacheFingerprint()).thenReturn(fingerprint);

    var ctx = new BasicCommandContext();
    assertThat(new IndexLookup(descA).cacheKey(ctx))
        .isEqualTo(new IndexLookup(descB).cacheKey(ctx));
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

  /**
   * Adding a third descriptor must produce a flat Composite of three leaves,
   * not a nested {@code Composite(Composite(A, B), C)}. A nested structure
   * would hide leaves from helpers that only walk the top-level descriptor
   * list (notably {@link RidFilterDescriptor.Composite#findIndexLookup}),
   * silently degrading the build-amortization guard.
   */
  @Test
  public void addIntersectionDescriptor_three_producesFlatComposite() {
    var et = createEdgeTraversal();
    var desc1 = mock(EdgeRidLookup.class);
    var desc2 = mock(EdgeRidLookup.class);
    var desc3 = mock(EdgeRidLookup.class);
    et.addIntersectionDescriptor(desc1);
    et.addIntersectionDescriptor(desc2);
    et.addIntersectionDescriptor(desc3);

    assertThat(et.getIntersectionDescriptor())
        .isInstanceOf(RidFilterDescriptor.Composite.class);
    var composite = (RidFilterDescriptor.Composite) et.getIntersectionDescriptor();
    assertThat(composite.descriptors())
        .as("three adds must flatten into a single Composite")
        .containsExactly(desc1, desc2, desc3);
    for (var child : composite.descriptors()) {
      assertThat(child)
          .as("no child of the top-level Composite may itself be a Composite")
          .isNotInstanceOf(RidFilterDescriptor.Composite.class);
    }
  }

  /**
   * When an IndexLookup is added after two EdgeRidLookups, the resulting
   * Composite must still expose the IndexLookup leaf through
   * {@link RidFilterDescriptor.Composite#findIndexLookup}. Regression for the
   * blind spot where a {@code Composite(Composite(EdgeRid, EdgeRid),
   * IndexLookup)} would mask the IndexLookup from the build-amortization
   * guard.
   */
  @Test
  public void addIntersectionDescriptor_indexLookupAfterTwoEdgeRid_findIndexLookupSucceeds() {
    var et = createEdgeTraversal();
    var edge1 = mock(EdgeRidLookup.class);
    var edge2 = mock(EdgeRidLookup.class);
    var indexLookup = mock(RidFilterDescriptor.IndexLookup.class);
    et.addIntersectionDescriptor(edge1);
    et.addIntersectionDescriptor(edge2);
    et.addIntersectionDescriptor(indexLookup);

    var composite = (RidFilterDescriptor.Composite) et.getIntersectionDescriptor();
    assertThat(composite.findIndexLookup())
        .as("IndexLookup must remain visible despite multiple prior adds")
        .isSameAs(indexLookup);
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

  /**
   * Unknown selectivity ({@code -1.0}) rejects — without a known {@code s}
   * the build cost {@code B} cannot be bounded, so the design's bounded-loss
   * contract requires REJECT. Mirrors the MATCH runtime path
   * ({@link EdgeTraversal#evaluateIndexLookupAmortization}).
   */
  @Test
  public void indexLookup_passesSelectivityCheck_unknownSelectivity_rejects() {
    var indexDesc = mock(IndexSearchDescriptor.class);
    when(indexDesc.estimateSelectivity(any())).thenReturn(-1.0);
    var desc = new IndexLookup(indexDesc);
    var ctx = new BasicCommandContext();

    assertThat(desc.passesSelectivityCheck(0, 0, ctx)).isFalse();
  }

  /**
   * NaN selectivity also rejects — same rationale as the negative-sentinel
   * case. The implementation must guard explicitly because
   * {@code NaN < 0} is {@code false} in Java's IEEE-754 semantics.
   */
  @Test
  public void indexLookup_passesSelectivityCheck_nanSelectivity_rejects() {
    var indexDesc = mock(IndexSearchDescriptor.class);
    when(indexDesc.estimateSelectivity(any())).thenReturn(Double.NaN);
    var desc = new IndexLookup(indexDesc);
    var ctx = new BasicCommandContext();

    assertThat(desc.passesSelectivityCheck(0, 0, ctx)).isFalse();
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
  // anyChildPassesExcluding — Composite fallback helper
  // =========================================================================

  /**
   * Excluded type is skipped: an IndexLookup child whose
   * passesSelectivityCheck would otherwise return true is not consulted,
   * and the result reflects only the non-excluded children.
   */
  @Test
  public void composite_anyChildPassesExcluding_skipsExcludedType() {
    var indexLookup = mock(RidFilterDescriptor.IndexLookup.class);
    when(indexLookup.passesSelectivityCheck(anyInt(), anyInt(), any()))
        .thenReturn(true); // would pass if consulted
    var edgeLookup = mock(RidFilterDescriptor.EdgeRidLookup.class);
    when(edgeLookup.passesSelectivityCheck(anyInt(), anyInt(), any()))
        .thenReturn(false); // the only non-excluded child fails

    var composite = new RidFilterDescriptor.Composite(
        List.of(indexLookup, edgeLookup));
    var ctx = new BasicCommandContext();

    assertThat(composite.anyChildPassesExcluding(
        RidFilterDescriptor.IndexLookup.class, 100, 1000, ctx)).isFalse();
    verify(indexLookup, never()).passesSelectivityCheck(
        anyInt(), anyInt(), any());
  }

  /**
   * Non-excluded child that passes its own check makes the composite pass:
   * the excluded type is skipped, but the remaining child is queried and
   * its result is honoured.
   */
  @Test
  public void composite_anyChildPassesExcluding_nonExcludedChildPasses() {
    var indexLookup = mock(RidFilterDescriptor.IndexLookup.class);
    var edgeLookup = mock(RidFilterDescriptor.EdgeRidLookup.class);
    when(edgeLookup.passesSelectivityCheck(anyInt(), anyInt(), any()))
        .thenReturn(true);

    var composite = new RidFilterDescriptor.Composite(
        List.of(indexLookup, edgeLookup));
    var ctx = new BasicCommandContext();

    assertThat(composite.anyChildPassesExcluding(
        RidFilterDescriptor.IndexLookup.class, 100, 1000, ctx)).isTrue();
  }

  /**
   * Empty composite returns false regardless of the excluded type — there
   * are no children to query.
   */
  @Test
  public void composite_anyChildPassesExcluding_empty_returnsFalse() {
    var composite = new RidFilterDescriptor.Composite(List.of());
    var ctx = new BasicCommandContext();

    assertThat(composite.anyChildPassesExcluding(
        RidFilterDescriptor.IndexLookup.class, 100, 1000, ctx)).isFalse();
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
   * Unknown selectivity (-1.0) → {@link Double#MAX_VALUE} (never build).
   * The cost-model formula m = estimatedSize / (ratio · (1 − s)) cannot
   * be evaluated without a valid s; mapping to MAX_VALUE makes the
   * downstream {@code forecastN > ceil(m)} and {@code accumulator < ceil(T)}
   * comparisons collapse to "never trigger", which is the design-coherent
   * response to a missing histogram input.
   */
  @Test
  public void computeMinNeighborsForBuild_unknownSelectivity_neverBuilds() {
    assertThat(EdgeTraversal.computeMinNeighborsForBuild(
        100_000, 100.0, -1.0)).isEqualTo(Double.MAX_VALUE);
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

  /** Change detector — the SSD-calibrated default is 100. */
  @Test
  public void defaultLoadToScanRatio_isOneHundred() {
    assertThat(EdgeTraversal.DEFAULT_LOAD_TO_SCAN_RATIO).isEqualTo(100.0);
  }

  /** Change detector — CLT confidence threshold for BUILD_EAGER is 30. */
  @Test
  public void minForClt_isThirty() {
    assertThat(EdgeTraversal.MIN_FOR_CLT).isEqualTo(30L);
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
   * Regression: when {@code estimateSelectivity} returns -1.0 (unknown),
   * {@code resolveWithCache} records {@link PreFilterSkipReason#STATS_UNAVAILABLE}
   * and skips materialisation. Previously this path returned PROCEED
   * optimistically — safe only while {@code maxRidSetSize} was a fixed
   * 100K cap, but disastrous after auto-scaling raised the cap to 10M
   * (see IC2 regression: a 1.5M-entry index scan on the cold path for
   * every query). The fix matches the design's bounded-loss contract:
   * without a known {@code s} the build cost {@code B} is unbounded,
   * so the only safe response is to skip the pre-filter.
   */
  @Test
  public void resolveWithCache_indexLookup_unknownSelectivity_skipsAndRecordsStatsUnavailable() {
    var et = createEdgeTraversal();
    var ridSet = singletonRidSet(10, 1);
    var desc = stubIndexLookup(-1.0, 100_000, "Post.date", ridSet);
    et.setIntersectionDescriptor(desc);
    var ctx = new BasicCommandContext();

    var result = et.resolveWithCache(ctx, 10_000);

    assertThat(result).isNull();
    verify(desc, never()).resolve(any(), any());
    assertThat(et.getLastSkipReason())
        .isEqualTo(PreFilterSkipReason.STATS_UNAVAILABLE);
    assertThat(et.getPreFilterSkippedCount()).isEqualTo(1);

    // Subsequent vertices hit cached null and restore STATS_UNAVAILABLE
    // (rather than leaving lastSkipReason stale from an interleaved skip).
    assertThat(et.resolveWithCache(ctx, 10_000)).isNull();
    assertThat(et.getLastSkipReason())
        .isEqualTo(PreFilterSkipReason.STATS_UNAVAILABLE);
    assertThat(et.getPreFilterSkippedCount()).isEqualTo(2);
    verify(desc, never()).resolve(any(), any());
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
   * threshold (0.96 > 0.95) AND an EdgeRidLookup whose ratio check
   * also fails. Both children fail their selectivity checks, so the
   * Composite is rejected even with the fallback.
   */
  @Test
  public void resolveWithCache_compositeWithIndexLookup_selectivityTooHigh_rejected() {
    var et = createEdgeTraversal();
    var ridSet = singletonRidSet(10, 1);

    var indexLookup = stubIndexLookup(0.96, 100_000, "Post.date", ridSet);
    // Override: selectivity 0.96 > 0.95 → passesSelectivityCheck = false
    when(indexLookup.passesSelectivityCheck(anyInt(), anyInt(), any()))
        .thenReturn(false);
    var edgeLookup = mock(RidFilterDescriptor.EdgeRidLookup.class);
    when(edgeLookup.estimatedSize(any(), any())).thenReturn(500);
    when(edgeLookup.cacheKey(any())).thenReturn(new RecordId(10, 1));
    // EdgeRidLookup ratio also fails
    when(edgeLookup.passesSelectivityCheck(anyInt(), anyInt(), any()))
        .thenReturn(false);

    var composite = new RidFilterDescriptor.Composite(
        List.of(edgeLookup, indexLookup));
    et.setIntersectionDescriptor(composite);
    var ctx = new BasicCommandContext();

    // IndexLookup selectivity REJECTED → Composite fallback checks
    // children's passesSelectivityCheck → both fail → null
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
   * All expected enum values are defined with the correct names, in the
   * declared order. BUILD_FAILED is recorded when descriptor.resolve()
   * returns null on the materialization cold path (e.g. resolveIndexToRidSet
   * aborted, or RecordNotFoundException on a reverse-edge target).
   * STATS_UNAVAILABLE is recorded by checkIndexLookupAmortization when
   * estimateSelectivity returns -1 — the cost-model formula degenerates
   * without a known selectivity, so the only design-coherent response is
   * to skip the pre-filter and let normal traversal proceed.
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
            PreFilterSkipReason.OVERLAP_RATIO_TOO_HIGH,
            PreFilterSkipReason.BUILD_FAILED,
            PreFilterSkipReason.STATS_UNAVAILABLE,
            PreFilterSkipReason.IN_LIST_SELECTIVITY_TOO_LOW);
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
   * Variant B safety branch: when the calibrated path is active
   * (rootSourceRows < MIN_FOR_CLT, in-list sample present) and the
   * sampled selectivity exceeds the configured threshold, the counter
   * records IN_LIST_SELECTIVITY_TOO_LOW and returns null. Without this
   * branch, computeMinNeighborsForBuild collapses to MAX_VALUE on
   * selectivity ≥ 1.0 and the trigger silently masquerades as
   * BUILD_NOT_AMORTIZED for the rest of the execution — wasting the
   * sample work and masking the real diagnostic.
   *
   * <p>Class-level selectivity is set to 0.10 (well below the 0.95
   * threshold, so the class-level REJECT gate does not fire). The
   * sampled in-list selectivity is injected at 0.99 to model an LDBC
   * IC2-shaped adjacency-list bias.
   */
  @Ignore("EXPERIMENTAL: CLT-fail rollback to 0dd39b3 behavior disables the"
      + " Variant B in-list calibration branch. Re-enable when Variant B"
      + " is reintroduced.")
  @Test
  public void resolveWithCache_inListSelectivityTooLow_recordsCounter() {
    var et = createEdgeTraversal();
    var ridSet = singletonRidSet(10, 1);
    // Class-level 0.10 passes the SELECTIVITY_TOO_LOW gate.
    var desc = stubIndexLookup(0.10, 100_000, "Post.date", ridSet);
    et.setIntersectionDescriptor(desc);
    // CLT-fail path: rootSourceRows in [0, 30) enables the calibrated
    // branch. Combined with the injected in-list sample, useCalibratedM
    // becomes true inside checkIndexLookupAmortization.
    et.setRootSourceRows(10);
    // Inject a sampled selectivity above the 0.95 threshold to simulate
    // a biased adjacency list whose in-list mix is much higher than the
    // class average.
    et.setInListSelectivity(0.99);
    var ctx = new BasicCommandContext();

    var result = et.resolveWithCache(ctx, LARGE_LINKBAG);

    assertThat(result).isNull();
    assertThat(et.getLastSkipReason())
        .isEqualTo(PreFilterSkipReason.IN_LIST_SELECTIVITY_TOO_LOW);
    assertThat(et.getPreFilterSkippedCount()).isEqualTo(1);
    assertThat(et.getPreFilterAppliedCount()).isZero();
    // Sample-based REJECT must NOT cache null — the sample is empirical
    // and per-execution, so a fresh execution against less biased bags
    // is free to re-evaluate. Re-calling with the same linkBagSize must
    // hit the REJECT branch again (skip count grows) rather than a
    // cached short-circuit.
    et.resolveWithCache(ctx, LARGE_LINKBAG);
    assertThat(et.getPreFilterSkippedCount()).isEqualTo(2);
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
    // buildTime nanoTime pair only fires under PROFILE — enable so the
    // counter is non-zero for the assertion below.
    et.setProfilingEnabled(true);
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

    // buildTime nanoTime pair only fires under PROFILE — enable so the
    // counter is non-zero for the assertion below.
    et.setProfilingEnabled(true);

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
   * Regression: cached-null hit must restore the original rejection reason
   * even if an external {@code recordPreFilterSkip(LINKBAG_TOO_SMALL)} call
   * (made by {@code MatchEdgeTraverser.applyPreFilter} when a vertex has
   * a link bag below {@code minLinkBagSize}) overwrites
   * {@link EdgeTraversal#lastSkipReason} between the original caching and
   * the next cached-null hit. Without restoration, PROFILE's NEVER-APPLIED
   * diagnostic would report LINKBAG_TOO_SMALL instead of the real
   * permanent cause (here: CAP_EXCEEDED), masking the actual reason
   * pre-filter was disabled for this edge.
   */
  @Test
  public void resolveWithCache_cacheHitNull_restoresReasonAfterInterleavedSkip() {
    var et = createEdgeTraversal();
    var desc = mock(EdgeRidLookup.class);
    var key = new RecordId(5, 1);

    when(desc.cacheKey(any())).thenReturn(key);
    when(desc.estimatedSize(any(), any()))
        .thenReturn(TraversalPreFilterHelper.maxRidSetSize() + 1);
    et.setIntersectionDescriptor(desc);
    var ctx = new BasicCommandContext();

    // V1: large link bag → CAP_EXCEEDED, null cached, reason recorded.
    et.resolveWithCache(ctx, LARGE_LINKBAG);
    assertThat(et.getLastSkipReason())
        .isEqualTo(PreFilterSkipReason.CAP_EXCEEDED);

    // V2: small link bag → MatchEdgeTraverser bypasses resolveWithCache
    // and overwrites lastSkipReason with LINKBAG_TOO_SMALL.
    et.recordPreFilterSkip(PreFilterSkipReason.LINKBAG_TOO_SMALL);
    assertThat(et.getLastSkipReason())
        .isEqualTo(PreFilterSkipReason.LINKBAG_TOO_SMALL);

    // V3: large link bag → cached-null hit must restore CAP_EXCEEDED.
    et.resolveWithCache(ctx, LARGE_LINKBAG);
    assertThat(et.getLastSkipReason())
        .isEqualTo(PreFilterSkipReason.CAP_EXCEEDED);
    // Both skips (V2 external + V3 cached-null) counted.
    assertThat(et.getPreFilterSkippedCount()).isEqualTo(3);
  }

  /**
   * When resolve() returns null on the materialization cold path (all
   * selectivity checks passed, but descriptor produced no RidSet — e.g.
   * resolveIndexToRidSet aborted on the runtime checkpoint guard, or a
   * RecordNotFoundException short-circuited resolveReverseEdgeLookup),
   * preFilterBuildTimeNanos is accumulated (build cost was real),
   * preFilterAppliedCount stays at 0, and BUILD_FAILED is recorded as the
   * skip reason so the PROFILE NEVER-APPLIED diagnostic names a concrete
   * cause instead of leaving lastSkipReason at NONE.
   */
  @Test
  public void resolveWithCache_resolveReturnsNull_recordsBuildFailedReason() {
    var et = createEdgeTraversal();
    var desc = mock(EdgeRidLookup.class);
    var key = new RecordId(5, 1);

    when(desc.cacheKey(any())).thenReturn(key);
    when(desc.resolve(any(), any())).thenReturn(null);
    stubSmallEstimate(desc);
    et.setIntersectionDescriptor(desc);
    // buildTime nanoTime pair only fires under PROFILE — enable so the
    // counter is non-zero for the assertion below.
    et.setProfilingEnabled(true);
    var ctx = new BasicCommandContext();

    var result = et.resolveWithCache(ctx, LARGE_LINKBAG);

    assertThat(result).isNull();
    assertThat(et.getPreFilterBuildTimeNanos()).isGreaterThan(0L);
    assertThat(et.getPreFilterAppliedCount()).isZero();
    assertThat(et.getPreFilterRidSetSize()).isZero();
    assertThat(et.getLastSkipReason())
        .isEqualTo(PreFilterSkipReason.BUILD_FAILED);
    assertThat(et.getPreFilterSkippedCount()).isEqualTo(1);
  }

  /**
   * Regression for the second part of the same cold-path bug: a cached
   * null produced by resolve() returning null must remember BUILD_FAILED
   * in cachedSkipReasons so a subsequent vertex with the same key
   * restores BUILD_FAILED rather than carrying a stale prior reason (or
   * leaving lastSkipReason untouched, which the original code did).
   * Without this, an interleaved external skip between V1 and V2 would
   * mask the real cold-path failure in the PROFILE diagnostic.
   */
  @Test
  public void resolveWithCache_cachedNullFromBuildFailure_restoresReason() {
    var et = createEdgeTraversal();
    var desc = mock(EdgeRidLookup.class);
    var key = new RecordId(5, 1);

    when(desc.cacheKey(any())).thenReturn(key);
    when(desc.resolve(any(), any())).thenReturn(null);
    stubSmallEstimate(desc);
    et.setIntersectionDescriptor(desc);
    var ctx = new BasicCommandContext();

    // V1: cold-path resolve returns null, BUILD_FAILED cached.
    et.resolveWithCache(ctx, LARGE_LINKBAG);
    assertThat(et.getLastSkipReason())
        .isEqualTo(PreFilterSkipReason.BUILD_FAILED);

    // External interleaved skip overwrites lastSkipReason.
    et.recordPreFilterSkip(PreFilterSkipReason.LINKBAG_TOO_SMALL);
    assertThat(et.getLastSkipReason())
        .isEqualTo(PreFilterSkipReason.LINKBAG_TOO_SMALL);

    // V2: cached-null hit must restore BUILD_FAILED, not leave the stale
    // LINKBAG_TOO_SMALL value visible to PROFILE.
    et.resolveWithCache(ctx, LARGE_LINKBAG);
    assertThat(et.getLastSkipReason())
        .isEqualTo(PreFilterSkipReason.BUILD_FAILED);
    assertThat(et.getPreFilterSkippedCount()).isEqualTo(3);
  }

  /**
   * Regression for the cache-capacity off-by-one. When the cache is
   * full from prior CAP_EXCEEDED rejections and a new key undergoes a
   * REJECT-then-successful-rescue path, the rescue's resolved RidSet must
   * replace the just-inserted null sentinel (a HashMap.put on an existing
   * key does not grow the map, so it is safe at capacity).
   *
   * <p>Before the fix, the rescue path's {@code cache.put(key, ridSet)}
   * was guarded by {@code cache.size() < CACHE_CAPACITY} — false at
   * capacity — so the freshly-built RidSet was silently dropped and the
   * cached null persisted, causing subsequent vertices on the same key
   * to skip with the rejection reason of the very last REJECT (here:
   * CAP_EXCEEDED) instead of returning the materialized set.
   *
   * <p>Note: this test exercises a non-Composite descriptor so the same
   * key flow that recorded the null can also produce a non-null RidSet —
   * we simulate the rescue by reconfiguring the mock between the null
   * caching call and the materialization call. The mechanism under test
   * (HashMap.put overwrite at capacity) is descriptor-agnostic.
   */
  @Test
  public void resolveWithCache_atCapacity_overwritesCachedNullWithRidSet() {
    var et = createEdgeTraversal();
    var ctx = new BasicCommandContext();

    // Fill the 64-slot cache with CAP_EXCEEDED null entries on 64
    // distinct keys. Each vertex uses its own descriptor returning a
    // unique cacheKey and an over-cap estimate.
    var capacity = 64;
    for (int i = 0; i < capacity; i++) {
      var filler = mock(EdgeRidLookup.class);
      when(filler.cacheKey(any())).thenReturn(new RecordId(9, i));
      when(filler.estimatedSize(any(), any()))
          .thenReturn(TraversalPreFilterHelper.maxRidSetSize() + 1);
      et.setIntersectionDescriptor(filler);
      et.resolveWithCache(ctx, LARGE_LINKBAG);
    }

    // 65th key: descriptor whose estimate also overflows the cap so the
    // first call inserts another null. The cache is already at CAPACITY,
    // so this insert would not be accepted under the old guard — verify
    // we still cache it (containsKey-aware guard means a fresh put at
    // capacity is rejected, but an overwrite on an already-cached key is
    // accepted). To exercise the overwrite, we then reconfigure the
    // descriptor to return a real RidSet and call again with the SAME
    // key, simulating the Composite REJECT-then-rescue control flow.
    var target = new RecordId(7, 99);
    var pivot = mock(EdgeRidLookup.class);
    when(pivot.cacheKey(any())).thenReturn(target);

    // Phase 1: over-cap estimate → CAP_EXCEEDED → null cached (or
    // dropped if cache is full — either way the post-phase state is
    // important: the key may or may not be in the cache).
    when(pivot.estimatedSize(any(), any()))
        .thenReturn(TraversalPreFilterHelper.maxRidSetSize() + 1);
    et.setIntersectionDescriptor(pivot);
    et.resolveWithCache(ctx, LARGE_LINKBAG);

    // Phase 2: descriptor now passes selectivity and resolves a real
    // RidSet. Old guard at the materialization branch would NOT allow
    // overwriting the cached null at full capacity → silent drop.
    // New guard via canCache(key) does allow it, so the second call on
    // the same key must return the non-null RidSet either freshly
    // resolved or restored from the now-overwritten cache.
    var ridSet = singletonRidSet(7, 99);
    when(pivot.resolve(any(), any())).thenReturn(ridSet);
    when(pivot.estimatedSize(any(), any())).thenReturn(1);
    when(pivot.passesSelectivityCheck(anyInt(), anyInt(), any()))
        .thenReturn(true);

    var first = et.resolveWithCache(ctx, LARGE_LINKBAG);
    assertThat(first).isSameAs(ridSet);

    // Third call on the same key must NOT see the stale cached null —
    // either it hits the freshly cached ridSet, or (if the cache
    // dropped the new entry as well) re-resolves. Asserting non-null is
    // sufficient: the bug manifested as a stale-null return here.
    var second = et.resolveWithCache(ctx, LARGE_LINKBAG);
    assertThat(second).isSameAs(ridSet);
  }

  // ---- Metric resolve fallback (null registry in unit test env) ----

  /**
   * resolveEffectivenessMetric() must return a functional dual-sink Ratio
   * that delegates {@code record(filtered, probed)} both to the global
   * registry metric (live {@link Ratio.Impl} or {@link Ratio#NOOP}) and to
   * this traversal's per-query probed/filtered totals. The cached reference
   * is reused on subsequent calls, avoiding repeated ConcurrentHashMap
   * lookups in the registry.
   */
  @Test
  public void resolveEffectivenessMetricReturnsCachedFunctionalMetric() {
    var et = createEdgeTraversal();
    var metric = et.resolveEffectivenessMetric();
    assertThat(metric).isNotNull();

    // Recording on the wrapper must not throw and must update this
    // traversal's per-query counters via the second sink.
    metric.record(50, 100);
    assertThat(et.getPreFilterTotalProbed()).isEqualTo(100);
    assertThat(et.getPreFilterTotalFiltered()).isEqualTo(50);

    // Second call returns the same cached wrapper reference (no re-resolve).
    assertThat(et.resolveEffectivenessMetric()).isSameAs(metric);

    // The wrapper's getRatio() must delegate without throwing, even when the
    // backing global delegate has no data yet (returns 0.0 from NOOP/Impl).
    assertThat(metric.getRatio()).isNotNaN();
  }

  // =========================================================================
  // Composite with high-selectivity IndexLookup — regression test for
  // IC5 benchmark timeout (YTDB-651). A Composite(IndexLookup, EdgeRidLookup)
  // must NOT be rejected when only the IndexLookup fails the selectivity
  // threshold but the EdgeRidLookup still makes the pre-filter effective.
  // =========================================================================

  /**
   * Composite containing a high-selectivity IndexLookup (0.98 > 0.95
   * threshold) and a selective EdgeRidLookup. The IndexLookup alone
   * would be rejected, but the Composite should fall back to
   * passesSelectivityCheck() where the EdgeRidLookup child passes,
   * allowing materialization to proceed.
   *
   * <p>This reproduces the IC5 (newGroups) regression where a Composite
   * of HAS_MEMBER.joinDate index + back-reference EdgeRidLookup was
   * permanently rejected, causing a 28x performance regression.
   */
  @Test
  public void resolveWithCache_composite_indexHighSelectivity_edgeRidFallback() {
    var et = createEdgeTraversal();

    // IndexLookup child: selectivity 0.98 (> 0.95 threshold → would
    // reject a standalone IndexLookup).
    var indexRidSet = singletonRidSet(10, 1);
    var indexChild = stubIndexLookup(0.98, 500, "HAS_MEMBER.joinDate",
        indexRidSet);

    // EdgeRidLookup child: highly selective (resolves to 1 RID).
    var edgeChild = mock(EdgeRidLookup.class);
    var edgeRidSet = singletonRidSet(5, 1);
    when(edgeChild.cacheKey(any())).thenReturn(new RecordId(5, 1));
    when(edgeChild.estimatedSize(any(), any())).thenReturn(1);
    when(edgeChild.resolve(any(), any())).thenReturn(edgeRidSet);
    when(edgeChild.passesSelectivityCheck(anyInt(), anyInt(), any()))
        .thenReturn(true);

    // Build a real Composite (not mocked) so passesSelectivityCheck()
    // delegates to children correctly.
    var composite = new RidFilterDescriptor.Composite(
        List.of(indexChild, edgeChild));
    et.setIntersectionDescriptor(composite);
    var ctx = new BasicCommandContext();

    // The Composite must NOT be rejected — EdgeRidLookup saves it.
    var result = et.resolveWithCache(ctx, LARGE_LINKBAG);
    assertThat(result).isNotNull();
  }

  /**
   * Composite fallback after IndexLookup REJECT must NOT consult the
   * IndexLookup child's own {@code passesSelectivityCheck}. Today the two
   * thresholds happen to agree, so an implicit any-match over all children
   * would still give the right answer — but only by coincidence. This
   * test pathologically forces {@code IndexLookup.passesSelectivityCheck}
   * to return {@code true} while amortization REJECTs, and the
   * EdgeRidLookup child to FAIL. The expectation is that the Composite is
   * skipped, because the only decision that matters at this point is the
   * non-IndexLookup children's verdicts.
   */
  @Test
  public void resolveWithCache_composite_indexRejectedButPassesCheck_edgeRidFails_skipped() {
    var et = createEdgeTraversal();
    var ridSet = singletonRidSet(10, 1);

    // IndexLookup: selectivity 0.98 → amortization REJECT, but force
    // passesSelectivityCheck to return true (contradicting the threshold).
    var indexLookup = stubIndexLookup(0.98, 500, "Post.date", ridSet);
    when(indexLookup.passesSelectivityCheck(anyInt(), anyInt(), any()))
        .thenReturn(true);

    // EdgeRidLookup: ratio check fails.
    var edgeLookup = mock(RidFilterDescriptor.EdgeRidLookup.class);
    when(edgeLookup.estimatedSize(any(), any())).thenReturn(500);
    when(edgeLookup.cacheKey(any())).thenReturn(new RecordId(10, 1));
    when(edgeLookup.passesSelectivityCheck(anyInt(), anyInt(), any()))
        .thenReturn(false);

    var composite = new RidFilterDescriptor.Composite(
        List.of(indexLookup, edgeLookup));
    et.setIntersectionDescriptor(composite);
    var ctx = new BasicCommandContext();

    // Skip: EdgeRidLookup is the only valid non-excluded voter, and it
    // says no. The IndexLookup's stubbed-true passesSelectivityCheck
    // must not save the composite.
    assertThat(et.resolveWithCache(ctx, LARGE_LINKBAG)).isNull();
  }

  /**
   * Standalone IndexLookup with selectivity above threshold is still
   * rejected (no Composite fallback applies).
   */
  @Test
  public void resolveWithCache_standaloneIndexLookup_highSelectivity_stillRejected() {
    var et = createEdgeTraversal();
    var ridSet = singletonRidSet(10, 1);
    var desc = stubIndexLookup(0.98, 500, "HAS_MEMBER.joinDate", ridSet);
    et.setIntersectionDescriptor(desc);
    var ctx = new BasicCommandContext();

    assertThat(et.resolveWithCache(ctx, LARGE_LINKBAG)).isNull();
    verify(desc, never()).resolve(any(), any());
  }

  /**
   * copy() must reset the cached effectiveness metric reference — stale
   * metric data from a previous execution must not leak into a new plan
   * instance.
   */
  @Test
  public void copy_resetsCachedEffectiveness() {
    var et = createEdgeTraversal();
    et.setCachedEffectiveness(Ratio.NOOP);

    var copy = et.copy();

    assertThat(copy.getCachedEffectiveness()).isNull();
  }

  // =========================================================================
  // evaluateIndexLookupAmortization — stateless guard reused by
  // BackRefHashJoinStep (YTDB-651 IC5 regression fix)
  // =========================================================================

  /**
   * Selectivity above {@code indexLookupMaxSelectivity} (default 0.95) must
   * return REJECT regardless of accumulator — the condition matches too
   * many records to ever justify the build.
   */
  @Test
  public void evaluateIndexLookupAmortization_highSelectivity_rejects() {
    var decision = EdgeTraversal.evaluateIndexLookupAmortization(
        /* estimatedSize */ 100_000,
        /* selectivity   */ 0.98,
        /* accumulated   */ 10_000_000L,
        /* loadToScan    */ 100.0);
    assertThat(decision).isEqualTo(EdgeTraversal.AmortizationDecision.REJECT);
  }

  /**
   * Selectivity == threshold is the boundary; the check is strictly
   * greater-than, so equality still PROCEEDs to the amortization branch.
   * With selectivity 0.95, loadToScan 100 and estimatedSize 100K, the
   * formula yields 100K / (100 * 0.05) = 20_000, so a large accumulator
   * should PROCEED (not REJECT, not DEFER).
   */
  @Test
  public void evaluateIndexLookupAmortization_selectivityAtThreshold_proceeds() {
    var decision = EdgeTraversal.evaluateIndexLookupAmortization(
        /* estimatedSize */ 100_000,
        /* selectivity   */ 0.95,
        /* accumulated   */ 100_000L,
        /* loadToScan    */ 100.0);
    assertThat(decision).isEqualTo(EdgeTraversal.AmortizationDecision.PROCEED);
  }

  /**
   * IC5-shaped input: indexHits=1_000_000, selectivity=0.5,
   * loadToScan=25 (warm-cache live ratio), accumulated=75_000.
   * minNeighbors = 1_000_000 / (25 * 0.5) = 80_000.
   * Accumulator 75K is just below the threshold → DEFER (the regression
   * fix: IC5 must NOT materialise the RidSet).
   */
  @Test
  public void evaluateIndexLookupAmortization_ic5Shape_defers() {
    var decision = EdgeTraversal.evaluateIndexLookupAmortization(
        /* estimatedSize */ 1_000_000,
        /* selectivity   */ 0.5,
        /* accumulated   */ 75_000L,
        /* loadToScan    */ 25.0);
    assertThat(decision).isEqualTo(EdgeTraversal.AmortizationDecision.DEFER);
  }

  /**
   * Same IC5 shape but with cold-start ratio=100 the threshold drops to
   * 1_000_000 / (100 * 0.5) = 20_000; accumulator 75K is well above it,
   * so the guard PROCEEDs. This illustrates why the live cost ratio
   * matters — cold-start triggers the build, warm metrics defer it.
   */
  @Test
  public void evaluateIndexLookupAmortization_ic5Shape_coldStart_proceeds() {
    var decision = EdgeTraversal.evaluateIndexLookupAmortization(
        /* estimatedSize */ 1_000_000,
        /* selectivity   */ 0.5,
        /* accumulated   */ 75_000L,
        /* loadToScan    */ 100.0);
    assertThat(decision).isEqualTo(EdgeTraversal.AmortizationDecision.PROCEED);
  }

  /**
   * Selectivity unknown (negative sentinel from IndexSearchDescriptor) →
   * REJECT. The stateless variant cannot bound build cost B without a
   * valid s, so the design's bounded-loss contract requires REJECT
   * (callers cache null with STATS_UNAVAILABLE when they have the state).
   * Previously this branch returned PROCEED, which combined with the
   * auto-scaled 10M maxRidSetSize cap caused IC2 to scan a 1.5M-entry
   * index on the cold path for every query.
   */
  @Test
  public void evaluateIndexLookupAmortization_unknownSelectivity_rejects() {
    var decision = EdgeTraversal.evaluateIndexLookupAmortization(
        /* estimatedSize */ 100_000,
        /* selectivity   */ -1.0,
        /* accumulated   */ 0L,
        /* loadToScan    */ 100.0);
    assertThat(decision).isEqualTo(EdgeTraversal.AmortizationDecision.REJECT);
  }

  /**
   * NaN selectivity also REJECTs. {@code Double.NaN < 0} evaluates to
   * {@code false} under IEEE-754, so a plain {@code selectivity < 0} guard
   * would have let a NaN sentinel slip through into the downstream
   * {@code 1 - selectivity} arithmetic (which would itself yield NaN). The
   * implementation guards explicitly with {@link Double#isNaN(double)}
   * even though no current caller produces NaN — the cost of the extra
   * branch is negligible and keeps the contract aligned with the javadoc.
   */
  @Test
  public void evaluateIndexLookupAmortization_nanSelectivity_rejects() {
    var decision = EdgeTraversal.evaluateIndexLookupAmortization(
        /* estimatedSize */ 100_000,
        /* selectivity   */ Double.NaN,
        /* accumulated   */ 0L,
        /* loadToScan    */ 100.0);
    assertThat(decision).isEqualTo(EdgeTraversal.AmortizationDecision.REJECT);
  }

  /**
   * Zero estimatedSize implies {@code computeMinNeighborsForBuild=0}, so
   * even an empty accumulator PROCEEDs (trivially small build — do it now).
   */
  @Test
  public void evaluateIndexLookupAmortization_zeroEstimatedSize_proceeds() {
    var decision = EdgeTraversal.evaluateIndexLookupAmortization(
        /* estimatedSize */ 0,
        /* selectivity   */ 0.5,
        /* accumulated   */ 0L,
        /* loadToScan    */ 100.0);
    assertThat(decision).isEqualTo(EdgeTraversal.AmortizationDecision.PROCEED);
  }

  /**
   * Accumulator equal to {@code Math.ceil(minNeighbors)} is the boundary —
   * strict less-than means equality PROCEEDs. With estimatedSize=100K,
   * selectivity=0.5, loadToScan=100 → minNeighbors=2000.0,
   * Math.ceil=2000; accumulator 2000 → PROCEED.
   */
  @Test
  public void evaluateIndexLookupAmortization_accumulatorAtThreshold_proceeds() {
    var decision = EdgeTraversal.evaluateIndexLookupAmortization(
        /* estimatedSize */ 100_000,
        /* selectivity   */ 0.5,
        /* accumulated   */ 2_000L,
        /* loadToScan    */ 100.0);
    assertThat(decision).isEqualTo(EdgeTraversal.AmortizationDecision.PROCEED);
  }

  /**
   * Accumulator one below the ceiling → DEFER. Pairs with
   * {@link #evaluateIndexLookupAmortization_accumulatorAtThreshold_proceeds}
   * to fence the strict-less-than boundary.
   */
  @Test
  public void evaluateIndexLookupAmortization_accumulatorJustBelowThreshold_defers() {
    var decision = EdgeTraversal.evaluateIndexLookupAmortization(
        /* estimatedSize */ 100_000,
        /* selectivity   */ 0.5,
        /* accumulated   */ 1_999L,
        /* loadToScan    */ 100.0);
    assertThat(decision).isEqualTo(EdgeTraversal.AmortizationDecision.DEFER);
  }

  // =========================================================================
  // Option C: forecastN threading + Mode memoization
  // =========================================================================

  /**
   * Default state: forecastN starts absent (-1) and mode starts
   * {@link EdgeTraversal.Mode#UNDETERMINED}. setForecastN normalises any
   * negative input back to -1.
   */
  @Test
  public void forecastN_defaultAbsent_modeUndetermined() {
    var et = createEdgeTraversal();
    assertThat(et.getForecastN()).isEqualTo(-1L);
    assertThat(et.getMode()).isEqualTo(EdgeTraversal.Mode.UNDETERMINED);

    et.setForecastN(-42L);
    assertThat(et.getForecastN()).isEqualTo(-1L);

    et.setForecastN(0L);
    assertThat(et.getForecastN()).isZero();

    et.setForecastN(12345L);
    assertThat(et.getForecastN()).isEqualTo(12345L);
  }

  /**
   * Small root-lineage sample size (RID-bind pattern, n=1) forbids
   * BUILD_EAGER regardless of how high {@code forecastN} sits above
   * {@code m}. Regression guard for LDBC IC2: a single {@code :personId}
   * binding gives a forecast that is essentially {@code mean × const}
   * with full mean-variance exposure to heavy-tail distributions; BUILD_EAGER
   * on such a forecast commits the one-time build cost without statistical
   * justification.
   */
  @Test
  public void resolveWithCache_smallRootSourceRows_deniesEagerEvenWithLargeForecast() {
    var et = createEdgeTraversal();
    // m=2000 (100K / (100·0.5)); forecastN=50K >> m would normally BUILD_EAGER.
    et.setForecastN(50_000L);
    // RID-bound source: only 1 sample at the root of the lineage. CLT
    // cannot justify trusting forecastN here.
    et.setRootSourceRows(1L);
    var ridSet = singletonRidSet(10, 1);
    var desc = stubIndexLookup(0.5, 100_000, "Post.date", ridSet);
    et.setIntersectionDescriptor(desc);
    var ctx = new BasicCommandContext();

    var result = et.resolveWithCache(ctx, 100);

    assertThat(result).isNull();
    assertThat(et.getMode()).isEqualTo(EdgeTraversal.Mode.DEFERRED_WITH_NET);
    verify(desc, never()).resolve(any(), any());
  }

  /**
   * BUILD_EAGER fires when CLT confidence holds
   * ({@code rootSourceRows >= MIN_FOR_CLT}) AND the cost-model break-even
   * is satisfied ({@code forecastN > ceil(m)}). With estimatedSize=100K,
   * selectivity=0.5, loadToScan=100 → m=2000; forecastN=5000 > 2000 and
   * rootSourceRows=100 >> 30 → materialise on the first vertex regardless
   * of linkBagSize.
   */
  @Test
  public void resolveWithCache_largeSampleAndForecastAboveM_buildsEagerly() {
    var et = createEdgeTraversal();
    et.setForecastN(5000L); // > m=2000
    et.setRootSourceRows(100L); // >= 30 → CLT confidence
    var ridSet = singletonRidSet(10, 1);
    var desc = stubIndexLookup(0.5, 100_000, "Post.date", ridSet);
    et.setIntersectionDescriptor(desc);
    var ctx = new BasicCommandContext();

    // linkBagSize = 100 — too small for the m=2000 trigger to fire under
    // the legacy formula, but BUILD_EAGER ignores the accumulator.
    var result = et.resolveWithCache(ctx, 100);

    assertThat(result).isSameAs(ridSet);
    assertThat(et.getMode()).isEqualTo(EdgeTraversal.Mode.BUILD_EAGER);
    verify(desc, times(1)).resolve(any(), any());
  }

  /**
   * DEFERRED_WITH_NET fires when forecastN ≤ ceil(m). With m=2000 and
   * forecastN=500, mode resolves to DEFERRED_WITH_NET. The safety-net
   * trigger is {@code T = max(2·500, 2000) = 2000} (floor wins), so a
   * vertex with linkBagSize=1500 < 2000 must DEFER.
   */
  @Test
  public void resolveWithCache_forecastBelowM_deferredWithNetSmallLinkBag() {
    var et = createEdgeTraversal();
    et.setForecastN(500L);
    var ridSet = singletonRidSet(10, 1);
    var desc = stubIndexLookup(0.5, 100_000, "Post.date", ridSet);
    et.setIntersectionDescriptor(desc);
    var ctx = new BasicCommandContext();

    var result = et.resolveWithCache(ctx, 1500);

    assertThat(result).isNull();
    assertThat(et.getMode()).isEqualTo(EdgeTraversal.Mode.DEFERRED_WITH_NET);
    verify(desc, never()).resolve(any(), any());
  }

  /**
   * DEFERRED_WITH_NET trigger crossing: with forecastN=1500 (≤ m=2000) and
   * m=2000, the adaptive trigger {@code T = max(2·1500, 2000) = 3000}. A
   * vertex with linkBagSize=3000 reaches the trigger and PROCEEDs.
   */
  @Test
  public void resolveWithCache_deferredWithNet_adaptiveTriggerFires() {
    var et = createEdgeTraversal();
    et.setForecastN(1500L);
    var ridSet = singletonRidSet(10, 1);
    var desc = stubIndexLookup(0.5, 100_000, "Post.date", ridSet);
    et.setIntersectionDescriptor(desc);
    var ctx = new BasicCommandContext();

    var result = et.resolveWithCache(ctx, 3000);

    assertThat(result).isSameAs(ridSet);
    assertThat(et.getMode()).isEqualTo(EdgeTraversal.Mode.DEFERRED_WITH_NET);
  }

  /**
   * Absent forecast (-1) collapses to f=0, so {@code T = max(0, m) = m}.
   * That matches the legacy single-m trigger exactly — backward
   * compatibility for edges whose plan-time walk could not produce a
   * forecast.
   */
  @Test
  public void resolveWithCache_absentForecast_triggerFloorEqualsM() {
    var et = createEdgeTraversal();
    // forecastN stays -1 (default).
    var ridSet = singletonRidSet(10, 1);
    var desc = stubIndexLookup(0.5, 100_000, "Post.date", ridSet);
    et.setIntersectionDescriptor(desc);
    var ctx = new BasicCommandContext();

    // m=2000; linkBagSize=2000 hits the floor → PROCEED.
    var result = et.resolveWithCache(ctx, 2000);
    assertThat(result).isSameAs(ridSet);
    assertThat(et.getMode()).isEqualTo(EdgeTraversal.Mode.DEFERRED_WITH_NET);
  }

  /**
   * Mode is memoized on the first call and not recomputed on subsequent
   * calls. Even if forecastN were mutated between calls (it shouldn't be
   * at runtime, but the test forces the issue), the second call respects
   * the first decision.
   */
  @Test
  public void resolveWithCache_modeMemoizedAcrossCalls() {
    var et = createEdgeTraversal();
    et.setForecastN(5000L); // > m=2000
    et.setRootSourceRows(100L); // >= 30 → BUILD_EAGER fires
    var ridSet1 = singletonRidSet(10, 1);
    var ridSet2 = singletonRidSet(10, 2);
    // Different cache keys so the second call exercises mode logic again
    // rather than hitting the RidSet cache.
    var desc = mock(IndexLookup.class);
    var indexDesc = mock(IndexSearchDescriptor.class);
    when(indexDesc.estimateSelectivity(any())).thenReturn(0.5);
    when(indexDesc.estimateHits(any())).thenReturn(100_000L);
    var index = mock(Index.class);
    when(index.getName()).thenReturn("Post.date");
    when(indexDesc.getIndex()).thenReturn(index);
    when(desc.indexDescriptor()).thenReturn(indexDesc);
    when(desc.cacheKey(any())).thenReturn("k1", "k2");
    when(desc.estimatedSize(any(), any())).thenReturn(100_000);
    when(desc.passesSelectivityCheck(anyInt(), anyInt(), any()))
        .thenReturn(true);
    when(desc.resolve(any(), any())).thenReturn(ridSet1, ridSet2);
    et.setIntersectionDescriptor(desc);
    var ctx = new BasicCommandContext();

    // First call sets mode = BUILD_EAGER, materialises.
    et.resolveWithCache(ctx, 50);
    assertThat(et.getMode()).isEqualTo(EdgeTraversal.Mode.BUILD_EAGER);

    // Forcibly stale-ify forecastN; mode must still be BUILD_EAGER on
    // the second call (different cache key, exercises checkIndexLookupAmortization).
    et.setForecastN(0L);
    et.resolveWithCache(ctx, 50);
    assertThat(et.getMode()).isEqualTo(EdgeTraversal.Mode.BUILD_EAGER);
    verify(desc, times(2)).resolve(any(), any());
  }

  /**
   * copy() preserves forecastN (bind-independent, structural) but resets
   * mode to UNDETERMINED (bind-dependent — recomputed on the next
   * execution's first call).
   */
  @Test
  public void copy_preservesForecastN_resetsMode() {
    var et = createEdgeTraversal();
    et.setForecastN(7777L); // > m=2000
    et.setRootSourceRows(100L); // >= 30 → BUILD_EAGER fires
    var ridSet = singletonRidSet(10, 1);
    var desc = stubIndexLookup(0.5, 100_000, "Post.date", ridSet);
    et.setIntersectionDescriptor(desc);
    et.resolveWithCache(new BasicCommandContext(), 50);
    assertThat(et.getMode()).isEqualTo(EdgeTraversal.Mode.BUILD_EAGER);

    var copy = et.copy();

    assertThat(copy.getForecastN()).isEqualTo(7777L);
    assertThat(copy.getMode()).isEqualTo(EdgeTraversal.Mode.UNDETERMINED);
  }

  /**
   * High selectivity REJECTs before mode decision. forecastN value is
   * irrelevant — the REJECT short-circuit fires first and caches null
   * permanently. Mode stays UNDETERMINED because the decision was never
   * reached.
   */
  @Test
  public void resolveWithCache_highSelectivity_rejectsBeforeModeDecision() {
    var et = createEdgeTraversal();
    et.setForecastN(1_000_000L); // huge, would be BUILD_EAGER if reached
    var ridSet = singletonRidSet(10, 1);
    // selectivity 0.98 > 0.95 threshold → REJECT
    var desc = stubIndexLookup(0.98, 100_000, "Post.date", ridSet);
    et.setIntersectionDescriptor(desc);
    var ctx = new BasicCommandContext();

    var result = et.resolveWithCache(ctx, 50);

    assertThat(result).isNull();
    assertThat(et.getMode()).isEqualTo(EdgeTraversal.Mode.UNDETERMINED);
    verify(desc, never()).resolve(any(), any());
  }
}
