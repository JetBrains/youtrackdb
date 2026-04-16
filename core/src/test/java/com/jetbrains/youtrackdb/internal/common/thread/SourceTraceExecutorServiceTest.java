package com.jetbrains.youtrackdb.internal.common.thread;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for {@link SourceTraceExecutorService}. Verifies that the decorator
 * wraps tasks with source trace capture, so exceptions thrown during async
 * execution include the submission-site stack trace.
 */
public class SourceTraceExecutorServiceTest {

  private ExecutorService delegate;
  private SourceTraceExecutorService traced;

  @Before
  public void setUp() {
    delegate = Executors.newSingleThreadExecutor();
    traced = new SourceTraceExecutorService(delegate);
  }

  @After
  public void tearDown() throws Exception {
    traced.shutdownNow();
    delegate.awaitTermination(5, TimeUnit.SECONDS);
  }

  // --- submit(Callable) ---

  /**
   * Verifies that a successful Callable returns its result normally.
   */
  @Test
  public void submitCallable_success_returnsResult() throws Exception {
    var future = traced.submit(() -> 42);
    assertThat(future.get(5, TimeUnit.SECONDS)).isEqualTo(42);
  }

  /**
   * Verifies that when a Callable throws a RuntimeException, it is wrapped
   * in a TracedExecutionException with the submission-site stack trace.
   */
  @Test
  public void submitCallable_runtimeException_wrappedWithTrace()
      throws Exception {
    var future = traced.submit(() -> {
      throw new RuntimeException("callable fail");
    });

    assertThatThrownBy(() -> future.get(5, TimeUnit.SECONDS))
        .isInstanceOf(ExecutionException.class)
        .hasCauseInstanceOf(TracedExecutionException.class)
        .satisfies(e -> {
          var traced = (TracedExecutionException) e.getCause();
          assertThat(traced.getMessage()).contains("Async task");
          assertThat(traced.getCause())
              .isInstanceOf(RuntimeException.class)
              .hasMessage("callable fail");
        });
  }

  // --- submit(Runnable, result) ---

  /**
   * Verifies that a successful Runnable returns the provided result.
   */
  @Test
  public void submitRunnableWithResult_success_returnsResult()
      throws Exception {
    var future = traced.submit(() -> {
      // no-op
    }, "hello");
    assertThat(future.get(5, TimeUnit.SECONDS)).isEqualTo("hello");
  }

  /**
   * Verifies that when a Runnable throws a RuntimeException in submit(Runnable, T),
   * it is wrapped in a TracedExecutionException.
   */
  @Test
  public void submitRunnableWithResult_runtimeException_wrappedWithTrace()
      throws Exception {
    var future = traced.submit((Runnable) () -> {
      throw new RuntimeException("runnable fail");
    }, "unused");

    assertThatThrownBy(() -> future.get(5, TimeUnit.SECONDS))
        .isInstanceOf(ExecutionException.class)
        .hasCauseInstanceOf(TracedExecutionException.class);
  }

  // --- submit(Runnable) ---

  /**
   * Verifies that a successful Runnable completes without exception.
   */
  @Test
  public void submitRunnable_success_completesNormally() throws Exception {
    var latch = new CountDownLatch(1);
    var future = traced.submit((Runnable) latch::countDown);
    future.get(5, TimeUnit.SECONDS);
    assertThat(latch.await(1, TimeUnit.SECONDS)).isTrue();
  }

  /**
   * Verifies that when a Runnable throws a RuntimeException in submit(Runnable),
   * it is wrapped in a TracedExecutionException.
   */
  @Test
  public void submitRunnable_runtimeException_wrappedWithTrace()
      throws Exception {
    var future = traced.submit((Runnable) () -> {
      throw new RuntimeException("submit-runnable fail");
    });

    assertThatThrownBy(() -> future.get(5, TimeUnit.SECONDS))
        .isInstanceOf(ExecutionException.class)
        .hasCauseInstanceOf(TracedExecutionException.class);
  }

  // --- execute(Runnable) ---

  /**
   * Verifies that a successful Runnable executes via execute().
   */
  @Test
  public void execute_success_runsNormally() throws Exception {
    var latch = new CountDownLatch(1);
    traced.execute(latch::countDown);
    assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
  }

