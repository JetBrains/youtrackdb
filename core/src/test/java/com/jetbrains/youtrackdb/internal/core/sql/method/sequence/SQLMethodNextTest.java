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
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for the SQL <code>next()</code> method on sequences ({@link SQLMethodNext}).
 *
 * <p>Extends {@link DbTestBase} because the method resolves
 * {@link com.jetbrains.youtrackdb.internal.core.command.CommandContext#getDatabaseSession()}
 * in every code path and calls {@link DBSequence#next(DatabaseSessionEmbedded)} which performs
 * a transactional entity update with retry semantics.
 *
 * <p>Covered branches:
 *
 * <ul>
 *   <li>Happy path: first {@code next()} on a fresh ORDERED sequence returns 1 (DEFAULT_START
 *       + DEFAULT_INCREMENT).</li>
 *   <li>Consecutive {@code next()} calls advance by DEFAULT_INCREMENT (1, 2, 3).</li>
 *   <li>{@code iThis == null} throws {@link CommandSQLParsingException} mentioning "NULL was
 *       found" and "next()".</li>
 *   <li>Wrong-type {@code iThis} throws {@link CommandSQLParsingException} mentioning the
 *       actual class of {@code iThis}.</li>
 *   <li>A {@link DatabaseException} thrown from the underlying sequence is re-wrapped into
 *       a {@link CommandExecutionException} whose message begins with "Unable to execute
 *       command:" and carries the original detail.</li>
 *   <li>Metadata: name = "next", minParams = 0, maxParams = 0, syntax = "next()".</li>
 * </ul>
 */
public class SQLMethodNextTest extends DbTestBase {

  private static final String SEQ_NAME = "mySeq";

  private SQLMethodNext method;
  private BasicCommandContext ctx;

  @Before
  public void setup() {
    method = new SQLMethodNext();
    ctx = new BasicCommandContext(session);
    session.getMetadata().getSequenceLibrary().createSequence(
        SEQ_NAME, DBSequence.SEQUENCE_TYPE.ORDERED,
        new DBSequence.CreateParams().setDefaults());
  }

  // ---------------------------------------------------------------------------
  // Happy path
  // ---------------------------------------------------------------------------

  @Test
  public void firstNextReturnsOne() {
    var seq = session.getMetadata().getSequenceLibrary().getSequence(SEQ_NAME.toUpperCase());
    assertNotNull("sequence fixture missing", seq);

    var result = method.execute(seq, null, ctx, null, new Object[] {});

    // Default start=0, default increment=1 → first next() = 1
    assertEquals(1L, result);
  }

  @Test
  public void consecutiveNextCallsAdvanceByIncrement() {
    var seq = session.getMetadata().getSequenceLibrary().getSequence(SEQ_NAME.toUpperCase());

    var r1 = method.execute(seq, null, ctx, null, new Object[] {});
    var r2 = method.execute(seq, null, ctx, null, new Object[] {});
    var r3 = method.execute(seq, null, ctx, null, new Object[] {});

    assertEquals(1L, r1);
    assertEquals(2L, r2);
    assertEquals(3L, r3);
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
          "message should reference next(), saw: " + e.getMessage(),
          e.getMessage().contains("next()"));
    }
  }

  @Test
  public void wrongTypeThisThrowsParsingExceptionMentioningClassName() {
    var bogus = Integer.valueOf(42);

    try {
      method.execute(bogus, null, ctx, null, new Object[] {});
      fail("expected CommandSQLParsingException for Integer iThis");
    } catch (CommandSQLParsingException e) {
      assertNotNull(e.getMessage());
      assertTrue(
          "message should mention the Integer class, saw: " + e.getMessage(),
          e.getMessage().contains(Integer.class.getName()));
      assertTrue(
          "message should reference next(), saw: " + e.getMessage(),
          e.getMessage().contains("next()"));
    }
  }

  // ---------------------------------------------------------------------------
  // Error path — DatabaseException re-wrap
  // ---------------------------------------------------------------------------

  @Test
  public void databaseExceptionFromSequenceIsWrappedAsCommandExecutionException() {
    var realSeq = session.getMetadata().getSequenceLibrary().getSequence(SEQ_NAME.toUpperCase());
    var failing = FailingDBSequence.wrapping(session, realSeq, "boom-next");

    try {
      method.execute(failing, null, ctx, null, new Object[] {});
      fail("expected CommandExecutionException wrapping the DatabaseException");
    } catch (CommandExecutionException e) {
      assertNotNull(e.getMessage());
      assertTrue(
          "message should start with 'Unable to execute command:', saw: " + e.getMessage(),
          e.getMessage().contains("Unable to execute command:"));
      assertTrue(
          "message should carry the original 'boom-next' detail, saw: " + e.getMessage(),
          e.getMessage().contains("boom-next"));
    }
  }

  // ---------------------------------------------------------------------------
  // Metadata / contract surface
  // ---------------------------------------------------------------------------

  @Test
  public void constantNameMatchesMethodName() {
    assertEquals("next", SQLMethodNext.NAME);
    assertEquals(SQLMethodNext.NAME, method.getName());
  }

  @Test
  public void minAndMaxParamsAreZero() {
    assertEquals(0, method.getMinParams());
    assertEquals(0, method.getMaxParams(session));
  }

  @Test
  public void syntaxIsNextWithEmptyParens() {
    assertEquals("next()", method.getSyntax());
  }
}
