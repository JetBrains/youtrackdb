package com.jetbrains.youtrack.db.internal.core.sql.executor;

import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import org.junit.Assert;
import org.junit.Test;

/**
 *
 */
public class ResultSetTest extends DbTestBase {

  @Test
  public void testResultStream() {
    var rs = new InternalResultSet(session);
    for (var i = 0; i < 10; i++) {
      var item = new ResultInternal(session);
      item.setProperty("i", i);
      rs.add(item);
    }
    var result =
        rs.stream().map(x -> (int) x.getProperty("i")).reduce(Integer::sum);
    Assert.assertTrue(result.isPresent());
    Assert.assertEquals(45, result.get().intValue());
  }

  @Test(expected = IllegalStateException.class)
  public void testResultEmptyVertexStream() {
    var rs = new InternalResultSet(session);
    for (var i = 0; i < 10; i++) {
      var item = new ResultInternal(session);
      item.setProperty("i", i);
      rs.add(item);
    }
    rs.vertexStream().map(x -> (int) x.getProperty("i")).reduce(Integer::sum);
  }

  @Test(expected = IllegalStateException.class)
  public void testResultEdgeVertexStream() {
    var rs = new InternalResultSet(session);
    for (var i = 0; i < 10; i++) {
      var item = new ResultInternal(session);
      item.setProperty("i", i);
      rs.add(item);
    }
    rs.vertexStream().map(x -> (int) x.getProperty("i")).reduce(Integer::sum);
  }

  @Test
  public void testResultSetAutoClose() {
    // Without result set auto close, the following code will cause OOM

    // disable warning
    withOverriddenConfig(GlobalConfiguration.QUERY_RESULT_SET_OPEN_WARNING_THRESHOLD, 100,
        session -> {

          final var clazz = session.getSchema().createClass("ResultSetTest_testAutoClose");
          for (var i = 0; i < 10; i++) {
            final var name = "foo" + i;
            session.executeInTx(tx -> tx.newEntity(clazz).setString("name", name));
          }

          session.executeInTx(tx -> {
            for (var i = 0; i < 5_000_000; i++) {
              tx.query("SELECT FROM " + clazz.getName());
            }
          });
        });
  }
}
