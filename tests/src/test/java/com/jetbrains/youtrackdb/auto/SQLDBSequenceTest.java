package com.jetbrains.youtrackdb.auto;

import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.exception.DatabaseException;
import com.jetbrains.youtrackdb.internal.core.exception.SequenceException;
import com.jetbrains.youtrackdb.internal.core.metadata.sequence.DBSequence;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * Tests for database sequence operations via SQL commands.
 *
 * @since 3/5/2015
 */
@Test
public class SQLDBSequenceTest extends BaseDBTest {
  private static final long FIRST_START = DBSequence.DEFAULT_START;
  private static final long SECOND_START = 31;

  @Test
  public void trivialTest() {
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
    Assert.assertTrue(
        err == null || err.getMessage().toLowerCase(Locale.ENGLISH).contains("already exists"),
        "Creating a second "
            + sequenceType.toString()
            + " sequences with same name doesn't throw an exception");

    // Doing it twice to check everything works after reset
    session.begin();
    for (var i = 0; i < 2; ++i) {
      Assert.assertEquals(sequenceCurrent(sequenceName), 0L);
      Assert.assertEquals(sequenceNext(sequenceName), 1L);
      Assert.assertEquals(sequenceCurrent(sequenceName), 1L);
      Assert.assertEquals(sequenceNext(sequenceName), 2L);
      Assert.assertEquals(sequenceNext(sequenceName), 3L);
      Assert.assertEquals(sequenceNext(sequenceName), 4L);
      Assert.assertEquals(sequenceCurrent(sequenceName), 4L);
      Assert.assertEquals(sequenceReset(sequenceName), 0L);
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
  public void testFree() throws ExecutionException, InterruptedException {
    var sequenceManager = session.getMetadata().getSequenceLibrary();

    DBSequence seq = null;
    try {
      seq = sequenceManager.createSequence("seqSQLOrdered", DBSequence.SEQUENCE_TYPE.ORDERED, null);
    } catch (DatabaseException exc) {
      Assert.fail("Unable to create sequence");
    }

    SequenceException err = null;
    try {
      sequenceManager.createSequence("seqSQLOrdered", DBSequence.SEQUENCE_TYPE.ORDERED, null);
    } catch (SequenceException se) {
      err = se;
    } catch (DatabaseException exc) {
      Assert.fail("Unable to create sequence");
    }

    Assert.assertTrue(
        err == null || err.getMessage().toLowerCase(Locale.ENGLISH).contains("already exists"),
        "Creating two ordered sequences with same name doesn't throw an exception");

    var seqSame = sequenceManager.getSequence("seqSQLOrdered");
    Assert.assertEquals(seqSame, seq);

    testUsage(seq, FIRST_START);

    //
    try {
      session.begin();
      seq.updateParams(session,
          new DBSequence.CreateParams().setStart(SECOND_START).setCacheSize(13));
      session.commit();
    } catch (DatabaseException exc) {
      Assert.fail("Unable to update paramas");
    }
    testUsage(seq, SECOND_START);
  }

  private void testUsage(DBSequence seq, long reset)
      throws ExecutionException, InterruptedException {
    for (var i = 0; i < 2; ++i) {
      Assert.assertEquals(seq.reset(session), reset);
      Assert.assertEquals(seq.current(session), reset);
      Assert.assertEquals(seq.next(session), reset + 1L);
      Assert.assertEquals(seq.current(session), reset + 1L);
      Assert.assertEquals(seq.next(session), reset + 2L);
      Assert.assertEquals(seq.next(session), reset + 3L);
      Assert.assertEquals(seq.next(session), reset + 4L);
      Assert.assertEquals(seq.current(session), reset + 4L);
      Assert.assertEquals(seq.reset(session), reset);
    }
  }
}
