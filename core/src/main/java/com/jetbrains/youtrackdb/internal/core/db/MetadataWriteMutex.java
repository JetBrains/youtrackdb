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
 *
 */
package com.jetbrains.youtrackdb.internal.core.db;

import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.core.exception.BaseException;
import com.jetbrains.youtrackdb.internal.core.exception.DatabaseException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The single, storage-scoped serialization point for schema- and index-changing transactions. It is
 * a {@link Semaphore} with one permit: a transaction engages it on its first schema or index write
 * and holds it until the transaction's outermost frame closes, so a second schema-changing
 * transaction blocks on the permit rather than racing to a commit-time conflict (single-writer
 * enforced by locking, never by rollback). Data commits and snapshot-based schema reads never touch
 * the mutex, so the low-schema-change-rate premise keeps contention negligible.
 *
 * <p>The permit is a {@link Semaphore} rather than a {@code ReentrantLock} because a held permit may
 * need to be released by a thread other than the one that acquired it (a pool shutdown of a
 * still-checked-out session whose owner thread is gone). A {@code ReentrantLock} only unlocks on its
 * locking thread; a semaphore permit can be released from any thread. A bare semaphore is unsafe
 * because its {@code release()} is an unconditional counter increment any caller can issue, so the
 * permit carries an ownership {@link Holder} record written at engage time and consulted at release.
 *
 * <p>Abnormal-termination handshake. The authoritative ownership record is
 * {@code (session, ordinal, thread)}: the session is the release key, the monotonic acquire ordinal
 * distinguishes this acquisition from a later one on the same recycled session object (pooled
 * sessions are reused, so session identity alone is ABA-prone: a stale releaser could otherwise free
 * a successor acquisition's permit), and the thread is engage-guard and diagnostic only — never part
 * of the release key, because the one legitimate foreign releaser (a pool shutdown running the
 * owning session's own teardown) runs on a different thread. Releases are double-belted: every
 * releaser first wins the session-level {@code getAndSet(engagedOrdinal, 0)} claim (see
 * {@code DatabaseSessionEmbedded.releaseMetadataWriteMutexForTx()} — the single funnel all three
 * release sites go through: the owner's tx-close finally, the foreign teardown's release pass, and
 * the engage-path Dekker self-release), and {@link #releaseFor(DatabaseSessionEmbedded, long)} then
 * compare-and-sets the {@code holder} keyed by {@code (session, ordinal)} as the independent second
 * belt, so two racing releasers can never both release the single permit.
 */
public final class MetadataWriteMutex {

  /**
   * The interval of the engage wait loop: how long one {@code tryAcquire} attempt parks before the
   * loop emits a holder-naming WARN and re-checks the waiter's own teardown state. A ~10s constant,
   * deliberately not configurable: it is a diagnostic cadence, not a timeout — the wait itself is
   * unbounded, so a schema transaction is never spuriously failed by contention alone.
   */
  private static final long ENGAGE_WARN_INTERVAL_SECONDS = 10;

  /**
   * The ownership record written when a session engages the permit. The {@code session} is the
   * release key; the {@code ordinal} is this acquisition's monotonic id, the second half of the
   * release key (it protects against session-identity ABA on recycled pooled sessions); the
   * {@code thread} is engage-guard and diagnostic only; {@code acquiredAtNanos} feeds the elapsed
   * time named by the wait-diagnostic WARN and the stranded-holder throw.
   */
  private record Holder(DatabaseSessionEmbedded session, long ordinal, Thread thread,
      long acquiredAtNanos) {

  }

  private final Semaphore permit = new Semaphore(1);

  /**
   * Monotonic acquire-ordinal generator. Ordinals start at 1 (0 is the session-side "not engaged"
   * sentinel) and are unique per mutex instance, so a release presenting a stale ordinal can never
   * match a successor acquisition.
   */
  private final AtomicLong ordinalGenerator = new AtomicLong();

  /**
   * The current permit owner, or {@code null} when the permit is free. An
   * {@link AtomicReference} because release is compare-and-set gated: with a foreign teardown and
   * the owner's own finally both allowed to release, a plain clear-then-release could double-release
   * the single permit. A non-null holder is written only by the thread that just acquired the
   * permit; it is cleared only by the CAS winner in {@link #releaseFor}.
   */
  private final AtomicReference<Holder> holder = new AtomicReference<>();

  /**
   * Engages the permit for {@code session} on the transaction's first schema or index write and
   * returns the acquisition's ordinal, which the caller must store into the session-side
   * {@code engagedOrdinal} record (the release funnel's claim source).
   *
   * <p>Entry rejects, both loud instead of self-deadlocking:
   * <ul>
   *   <li>same session already the holder — a stranded prior acquisition (the release never ran);
   *       re-engaging would park this thread forever on a permit its own session holds. Throws
   *       {@link IllegalStateException} naming the stranded holder and the likely cause.</li>
   *   <li>same thread, different session — one thread running an embedded session inside another
   *       would park forever on a permit its own thread holds. Throws (unchanged behavior).</li>
   * </ul>
   *
   * <p>The wait is a timed re-wait loop, not {@code acquireUninterruptibly}: the wait is UNBOUNDED
   * (a schema transaction is never spuriously failed by contention), but each
   * {@link #ENGAGE_WARN_INTERVAL_SECONDS} timeout emits a WARN naming the current holder (session,
   * thread, ordinal, elapsed hold) so an operator can diagnose a stranded holder; the loop top
   * re-checks the waiter's own teardown-intent mark and session status, so a waiter whose session
   * was torn down aborts instead of acquiring; and the wait is interruptible — an interrupt
   * restores the flag and throws {@link DatabaseException} naming the holder.
   */
  public long engage(final DatabaseSessionEmbedded session) {
    final var current = holder.get();
    if (current != null && current.session() == session) {
      // FM-A7 / Q-A5: a same-session re-engage is only reachable after a prior acquisition on this
      // session was stranded (its release never ran); parking would self-deadlock forever on the
      // session's own permit. Type and message are pinned by a regression test.
      throw new IllegalStateException(
          "the metadata-write mutex is already held by this session (acquire ordinal "
              + current.ordinal() + ", acquired on thread '" + current.thread().getName() + "' "
              + elapsedSeconds(current) + "s ago): the previous acquisition on this session was"
              + " never released; re-engaging would park forever on the session's own permit");
    }
    if (current != null && current.thread() == Thread.currentThread()) {
      // Same thread, different session: the inner embedded session would park forever on a permit
      // its own thread already holds. Fail loudly rather than self-deadlock.
      throw new IllegalStateException(
          "The current thread already holds the metadata-write mutex through a different session;"
              + " a schema transaction cannot be opened on an embedded session while the outer"
              + " session still holds the mutex on this thread");
    }

    final long waitStartNanos = System.nanoTime();
    while (true) {
      // Loop-top self-check: a waiter whose own session was marked for teardown (or closed) must
      // abort instead of acquiring — otherwise a pool shutdown could park behind a permit only to
      // engage it on a session that no longer has a releaser. The status probe is the LOCK-FREE
      // getStatus() read, deliberately not isClosed(): isClosed() consults the storage state lock
      // (stateLock.readLock), which would block this waiter uninterruptibly and WARN-silently
      // behind any in-flight commit window — defeating the loop's own diagnostics and
      // interruptibility pins.
      if (session.isTeardownIntentMarked()
          || session.getStatus() == DatabaseSessionEmbedded.STATUS.CLOSED) {
        throw new DatabaseException(session.getDatabaseName(),
            "the session was closed while waiting to engage the metadata-write mutex; "
                + describeHolder());
      }
      try {
        if (permit.tryAcquire(ENGAGE_WARN_INTERVAL_SECONDS, TimeUnit.SECONDS)) {
          break;
        }
      } catch (final InterruptedException e) {
        // Q-A3 pin (3): restore the interrupt flag and fail loudly naming the holder — the waiter
        // is killable, unlike the old acquireUninterruptibly park.
        Thread.currentThread().interrupt();
        throw BaseException.wrapException(
            new DatabaseException(session.getDatabaseName(),
                "interrupted while waiting to engage the metadata-write mutex; "
                    + describeHolder()),
            e, session.getDatabaseName());
      }
      LogManager.instance()
          .warn(this,
              "still waiting to engage the metadata-write mutex after %d s; %s",
              TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - waitStartNanos),
              describeHolder());
    }

    final long ordinal = ordinalGenerator.incrementAndGet();
    // Plain set is safe: only the thread that holds the single permit writes a non-null holder,
    // and the AtomicReference store is a volatile write, publishing the record to any foreign
    // releaser.
    holder.set(new Holder(session, ordinal, Thread.currentThread(), System.nanoTime()));
    return ordinal;
  }

  /**
   * Releases the permit for {@code session}'s acquisition {@code expectedOrdinal}. The
   * {@code (session, ordinal)}-keyed {@link AtomicReference#compareAndSet} is the second belt
   * behind the session-level {@code getAndSet(engagedOrdinal, 0)} claim: a releaser presenting a
   * stale session or ordinal (a recycled session's earlier acquisition, a successor's acquisition)
   * warn-noops, and of two racing releasers presenting the same valid key exactly one wins the CAS
   * and releases. NEVER throws: every caller sits in a teardown finally where a throw would mask
   * the real exception. The winner nulls the holder BEFORE releasing the permit, so the next
   * engager can never observe a stale owner after acquiring.
   */
  public void releaseFor(final DatabaseSessionEmbedded session, final long expectedOrdinal) {
    final var current = holder.get();
    if (current == null || current.session() != session
        || current.ordinal() != expectedOrdinal) {
      // Free, owned by another session, or a different acquisition of this session: nothing for
      // this (session, ordinal) to release. Warn — with the session-level claim in front of this
      // method, a mismatch here means a stale or duplicate release attempt worth diagnosing.
      LogManager.instance()
          .warn(this,
              "metadata-write mutex release for ordinal %d skipped: %s",
              expectedOrdinal, describeHolder());
      return;
    }
    if (holder.compareAndSet(current, null)) {
      permit.release();
    } else {
      // A concurrent releaser won the CAS between our read and our CAS; it releases the permit.
      LogManager.instance()
          .warn(this,
              "metadata-write mutex release for ordinal %d lost the clear race; the concurrent"
                  + " releaser owns the permit release",
              expectedOrdinal);
    }
  }

  /**
   * Whether {@code session} currently holds the permit. Used by the engage-order assertion and by
   * tests; not part of the release protocol.
   */
  public boolean isEngagedBy(final DatabaseSessionEmbedded session) {
    final var current = holder.get();
    return current != null && current.session() == session;
  }

  /** Names the current holder for wait diagnostics and release warn-noops. */
  private String describeHolder() {
    final var current = holder.get();
    if (current == null) {
      return "the permit is currently free";
    }
    return "current holder: session of database '" + current.session().getDatabaseName()
        + "' (ordinal " + current.ordinal() + ", acquired on thread '"
        + current.thread().getName() + "' " + elapsedSeconds(current) + "s ago)";
  }

  private static long elapsedSeconds(final Holder current) {
    return TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - current.acquiredAtNanos());
  }
}
