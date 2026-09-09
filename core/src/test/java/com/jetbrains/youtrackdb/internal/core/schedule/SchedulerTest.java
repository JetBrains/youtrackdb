package com.jetbrains.youtrackdb.internal.core.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB.LocalUserCredential;
import com.jetbrains.youtrackdb.api.YouTrackDB.PredefinedLocalRole;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.api.exception.ConcurrentModificationException;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.common.concur.NeedRetryException;
import com.jetbrains.youtrackdb.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseThreadLocalFactory;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.metadata.function.Function;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import io.netty.util.internal.ThreadLocalRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.apache.commons.configuration2.BaseConfiguration;
import org.junit.Assert;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * Tests cases for the Scheduler component.
 */
@Category(SequentialTest.class)
public class SchedulerTest {

  private static final String NEW_ADMIN_PASSWORD = "adminpwd";
  private static final String FORMATTING_WARNING =
      "Scheduled result was formatted after transaction completion";

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

  /** Full logging must not format a scheduled function result after its transaction ends. */
  @Test
  public void scheduledResultIsNotFormattedAfterTransactionCompletion() throws Exception {
    var enginesManager = YouTrackDBEnginesManager.instance();
    var previousFactory = enginesManager.getDatabaseThreadFactory();
    try (var logs = CapturedLogs.install();
        var context = createContext()) {
      var db = context.cachedPool("test", "admin", NEW_ADMIN_PASSWORD).acquire();
      runWithEventCleanup(
          context,
          "safe-result",
          () -> {
            var result = new WarnWhenFormatted();
            createResultEvent(db, "safe-result", "safeResultFunction", result);

            DbTestBase.assertWithTimeout(
                db,
                () -> assertThat(logs.completionRecords("safe-result"))
                    .anyMatch(
                        record -> record.getMessage().contains(WarnWhenFormatted.class.getName())));
            assertThat(logs.warningRecords(FORMATTING_WARNING)).isEmpty();
          });
    } finally {
      enginesManager.registerThreadDatabaseFactory(previousFactory);
    }
  }

