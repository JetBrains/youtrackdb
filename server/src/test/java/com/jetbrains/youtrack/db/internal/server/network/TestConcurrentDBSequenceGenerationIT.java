package com.jetbrains.youtrack.db.internal.server.network;

import static org.junit.Assert.assertNotNull;

import com.jetbrains.youtrack.db.api.YouTrackDB;
import com.jetbrains.youtrack.db.api.config.YouTrackDBConfig;
import com.jetbrains.youtrack.db.internal.common.io.FileUtils;
import com.jetbrains.youtrack.db.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrack.db.internal.server.YouTrackDBServer;
import java.io.File;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class TestConcurrentDBSequenceGenerationIT {

  static final int THREADS = 20;
  static final int RECORDS = 100;
  private YouTrackDBServer server;
  private YouTrackDB youTrackDB;

  @Before
  public void before() throws Exception {
    server = new YouTrackDBServer(false);
    server.startup(getClass().getResourceAsStream("youtrackdb-server-config.xml"));
    server.activate();
    youTrackDB = new YouTrackDBImpl("remote:localhost", "root", "root",
        YouTrackDBConfig.defaultConfig());
    youTrackDB.execute(
        "create database ? memory users (admin identified by 'admin' role admin)",
        TestConcurrentDBSequenceGenerationIT.class.getSimpleName());
    var databaseSession =
        youTrackDB.open(TestConcurrentDBSequenceGenerationIT.class.getSimpleName(), "admin",
            "admin");
    databaseSession.runScript(
        "sql",
        """
            CREATE CLASS TestSequence EXTENDS V;
            CREATE SEQUENCE TestSequenceIdSequence TYPE ORDERED;
            CREATE PROPERTY TestSequence.id LONG (MANDATORY TRUE, default\
            "sequence('TestSequenceIdSequence').next()");
            CREATE INDEX TestSequence_id_index ON TestSequence (id BY VALUE) UNIQUE;""");
    databaseSession.close();
  }

  @Test
  public void test() throws Exception {

    try (var pool = youTrackDB.cachedPool(
        TestConcurrentDBSequenceGenerationIT.class.getSimpleName(),
        "admin", "admin")) {
      var executorService = Executors.newFixedThreadPool(THREADS);
      var futures = new ArrayList<Future<Object>>();

      for (var i = 0; i < THREADS; i++) {
        var future =
            executorService.submit(
                () -> {
                  try (var db = (DatabaseSessionInternal) pool.acquire()) {
                    Assert.assertTrue(db.assertIfNotActive());
                    for (var j = 0; j < RECORDS; j++) {
                      db.executeInTx(transaction -> {
                        var vert = db.newVertex("TestSequence");
                        assertNotNull(vert.getProperty("id"));
                      });
                    }
                  }

                  return null;
                });
        futures.add(future);
      }

      for (var future : futures) {
        future.get();
      }

      executorService.shutdown();
    }
  }

  @After
  public void after() {
    youTrackDB.drop(TestConcurrentDBSequenceGenerationIT.class.getSimpleName());
    youTrackDB.close();
    server.shutdown();

    YouTrackDBEnginesManager.instance().shutdown();
    FileUtils.deleteRecursively(new File(server.getDatabaseDirectory()));
    YouTrackDBEnginesManager.instance().startup();
  }
}
