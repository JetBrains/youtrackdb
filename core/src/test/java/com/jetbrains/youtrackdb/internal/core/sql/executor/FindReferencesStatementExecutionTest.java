package com.jetbrains.youtrackdb.internal.core.sql.executor;

import static com.jetbrains.youtrackdb.internal.core.sql.executor.ExecutionPlanPrintUtils.printExecutionPlan;

import com.jetbrains.youtrackdb.api.record.RID;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.HashSet;
import java.util.Set;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;

/**
 *
 */
public class FindReferencesStatementExecutionTest extends DbTestBase {

  @Test
  @Ignore
  public void testLink() {
    var name = "testLink1";
    var name2 = "testLink2";
    session.getMetadata().getSchema().createClass(name);
    session.getMetadata().getSchema().createClass(name2);

    session.begin();
    var linked = (EntityImpl) session.newEntity(name);
    linked.setProperty("foo", "bar");

    session.commit();

    Set<RID> ridsToMatch = new HashSet<>();

    for (var i = 0; i < 10; i++) {
      session.begin();
      var activeTx = session.getActiveTransaction();
      linked = activeTx.load(linked);
      var doc = (EntityImpl) session.newEntity(name2);
      doc.setProperty("counter", i);
      if (i % 2 == 0) {
        doc.setProperty("link", linked);
      }

      session.commit();
      if (i % 2 == 0) {
        ridsToMatch.add(doc.getIdentity());
      }
    }

    session.begin();
    var result = session.query("find references " + linked.getIdentity());

    printExecutionPlan(result);

    for (var i = 0; i < 5; i++) {
      Assert.assertTrue(result.hasNext());
      var next = result.next();
      ridsToMatch.remove(next.getProperty("referredBy"));
    }

    Assert.assertFalse(result.hasNext());
    Assert.assertTrue(ridsToMatch.isEmpty());
    result.close();
    session.commit();
  }
}
