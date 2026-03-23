package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
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
    when(desc.resolve(any()))
        .thenReturn(ridSet);
    et.setIntersectionDescriptor(desc);
    var ctx = new BasicCommandContext();

    var first = et.resolveWithCache(ctx);
    var second = et.resolveWithCache(ctx);

    verify(desc, times(1)).resolve(any());
    assertThat(second).isSameAs(first);
  }

  /**
   * When the cache key changes between calls, the cache is invalidated
   * and resolve() is called again.
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
    when(desc.resolve(any()))
        .thenReturn(ridSet1, ridSet2);
    et.setIntersectionDescriptor(desc);
    var ctx = new BasicCommandContext();

    var first = et.resolveWithCache(ctx);
    var second = et.resolveWithCache(ctx);

    verify(desc, times(2)).resolve(any());
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
    when(desc.resolve(any()))
        .thenReturn(ridSet1, ridSet2);
    et.setIntersectionDescriptor(desc);
    var ctx = new BasicCommandContext();

    var first = et.resolveWithCache(ctx);
    var second = et.resolveWithCache(ctx);

    verify(desc, times(2)).resolve(any());
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

    assertThat(et.resolveWithCache(ctx)).isNull();
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
    when(desc.resolve(any()))
        .thenReturn(ridSet1, ridSet2);
    et.setIntersectionDescriptor(desc);
    var ctx = new BasicCommandContext();

    et.resolveWithCache(ctx);

    var copy = et.copy();
    var fromCopy = copy.resolveWithCache(ctx);

    verify(desc, times(2)).resolve(any());
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
    when(desc.resolve(any()))
        .thenReturn(null);
    et.setIntersectionDescriptor(desc);
    var ctx = new BasicCommandContext();

    var first = et.resolveWithCache(ctx);
    assertThat(first).isNull();

    var second = et.resolveWithCache(ctx);
    assertThat(second).isNull();

    verify(desc, times(1)).resolve(any());
  }

  // =========================================================================
  // DirectRid — cacheKey() and resolve()
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
    var result = desc.resolve(new BasicCommandContext());

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
    var result = desc.resolve(new BasicCommandContext());

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
    var result = desc.resolve(new BasicCommandContext());

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

    var desc = new EdgeRidLookup("KNOWS", "out", expr);
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

    var desc = new EdgeRidLookup("KNOWS", "out", expr);
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
  // Composite — resolve and cacheKey
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

    when(desc1.resolve(any())).thenReturn(set1);
    when(desc2.resolve(any())).thenReturn(set2);

    var composite = new RidFilterDescriptor.Composite(
        java.util.List.of(desc1, desc2));
    var result = composite.resolve(new BasicCommandContext());

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
    when(desc1.resolve(any())).thenReturn(set1);
    when(desc2.resolve(any())).thenReturn(null);

    var composite = new RidFilterDescriptor.Composite(
        java.util.List.of(desc1, desc2));
    var result = composite.resolve(new BasicCommandContext());

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
    when(desc.resolve(any()))
        .thenReturn(ridSet1, ridSet2);
    et.setIntersectionDescriptor(desc);
    var ctx = new BasicCommandContext();

    // First calls — cache misses
    assertThat(et.resolveWithCache(ctx)).isSameAs(ridSet1);
    assertThat(et.resolveWithCache(ctx)).isSameAs(ridSet2);
    // Second calls — cache hits
    assertThat(et.resolveWithCache(ctx)).isSameAs(ridSet1);
    assertThat(et.resolveWithCache(ctx)).isSameAs(ridSet2);

    // resolve() called only twice (once per unique key)
    verify(desc, times(2)).resolve(any());
  }
}
