package com.jetbrains.youtrack.db.internal.core.sql.select;

import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import org.junit.Assert;
import org.junit.Test;

public class TestSqlForeach extends DbTestBase {

  @Test
  public void testForeach() {
    session.getMetadata().getSchema().createClass("Test");

    session.begin();
    var doc = ((EntityImpl) session.newEntity("Test"));
    session.commit();

    session.begin();
    var result =
        session.computeScript(
            "sql",
            "let $res = select from Test; foreach ($r in $res) { update $r set timestamp ="
                + " sysdate(); }; return $res; ");

    Assert.assertTrue(result.hasNext());

    while (result.hasNext()) {
      result.next();
    }
  }
}
