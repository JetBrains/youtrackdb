/*
 * JUnit 4 version of ConcurrentQueriesTest.
 * Original: tests/src/test/java/com/jetbrains/youtrackdb/auto/ConcurrentQueriesTest.java
 */
package com.jetbrains.youtrackdb.auto.junit.databasesuite;

import com.jetbrains.youtrackdb.auto.junit.BaseDBTest;
import com.jetbrains.youtrackdb.auto.junit.BaseTest;

import com.jetbrains.youtrackdb.internal.common.concur.NeedRetryException;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSession;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.test.ConcurrentTestHelper;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

/**
 * JUnit 4 version of ConcurrentQueriesTest. Original:
 * tests/src/test/java/com/jetbrains/youtrackdb/auto/ConcurrentQueriesTest.java
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class ConcurrentQueriesTest extends BaseDBTest {

  private static final int THREADS = 10;
  private static final int CYCLES = 50;
  private static final int MAX_RETRIES = 50;
  private static ConcurrentQueriesTest instance;

  @BeforeClass
  public static void setUpClass() throws Exception {
    instance = new ConcurrentQueriesTest();
    instance.beforeClass();
    instance.init();
  }

  /**
   * Original: concurrentCommands (line 85) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/ConcurrentQueriesTest.java
   */
  @Test
  public void test01_ConcurrentCommands() {
    ConcurrentTestHelper.test(THREADS, CommandExecutor::new);
    Assert.assertEquals(counter.get(), CYCLES * THREADS);
  }

  // Helper methods from original
  private final AtomicLong counter = new AtomicLong();
  private final AtomicLong totalRetries = new AtomicLong();

  class CommandExecutor implements Callable<Void> {

    public Void call() {
      for (var i = 0; i < CYCLES; i++) {
        try (DatabaseSession db = acquireSession()) {
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

  public void concurrentCommands() {
    ConcurrentTestHelper.test(THREADS, CommandExecutor::new);
    Assert.assertEquals(counter.get(), CYCLES * THREADS);
  }
}