  /**
   * Verifies that execute() wraps a failing Runnable's exception with trace.
   * The wrapped exception propagates to the thread's UncaughtExceptionHandler,
   * not to the caller.
   */
  @Test
  public void execute_runtimeException_wrappedWithTrace() throws Exception {
    var caughtException = new java.util.concurrent.atomic.AtomicReference<Throwable>();
    var latch = new CountDownLatch(1);

    // Create a delegate with a custom thread factory that captures the
    // uncaught exception.
    var customDelegate = Executors.newSingleThreadExecutor(r -> {
      var t = new Thread(r, "test-execute-trace");
      t.setUncaughtExceptionHandler((thread, ex) -> {
        caughtException.set(ex);
        latch.countDown();
      });
      return t;
    });
    var customTraced = new SourceTraceExecutorService(customDelegate);
    try {
      customTraced.execute(() -> {
        throw new RuntimeException("execute fail");
      });

      assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(caughtException.get())
          .isInstanceOf(TracedExecutionException.class);
      assertThat(caughtException.get().getCause())
          .isInstanceOf(RuntimeException.class)
          .hasMessage("execute fail");
    } finally {
      customDelegate.shutdownNow();
      customDelegate.awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  // --- Delegation methods ---

  /**
   * Verifies shutdown() delegates to the wrapped service.
   */
  @Test
  public void shutdown_delegatesToWrappedService() {
    traced.shutdown();
    assertThat(delegate.isShutdown()).isTrue();
  }

  /**
   * Verifies shutdownNow() delegates to the wrapped service.
   */
  @Test
  public void shutdownNow_delegatesToWrappedService() {
    traced.shutdownNow();
    assertThat(delegate.isShutdown()).isTrue();
  }

  /**
   * Verifies isShutdown() reflects the delegate state.
   */
  @Test
  public void isShutdown_reflectsDelegateState() {
    assertThat(traced.isShutdown()).isFalse();
    delegate.shutdown();
    assertThat(traced.isShutdown()).isTrue();
  }

  /**
   * Verifies isTerminated() reflects the delegate state.
   */
  @Test
  public void isTerminated_reflectsDelegateState() throws Exception {
    assertThat(traced.isTerminated()).isFalse();
    delegate.shutdown();
    delegate.awaitTermination(5, TimeUnit.SECONDS);
    assertThat(traced.isTerminated()).isTrue();
  }

  /**
   * Verifies awaitTermination() delegates correctly.
   */
  @Test
  public void awaitTermination_delegatesToWrappedService() throws Exception {
    delegate.shutdown();
    assertThat(traced.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
  }

  /**
   * Verifies the constructor rejects null services.
   */
  @Test
  public void constructor_nullService_throwsNPE() {
    assertThatThrownBy(() -> new SourceTraceExecutorService(null))
        .isInstanceOf(NullPointerException.class);
  }

  // --- invokeAll / invokeAny ---

  /**
   * Verifies invokeAll delegates to the wrapped service.
   */
  @Test
  public void invokeAll_delegatesToWrappedService() throws Exception {
    var results = traced.invokeAll(
        java.util.List.of(() -> 1, () -> 2, () -> 3));
    assertThat(results).hasSize(3);
    assertThat(results.get(0).get()).isEqualTo(1);
    assertThat(results.get(1).get()).isEqualTo(2);
    assertThat(results.get(2).get()).isEqualTo(3);
  }

  /**
   * Verifies invokeAll with timeout delegates to the wrapped service.
   */
  @Test
  public void invokeAllWithTimeout_delegatesToWrappedService()
      throws Exception {
    var results = traced.invokeAll(
        java.util.List.of(() -> 10, () -> 20),
        5, TimeUnit.SECONDS);
    assertThat(results).hasSize(2);
    assertThat(results.get(0).get()).isEqualTo(10);
  }

  /**
   * Verifies invokeAny delegates to the wrapped service and returns a result.
   */
  @Test
  public void invokeAny_delegatesToWrappedService() throws Exception {
    var result = traced.invokeAny(
        java.util.List.of(() -> 99));
    assertThat(result).isEqualTo(99);
  }

  /**
   * Verifies invokeAny with timeout delegates to the wrapped service.
   */
  @Test
  public void invokeAnyWithTimeout_delegatesToWrappedService()
      throws Exception {
    var result = traced.invokeAny(
        java.util.List.of(() -> 77),
        5, TimeUnit.SECONDS);
    assertThat(result).isEqualTo(77);
  }
}
