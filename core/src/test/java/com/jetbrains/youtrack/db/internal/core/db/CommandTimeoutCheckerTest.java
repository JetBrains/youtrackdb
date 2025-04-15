package com.jetbrains.youtrack.db.internal.core.db;

import static org.junit.Assert.assertTrue;

import java.util.Optional;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public class CommandTimeoutCheckerTest implements SchedulerInternal {

  private final Timer timer = new Timer();

  @Override
  public void schedule(TimerTask task, long delay, long period) {
    timer.scheduleAtFixedRate(task, delay, period);
  }

  @Override
  public void scheduleOnce(TimerTask task, long delay) {
    throw new UnsupportedOperationException();
  }

  @Test
  public void testTimeout() throws InterruptedException {
    var checker = new CommandTimeoutChecker(100, this);
    var latch = new CountDownLatch(10);
    for (var i = 0; i < 10; i++) {
      new Thread(
          () -> {
            checker.startCommand(Optional.empty());
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
            checker.startCommand(Optional.empty());
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
