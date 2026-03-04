package com.jetbrains.youtrackdb.internal.core.db;

import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public class CommandTimeoutCheckerTest implements SchedulerInternal {

  private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

  @Override
  public ScheduledFuture<?> schedule(Runnable task, long delay, long period) {
    return scheduler.scheduleWithFixedDelay(task, delay, period, TimeUnit.MILLISECONDS);
  }

  @Override
  public ScheduledFuture<?> scheduleOnce(Runnable task, long delay) {
    return scheduler.schedule(task, delay, TimeUnit.MILLISECONDS);
  }

  @Test
  public void testTimeout() throws InterruptedException {
    var checker = new CommandTimeoutChecker(100, this);
    var latch = new CountDownLatch(10);
    for (var i = 0; i < 10; i++) {
      new Thread(
          () -> {
            checker.startCommand(null);
            try {
              Thread.sleep(1000);
            } catch (InterruptedException e) {
              latch.countDown();
            }
            checker.endCommand();
          })
          .start();
    }

    assertTrue(latch.await(2, TimeUnit.SECONDS));
    checker.close();
  }

  @Test
  public void testNoTimeout() throws InterruptedException {
    var checker = new CommandTimeoutChecker(1000, this);
    var latch = new CountDownLatch(10);
    for (var i = 0; i < 10; i++) {
      new Thread(
          () -> {
            checker.startCommand(null);
            try {
              Thread.sleep(100);
            } catch (InterruptedException e) {
              throw new RuntimeException(e);
            }
            latch.countDown();
            checker.endCommand();
          })
          .start();
    }

    assertTrue(latch.await(2, TimeUnit.SECONDS));
    checker.close();
  }
}
