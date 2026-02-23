package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.exception.DatabaseException;
import com.jetbrains.youtrackdb.internal.core.metadata.sequence.DBSequence;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests execution of DROP SEQUENCE SQL statements.
 */
public class DropDBSequenceStatementExecutionTest extends DbTestBase {

  @Test
  public void testPlain() {
    var name = "testPlain";
    try {
      session.getMetadata()
          .getSequenceLibrary()
          .createSequence(name, DBSequence.SEQUENCE_TYPE.CACHED, new DBSequence.CreateParams());
    } catch (DatabaseException exc) {
      Assert.fail("Creating sequence failed");
    }

    Assert.assertNotNull(session.getMetadata().getSequenceLibrary().getSequence(name));
    session.begin();
    var result = session.execute("drop sequence " + name);
    Assert.assertTrue(result.hasNext());
    var next = result.next();
    Assert.assertEquals("drop sequence", next.getProperty("operation"));
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();

    Assert.assertNull(session.getMetadata().getSequenceLibrary().getSequence(name));
  }

  @Test
  public void testNonExisting() {
    var name = "testNonExisting";
    var lib = session.getMetadata().getSequenceLibrary();
    Assert.assertNull(lib.getSequence(name));
    try {
      session.execute("drop sequence " + name).close();
      Assert.fail();
    } catch (CommandExecutionException ex1) {

    } catch (Exception ex1) {
      Assert.fail();
    }
  }

  @Test
  public void testNonExistingWithIfExists() {
    var name = "testNonExistingWithIfExists";
    var lib = session.getMetadata().getSequenceLibrary();
    Assert.assertNull(lib.getSequence(name));

    var result = session.execute("drop sequence " + name + " if exists");
    Assert.assertFalse(result.hasNext());
    result.close();

    try {
      session.getMetadata()
          .getSequenceLibrary()
          .createSequence(name, DBSequence.SEQUENCE_TYPE.CACHED, new DBSequence.CreateParams());
    } catch (DatabaseException exc) {
      exc.printStackTrace();
      Assert.fail("Creating sequence failed");
    }

    Assert.assertNotNull(session.getMetadata().getSequenceLibrary().getSequence(name));
    session.begin();
    result = session.execute("drop sequence " + name + " if exists");
    Assert.assertTrue(result.hasNext());
    var next = result.next();
    Assert.assertEquals("drop sequence", next.getProperty("operation"));
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();

    Assert.assertNull(session.getMetadata().getSequenceLibrary().getSequence(name));
  }
}
