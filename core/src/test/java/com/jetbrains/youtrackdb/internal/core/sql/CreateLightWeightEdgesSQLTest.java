package com.jetbrains.youtrackdb.internal.core.sql;

import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB.PredefinedRole;
import com.jetbrains.youtrackdb.api.YouTrackDB.UserCredential;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.exception.ConcurrentModificationException;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.SessionPool;
import com.jetbrains.youtrackdb.internal.core.db.SessionPoolImpl;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import java.util.concurrent.CountDownLatch;
import java.util.stream.IntStream;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class CreateLightWeightEdgesSQLTest {

  private YouTrackDBImpl youTrackDB;

  @Before
  public void before() {
    youTrackDB = (YouTrackDBImpl) YourTracks.instance(DbTestBase.getBaseDirectoryPath(getClass()));
    youTrackDB.create(CreateLightWeightEdgesSQLTest.class.getSimpleName(), DatabaseType.MEMORY,
        new UserCredential("admin", DbTestBase.ADMIN_PASSWORD, PredefinedRole.ADMIN));
  }

  @Test
  public void test() {
    var session = youTrackDB.open(
        CreateLightWeightEdgesSQLTest.class.getSimpleName(),
        "admin",
        DbTestBase.ADMIN_PASSWORD);

    session.getSchema().createLightweightEdgeClass("lightweight");

    var tx = session.begin();
    tx.command("create vertex v set name='a' ");
    tx.command("create vertex v set name='b' ");
    tx.command(
        "create edge lightweight from (select from v where name='a') to (select from v where name='a') ");
    tx.commit();

    tx = session.begin();
    try (var res = tx.query(
        "select expand(out('lightweight')) from v where name='a' ")) {
      assertEquals(1, res.stream().count());
    }
    tx.commit();
    session.close();
  }

  @Test
  public void mtTest() throws InterruptedException {
    SessionPool pool = new SessionPoolImpl(
        youTrackDB,
        CreateLightWeightEdgesSQLTest.class.getSimpleName(),
        "admin",
        DbTestBase.ADMIN_PASSWORD);

    var session = pool.acquire();

    session.getSchema().createLightweightEdgeClass("lightweight");

    var tx = session.begin();
    tx.command("create vertex v set id = 1 ");
    tx.command("create vertex v set id = 2 ");
    tx.commit();

    session.close();

    var latch = new CountDownLatch(10);

    IntStream.range(0, 10)
        .forEach(
            (i) -> new Thread(
                () -> {
                  try (var session1 = pool.acquire()) {
                    for (var j = 0; j < 100; j++) {

                      try {
                        var tx1 = session1.begin();
                        tx1.command(
                            "create edge lightweight from (select from v where id=1) to (select from v"
                                + " where id=2) ");
                        tx1.commit();
                      } catch (ConcurrentModificationException e) {
                        // ignore
                      }
                    }
                  } finally {
                    latch.countDown();
                  }
                })
                .start());

    latch.await();

    session = pool.acquire();
    tx = session.begin();
    try (var res = tx.query(
        "select sum(out('lightweight').size()) as size from V where id = 1");
        var res1 = tx.query(
            "select sum(in('lightweight').size()) as size from V where id = 2")) {

      var s1 = res.findFirst(r -> r.getLong("size"));
      var s2 = res1.findFirst(r -> r.getLong("size"));
      assertEquals(s1, s2);

    } finally {
      session.close();
      pool.close();
    }
  }

  @After
  public void after() {
    youTrackDB.close();
  }
}
