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
package com.jetbrains.youtrack.db.auto;

import com.jetbrains.youtrack.db.internal.client.remote.StorageRemote;
import com.jetbrains.youtrack.db.internal.client.remote.db.DatabaseSessionRemote;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.testng.Assert;
import org.testng.annotations.Optional;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

/**
 *
 */
public class MultipleDBTest extends BaseDBTest {

  public MultipleDBTest() {
  }

  @Parameters(value = "remote")
  public MultipleDBTest(@Optional Boolean remote) {
    super(remote != null && remote);
  }

  @Test
  public void testObjectMultipleDBsThreaded() throws Exception {
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

                Assert.assertEquals(
                    dummy.getIdentity().getClusterPosition(), j, "RID was " + dummy.getIdentity());
              }
              var end = System.currentTimeMillis();

              var time =
                  "("
                      + getDbId(session)
                      + ") "
                      + "Executed operations (WRITE) in: "
                      + (end - start)
                      + " ms";
              // System.out.println(time);
              times.add(time);

              start = System.currentTimeMillis();
              for (var j = 0; j < operations_read; j++) {
                var l = session.query(" select * from DummyObject ").stream().toList();
                Assert.assertEquals(l.size(), operations_write);
              }
              end = System.currentTimeMillis();

              time =
                  "("
                      + getDbId(session)
                      + ") "
                      + "Executed operations (READ) in: "
                      + (end - start)
                      + " ms";
              // System.out.println(time);
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
  public void testDocumentMultipleDBsThreaded() throws Exception {
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

                Assert.assertEquals(
                    dummy.getIdentity().getClusterPosition(), j, "RID was " + dummy.getIdentity());
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
                Assert.assertEquals(l.size(), operations_write);
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

  private static String getDbId(DatabaseSessionInternal db) {
    if (db.getStorage() instanceof StorageRemote) {
      return db.getURL() + " - sessionId: " + ((StorageRemote) db.getStorage()).getSessionId(
          (DatabaseSessionRemote) db);
    } else {
      return db.getURL();
    }
  }
}
