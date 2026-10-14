package com.jetbrains.youtrackdb.internal.core.sql.parser;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.BaseMemoryInternalDatabase;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.sql.SQLEngine;
import com.jetbrains.youtrackdb.internal.core.sql.executor.CountFromClassStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.CreateEdgesStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.GlobalLetQueryStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.InternalExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.sql.executor.InsertExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.sql.executor.UpdateExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.sql.executor.LetQueryStep;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/** SQL publisher, nested-entry, metadata, and stable-metadata write-safety contracts. */
@Category(SequentialTest.class)
public class GenerationStampedYqlExecutionPlanCacheTest extends BaseMemoryInternalDatabase {

  private RID from;
  private RID to;

  @Before
  public void createFixture() {
    var schema = session.getMetadata().getSchema();
    var vertex = schema.createClass("CacheVertex", schema.getClass("V"));
    vertex.createProperty("id", PropertyType.INTEGER);
    var edge = schema.createClass("CacheEdge", schema.getClass("E"));
    edge.createProperty("out", PropertyType.LINK);
    edge.createProperty("in", PropertyType.LINK);
    session.begin();
    var first = session.newVertex("CacheVertex");
    first.setProperty("id", 1);
    from = first.getIdentity();
    var second = session.newVertex("CacheVertex");
    second.setProperty("id", 2);
    to = second.getIdentity();
    session.commit();
    cache().invalidate();
  }

  /** All four families publish eligible cold builds and return distinct private copies on hits. */
  @Test
  public void eligiblePublishersReturnPrivateWarmCopies() {
    session.begin();
    for (var sql : publisherStatements()) {
      cache().invalidate();
      var statement = parse(sql);
      var ctx = context(session);
      var cold = statement.createExecutionPlan(ctx, false);
      Assert.assertTrue("eligible cold publication: " + sql, cache().contains(sql));
      var hits = cache().getHits();
      var warm = statement.createExecutionPlan(context(session), false);
      Assert.assertEquals("warm lookup: " + sql, hits + 1, cache().getHits());
      Assert.assertNotSame(cold, warm);
      Assert.assertNotSame(cold.getSteps().getFirst(), warm.getSteps().getFirst());
      cold.close();
      warm.close();
    }
    session.rollback();
  }

  /** Every publisher refuses useCache=false and profiling. MATCH count keeps its step blocker. */
  @Test
  public void eachPublisherPreservesRefusalsAndHardwiredMatchCount() {
    session.begin();
    for (var sql : publisherStatements()) {
      cache().invalidate();
      var statement = parse(sql);
      statement.createExecutionPlanNoCache(context(session), false).close();
      Assert.assertFalse("no-cache refusal: " + sql, cache().contains(sql));
      statement.createExecutionPlan(context(session), true).close();
      Assert.assertFalse("profiling refusal: " + sql, cache().contains(sql));
    }
    var sql = "MATCH {class:CacheVertex,as:v} RETURN count(*)";
    var count = parse(sql).createExecutionPlan(context(session), false);
    Assert.assertTrue(count.getSteps().stream().anyMatch(CountFromClassStep.class::isInstance));
    Assert.assertFalse("CountFromClassStep must remain uncacheable", count.canBeCached());
    Assert.assertFalse("the hardwired-count publication site must refuse", cache().contains(sql));
    count.close();
    session.rollback();
  }

  /** Eligibility can read metadata. Invalidation there must not be hidden by a later capture. */
  @Test
  public void allPublishersCaptureBeforeEligibilityReadsMetadata() {
    session.begin();
    for (var sql : publisherStatements()) {
      cache().invalidate();
      var statement = spy(parse(sql));
      var fired = new AtomicBoolean();
      doAnswer(call -> {
        var answer = call.callRealMethod();
        if (fired.compareAndSet(false, true)) {
          cache().invalidate();
        }
        return answer;
      }).when(statement).executinPlanCanBeCached(any());
      statement.createExecutionPlan(context(session), false).close();
      Assert.assertTrue("the eligibility checkpoint must run", fired.get());
      Assert.assertTrue("late publication is retained, not cleaned up", cache().contains(sql));
      Assert.assertNull("stale publisher must miss: " + sql,
          cache().getInternal(sql, context(session), session));
      parse(sql).createExecutionPlan(context(session), false).close();
      assertHit(sql);
    }
    session.rollback();
  }

