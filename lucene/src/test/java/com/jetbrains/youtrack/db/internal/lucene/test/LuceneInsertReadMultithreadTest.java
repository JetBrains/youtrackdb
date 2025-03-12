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

package com.jetbrains.youtrack.db.internal.lucene.test;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 *
 */
public class LuceneInsertReadMultithreadTest extends BaseLuceneTest {

  private static final int THREADS = 10;
  private static final int RTHREADS = 1;
  private static final int CYCLE = 100;

  protected String url = "";

  @Before
  public void init() {

    url = session.getURL();
    Schema schema = session.getMetadata().getSchema();
    var oClass = schema.createClass("City");

    oClass.createProperty("name", PropertyType.STRING);
    session.command("create index City.name on City (name) FULLTEXT ENGINE LUCENE").close();
  }

  @Test
  public void testConcurrentInsertWithIndex() throws Exception {

    session.getMetadata().reload();
    Schema schema = session.getMetadata().getSchema();

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

    System.out.println(
        "Started LuceneInsertReadMultithreadBaseTest test, waiting for "
            + threads.length
            + " threads to complete...");

    for (var i = 0; i < THREADS + RTHREADS; ++i) {
      threads[i].join();
    }

    System.out.println("LuceneInsertReadMultithreadBaseTest all threads completed");

    var idx = session.getClassInternal("City").getClassIndex(session, "City.name");

    session.begin();
    Assert.assertEquals(idx.getInternal().size(session), THREADS * CYCLE);
    session.commit();
  }

  public class LuceneInsertThread implements Runnable {

    private DatabaseSession db;
    private int cycle = 0;
    private final int commitBuf = 500;

    public LuceneInsertThread(int cycle) {
      this.cycle = cycle;
    }

    @Override
    public void run() {

      db = openDatabase();

      db.begin();
      for (var i = 0; i < cycle; i++) {
        var doc = ((EntityImpl) db.newEntity("City"));

        doc.field("name", "Rome");

        if (i % commitBuf == 0) {
          db.commit();
          db.begin();
        }
      }
      db.commit();

      db.close();
    }
  }

  public class LuceneReadThread implements Runnable {

    private final int cycle;
    private DatabaseSessionInternal databaseDocumentTx;

    public LuceneReadThread(int cycle) {
      this.cycle = cycle;
    }

    @Override
    public void run() {

      databaseDocumentTx = openDatabase();

      Schema schema = databaseDocumentTx.getMetadata().getSchema();
      var idx = databaseDocumentTx.getClassInternal("City").getClassIndex(session, "City.name");

      for (var i = 0; i < cycle; i++) {

        databaseDocumentTx.query("select from city where name LUCENE 'Rome'");
      }
    }
  }
}
