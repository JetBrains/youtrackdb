package com.jetbrains.youtrackdb.internal.core.sql.executor.resultset;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.common.thread.SoftThread;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.exception.CommandInterruptedException;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/**
 * Direct coverage for {@link InterruptResultSet}. The wrapper inspects
 * {@link com.jetbrains.youtrackdb.internal.core.db.ExecutionThreadLocal#isInterruptCurrentOperation()}
 * on every hasNext/next call and throws {@link CommandInterruptedException} when the current
 * thread is a {@link SoftThread} whose shutdown flag has been set.
 *
 * <p>JUnit test threads are plain {@link Thread}s, so the default path delegates to the source
 * without throwing. To exercise the interrupt branch we run the interaction inside a test-only
 * {@link SoftThread} subclass and flip the flag via {@code softShutdown()} before invoking
 * hasNext/next.
 *
 * <p>The test extends {@link DbTestBase} because {@link CommandInterruptedException} needs a
 * database session in its constructor; {@code ctx.getDatabaseSession()} would otherwise throw
 * before the exception can propagate.
 */
public class InterruptResultSetTest extends DbTestBase {

  private BasicCommandContext newContext() {
    return new BasicCommandContext(session);
  }

  private ExecutionStream streamOfInts(int... values) {
    var list = new ArrayList<Result>(values.length);
    for (var v : values) {
      var r = new ResultInternal(session);
      r.setProperty("value", v);
      list.add(r);
    }
    return ExecutionStream.resultIterator(list.iterator());
  }

  /**
   * On a plain Thread the interrupt check is a no-op: hasNext/next/close delegate straight
   * to the source.
   */
  @Test
  public void normalThreadDelegatesWithoutInterrupt() {
    var ctx = newContext();
    var stream = new InterruptResultSet(streamOfInts(1, 2));

    var drained = new ArrayList<Integer>();
    while (stream.hasNext(ctx)) {
      drained.add(stream.next(ctx).<Integer>getProperty("value"));
    }
    stream.close(ctx);
    assertThat(drained).containsExactly(1, 2);
  }