  /** A read has already returned a schema snapshot when invalidation fires for each family. */
  @Test
  public void invalidationAfterFirstMetadataReadRejectsAllPublishers() {
    session.getMetadata().getSchema().getClass("CacheEdge")
        .createIndex("CacheEdge.endpoints", SchemaClass.INDEX_TYPE.UNIQUE, "out", "in");
    session.begin();
    for (var publisherSql : publisherStatements()) {
      // CREATE EDGE reads its UPSERT index choice during planning, not in CheckClassTypeStep.
      var sql = publisherSql.startsWith("CREATE EDGE")
          ? "CREATE EDGE CacheEdge UPSERT FROM :from TO :to" : publisherSql;
      cache().invalidate();
      var metadata = spy(session.getMetadata());
      var planningSession = spy(session);
      doReturn(metadata).when(planningSession).getMetadata();
      var fired = new AtomicBoolean();
      doAnswer(call -> {
        var snapshot = call.callRealMethod();
        if (fired.compareAndSet(false, true)) {
          cache().invalidate();
        }
        return snapshot;
      }).when(metadata).getImmutableSchemaSnapshot();
      parse(sql).createExecutionPlan(context(planningSession), false).close();
      Assert.assertTrue("the snapshot checkpoint must run: " + sql, fired.get());
      Assert.assertNull("metadata-derived stale plan must miss: " + sql,
          cache().getInternal(sql, context(session), session));
    }
    session.rollback();
  }

  /** A parsed FROM child starts after parent invalidation and must retain its own newer stamp. */
  @Test
  public void parsedFromChildPublishesWithItsOwnStamp() {
    var childSql = "SELECT FROM CacheVertex WHERE id = :id";
    var parent = (SQLSelectStatement) spy(parse("SELECT FROM (" + childSql + ")"));
    parent.getTarget().getItem().setStatement(parse(childSql));
    invalidateOnFirstEligibility(parent);
    session.begin();
    parent.createExecutionPlan(context(session), false).close();
    Assert.assertNull("the parent's older build must miss",
        cache().getInternal(parent.getOriginalStatement(), context(session), session));
    assertHit(childSql);
    session.rollback();
  }

  /** Global LET construction builds and publishes a parsed child after the host's invalidation. */
  @Test
  public void globalLetChildPublishesWithItsOwnStamp() {
    var childSql = "SELECT FROM CacheVertex WHERE id = :id";
    var parent = (SQLSelectStatement) spy(parse(
        "SELECT $rows FROM CacheVertex LET $rows = (" + childSql + ")"));
    parent.getLetClause().getItems().getFirst().setQuery(parse(childSql));
    invalidateOnFirstEligibility(parent);
    session.begin();
    var plan = parent.createExecutionPlan(context(session), false);
    Assert.assertTrue(plan.getSteps().stream().anyMatch(GlobalLetQueryStep.class::isInstance));
    assertHit(childSql);
    Assert.assertNull(cache().getInternal(parent.getOriginalStatement(), context(session), session));
    plan.close();
    session.rollback();
  }

