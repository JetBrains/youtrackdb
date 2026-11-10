package com.jetbrains.youtrackdb.benchmarks.pagelock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

public class WriterWiringTest {

  @Rule
  public TemporaryFolder folder = new TemporaryFolder();

  // Exercise generated harnesses, trial setup, real commits and teardown in six fresh JMH JVMs.
  // Small data is an explicit parameter override. Annotation thread counts must remain intact.
  // Fifteen minutes allows bounded seed creation and seven forks on slow Windows disks.
  @Test(timeout = 900000)
  public void sixForkedCellsReturnResultsAndMissingSeedFails() throws Exception {
    Path root = folder.getRoot().toPath();
    withChildCleanup(() -> {
      var prepare = new ProcessBuilder(javaCommand(root.resolve("seeds").toString()))
          .inheritIO().start();
      assertTrue("seed preparation timed out", prepare.waitFor(240, TimeUnit.SECONDS));
      assertEquals(0, prepare.exitValue());
      return null;
    });
    for (String cell : List.of("singleRecords1", "singleRecords4", "nearLimit1", "nearLimit4",
        "smallCache1", "smallCache4")) {
      long start = System.nanoTime();
      var results = withChildCleanup(
          () -> new Runner(options(root, cell, root.resolve("seeds"))).run());
      assertEquals(cell, 1, results.size());
      var result = results.iterator().next();
      assertEquals(cell.endsWith("4") ? 4 : 1, result.getParams().getThreads());
      System.out.println("WIRING " + cell + " elapsedMs="
          + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
    }
    assertThrows(RunnerException.class,
        () -> withChildCleanup(
            () -> new Runner(options(root, "nearLimit1", root.resolve("missing"))).run()));
  }

  // A normally returning runner must not leave a child behind.
  @Test(timeout = 30000)
  public void successfulRunReapsRemainingChild() throws Exception {
    var child = new ProcessBuilder(javaCommand("wait")).start();
    try {
      assertEquals("result", withChildCleanup(() -> {
        assertTrue(child.isAlive());
        return "result";
      }));
      assertFalse("child survived successful run cleanup", child.isAlive());
    } finally {
      child.destroyForcibly();
    }
  }

  // Model JUnit's deadline interruption while a runner fails. Cleanup must reap the child,
  // preserve the original failure and restore the interrupt flag for the caller.
  @Test(timeout = 30000)
  public void interruptedFailedRunReapsChildAndPreservesFailure() throws Exception {
    var child = new ProcessBuilder(javaCommand("wait")).start();
    var failure = new IllegalStateException("runner failed");
    try {
      assertSame(failure, assertThrows(IllegalStateException.class, () -> withChildCleanup(() -> {
        assertTrue(child.isAlive());
        Thread.currentThread().interrupt();
        throw failure;
      })));
      assertTrue("cleanup lost the interrupt", Thread.currentThread().isInterrupted());
      assertFalse("child survived interrupted run cleanup", child.isAlive());
    } finally {
      Thread.interrupted();
      child.destroyForcibly();
    }
  }

  private static <T> T withChildCleanup(Callable<T> action) throws Exception {
    try {
      return action.call();
    } finally {
      // Surefire gives this class a fresh JVM. Its descendants belong to seed preparation or
      // JMH. Runner interruption alone does not terminate a stalled fork.
      var children = ProcessHandle.current().descendants().toList();
      boolean interrupted = Thread.interrupted();
      try {
        children.forEach(ProcessHandle::destroy);
        interrupted |= awaitExit(children);
        children.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
        interrupted |= awaitExit(children);
        assertTrue("child processes survived cleanup: "
            + children.stream().filter(ProcessHandle::isAlive).map(ProcessHandle::pid).toList(),
            children.stream().noneMatch(ProcessHandle::isAlive));
      } finally {
        if (interrupted) {
          Thread.currentThread().interrupt();
        }
      }
    }
  }

  private static boolean awaitExit(List<ProcessHandle> children) {
    boolean interrupted = false;
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (children.stream().anyMatch(ProcessHandle::isAlive) && System.nanoTime() < deadline) {
      try {
        Thread.sleep(10);
      } catch (InterruptedException ignored) {
        // Finish the bounded reap even when JUnit has interrupted the runner.
        interrupted = true;
      }
    }
    return interrupted;
  }

  private static List<String> javaCommand(String argument) {
    var command = new ArrayList<String>();
    command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
        .filter(arg -> arg.startsWith("--add-")).forEach(command::add);
    command.add("-Dpagelock.expectUnavailable="
        + Boolean.getBoolean("pagelock.expectUnavailable"));
    command.add("-cp");
    command
        .add(System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")));
    command.add(WriterWiringTest.class.getName());
    command.add(argument);
    return command;
  }

  private static Options options(Path root, String cell, Path seeds) {
    return new OptionsBuilder()
        .include("^" + WriterBenchmark.class.getName().replace(".", "\\.") + "\\." + cell + "$")
        .forks(1).warmupForks(0).warmupIterations(1).measurementIterations(1)
        .warmupTime(TimeValue.milliseconds(100)).measurementTime(TimeValue.milliseconds(100))
        .timeout(TimeValue.minutes(2)).shouldFailOnError(true)
        .jvmArgsAppend("-Dpagelock.expectUnavailable="
            + Boolean.getBoolean("pagelock.expectUnavailable"))
        .param("seedRoot", seeds.toString()).param("runRoot", root.resolve("runs").toString())
        .param("bufferMiB", "80").param("writeCachePercent", "20").param("pageKiB", "8")
        .param("commitLimit", "17").param("pinPercent", "1").param("recordCount", "128")
        .build();
  }

  /** Subprocess entry point. The Surefire JVM never initializes the storage engine. */
  public static void main(String[] args) throws Exception {
    if ("wait".equals(args[0])) {
      Thread.sleep(Long.MAX_VALUE);
      return;
    }
    var settings = new WriterSettings(80, 20, 8, 17, 1);
    settings.apply(4, 16);
    Path root = Path.of(args[0]);
    for (int writers : new int[] {1, 4}) {
      RecordWorkload.prepare(RecordWorkload.seedRoot(root, 8), writers, 128);
      NearLimitWorkload.prepare(root, writers, settings);
    }
  }
}
