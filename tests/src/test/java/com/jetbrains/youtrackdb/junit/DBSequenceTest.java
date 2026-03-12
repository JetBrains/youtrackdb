package com.jetbrains.youtrackdb.junit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jetbrains.youtrackdb.internal.core.exception.SequenceException;
import com.jetbrains.youtrackdb.internal.core.metadata.sequence.DBSequence;
import com.jetbrains.youtrackdb.internal.core.metadata.sequence.DBSequence.SEQUENCE_TYPE;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.Test;

/**
 * Tests for database sequence creation and operations.
 *
 * @since 3/2/2015
 */
public class DBSequenceTest extends BaseDBJUnit5Test {
  private static final long FIRST_START = DBSequence.DEFAULT_START;
  private static final long SECOND_START = 31;

  @Test
  void trivialTest() throws ExecutionException, InterruptedException {
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
    assertTrue(
        err == null || err.getMessage().toLowerCase(Locale.ENGLISH).contains("already exists"),
        "Creating a second "
            + sequenceType.toString()
            + " sequences with same name doesn't throw an exception");

    var seqSame = sequenceLibrary.getSequence(sequenceName);
    assertEquals(seq, seqSame);

    session.begin();
    // Doing it twice to check everything works after reset
    for (var i = 0; i < 2; ++i) {
      assertEquals(1L, seq.next(session));
      assertEquals(1L, seq.current(session));
      assertEquals(2L, seq.next(session));
      assertEquals(3L, seq.next(session));
      assertEquals(4L, seq.next(session));
      assertEquals(4L, seq.current(session));
      assertEquals(0L, seq.reset(session));
    }
    session.commit();
  }

  @Test
  void testOrdered() throws ExecutionException, InterruptedException {
    var sequenceManager = session.getMetadata().getSequenceLibrary();

    var seq = sequenceManager.createSequence("seqOrdered", SEQUENCE_TYPE.ORDERED, null);

    SequenceException err = null;
    try {
      sequenceManager.createSequence("seqOrdered", SEQUENCE_TYPE.ORDERED, null);
    } catch (SequenceException se) {
      err = se;
    }
    assertTrue(
        err == null || err.getMessage().toLowerCase(Locale.ENGLISH).contains("already exists"),
        "Creating two ordered sequences with same name doesn't throw an exception");

    var seqSame = sequenceManager.getSequence("seqOrdered");
    assertEquals(seq, seqSame);

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
