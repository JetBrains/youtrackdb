package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy.RuntimeRidStartTestFactory;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBinaryCondition;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBooleanExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLEqualsOperator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIdentifier;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import java.util.Map;
import org.junit.Test;

/** Direct tests for the runtime MATCH source, without planner or translator wiring. */
public class RuntimeRidStartStepTest extends DbTestBase {

  private BasicCommandContext context(DatabaseSessionEmbedded db, Object value) {
    var ctx = new BasicCommandContext();
    ctx.setDatabaseSession(db);
    ctx.setInputParameters(Map.of(2, value));
    return ctx;
  }

  private RuntimeRidStartStep step(CommandContext ctx, String aliasClass,
      SQLWhereClause filter, boolean profiling) {
    return new RuntimeRidStartStep(
        RuntimeRidStartTestFactory.create("start", aliasClass, 2), filter, ctx, profiling);
  }

  private Result single(RuntimeRidStartStep step, CommandContext ctx) {
    var stream = step.start(ctx);
    try {
      assertTrue(stream.hasNext(ctx));
      var row = stream.next(ctx);
      assertFalse(stream.hasNext(ctx));
      assertSame(row, ctx.getSystemVariable(CommandContext.VAR_MATCHED));
      return row;
    } finally {
      stream.close(ctx);
    }
  }

  private void empty(RuntimeRidStartStep step, CommandContext ctx) {
    var stream = step.start(ctx);
    try {
      assertFalse(stream.hasNext(ctx));
    } finally {
      stream.close(ctx);
    }
  }

  private RecordId createVertex(String className) {
    session.begin();
    var vertex = session.newVertex(className);
    session.commit();
    return new RecordId(vertex.getIdentity());
  }

  /** Absent, non-RID, and non-persistent bindings signal a broken runtime-start invariant. */
  @Test
  public void invalidBindingsAreErrors() {
    var ctx = new BasicCommandContext();
    var step = step(ctx, "Animal", null, false);
    assertThrows(IllegalStateException.class, () -> step.start(ctx));
    ctx.setInputParameters(Map.of(2, "not a RID"));
    assertThrows(IllegalStateException.class, () -> step.start(ctx));
    ctx.setInputParameters(Map.of(2, new RecordId(5, -1)));
    assertThrows(IllegalStateException.class, () -> step.start(ctx));
  }

  /** A subclass is accepted, while a different collection is rejected before any load. */
  @Test
  public void subclassAndWrongClassDoNotShareTheLoadPath() {
    session.createVertexClass("Animal");
    session.createClass("Dog", "Animal");
    session.createVertexClass("Other");
    var dog = createVertex("Dog");
    var wrong = createVertex("Other");
    session.begin();
    var ctx = context(session, dog);
    assertEquals(dog, single(step(ctx, "Animal", null, false), ctx).getProperty("start"));

    var db = mock(DatabaseSessionEmbedded.class);
    when(db.getMetadata()).thenReturn(session.getMetadata());
    var wrongCtx = context(db, wrong);
    empty(step(wrongCtx, "Animal", null, false), wrongCtx);
    verify(db, never()).load(any(RID.class));
  }

  /** Missing and deleted persistent records produce no row, and an unowned collection never loads. */
  @Test
  public void missingDeletedAndUnownedCollection() {
    session.createVertexClass("Animal");
    var rid = createVertex("Animal");
    session.begin();
    var missing = new RecordId(rid.getCollectionId(), rid.getCollectionPosition() + 100000);
    var missingCtx = context(session, missing);
    empty(step(missingCtx, "Animal", null, false), missingCtx);

    session.loadEntity(rid).delete();
    var deletedCtx = context(session, rid);
    empty(step(deletedCtx, "Animal", null, false), deletedCtx);
    session.rollback();
    session.begin();
    assertNotNull(single(step(deletedCtx, "Animal", null, false), deletedCtx));

    var unowned = session.addCollection("unowned_runtime_start");
    var db = mock(DatabaseSessionEmbedded.class);
    when(db.getMetadata()).thenReturn(session.getMetadata());
    var unownedCtx = context(db, new RecordId(unowned, 1));
    empty(step(unownedCtx, "Animal", null, false), unownedCtx);
    verify(db, never()).load(any(RID.class));
  }

  /** A deletion committed before execution returns no row through the storage read path. */
  @Test
  public void committedDeleteReturnsNoRow() {
    session.createVertexClass("Animal");
    var rid = createVertex("Animal");
    session.begin();
    session.loadEntity(rid).delete();
    session.commit();

    session.begin();
    var ctx = context(session, rid);
    empty(step(ctx, "Animal", null, false), ctx);
  }

  /** A temporary RID is a defect, whereas a committed create is readable after commit. */
  @Test
  public void pendingCreateRejectedAndCommittedCreateAccepted() {
    session.createVertexClass("Animal");
    session.begin();
    var vertex = session.newVertex("Animal");
    var temporary = context(session, vertex.getIdentity());
    assertThrows(IllegalStateException.class,
        () -> step(temporary, "Animal", null, false).start(temporary));
    session.commit();
    session.begin();
    var committed = context(session, new RecordId(vertex.getIdentity()));
    single(step(committed, "Animal", null, false), committed);
  }

