package com.jetbrains.youtrack.db.internal.core.index;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.common.SessionPool;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;

public class LiveIndexRebuildTest {
  private final SessionPool<DatabaseSession> pool =
      new PartitionedDatabasePool("memory:liveIndexRebuild", "admin", "admin");

  private final String indexName = "liveIndex";
  private final String className = "liveIndexClass";
  private final String propertyName = "liveIndexProperty";

  private final String databaseURL = "memory:liveIndexRebuild";
  private final AtomicBoolean stop = new AtomicBoolean();

  @Test
  @Ignore
  public void testLiveIndexRebuild() throws Exception {
    final var db = new DatabaseDocumentTx(databaseURL);
    db.create();

    final var clazz = db.getMetadata().getSchema().createClass(className);
    clazz.createProperty(propertyName, PropertyType.INTEGER);

    clazz.createIndex(indexName, SchemaClass.INDEX_TYPE.UNIQUE, propertyName);

    for (var i = 0; i < 1000000; i++) {
      var document = (EntityImpl) db.newEntity(className);
      document.setProperty(propertyName, i);

    }

    var executorService = Executors.newFixedThreadPool(6);
    List<Future<?>> futures = new ArrayList<Future<?>>();

    for (var i = 0; i < 5; i++) {
      futures.add(executorService.submit(new Reader()));
    }

    futures.add(executorService.submit(new Writer()));

    Thread.sleep(60 * 60 * 1000);

    stop.set(true);
    executorService.shutdown();

    var minInterval = Long.MAX_VALUE;
    var maxInterval = Long.MIN_VALUE;

    for (var future : futures) {
      var result = future.get();
      if (result instanceof long[] results) {
        if (results[0] < minInterval) {
          minInterval = results[0];
        }

        if (results[1] > maxInterval) {
          maxInterval = results[1];
        }
      }
    }

    System.out.println(
        "Min interval "
            + (minInterval / 1000000)
            + ", max interval "
            + (maxInterval / 1000000)
            + " ms");
  }

  private final class Writer implements Callable<Void> {

    @Override
    public Void call() throws Exception {
      try {
        long rebuildInterval = 0;
        long rebuildCount = 0;
        while (!stop.get()) {
          for (var i = 0; i < 10; i++) {
            final var database = pool.acquire();
            try {
              var start = System.nanoTime();
              database.execute("rebuild index " + indexName).close();
              var end = System.nanoTime();
              rebuildInterval += (end - start);
              rebuildCount++;
            } finally {
              database.close();
            }

            if (stop.get()) {
              break;
            }
          }

          Thread.sleep(5 * 60 * 1000);
        }

        System.out.println(
            "Average rebuild interval " + ((rebuildInterval / rebuildCount) / 1000000) + ", ms");
      } catch (Exception e) {
        e.printStackTrace();
        throw e;
      }
      return null;
    }
  }

  private final class Reader implements Callable<long[]> {

    @Override
    public long[] call() throws Exception {
      var minInterval = Long.MAX_VALUE;
      var maxInterval = Long.MIN_VALUE;

      try {

        while (!stop.get()) {
          var database = pool.acquire();
          try {
            var start = System.nanoTime();

            final var result =
                database.query(
                    "select from "
                        + className
                        + " where "
                        + propertyName
                        + " >= 100 and "
                        + propertyName
                        + "< 200");

            var end = System.nanoTime();
            var interval = end - start;

            if (interval > maxInterval) {
              maxInterval = interval;
            }

            if (interval < minInterval) {
              minInterval = interval;
            }

            Assert.assertEquals(result.stream().count(), 100);
          } finally {
            database.close();
          }
        }

      } catch (Exception e) {
        e.printStackTrace();
        throw e;
      }

      return new long[]{minInterval, maxInterval};
    }
  }
}
