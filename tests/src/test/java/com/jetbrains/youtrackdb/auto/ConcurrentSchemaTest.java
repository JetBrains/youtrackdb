/*
 *
 *
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
package com.jetbrains.youtrackdb.auto;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.test.ConcurrentTestHelper;
import com.jetbrains.youtrackdb.internal.test.TestFactory;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;
import org.testng.Assert;
import org.testng.annotations.Test;

@Test
public class ConcurrentSchemaTest extends BaseDBTest {

  private static final int THREADS = 10;
  private static final int CYCLES = 50;

  private final AtomicLong createClassThreadCounter = new AtomicLong();
  private final AtomicLong dropClassThreadCounter = new AtomicLong();
  private final AtomicLong counter = new AtomicLong();

  class CreateClassCommandExecutor implements Callable<Void> {

    long id;

    @Override
    public Void call() {
      this.id = createClassThreadCounter.getAndIncrement();
      for (var i = 0; i < CYCLES; i++) {
        DatabaseSessionEmbedded db = acquireSession();
        try {
          final var clsName = "ConcurrentClassTest-" + id + "-" + i;

          var cls = ConcurrentSchemaTest.this.session.getMetadata().getSchema()
              .createClass(clsName);

          Assert.assertEquals(cls.getName(), clsName);
          Assert.assertTrue(
              ConcurrentSchemaTest.this.session.getMetadata().getSchema().existsClass(clsName));

          db.executeInTx(transaction -> {
            transaction.execute("select from " + clsName).close();
          });

          counter.incrementAndGet();
        } finally {
          db.close();
        }
      }
      return null;
    }
  }

  class DropClassCommandExecutor implements Callable<Void> {

    long id;

    public DropClassCommandExecutor() {
    }

    @Override
    public Void call() {
      this.id = dropClassThreadCounter.getAndIncrement();
      for (var i = 0; i < CYCLES; i++) {
        DatabaseSessionEmbedded db = acquireSession();
        try {
          final var clsName = "ConcurrentClassTest-" + id + "-" + i;

          Assert.assertTrue(
              ConcurrentSchemaTest.this.session.getMetadata().getSchema().existsClass(clsName));
          ConcurrentSchemaTest.this.session.getMetadata().getSchema().dropClass(clsName);
          Assert.assertFalse(
              ConcurrentSchemaTest.this.session.getMetadata().getSchema().existsClass(clsName));

          counter.decrementAndGet();
        } finally {
          db.close();
        }
      }
      return null;
    }
  }

  @Test
  public void concurrentCommands() throws Exception {
    ConcurrentTestHelper.test(
        THREADS,
        new TestFactory<Void>() {
          @Override
          public Callable<Void> createWorker() {
            return new CreateClassCommandExecutor();
          }
        });

    //    System.out.println("Create classes, checking...");

    for (var id = 0; id < THREADS; ++id) {
      for (var i = 0; i < CYCLES; ++i) {
        final var clsName = "ConcurrentClassTest-" + id + "-" + i;
        Assert.assertTrue(session.getMetadata().getSchema().existsClass(clsName));
      }
    }

    //    System.out.println("Dropping classes, spanning " + THREADS + " threads...");

    ConcurrentTestHelper.test(
        THREADS,
        new TestFactory<Void>() {
          @Override
          public Callable<Void> createWorker() {
            return new DropClassCommandExecutor();
          }
        });

    //    System.out.println("Done!");

    Assert.assertEquals(counter.get(), 0);
  }
}