  /** The safe completion message retains the scheduled event name and execution identifier. */
  @Test
  public void completionLogRetainsEventNameAndExecutionIdentifier() throws Exception {
    var enginesManager = YouTrackDBEnginesManager.instance();
    var previousFactory = enginesManager.getDatabaseThreadFactory();
    try (var logs = CapturedLogs.install();
        var context = createContext()) {
      var db = context.cachedPool("test", "admin", NEW_ADMIN_PASSWORD).acquire();
      runWithEventCleanup(
          context,
          "useful-completion",
          () -> {
            createResultEvent(db, "useful-completion", "usefulCompletionFunction", "done");

            DbTestBase.assertWithTimeout(
                db,
                () -> assertThat(logs.completionRecords("useful-completion"))
                    .anyMatch(record -> record.getMessage().contains("executionId=1")));
            assertThat(logs.completionRecords("useful-completion"))
                .anyMatch(
                    record -> record
                        .getMessage()
                        .contains(
                            "Scheduled event 'useful-completion' executionId=1 completed with result:"));
          });
    } finally {
      enginesManager.registerThreadDatabaseFactory(previousFactory);
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
        context.open("test", "admin", NEW_ADMIN_PASSWORD);
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
        new LocalUserCredential("admin", NEW_ADMIN_PASSWORD, PredefinedLocalRole.ADMIN));
    final var pool =
        youTrackDb.cachedPool("test", "admin", NEW_ADMIN_PASSWORD);
    var db = pool.acquire();

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
          "insert into OSchedule set name = 'test',"
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
              "update OSchedule set rule = \"0/2 * * * * ?\", function = ? where name = 'test'",
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
          db.execute("delete from OSchedule where name = 'test'", func.getIdentity()).close();
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

  private static void createLogEvent(DatabaseSessionEmbedded db) {
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

  private static void runWithEventCleanup(
      YouTrackDBImpl context, String eventName, TestBody body) throws Exception {
    Throwable bodyFailure = null;
    try {
      body.run();
    } catch (Exception | Error failure) {
      bodyFailure = failure;
      throw failure;
    } finally {
      try {
        removeEventInFreshSession(context, eventName);
      } catch (RuntimeException | Error cleanupFailure) {
        if (bodyFailure == null) {
          throw cleanupFailure;
        }
        bodyFailure.addSuppressed(cleanupFailure);
      }
    }
  }

  private static void removeEventInFreshSession(YouTrackDBImpl context, String eventName) {
    try {
      removeReloadedEvent(context, eventName);
    } catch (ConcurrentModificationException versionConflict) {
      // The timer can advance the stored version between reload and deletion. Retry once with
      // another reload. A second conflict and every other failure still fail the test.
      removeReloadedEvent(context, eventName);
    }
  }

  private static void removeReloadedEvent(YouTrackDBImpl context, String eventName) {
    try (var cleanupDb = context.open("test", "admin", NEW_ADMIN_PASSWORD)) {
      var reloadedEvent =
          cleanupDb.computeInTx(
              transaction -> {
                try (var result =
                    transaction.query(
                        "select from " + ScheduledEvent.CLASS_NAME + " where name = ?",
                        eventName)) {
                  var eventEntity =
                      (EntityImpl) result.stream().findFirst().orElseThrow().asEntity();
                  return new ScheduledEvent(eventEntity, cleanupDb);
                }
              });
      cleanupDb.getSharedContext().getScheduler().removeEventInternal(eventName);
      cleanupDb.executeInTx(transaction -> reloadedEvent.delete(cleanupDb));
    }
  }

  @FunctionalInterface
  private interface TestBody {

    void run() throws Exception;
  }

  private static void createResultEvent(
      DatabaseSessionEmbedded db, String eventName, String functionName, Object result) {
    var function =
        db.computeInTx(
            transaction -> {
              var created = db.getMetadata().getFunctionLibrary().createFunction(functionName);
              created.setLanguage("SQL");
              created.setCode("select 1");
              created.setParameters(List.of());
              created.save(db);
              return created;
            });
    db.executeInTx(
        transaction -> new ScheduledEventBuilder()
            .setName(eventName)
            .setRule("0/1 * * * * ?")
            .setFunction(function)
            .setArguments(Map.of())
            .build(db));
    db.getMetadata()
        .getScheduler()
        .getEvent(eventName)
        .getFunction()
        .setCallback(arguments -> result);
  }

  private static Function createFunction(DatabaseSessionEmbedded db) {
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

  private static final class WarnWhenFormatted {

    @Override
    public String toString() {
      Logger.getLogger(WarnWhenFormatted.class.getName()).warning(FORMATTING_WARNING);
      return "unsafe-result";
    }
  }

  private static final class CapturedLogs implements AutoCloseable {

    private final Logger rootLogger;
    private final Level previousLevel;
    private final CapturingHandler handler;

    private CapturedLogs(Logger rootLogger, Level previousLevel, CapturingHandler handler) {
      this.rootLogger = rootLogger;
      this.previousLevel = previousLevel;
      this.handler = handler;
    }

    private static CapturedLogs install() {
      var rootLogger = Logger.getLogger("");
      var handler = new CapturingHandler();
      handler.setLevel(Level.ALL);
      var previousLevel = rootLogger.getLevel();
      rootLogger.addHandler(handler);
      rootLogger.setLevel(Level.ALL);
      return new CapturedLogs(rootLogger, previousLevel, handler);
    }

    private List<LogRecord> completionRecords(String eventName) {
      return handler.records.stream()
          .filter(record -> record.getMessage().contains("Scheduled event '" + eventName + "'"))
          .filter(record -> record.getMessage().contains("completed with result:"))
          .toList();
    }

    private List<LogRecord> warningRecords(String marker) {
      return handler.records.stream()
          .filter(record -> record.getLevel().intValue() >= Level.WARNING.intValue())
          .filter(record -> record.getMessage().contains(marker))
          .toList();
    }

    @Override
    public void close() {
      rootLogger.removeHandler(handler);
      rootLogger.setLevel(previousLevel);
    }
  }

  private static final class CapturingHandler extends Handler {

    private final List<LogRecord> records = new CopyOnWriteArrayList<>();

    @Override
    public void publish(LogRecord record) {
      records.add(record);
    }

    @Override
    public void flush() {
    }

    @Override
    public void close() {
    }
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
    public DatabaseSessionEmbedded getThreadDatabase() {
      return context.cachedPool(database, username, password).acquire();
    }
  }
}
