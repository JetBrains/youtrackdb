package com.jetbrains.youtrack.db.internal.server.query;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.common.query.BasicLiveQueryResultListener;
import com.jetbrains.youtrack.db.api.common.query.BasicResult;
import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.internal.server.BaseServerMemoryDatabase;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nonnull;
import org.junit.Assert;
import org.junit.Test;

public class RemoteGraphLiveQueryTest extends BaseServerMemoryDatabase {

  public void beforeTest() {
    super.beforeTest();
    session.createClassIfNotExist("FirstV", "V");
    session.createClassIfNotExist("SecondV", "V");
    session.createClassIfNotExist("TestEdge", "E");
  }

  @Test
  public void testLiveQuery() throws InterruptedException {

    session.begin();
    session.execute("create vertex FirstV set id = '1'").close();
    session.execute("create vertex SecondV set id = '2'").close();
    session.commit();

    session.begin();
    try (var resultSet =
        session.execute(
            "create edge TestEdge  from (select from FirstV) to (select from SecondV)")) {
      var result = resultSet.stream().iterator().next();

      Assert.assertTrue(result.isStatefulEdge());
    }
    session.commit();

    var l = new AtomicLong(0);

    context.live(session.getDatabaseName(), "admin", "adminpwd",
        "select from SecondV",
        new BasicLiveQueryResultListener() {

          @Override
          public void onUpdate(@Nonnull DatabaseSession session, @Nonnull BasicResult before,
              @Nonnull BasicResult after) {
            l.incrementAndGet();
          }

          @Override
          public void onError(@Nonnull DatabaseSession session, @Nonnull BaseException exception) {
          }

          @Override
          public void onEnd(@Nonnull DatabaseSession session) {
          }

          @Override
          public void onDelete(@Nonnull DatabaseSession session, @Nonnull BasicResult data) {
          }

          @Override
          public void onCreate(@Nonnull DatabaseSession session, @Nonnull BasicResult data) {
          }
        },
        new HashMap<String, String>());

    session.begin();
    session.execute("update SecondV set id = 3");
    session.commit();

    Thread.sleep(100);

    Assert.assertEquals(1L, l.get());
  }
}
