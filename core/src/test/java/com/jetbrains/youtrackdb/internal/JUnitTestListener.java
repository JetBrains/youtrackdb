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
 */

package com.jetbrains.youtrackdb.internal;

import com.jetbrains.youtrackdb.internal.common.directmemory.ByteBufferPool;
import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.core.YouTrackDBEnginesManager;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.junit.runner.Description;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunListener;

///   - Listens for JUnit test run started and prohibits logging of exceptions on storage level.
///   - Listens for the JUnit test run finishing and runs the direct memory leaks detector, if no
///     tests failed. If leak detector finds some leaks, it triggers [AssertionError] and the
///     build is marked as failed. Java assertions (-ea) must be active for this to work.
///   - Triggers [AssertionError] if [LogManager] is shutdown before test is finished.
///     We may miss some errors because [LogManager] is shutdown
///   - Runs a watchdog thread that detects deadlocked or stuck tests. If a test exceeds the
///     configured timeout, diagnostics are dumped and the JVM is terminated. The timeout is
///     configurable via `-Dyoutrackdb.test.deadlock.timeout.minutes` (default: 15).
///   - Detects inactivity gaps (e.g. during @After teardown or between test methods) when no
///     test is tracked as running. Uses `Runtime.halt(1)` instead of `System.exit(1)` to avoid
///     deadlocking in shutdown hooks when threads hold locks that would never be released.
public class JUnitTestListener extends RunListener {

  private static final long DEFAULT_TIMEOUT_MINUTES = 15;
  private static final long CI_DEFAULT_TIMEOUT_MINUTES = 60;
  private static final long CHECK_INTERVAL_MS = TimeUnit.SECONDS.toMillis(30);

  private final ConcurrentHashMap<String, Long> runningTests = new ConcurrentHashMap<>();
  private volatile boolean running;
  // Tracks the last time any test lifecycle event occurred, so the watchdog can
  // detect hangs that happen between test methods (when runningTests is empty).
  private volatile long lastActivityNanos = System.nanoTime();
  private Thread watchdogThread;

  @Override
  public void testRunStarted(Description description) throws Exception {
    super.testRunStarted(description);
    lastActivityNanos = System.nanoTime();
    running = true;
    startWatchdog();
  }

  @Override
  public void testStarted(Description description) throws Exception {
    super.testStarted(description);
    lastActivityNanos = System.nanoTime();
    runningTests.put(description.getDisplayName(), System.currentTimeMillis());
  }

  @Override
  public void testFinished(Description description) throws Exception {
    super.testFinished(description);
    lastActivityNanos = System.nanoTime();
    runningTests.remove(description.getDisplayName());
  }

  @Override
  public void testFailure(Failure failure) throws Exception {
    super.testFailure(failure);
    lastActivityNanos = System.nanoTime();
    checkAndLogDeadlocks(failure.getDescription().getDisplayName());
  }

  @Override
  public void testAssumptionFailure(Failure failure) {
    // Some test runners (e.g. TinkerPop's Gremlin suite) may call testStarted() followed
    // by an assumption failure without a corresponding testFinished(). Clean up here to
    // prevent the watchdog from seeing the test as stuck.
    lastActivityNanos = System.nanoTime();
    runningTests.remove(failure.getDescription().getDisplayName());
  }

  @Override
  public void testIgnored(Description description) throws Exception {
    super.testIgnored(description);
    // Safety net: remove in case testStarted() was called before the runner decided to skip.
    runningTests.remove(description.getDisplayName());
  }

  @Override
  public void testRunFinished(Result result) throws Exception {
    super.testRunFinished(result);

    running = false;
    if (watchdogThread != null) {
      watchdogThread.interrupt();
      watchdogThread.join(TimeUnit.SECONDS.toMillis(5));
    }

    if (result.wasSuccessful()) {
      System.out.println(
          "Shutting down YouTrackDB engine and checking for direct memory leaks...");
      final var youTrack = YouTrackDBEnginesManager.instance();

      if (youTrack != null) {
        // state is verified during engine shutdown
        youTrack.shutdown();
      } else {
        ByteBufferPool.instance(null).checkMemoryLeaks();
      }
    }
  }

