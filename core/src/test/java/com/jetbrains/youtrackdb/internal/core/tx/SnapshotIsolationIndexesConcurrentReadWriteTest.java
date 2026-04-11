package com.jetbrains.youtrackdb.internal.core.tx;

import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass.INDEX_TYPE;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Multi-threaded concurrent read/write test for snapshot isolation on indexes.
 *
 * <p>Unlike the existing SI end-to-end tests (which simulate concurrent transactions
 * sequentially on the same thread), this test spawns actual concurrent threads where
 * writers insert records and commit while readers open snapshot TXs and query the
 * index. This exercises JMM visibility, instruction reordering, and real contention
 * on the {@code ConcurrentSkipListMap} backing the snapshot index.
 *
 * <p>Test design: pre-populate N records, then writers continuously insert new records
 * with unique keys beyond the pre-populated range. Readers open snapshot TXs and query
 * pre-populated keys — they must always see exactly 1 result per key, regardless of
 * concurrent writer activity.
 */
public class SnapshotIsolationIndexesConcurrentReadWriteTest {

  private YouTrackDBImpl youTrackDB;
  private DatabaseSessionEmbedded db;

  @Before
  public void before() {
    youTrackDB = DbTestBase.createYTDBManagerAndDb(
        "test", DatabaseType.MEMORY, getClass());
    db = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
  }

  @After
  public void after() {
    db.close();
    youTrackDB.close();
  }

  /**
   * UNIQUE index: 4 writer threads continuously insert new records while
   * 4 reader threads open snapshot TXs and query pre-populated keys.
   *
   * <p>Pre-populated keys (0..99) are never modified by writers, so readers
   * must always see exactly 1 result per key. Writers insert keys starting
   * from 1000 upward, exercising the visibility filter under concurrent
   * B-tree modifications.
   */
  @Test
  public void unique_concurrentReadersAndWriters_noPhantoms() throws Throwable {
    int writerCount = 4;
    int readerCount = 4;
    int prePopulated = 100;

    var cls = db.createClass("CRWU");
    cls.createProperty("key", PropertyType.INTEGER);
    cls.createIndex("CRWUIdx", INDEX_TYPE.UNIQUE, "key");

    // Pre-populate keys 0..99
    db.begin();
    for (int i = 0; i < prePopulated; i++) {
      db.newEntity("CRWU").setProperty("key", i);
    }
    db.commit();

    var running = new AtomicBoolean(true);
    var barrier = new CyclicBarrier(writerCount + readerCount);
    var latch = new CountDownLatch(writerCount + readerCount);
    var error = new AtomicReference<Throwable>();
    var nextKey = new AtomicInteger(1000);

    // Writers: continuously insert new records with unique keys >= 1000
    for (int w = 0; w < writerCount; w++) {
      new Thread(
          () -> {
            DatabaseSessionEmbedded s = null;
            try {
              s = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
              barrier.await();
              while (running.get() && error.get() == null) {
                int keyVal = nextKey.getAndIncrement();
                s.begin();
                s.newEntity("CRWU").setProperty("key", keyVal);
                s.commit();
              }
            } catch (Throwable t) {
              error.compareAndSet(null, t);
            } finally {
              if (s != null && !s.isClosed()) {
                try {
                  s.rollback();
                } catch (Exception ignored) {
                }
                s.close();
              }
              latch.countDown();
            }
          },
          "writer-" + w)
          .start();
    }

    // Readers: open snapshot TXs and verify pre-populated keys are stable
    for (int r = 0; r < readerCount; r++) {
      new Thread(
          () -> {
            DatabaseSessionEmbedded s = null;
            try {
              s = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
              barrier.await();
              var rng = ThreadLocalRandom.current();
              for (int i = 0; i < 200 && running.get() && error.get() == null; i++) {
                s.begin();
                try {
                  int queryKey = rng.nextInt(prePopulated);
                  var rs = s.query(
                      "SELECT FROM CRWU WHERE key = ?", queryKey);
                  int count = 0;
                  while (rs.hasNext()) {
                    var entity = rs.next();
                    var val = (Integer) entity.getProperty("key");
                    if (val == null || val != queryKey) {
                      error.compareAndSet(
                          null,
                          new AssertionError(
                              "Phantom read: queried key=" + queryKey
                                  + " but got key=" + val));
                    }
                    count++;
                  }
                  rs.close();
                  if (count != 1) {
                    error.compareAndSet(
                        null,
                        new AssertionError(
                            "Expected exactly 1 result for key=" + queryKey
                                + " but got " + count));
                  }
                } finally {
                  s.rollback();
                }
              }
              running.set(false);
            } catch (Throwable t) {
              error.compareAndSet(null, t);
              running.set(false);
            } finally {
              if (s != null && !s.isClosed()) {
                try {
                  s.rollback();
                } catch (Exception ignored) {
                }
                s.close();
              }
              latch.countDown();
            }
          },
          "reader-" + r)
          .start();
    }

    assertTrue("Threads should finish within 30s",
        latch.await(30, TimeUnit.SECONDS));
    if (error.get() != null) {
      throw error.get();
    }
  }

