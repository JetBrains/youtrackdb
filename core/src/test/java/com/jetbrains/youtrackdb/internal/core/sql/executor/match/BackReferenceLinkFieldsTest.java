package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Direction;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.record.impl.VertexEntityImpl;
import com.jetbrains.youtrackdb.internal.core.sql.executor.RidFilterDescriptor;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchPathItem;
import java.util.ArrayList;
import org.junit.After;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/** Database-backed checks of the plain MATCH lookup, independent of planner admission. */
@Category(SequentialTest.class)
public class BackReferenceLinkFieldsTest extends DbTestBase {

  private final Object savedCap = GlobalConfiguration.QUERY_PREFILTER_MAX_RIDSET_SIZE.getValue();

  @After
  public void restoreLimits() {
    GlobalConfiguration.QUERY_PREFILTER_MAX_RIDSET_SIZE.setValue(savedCap);
  }

  /** Required-target MATCH queries must retain every subclass neighbor and apply the filter. */
  @Test
  public void realMatchSubclassOut() {
    checkQuery("out", "L", "L2", "L3");
  }

  @Test
  public void realMatchMixedOut() {
    checkQuery("out", "L", "L", "L3");
  }

  @Test
  public void realMatchSubclassIn() {
    checkQuery("in", "L", "L2", "L3");
  }

  @Test
  public void realMatchBothOneSideAbsent() {
    checkQuery("both", "L", "L2", "L3");
  }

  @Test
  public void realMatchBaseE() {
    checkQuery("out", "E", "L2", "L3");
  }

  private void checkQuery(String direction, String label, String... classes) {
    var savedThreshold = GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.getValue();
    GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.setValue(0L);
    try {
      schema();
      session.execute("create class F extends E").close();
      session.begin();
      var anchor = session.newVertex("V");
      var source = session.newVertex("V");
      session.newRegularEdge("F", anchor, source);
      session.newRegularEdge("L3", session.newVertex("V"), anchor).delete();
      var expected = new java.util.HashSet<RID>();
      for (int i = 0; i < 100; i++) {
        var candidate = session.newVertex("V");
        session.newRegularEdge("H", source, candidate);
        if (i < 60) {
          expected.add(candidate.getIdentity());
          if (direction.equals("in")) {
            session.newRegularEdge(classes[i % classes.length], anchor, candidate);
          } else {
            session.newRegularEdge(classes[i % classes.length], candidate, anchor);
          }
        }
      }
      String query = "match {rid: " + anchor.getIdentity()
          + ", as:a}.out('F'){as:s}.out('H'){as:c}."
          + direction + "('" + label + "'){as:z, where:(@rid = $matched.a.@rid)} return c";
      try (var result = session.query(query)) {
        var actual = new java.util.HashSet<RID>();
        for (var row : result.toList()) {
          actual.add(com.jetbrains.youtrackdb.internal.core.sql.executor.TraversalPreFilterHelper
              .toRid(row.getProperty("c")));
        }
        assertEquals(expected, actual);
        var plan = result.getExecutionPlan();
        assertTrue(plan.prettyPrint(0, 2), plan.getSteps().stream()
            .filter(MatchStep.class::isInstance).map(MatchStep.class::cast)
            .anyMatch(step -> step.edge.getPreFilterAppliedCount() > 0));
      }
      session.rollback();
    } finally {
      GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.setValue(savedThreshold);
    }
  }