  /** Per-record LET preview and execution build independently, including a later invalidation. */
  @Test
  public void perRecordLetPreviewAndExecutionCaptureTheirOwnStamps() {
    var childSql = "SELECT FROM CacheVertex WHERE id = $parent.$current.id";
    var child = parse(childSql);
    session.begin();
    var ctx = context(session);
    var step = new LetQueryStep(new SQLIdentifier("rows"), child, ctx, false);
    cache().invalidate();
    step.prettyPrint(0, 2);
    assertHit(childSql + "\0letHostedCorrelatedRidFetch");
    cache().invalidate();
    var parent = (SQLSelectStatement) parse(
        "SELECT id, $rows FROM CacheVertex LET $rows = (" + childSql + ")");
    parent.getLetClause().getItems().getFirst().setQuery(parse(childSql));
    var plan = parent.createExecutionPlan(ctx, false);
    cache().invalidate();
    try (var rows = new LocalResultSet(session, plan)) {
      Assert.assertEquals(2, rows.stream().count());
    }
    assertHit(childSql + "\0letHostedCorrelatedRidFetch");
    step.close();
    session.rollback();
  }

  /** Endpoint SELECTs run after CREATE EDGE planning. Both separately publish at execution start. */
  @Test
  public void createEdgeEndpointsCaptureAfterHostBuild() {
    var leftSql = "SELECT FROM CacheVertex WHERE id = 1";
    var rightSql = "SELECT FROM CacheVertex WHERE id = 2";
    session.begin();
    var ctx = context(session);
    // Use parsed children. Fresh MATCH-synthesized SELECTs have no statement text to publish.
    var step = new CreateEdgesStep(new SQLIdentifier("CacheEdge"), null, null, null,
        parse(leftSql), parse(rightSql), ctx, false);
    cache().invalidate();
    var stream = step.start(ctx);
    Assert.assertTrue(stream.hasNext(ctx));
    stream.next(ctx);
    stream.close(ctx);
    assertHit(leftSql);
    assertHit(rightSql);
    step.close();
    session.rollback();
  }

  /** A child invalidated after its first metadata read cannot gain a fresh stamp at publication. */
  @Test
  public void parsedNestedChildrenRejectInvalidationDuringTheirOwnBuild() {
    session.begin();
    var childSql = "SELECT FROM CacheVertex WHERE id = :id";
    for (var kind : List.of("from", "global", "record", "endpoint")) {
      cache().invalidate();
      var child = spy(parse(childSql));
      doReturn(child).when(child).copy();
      var buildingChild = new AtomicBoolean();
      doAnswer(call -> {
        buildingChild.set(true);
        try {
          return call.callRealMethod();
        } finally {
          buildingChild.set(false);
        }
      }).when(child).createExecutionPlan(any(), anyBoolean());
      var fired = new AtomicBoolean();
      doAnswer(call -> {
        if (buildingChild.get() && fired.compareAndSet(false, true)) {
          session.getMetadata().getImmutableSchemaSnapshot();
          cache().invalidate();
        }
        return call.callRealMethod();
      }).when(child).executinPlanCanBeCached(any());
      var ctx = context(session);
      switch (kind) {
        case "from" -> {
          var parent = (SQLSelectStatement) parse("SELECT FROM (" + childSql + ")");
          parent.getTarget().getItem().setStatement(child);
          parent.createExecutionPlan(ctx, false).close();
        }
        case "global" -> {
          var step = new GlobalLetQueryStep(new SQLIdentifier("rows"), child, ctx, false, null);
          step.close();
        }
        case "record" -> {
          var step = new LetQueryStep(new SQLIdentifier("rows"), child, ctx, false);
          step.prettyPrint(0, 2);
          step.close();
        }
        case "endpoint" -> {
          var step = new CreateEdgesStep(new SQLIdentifier("CacheEdge"), null, null, null,
              child, parse("SELECT FROM CacheVertex WHERE id = 2"), ctx, false);
          var stream = step.start(ctx);
          stream.hasNext(ctx);
          stream.close(ctx);
          step.close();
        }
        default -> throw new AssertionError(kind);
      }
      var key = kind.equals("record") ? childSql + "\0letHostedCorrelatedRidFetch" : childSql;
      Assert.assertTrue("the child's own checkpoint must run for " + kind, fired.get());
      Assert.assertTrue("separate publication must occur for " + kind, cache().contains(key));
      Assert.assertNull("stale nested child must miss for " + kind,
          cache().getInternal(key, context(session), session));
      var freshCtx = context(session);
      freshCtx.setLetHostedCorrelatedRidFetch(kind.equals("record"));
      parse(childSql).createExecutionPlan(freshCtx, false).close();
      assertHit(key);
    }
    session.rollback();
  }

