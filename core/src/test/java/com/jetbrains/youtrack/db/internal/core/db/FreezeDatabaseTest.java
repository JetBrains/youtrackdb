package com.jetbrains.youtrack.db.internal.core.db;

import com.jetbrains.youtrack.db.api.exception.ModificationOperationProhibitedException;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import org.junit.Assert;
import org.junit.Test;

public class FreezeDatabaseTest extends DbTestBase {

  @Test
  public void testFreezeAndCommitExpectException() {
    session.freeze(true);

    var tx = session.begin();
    tx.newEntity();
    try {
      session.commit();
      Assert.fail("commit should fail with ModificationOperationProhibitedException after freeze");
    } catch (ModificationOperationProhibitedException ex) {
      // ok
    }

    session.release();

    tx = session.begin();
    tx.newEntity();
    session.commit();
  }

}
