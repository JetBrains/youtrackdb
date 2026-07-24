package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.operationsfreezer;

/**
 * The taxonomy of write-operation freezes, recorded at every registration site so the freezer's
 * schema-commit gate can tell a long-lived operator action from a routine internal quiesce.
 *
 * <ul>
 *   <li>{@link #OPERATOR} — an admin-initiated freeze of unbounded duration (the filesystem
 *       snapshot freeze/release pair). A schema-carrying commit must never block or park while one
 *       is active: parked inside its four-lock window it would convert the freeze into a
 *       storage-wide read outage, so the gate aborts it loudly instead.</li>
 *   <li>{@link #TRANSIENT_QUIESCE} — a short, self-releasing internal quiesce (the synch flush,
 *       the incremental-backup WAL copy, the backup segment cut). A schema commit may park behind
 *       one exactly like a data commit; the park is bounded by the quiesce body.</li>
 * </ul>
 */
public enum FreezeKind {
  OPERATOR, TRANSIENT_QUIESCE
}