  /** Real producers must advance the generation and reject a late plan from before the change. */
  @Test
  public void metadataAndStorageConfigurationProducersRejectLatePlans() {
    assertRealInvalidation(() -> session.getMetadata().getSchema().createClass("ChangedSchema"));
    assertRealInvalidation(() -> session.getMetadata().getSchema().getClass("CacheVertex")
        .createIndex("CacheVertex.id", SchemaClass.INDEX_TYPE.NOTUNIQUE, "id"));
    assertRealInvalidation(() -> session.getMetadata().getFunctionLibrary()
        .createFunction("cacheGenerationFunction"));
    assertRealInvalidation(() -> session.execute("CREATE SEQUENCE cacheGenerationSequence TYPE ORDERED")
        .close());
    assertRealInvalidation(() -> session.setCustom("cache.generation", "changed"));
  }

  /** A timeout change invalidates every entry, including plans that resolve no null placement. */
  @Test
  public void timeoutChangeAdvancesGenerationAndRejectsLateEntries() {
    var config = session.getStorage().getContextConfiguration();
    var previous = config.getValue(GlobalConfiguration.COMMAND_TIMEOUT);
    var sql = "SELECT FROM CacheVertex";
    var ctx = context(session);
    var plan = parse(sql).createExecutionPlan(ctx, false);
    var generation = cache().getGeneration();
    parse("SELECT id FROM CacheVertex").createExecutionPlan(ctx, false).close();
    try {
      config.setValue(GlobalConfiguration.COMMAND_TIMEOUT,
          session.getConfiguration().getValueAsLong(GlobalConfiguration.COMMAND_TIMEOUT) + 1234);
      Assert.assertNull(cache().getInternal(sql, ctx, session));
      Assert.assertTrue(cache().getGeneration() > generation);
      Assert.assertFalse(cache().contains("SELECT id FROM CacheVertex"));
      cache().putInternal(sql, plan, session, generation, null);
      Assert.assertNull(cache().getInternal(sql, ctx, session));
    } finally {
      config.setValue(GlobalConfiguration.COMMAND_TIMEOUT, previous);
      cache().getInternal(null, ctx, session);
      plan.close();
    }
  }

  /** Overlapping bound copies belong to their sessions. Closing one leaves the other and template. */
  @Test
  public void crossSessionCopiesKeepBindingsAndResourcesPrivate() {
    var sql = "SELECT id FROM CacheVertex WHERE id >= :id ORDER BY id";
    session.begin();
    var cold = parse(sql).createExecutionPlan(context(session), false);
    cold.close();
    session.commit();
    try (var first = openDatabase(); var second = openDatabase()) {
      first.activateOnCurrentThread();
      first.begin();
      var firstCtx = context(first);
      firstCtx.setInputParameters(Map.of("id", 1));
      var firstPlan = (InternalExecutionPlan) cache().getInternal(sql, firstCtx, first);
      var firstRows = new LocalResultSet(first, firstPlan);
      Assert.assertEquals(1, (int) firstRows.next().<Integer>getProperty("id"));
      second.activateOnCurrentThread();
      second.begin();
      var secondCtx = context(second);
      secondCtx.setInputParameters(Map.of("id", 2));
      var secondPlan = (InternalExecutionPlan) cache().getInternal(sql, secondCtx, second);
      Assert.assertNotSame(firstPlan, secondPlan);
      var secondRows = new LocalResultSet(second, secondPlan);
      first.activateOnCurrentThread();
      firstRows.close();
      first.rollback();
      second.activateOnCurrentThread();
      Assert.assertEquals(2, (int) secondRows.next().<Integer>getProperty("id"));
      Assert.assertFalse(secondRows.hasNext());
      secondRows.close();
      var thirdCopy = (InternalExecutionPlan) cache().getInternal(sql, secondCtx, second);
      try (var rows = new LocalResultSet(second, thirdCopy)) {
        Assert.assertEquals(2, (int) rows.next().<Integer>getProperty("id"));
        Assert.assertFalse(rows.hasNext());
      }
      second.rollback();
    } finally {
      session.activateOnCurrentThread();
    }
  }

