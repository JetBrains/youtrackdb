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
 * Tests for the SQL <code>current()</code> method on sequences ({@link SQLMethodCurrent}).
 *
 * <p>Extends {@link DbTestBase} because the method resolves
 * {@link com.jetbrains.youtrackdb.internal.core.command.CommandContext#getDatabaseSession()}
 * in every code path (including the two error paths that build exception messages) and then
 * calls {@link DBSequence#current(DatabaseSessionEmbedded)} which performs a transactional
 * entity load.
 *
 * <p>Covered branches:
 *
 * <ul>
 *   <li>Happy path: freshly-created ORDERED sequence returns the default start value (0).</li>
 *   <li>After two {@code next()} bumps, {@code current()} reflects the incremented value (2).</li>
 *   <li>{@code iThis == null} throws {@link CommandSQLParsingException} whose message contains
 *       "NULL was found" and references "current()".</li>
 *   <li>{@code iThis} is not a {@link DBSequence} throws {@link CommandSQLParsingException}
 *       whose message mentions the actual class of {@code iThis} (drift guard against someone
 *       silently coercing a String/Number).</li>
 *   <li>A {@link DatabaseException} thrown from the underlying sequence is re-wrapped into a
 *       {@link CommandExecutionException} whose message begins with "Unable to execute
 *       command:" and preserves the original detail.</li>
 *   <li>Metadata: name = "current", minParams = 0, maxParams = 0, syntax = "current()".</li>
 * </ul>
 */
public class SQLMethodCurrentTest extends DbTestBase {

  private static final String SEQ_NAME = "mySeq";

  private SQLMethodCurrent method;
  private BasicCommandContext ctx;

  @Before
  public void setup() {
    method = new SQLMethodCurrent();
    ctx = new BasicCommandContext(session);
    // SequenceLibrary stores sequences keyed by upper-cased name — getSequence below therefore
    // looks up SEQ_NAME.toUpperCase() even though createSequence accepts the mixed-case form.
    session.getMetadata().getSequenceLibrary().createSequence(
        SEQ_NAME, DBSequence.SEQUENCE_TYPE.ORDERED,
        new DBSequence.CreateParams().setDefaults());
  }

  /**
   * Safety net: {@code SQLMethodCurrent.execute} does not itself open a transaction, but the
   * underlying {@link DBSequence} entity load may leak a transaction if a test fails mid-path.
   * Roll back any leaked tx to prevent cascade-failures across sibling methods. Uses
   * {@code getActiveTransactionOrNull()} because the non-or-null variant throws when no tx is
   * active.
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
  public void newSequenceCurrentReturnsDefaultStart() {
    var seq = session.getMetadata().getSequenceLibrary().getSequence(SEQ_NAME.toUpperCase());
    assertNotNull("sequence fixture missing", seq);

    var result = method.execute(seq, null, ctx, null, new Object[] {});

    // DEFAULT_START is 0L — next() has never been called.
    assertEquals(0L, result);
  }

  @Test
  public void currentReflectsValueAfterNext() {
    var seq = session.getMetadata().getSequenceLibrary().getSequence(SEQ_NAME.toUpperCase());
    // Bump twice via next() — current() must reflect the stored value, not call next() itself.
    seq.next(session);
    seq.next(session);

    var result = method.execute(seq, null, ctx, null, new Object[] {});

    assertEquals(2L, result);
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
          "message should reference current(), saw: " + e.getMessage(),
          e.getMessage().contains("current()"));
    }
  }

  @Test
  public void wrongTypeThisThrowsParsingExceptionMentioningClassName() {
    var bogus = "not a sequence";

    try {
      method.execute(bogus, null, ctx, null, new Object[] {});
      fail("expected CommandSQLParsingException for String iThis");
    } catch (CommandSQLParsingException e) {
      assertNotNull(e.getMessage());
      // The production code embeds iThis.getClass() (whole class descriptor including "class ").
      assertTrue(
          "message should mention the String class, saw: " + e.getMessage(),
          e.getMessage().contains(String.class.getName()));
      assertTrue(
          "message should reference current(), saw: " + e.getMessage(),
          e.getMessage().contains("current()"));
    }
  }

  // ---------------------------------------------------------------------------
  // Error path — DatabaseException re-wrap
  // ---------------------------------------------------------------------------

  @Test
  public void databaseExceptionFromSequenceIsWrappedAsCommandExecutionException() {
    var realSeq = session.getMetadata().getSequenceLibrary().getSequence(SEQ_NAME.toUpperCase());
    var failing = FailingDBSequence.wrapping(session, realSeq, "boom-current");

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
          "message should carry the original 'boom-current' detail, saw: " + e.getMessage(),
          e.getMessage().contains("boom-current"));
    }
  }

  // ---------------------------------------------------------------------------
  // Non-default start — pins that current() delegates to the sequence's stored value
  // ---------------------------------------------------------------------------

  @Test
  public void currentWithCustomStartReflectsStoredValue() {
    // A regression that hardcoded 0 as current() would be caught here — use start=100 and
    // verify current() returns 100 before any next() call.
    var params = new DBSequence.CreateParams().setDefaults().setStart(100L);
    session.getMetadata().getSequenceLibrary()
        .createSequence("customSeq", DBSequence.SEQUENCE_TYPE.ORDERED, params);
    var seq = session.getMetadata().getSequenceLibrary().getSequence("CUSTOMSEQ");

    assertEquals(100L, method.execute(seq, null, ctx, null, new Object[] {}));
  }

  // ---------------------------------------------------------------------------
  // Metadata / contract surface
  // ---------------------------------------------------------------------------

  @Test
  public void constantNameMatchesMethodName() {
    assertEquals("current", SQLMethodCurrent.NAME);
    assertEquals(SQLMethodCurrent.NAME, method.getName());
  }

  @Test
  public void minAndMaxParamsAreZero() {
    assertEquals(0, method.getMinParams());
    assertEquals(0, method.getMaxParams(session));
  }

  @Test
  public void syntaxIsCurrentWithEmptyParens() {
    assertEquals("current()", method.getSyntax());
  }
}
