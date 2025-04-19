package com.jetbrains.youtrack.db.internal.server.network;

import static org.junit.Assert.assertNotNull;

import com.jetbrains.youtrack.db.api.YourTracks;
import com.jetbrains.youtrack.db.api.common.SessionPool;
import com.jetbrains.youtrack.db.api.config.YouTrackDBConfig;
import com.jetbrains.youtrack.db.api.remote.RemoteYouTrackDB;
import com.jetbrains.youtrack.db.internal.client.remote.db.DatabaseSessionRemote;
import com.jetbrains.youtrack.db.internal.common.io.FileUtils;
import com.jetbrains.youtrack.db.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrack.db.internal.core.db.SessionPoolImpl;
import com.jetbrains.youtrack.db.internal.core.db.YouTrackDBAbstract;
import com.jetbrains.youtrack.db.internal.server.YouTrackDBServer;
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
  private RemoteYouTrackDB youTrackDB;

  @Before
  public void before() throws Exception {
    server = new YouTrackDBServer(false);
    server.startup(getClass().getResourceAsStream("youtrackdb-server-config.xml"));
    server.activate();
    youTrackDB = YourTracks.remote("remote:localhost", "root", "root",
        YouTrackDBConfig.defaultConfig());
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
                        create vertex TestSequence;
                        commit;
                        """).findFirst().getIdentity();
                    var entity = db.query("select from TestSequence where @rid = ?", rid)
                        .findFirst();
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
