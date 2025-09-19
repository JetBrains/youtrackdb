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

package com.jetbrains.youtrackdb.internal.lucene.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.api.DatabaseSession;
import com.jetbrains.youtrackdb.api.common.SessionPool;
import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class LuceneInsertReadMultiThreadTest extends LuceneBaseTest {

  private static final int THREADS = 10;
  private static final int RTHREADS = 10;
  private static final int CYCLE = 100;

  @Before
  public void init() {

    Schema schema = session.getMetadata().getSchema();
    var oClass = schema.createClass("City");

    oClass.createProperty("name", PropertyType.STRING);
    session.execute("create index City.name on City (name) FULLTEXT ENGINE LUCENE");
  }

  @Test
  public void testConcurrentInsertWithIndex() throws Exception {

    var futures =
        IntStream.range(0, THREADS)
            .boxed()
            .map(i -> CompletableFuture.runAsync(new LuceneInsert(pool, CYCLE)))
            .collect(Collectors.toList());

    futures.addAll(
        IntStream.range(0, 1)
            .boxed()
            .map(i -> CompletableFuture.runAsync(new LuceneReader(pool, CYCLE)))
            .collect(Collectors.toList()));

    futures.forEach(cf -> cf.join());

    var db1 = (DatabaseSessionEmbedded) pool.acquire();
    db1.getMetadata().reload();
    var schema = db1.getMetadata().getSchema();

    var idx = schema.getClassInternal("City").getClassIndex(session, "City.name");

    db1.begin();
    Assert.assertEquals(idx.size(db1), THREADS * CYCLE);
    db1.commit();
  }

  public class LuceneInsert implements Runnable {

    private final SessionPool<DatabaseSession> pool;
    private final int cycle;
    private final int commitBuf;

    public LuceneInsert(SessionPool<DatabaseSession> pool, int cycle) {
      this.pool = pool;
      this.cycle = cycle;

      this.commitBuf = cycle / 10;
    }

    @Override
    public void run() {

      final var db = pool.acquire();
      var tx = db.begin();
      var i = 0;
      for (; i < cycle; i++) {
        var doc = tx.newEntity("City");

        doc.setProperty("name", "Rome");

        if (i % commitBuf == 0) {
          tx.commit();
          tx = db.begin();
        }
      }
      tx.commit();
      db.close();
    }
  }

  public class LuceneReader implements Runnable {

    private final int cycle;
    private final SessionPool<DatabaseSession> pool;

    public LuceneReader(SessionPool<DatabaseSession> pool, int cycle) {
      this.pool = pool;
      this.cycle = cycle;
    }

    @Override
    public void run() {

      final var db = (DatabaseSessionInternal) pool.acquire();
      db.activateOnCurrentThread();
      var schema = db.getMetadata().getSchema();
      schema.getClassInternal("City").getClassIndex(session, "City.name");

      for (var i = 0; i < cycle; i++) {

        var resultSet =
            db.query("select from City where SEARCH_FIELDS(['name'], 'Rome') =true ");

        if (resultSet.hasNext()) {
          assertThat(resultSet.next().asEntityOrNull().<String>getProperty("name"))
              .isEqualToIgnoringCase("rome");
        }
        resultSet.close();
      }
      db.close();
    }
  }
}
