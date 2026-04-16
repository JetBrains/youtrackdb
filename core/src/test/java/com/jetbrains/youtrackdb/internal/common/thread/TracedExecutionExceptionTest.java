package com.jetbrains.youtrackdb.internal.common.thread;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

/**
 * Tests for {@link TracedExecutionException}. Verifies the two-phase trace
 * pattern: prepareTrace() captures the stack at submission time, trace()
 * attaches the actual cause at execution time.
 */
public class TracedExecutionExceptionTest {

  /**
   * Verifies prepareTrace creates an exception with the task's class name
   * in the message and a filled-in stack trace.
   */
  @Test
  public void prepareTrace_capturesStackWithTaskName() {
    Runnable task = () -> {
    };
    var trace = TracedExecutionException.prepareTrace(task);

    assertThat(trace).isNotNull();
    assertThat(trace.getMessage()).contains("Async task");
    // The task is an anonymous lambda, so the class name will be
    // something like TracedExecutionExceptionTest$$Lambda
    assertThat(trace.getMessage()).contains("[");
    assertThat(trace.getMessage()).contains("]");
    assertThat(trace.getStackTrace()).isNotEmpty();
    assertThat(trace.getCause()).isNull();
  }

  /**
   * Verifies prepareTrace with null task uses "?" as the name.
   */
  @Test
  public void prepareTrace_nullTask_usesQuestionMark() {
    var trace = TracedExecutionException.prepareTrace(null);

    assertThat(trace.getMessage()).contains("[?]");
  }

  /**
   * Verifies trace() attaches the cause to a prepared trace exception.
   */
  @Test
  public void trace_attachesCauseToPreparedTrace() {
    var task = new Runnable() {
      @Override
      public void run() {
      }
    };
    var trace = TracedExecutionException.prepareTrace(task);
    var cause = new RuntimeException("original cause");

    var result = TracedExecutionException.trace(trace, cause, task);

    assertThat(result).isSameAs(trace);
    assertThat(result.getCause()).isSameAs(cause);
  }

  /**
   * Verifies that when trace is null, trace() creates a new exception with
   * the cause attached.
   */
  @Test
  public void trace_nullTrace_createsNewException() {
    Runnable task = () -> {
    };
    var cause = new RuntimeException("new cause");

    var result = TracedExecutionException.trace(null, cause, task);

    assertThat(result).isNotNull();
    assertThat(result.getMessage()).contains("Async task");
    assertThat(result.getCause()).isSameAs(cause);
  }

  /**
   * Verifies that when trace is null and task is null, trace() creates
   * a new exception with "?" task name.
   */
  @Test
  public void trace_nullTraceAndTask_usesQuestionMark() {
    var cause = new RuntimeException("cause");
    var result = TracedExecutionException.trace(null, cause, null);

    assertThat(result.getMessage()).contains("[?]");
    assertThat(result.getCause()).isSameAs(cause);
  }

  /**
   * Verifies the message constructor works.
   */
  @Test
  public void messageConstructor_setsMessage() {
    var ex = new TracedExecutionException("test message");
    assertThat(ex.getMessage()).isEqualTo("test message");
    assertThat(ex.getCause()).isNull();
  }

  /**
   * Verifies the message+cause constructor works.
   */
  @Test
  public void messageAndCauseConstructor_setsBoth() {
    var cause = new RuntimeException("root");
    var ex = new TracedExecutionException("wrapped", cause);
    assertThat(ex.getMessage()).isEqualTo("wrapped");
    assertThat(ex.getCause()).isSameAs(cause);
  }

  /**
   * Verifies prepareTrace with a named inner class uses the simple class name.
   */
  @Test
  public void prepareTrace_namedClass_usesSimpleName() {
    class MyTask implements Runnable {
      @Override
      public void run() {
      }
    }

    var trace = TracedExecutionException.prepareTrace(new MyTask());
    assertThat(trace.getMessage()).contains("[MyTask]");
  }
}