  /** Wide directional estimates reject before iteration, but a larger forward bag builds. */
  @Test
  public void wideEstimateRejectsBeforeBuild() {
    schema();
    for (int i = 0; i < 50; i++) {
      session.execute("create class Wide" + i + " extends L").close();
    }
    session.begin();
    var anchor = session.newVertex("V");
    for (int i = 0; i < 100; i++) {
      session.newRegularEdge("Wide" + i % 50, session.newVertex("V"), anchor);
    }
    var ctx = context();
    var descriptor = org.mockito.Mockito.spy(lookup("L", "both", anchor.getIdentity(), ctx, false));
    var edge = traversal(descriptor);
    assertNull(edge.resolvePreFilter(ctx, 60));
    org.mockito.Mockito.verify(descriptor, org.mockito.Mockito.never())
        .resolve(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    assertEquals(100, edge.resolvePreFilter(ctx, 200).size());
    org.mockito.Mockito.verify(descriptor).resolve(ctx, anchor.getIdentity());
    session.rollback();
  }

  /** The planner's edge-RID reader must retain all sixty subclass edge identities. */
  @Test
  public void realMatchEdgeRids() {
    var saved = GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.getValue();
    GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.setValue(0L);
    try {
      schema();
      session.execute("create class F extends E").close();
      session.begin();
      var anchor = session.newVertex("V");
      var source = session.newVertex("V");
      session.newRegularEdge("F", anchor, source);
      var expected = new java.util.HashSet<RID>();
      for (int i = 0; i < 100; i++) {
        var edge = session.newRegularEdge("L3", source,
            i < 60 ? anchor : session.newVertex("V"));
        if (i < 60) {
          expected.add(edge.getIdentity());
        }
      }
      String query = "match {rid:" + anchor.getIdentity()
          + ",as:a}.out('F'){as:s}.outE('L'){as:c}.inV()"
          + "{as:z,where:(@rid=$matched.a.@rid)} return c";
      try (var result = session.query(query)) {
        var actual = new java.util.HashSet<RID>();
        result.toList().forEach(row -> actual.add(
            com.jetbrains.youtrackdb.internal.core.sql.executor.TraversalPreFilterHelper
                .toRid(row.getProperty("c"))));
        assertEquals(expected, actual);
        assertTrue(result.getExecutionPlan().prettyPrint(0, 2),
            result.getExecutionPlan().getSteps().stream()
                .filter(MatchStep.class::isInstance).map(MatchStep.class::cast)
                .anyMatch(step -> step.edge.getPreFilterAppliedCount() > 0));
      }
      session.rollback();
    } finally {
      GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.setValue(saved);
    }
  }

  /** A cached plan built before a schema change must resolve the new hierarchy on first use. */
  @Test
  public void realCachedPlanAfterSchemaChange() {
    checkPlanLifecycle(false, false);
  }

  /** Restart must refresh a used MATCH reader after a schema change. */
  @Test
  public void realPlanResetAfterSchemaChange() {
    checkPlanLifecycle(true, false);
  }

  /** Restart must reach MATCH readers nested inside a Cartesian component. */
  @Test
  public void realCartesianResetAfterCommittedEdge() {
    checkPlanLifecycle(true, true);
  }

  private void checkPlanLifecycle(boolean runBefore, boolean cartesian) {
    var saved = GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.getValue();
    GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.setValue(0L);
    try {
      schema();
      session.execute("create class F extends E").close();
      session.execute("create class Other extends E").close();
      session.begin();
      var anchor = session.newVertex("V");
      var source = session.newVertex("V");
      session.newRegularEdge("F", anchor, source);
      var expected = new ArrayList<RID>();
      for (int i = 0; i < 100; i++) {
        var candidate = session.newVertex("V");
        session.newRegularEdge("H", source, candidate);
        if (i < 60) {
          expected.add(candidate.getIdentity());
          session.newRegularEdge(i < 30 || cartesian ? "L2" : "Other", candidate, anchor);
        }
      }
      session.commit();
      session.begin();
      String query = "match {rid:" + anchor.getIdentity()
          + ",as:a}.out('F'){as:s}.out('H'){as:c}.out('L')"
          + "{as:z,where:(@rid=$matched.a.@rid)}"
          + (cartesian ? ", {rid:" + source.getIdentity() + ",as:ind}" : "")
          + " return c";
      var ctx = context();
      var statement =
          (com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchStatement) com.jetbrains.youtrackdb.internal.core.sql.SQLEngine
              .parse(query, session);
      var plan = statement.createExecutionPlan(ctx, false);
      if (runBefore) {
        assertPlanRows(plan, ctx, expected.subList(0, cartesian ? 60 : 30));
      }
      session.commit();
      if (cartesian) {
        session.begin();
        var candidate = session.newVertex("V");
        session.newRegularEdge("H", session.load(source.getIdentity()), candidate);
        session.newRegularEdge("L3", candidate, session.load(anchor.getIdentity()));
        expected.add(candidate.getIdentity());
        session.commit();
      } else {
        var schema = session.getMetadata().getSchema();
        schema.getClass("Other").addSuperClass(schema.getClass("L"));
      }
      session.begin();
      if (runBefore) {
        plan.reset(ctx);
      }
      assertPlanRows(plan, ctx, expected);
      assertTrue(plan.prettyPrint(0, 2), hasAppliedFilter(plan));
      session.rollback();
    } finally {
      GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.setValue(saved);
    }
  }

  private boolean hasAppliedFilter(
      com.jetbrains.youtrackdb.internal.core.sql.executor.InternalExecutionPlan plan) {
    for (var step : plan.getSteps()) {
      if (step instanceof MatchStep match && match.edge.getPreFilterAppliedCount() > 0) {
        return true;
      }
      if (step instanceof com.jetbrains.youtrackdb.internal.core.sql.executor.CartesianProductStep) {
        try {
          var field = step.getClass().getDeclaredField("subPlans");
          field.setAccessible(true);
          for (var nested : (java.util.List<?>) field.get(step)) {
            if (hasAppliedFilter(
                (com.jetbrains.youtrackdb.internal.core.sql.executor.InternalExecutionPlan) nested)) {
              return true;
            }
          }
        } catch (ReflectiveOperationException e) {
          throw new AssertionError(e);
        }
      }
    }
    return false;
  }

  private void assertPlanRows(
      com.jetbrains.youtrackdb.internal.core.sql.executor.InternalExecutionPlan plan,
      BasicCommandContext ctx, java.util.List<RID> expected) {
    var actual = new java.util.HashSet<RID>();
    var stream = plan.start();
    try {
      while (stream.hasNext(ctx)) {
        actual.add(com.jetbrains.youtrackdb.internal.core.sql.executor.TraversalPreFilterHelper
            .toRid(stream.next(ctx).getProperty("c")));
      }
    } finally {
      stream.close(ctx);
    }
    assertEquals(new java.util.HashSet<>(expected), actual);
  }

  /** Every reusable SELECT container forwards reset to the MATCH sub-plan it owns. */
  @Test
  public void containersForwardReset() throws ReflectiveOperationException {
    var ctx = context();
    var nested =
        mock(com.jetbrains.youtrackdb.internal.core.sql.executor.InternalExecutionPlan.class);
    var product = new com.jetbrains.youtrackdb.internal.core.sql.executor.CartesianProductStep(
        ctx, false);
    product.addSubPlan(nested);
    product.reset();
    new com.jetbrains.youtrackdb.internal.core.sql.executor.SubQueryStep(
        nested, ctx, ctx, false).reset();
    new com.jetbrains.youtrackdb.internal.core.sql.executor.ParallelExecStep(
        java.util.List.of(nested), ctx, false).reset();
    var constructor = com.jetbrains.youtrackdb.internal.core.sql.executor.GlobalLetQueryStep.class
        .getDeclaredConstructor(
            com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIdentifier.class,
            com.jetbrains.youtrackdb.internal.core.sql.executor.InternalExecutionPlan.class,
            com.jetbrains.youtrackdb.internal.core.command.CommandContext.class, boolean.class);
    constructor.setAccessible(true);
    constructor.newInstance(
        new com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIdentifier("nested"),
        nested, ctx, false).reset();
    org.mockito.Mockito.verify(nested, org.mockito.Mockito.times(4)).reset(ctx);
  }

  private void schema() {
    session.execute("create class L extends E").close();
    session.execute("create class L2 extends L").close();
    session.execute("create class L3 extends L2").close();
    session.execute("create class H extends E").close();
  }

  private BasicCommandContext context() {
    var ctx = new BasicCommandContext();
    ctx.setDatabaseSession(session);
    return ctx;
  }

  private RidFilterDescriptor.EdgeRidLookup lookup(
      String label, String direction, RID target, BasicCommandContext ctx, boolean edges) {
    var expression = mock(SQLExpression.class);
    when(expression.execute((Result) null, ctx)).thenReturn(target);
    return new RidFilterDescriptor.EdgeRidLookup(label, direction, expression, edges);
  }

  private EdgeTraversal traversal(RidFilterDescriptor descriptor) {
    var a = new PatternNode();
    a.alias = "a";
    var b = new PatternNode();
    b.alias = "b";
    a.addEdge(new SQLMatchPathItem(-1), b);
    var edge = new EdgeTraversal(a.out.iterator().next(), true);
    edge.setIntersectionDescriptor(descriptor);
    return edge;
  }

  /**
   * Builds a real 100-entry forward bag. Sixty candidates link back to the anchor.
   * Applying the traverser's filter must retain all sixty and record actual activation.
   */
  private void check(String label, String direction, String... classes) {
    schema();
    session.begin();
    var anchor = session.newVertex("V");
    var source = session.newVertex("V");
    var expected = new ArrayList<RID>();
    for (int i = 0; i < 100; i++) {
      var candidate = session.newVertex("V");
      session.newRegularEdge("H", source, candidate);
      if (i < 60) {
        expected.add(candidate.getIdentity());
        String type = classes[i % classes.length];
        if (direction.equals("in")) {
          session.newRegularEdge(type, anchor, candidate);
        } else {
          session.newRegularEdge(type, candidate, anchor);
        }
      }
    }
    var ctx = context();
    var desc = lookup(label, direction, anchor.getIdentity(), ctx, false);
    var edge = traversal(desc);
    var traverser = new MatchEdgeTraverser(null, edge);
    var filtered = (Iterable<?>) traverser.applyPreFilter(
        source.getVertices(Direction.OUT, "H"), ctx);
    var actual = new ArrayList<RID>();
    for (var item : filtered) {
      actual.add(((VertexEntityImpl) item).getIdentity());
    }
    assertEquals(new java.util.HashSet<>(expected), new java.util.HashSet<>(actual));
    assertEquals("The real forward bag must be filtered", 1, edge.getPreFilterAppliedCount());
    assertEquals(60, edge.getPreFilterRidSetSize());
    session.rollback();
  }

  /** A missing parent bag must not hide subclass-only outgoing links. */
  @Test
  public void subclassOnlyOut() {
    check("L", "out", "L2");
  }

  /** A nonempty parent bag must not hide siblings stored in a subclass bag. */
  @Test
  public void mixedParentAndSubclassIn() {
    check("L", "in", "L", "L2");
  }

  /** Recursive expansion includes the grandchild, even with no child bag. */
  @Test
  public void severalSubclassLevels() {
    check("L", "out", "L", "L3");
  }

  /** Explicit E includes its bare-prefix bag and current schema subclasses. */
  @Test
  public void baseEdgeClass() {
    check("E", "out", "E", "L3");
  }

  /** BOTH must work with only incoming fields on the anchor. */
  @Test
  public void bothWithOutgoingSideAbsent() {
    check("L", "both", "L2", "L3");
  }

  /**
   * Duplicate directions, self-loops and parallel edges contribute entries to the cap,
   * but only distinct neighbors to the post-build ratio. The summed 129 exceeds 80.
   * Five additional incoming-only neighbors also prove that both directions are read.
   */
  @Test
  public void bothUsesDistinctCountAfterBuild() {
    schema();
    session.begin();
    var anchor = session.newVertex("V");
    for (int i = 0; i < 60; i++) {
      var neighbor = session.newVertex("V");
      session.newRegularEdge("L", anchor, neighbor);
      session.newRegularEdge("L2", neighbor, anchor);
    }
    session.newRegularEdge("L", anchor, anchor);
    session.newRegularEdge("L", anchor, anchor);
    for (int i = 0; i < 5; i++) {
      session.newRegularEdge("L3", session.newVertex("V"), anchor);
    }
    var ctx = context();
    var edge = traversal(lookup("L", "both", anchor.getIdentity(), ctx, false));
    assertEquals(66, edge.resolvePreFilter(ctx, 100).size());
    assertEquals(1, edge.getPreFilterAppliedCount());
    session.rollback();
  }

  /** Edge-mode collects all primary RIDs rather than the repeated neighbor RID. */
  @Test
  public void edgeRidModeIncludesSubclassEdges() {
    schema();
    session.begin();
    var anchor = session.newVertex("V");
    var neighbor = session.newVertex("V");
    var expected = new ArrayList<RID>();
    for (int i = 0; i < 60; i++) {
      expected.add(session.newRegularEdge(i % 2 == 0 ? "L" : "L3", neighbor, anchor)
          .getIdentity());
    }
    var ctx = context();
    var edge = traversal(lookup("L", "out", anchor.getIdentity(), ctx, true));
    var set = edge.resolvePreFilter(ctx, 100);
    assertEquals(60, set.size());
    expected.forEach(rid -> assertTrue(set.contains(rid)));
    assertEquals(1, edge.getPreFilterAppliedCount());
    session.rollback();
  }

  /** Each bag fits the cap, but their total exceeds it, so MATCH must fall back. */
  @Test
  public void aggregateCapRejectsBuild() {
    schema();
    session.begin();
    var anchor = session.newVertex("V");
    for (int i = 0; i < 60; i++) {
      session.newRegularEdge(i % 2 == 0 ? "L" : "L2", session.newVertex("V"), anchor);
    }
    var ctx = context();
    var desc = lookup("L", "out", anchor.getIdentity(), ctx, false);
    assertEquals(60, desc.estimatedSize(ctx, anchor.getIdentity()));
    GlobalConfiguration.QUERY_PREFILTER_MAX_RIDSET_SIZE.setValue(50);
    var edge = traversal(desc);
    assertNull(edge.resolvePreFilter(ctx, 100));
    assertEquals(PreFilterSkipReason.CAP_EXCEEDED, edge.getLastSkipReason());
    assertNull(desc.resolve(ctx, anchor.getIdentity()));
    session.rollback();
  }

  /** Pending additions and removals, including temporary RIDs, must be reflected. */
  @Test
  public void pendingTransactionEdits() {
    schema();
    session.begin();
    var anchor = session.newVertex("V");
    var removed = session.newRegularEdge("L2", session.newVertex("V"), anchor);
    removed.delete();
    var added = session.newVertex("V");
    session.newRegularEdge("L3", added, anchor);
    var ctx = context();
    var edge = traversal(lookup("L", "out", anchor.getIdentity(), ctx, false));
    var set = edge.resolvePreFilter(ctx, 100);
    assertEquals(1, set.size());
    assertTrue(set.contains(added.getIdentity()));
    assertEquals(1, edge.getPreFilterAppliedCount());
    session.rollback();
  }

  /**
   * SELECT keeps its literal bag even when a subclass has LINKLIST storage.
   * MATCH rejects the incomplete build and leaves the original condition in control.
   */
  @Test
  public void selectKeepsSingleFieldAndMatchFallsBack() throws ReflectiveOperationException {
    schema();
    session.execute("create class Anchor extends V").close();
    session.execute("create class Source extends V").close();
    session.execute("create property Anchor.in_L2 LINKLIST").close();
    session.begin();
    var anchor = session.newVertex("Anchor");
    var source = session.newVertex("Source");
    for (int i = 0; i < 100; i++) {
      var candidate = session.newVertex("V");
      session.newRegularEdge("H", source, candidate);
      if (i < 60) {
        session.newRegularEdge(i < 30 ? "L" : "L2", candidate, anchor);
      }
    }
    session.commit();
    session.begin();
    var ctx = context();
    var desc = lookup("L", "out", anchor.getIdentity(), ctx, false);
    assertEquals(-1, desc.estimatedSize(ctx, anchor.getIdentity()));
    var edge = traversal(desc);
    assertNull(edge.resolvePreFilter(ctx, 100));
    assertEquals(PreFilterSkipReason.BUILD_FAILED, edge.getLastSkipReason());
    String query = "select from (select expand(out('H')) from Source)"
        + " where out('L').@rid = " + anchor.getIdentity();
    // C1 intentionally pins the incomplete SELECT result, tracked by YTDB-1644.
    var legacy = new RidFilterDescriptor.EdgeRidLookup(
        "L", "out", desc.targetRidExpression(), false, true);
    assertEquals("Literal SELECT bag", 30, legacy.resolve(ctx, anchor.getIdentity()).size());
    var result = session.query(query);
    var rows = result.toList();
    // The baseline query returns 60 rows. Inspect its planned reader as well as pinning rows.
    assertEquals(result.getExecutionPlan().prettyPrint(0, 2), 60, rows.size());
    var subPlanField = com.jetbrains.youtrackdb.internal.core.sql.executor.SubQueryStep.class
        .getDeclaredField("subExecutionPlan");
    subPlanField.setAccessible(true);
    var innerPlan =
        (com.jetbrains.youtrackdb.internal.core.sql.executor.InternalExecutionPlan) subPlanField
            .get(result.getExecutionPlan().getSteps().getFirst());
    var expand = innerPlan.getSteps().stream()
        .filter(com.jetbrains.youtrackdb.internal.core.sql.executor.ExpandStep.class::isInstance)
        .findFirst().orElseThrow();
    var descriptorField = com.jetbrains.youtrackdb.internal.core.sql.executor.ExpandStep.class
        .getDeclaredField("ridFilterDescriptor");
    descriptorField.setAccessible(true);
    var plannedLookup = (RidFilterDescriptor) descriptorField.get(expand);
    assertEquals(30, plannedLookup.resolve(ctx, anchor.getIdentity()).size());
    assertTrue(result.getExecutionPlan().prettyPrint(0, 2).contains("rid filter: out('L')"));
    session.rollback();
  }

  /** A lookup planned before a hierarchy change resolves the execution-time snapshot. */
  @Test
  public void schemaChangeAfterPlanning() {
    schema();
    session.execute("create class Other extends E").close();
    session.begin();
    var anchor = session.newVertex("V");
    session.newRegularEdge("Other", session.newVertex("V"), anchor);
    session.commit();
    var ctx = context();
    var edge = traversal(lookup("L", "out", anchor.getIdentity(), ctx, false));
    var schema = session.getMetadata().getSchema();
    schema.getClass("Other").addSuperClass(schema.getClass("L"));
    session.begin();
    assertEquals(1, edge.resolvePreFilter(ctx, 100).size());
    assertEquals(1, edge.getPreFilterAppliedCount());
    session.rollback();
  }

  /**
   * Planning predates a hierarchy change. A second pass on the same plain MATCH step
   * must discard its old RID set and use the new session-visible schema snapshot.
   */
  @Test
  public void restartAfterSchemaChange() {
    schema();
    session.execute("create class Other extends E").close();
    session.begin();
    var anchor = session.newVertex("V");
    var oldNeighbor = session.newVertex("V");
    var newNeighbor = session.newVertex("V");
    session.newRegularEdge("L", oldNeighbor, anchor);
    session.newRegularEdge("Other", newNeighbor, anchor);
    session.commit();
    var ctx = context();
    var desc = lookup("L", "out", anchor.getIdentity(), ctx, false);
    var step = new MatchStep(ctx, traversal(desc), false);
    session.begin();
    assertEquals(1, step.edge.resolvePreFilter(ctx, 100).size());
    session.commit();
    var schema = session.getMetadata().getSchema();
    schema.getClass("Other").addSuperClass(schema.getClass("L"));
    step.reset();
    org.junit.Assert.assertNotSame(desc, step.edge.getIntersectionDescriptor());
    session.begin();
    var set = step.edge.resolvePreFilter(ctx, 100);
    assertEquals(2, set.size());
    assertTrue(set.contains(newNeighbor.getIdentity()));
    assertEquals(1, step.edge.getPreFilterAppliedCount());
    session.rollback();
  }
}