  /**
   * Nested SELECT sources under every non-publishing write family must reject a late
   * pre-change scan. Cached and fresh executions keep the detached subclass record intact.
   */
  @Test
  public void nestedWriteSourcesKeepRecordsOutsideCurrentScope() {
    var schema = session.getMetadata().getSchema();
    var parentClass = schema.createClass("ScopeParent", schema.getClass("V"));
    parentClass.createProperty("changed", PropertyType.BOOLEAN);
    var childClass = schema.createClass("ScopeChild", parentClass);
    session.begin();
    var kept = session.newVertex("ScopeChild").getIdentity();
    session.newVertex("ScopeParent");
    session.commit();
    var sources = List.of("SELECT FROM ScopeParent");
    var oldPlans = sources.stream().map(sql -> parse(sql)
        .createExecutionPlan(context(session), false)).toList();
    var generation = cache().getGeneration();
    childClass.removeSuperClass(parentClass);
    Assert.assertFalse(session.getMetadata().getImmutableSchemaSnapshot()
        .getClass("ScopeChild").isSubClassOf("ScopeParent"));
    Assert.assertTrue(cache().getGeneration() > generation);
    for (var i = 0; i < sources.size(); i++) {
      var source = sources.get(i);
      for (var operation : List.of("UPDATE", "DELETE", "DELETE VERTEX")) {
        cache().invalidate();
        session.begin();
        cache().putInternal(source, oldPlans.get(i), session, generation, null);
        Assert.assertNull("late write source must be rejected",
            cache().getInternal(source, context(session), session));
        var sql = switch (operation) {
          case "UPDATE" -> "UPDATE (" + source + ") SET changed = true";
          case "DELETE" -> "DELETE FROM (" + source + ") UNSAFE";
          default -> "DELETE VERTEX FROM (" + source + ")";
        };
        var statement = parse(sql);
        var target = switch (statement) {
          case SQLUpdateStatement update -> update.getTarget();
          case SQLDeleteStatement delete -> delete.getFromClause();
          case SQLDeleteVertexStatement delete -> delete.getFromClause();
          default -> throw new AssertionError(statement);
        };
        // Parsed children keep genuine SQL text across the write planner's AST copy.
        target.getItem().setStatement(parse(source));
        parse(source).createExecutionPlan(context(session), false).close();
        var hits = cache().getHits();
        var cachedResult = executePlan(statement.createExecutionPlan(context(session), false));
        Assert.assertTrue("the write must consume a warm nested source", cache().getHits() > hits);
        Assert.assertNotNull(session.load(kept));
        Assert.assertNull(session.load(kept).asEntity().getProperty("changed"));
        session.rollback();
        assertHit(source);
        cache().invalidate();
        session.begin();
        var freshResult = executePlan(statement.createExecutionPlanNoCache(context(session), false));
        Assert.assertEquals("stable-metadata cached/fresh agreement: " + sql,
            cachedResult, freshResult);
        Assert.assertNotNull(session.load(kept));
        Assert.assertNull(session.load(kept).asEntity().getProperty("changed"));
        session.rollback();
      }
      oldPlans.get(i).close();
    }
  }

