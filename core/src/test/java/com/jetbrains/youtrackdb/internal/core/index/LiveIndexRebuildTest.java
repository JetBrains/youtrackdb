package com.jetbrains.youtrackdb.internal.core.index;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.SessionPool;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
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

  private final String indexName = "liveIndex";
  private final String className = "liveIndexClass";
  private final String propertyName = "liveIndexProperty";

  private final AtomicBoolean stop = new AtomicBoolean();

  @Test
  @Ignore
  public void testLiveIndexRebuild() throws Exception {
    try (var youTrackDb = (YouTrackDBImpl) YourTracks.instance(
        DbTestBase.getBaseDirectoryPathStr(LiveIndexRebuildTest.class))) {
      if (youTrackDb.exists(LiveIndexRebuildTest.class.getSimpleName())) {
        youTrackDb.drop(LiveIndexRebuildTest.class.getSimpleName());
      }
      youTrackDb.create(LiveIndexRebuildTest.class.getSimpleName(), DatabaseType.DISK, "admin",
          "admin", "admin");
      try (var db = youTrackDb.open(LiveIndexRebuildTest.class.getSimpleName(), "admin", "admin")) {
        db.executeInTx(transaction -> {
          final var clazz = db.getSchema().createClass(className);
          clazz.createProperty(propertyName, PropertyType.INTEGER);
          clazz.createIndex(indexName, SchemaClass.INDEX_TYPE.UNIQUE, propertyName);

          for (var i = 0; i < 1000000; i++) {
            var document = transaction.newEntity(className);
            document.setProperty(propertyName, i);
          }
        });
      }

      try (var pool = youTrackDb.cachedPool(LiveIndexRebuildTest.class.getSimpleName(), "admin",
          "admin")) {
        var executorService = Executors.newFixedThreadPool(6);
        List<Future<?>> futures = new ArrayList<Future<?>>();

        for (var i = 0; i < 5; i++) {
          futures.add(executorService.submit(new Reader(pool)));
        }

        futures.add(executorService.submit(new Writer(pool)));

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
    }
  }

  private final class Writer implements Callable<Void> {

    private final SessionPool pool;

    private Writer(SessionPool pool) {
      this.pool = pool;
    }

    @Override
    public Void call() throws Exception {
      try {
        var rebuildInterval = new long[1];
        var rebuildCount = new long[1];
        while (!stop.get()) {
          for (var i = 0; i < 10; i++) {
            try (var database = pool.acquire()) {
              database.executeInTx(transaction -> {
                var start = System.nanoTime();
                transaction.execute("rebuild index " + indexName).close();
                var end = System.nanoTime();
                rebuildInterval[0] += (end - start);
                rebuildCount[0]++;
              });
            }

            if (stop.get()) {
              break;
            }
          }

          //noinspection BusyWait
          Thread.sleep(5 * 60 * 1000);
        }

        System.out.println(
            "Average rebuild interval " + ((rebuildInterval[0] / rebuildCount[0]) / 1000000)
                + ", ms");
      } catch (Exception e) {
        e.printStackTrace();
        throw e;
      }
      return null;
    }
  }

  private final class Reader implements Callable<long[]> {

    private final SessionPool pool;

    private Reader(SessionPool pool) {
      this.pool = pool;
    }

    @Override
    public long[] call() throws Exception {
      var minInterval = new long[]{Long.MAX_VALUE};
      var maxInterval = new long[]{Long.MIN_VALUE};

      try {
        while (!stop.get()) {
          try (var database = pool.acquire()) {
            database.executeInTx(transaction -> {
              var start = System.nanoTime();
              final var result =
                  transaction.query(
                      "select from "
                          + className
                          + " where "
                          + propertyName
                          + " >= 100 and "
                          + propertyName
                          + "< 200");

              var end = System.nanoTime();
              var interval = end - start;

              if (interval > maxInterval[0]) {
                maxInterval[0] = interval;
              }

              if (interval < minInterval[0]) {
                minInterval[0] = interval;
              }

              Assert.assertEquals(100, result.stream().count());
            });
          }
        }

      } catch (Exception e) {
        e.printStackTrace();
        throw e;
      }

      return new long[]{minInterval[0], maxInterval[0]};
    }
  }
}
