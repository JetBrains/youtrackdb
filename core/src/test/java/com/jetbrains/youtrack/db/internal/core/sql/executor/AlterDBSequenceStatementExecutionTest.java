package com.jetbrains.youtrack.db.internal.core.sql.executor;

import com.jetbrains.youtrack.db.api.exception.DatabaseException;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.core.metadata.sequence.DBSequence;
import org.junit.Assert;
import org.junit.Test;

/**
 *
 */
public class AlterDBSequenceStatementExecutionTest extends DbTestBase {
  @Test
  public void testSetIncrement() {
    var sequenceName = "testSetStart";
    session.executeInTx(
        () -> {
          try {
            session.getMetadata()
                .getSequenceLibrary()
                .createSequence(
                    sequenceName, DBSequence.SEQUENCE_TYPE.ORDERED, new DBSequence.CreateParams());
          } catch (DatabaseException exc) {
            Assert.fail("Failed to create sequence");
          }
        });

    session.begin();
    var result = session.execute("alter sequence " + sequenceName + " increment 20");
    Assert.assertNotNull(result);
    Assert.assertTrue(result.hasNext());
    var next = result.next();
    Assert.assertNotNull(next);
    Assert.assertEquals((Object) 20, next.getProperty("increment"));
    result.close();
    session.commit();

    session.executeInTx(
        () -> {
          var seq = session.getMetadata().getSequenceLibrary().getSequence(sequenceName);
          Assert.assertNotNull(seq);
          try {
            Assert.assertEquals(20, seq.next(session));
          } catch (DatabaseException exc) {
            Assert.fail("Failed to call next");
          }
        });
  }
}
