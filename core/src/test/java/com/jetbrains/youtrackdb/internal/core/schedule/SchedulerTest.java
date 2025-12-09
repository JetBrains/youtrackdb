package com.jetbrains.youtrackdb.internal.core.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB.PredefinedRole;
import com.jetbrains.youtrackdb.api.YouTrackDB.UserCredential;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.common.concur.NeedRetryException;
import com.jetbrains.youtrackdb.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseThreadLocalFactory;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.metadata.function.Function;
import io.netty.util.internal.ThreadLocalRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.configuration2.BaseConfiguration;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests cases for the Scheduler component.
 */
public class SchedulerTest {

  private static final String NEW_ADMIN_PASSWORD = "adminpwd";

  @Test
  public void scheduleSQLFunction() throws Exception {
    try (var context = createContext()) {
      var db =
          context.cachedPool("test", "admin", NEW_ADMIN_PASSWORD).acquire();
      createLogEvent(db);

      DbTestBase.assertWithTimeout(
          db,
          () -> {
            var count = getLogCounter(db);
            Assert.assertTrue(count >= 2 && count <= 3);
          });
    }
  }

  @Test
  public void scheduleWithDbClosed() throws Exception {
    var context = createContext();
    {
      var db = context.open("test", "admin", NEW_ADMIN_PASSWORD);
      createLogEvent(db);
      db.close();
    }

    var db = context.open("test", "admin", NEW_ADMIN_PASSWORD);
    DbTestBase.assertWithTimeout(
        db,
        () -> {
          var count = getLogCounter(db);
          Assert.assertTrue(count >= 2);
        });

    db.close();
    context.close();
  }

  @Test
  public void eventLifecycle() throws Exception {
    try (var context = createContext()) {
      var db =
          context.cachedPool("test", "admin", NEW_ADMIN_PASSWORD).acquire();
      createLogEvent(db);

      Thread.sleep(2000);

      db.getMetadata().getScheduler().removeEvent(db, "test");

      assertThat(db.getMetadata().getScheduler().getEvents()).isEmpty();

      assertThat(db.getMetadata().getScheduler().getEvent("test")).isNull();

      // remove again
      db.getMetadata().getScheduler().removeEvent(db, "test");

      Thread.sleep(3000);

      var count = getLogCounter(db);

      Assert.assertTrue(count >= 1 && count <= 3);
    }
  }

  @Test
  public void eventSavedAndLoaded() throws Exception {
    var context = createContext();
    var db =
        (DatabaseSessionInternal) context.open("test", "admin", NEW_ADMIN_PASSWORD);
    createLogEvent(db);
    db.close();

    Thread.sleep(1000);

    final var db2 = context.open("test", "admin", NEW_ADMIN_PASSWORD);
    try {
      Thread.sleep(4000);
      var count = getLogCounter(db2);
      Assert.assertTrue(count >= 2);

    } finally {
      db2.close();
      context.close();
    }
  }

  @Test
  public void testScheduleEventWithMultipleActiveDatabaseConnections() {
    var config = new BaseConfiguration();
    config.setProperty(GlobalConfiguration.CREATE_DEFAULT_USERS.getKey(), false);
    config.setProperty(GlobalConfiguration.DB_POOL_MAX.getKey(), 1);

    final var youTrackDb =
        (YouTrackDBImpl) YourTracks.instance(
            DbTestBase.getBaseDirectoryPathStr(getClass()),
            config);
    if (youTrackDb.exists("test")) {
      youTrackDb.drop("test");
    }

    youTrackDb.create("test", DatabaseType.DISK,
        new UserCredential("admin", NEW_ADMIN_PASSWORD, PredefinedRole.ADMIN));
    final var pool =
        youTrackDb.cachedPool("test", "admin", NEW_ADMIN_PASSWORD);
    var db = (DatabaseSessionInternal) pool.acquire();

    createLogEvent(db);
    youTrackDb.close();
  }

