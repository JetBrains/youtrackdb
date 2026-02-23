package com.jetbrains.youtrackdb.auto;

import com.jetbrains.youtrackdb.internal.core.exception.SequenceException;
import com.jetbrains.youtrackdb.internal.core.metadata.sequence.DBSequence;
import com.jetbrains.youtrackdb.internal.core.metadata.sequence.DBSequence.SEQUENCE_TYPE;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * @since 3/2/2015
 */
public class DBSequenceTest extends BaseDBTest {
  private static final long FIRST_START = DBSequence.DEFAULT_START;
  private static final long SECOND_START = 31;

  @Test
  public void trivialTest() throws ExecutionException, InterruptedException {
    testSequence("seq1", SEQUENCE_TYPE.ORDERED);
    testSequence("seq2", SEQUENCE_TYPE.CACHED);
  }

  private void testSequence(String sequenceName, SEQUENCE_TYPE sequenceType) {
    var sequenceLibrary = session.getMetadata().getSequenceLibrary();

    var seq = sequenceLibrary.createSequence(sequenceName, sequenceType, null);

    SequenceException err = null;
    try {
      sequenceLibrary.createSequence(sequenceName, sequenceType, null);
    } catch (SequenceException se) {
      err = se;
    }
    Assert.assertTrue(
        err == null || err.getMessage().toLowerCase(Locale.ENGLISH).contains("already exists"),
        "Creating a second "
            + sequenceType.toString()
            + " sequences with same name doesn't throw an exception");

    var seqSame = sequenceLibrary.getSequence(sequenceName);
    Assert.assertEquals(seqSame, seq);

    session.begin();
    // Doing it twice to check everything works after reset
    for (var i = 0; i < 2; ++i) {
      Assert.assertEquals(seq.next(session), 1L);
      Assert.assertEquals(seq.current(session), 1L);
      Assert.assertEquals(seq.next(session), 2L);
      Assert.assertEquals(seq.next(session), 3L);
      Assert.assertEquals(seq.next(session), 4L);
      Assert.assertEquals(seq.current(session), 4L);
      Assert.assertEquals(seq.reset(session), 0L);
    }
    session.commit();
  }

  @Test
  public void testOrdered() throws ExecutionException, InterruptedException {
    var sequenceManager = session.getMetadata().getSequenceLibrary();

    var seq = sequenceManager.createSequence("seqOrdered", SEQUENCE_TYPE.ORDERED, null);

    SequenceException err = null;
    try {
      sequenceManager.createSequence("seqOrdered", SEQUENCE_TYPE.ORDERED, null);
    } catch (SequenceException se) {
      err = se;
    }
    Assert.assertTrue(
        err == null || err.getMessage().toLowerCase(Locale.ENGLISH).contains("already exists"),
        "Creating two ordered sequences with same name doesn't throw an exception");

    var seqSame = sequenceManager.getSequence("seqOrdered");
    Assert.assertEquals(seqSame, seq);

    testUsage(seq, FIRST_START);

    //
    session.begin();
    seq.updateParams(session,
        new DBSequence.CreateParams().setStart(SECOND_START).setCacheSize(13));
    session.commit();

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