  /** DELETE EDGE rebuilds its frozen collection list after an out-of-transaction hierarchy change. */
  @Test
  public void deleteEdgeAfterRemoveSuperClassKeepsDetachedEdges() {
    var schema = session.getMetadata().getSchema();
    var parent = schema.getClass("CacheEdge");
    var child = schema.createClass("DetachedEdge", parent);
    session.begin();
    var keep = session.load(from).asVertex().addEdge(session.load(to).asVertex(), "DetachedEdge")
        .getIdentity();
    var remove = session.load(from).asVertex().addEdge(session.load(to).asVertex(), "CacheEdge")
        .getIdentity();
    session.commit();
    var sql = "DELETE EDGE CacheEdge";
    var oldPlan = parse(sql).createExecutionPlan(context(session), false);
    var generation = cache().getGeneration();
    child.removeSuperClass(parent);
    Assert.assertFalse(session.getMetadata().getImmutableSchemaSnapshot()
        .getClass("DetachedEdge").isSubClassOf("CacheEdge"));
    cache().putInternal(sql, oldPlan, session, generation, null);
    Assert.assertNull(cache().getInternal(sql, context(session), session));
    session.begin();
    var cachedResult = executePlan(parse(sql).createExecutionPlan(context(session), false));
    Assert.assertNotNull(session.load(keep));
    Assert.assertFalse(session.exists(remove));
    session.rollback();
    assertHit(sql);
    session.begin();
    var freshResult = executePlan(parse(sql).createExecutionPlanNoCache(context(session), false));
    Assert.assertEquals(cachedResult, freshResult);
    Assert.assertNotNull(session.load(keep));
    Assert.assertFalse(session.exists(remove));
    session.rollback();
    oldPlan.close();
  }

  /** A renamed class makes both cached and fresh DELETE EDGE fail without deleting its records. */
  @Test
  public void deleteEdgeAfterClassRenameRejectsOldScope() {
    session.begin();
    var keep = session.load(from).asVertex().addEdge(session.load(to).asVertex(), "CacheEdge")
        .getIdentity();
    session.commit();
    var sql = "DELETE EDGE CacheEdge";
    var oldPlan = parse(sql).createExecutionPlan(context(session), false);
    var generation = cache().getGeneration();
    session.getMetadata().getSchema().getClass("CacheEdge").setName("RenamedCacheEdge");
    Assert.assertNull(session.getMetadata().getImmutableSchemaSnapshot().getClass("CacheEdge"));
    cache().putInternal(sql, oldPlan, session, generation, null);
    Assert.assertNull(cache().getInternal(sql, context(session), session));
    for (var cached : List.of(true, false)) {
      session.begin();
      Assert.assertThrows(CommandExecutionException.class, () -> executePlan(cached
          ? parse(sql).createExecutionPlan(context(session), false)
          : parse(sql).createExecutionPlanNoCache(context(session), false)));
      Assert.assertNotNull(session.load(keep));
      session.rollback();
    }
    oldPlan.close();
  }

  /** A same-name index moved to another class must not let UPSERT reuse an unrelated edge. */
  @Test
  public void createEdgeUpsertAfterSameNameIndexReplacementUsesCurrentIndex() {
    var schema = session.getMetadata().getSchema();
    var target = schema.getClass("CacheEdge");
    var unrelated = schema.createClass("UnrelatedEdge", schema.getClass("E"));
    unrelated.createProperty("out", PropertyType.LINK);
    unrelated.createProperty("in", PropertyType.LINK);
    target.createIndex("sharedEndpoints", SchemaClass.INDEX_TYPE.UNIQUE, "out", "in");
    var sql = "CREATE EDGE CacheEdge UPSERT FROM :from TO :to";
    session.begin();
    var original = executePlan(parse(sql).createExecutionPlan(context(session), false)).getFirst();
    var keep = session.load(from).asVertex().addEdge(session.load(to).asVertex(), "UnrelatedEdge")
        .getIdentity();
    session.commit();
    var oldPlan = parse(sql).createExecutionPlan(context(session), false);
    var generation = cache().getGeneration();
    session.getSharedContext().getIndexManager().dropIndex(session, "sharedEndpoints");
    unrelated.createIndex("sharedEndpoints", SchemaClass.INDEX_TYPE.UNIQUE, "out", "in");
    target.createIndex("currentEndpoints", SchemaClass.INDEX_TYPE.UNIQUE, "out", "in");
    Assert.assertTrue(cache().getGeneration() > generation);
    cache().putInternal(sql, oldPlan, session, generation, null);
    Assert.assertNull(cache().getInternal(sql, context(session), session));
    var alreadyPublished = false;
    for (var cached : List.of(true, true, false)) {
      session.begin();
      var hits = cache().getHits();
      var result = executePlan(cached ? parse(sql).createExecutionPlan(context(session), false)
          : parse(sql).createExecutionPlanNoCache(context(session), false));
      Assert.assertEquals(List.of(original), result);
      Assert.assertNotNull(session.load(keep));
      Assert.assertEquals(1, session.countClass("CacheEdge"));
      Assert.assertEquals(1, session.countClass("UnrelatedEdge"));
      if (cached && alreadyPublished) {
        Assert.assertTrue("the second cached execution must hit", cache().getHits() > hits);
      }
      session.commit();
      alreadyPublished = true;
    }
    oldPlan.close();
  }

