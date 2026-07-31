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
import com.jetbrains.youtrackdb.internal.core.YouTrackDBEnginesManager;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.runner.Description;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunListener;

///   - Listens for JUnit test run started and prohibits logging of exceptions on storage level.
///   - Listens for the JUnit test run finishing and runs the direct memory leaks detector, if no
///     tests failed. If leak detector finds some leaks, it triggers [AssertionError] and the
///     build is marked as failed. Java assertions (-ea) must be active for this to work.
///   - Triggers [AssertionError] if the engine's log manager is shutdown before the test is
///     finished. We may miss some errors because the log manager is shutdown.
///   - Runs a watchdog thread that detects deadlocked or stuck tests. If a test exceeds the
///     configured timeout, diagnostics are dumped and the JVM is terminated. The timeout is
///     configurable via `-Dyoutrackdb.test.deadlock.timeout.minutes` (default: 15).
///   - Detects inactivity gaps (e.g. during @After teardown or between test methods) when no
///     test is tracked as running. Uses `Runtime.halt(1)` instead of `System.exit(1)` to avoid
///     deadlocking in shutdown hooks when threads hold locks that would never be released.
///   - Exposes [#appendDiagnostics] so a test that detects a stuck thread can contribute its own
///     labelled thread dump to the same report the watchdog writes.
public class JUnitTestListener extends RunListener {

  private static final long DEFAULT_TIMEOUT_MINUTES = 15;
  private static final long CI_DEFAULT_TIMEOUT_MINUTES = 60;
  private static final long CHECK_INTERVAL_MS = TimeUnit.SECONDS.toMillis(30);
  /// Serializes every write to the shared diagnostics report. Up to four test classes share one
  /// forked JVM under parallel class execution, and a test's own dump can race the watchdog's, so
  /// without this lock (and append mode) the writes truncate or interleave with each other.
  ///
  /// A [ReentrantLock] rather than a monitor because the watchdog's halt path must never *block*
  /// on it: see [#appendReportToFile].
  private static final ReentrantLock REPORT_LOCK = new ReentrantLock();
  /// How long the watchdog's last-resort halt path is willing to wait for [#REPORT_LOCK] before it
  /// gives up on the file and halts anyway.
  private static final long HALT_PATH_LOCK_TIMEOUT_SECONDS = 5;

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

  /// Appends a labelled diagnostics block — a header carrying `label` and a timestamp, the
  /// deadlocked-thread set when the JVM reports one, and a full thread dump with locked monitors
  /// and synchronizers — to the shared diagnostics report, and echoes it to `System.err`.
  ///
  /// Public entry point for tests that detect a stuck thread: the stack has to be captured BEFORE
  /// the test interrupts that thread or releases a latch it is pinned on, because both unwind
  /// exactly the state the dump must show. Writes are append-mode and serialized on
  /// [#REPORT_LOCK], so a test's dump never erases the watchdog's dump or another test's.
  public static void appendDiagnostics(String label) {
    var bean = ManagementFactory.getThreadMXBean();

    var report = new StringBuilder();
    report.append(blockHeader("DIAGNOSTICS: " + label));

    var deadlocked = bean.findDeadlockedThreads();
    if (deadlocked != null) {
      report.append("\n=== DEADLOCKED THREADS ===\n");
      for (var info : bean.getThreadInfo(deadlocked, true, true)) {
        report.append(info).append("\n");
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

    appendReportToFile(reportStr, false);
  }

  /// The common first line of every report block: the reason/label, the wall-clock instant, and the
  /// thread that produced it. Both producers (a test's [#appendDiagnostics] and the watchdog's
  /// [#dumpDiagnosticsAndHalt]) use it, so blocks in the accumulated report are always
  /// attributable.
  private static String blockHeader(String reason) {
    return "\n=== " + reason + " === (" + Instant.now() + ", reported by thread '"
        + Thread.currentThread().getName() + "')\n";
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
    report.append(blockHeader(reason));

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

    // Best effort: this is the last-resort halt path, so it must never be blocked by whoever holds
    // the report lock (a wedged writer, or a test thread stuck mid-dump).
    appendReportToFile(reportStr, true);

    // halt() forces immediate JVM termination without running shutdown hooks.
    Runtime.getRuntime().halt(1);
  }

  /// Appends `report` to the shared diagnostics report file. The file name and location are fixed
  /// (`${buildDirectory}/deadlock-report.txt`) because the CI artifact upload globs match it by
  /// name — renaming it would silently stop uploading diagnostics. Append mode plus
  /// [#REPORT_LOCK] is what lets several producers (the watchdog and individual tests) contribute
  /// to one report instead of overwriting each other.
  ///
  /// `bestEffort` callers (only the watchdog's halt path) never block indefinitely on the lock:
  /// they wait [#HALT_PATH_LOCK_TIMEOUT_SECONDS] and then fall back to stderr, which the report
  /// has already been echoed to. Blocking there would let a wedged writer prevent the halt — the
  /// one action that must always happen.
  private static void appendReportToFile(String report, boolean bestEffort) {
    var buildDir = System.getProperty("buildDirectory", "./target");
    var reportFile = new File(buildDir, "deadlock-report.txt");
    //noinspection ResultOfMethodCallIgnored
    reportFile.getParentFile().mkdirs();

    if (bestEffort) {
      var acquired = false;
      try {
        acquired = REPORT_LOCK.tryLock(HALT_PATH_LOCK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      if (!acquired) {
        System.err.println("Skipping the " + reportFile + " write: the report lock was still held"
            + " after " + HALT_PATH_LOCK_TIMEOUT_SECONDS + "s. The report above (on stderr) is the"
            + " only copy; halting now.");
        System.err.flush();
        return;
      }
    } else {
      REPORT_LOCK.lock();
    }

    try (var writer = new PrintWriter(new FileWriter(reportFile, true))) {
      writer.print(report);
      writer.flush();
    } catch (IOException e) {
      System.err.println("Failed to write deadlock report to " + reportFile + ": "
          + e.getMessage());
      System.err.flush();
    } finally {
      REPORT_LOCK.unlock();
    }
  }
}
