package com.jetbrains.youtrackdb.internal.server.network;

import static org.junit.Assert.assertNotNull;

import com.jetbrains.youtrackdb.internal.common.io.FileUtils;
import com.jetbrains.youtrackdb.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBRemoteImpl;
import com.jetbrains.youtrackdb.internal.server.YouTrackDBServer;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class TestConcurrentCachedDBSequenceGenerationIT {

  static final int THREADS = 20;
  static final int RECORDS = 100;
  private YouTrackDBServer server;
  private YouTrackDBRemoteImpl youTrackDB;

  @Before
  public void before() throws Exception {
    server = new YouTrackDBServer(false);
    server.startup(getClass().getResourceAsStream("youtrackdb-server-config.xml"));
    server.activate();
    youTrackDB = (YouTrackDBRemoteImpl) YouTrackDBRemoteImpl.remote("remote:localhost", "root",
        "root");
    youTrackDB.execute(
        "create database ? memory users (admin identified by 'admin' role admin)",
        TestConcurrentCachedDBSequenceGenerationIT.class.getSimpleName());
    var databaseSession =
        youTrackDB.open(
            TestConcurrentCachedDBSequenceGenerationIT.class.getSimpleName(), "admin", "admin");
    databaseSession.executeScript(
        "sql",
        """
            CREATE CLASS TestSequence EXTENDS V;
            begin;
            CREATE SEQUENCE TestSequenceIdSequence TYPE CACHED CACHE 100;
            commit;
            CREATE PROPERTY TestSequence.id LONG (MANDATORY TRUE, default\
             "sequence('TestSequenceIdSequence').next()");
            CREATE INDEX TestSequence_id_index ON TestSequence (id BY VALUE) UNIQUE;""");
    databaseSession.close();
  }

  @Test
  public void test() throws InterruptedException {
    var failures = new AtomicLong(0);
    try (var pool = youTrackDB.cachedPool(
        TestConcurrentCachedDBSequenceGenerationIT.class.getSimpleName(), "admin", "admin")) {
      List<Thread> threads = new ArrayList<>();
      for (var i = 0; i < THREADS; i++) {
        var thread =
            new Thread() {
              @Override
              public void run() {
                try (var db = pool.acquire()) {
                  for (var j = 0; j < RECORDS; j++) {
                    var rid = db.computeSQLScript("""
                        begin;
                        let $v = create vertex TestSequence;
                        commit;
                        return $v;
                        """).findFirst().getIdentity();
                    db.begin();
                    var entity = db.query("select id from ?", rid).findFirst();
                    db.commit();
                    assertNotNull(entity.getLong("id"));
                  }
                } catch (Exception e) {
                  failures.incrementAndGet();
                  e.printStackTrace();
                }
              }
            };
        threads.add(thread);
        thread.start();
      }
      for (var t : threads) {
        t.join();
      }
    }
    Assert.assertEquals(0, failures.get());
  }

  @After
  public void after() {
    youTrackDB.drop(TestConcurrentCachedDBSequenceGenerationIT.class.getSimpleName());
    youTrackDB.close();
    server.shutdown();

    YouTrackDBEnginesManager.instance().shutdown();
    FileUtils.deleteRecursively(new File(server.getDatabaseDirectory()));
    YouTrackDBEnginesManager.instance().startup();
  }
}