  private void startWatchdog() {
    var isCi = "true".equalsIgnoreCase(System.getenv("CI"));
    var defaultMinutes = isCi ? CI_DEFAULT_TIMEOUT_MINUTES : DEFAULT_TIMEOUT_MINUTES;
    long timeoutMinutes = Long.getLong(
        "youtrackdb.test.deadlock.timeout.minutes", defaultMinutes);
    var timeoutMs = TimeUnit.MINUTES.toMillis(timeoutMinutes);
    var timeoutNanos = TimeUnit.MINUTES.toNanos(timeoutMinutes);

    // Inactivity timeout: same default as per-test timeout, separately configurable.
    long inactivityMinutes = Long.getLong(
        "youtrackdb.test.inactivity.timeout.minutes", timeoutMinutes);
    var inactivityTimeoutNanos = TimeUnit.MINUTES.toNanos(inactivityMinutes);

    watchdogThread = new Thread(() -> {
      while (running) {
        try {
          //noinspection BusyWait
          Thread.sleep(CHECK_INTERVAL_MS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return;
        }

        // Check for deadlocks on every tick, exit immediately if found
        var bean = ManagementFactory.getThreadMXBean();
        var deadlocked = bean.findDeadlockedThreads();
        if (deadlocked != null) {
          dumpDiagnosticsAndHalt(runningTests, deadlocked, "DEADLOCK DETECTED");
        }

        if (runningTests.isEmpty()) {
          // No test is currently tracked (e.g. during @After teardown or between tests).
          // Check for inactivity timeout to catch hangs in teardown code.
          var inactiveNanos = System.nanoTime() - lastActivityNanos;
          if (inactiveNanos >= inactivityTimeoutNanos) {
            dumpDiagnosticsAndHalt(runningTests, null,
                "INACTIVITY TIMEOUT (no test lifecycle event for "
                    + TimeUnit.NANOSECONDS.toMinutes(inactiveNanos) + " minutes)");
          }
          continue;
        }

        // Check all running tests for timeout
        var now = System.currentTimeMillis();
        for (var entry : runningTests.entrySet()) {
          var elapsedMs = now - entry.getValue();
          if (elapsedMs >= timeoutMs) {
            dumpDiagnosticsAndHalt(runningTests, null,
                "TEST TIMEOUT (" + entry.getKey() + " running for "
                    + (elapsedMs / 1000) + " seconds)");
          }
        }
      }
    }, "deadlock-watchdog");
    watchdogThread.setDaemon(true);
    watchdogThread.start();
  }

  private static void checkAndLogDeadlocks(String testName) {
    var bean = ManagementFactory.getThreadMXBean();
    var deadlocked = bean.findDeadlockedThreads();

    if (deadlocked != null) {
      var infos = bean.getThreadInfo(deadlocked, true, true);
      System.err.println("=== DEADLOCK DETECTED during: " + testName + " ===");
      for (var info : infos) {
        System.err.println(info);
      }
      System.err.flush();
    }
  }

  /// Dumps full diagnostics (thread dump, deadlock info, running tests) and then
  /// forcefully terminates the JVM with `Runtime.halt(1)`.
  ///
  /// Uses `halt()` instead of `System.exit()` because `exit()` runs shutdown hooks,
  /// and the engine's shutdown hook acquires locks that deadlocked threads will never
  /// release — causing the JVM to hang indefinitely instead of terminating.
  private static void dumpDiagnosticsAndHalt(
      ConcurrentHashMap<String, Long> runningTests, long[] deadlocked, String reason) {
    var bean = ManagementFactory.getThreadMXBean();
    var now = System.currentTimeMillis();

    var report = new StringBuilder();
    report.append("\n=== ").append(reason).append(" ===\n");

    if (deadlocked != null) {
      report.append("\n=== DEADLOCKED THREADS ===\n");
      var infos = bean.getThreadInfo(deadlocked, true, true);
      for (var info : infos) {
        report.append(info).append("\n");
      }
    }

    report.append("\n=== RUNNING TESTS ===\n");
    if (runningTests.isEmpty()) {
      report.append("  (none — hang occurred outside tracked test methods)\n");
    } else {
      for (var entry : runningTests.entrySet()) {
        var elapsedMs = now - entry.getValue();
        report.append("  ").append(entry.getKey())
            .append(" (").append(elapsedMs / 1000).append(" seconds)\n");
      }
    }

    report.append("\n=== ALL THREADS ===\n");
    for (var info : bean.dumpAllThreads(true, true)) {
      report.append(info).append("\n");
    }

    var reportStr = report.toString();

    System.err.println(reportStr);
    System.err.flush();
    System.out.flush();

    writeReportToFile(reportStr);

    // halt() forces immediate JVM termination without running shutdown hooks.
    Runtime.getRuntime().halt(1);
  }

  private static void writeReportToFile(String report) {
    var buildDir = System.getProperty("buildDirectory", "./target");
    var reportFile = new File(buildDir, "deadlock-report.txt");
    //noinspection ResultOfMethodCallIgnored
    reportFile.getParentFile().mkdirs();
    try (var writer = new PrintWriter(new FileWriter(reportFile))) {
      writer.print(report);
      writer.flush();
    } catch (IOException e) {
      System.err.println("Failed to write deadlock report to " + reportFile + ": "
          + e.getMessage());
      System.err.flush();
    }
  }
}
