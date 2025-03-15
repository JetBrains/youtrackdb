/*
 *
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
package com.jetbrains.youtrack.db.auto;

import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.common.concur.NeedRetryException;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import java.util.concurrent.atomic.AtomicLong;
import org.testng.Assert;
import org.testng.annotations.Optional;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

@Test
public class ConcurrentUpdatesTest extends BaseDBTest {

  private static final int OPTIMISTIC_CYCLES = 100;
  private static final int PESSIMISTIC_CYCLES = 100;
  private static final int THREADS = 10;

  private final AtomicLong counter = new AtomicLong();
  private final AtomicLong totalRetries = new AtomicLong();

  @Parameters(value = "remote")
  public ConcurrentUpdatesTest(@Optional Boolean remote) {
    super(remote != null && remote);
  }

  class OptimisticUpdateField implements Runnable {

    RID rid1;
    RID rid2;
    String fieldValue = null;
    String threadName;

    public OptimisticUpdateField(RID iRid1, RID iRid2, String iThreadName) {
      super();
      rid1 = iRid1;
      rid2 = iRid2;
      threadName = iThreadName;
    }

    @Override
    public void run() {
      try {
        var db = acquireSession();
        for (var i = 0; i < OPTIMISTIC_CYCLES; i++) {
          var retries = 0;
          while (true) {
            retries++;
            try {
              db.begin();

              EntityImpl vDoc1 = db.load(rid1);
              Object iPropertyValue1 = vDoc1.getProperty(threadName) + ";" + i;
              vDoc1.setProperty(threadName, iPropertyValue1);

              EntityImpl vDoc2 = db.load(rid2);
              Object iPropertyValue = vDoc2.getProperty(threadName) + ";" + i;
              vDoc2.setProperty(threadName, iPropertyValue);

              db.commit();

              counter.incrementAndGet();
              totalRetries.addAndGet(retries);
              break;
            } catch (NeedRetryException e) {
              Thread.sleep(retries * 10L);
            }
          }
          fieldValue += ";" + i;
        }

      } catch (Throwable e) {
        throw new IllegalStateException(e);
      }
    }
  }

  class PessimisticUpdate implements Runnable {

    RID rid;
    String threadName;
    boolean lock;

    public PessimisticUpdate(RID iRid, String iThreadName, boolean iLock) {
      super();

      rid = iRid;
      threadName = iThreadName;
      lock = iLock;
    }

    @Override
    public void run() {
      try {
        var db = acquireSession();

        for (var i = 0; i < PESSIMISTIC_CYCLES; i++) {
          var cmd = "update " + rid + " set total = total + 1";
          if (lock) {
            cmd += " lock record";
          }

          var retries = 0;
          while (true) {
            try {
              retries++;
              db.begin();
              db.command(cmd).close();
              db.commit();
              counter.incrementAndGet();

              if (retries % 10 == 0) {
                System.out.println(retries + " retries for thread " + threadName);
              }

              break;

            } catch (NeedRetryException e) {
              if (lock) {
                Assert.fail(NeedRetryException.class.getSimpleName() + " was encountered");
              }
            }
          }
        }
      } catch (RuntimeException e) {
        e.printStackTrace();
        throw e;
      }
    }
  }

  @Test
  public void concurrentOptimisticUpdates() throws Exception {
    counter.set(0);

    var database = acquireSession();

    EntityImpl doc1 = database.newInstance();
    doc1.setProperty("INIT", "ok");
    database.begin();
    database.commit();

    RID rid1 = doc1.getIdentity();

    EntityImpl doc2 = database.newInstance();
    doc2.setProperty("INIT", "ok");

    database.begin();
    database.commit();

    RID rid2 = doc2.getIdentity();

    var ops = new OptimisticUpdateField[THREADS];
    for (var i = 0; i < THREADS; ++i) {
      ops[i] = new OptimisticUpdateField(rid1, rid2, "thread" + i);
    }

    var threads = new Thread[THREADS];
    for (var i = 0; i < THREADS; ++i) {
      threads[i] = new Thread(ops[i], "ConcurrentTest" + i);
    }

    for (var i = 0; i < THREADS; ++i) {
      threads[i].start();
    }

    for (var i = 0; i < THREADS; ++i) {
      threads[i].join();
    }

    Assert.assertEquals(counter.get(), OPTIMISTIC_CYCLES * THREADS);

    doc1 = database.load(rid1);

    for (var i = 0; i < THREADS; ++i) {
      Assert.assertEquals(doc1.getProperty(ops[i].threadName), ops[i].fieldValue,
          ops[i].threadName);
    }

    doc1.toJSON();

    doc2 = database.load(rid2);

    for (var i = 0; i < THREADS; ++i) {
      Assert.assertEquals(doc2.getProperty(ops[i].threadName), ops[i].fieldValue,
          ops[i].threadName);
    }

    doc2.toJSON();
    System.out.println(doc2.toJSON());

    database.close();
  }

  @Test(enabled = false)
  public void concurrentPessimisticSQLUpdates() throws Exception {
    sqlUpdate(true);
  }

  @Test
  public void concurrentOptimisticSQLUpdates() throws Exception {
    sqlUpdate(false);
  }

  protected void sqlUpdate(boolean lock) throws InterruptedException {
    counter.set(0);

    var database = acquireSession();
    EntityImpl doc1 = database.newInstance();
    doc1.setProperty("total", 0);

    database.begin();
    database.commit();

    RID rid1 = doc1.getIdentity();

    var ops = new PessimisticUpdate[THREADS];
    for (var i = 0; i < THREADS; ++i) {
      ops[i] = new PessimisticUpdate(rid1, "thread" + i, lock);
    }

    var threads = new Thread[THREADS];
    for (var i = 0; i < THREADS; ++i) {
      threads[i] = new Thread(ops[i], "ConcurrentTest" + i);
    }

    for (var i = 0; i < THREADS; ++i) {
      threads[i].start();
    }

    for (var i = 0; i < THREADS; ++i) {
      threads[i].join();
    }

    Assert.assertEquals(counter.get(), PESSIMISTIC_CYCLES * THREADS);

    doc1 = database.load(rid1);
    Assert.assertEquals(doc1.<Object>getProperty("total"), PESSIMISTIC_CYCLES * THREADS);

    database.close();
  }
}
