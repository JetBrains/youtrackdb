/*
 *
 *  *  Copyright YouTrackDB
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *
 *
 */

package com.jetbrains.youtrack.db.internal.core.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrack.db.api.DatabaseType;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 *
 */
public class FreezeAndDBRecordInsertAtomicityTest extends DbTestBase {

  private static final int THREADS = Runtime.getRuntime().availableProcessors() << 1;
  private static final int ITERATIONS = 100;

  private Random random;
  private ExecutorService executorService;
  private CountDownLatch countDownLatch;

  @Override
  protected DatabaseType calculateDbType() {
    return DatabaseType.PLOCAL;
  }

  @Before
  public void before() {
    final var seed = System.currentTimeMillis();
    System.out.println(
        FreezeAndDBRecordInsertAtomicityTest.class.getSimpleName() + " seed: " + seed);
    random = new Random(seed);

    session.getMetadata()
        .getSchema()
        .createClass("Person")
        .createProperty("name", PropertyType.STRING)
        .createIndex(SchemaClass.INDEX_TYPE.UNIQUE);

    executorService = Executors.newFixedThreadPool(THREADS);
    countDownLatch = new CountDownLatch(THREADS);
  }

  @After
  public void after() throws InterruptedException {
    executorService.shutdown();
    assertTrue(executorService.awaitTermination(5, TimeUnit.SECONDS));
  }

  @Test
  public void test() throws InterruptedException, ExecutionException {
    final Set<Future<?>> futures = new HashSet<Future<?>>();

    for (var i = 0; i < THREADS; ++i) {
      final var thread = i;

      futures.add(
          executorService.submit(
              () -> {
                try (final var session = openDatabase()) {
                  final var index =
                      session.getMetadata().getIndexManagerInternal()
                          .getIndex(session, "Person.name");
                  for (var i1 = 0; i1 < ITERATIONS; ++i1) {
                    switch (random.nextInt(2)) {
                      case 0:
                        var val = i1;
                        session.executeInTx(
                            transaction ->
                            {
                              session.newInstance("Person")
                                  .setProperty("name", "name-" + thread + "-" + val);
                            });
                        break;

                      case 1:
                        session.freeze();
                        try {
                          session.begin();
                          var entityIterator = session.browseClass("Person");
                          while (entityIterator.hasNext()) {
                            var entity = entityIterator.next();
                            try (var rids =
                                index.getRids(session, entity.getProperty("name"))) {
                              assertEquals(entity.getIdentity(), rids.findFirst().orElse(null));
                            }
                          }
                          session.commit();
                        } finally {
                          session.release();
                        }

                        break;
                    }
                  }
                } catch (RuntimeException | Error e) {
                  e.printStackTrace();
                  throw e;
                } finally {
                  countDownLatch.countDown();
                }
              }));
    }

    countDownLatch.await();

    for (var future : futures) {
      future.get(); // propagate exceptions, if there are any
    }
  }
}
