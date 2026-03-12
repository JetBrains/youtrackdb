/**
 * <p>Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 * <p>http://www.apache.org/licenses/LICENSE-2.0
 *
 * <p>Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jetbrains.youtrackdb.junit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Tests for concurrent operations across multiple database instances.
 */
public class MultipleDBTest extends BaseDBJUnit5Test {

  int oldCollectionCount = 8;

  public MultipleDBTest() {
  }

  @BeforeAll
  @Override
  void beforeAll() throws Exception {
    super.beforeAll();
    oldCollectionCount = GlobalConfiguration.CLASS_COLLECTIONS_COUNT.getValue();
    GlobalConfiguration.CLASS_COLLECTIONS_COUNT.setValue(1);
  }

  @AfterAll
  @Override
  void afterAll() throws Exception {
    GlobalConfiguration.CLASS_COLLECTIONS_COUNT.setValue(oldCollectionCount);
    super.afterAll();
  }

  @Test
  void testObjectMultipleDBsThreaded() throws Exception {
    final var operations_write = 1000;
    final var operations_read = 1;
    final var dbs = 10;

    final Set<String> times = Collections.newSetFromMap(new ConcurrentHashMap<>());

    Set<Future<Void>> threads = new HashSet<>();
    var executorService = Executors.newFixedThreadPool(4);
    for (var i = 0; i < dbs; i++) {
      var dbName = this.dbName + i;
      Callable<Void> t =
          () -> {
            dropDatabase(dbName);
            createDatabase(dbName);
            try {
              var session = createSessionInstance(dbName);

              session.getMetadata().getSchema().getOrCreateClass("DummyObject");

              var start = System.currentTimeMillis();
              for (var j = 0; j < operations_write; j++) {
                session.begin();
                var dummy = session.newInstance("DummyObject");
                dummy.setProperty("name", "name" + j);

                session.commit();

                assertEquals(
                    j, dummy.getIdentity().getCollectionPosition(),
                    "RID was " + dummy.getIdentity());
              }
              var end = System.currentTimeMillis();

              var time =
                  "("
                      + getDbId(session)
                      + ") "
                      + "Executed operations (WRITE) in: "
                      + (end - start)
                      + " ms";
              times.add(time);

              start = System.currentTimeMillis();
              for (var j = 0; j < operations_read; j++) {
                var l = session.query(" select * from DummyObject ").stream().toList();
                assertEquals(operations_write, l.size());
              }
              end = System.currentTimeMillis();

              time =
                  "("
                      + getDbId(session)
                      + ") "
                      + "Executed operations (READ) in: "
                      + (end - start)
                      + " ms";
              times.add(time);

              session.close();

            } finally {
              dropDatabase(dbName);
            }
            return null;
          };

      threads.add(executorService.submit(t));
    }

    for (var future : threads) {
      future.get();
    }
  }

  @Test
  void testDocumentMultipleDBsThreaded() throws Exception {
    final var operations_write = 1000;
    final var operations_read = 1;
    final var dbs = 10;

    final Set<String> times = Collections.newSetFromMap(new ConcurrentHashMap<>());

    Set<Future<Void>> results = new HashSet<>();
    var executorService = Executors.newFixedThreadPool(4);
    for (var i = 0; i < dbs; i++) {
      var dbName = this.dbName + i;
      Callable<Void> t =
          () -> {
            dropDatabase(dbName);
            createDatabase(dbName);

            try (var session = createSessionInstance(dbName)) {
              session.getMetadata().getSchema().createClass("DummyObject", 1);

              var start = System.currentTimeMillis();
              for (var j = 0; j < operations_write; j++) {

                session.begin();
                var dummy = ((EntityImpl) session.newEntity("DummyObject"));
                dummy.setProperty("name", "name" + j);

                session.commit();

                assertEquals(
                    j, dummy.getIdentity().getCollectionPosition(),
                    "RID was " + dummy.getIdentity());
              }
              var end = System.currentTimeMillis();

              var time =
                  "("
                      + getDbId(session)
                      + ") "
                      + "Executed operations (WRITE) in: "
                      + (end - start)
                      + " ms";
              times.add(time);

              start = System.currentTimeMillis();
              for (var j = 0; j < operations_read; j++) {
                var l = session.query(" select * from DummyObject ").stream().toList();
                assertEquals(operations_write, l.size());
              }
              end = System.currentTimeMillis();

              time =
                  "("
                      + getDbId(session)
                      + ") "
                      + "Executed operations (READ) in: "
                      + (end - start)
                      + " ms";
              times.add(time);

            } finally {
              dropDatabase(dbName);
            }
            return null;
          };

      results.add(executorService.submit(t));
    }

    for (var future : results) {
      future.get();
    }
  }

  private static String getDbId(DatabaseSessionEmbedded db) {
    return db.getURL();
  }
}
