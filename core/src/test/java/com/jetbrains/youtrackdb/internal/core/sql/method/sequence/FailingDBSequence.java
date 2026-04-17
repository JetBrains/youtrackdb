/*
 *
 * Copyright 2013 Geomatys.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.jetbrains.youtrackdb.internal.core.sql.method.sequence;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.exception.DatabaseException;
import com.jetbrains.youtrackdb.internal.core.metadata.sequence.DBSequence;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;

/**
 * Test-only {@link DBSequence} subclass that throws {@link DatabaseException} from every
 * {@code *Work} hook. Shared by the three {@code SQLMethodCurrent/Next/Reset} tests to cover
 * the <em>re-wrap</em> branch where the method catches a {@link DatabaseException} and throws
 * a {@link com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException}.
 *
 * <p>Construction requires an existing real {@link DBSequence} whose entity's {@link RID} is
 * borrowed via reflection on the {@code entityRid} protected field. The borrowed entity is
 * only used to satisfy {@code Objects.requireNonNull(entity)} in the {@link DBSequence}
 * constructor — none of the overridden work methods dereference it.
 */
final class FailingDBSequence extends DBSequence {

  private final String detail;

  private FailingDBSequence(EntityImpl entity, String detail) {
    super(entity);
    this.detail = detail;
  }

  /** Borrow the entity backing {@code real} and build a failing subclass with the given detail. */
  static FailingDBSequence wrapping(
      DatabaseSessionEmbedded session, DBSequence real, String detail) {
    try {
      var f = DBSequence.class.getDeclaredField("entityRid");
      f.setAccessible(true);
      var rid = (RID) f.get(real);
      // Entity load requires an active transaction — the production code inside
      // callRetry opens its own tx, but we only need the entity for
      // Objects.requireNonNull in the super-constructor. Wrap the load in a
      // short-lived tx so this helper works whether or not the caller has one.
      var existing = session.getActiveTransactionOrNull();
      boolean openedHere = existing == null || !existing.isActive();
      if (openedHere) {
        session.begin();
      }
      try {
        EntityImpl entity = session.load(rid);
        return new FailingDBSequence(entity, detail);
      } finally {
        if (openedHere) {
          session.rollback();
        }
      }
    } catch (ReflectiveOperationException e) {
      throw new AssertionError("unable to access DBSequence.entityRid via reflection", e);
    }
  }

  @Override
  public long nextWork(DatabaseSessionEmbedded session) {
    throw new DatabaseException(session, detail);
  }

  @Override
  protected long currentWork(DatabaseSessionEmbedded session) {
    throw new DatabaseException(session, detail);
  }

  @Override
  public long resetWork(DatabaseSessionEmbedded session) {
    throw new DatabaseException(session, detail);
  }

  @Override
  public SEQUENCE_TYPE getSequenceType() {
    return SEQUENCE_TYPE.ORDERED;
  }
}
