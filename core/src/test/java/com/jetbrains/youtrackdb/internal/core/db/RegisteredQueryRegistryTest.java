package com.jetbrains.youtrackdb.internal.core.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.BaseMemoryInternalDatabase;
import com.jetbrains.youtrackdb.internal.LogRecordCollector;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.core.cache.WeakValueHashMap;
import com.jetbrains.youtrackdb.internal.core.query.RegisteredQuery;
import com.jetbrains.youtrackdb.internal.core.sql.executor.InternalResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.commons.configuration2.Configuration;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
@Category(SequentialTest.class)
public class RegisteredQueryRegistryTest extends BaseMemoryInternalDatabase {

  @Parameterized.Parameters(name = "serverMode-{0}")
  public static Object[] retentionModes() {
    return new Object[] {false, true};
  }

  private final boolean serverMode;

  public RegisteredQueryRegistryTest(boolean serverMode) {
    this.serverMode = serverMode;
  }

  @Override
  protected Configuration createConfig() {
    var config = super.createConfig();
    config.setProperty(GlobalConfiguration.QUERY_RESULT_SET_OPEN_WARNING_THRESHOLD.getKey(), 2);
    return config;
  }

  @Before
  public void openSessionWithSelectedRetentionMode() {
    // Follow session.copy() initialization, but select the registry retention policy explicitly.
    var original = session;
    var storage = original.getStorage();
    storage.open(original, null, null, original.getConfiguration());
    session = new DatabaseSessionEmbedded(storage, serverMode);
    session.init((YouTrackDBConfigImpl) original.getConfig(), original.getSharedContext());
    session.internalOpen(adminUser, adminPassword);
    original.close();
    session.activateOnCurrentThread();
    assertTrue(session.getActiveQueries().isEmpty());
  }

  @Test
  public void registryGettersExposeContractAndPreserveRetentionPolicy() {
    Map<String, RegisteredQuery> entries = session.getActiveQueries();
    assertTrue(serverMode ? entries instanceof HashMap : entries instanceof WeakValueHashMap);
    var query = new TestQuery(session, "synthetic", "synthetic description");
    session.queryStarted(query.id, query);
    RegisteredQuery entry = session.getActiveQuery(query.id);
    assertSame(query, entry);
    assertSame(query, entries.get(query.id));
    assertEquals("synthetic description", entry.getDescription());
    assertNull(session.getActiveQuery("missing"));
    query.close();
    assertEquals(1, query.closes);
    assertNull(session.getActiveQuery(query.id));
    assertTrue(entries.isEmpty());
  }

  @Test
  public void yqlResultSetKeepsPlanDescriptionAndExplicitCloseDeregisters() {
    session.begin();
    try (var result = session.query("select 42 as answer")) {
      var entries = session.getActiveQueries();
      assertEquals(1, entries.size());
      var id = entries.keySet().iterator().next();
      assertSame(result, session.getActiveQuery(id));
      assertEquals(result.getExecutionPlan().toString(), result.getDescription());
      assertEquals(42, ((Number) result.next().getProperty("answer")).intValue());
      result.close();
      assertNull(session.getActiveQuery(id));
      assertTrue(entries.isEmpty());
    } finally {
      session.rollback();
    }
  }

  @Test
  public void mixedSnapshotClosesEachEntryDespiteDeregistrationAndLeavesOtherSessionOpen() {
    session.begin();
    try (var result = session.query("select 1 as answer");
        var otherSession = openDatabase()) {
      otherSession.begin();
      try (var otherResult = otherSession.query("select 2 as answer")) {
        var first = new TestQuery(session, "first", "first description");
        var second = new TestQuery(session, "second", "second description");
        session.queryStarted(first.id, first);
        session.queryStarted(second.id, second);
        assertEquals(3, session.getActiveQueries().size());
        assertEquals(1, otherSession.getActiveQueries().size());

        session.closeActiveQueries();

        assertTrue(result.isClosed());
        assertFalse(result.hasNext());
        assertEquals(1, first.closes);
        assertEquals(1, second.closes);
        assertTrue(session.getActiveQueries().isEmpty());
        assertFalse(otherResult.isClosed());
        assertSame(otherResult, otherSession.getActiveQueries().values().iterator().next());
        assertEquals(2, ((Number) otherResult.next().getProperty("answer")).intValue());
        session.closeActiveQueries();
        assertEquals(1, first.closes);
        assertEquals(1, second.closes);
      } finally {
        otherSession.rollback();
      }
    } finally {
      session.rollback();
    }
  }