  private List<Object> executePlan(InternalExecutionPlan plan) {
    if (plan instanceof InsertExecutionPlan insert) {
      insert.executeInternal();
    } else if (plan instanceof UpdateExecutionPlan update) {
      update.executeInternal();
    }
    try (var rows = new LocalResultSet(session, plan)) {
      return rows.stream().map(row -> row.getIdentity() != null ? (Object) row.getIdentity()
          : row.<Object>getProperty("count")).toList();
    }
  }

  private List<String> publisherStatements() {
    return List.of("SELECT FROM CacheVertex", "MATCH {class:CacheVertex,as:v} RETURN $patterns",
        "CREATE EDGE CacheEdge FROM :from TO :to", "DELETE EDGE CacheEdge");
  }

  private YqlExecutionPlanCache cache() {
    return YqlExecutionPlanCache.instance(session);
  }

  private SQLStatement parse(String sql) {
    return SQLEngine.parse(sql, session);
  }

  private BasicCommandContext context(DatabaseSessionEmbedded database) {
    var ctx = new BasicCommandContext();
    ctx.setDatabaseSession(database);
    ctx.setInputParameters(Map.of("from", from, "to", to, "id", 1));
    return ctx;
  }

  private void assertHit(String sql) {
    var hits = cache().getHits();
    var copy = (InternalExecutionPlan) cache().getInternal(sql, context(session), session);
    Assert.assertNotNull("separately published child must be a valid hit: " + sql, copy);
    Assert.assertEquals(hits + 1, cache().getHits());
    copy.close();
  }

  private void invalidateOnFirstEligibility(SQLStatement statement) {
    var fired = new AtomicBoolean();
    doAnswer(call -> {
      // Read metadata before firing so the input snapshot belongs to this child's build.
      session.getMetadata().getImmutableSchemaSnapshot();
      if (fired.compareAndSet(false, true)) {
        cache().invalidate();
      }
      return call.callRealMethod();
    }).when(statement).executinPlanCanBeCached(any());
  }

  private void assertRealInvalidation(Runnable change) {
    var sql = "SELECT FROM CacheVertex";
    var ctx = context(session);
    var generation = cache().getGeneration();
    var plan = parse(sql).createExecutionPlan(ctx, false);
    try {
      change.run();
      Assert.assertTrue("real metadata producer must advance generation",
          cache().getGeneration() > generation);
      Assert.assertFalse(cache().contains(sql));
      cache().putInternal(sql, plan, session, generation, null);
      Assert.assertNull(cache().getInternal(sql, ctx, session));
      // DDL has returned and refreshed the snapshot. Do not exercise the accepted DU2 gap.
      parse(sql).createExecutionPlan(ctx, false).close();
      assertHit(sql);
    } finally {
      plan.close();
    }
  }
}
