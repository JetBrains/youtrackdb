package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator;
import com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator.Intention;
import com.jetbrains.youtrackdb.internal.common.directmemory.PageFrame;
import com.jetbrains.youtrackdb.internal.common.directmemory.PageFramePool;
import com.jetbrains.youtrackdb.internal.core.storage.cache.PageView;
import com.jetbrains.youtrackdb.internal.core.storage.collection.CollectionPage;
import com.jetbrains.youtrackdb.internal.core.storage.collection.CollectionPositionMapBucket;
import com.jetbrains.youtrackdb.internal.core.storage.collection.v2.FreeSpaceMapPage;
import com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.v3.CellBTreeSingleValueBucketV3;
import com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.v3.CellBTreeSingleValueEntryPointV3;
import com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.v3.CellBTreeSingleValueV3NullBucket;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Verifies that all DurablePage subclasses used in read paths can be constructed from
 * a PageView (optimistic read path). Each constructor delegates to DurablePage(PageView),
 * which sets speculativeRead=true and changes=null.
 */
public class DurablePageSubclassPageViewTest {

  private static final int PAGE_SIZE =
      GlobalConfiguration.DISK_CACHE_PAGE_SIZE.getValueAsInteger() << 10;

  private DirectMemoryAllocator allocator;
  private PageFramePool pool;
  private PageFrame frame;
  private PageView pageView;

  @Before
  public void setUp() {
    GlobalConfiguration.DIRECT_MEMORY_TRACK_MODE.setValue(true);
    allocator = new DirectMemoryAllocator();
    pool = new PageFramePool(PAGE_SIZE, allocator, 4);
    frame = pool.acquire(true, Intention.TEST);

    long exclusiveStamp = frame.acquireExclusiveLock();
    frame.setPageCoordinates(1, 0);
    frame.releaseExclusiveLock(exclusiveStamp);

    long stamp = frame.tryOptimisticRead();
    assertTrue("Stamp must be valid", stamp != 0);
    pageView = new PageView(frame.getBuffer(), frame, stamp);
  }

  @After
  public void tearDown() {
    long stamp = frame.acquireExclusiveLock();
    frame.setPageCoordinates(-1, -1);
    frame.releaseExclusiveLock(stamp);
    pool.release(frame);
    pool.clear();
    allocator.checkMemoryLeaks();
    GlobalConfiguration.DIRECT_MEMORY_TRACK_MODE.setValue(false);
  }

  @Test
  public void testCellBTreeSingleValueBucketV3() {
    var page = new CellBTreeSingleValueBucketV3<>(pageView);
    assertNotNull(page);
  }

  @Test
  public void testCellBTreeSingleValueV3NullBucket() {
    var page = new CellBTreeSingleValueV3NullBucket(pageView);
    assertNotNull(page);
  }

  @Test
  public void testCellBTreeSingleValueEntryPointV3() {
    var page = new CellBTreeSingleValueEntryPointV3<>(pageView);
    assertNotNull(page);
  }

  @Test
  public void testCollectionPage() {
    var page = new CollectionPage(pageView);
    assertNotNull(page);
  }

  @Test
  public void testCollectionPositionMapBucket() {
    var page = new CollectionPositionMapBucket(pageView);
    assertNotNull(page);
  }

  @Test
  public void testFreeSpaceMapPage() {
    var page = new FreeSpaceMapPage(pageView);
    assertNotNull(page);
  }
}
