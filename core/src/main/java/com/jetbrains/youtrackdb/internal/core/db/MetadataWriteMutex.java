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

import java.util.concurrent.Semaphore;
import javax.annotation.Nullable;

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
 * <p>This class ships the engage path, the same-thread loud-reject, and the normal release fired in
 * the owning transaction's outermost teardown. The full abnormal-termination handshake (the
 * teardown-intent mark, the monotonic acquire ordinal, the foreign-teardown heal) and the freezer
 * gate are a later track. The {@link #releaseFor(DatabaseSessionEmbedded)} compare-and-clear is
 * already session-keyed so that this track's normal release and the later track's compare-and-clear
 * can both target the same permit without ever double-releasing it: whichever runs first clears the
 * holder, and the other observes a foreign or absent holder and no-ops.
 */
public final class MetadataWriteMutex {

  /**
   * The ownership record written when a session engages the permit. The {@code session} is the
   * release key (release fires only while this session still owns the permit); the {@code thread} is
   * engage-guard and diagnostic only -- it powers the same-thread loud-reject and names the holder in
   * a wait diagnostic, and it is never part of the release key because the one legitimate foreign
   * releaser runs on a different thread. The later track extends this record with a monotonic acquire
   * ordinal for the compare-and-clear; the session-only key here is the subset that keeps the normal
   * release idempotent against that extension.
   */
  private record Holder(DatabaseSessionEmbedded session, Thread thread) {
  }

  private final Semaphore permit = new Semaphore(1);

  /**
   * The current permit owner, or {@code null} when the permit is free. Volatile so the engage's
   * post-acquire write is visible to a release running on a foreign thread and so the same-thread
   * reject reads a current value. Mutated only by a thread that holds the permit (engage, just after
   * acquiring) or that is clearing it under the session-keyed compare-and-clear.
   */
  @Nullable private volatile Holder holder;

  /**
   * Engages the permit for {@code session} on the transaction's first schema or index write. Blocks
   * until the permit is free when another session holds it (a different thread parking on a held
   * permit is healthy contention). Throws when the current thread already holds the permit through a
   * <em>different</em> session: that is one thread running an embedded session inside another, which
   * would self-deadlock by parking forever on a permit its own thread holds, so it fails fast
   * instead. Re-engaging through the same session is impossible because the caller only reaches this
   * method on the first write of a transaction (the tx-local schema state seeds at most once).
   */
  public void engage(final DatabaseSessionEmbedded session) {
    final var current = holder;
    if (current != null
        && current.thread() == Thread.currentThread()
        && current.session() != session) {
      // Same thread, different session: the inner embedded session would park forever on a permit
      // its own thread already holds. Fail loudly rather than self-deadlock.
      throw new IllegalStateException(
          "The current thread already holds the metadata-write mutex through a different session;"
              + " a schema transaction cannot be opened on an embedded session while the outer"
              + " session still holds the mutex on this thread");
    }

    permit.acquireUninterruptibly();
    holder = new Holder(session, Thread.currentThread());
  }

  /**
   * Releases the permit for {@code session} in the transaction's outermost teardown, using a
   * session-keyed compare-and-clear: the permit is released only while {@code session} is still the
   * recorded holder. A no-op when the permit is free or owned by another session, which makes the
   * release idempotent -- the later track's abnormal-termination compare-and-clear can run against
   * the same permit without either releaser double-incrementing the counter. Releasing clears the
   * holder first and then calls {@link Semaphore#release()}, so a concurrent engage cannot observe a
   * stale owner once the permit is handed off.
   */
  public void releaseFor(final DatabaseSessionEmbedded session) {
    final var current = holder;
    if (current == null || current.session() != session) {
      // Free, or owned by a successor / different session: nothing for this session to release.
      return;
    }
    holder = null;
    permit.release();
  }

  /**
   * Whether {@code session} currently holds the permit. Used by the engage-order assertion and by
   * tests; not part of the release protocol.
   */
  public boolean isEngagedBy(final DatabaseSessionEmbedded session) {
    final var current = holder;
    return current != null && current.session() == session;
  }
}
