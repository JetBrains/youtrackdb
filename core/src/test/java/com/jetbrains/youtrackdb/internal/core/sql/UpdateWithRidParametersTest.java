package com.jetbrains.youtrackdb.internal.core.sql;

import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Schema;
import java.util.List;
import org.junit.Test;

public class UpdateWithRidParametersTest extends DbTestBase {

  @Test
  public void testRidParameters() {

    Schema schm = session.getMetadata().getSchema();
    schm.createClass("testingClass");
    schm.createClass("testingClass2");

    var rid = session.computeInTx(transaction -> {
      transaction.execute("INSERT INTO testingClass SET id = ?", 123).close();

      transaction.execute("INSERT INTO testingClass2 SET id = ?", 456).close();
      RID recordId;
      try (var docs = session.query("SELECT FROM testingClass2 WHERE id = ?", 456)) {
        recordId = docs.next().getProperty("@rid");
      }

      // This does not work. It silently adds a null instead of the RID.
      transaction.execute("UPDATE testingClass set linkedlist = linkedlist || ?", recordId).close();

      // This does work.
      transaction.execute(
              "UPDATE testingClass set linkedlist = linkedlist || " + recordId.toString())
          .close();
      return recordId;
    });

    List<RID> lst;
    try (var docs = session.query("SELECT FROM testingClass WHERE id = ?", 123)) {
      lst = docs.next().getProperty("linkedlist");
    }

    assertEquals(rid, lst.get(0));
    assertEquals(rid, lst.get(1));
  }
}
