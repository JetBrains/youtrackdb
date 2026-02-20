/*
 * JUnit 4 version of MultipleDBTest.
 * Original: tests/src/test/java/com/jetbrains/youtrackdb/auto/MultipleDBTest.java
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jetbrains.youtrackdb.auto.junit.databasesuite;

import com.jetbrains.youtrackdb.auto.junit.BaseDBTest;
import com.jetbrains.youtrackdb.auto.junit.BaseTest;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

/**
 *
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class MultipleDBTest extends BaseDBTest {

  int oldCollectionCount = 8;
  private static MultipleDBTest instance;

  @BeforeClass
  public static void setUpClass() throws Exception {
    instance = new MultipleDBTest();
    instance.beforeClass();
  }

  /**
   * Original: beforeClass (line 41) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/MultipleDBTest.java
   */
  @Override
  public void beforeClass() throws Exception {
    super.beforeClass();
    oldCollectionCount = GlobalConfiguration.CLASS_COLLECTIONS_COUNT.getValue();
    GlobalConfiguration.CLASS_COLLECTIONS_COUNT.setValue(1);
  }

  /**
   * Original: testObjectMultipleDBsThreaded (line 55) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/MultipleDBTest.java
   */
  @Test
  public void test01_ObjectMultipleDBsThreaded() throws Exception {
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
                    "RID was " + dummy.getIdentity(),
                    j, dummy.getIdentity().getCollectionPosition());
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

  /**
   * Original: testDocumentMultipleDBsThreaded (line 133) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/MultipleDBTest.java
   */
  @Test
  public void test02_DocumentMultipleDBsThreaded() throws Exception {
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
                    "RID was " + dummy.getIdentity(),
                    j, dummy.getIdentity().getCollectionPosition());
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

  /**
   * Original: getDbId (line 206) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/MultipleDBTest.java
   */
  private static String getDbId(DatabaseSessionInternal db) {
    return db.getURL();
  }
}
