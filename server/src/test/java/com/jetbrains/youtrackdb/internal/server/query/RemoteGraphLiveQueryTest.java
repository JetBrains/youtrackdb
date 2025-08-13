package com.jetbrains.youtrackdb.internal.server.query;

import com.jetbrains.youtrackdb.api.exception.BaseException;
import com.jetbrains.youtrackdb.api.remote.RemoteDatabaseSession;
import com.jetbrains.youtrackdb.api.remote.query.RemoteLiveQueryResultListener;
import com.jetbrains.youtrackdb.api.remote.query.RemoteResult;
import com.jetbrains.youtrackdb.internal.server.BaseServerMemoryDatabase;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nonnull;
import org.junit.Assert;
import org.junit.Test;

public class RemoteGraphLiveQueryTest extends BaseServerMemoryDatabase {

  @Override
  public void beforeTest() {
    super.beforeTest();

    session.executeSQLScript("""
        create class FirstV if not exists extends V;
        create class SecondV if not exists extends V;
        create class TestEdge if not exists extends E;
        """);
  }

  @Test
  public void testLiveQuery() throws InterruptedException {
    session.command("begin");
    session.execute("create vertex FirstV set id = '1'").close();
    session.execute("create vertex SecondV set id = '2'").close();
    session.command("commit");

    try (var resultSet =
        session.computeSQLScript(
            """
                begin;
                let $res = create edge TestEdge from (select from FirstV) to (select from SecondV);
                commit;
                return $res;
                """)) {
      var result = resultSet.stream().iterator().next();
      Assert.assertTrue(result.isIdentifiable());
    }

    var l = new AtomicLong(0);

    context.live(session.getDatabaseName(), "admin", "adminpwd",
        "select from SecondV",
        new RemoteLiveQueryResultListener() {

          @Override
          public void onUpdate(@Nonnull RemoteDatabaseSession session, @Nonnull RemoteResult before,
              @Nonnull RemoteResult after) {
            l.incrementAndGet();
          }

          @Override
          public void onError(@Nonnull RemoteDatabaseSession session,
              @Nonnull BaseException exception) {
          }

          @Override
          public void onEnd(@Nonnull RemoteDatabaseSession session) {
          }

          @Override
          public void onDelete(@Nonnull RemoteDatabaseSession session, @Nonnull RemoteResult data) {
          }

          @Override
          public void onCreate(@Nonnull RemoteDatabaseSession session, @Nonnull RemoteResult data) {
          }
        },
        new HashMap<String, String>());

    session.executeSQLScript("""
        begin;
        update SecondV set id = 3;
        commit;
        """);

    Thread.sleep(100);

    Assert.assertEquals(1L, l.get());
  }
}