  @Test
  public void eventBySQL() throws Exception {
    var context = createContext();
    try (context;
        var db = context.open("test", "admin", NEW_ADMIN_PASSWORD)) {
      var func = createFunction(db);
      db.begin();
      db.execute(
              "insert into oschedule set name = 'test',"
                  + " function = ?, rule = \"0/1 * * * * ?\", arguments = {\"note\": \"test\"}",
              func.getIdentity())
          .close();
      db.commit();

      DbTestBase.assertWithTimeout(
          db,
          () -> {
            long count = getLogCounter(db);
            Assert.assertTrue(count >= 2);
          });

      final long count = getLogCounter(db);
      Assert.assertTrue(count >= 2);

      var retryCount = 10;
      while (true) {
        try {
          db.begin();
          db.execute(
                  "update oschedule set rule = \"0/2 * * * * ?\", function = ? where name = 'test'",
                  func.getIdentity())
              .close();
          db.commit();
          break;
        } catch (NeedRetryException e) {
          retryCount--;
          //noinspection BusyWait
          Thread.sleep(10);
        }
        Assert.assertTrue(retryCount >= 0);
      }

      DbTestBase.assertWithTimeout(
          db,
          () -> {
            long newCount = getLogCounter(db);
            Assert.assertTrue(newCount - count > 1);
          });

      long newCount = getLogCounter(db);
      Assert.assertTrue(newCount - count > 1);

      retryCount = 10;
      while (true) {
        try {
          // DELETE
          db.begin();
          db.execute("delete from oschedule where name = 'test'", func.getIdentity()).close();
          db.commit();
          break;
        } catch (NeedRetryException e) {
          retryCount--;
          //noinspection BusyWait
          Thread.sleep(10);
        }
        Assert.assertTrue(retryCount >= 0);
      }

      DbTestBase.assertWithTimeout(
          db,
          () -> {
            var counter = getLogCounter(db);
            Assert.assertTrue(counter - newCount <= 1);
          });
    }
  }

  private YouTrackDBImpl createContext() {
    var youTrackDB = (YouTrackDBImpl) YourTracks.instance(
        DbTestBase.getBaseDirectoryPathStr(getClass()) + ThreadLocalRandom.current().nextInt());
    if (youTrackDB.exists("test")) {
      youTrackDB.drop("test");
    }

    youTrackDB.create("test",
        DatabaseType.MEMORY, "admin", NEW_ADMIN_PASSWORD, "admin");

    YouTrackDBEnginesManager.instance()
        .registerThreadDatabaseFactory(
            new TestScheduleDatabaseFactory(
                youTrackDB, "test", "admin", NEW_ADMIN_PASSWORD));

    return youTrackDB;
  }

  private static void createLogEvent(DatabaseSessionInternal db) {
    var func = createFunction(db);

    db.executeInTx(transaction -> {
      Map<Object, Object> args = new HashMap<>();
      args.put("note", "test");

      new ScheduledEventBuilder()
          .setName("test")
          .setRule("0/1 * * * * ?")
          .setFunction(func)
          .setArguments(args)
          .build(db);
    });
  }

  private static Function createFunction(DatabaseSessionInternal db) {
    db.getMetadata().getSchema().createClass("scheduler_log");

    return db.computeInTx(
        transaction -> {
          var func = db.getMetadata().getFunctionLibrary().createFunction("logEvent");
          func.setLanguage("SQL");
          func.setCode("insert into scheduler_log set timestamp = sysdate(), note = :note");
          final List<String> pars = new ArrayList<>();
          pars.add("note");
          func.setParameters(pars);
          func.save(db);
          return func;
        });
  }

  private static Long getLogCounter(final DatabaseSessionEmbedded session) {
    return session.computeInTx(transaction -> {
      var resultSet =
          transaction.query("select count(*) as count from scheduler_log where note = 'test'");
      var result = resultSet.stream().findFirst().orElseThrow();
      var count = result.<Long>getProperty("count");
      resultSet.close();
      return count;
    });
  }

  private static class TestScheduleDatabaseFactory implements DatabaseThreadLocalFactory {

    private final YouTrackDBImpl context;
    private final String database;
    private final String username;
    private final String password;

    public TestScheduleDatabaseFactory(
        YouTrackDBImpl context, String database, String username, String password) {
      this.context = context;
      this.database = database;
      this.username = username;
      this.password = password;
    }

    @Override
    public DatabaseSessionInternal getThreadDatabase() {
      return context.cachedPool(database, username, password).acquire();
    }
  }
}