  @Test
  public void warningUsesPreInsertionCountAndExistingEntryDescriptions() {
    session.begin();
    try (var logs = LogRecordCollector.attachTo(DatabaseSessionEmbedded.class);
        var result = session.query("select 3 as answer")) {
      var existing = new TestQuery(session, "existing", "existing synthetic description");
      var incoming = new TestQuery(session, "incoming", "incoming synthetic description");
      session.queryStarted(existing.id, existing);
      assertEquals(2, session.getActiveQueries().size());
      assertTrue(logs.messages().isEmpty());

      session.queryStarted(incoming.id, incoming);

      assertEquals(3, session.getActiveQueries().size());
      assertSame(incoming, session.getActiveQuery(incoming.id));
      assertEquals(List.of(warning(session, 2)), logs.messages().stream()
          .filter(message -> message.startsWith("WARNING ")).toList());
      assertTrue(logs.messages().contains(logPrefix("FINE", session) + result.getExecutionPlan()));
      assertTrue(logs.messages().contains(logPrefix("FINE", session) + existing.getDescription()));
      assertFalse(logs.messages().stream().anyMatch(message -> message.contains(
          "incoming synthetic description")));
      session.closeActiveQueries();
    } finally {
      session.rollback();
    }
  }

  @Test
  public void nullPlanResultSetDescriptionIsSafeInDebugDiagnostics() {
    try (var logs = LogRecordCollector.attachTo(DatabaseSessionEmbedded.class);
        var result = new InternalResultSet(null)) {
      assertNull(result.getExecutionPlan());
      assertEquals("null", result.getDescription());
      session.queryStarted("no-plan", result);
      var existing = new TestQuery(session, "existing", "synthetic description");
      var incoming = new TestQuery(session, "incoming", "incoming description");
      session.queryStarted(existing.id, existing);
      session.queryStarted(incoming.id, incoming);
      assertTrue(logs.messages().contains(warning(session, 2)));
      assertTrue(logs.messages().contains(logPrefix("FINE", session) + "null"));
      session.closeActiveQueries();
      assertTrue(result.isClosed());
      // InternalResultSet is not a lifecycle-listening YQL cursor, so removal remains explicit.
      session.queryClosed("no-plan");
      assertTrue(session.getActiveQueries().isEmpty());
    }
  }

  @Test
  public void disabledThresholdNeverWarnsAndThresholdOneStillSkipsCountsZeroAndOne() {
    for (var threshold : new int[] {0, -1, 1}) {
      var registry = registryWithThreshold(threshold);
      var logger = Logger.getLogger(DatabaseSessionEmbedded.class.getName());
      var oldLevel = logger.getLevel();
      try (var logs = LogRecordCollector.attachTo(DatabaseSessionEmbedded.class)) {
        // Exercise the non-debug path without replacing the logger or the session registry.
        logger.setLevel(Level.INFO);
        var queries = new ArrayList<TestQuery>();
        for (var i = 0; i < 4; i++) {
          var query = new TestQuery(registry, "entry-" + i, "description-" + i);
          queries.add(query); // Keep weak values strongly owned for the entire assertion scope.
          registry.queryStarted(query.id, query);
          if (i < 2) {
            assertTrue(logs.messages().isEmpty());
          }
        }
        assertEquals(threshold == 1 ? List.of(warning(registry, 2), warning(registry, 3))
            : List.of(), logs.messages());
        registry.closeActiveQueries();
        assertTrue(registry.getActiveQueries().isEmpty());
        queries.forEach(query -> assertEquals(1, query.closes));
      } finally {
        logger.setLevel(oldLevel);
      }
    }
  }

  private DatabaseSessionEmbedded registryWithThreshold(int threshold) {
    var config = session.getConfiguration();
    var key = GlobalConfiguration.QUERY_RESULT_SET_OPEN_WARNING_THRESHOLD;
    var previous = config.setValue(key, threshold);
    try {
      // A registry-only session uses the real constructor without opening storage resources.
      return new DatabaseSessionEmbedded(session.getStorage(), serverMode);
    } finally {
      config.setValue(key, previous);
    }
  }

  private static String warning(DatabaseSessionEmbedded registry, int count) {
    return logPrefix("WARNING", registry) + "This database instance has " + count
        + " open command/query result sets, please make sure you close them with ResultSet.close()";
  }

  private static String logPrefix(String level, DatabaseSessionEmbedded registry) {
    var dbName = registry.getDatabaseName();
    return level + " youtrackdb:" + dbName + " [" + dbName + "] ";
  }

  private static final class TestQuery implements RegisteredQuery {

    private final DatabaseSessionEmbedded registry;
    private final String id;
    private final String description;
    private int closes;

    private TestQuery(DatabaseSessionEmbedded registry, String id, String description) {
      this.registry = registry;
      this.id = id;
      this.description = description;
    }

    @Override
    public void close() {
      closes++;
      registry.queryClosed(id);
    }

    @Override
    public String getDescription() {
      return description;
    }
  }
}
