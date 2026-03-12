package com.jetbrains.youtrackdb.junit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.exception.DatabaseException;
import com.jetbrains.youtrackdb.internal.core.exception.SequenceException;
import com.jetbrains.youtrackdb.internal.core.metadata.sequence.DBSequence;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.Test;

/**
 * Tests for database sequence operations via SQL commands.
 *
 * @since 3/5/2015
 */
public class SQLDBSequenceTest extends BaseDBJUnit5Test {
  private static final long FIRST_START = DBSequence.DEFAULT_START;
  private static final long SECOND_START = 31;

  @Test
  void trivialTest() {
    testSequence("seqSQL1", DBSequence.SEQUENCE_TYPE.ORDERED);
    testSequence("seqSQL2", DBSequence.SEQUENCE_TYPE.CACHED);
  }

  private void testSequence(String sequenceName, DBSequence.SEQUENCE_TYPE sequenceType) {

    session.execute("CREATE SEQUENCE " + sequenceName + " TYPE " + sequenceType).close();

    CommandExecutionException err = null;
    try {
      session.execute("CREATE SEQUENCE " + sequenceName + " TYPE " + sequenceType).close();
    } catch (CommandExecutionException se) {
      err = se;
    }
    assertTrue(
        err == null || err.getMessage().toLowerCase(Locale.ENGLISH).contains("already exists"),
        "Creating a second "
            + sequenceType.toString()
            + " sequences with same name doesn't throw an exception");

    // Doing it twice to check everything works after reset
    session.begin();
    for (var i = 0; i < 2; ++i) {
      assertEquals(0L, sequenceCurrent(sequenceName));
      assertEquals(1L, sequenceNext(sequenceName));
      assertEquals(1L, sequenceCurrent(sequenceName));
      assertEquals(2L, sequenceNext(sequenceName));
      assertEquals(3L, sequenceNext(sequenceName));
      assertEquals(4L, sequenceNext(sequenceName));
      assertEquals(4L, sequenceCurrent(sequenceName));
      assertEquals(0L, sequenceReset(sequenceName));
    }
    session.commit();
  }

  private long sequenceReset(String sequenceName) {
    return sequenceSql(sequenceName, "reset()");
  }

  private long sequenceNext(String sequenceName) {
    return sequenceSql(sequenceName, "next()");
  }

  private long sequenceCurrent(String sequenceName) {
    return sequenceSql(sequenceName, "current()");
  }

  private long sequenceSql(String sequenceName, String cmd) {
    try (var ret =
        session.execute("SELECT sequence('" + sequenceName + "')." + cmd + " as value")) {
      return ret.next().getProperty("value");
    }
  }

  @Test
  void testFree() throws ExecutionException, InterruptedException {
    var sequenceManager = session.getMetadata().getSequenceLibrary();

    DBSequence seq = null;
    try {
      seq = sequenceManager.createSequence(
          "seqSQLOrdered", DBSequence.SEQUENCE_TYPE.ORDERED, null);
    } catch (DatabaseException exc) {
      fail("Unable to create sequence");
    }

    SequenceException err = null;
    try {
      sequenceManager.createSequence(
          "seqSQLOrdered", DBSequence.SEQUENCE_TYPE.ORDERED, null);
    } catch (SequenceException se) {
      err = se;
    } catch (DatabaseException exc) {
      fail("Unable to create sequence");
    }

    assertTrue(
        err == null || err.getMessage().toLowerCase(Locale.ENGLISH).contains("already exists"),
        "Creating two ordered sequences with same name doesn't throw an exception");

    var seqSame = sequenceManager.getSequence("seqSQLOrdered");
    assertEquals(seq, seqSame);

    testUsage(seq, FIRST_START);

    //
    try {
      session.begin();
      seq.updateParams(session,
          new DBSequence.CreateParams().setStart(SECOND_START).setCacheSize(13));
      session.commit();
    } catch (DatabaseException exc) {
      fail("Unable to update paramas");
    }
    testUsage(seq, SECOND_START);
  }

  private void testUsage(DBSequence seq, long reset)
      throws ExecutionException, InterruptedException {
    for (var i = 0; i < 2; ++i) {
      assertEquals(reset, seq.reset(session));
      assertEquals(reset, seq.current(session));
      assertEquals(reset + 1L, seq.next(session));
      assertEquals(reset + 1L, seq.current(session));
      assertEquals(reset + 2L, seq.next(session));
      assertEquals(reset + 3L, seq.next(session));
      assertEquals(reset + 4L, seq.next(session));
      assertEquals(reset + 4L, seq.current(session));
      assertEquals(reset, seq.reset(session));
    }
  }
}
