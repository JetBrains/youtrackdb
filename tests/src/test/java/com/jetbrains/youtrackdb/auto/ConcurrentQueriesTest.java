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
package com.jetbrains.youtrackdb.auto;

import com.jetbrains.youtrackdb.internal.common.concur.NeedRetryException;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.test.ConcurrentTestHelper;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

@Test
public class ConcurrentQueriesTest extends BaseDBTest {

  private static final int THREADS = 10;
  private static final int CYCLES = 50;
  private static final int MAX_RETRIES = 50;

  private final AtomicLong counter = new AtomicLong();
  private final AtomicLong totalRetries = new AtomicLong();

  class CommandExecutor implements Callable<Void> {

    @Override
    public Void call() {
      for (var i = 0; i < CYCLES; i++) {
        try (var db = acquireSession()) {
          for (var retry = 0; retry < MAX_RETRIES; ++retry) {
            try {
              db.executeInTx(transaction -> {
                transaction.execute("select from Concurrent").close();
              });

              counter.incrementAndGet();
              totalRetries.addAndGet(retry);
              break;
            } catch (NeedRetryException e) {
              try {
                Thread.sleep(retry * 10);
              } catch (InterruptedException e1) {
                throw new RuntimeException(e1);
              }
            }
          }
        }
      }
      return null;
    }
  }

  @BeforeClass
  public void init() {
    if (session.getMetadata().getSchema().existsClass("Concurrent")) {
      session.getMetadata().getSchema().dropClass("Concurrent");
    }

    session.getMetadata().getSchema().createClass("Concurrent");

    for (var i = 0; i < 1000; ++i) {
      session.begin();
      EntityImpl entity = session.newInstance("Concurrent");
      entity.setProperty("test", i);

      session.commit();
    }
  }

  @Test
  public void concurrentCommands() {
    ConcurrentTestHelper.test(THREADS, CommandExecutor::new);
    Assert.assertEquals(counter.get(), CYCLES * THREADS);
  }
}
