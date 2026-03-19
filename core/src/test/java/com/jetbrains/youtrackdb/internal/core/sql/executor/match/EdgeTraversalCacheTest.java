package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.sql.executor.RidFilterDescriptor;
import com.jetbrains.youtrackdb.internal.core.sql.executor.RidFilterDescriptor.EdgeRidLookup;
import com.jetbrains.youtrackdb.internal.core.sql.executor.RidSet;
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
    when(desc.resolve(any(), eq(RidFilterDescriptor.UNKNOWN_LINKBAG_SIZE)))
        .thenReturn(ridSet);
    et.setIntersectionDescriptor(desc);
    var ctx = new BasicCommandContext();

    var first = et.resolveWithCache(ctx);
    var second = et.resolveWithCache(ctx);

    verify(desc, times(1)).resolve(any(), eq(RidFilterDescriptor.UNKNOWN_LINKBAG_SIZE));
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
    when(desc.resolve(any(), eq(RidFilterDescriptor.UNKNOWN_LINKBAG_SIZE)))
        .thenReturn(ridSet1, ridSet2);
    et.setIntersectionDescriptor(desc);
    var ctx = new BasicCommandContext();

    var first = et.resolveWithCache(ctx);
    var second = et.resolveWithCache(ctx);

    verify(desc, times(2)).resolve(any(), eq(RidFilterDescriptor.UNKNOWN_LINKBAG_SIZE));
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
    when(desc.resolve(any(), eq(RidFilterDescriptor.UNKNOWN_LINKBAG_SIZE)))
        .thenReturn(ridSet1, ridSet2);
    et.setIntersectionDescriptor(desc);
    var ctx = new BasicCommandContext();

    var first = et.resolveWithCache(ctx);
    var second = et.resolveWithCache(ctx);

    verify(desc, times(2)).resolve(any(), eq(RidFilterDescriptor.UNKNOWN_LINKBAG_SIZE));
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
    when(desc.resolve(any(), eq(RidFilterDescriptor.UNKNOWN_LINKBAG_SIZE)))
        .thenReturn(ridSet1, ridSet2);
    et.setIntersectionDescriptor(desc);
    var ctx = new BasicCommandContext();

    et.resolveWithCache(ctx);

    var copy = et.copy();
    var fromCopy = copy.resolveWithCache(ctx);

    verify(desc, times(2)).resolve(any(), eq(RidFilterDescriptor.UNKNOWN_LINKBAG_SIZE));
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
    when(desc.resolve(any(), eq(RidFilterDescriptor.UNKNOWN_LINKBAG_SIZE)))
        .thenReturn(null);
    et.setIntersectionDescriptor(desc);
    var ctx = new BasicCommandContext();

    var first = et.resolveWithCache(ctx);
    assertThat(first).isNull();

    var second = et.resolveWithCache(ctx);
    assertThat(second).isNull();

    verify(desc, times(1)).resolve(any(), eq(RidFilterDescriptor.UNKNOWN_LINKBAG_SIZE));
  }
}
