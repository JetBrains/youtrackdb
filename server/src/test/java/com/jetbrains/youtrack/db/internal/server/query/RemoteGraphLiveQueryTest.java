package com.jetbrains.youtrack.db.internal.server.query;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.api.query.LiveQueryResultListener;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.server.BaseServerMemoryDatabase;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nonnull;
import org.junit.Assert;
import org.junit.Test;

public class RemoteGraphLiveQueryTest extends BaseServerMemoryDatabase {

  public void beforeTest() {
    super.beforeTest();
    db.createClassIfNotExist("FirstV", "V");
    db.createClassIfNotExist("SecondV", "V");
    db.createClassIfNotExist("TestEdge", "E");
  }

  @Test
  public void testLiveQuery() throws InterruptedException {

    db.begin();
    db.execute("create vertex FirstV set id = '1'").close();
    db.execute("create vertex SecondV set id = '2'").close();
    db.commit();

    db.begin();
    try (var resultSet =
        db.execute("create edge TestEdge  from (select from FirstV) to (select from SecondV)")) {
      var result = resultSet.stream().iterator().next();

      Assert.assertTrue(result.isStatefulEdge());
    }
    db.commit();

    var l = new AtomicLong(0);

    db.live(
        "select from SecondV",
        new LiveQueryResultListener() {

          @Override
          public void onUpdate(@Nonnull DatabaseSessionInternal session, @Nonnull Result before,
              @Nonnull Result after) {
            l.incrementAndGet();
          }

          @Override
          public void onError(@Nonnull DatabaseSession session, @Nonnull BaseException exception) {
          }

          @Override
          public void onEnd(@Nonnull DatabaseSession session) {
          }

          @Override
          public void onDelete(@Nonnull DatabaseSessionInternal session, @Nonnull Result data) {
          }

          @Override
          public void onCreate(@Nonnull DatabaseSessionInternal session, @Nonnull Result data) {
          }
        },
        new HashMap<String, String>());

    db.begin();
    db.execute("update SecondV set id = 3");
    db.commit();

    Thread.sleep(100);

    Assert.assertEquals(1L, l.get());
  }
}
