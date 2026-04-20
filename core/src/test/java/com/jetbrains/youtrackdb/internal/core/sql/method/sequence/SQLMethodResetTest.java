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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.exception.CommandSQLParsingException;
import com.jetbrains.youtrackdb.internal.core.exception.DatabaseException;
import com.jetbrains.youtrackdb.internal.core.metadata.sequence.DBSequence;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for the SQL <code>reset()</code> method on sequences ({@link SQLMethodReset}).
 *
 * <p>Extends {@link DbTestBase} because the method resolves
 * {@link com.jetbrains.youtrackdb.internal.core.command.CommandContext#getDatabaseSession()}
 * and calls {@link DBSequence#reset(DatabaseSessionEmbedded)} which writes the default start
 * value back to the entity.
 *
 * <p>Covered branches:
 *
 * <ul>
 *   <li>Happy path: after several {@code next()} bumps, {@code reset()} restores the default
 *       start value (0) and subsequent {@code current()} also reads 0.</li>
 *   <li>{@code iThis == null} throws {@link CommandSQLParsingException} mentioning "NULL was
 *       found" and "reset()".</li>
 *   <li>Wrong-type {@code iThis} throws {@link CommandSQLParsingException} mentioning the
 *       actual class of {@code iThis}.</li>
 *   <li>A {@link DatabaseException} thrown from the underlying sequence is re-wrapped into
 *       a {@link CommandExecutionException} whose message begins with "Unable to execute
 *       command:" and carries the original detail.</li>
 *   <li>Metadata: name = "reset", minParams = 0, maxParams = 0, syntax = "reset()".</li>
 * </ul>
 */
public class SQLMethodResetTest extends DbTestBase {

  private static final String SEQ_NAME = "mySeq";

  private SQLMethodReset method;
  private BasicCommandContext ctx;

  @Before
  public void setup() {
    method = new SQLMethodReset();
    ctx = new BasicCommandContext(session);
    session.getMetadata().getSequenceLibrary().createSequence(
        SEQ_NAME, DBSequence.SEQUENCE_TYPE.ORDERED,
        new DBSequence.CreateParams().setDefaults());
  }

  /**
   * Safety net: {@code SQLMethodReset} relies on {@link DBSequence#reset} which writes the entity
   * via its own transactional retry. Roll back any leaked tx so a mid-test failure does not
   * cascade into subsequent methods.
   */
  @After
  public void rollbackIfLeftOpen() {
    var tx = session.getActiveTransactionOrNull();
    if (tx != null && tx.isActive()) {
      session.rollback();
    }
  }

  // ---------------------------------------------------------------------------
  // Happy path
  // ---------------------------------------------------------------------------

  @Test
  public void resetAfterNextRestoresStartAndPersists() {
    var seq = session.getMetadata().getSequenceLibrary().getSequence(SEQ_NAME.toUpperCase());
    assertNotNull("sequence fixture missing", seq);
    seq.next(session);
    seq.next(session);
    seq.next(session);

    var resetResult = method.execute(seq, null, ctx, null, new Object[] {});

    assertEquals(0L, resetResult);
    // Drift guard: the reset must be persisted — current() must also report 0 after reset.
    assertEquals(0L, seq.current(session));
  }

  @Test
  public void resetOnFreshSequenceStillReturnsStart() {
    // A sequence that never had next() called should still reset cleanly to start=0.
    var seq = session.getMetadata().getSequenceLibrary().getSequence(SEQ_NAME.toUpperCase());

    assertEquals(0L, method.execute(seq, null, ctx, null, new Object[] {}));
  }

  // ---------------------------------------------------------------------------
  // Error paths — CommandSQLParsingException
  // ---------------------------------------------------------------------------

  @Test
  public void nullThisThrowsParsingExceptionMentioningNull() {
    try {
      method.execute(null, null, ctx, null, new Object[] {});
      fail("expected CommandSQLParsingException for null iThis");
    } catch (CommandSQLParsingException e) {
      assertNotNull(e.getMessage());
      assertTrue(
          "message should mention NULL, saw: " + e.getMessage(),
          e.getMessage().contains("NULL was found"));
      assertTrue(
          "message should reference reset(), saw: " + e.getMessage(),
          e.getMessage().contains("reset()"));
    }
  }

  @Test
  public void wrongTypeThisThrowsParsingExceptionMentioningClassName() {
    var bogus = new Object();

    try {
      method.execute(bogus, null, ctx, null, new Object[] {});
      fail("expected CommandSQLParsingException for Object iThis");
    } catch (CommandSQLParsingException e) {
      assertNotNull(e.getMessage());
      assertTrue(
          "message should mention the Object class, saw: " + e.getMessage(),
          e.getMessage().contains(Object.class.getName()));
      assertTrue(
          "message should reference reset(), saw: " + e.getMessage(),
          e.getMessage().contains("reset()"));
    }
  }

  // ---------------------------------------------------------------------------
  // Error path — DatabaseException re-wrap
  // ---------------------------------------------------------------------------

  @Test
  public void databaseExceptionFromSequenceIsWrappedAsCommandExecutionException() {
    var realSeq = session.getMetadata().getSequenceLibrary().getSequence(SEQ_NAME.toUpperCase());
    var failing = FailingDBSequence.wrapping(session, realSeq, "boom-reset");

    try {
      method.execute(failing, null, ctx, null, new Object[] {});
      fail("expected CommandExecutionException wrapping the DatabaseException");
    } catch (CommandExecutionException e) {
      // Catching CommandExecutionException (not DatabaseException) pins the re-wrap:
      // CommandExecutionException extends CoreException — it is NOT a DatabaseException
      // subtype — so a re-thrown DatabaseException would fall through this catch and fail
      // the test at the outer `fail(...)` below.
      assertNotNull(e.getMessage());
      assertTrue(
          "message should start with 'Unable to execute command:', saw: " + e.getMessage(),
          e.getMessage().contains("Unable to execute command:"));
      assertTrue(
          "message should carry the original 'boom-reset' detail, saw: " + e.getMessage(),
          e.getMessage().contains("boom-reset"));
    }
  }

  // ---------------------------------------------------------------------------
  // Non-default start — pins that reset() uses the sequence's configured start, not hardcoded
  // ---------------------------------------------------------------------------

  @Test
  public void resetWithCustomStartReturnsConfiguredStart() {
    // A regression that hardcoded 0 as the reset value would be caught here. start=100
    // → after any number of next() calls, reset() must return 100.
    var params = new DBSequence.CreateParams().setDefaults().setStart(100L).setIncrement(1);
    session.getMetadata().getSequenceLibrary()
        .createSequence("customSeq", DBSequence.SEQUENCE_TYPE.ORDERED, params);
    var seq = session.getMetadata().getSequenceLibrary().getSequence("CUSTOMSEQ");
    seq.next(session);
    seq.next(session);

    assertEquals(100L, method.execute(seq, null, ctx, null, new Object[] {}));
    assertEquals(100L, seq.current(session));
  }

  // ---------------------------------------------------------------------------
  // Metadata / contract surface
  // ---------------------------------------------------------------------------

  @Test
  public void constantNameMatchesMethodName() {
    assertEquals("reset", SQLMethodReset.NAME);
    assertEquals(SQLMethodReset.NAME, method.getName());
  }

  @Test
  public void minAndMaxParamsAreZero() {
    assertEquals(0, method.getMinParams());
    assertEquals(0, method.getMaxParams(session));
  }

  @Test
  public void syntaxIsResetWithEmptyParens() {
    assertEquals("reset()", method.getSyntax());
  }
}
