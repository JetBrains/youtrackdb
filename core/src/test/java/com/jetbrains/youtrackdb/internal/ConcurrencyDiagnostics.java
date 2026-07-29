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

import static org.junit.Assert.fail;

import java.util.concurrent.TimeUnit;

/// One shared dialect for the timeout-bounded concurrency tests, colocated with
/// [JUnitTestListener#appendDiagnostics] because every helper here ends in that report:
///
///   - **shared deadlines**: [#deadlineFromNow] / [#remainingMillis], so a test's sequential waits
///     share ONE budget instead of summing independent bounds past the `@Test(timeout)` (which can
///     only ever produce a bare `TestTimedOutException` carrying no thread state);
///   - **dump before cleanup**: [#dumpThreads] / [#assertWithThreadDump], so the stacks are
///     captured before the caller's `finally` interrupts a stuck thread or releases a latch it is
///     pinned on.
///
/// Deliberately monotonic: deadlines are [System#nanoTime] values, never wall clock. A forward
/// clock step (NTP, or a Hyper-V guest resyncing after the host suspends it — routine on the
/// Windows CI VMs) would collapse every remaining-time computation onto the 1 ms clamp and report a
/// false deadlock; a backward step would push the tail past the `@Test(timeout)` and restore the
/// very failure mode these helpers exist to remove.
public final class ConcurrencyDiagnostics {

  private ConcurrencyDiagnostics() {
  }

  /// A deadline `millis` from now, as a [System#nanoTime] value to be passed to
  /// [#remainingMillis]. Arm it immediately before the racing starts, so unrelated setup (opening
  /// sessions, building fixtures) is not charged to the contention budget.
  public static long deadlineFromNow(final long millis) {
    return System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
  }

  /// Milliseconds remaining until `deadlineNanos`, clamped to at least 1 ms. Every wait derived
  /// from a shared deadline must go through this clamp: [Thread#join(long)] with `0` waits
  /// *forever* and with a negative argument throws [IllegalArgumentException], so an expired
  /// deadline would otherwise turn a bounded wait into an unbounded one (or an unrelated crash).
  ///
  /// The subtraction is done in nanos so it stays correct across [System#nanoTime] wraparound.
  public static long remainingMillis(final long deadlineNanos) {
    return Math.max(1L, TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime()));
  }

  /// Appends a `label`-tagged thread dump to the shared diagnostics report (the build directory's
  /// `deadlock-report.txt`, which CI uploads as an artifact).
  ///
  /// Never throws: a caller is always about to report a *different*, real failure, and a dump that
  /// failed (a full disk, a security manager, an [OutOfMemoryError] while building the dump string)
  /// must not replace that failure with its own. A dump that cannot be written is reported on
  /// stderr and otherwise ignored.
  public static void dumpThreads(final String label) {
    try {
      JUnitTestListener.appendDiagnostics(label);
    } catch (final Throwable dumpFailure) {
      // Deliberately swallowed - see above. Printed, not rethrown, so the caller's own failure is
      // the one that reaches the test report.
      System.err.println("Failed to write thread diagnostics for '" + label + "': " + dumpFailure);
      System.err.flush();
    }
  }

  /// Dumps thread state under `message` and then fails with it. For stalls only: use it where the
  /// caller's cleanup (an interrupt, a latch release) would unwind the state that explains the
  /// stall before anyone could read it.
  public static void failWithThreadDump(final String message) {
    dumpThreads(message);
    fail(message);
  }

  /// [#failWithThreadDump] unless `condition` holds.
  public static void assertWithThreadDump(final String message, final boolean condition) {
    if (!condition) {
      failWithThreadDump(message);
    }
  }
}
