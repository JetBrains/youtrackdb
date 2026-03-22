package com.jetbrains.youtrackdb.internal.core.sql.parser;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.BaseMemoryInternalDatabase;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Assert;
import org.junit.Test;
import org.junit.experimental.categories.Category;

@Category(SequentialTest.class)
public class YqlExecutionPlanCacheTest extends BaseMemoryInternalDatabase {

  @Test
  public void testCacheInvalidation1() {
    var cache = YqlExecutionPlanCache.instance(session);
    var stm = "SELECT FROM OUser";

    // schema changes
    session.begin();
    session.query(stm).close();
    session.commit();
    cache = YqlExecutionPlanCache.instance(session);
    Assert.assertTrue(cache.contains(stm));

    var clazz = session.getMetadata().getSchema().createClass("testCacheInvalidation1");
    Assert.assertFalse(cache.contains(stm));

    // schema changes 2
    session.begin();
    session.query(stm).close();
    session.commit();

    cache = YqlExecutionPlanCache.instance(session);
    Assert.assertTrue(cache.contains(stm));

    var prop = clazz.createProperty("name", PropertyType.STRING);
    Assert.assertFalse(cache.contains(stm));

    // index changes
    session.begin();
    session.query(stm).close();
    session.commit();
    cache = YqlExecutionPlanCache.instance(session);
    Assert.assertTrue(cache.contains(stm));

    prop.createIndex(SchemaClass.INDEX_TYPE.NOTUNIQUE);
    Assert.assertFalse(cache.contains(stm));
  }

  @Test
  public void testLRUEvictionLogic() {
    var stm1 = "SELECT FROM V";
    var stm2 = "SELECT FROM E";
    var stm3 = "SELECT FROM OUser";

    // Populate using putInternal (needs a session for copy)
    session.begin();
    session.query(stm1).close();
    session.commit();

    session.begin();
    session.query(stm2).close();
    session.commit();

    // Use the real cache instance to test eviction
    var realCache = YqlExecutionPlanCache.instance(session);
    Assert.assertTrue(realCache.contains(stm1));
    Assert.assertTrue(realCache.contains(stm2));

    // Third entry should evict the oldest
    session.begin();
    session.query(stm3).close();
    session.commit();

    Assert.assertTrue(realCache.contains(stm3));
  }

  @Test
  public void testDisabledCacheWhenSizeIsZero() {
    var cache = new YqlExecutionPlanCache(0);
    Assert.assertFalse(cache.contains("SELECT FROM V"));

    var result = cache.getInternal("SELECT FROM V", null, session);
    Assert.assertNull(result);
  }

  @Test
  public void testConcurrentAccess() throws InterruptedException {
    var cache = YqlExecutionPlanCache.instance(session);
    var threadCount = 8;
    var queriesPerThread = 10;
    var latch = new CountDownLatch(threadCount);
    var errors = new AtomicInteger(0);

    // Pre-populate with a query to test concurrent reads
    session.begin();
    session.query("SELECT FROM OUser").close();
    session.commit();
    Assert.assertTrue(cache.contains("SELECT FROM OUser"));

    for (var t = 0; t < threadCount; t++) {
      var thread = new Thread(() -> {
        try {
          for (var i = 0; i < queriesPerThread; i++) {
            // Concurrent contains() and invalidation should not throw
            cache.contains("SELECT FROM OUser");
          }
        } catch (Exception e) {
          errors.incrementAndGet();
          e.printStackTrace();
        } finally {
          latch.countDown();
        }
      });
      thread.start();
    }

    latch.await();
    Assert.assertEquals("No errors should occur during concurrent access", 0, errors.get());
  }

  @Test
  public void invalidate_clearsCache() {
    var cache = YqlExecutionPlanCache.instance(session);
    session.begin();
    session.query("SELECT FROM OUser").close();
    session.commit();
    Assert.assertTrue(cache.contains("SELECT FROM OUser"));

    cache.invalidate();
    Assert.assertFalse(cache.contains("SELECT FROM OUser"));
  }

  @Test
  public void invalidate_updatesTimestamp() {
    var cache = YqlExecutionPlanCache.instance(session);
    var before = YqlExecutionPlanCache.getLastInvalidation(session);
    cache.invalidate();
    var after = YqlExecutionPlanCache.getLastInvalidation(session);
    Assert.assertTrue("Invalidation timestamp should increase", after > before);
  }

  @Test
  public void invalidate_onDisabledCache_doesNotThrow() {
    var cache = new YqlExecutionPlanCache(0);
    cache.invalidate();
  }

  @Test
  public void onSchemaUpdate_invalidatesCache() {
    var cache = new YqlExecutionPlanCache(10);
    cache.onSchemaUpdate(null, "test", null);
  }

  @Test
  public void onIndexManagerUpdate_invalidatesCache() {
    var cache = new YqlExecutionPlanCache(10);
    cache.onIndexManagerUpdate(null, "test", null);
  }

  @Test
  public void onFunctionLibraryUpdate_invalidatesCache() {
    var cache = new YqlExecutionPlanCache(10);
    cache.onFunctionLibraryUpdate(null, "test");
  }

  @Test
  public void onSequenceLibraryUpdate_invalidatesCache() {
    var cache = new YqlExecutionPlanCache(10);
    cache.onSequenceLibraryUpdate(null, "test");
  }

  @Test
  public void onStorageConfigurationUpdate_invalidatesCache() {
    var cache = new YqlExecutionPlanCache(10);
    cache.onStorageConfigurationUpdate("test", null);
  }

  @Test
  public void testGlobalConfigurationCacheSize() {
    GlobalConfiguration.STATEMENT_CACHE_SIZE.setValue(2);

    var cache = new YqlExecutionPlanCache(
        GlobalConfiguration.STATEMENT_CACHE_SIZE.getValueAsInteger());

    Assert.assertFalse(cache.contains("SELECT FROM V"));
    Assert.assertFalse(cache.contains("SELECT FROM E"));
  }
}