  /**
   * On a {@link SoftThread} whose shutdown flag is clear, the wrapper still delegates
   * transparently; the flag is not a single-threaded tripwire. We don't read property
   * values on the worker thread (the session isn't activated there); we only check that
   * next() returns a non-null Result, which proves the delegate was reached.
   */
  @Test
  public void softThreadWithoutShutdownFlagDelegates() throws Exception {
    var ctx = newContext();
    var reached = new boolean[1];
    var source = new ExecutionStream() {
      @Override
      public boolean hasNext(CommandContext c) {
        reached[0] = true;
        return true;
      }

      @Override
      public Result next(CommandContext c) {
        reached[0] = true;
        return new ResultInternal(null);
      }

      @Override
      public void close(CommandContext c) {
      }
    };
    var stream = new InterruptResultSet(source);
    var captured = new AtomicReference<Object>();
    var done = new CountDownLatch(1);

    var thread = new RunOnceSoftThread("interrupt-test-ok", () -> {
      try {
        stream.next(ctx);
        captured.set("ok");
      } catch (Throwable t) {
        captured.set(t);
      } finally {
        done.countDown();
      }
    });

    thread.start();
    assertThat(done.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    thread.join(1_000);

    assertThat(captured.get()).isEqualTo("ok");
    assertThat(reached[0]).isTrue();
  }

  /**
   * On a {@link SoftThread} with its shutdown flag set, {@link InterruptResultSet#hasNext}
   * throws {@link CommandInterruptedException} with the documented message. The underlying
   * source is never consulted.
   */
  @Test
  public void hasNextOnInterruptedSoftThreadThrowsCommandInterrupted() throws Exception {
    var ctx = newContext();
    var consulted = new boolean[1];
    var source = new ExecutionStream() {
      @Override
      public boolean hasNext(CommandContext c) {
        consulted[0] = true;
        return true;
      }

      @Override
      public Result next(CommandContext c) {
        consulted[0] = true;
        return new ResultInternal(session);
      }

      @Override
      public void close(CommandContext c) {
      }
    };
    var wrapped = new InterruptResultSet(source);

    var thrown = new AtomicReference<Throwable>();
    var done = new CountDownLatch(1);

    var thread = new RunOnceSoftThread("interrupt-test-hasnext", () -> {
      // Trip the shutdown flag before calling the wrapper.
      ((SoftThread) Thread.currentThread()).softShutdown();
      try {
        wrapped.hasNext(ctx);
      } catch (Throwable t) {
        thrown.set(t);
      } finally {
        done.countDown();
      }
    });

    thread.start();
    assertThat(done.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    thread.join(1_000);

    assertThat(thrown.get()).isInstanceOf(CommandInterruptedException.class);
    assertThat(((CommandInterruptedException) thrown.get()).getMessage())
        .contains("interrupted");
    assertThat(consulted[0]).isFalse();
  }

  /**
   * Symmetric to the hasNext case: {@link InterruptResultSet#next} throws without consulting
   * the source when the shutdown flag is set on the current SoftThread.
   */
  @Test
  public void nextOnInterruptedSoftThreadThrowsCommandInterrupted() throws Exception {
    var ctx = newContext();
    var consulted = new boolean[1];
    var source = new ExecutionStream() {
      @Override
      public boolean hasNext(CommandContext c) {
        return true;
      }

      @Override
      public Result next(CommandContext c) {
        consulted[0] = true;
        return new ResultInternal(session);
      }

      @Override
      public void close(CommandContext c) {
      }
    };
    var wrapped = new InterruptResultSet(source);

    var thrown = new AtomicReference<Throwable>();
    var done = new CountDownLatch(1);

    var thread = new RunOnceSoftThread("interrupt-test-next", () -> {
      ((SoftThread) Thread.currentThread()).softShutdown();
      try {
        wrapped.next(ctx);
      } catch (Throwable t) {
        thrown.set(t);
      } finally {
        done.countDown();
      }
    });

    thread.start();
    assertThat(done.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    thread.join(1_000);

    assertThat(thrown.get()).isInstanceOf(CommandInterruptedException.class);
    assertThat(consulted[0]).isFalse();
  }

  /**
   * Closing an InterruptResultSet delegates to the source unconditionally — the interrupt
   * check does not apply to close.
   */
  @Test
  public void closeAlwaysDelegatesToSource() {
    var ctx = newContext();
    var closed = new boolean[1];
    var source = new ExecutionStream() {
      @Override
      public boolean hasNext(CommandContext c) {
        return false;
      }

      @Override
      public Result next(CommandContext c) {
        throw new IllegalStateException();
      }

      @Override
      public void close(CommandContext c) {
        closed[0] = true;
      }
    };
    new InterruptResultSet(source).close(ctx);
    assertThat(closed[0]).isTrue();
  }

  /**
   * A {@link SoftThread} that runs a single {@link Runnable} once and exits. Unlike the
   * production SoftThread.run() loop, this override avoids re-invoking execute in a
   * spin loop so tests terminate deterministically. We cannot override the final
   * {@code run()} — but we can make {@code execute()} set the shutdown flag itself after
   * running once, which breaks the loop at the top of the next iteration.
   */
  private static final class RunOnceSoftThread extends SoftThread {
    private final Runnable action;
    private boolean alreadyRan;

    RunOnceSoftThread(String name, Runnable action) {
      super(name);
      this.action = action;
    }

    @Override
    protected void execute() {
      if (alreadyRan) {
        // Defensive: SoftThread.run loop invokes execute again before checking the
        // shutdown flag, so we may re-enter here once — guard and yield out.
        sendShutdown();
        return;
      }
      alreadyRan = true;
      try {
        action.run();
      } finally {
        sendShutdown();
      }
    }
  }
}
