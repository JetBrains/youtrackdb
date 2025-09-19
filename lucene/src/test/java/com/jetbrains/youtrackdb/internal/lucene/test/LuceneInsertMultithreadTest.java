/*
 *
 *
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  *      http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 *
 */

package com.jetbrains.youtrackdb.internal.lucene.test;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import org.assertj.core.api.Assertions;
import org.junit.Test;

/**
 *
 */
public class LuceneInsertMultithreadTest {

  private static final int THREADS = 10;
  private static final int RTHREADS = 1;
  private static final int CYCLE = 100;
  private static String buildDirectory;
  private static final String dbName;
  private static final DatabaseType databaseType;
  private static final YouTrackDBImpl YOUTRACKDB;

  static {
    System.getProperty("buildDirectory", ".");
    if (buildDirectory == null) {
      buildDirectory = ".";
    }

    var config = System.getProperty("youtrackdb.test.env");

    if ("ci".equals(config) || "release".equals(config)) {
      databaseType = DatabaseType.DISK;
    } else {
      databaseType = DatabaseType.MEMORY;
    }

    dbName = "multiThread";
    YOUTRACKDB = (YouTrackDBImpl) YourTracks.instance(buildDirectory);
  }

  public LuceneInsertMultithreadTest() {
    super();
  }

  @Test
  public void testConcurrentInsertWithIndex() throws Exception {
    if (YOUTRACKDB.exists(dbName)) {
      YOUTRACKDB.drop(dbName);
    }
    YOUTRACKDB.execute(
        "create database ? " + databaseType + " users(admin identified by 'admin' role admin)",
        dbName);
    Schema schema;
    try (var session = (DatabaseSessionEmbedded) YOUTRACKDB.open(
        dbName, "admin", "admin")) {
      schema = session.getMetadata().getSlowMutableSchema();

      if (schema.getClass("City") == null) {
        var oClass = schema.createClass("City");

        oClass.createProperty("name", PropertyType.STRING);
        oClass.createIndex("City.name", "FULLTEXT", null, null, "LUCENE",
            new String[]{"name"});
      }

      var threads = new Thread[THREADS + RTHREADS];
      for (var i = 0; i < THREADS; ++i) {
        threads[i] = new Thread(new LuceneInsertThread(CYCLE), "ConcurrentWriteTest" + i);
      }

      for (var i = THREADS; i < THREADS + RTHREADS; ++i) {
        threads[i] = new Thread(new LuceneReadThread(CYCLE), "ConcurrentReadTest" + i);
      }

      for (var i = 0; i < THREADS + RTHREADS; ++i) {
        threads[i].start();
      }

      for (var i = 0; i < THREADS + RTHREADS; ++i) {
        threads[i].join();
      }

      var idx = session.getClassInternal("City")
          .getClassIndex(session, "City.name");

      session.begin();
      Assertions.assertThat(idx.size(session))
          .isEqualTo(THREADS * CYCLE);
      session.commit();
    }
    YOUTRACKDB.drop(dbName);
  }

  public static class LuceneInsertThread implements Runnable {

    private final int cycle;

    private LuceneInsertThread(int cycle) {
      this.cycle = cycle;
    }

    @Override
    public void run() {

      try (var db = YOUTRACKDB.open(dbName, "admin", "admin")) {
        var tx = db.begin();
        for (var i = 0; i < cycle; i++) {
          var doc = ((EntityImpl) tx.newEntity("City"));

          doc.setProperty("name", "Rome");

          tx = db.begin();
          tx.commit();
          var commitBuf = 500;
          if (i % commitBuf == 0) {
            tx.commit();
            tx = db.begin();
          }
        }
        tx.commit();
      }
    }
  }

  public static class LuceneReadThread implements Runnable {

    private final int cycle;

    private LuceneReadThread(int cycle) {
      this.cycle = cycle;
    }

    @Override
    public void run() {
      try (var session = (DatabaseSessionEmbedded) YOUTRACKDB.open(
          dbName, "admin", "admin")) {
        var idx = session.getClassInternal("City")
            .getClassIndex(session, "City.name");

        for (var i = 0; i < cycle; i++) {
          try (var stream = idx
              .getRids(session, "Rome")) {
            //noinspection ResultOfMethodCallIgnored
            stream.toList();
          }
        }
      }
    }
  }
}