  /** Remaining filters reject records and each copy keeps its own AST. */
  @Test
  public void filterAndCopyAreIndependent() {
    session.createVertexClass("Animal");
    var rid = createVertex("Animal");
    session.begin();
    var filter = new SQLWhereClause(-1);
    filter.setBaseExpression(SQLBooleanExpression.FALSE);
    var ctx = context(session, rid);
    var template = step(ctx, "Animal", filter, false);
    var copiedCtx = context(session, rid);
    var copy = (RuntimeRidStartStep) template.copy(copiedCtx);
    filter.setBaseExpression(SQLBooleanExpression.TRUE);
    empty(copy, copiedCtx);
    single(template, ctx);
    copiedCtx.setInputParameters(Map.of(2,
        new RecordId(rid.getCollectionId(), rid.getCollectionPosition() + 100000)));
    empty(copy, copiedCtx);
    single(template, ctx);
  }

  /** A data-dependent filter is evaluated for each record and copied below its container. */
  @Test
  public void predicateCopyRetainsIndependentRecordChecks() {
    session.createVertexClass("Animal");
    session.begin();
    var allowed = session.newVertex("Animal");
    allowed.setProperty("name", "allowed");
    var denied = session.newVertex("Animal");
    denied.setProperty("name", "denied");
    session.commit();
    var allowedRid = new RecordId(allowed.getIdentity());
    var deniedRid = new RecordId(denied.getIdentity());

    var condition = new SQLBinaryCondition(-1);
    condition.setLeft(new SQLExpression(new SQLIdentifier("name")));
    condition.setOperator(new SQLEqualsOperator(-1));
    var expectedName = new SQLExpression(-1);
    expectedName.setLiteralValue("allowed");
    condition.setRight(expectedName);
    var filter = new SQLWhereClause(-1);
    filter.setBaseExpression(condition);
    session.begin();
    var ctx = context(session, allowedRid);
    var template = step(ctx, "Animal", filter, false);
    assertEquals(allowedRid, single(template, ctx).getProperty("start"));
    ctx.setInputParameters(Map.of(2, deniedRid));
    empty(template, ctx);

    var copyCtx = context(session, allowedRid);
    var copy = (RuntimeRidStartStep) template.copy(copyCtx);
    var changedName = new SQLExpression(-1);
    changedName.setLiteralValue("denied");
    condition.setRight(changedName);
    ctx.setInputParameters(Map.of(2, allowedRid));
    empty(template, ctx);
    assertEquals(allowedRid, single(copy, copyCtx).getProperty("start"));
    ctx.setInputParameters(Map.of(2, deniedRid));
    assertEquals(deniedRid, single(template, ctx).getProperty("start"));
    copyCtx.setInputParameters(Map.of(2, deniedRid));
    empty(copy, copyCtx);
  }

  /** A cacheable template remains independent of the runtime RID binding. */
  @Test
  public void runtimeRidStartIsCacheable() {
    var ctx = new BasicCommandContext();
    assertTrue(step(ctx, "Animal", null, false).canBeCached());
  }

  /** EXPLAIN never prints either RID, and profiling records the full start call. */
  @Test
  public void planOutputHidesBoundValuesAndProfiles() {
    session.createVertexClass("Animal");
    var first = createVertex("Animal");
    var second = createVertex("Animal");
    session.begin();
    var ctx = context(session, first);
    var step = step(ctx, "Animal", null, true);
    var before = step.prettyPrint(0, 3);
    assertEquals(first, single(step, ctx).getProperty("start"));
    ctx.setInputParameters(Map.of(2, second));
    assertEquals(second, single(step, ctx).getProperty("start"));
    var after = step.prettyPrint(0, 3);
    for (var text : new String[] {before, after}) {
      assertTrue(text.contains("start"));
      assertTrue(text.contains("Animal"));
      assertTrue(text.contains("?"));
      assertFalse(text.contains(first.toString()));
      assertFalse(text.contains(second.toString()));
    }
    assertTrue(after.contains("μs"));
    var structured = step.toResult(session);
    for (var field : new String[] {"name", "type", "javaType", "cost", "subSteps",
        "description"}) {
      assertNotNull(structured.getProperty(field));
    }
    assertTrue(step.getCost() >= 0);
  }

  /** An edge collection is not a subclass of the vertex alias class. */
  @Test
  public void edgeCollectionDoesNotStartAVertexMatch() {
    session.createVertexClass("Animal");
    session.createEdgeClass("Rel");
    session.begin();
    var from = session.newVertex("Animal");
    var to = session.newVertex("Animal");
    var edge = session.newEdge(from, to, "Rel");
    session.commit();
    var ctx = context(session, new RecordId(edge.getIdentity()));
    empty(step(ctx, "Animal", null, false), ctx);
  }
}
