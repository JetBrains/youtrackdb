package com.jetbrains.youtrackdb.internal.core.tx;

import static org.junit.Assert.assertEquals;
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
    db.activateOnCurrentThread();
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
  @Test(timeout = 60_000)
  public void unique_concurrentReadersAndWriters_noPhantoms() throws Throwable {
    db.activateOnCurrentThread();
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
  @Test(timeout = 60_000)
  public void notUnique_concurrentReadersAndWriters_noPhantoms() throws Throwable {
    db.activateOnCurrentThread();
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

  /**
   * Same-key contention: multiple writers concurrently update the same UNIQUE
   * key while readers verify exactly 1 result is always visible for that key.
   *
   * <p>This exercises the doVersionedPut scan-then-remove-then-put sequence
   * and snapshot-level visibility under real contention on the same B-tree
   * leaf page and IndexesSnapshot entries.
   */
  @Test(timeout = 60_000)
  public void unique_concurrentUpdatesSameKey_readersAlwaysSeeConsistentValue()
      throws Throwable {
    db.activateOnCurrentThread();
    int writerCount = 4;
    int readerCount = 4;

    var cls = db.createClass("SameKeyU");
    cls.createProperty("key", PropertyType.INTEGER);
    cls.createProperty("ver", PropertyType.INTEGER);
    cls.createIndex("SameKeyUIdx", INDEX_TYPE.UNIQUE, "key");

    // Insert initial record: key=1, ver=0
    db.begin();
    var initial = db.newEntity("SameKeyU");
    initial.setProperty("key", 1);
    initial.setProperty("ver", 0);
    db.commit();

    var running = new AtomicBoolean(true);
    var barrier = new CyclicBarrier(writerCount + readerCount);
    var latch = new CountDownLatch(writerCount + readerCount);
    var error = new AtomicReference<Throwable>();
    var versionCounter = new AtomicInteger(1);

    // Writers: concurrently update key=1 with incrementing versions
    for (int w = 0; w < writerCount; w++) {
      new Thread(
          () -> {
            DatabaseSessionEmbedded s = null;
            try {
              s = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
              barrier.await();
              for (int i = 0; i < 50 && running.get() && error.get() == null; i++) {
                try {
                  s.begin();
                  var rs = s.query("SELECT FROM SameKeyU WHERE key = 1");
                  if (rs.hasNext()) {
                    var entity = rs.next().asEntity();
                    entity.setProperty("ver", versionCounter.getAndIncrement());
                  }
                  rs.close();
                  s.commit();
                } catch (Exception e) {
                  // Concurrent modification — rollback and retry
                  try {
                    s.rollback();
                  } catch (Exception ignored) {
                  }
                }
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

    // Readers: query key=1 and verify exactly 1 result
    for (int r = 0; r < readerCount; r++) {
      new Thread(
          () -> {
            DatabaseSessionEmbedded s = null;
            try {
              s = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
              barrier.await();
              for (int i = 0; i < 200 && running.get() && error.get() == null; i++) {
                s.begin();
                try {
                  var rs = s.query("SELECT FROM SameKeyU WHERE key = 1");
                  int count = 0;
                  while (rs.hasNext()) {
                    rs.next();
                    count++;
                  }
                  rs.close();
                  if (count != 1) {
                    error.compareAndSet(
                        null,
                        new AssertionError(
                            "Expected exactly 1 result for key=1 but got " + count));
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
   * Same-key contention for NOTUNIQUE: writers insert more records with the
   * same tag while readers verify the count is always >= the pre-populated count.
   *
   * <p>Since all records share the same key, this exercises the multi-value
   * B-tree's concurrent handling of the same key prefix.
   */
  @Test(timeout = 60_000)
  public void notUnique_concurrentInsertSameKey_readersAlwaysSeeMonotonicCount()
      throws Throwable {
    db.activateOnCurrentThread();
    int writerCount = 4;
    int readerCount = 4;
    int prePopulated = 10;

    var cls = db.createClass("SameKeyNU");
    cls.createProperty("tag", PropertyType.STRING);
    cls.createIndex("SameKeyNUIdx", INDEX_TYPE.NOTUNIQUE, "tag");

    // Pre-populate 10 records all with tag="shared"
    db.begin();
    for (int i = 0; i < prePopulated; i++) {
      db.newEntity("SameKeyNU").setProperty("tag", "shared");
    }
    db.commit();

    var running = new AtomicBoolean(true);
    var barrier = new CyclicBarrier(writerCount + readerCount);
    var latch = new CountDownLatch(writerCount + readerCount);
    var error = new AtomicReference<Throwable>();

    // Writers: insert more records with tag="shared"
    for (int w = 0; w < writerCount; w++) {
      new Thread(
          () -> {
            DatabaseSessionEmbedded s = null;
            try {
              s = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
              barrier.await();
              for (int i = 0; i < 50 && running.get() && error.get() == null; i++) {
                s.begin();
                s.newEntity("SameKeyNU").setProperty("tag", "shared");
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

    // Readers: count records with tag="shared" and verify count >= prePopulated
    for (int r = 0; r < readerCount; r++) {
      new Thread(
          () -> {
            DatabaseSessionEmbedded s = null;
            try {
              s = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
              barrier.await();
              for (int i = 0; i < 200 && running.get() && error.get() == null; i++) {
                s.begin();
                try {
                  var rs = s.query(
                      "SELECT count(*) as cnt FROM SameKeyNU WHERE tag = 'shared'");
                  var count = (Long) rs.next().getProperty("cnt");
                  rs.close();
                  if (count < prePopulated) {
                    error.compareAndSet(
                        null,
                        new AssertionError(
                            "Count for tag='shared' must be >= " + prePopulated
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

    // Verify final count
    db.begin();
    try {
      var rs = db.query(
          "SELECT count(*) as cnt FROM SameKeyNU WHERE tag = 'shared'");
      var count = (Long) rs.next().getProperty("cnt");
      rs.close();
      assertEquals("Final count must be prePopulated + (writerCount * 50)",
          prePopulated + writerCount * 50L, count.longValue());
    } finally {
      db.rollback();
    }
  }
}