  /**
   * NOTUNIQUE index: same concurrent pattern with a non-unique index.
   * Writers insert new records while readers query pre-populated tags
   * and verify count consistency within each snapshot TX.
   */
  @Test
  public void notUnique_concurrentReadersAndWriters_noPhantoms() throws Throwable {
    int writerCount = 4;
    int readerCount = 4;
    int prePopulated = 100;

    var cls = db.createClass("CRWNU");
    cls.createProperty("tag", PropertyType.STRING);
    cls.createIndex("CRWNUIdx", INDEX_TYPE.NOTUNIQUE, "tag");

    // Pre-populate 100 records with unique tags "t0".."t99"
    db.begin();
    for (int i = 0; i < prePopulated; i++) {
      db.newEntity("CRWNU").setProperty("tag", "t" + i);
    }
    db.commit();

    var running = new AtomicBoolean(true);
    var barrier = new CyclicBarrier(writerCount + readerCount);
    var latch = new CountDownLatch(writerCount + readerCount);
    var error = new AtomicReference<Throwable>();
    var nextTag = new AtomicInteger(1000);

    // Writers: insert new records with unique tags "t1000", "t1001", ...
    for (int w = 0; w < writerCount; w++) {
      new Thread(
          () -> {
            DatabaseSessionEmbedded s = null;
            try {
              s = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
              barrier.await();
              while (running.get() && error.get() == null) {
                int tagNum = nextTag.getAndIncrement();
                s.begin();
                s.newEntity("CRWNU").setProperty("tag", "t" + tagNum);
                s.commit();
              }
            } catch (Throwable t) {
              error.compareAndSet(null, t);
            } finally {
              if (s != null && !s.isClosed()) {
                try {
                  s.rollback();
                } catch (Exception ignored) {
                }
                s.close();
              }
              latch.countDown();
            }
          },
          "writer-" + w)
          .start();
    }

    // Readers: query pre-populated tags and verify 1 result each
    for (int r = 0; r < readerCount; r++) {
      new Thread(
          () -> {
            DatabaseSessionEmbedded s = null;
            try {
              s = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
              barrier.await();
              var rng = ThreadLocalRandom.current();
              for (int i = 0; i < 200 && running.get() && error.get() == null; i++) {
                s.begin();
                try {
                  int tagIdx = rng.nextInt(prePopulated);
                  var tag = "t" + tagIdx;
                  var rs = s.query(
                      "SELECT FROM CRWNU WHERE tag = ?", tag);
                  int count = 0;
                  while (rs.hasNext()) {
                    var entity = rs.next();
                    var val = (String) entity.getProperty("tag");
                    if (!tag.equals(val)) {
                      error.compareAndSet(
                          null,
                          new AssertionError(
                              "Phantom read: queried tag='" + tag
                                  + "' but got tag='" + val + "'"));
                    }
                    count++;
                  }
                  rs.close();
                  // Pre-populated tags each appear exactly once
                  if (count != 1) {
                    error.compareAndSet(
                        null,
                        new AssertionError(
                            "Expected 1 result for tag='" + tag
                                + "' but got " + count));
                  }
                } finally {
                  s.rollback();
                }
              }
              running.set(false);
            } catch (Throwable t) {
              error.compareAndSet(null, t);
              running.set(false);
            } finally {
              if (s != null && !s.isClosed()) {
                try {
                  s.rollback();
                } catch (Exception ignored) {
                }
                s.close();
              }
              latch.countDown();
            }
          },
          "reader-" + r)
          .start();
    }

    assertTrue("Threads should finish within 30s",
        latch.await(30, TimeUnit.SECONDS));
    if (error.get() != null) {
      throw error.get();
    }

    // After all threads complete, verify total count
    db.begin();
    try {
      var rs = db.query("SELECT count(*) as cnt FROM CRWNU");
      var count = (Long) rs.next().getProperty("cnt");
      rs.close();
      assertTrue("Total count must be >= pre-populated", count >= prePopulated);
    } finally {
      db.rollback();
    }
  }
}
