package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.common.concur.lock.LockException;
import com.jetbrains.youtrackdb.internal.common.concur.lock.ThreadInterruptedException;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.exception.CommandInterruptedException;
import com.jetbrains.youtrackdb.internal.core.exception.LiveQueryInterruptedException;
import com.jetbrains.youtrackdb.internal.core.exception.SessionNotActivatedException;
import com.jetbrains.youtrackdb.internal.core.exception.StorageException;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import com.jetbrains.youtrackdb.internal.core.sql.executor.SelectExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.parser.Pattern;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchFilter;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import com.jetbrains.youtrackdb.internal.core.sql.parser.YouTrackDBSql;
import com.jetbrains.youtrackdb.internal.core.sql.parser.YqlExecutionPlanCache;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/** Exercises the actual branch, its work counters and the original downstream pipeline. */
@Category(SequentialTest.class)
public class KnownEndpointExistsPlannerTest extends DbTestBase {

  private RecordIdInternal target;
  private RecordIdInternal otherTarget;
  private final List<RecordIdInternal> sources = new ArrayList<>();

  @Override
  public void beforeTest() throws Exception {
    super.beforeTest();
    session.createVertexClass("KnownSource");
    session.createVertexClass("KnownTarget");
    session.createEdgeClass("KnownLink");
    session.createEdgeClass("KnownOther");
    session.begin();
    var t = session.newVertex("KnownTarget");
    var other = session.newVertex("KnownTarget");
    var vertices = new ArrayList<com.jetbrains.youtrackdb.internal.core.db.record.record.Vertex>();
    for (int i = 0; i < 512; i++) {
      var source = session.newVertex("KnownSource");
      source.setProperty("n", i);
      vertices.add(source);
    }
    session.commit();
    target = (RecordIdInternal) t.getIdentity();
    otherTarget = (RecordIdInternal) other.getIdentity();
    vertices.forEach(v -> sources.add((RecordIdInternal) v.getIdentity()));
    sources.sort(RecordIdInternal::compareTo);
    // Commit allocates permanent RIDs independently of insertion order. Number the actual scan.
    session.begin();
    for (int i = 0; i < sources.size(); i++) {
      session.loadVertex(sources.get(i)).setProperty("n", i);
    }
    var endpoint = session.loadVertex(target);
    var otherEndpoint = session.loadVertex(otherTarget);
    // Reverse edge order differs from scan order. Parallel edges must not multiply source rows.
    session.loadVertex(sources.get(200)).addEdge(endpoint, "KnownLink");
    session.loadVertex(sources.get(7)).addEdge(endpoint, "KnownLink");
    session.loadVertex(sources.get(7)).addEdge(endpoint, "KnownLink");
    session.loadVertex(sources.get(7)).addEdge(otherEndpoint, "KnownOther");
    session.loadVertex(sources.get(7)).addEdge(otherEndpoint, "KnownOther");
    session.commit();
  }

  /** Target discovery deduplicates parallel edges and sorts sources before secured lazy reads. */
  @Test
  public void targetBranchKeepsScanOrderAndReportsRealWork() throws Exception {
    session.begin();
    var source = execute(KnownEndpointExistsStep.Path.SOURCE, "", Map.of());
    var targetRun = execute(KnownEndpointExistsStep.Path.TARGET, "", Map.of());
    assertThat(source.rows).containsExactly(7, 200);
    assertThat(targetRun.rows).isEqualTo(source.rows);
    assertThat(targetRun.counters.path).isEqualTo("target");
    assertThat(targetRun.counters.edgeReads).isEqualTo(3);
    assertThat(targetRun.counters.targetLoads).isEqualTo(1);
    assertThat(targetRun.counters.candidates).isEqualTo(2);
    assertThat(targetRun.counters.sourceRecordsRead).isEqualTo(2);
    assertThat(source.counters.path).isEqualTo("source");
    assertThat(source.counters.sourceRecordsRead).isEqualTo(512);
    session.rollback();
  }

  /** AUTO requires both full and first-row advantage and runs the low-degree branch here. */
  @Test
  public void lowDegreeAutomaticallyChoosesTargetAndCopyHasFreshCounters() throws Exception {
    session.begin();
    var plan = plan("MATCH {class:KnownSource,as:s} RETURN s.n as n", check(), Map.of());
    assertThat(plan.prettyPrint(0, 2)).contains("CURRENT:", "TARGET:", "first-row cost");
    var copy = (SelectExecutionPlan) plan.copy(context(Map.of()));
    var rows = drain(copy);
    assertThat(rows).containsExactly(7, 200);
    var chosen = (KnownEndpointExistsStep) copy.getSteps().getFirst();
    assertThat(chosen.counters().path).isEqualTo("target");
    assertThat(chosen.counters().edgeReads).isEqualTo(3);
    assertThat(((KnownEndpointExistsStep) plan.getSteps().getFirst()).counters().edgeReads)
        .isZero();
    assertThat(chosen.toResult(session).<Long>getProperty("sourceRecordsRead")).isEqualTo(2);
    assertThat(copy.prettyPrint(0, 2)).contains("path=target", "candidates=2");
    session.rollback();
  }

  /** Sorting precedes normal SKIP/LIMIT, and LIMIT 1 preserves the first passing scan row. */
  @Test
  public void pagingAndFirstRowMatchTheSourceBranch() throws Exception {
    session.begin();
    for (String suffix : List.of("LIMIT 1", "SKIP 1 LIMIT 1", "ORDER BY n DESC LIMIT 1")) {
      var source = execute(KnownEndpointExistsStep.Path.SOURCE, suffix, Map.of());
      var targetRun = execute(KnownEndpointExistsStep.Path.TARGET, suffix, Map.of());
      assertThat(targetRun.rows).as(suffix).isEqualTo(source.rows).hasSize(1);
      assertThat(targetRun.counters.path).isEqualTo("target");
    }
    try (var forced = KnownEndpointExistsStep.forcePath(KnownEndpointExistsStep.Path.TARGET)) {
      var plan = plan("MATCH {class:KnownSource,as:s} RETURN s.n as n", check(), Map.of());
      assertThat(plan.prettyPrint(0, 2)).doesNotContain("ORDER BY");
      assertThat(sources.get(7).compareTo(sources.get(200))).isNegative();
      var stream = plan.start();
      assertThat(stream.next(plan.getContext()).<Integer>getProperty("n"))
          .as("first row, plan %s", plan.prettyPrint(0, 2)).isEqualTo(7);
      assertThat(((KnownEndpointExistsStep) plan.getSteps().getFirst()).counters().candidates)
          .isEqualTo(1);
      stream.close(plan.getContext());
      plan.close();
    }
    session.rollback();
  }

  /** A positive hop retains parallel path rows, and another EXISTS and NOT run after it. */
  @Test
  public void downstreamMultiplicityAndDetachedCheckPositionsStayIntact() throws Exception {
    session.begin();
    var positive = "MATCH {class:KnownSource,as:s}.out('KnownOther'){as:y},"
        + " NOT {as:y}.out('KnownLink'){as:z} RETURN s.n as n";
    var checks = List.of(check(), "MATCH {as:y}.in('KnownOther'){as:z} RETURN y");
    try (var forced = KnownEndpointExistsStep.forcePath(KnownEndpointExistsStep.Path.TARGET)) {
      var plan = plan(positive, checks, Map.of());
      assertThat(drain(plan)).containsExactly(7, 7);
      var step = (KnownEndpointExistsStep) plan.getSteps().getFirst();
      assertThat(step.counters().path).isEqualTo("target");
      var targetPlan = step.getSubExecutionPlans().get(1);
      assertThat(targetPlan.getSteps()).noneMatch(HashJoinMatchStep.class::isInstance);
      assertThat(targetPlan.prettyPrint(0, 2)).contains("+ EXISTS (", "+ NOT (");
    }
    session.rollback();
  }

  /** The current branch can retain eager hashes, but the target branch probes all detached checks. */
  @Test
  public void branchSelectionPrecedesCurrentHashesAndTargetUsesOnlyProbes() throws Exception {
    var savedMinimum = GlobalConfiguration.QUERY_MATCH_HASH_JOIN_UPSTREAM_MIN.getValue();
    var savedThreshold = GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.getValue();
    GlobalConfiguration.QUERY_MATCH_HASH_JOIN_UPSTREAM_MIN.setValue(0L);
    GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.setValue(10_000L);
    session.begin();
    try {
      for (var path : List.of(KnownEndpointExistsStep.Path.SOURCE,
          KnownEndpointExistsStep.Path.TARGET)) {
        try (var forced = KnownEndpointExistsStep.forcePath(path)) {
          var plan = plan("MATCH {class:KnownSource,as:s} RETURN s.n as n",
              List.of(check(), "MATCH {as:s}.out('KnownOther'){as:y} RETURN s"), Map.of());
          var boundary = (KnownEndpointExistsStep) plan.getSteps().getFirst();
          assertThat(boundary.getSubExecutionPlans().getFirst().getSteps())
              .anyMatch(HashJoinMatchStep.class::isInstance);
          assertThat(boundary.getSubExecutionPlans().get(1).getSteps())
              .noneMatch(HashJoinMatchStep.class::isInstance);
          assertThat(drain(plan)).containsExactly(7);
          assertThat(boundary.counters().path)
              .isEqualTo(path == KnownEndpointExistsStep.Path.TARGET ? "target" : "source");
          if (path == KnownEndpointExistsStep.Path.TARGET) {
            assertThat(boundary.counters().sourceRecordsRead).isEqualTo(2);
            assertThat(boundary.counters().edgeReads).isEqualTo(3);
          }
        }
      }
    } finally {
      session.rollback();
      GlobalConfiguration.QUERY_MATCH_HASH_JOIN_UPSTREAM_MIN.setValue(savedMinimum);
      GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.setValue(savedThreshold);
    }
  }

  /** Equality unwraps a singleton collection but does not turn a two-RID parameter into targets. */
  @Test
  public void ridEqualityPreservesScalarAndCollectionMeaning() throws Exception {
    session.begin();
    for (Object value : List.of(target, List.of(target))) {
      try (var forced = KnownEndpointExistsStep.forcePath(KnownEndpointExistsStep.Path.TARGET)) {
        var plan = plan("MATCH {class:KnownSource,as:s} RETURN s.n as n",
            "MATCH {as:s}.out('KnownLink'){as:t,where:(@rid = :rid)} RETURN s",
            Map.of("rid", value));
        assertThat(drain(plan)).containsExactly(7, 200);
        assertThat(((KnownEndpointExistsStep) plan.getSteps().getFirst()).counters().path)
            .isEqualTo("target");
      }
    }
    for (Object value : List.of(List.of(target, otherTarget), "not-a-rid", "#40000:0")) {
      var invalid = plan("MATCH {class:KnownSource,as:s} RETURN s.n as n",
          "MATCH {as:s}.out('KnownLink'){as:t,where:(@rid = :rid)} RETURN s",
          Map.of("rid", value));
      assertThat(drain(invalid)).isEmpty();
      assertThat(((KnownEndpointExistsStep) invalid.getSteps().getFirst()).counters().path)
          .isEqualTo("source");
    }
    session.rollback();
  }

  /** Literal and parameter RID slots keep normal RID semantics for both forced paths. */
  @Test
  public void ridSlotsPreserveTheCurrentCheckMeaning() throws Exception {
    session.begin();
    for (var slot : List.of("rid:" + target, "rid:{\"@rid\": :rid}")) {
      for (var path : List.of(KnownEndpointExistsStep.Path.SOURCE,
          KnownEndpointExistsStep.Path.TARGET)) {
        try (var forced = KnownEndpointExistsStep.forcePath(path)) {
          var plan = plan("MATCH {class:KnownSource,as:s} RETURN s.n as n",
              "MATCH {as:s}.out('KnownLink'){as:t," + slot + "} RETURN s",
              Map.of("rid", target.toString()));
          assertThat(drain(plan)).containsExactly(7, 200);
          assertThat(((KnownEndpointExistsStep) plan.getSteps().getFirst()).counters().path)
              .isEqualTo(path == KnownEndpointExistsStep.Path.TARGET ? "target" : "source");
        }
      }
    }
    session.rollback();
  }

  /** A malformed RID slot must not raise an error when the source filter rejects every row. */
  @Test
  public void invalidRidSlotsDoNotAddErrorsBeforeSourceFiltering() {
    session.begin();
    for (var value : List.of("not-a-rid", "#40000:0")) {
      var plan = plan("MATCH {class:KnownSource,as:s,where:(n < 0)} RETURN s.n as n",
          "MATCH {as:s}.out('KnownLink'){as:t,rid:{\"@rid\": :rid}} RETURN s",
          Map.of("rid", value));
      assertThat(drain(plan)).isEmpty();
      assertThat(((KnownEndpointExistsStep) plan.getSteps().getFirst()).counters().path)
          .isEqualTo("source");
    }
    session.rollback();
  }

  /** Missing or non-vertex targets keep the complete current branch even under the test selector. */
  @Test
  public void unavailableTargetFallsBackWithoutChangingRows() throws Exception {
    session.begin();
    var edge = session.loadVertex(sources.get(7)).getEdges(
        com.jetbrains.youtrackdb.internal.core.db.record.record.Direction.OUT, "KnownLink")
        .iterator().next();
    var missing = RecordIdInternal.fromString("#" + target.getCollectionId() + ":999999", false);
    for (var rid : List.of(missing, edge.getIdentity())) {
      try (var forced = KnownEndpointExistsStep.forcePath(KnownEndpointExistsStep.Path.TARGET)) {
        var plan = plan("MATCH {class:KnownSource,as:s} RETURN s.n as n",
            "MATCH {as:s}.out('KnownLink'){as:t,where:(@rid = :rid)} RETURN s",
            Map.of("rid", rid));
        assertThat(drain(plan)).isEmpty();
        assertThat(((KnownEndpointExistsStep) plan.getSteps().getFirst()).counters().path)
            .isEqualTo("source");
      }
    }
    session.rollback();
  }

  /** Optional, recursive and record-dependent endpoints cannot introduce a target alternative. */
  @Test
  public void ineligibleCheckShapesKeepTheCurrentPlan() {
    session.begin();
    for (var check : List.of(
        "MATCH {as:s}.out('KnownLink'){as:t,optional:true,where:(@rid=" + target + ")} RETURN s",
        "MATCH {as:s}.out('KnownLink'){as:t,where:(@rid=$matched.s.@rid)} RETURN s",
        "MATCH {as:s}.out('KnownLink'){as:t,rid:{\"@rid\":$matched.s.@rid}} RETURN s",
        "MATCH {as:s}.out('KnownLink'){as:t,maxDepth:2,where:(@rid=" + target + ")} RETURN s")) {
      var plan = plan("MATCH {class:KnownSource,as:s} RETURN s.n as n", check, Map.of());
      assertThat(plan.getSteps()).noneMatch(KnownEndpointExistsStep.class::isInstance);
      plan.close();
    }
    session.rollback();
  }

  /** A selective index and an index used only for ordering retain their built starts. */
  @Test
  public void sourceIndexesNeverGainATargetAlternative() {
    session.getMetadata().getSchema().getClass("KnownSource")
        .createProperty("n",
            com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType.INTEGER);
    session.execute("CREATE INDEX KnownSource.n ON KnownSource(n) NOTUNIQUE").close();
    session.begin();
    for (String sql : List.of(
        "MATCH {class:KnownSource,as:s,where:(n=7)} RETURN s.n as n",
        "MATCH {class:KnownSource,as:s} RETURN s.n as n ORDER BY n")) {
      var plan = plan(sql, check(), Map.of());
      assertThat(plan.getSteps()).noneMatch(KnownEndpointExistsStep.class::isInstance);
      assertThat(drain(plan)).isNotEmpty();
    }
    session.rollback();
  }

  /** A copied parameterized plan resolves each run's targets, including short duplicate lists. */
  @Test
  public void targetsAreResolvedAgainWhenThePlanIsCopied() throws Exception {
    session.begin();
    var template = plan("MATCH {class:KnownSource,as:s} RETURN s.n as n",
        "MATCH {as:s}.out('KnownLink'){as:t,where:(@rid IN :rids)} RETURN s",
        Map.of("rids", List.of(target)));
    try (var forced = KnownEndpointExistsStep.forcePath(KnownEndpointExistsStep.Path.TARGET)) {
      var first = (SelectExecutionPlan) template.copy(context(Map.of(
          "rids", List.of(target, target, otherTarget))));
      assertThat(drain(first)).containsExactly(7, 200);
      assertThat(((KnownEndpointExistsStep) first.getSteps().getFirst()).counters().targetLoads)
          .isEqualTo(2);
      var second = (SelectExecutionPlan) template.copy(context(Map.of(
          "rids", List.of(otherTarget))));
      assertThat(drain(second)).isEmpty();
      assertThat(((KnownEndpointExistsStep) second.getSteps().getFirst()).counters().path)
          .isEqualTo("target");
      var invalid = (SelectExecutionPlan) template.copy(context(Map.of("rids", List.of(1))));
      assertThat(drain(invalid)).isEmpty();
      assertThat(((KnownEndpointExistsStep) invalid.getSteps().getFirst()).counters().path)
          .isEqualTo("source");
    }
    template.close();
    session.rollback();
  }

  /** Discovery ignores forward constraints, but source and far-node constraints still reject rows. */
  @Test
  public void normalFiltersRecheckTheCandidateSuperset() throws Exception {
    session.begin();
    for (var path : List.of(KnownEndpointExistsStep.Path.SOURCE,
        KnownEndpointExistsStep.Path.TARGET)) {
      try (var forced = KnownEndpointExistsStep.forcePath(path)) {
        var positive = "MATCH {class:KnownSource,as:s,where:(n >= 200)} RETURN s.n as n";
        var check = "MATCH {as:s}.out('KnownLink'){class:KnownTarget,as:t,"
            + "where:(@rid=" + target + " AND @class='KnownTarget')} RETURN s";
        assertThat(drain(plan(positive, check, Map.of()))).containsExactly(200);
        positive = "MATCH {class:KnownSource,as:s,where:($current.n >= 200)} RETURN s.n as n";
        assertThat(drain(plan(positive, check, Map.of()))).containsExactly(200);
        check = "MATCH {as:s}.out('KnownLink'){class:KnownSource,as:t,where:(@rid="
            + target + ")} RETURN s";
        assertThat(drain(plan(positive, check, Map.of()))).isEmpty();
      }
    }
    session.rollback();
  }

  /** A RID in only one OR branch or under NOT cannot restrict the whole detached check. */
  @Test
  public void ridConditionsOutsideMandatoryConjunctionKeepTheCurrentPlan() {
    session.begin();
    for (var where : List.of("@rid=" + target + " OR @class='KnownTarget'",
        "NOT (@rid=" + target + ")", "@rid IN [" + target + "] OR @class='KnownTarget'")) {
      var plan = plan("MATCH {class:KnownSource,as:s} RETURN s.n as n",
          "MATCH {as:s}.out('KnownLink'){as:t,where:(" + where + ")} RETURN s", Map.of());
      assertThat(plan.getSteps()).noneMatch(KnownEndpointExistsStep.class::isInstance);
      plan.close();
    }
    session.rollback();
  }

  /** One-shot RID iterables are not consumed during alternative preparation. */
  @Test
  public void longAndOneShotRidListsFallBackAtExecution() throws Exception {
    session.begin();
    var values = List.of((Iterable<RecordIdInternal>) () -> List.of(target).iterator());
    for (var value : values) {
      var plan = plan("MATCH {class:KnownSource,as:s} RETURN s.n as n",
          "MATCH {as:s}.out('KnownLink'){as:t,where:(@rid IN :rids)} RETURN s",
          Map.of("rids", value));
      try (var forced = KnownEndpointExistsStep.forcePath(KnownEndpointExistsStep.Path.TARGET)) {
        var stream = plan.start();
        try {
          assertThat(((KnownEndpointExistsStep) plan.getSteps().getFirst()).counters().path)
              .isEqualTo("source");
        } finally {
          stream.close(plan.getContext());
        }
      } finally {
        plan.close();
      }
    }
    session.rollback();
  }

  /** Early middle constraints shrink the superset, while normal forward probes still own results. */
  @Test
  public void middleClassAndEdgeFiltersAreSecuredCandidateConstraints() throws Exception {
    session.createVertexClass("KnownMiddle");
    session.createEdgeClass("KnownTail");
    session.begin();
    var middle = session.newVertex("KnownMiddle");
    var wrong = session.newVertex("KnownTarget");
    middle.setProperty("ok", true);
    wrong.setProperty("ok", true);
    session.loadVertex(sources.get(7)).addEdge(middle, "KnownLink").setProperty("weight", 1);
    session.loadVertex(sources.get(200)).addEdge(wrong, "KnownLink").setProperty("weight", 1);
    session.loadVertex(sources.get(300)).addEdge(middle, "KnownLink").setProperty("weight", 0);
    middle.addEdge(session.loadVertex(target), "KnownTail");
    wrong.addEdge(session.loadVertex(target), "KnownTail");
    var check = "MATCH {as:s}.outE('KnownLink'){class:KnownLink,as:e,where:(weight=1)}"
        + ".inV(){class:KnownMiddle,as:m,where:(ok=true)}"
        + ".out('KnownTail'){as:t,where:(@rid=" + target + ")} RETURN s";
    withTranslationSettings(() -> {
      for (var path : KnownEndpointExistsStep.Path.values()) {
        try (var forced = KnownEndpointExistsStep.forcePath(path)) {
          var plan = plan("MATCH {class:KnownSource,as:s} RETURN s.n as n", check, Map.of());
          var boundary = (KnownEndpointExistsStep) plan.getSteps().getFirst();
          assertThat(drain(plan)).containsExactly(7);
          if (path == KnownEndpointExistsStep.Path.TARGET) {
            assertThat(boundary.counters().path).isEqualTo("target");
            assertThat(boundary.counters().candidates).isEqualTo(1);
          }
        }
      }
    });
    session.rollback();
  }

  /** LIMIT 1 must keep a sparse two-hop TARGET winner after preparation and degree refinement. */
  @Test
  public void multiStepLimitKeepsTheClearTargetWinner() throws Exception {
    session.createVertexClass("KnownMiddle");
    session.createEdgeClass("KnownTail");
    session.begin();
    var middle = session.newVertex("KnownMiddle");
    session.loadVertex(sources.get(200)).addEdge(middle, "KnownLink");
    middle.addEdge(session.loadVertex(target), "KnownTail");
    var check = "MATCH {as:s}.out('KnownLink'){class:KnownMiddle,as:m}"
        + ".out('KnownTail'){as:t,where:(@rid=" + target + ")} RETURN s";
    withTranslationSettings(() -> {
      for (var path : KnownEndpointExistsStep.Path.values()) {
        try (var forced = KnownEndpointExistsStep.forcePath(path)) {
          var plan = plan("MATCH {class:KnownSource,as:s} RETURN s.n as n LIMIT 1", check,
              Map.of());
          var boundary = (KnownEndpointExistsStep) plan.getSteps().getFirst();
          assertThat(drain(plan)).containsExactly(200);
          if (path != KnownEndpointExistsStep.Path.SOURCE) {
            assertThat(boundary.counters().path).as(plan.prettyPrint(0, 2)).isEqualTo("target");
            assertThat(boundary.counters().budgetSwitch).isFalse();
            assertThat(boundary.counters().discoveryWork).isPositive()
                .isLessThanOrEqualTo(boundary.counters().workBudget);
          }
        }
      }
    });
    session.rollback();
  }

  /** A broad adjacency label must still enforce the narrower edge-node class during discovery. */
  @Test
  public void edgeNodeClassRejectsWrongClassesFromTheSameReverseWalk() throws Exception {
    session.begin();
    session.loadVertex(sources.get(7)).addEdge(session.loadVertex(target), "KnownLink")
        .setProperty("weight", 1);
    session.loadVertex(sources.get(200)).addEdge(session.loadVertex(target), "KnownOther")
        .setProperty("weight", 1);
    var check = "MATCH {as:s}.outE(){class:KnownLink,as:e,where:(weight=1)}"
        + ".inV(){as:t,where:(@rid=" + target + ")} RETURN s";
    withTranslationSettings(() -> {
      for (var path : KnownEndpointExistsStep.Path.values()) {
        try (var forced = KnownEndpointExistsStep.forcePath(path)) {
          var plan = plan("MATCH {class:KnownSource,as:s} RETURN s.n as n", check, Map.of());
          var boundary = (KnownEndpointExistsStep) plan.getSteps().getFirst();
          assertThat(drain(plan)).containsExactly(7);
          if (path != KnownEndpointExistsStep.Path.SOURCE) {
            assertThat(boundary.counters().path).as(plan.prettyPrint(0, 2)).isEqualTo("target");
            assertThat(boundary.counters().candidates).isEqualTo(1);
          }
        }
      }
    });
    session.rollback();
  }

  /** Collection-valued earlier labels retain SOURCE conversion instead of becoming class names. */
  @Test
  public void collectionMiddleLabelsKeepTheForwardSuperset() throws Exception {
    middleLabelParity(List.of("['KnownLink','KnownOther']", ":labels"),
        Map.of("labels", List.of("KnownLink", "KnownOther")), "source");
  }

  /** Quoted parameter labels use the same string-content conversion as forward navigation. */
  @Test
  public void quotedMiddleLabelsKeepTheForwardSuperset() throws Exception {
    middleLabelParity(List.of(":labels"), Map.of("labels", "'KnownLink'"), "target");
  }

  private void middleLabelParity(List<String> labels, Map<String, Object> params,
      String targetPath) throws Exception {
    session.createVertexClass("KnownMiddle");
    session.createEdgeClass("KnownTail");
    session.begin();
    var middle = session.newVertex("KnownMiddle");
    session.loadVertex(sources.get(7)).addEdge(middle, "KnownLink");
    middle.addEdge(session.loadVertex(target), "KnownTail");
    withTranslationSettings(() -> {
      for (var label : labels) {
        var check = "MATCH {as:s}.out(" + label + "){class:KnownMiddle,as:m}"
            + ".out('KnownTail'){as:t,where:(@rid=" + target + ")} RETURN s";
        List<Integer> expected;
        try (var forced = KnownEndpointExistsStep.forcePath(KnownEndpointExistsStep.Path.SOURCE)) {
          expected = drain(plan("MATCH {class:KnownSource,as:s} RETURN s.n as n", check, params));
        }
        // Forward navigation stringifies a collection parameter as one label. It does not flatten
        // this value into class names. Quoted scalar parameters do resolve to the linked class.
        assertThat(expected).containsExactlyElementsOf(
            targetPath.equals("source") ? List.of() : List.of(7));
        for (var path : KnownEndpointExistsStep.Path.values()) {
          try (var forced = KnownEndpointExistsStep.forcePath(path)) {
            var plan = plan("MATCH {class:KnownSource,as:s} RETURN s.n as n", check, params);
            var boundary = (KnownEndpointExistsStep) plan.getSteps().getFirst();
            assertThat(drain(plan)).as(label + " " + path).isEqualTo(expected);
            assertThat(boundary.counters().path).isEqualTo(
                path == KnownEndpointExistsStep.Path.SOURCE ? "source" : targetPath);
            if (targetPath.equals("source") && path != KnownEndpointExistsStep.Path.SOURCE) {
              assertThat(boundary.counters().reason).isEqualTo("unknown edge label");
            }
          }
        }
      }
    });
    session.rollback();
  }

  /** Repeated record classes share one policy lookup per class, direction and labels key. */
  @Test
  public void fieldPolicyCacheIsScopedToOneDecisionAndEveryRelevantKey() {
    session.begin();
    var policies = new KnownEndpointExistsStep.FieldPolicies(session);
    var out = com.jetbrains.youtrackdb.internal.core.db.record.record.Direction.OUT;
    var in = com.jetbrains.youtrackdb.internal.core.db.record.record.Direction.IN;
    for (int i = 0; i < 2000; i++) {
      assertThat(policies.has("KnownSource", out, List.of("KnownLink"))).isFalse();
      assertThat(policies.has("KnownTarget", in, List.of("KnownLink"))).isFalse();
    }
    assertThat(policies.size()).isEqualTo(2);
    assertThat(policies.has("KnownSource", in, List.of("KnownLink"))).isFalse();
    assertThat(policies.has("KnownSource", out, List.of("KnownOther"))).isFalse();
    assertThat(policies.has("KnownSource", out, List.of())).isFalse();
    assertThat(policies.size()).isEqualTo(5);
    var nextDecision = new KnownEndpointExistsStep.FieldPolicies(session);
    assertThat(nextDecision.size()).isZero();
    assertThat(nextDecision.has("KnownSource", out, List.of("KnownLink"))).isFalse();
    assertThat(nextDecision.size()).isEqualTo(1);
    session.rollback();
  }

  /** A skewed middle bag exhausts the refined LIMIT budget before a source row can be emitted. */
  @Test
  public void middleDiscoveryBudgetSwitchPrecedesEveryRowAndAppearsInProfile() throws Exception {
    session.createVertexClass("KnownMiddle");
    session.createEdgeClass("KnownTail");
    session.begin();
    var middle = session.newVertex("KnownMiddle");
    session.loadVertex(sources.get(7)).addEdge(middle, "KnownLink");
    middle.addEdge(session.loadVertex(target), "KnownTail");
    // The source oracle never visits these reverse-only entries. They model extreme degree skew
    // without increasing the schema's average fan-out estimate or changing any forward result.
    var bag =
        (com.jetbrains.youtrackdb.internal.core.db.record.ridbag.LinkBag) ((com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl) middle)
            .getPropertyInternal("in_KnownLink");
    int collection = session.getMetadata().getSchema().getClass("KnownLink").getCollectionIds()[0];
    for (int i = 0; i < 5000; i++) {
      bag.add(RecordIdInternal.fromString("#" + collection + ":" + (100000 + i), false),
          sources.get(7));
    }
    var check = "MATCH {as:s}.out('KnownLink'){class:KnownMiddle,as:m}"
        + ".out('KnownTail'){as:t,where:(@rid=" + target + ")} RETURN s";
    withTranslationSettings(() -> {
      for (var path : List.of(KnownEndpointExistsStep.Path.AUTO,
          KnownEndpointExistsStep.Path.TARGET)) {
        try (var forced = KnownEndpointExistsStep.forcePath(path)) {
          var plan =
              plan("MATCH {class:KnownSource,as:s} RETURN s.n as n LIMIT 1", check, Map.of());
          var boundary = (KnownEndpointExistsStep) plan.getSteps().getFirst();
          var stream = plan.start();
          try {
            var counters = boundary.counters();
            assertThat(counters.path).as(plan.prettyPrint(0, 2)).isEqualTo("source");
            assertThat(counters.budgetSwitch).isTrue();
            assertThat(counters.reason).isEqualTo("work budget exceeded");
            assertThat(counters.candidates).isZero();
            assertThat(counters.sourceRecordsRead).isZero();
            assertThat(counters.edgeReads).isBetween(1L, 4999L);
            assertThat(counters.discoveryWork).isLessThanOrEqualTo(counters.workBudget);
            assertThat(boundary.toResult(session).<Boolean>getProperty("budgetSwitch")).isTrue();
            assertThat(boundary.prettyPrint(0, 2)).contains("budgetSwitch=true")
                .doesNotContain("edgeReads", "targetLoads");
            assertThat(boundary.toResult(session).getPropertyNames())
                .doesNotContain("edgeReads", "targetLoads");
            assertThat(stream.stream(plan.getContext()).map(r -> r.<Integer>getProperty("n")))
                .containsExactly(7);
          } finally {
            stream.close(plan.getContext());
            plan.close();
          }
        }
      }
    });
    session.rollback();
  }

  /** Long finite lists use target discovery, but their preparation also obeys the work budget. */
  @Test
  public void longRidListsAreCostedAndBudgeted() throws Exception {
    session.begin();
    var check = "MATCH {as:s}.out('KnownLink'){as:t,where:(@rid IN :rids)} RETURN s";
    withTranslationSettings(() -> {
      for (var values : List.of(java.util.Collections.nCopies(9, target),
          java.util.Collections.nCopies(10000, target))) {
        List<Integer> expected;
        try (var forced = KnownEndpointExistsStep.forcePath(KnownEndpointExistsStep.Path.SOURCE)) {
          expected = drain(plan("MATCH {class:KnownSource,as:s} RETURN s.n as n", check,
              Map.of("rids", values)));
        }
        for (var path : KnownEndpointExistsStep.Path.values()) {
          try (var forced = KnownEndpointExistsStep.forcePath(path)) {
            var plan = plan("MATCH {class:KnownSource,as:s} RETURN s.n as n", check,
                Map.of("rids", values));
            var boundary = (KnownEndpointExistsStep) plan.getSteps().getFirst();
            assertThat(drain(plan)).isEqualTo(expected).containsExactly(7, 200);
            if (path != KnownEndpointExistsStep.Path.SOURCE) {
              boolean switchExpected = values.size() > 9;
              assertThat(boundary.counters().path).isEqualTo(switchExpected ? "source" : "target");
              assertThat(boundary.counters().budgetSwitch).isEqualTo(switchExpected);
              assertThat(boundary.counters().discoveryWork)
                  .isLessThanOrEqualTo(boundary.counters().workBudget);
              if (switchExpected) {
                assertThat(boundary.counters().targetLoads).isZero();
              }
            }
          }
        }
      }
    });
    session.rollback();
  }

  /** Prefetched, disconnected and known-RID roots cannot gain a target alternative. */
  @Test
  public void nonClassScanAndDisconnectedStartsKeepTheCurrentPlan() {
    session.createVertexClass("SmallSource");
    session.begin();
    session.newVertex("SmallSource");
    for (var sql : List.of(
        "MATCH {class:KnownSource,as:s,where:(@rid=" + sources.get(7) + ")} RETURN s.n as n",
        "MATCH {class:SmallSource,as:s} RETURN s.n as n",
        "MATCH {class:KnownSource,as:s},{class:KnownTarget,as:x} RETURN s.n as n")) {
      var plan = plan(sql, check(), Map.of());
      assertThat(plan.getSteps()).noneMatch(KnownEndpointExistsStep.class::isInstance);
      plan.close();
    }
    session.rollback();
  }

  /** High endpoint degree fails the first-row rule before any reverse edge is read. */
  @Test
  public void automaticChoiceRejectsAHubBeforeDiscovery() throws Exception {
    session.begin();
    for (var source : sources) {
      session.loadVertex(source).addEdge(session.loadVertex(target), "KnownLink");
    }
    var automatic = execute(KnownEndpointExistsStep.Path.AUTO, "", Map.of());
    assertThat(automatic.rows).hasSize(512);
    assertThat(automatic.counters.path).isEqualTo("source");
    assertThat(automatic.counters.reason).isEqualTo("cost or first-row margin");
    assertThat(automatic.counters.edgeReads).isZero();
    assertThat(automatic.counters.sourceRecordsRead).isEqualTo(512);
    session.rollback();
  }

  /** A function with constant arguments can still read the target record during its recheck. */
  @Test
  public void recordDependentFunctionsCannotDefineFixedTargets() throws Exception {
    session.begin();
    session.loadVertex(target).setProperty("choose", 1);
    var expression = "if(coalesce(eval('choose = 1'), false), :a, :b)";
    var check = "MATCH {as:s}.out('KnownLink'){as:t,where:(@rid = " + expression + ")} RETURN s";
    var params = Map.<String, Object>of("a", target, "b", otherTarget);
    withTranslationSettings(() -> {
      List<Integer> expected;
      try (var forced = KnownEndpointExistsStep.forcePath(KnownEndpointExistsStep.Path.SOURCE)) {
        expected = drain(plan("MATCH {class:KnownSource,as:s} RETURN s.n as n", check, params));
      }
      for (var path : KnownEndpointExistsStep.Path.values()) {
        try (var forced = KnownEndpointExistsStep.forcePath(path)) {
          var plan = plan("MATCH {class:KnownSource,as:s} RETURN s.n as n", check, params);
          assertThat(plan.getSteps()).noneMatch(KnownEndpointExistsStep.class::isInstance);
          assertThat(drain(plan)).isEqualTo(expected).containsExactly(7, 200);
        }
      }
    });
    session.rollback();
  }

  /** Resolution failures fall back, but errors actually visited by the retained check survive. */
  @Test
  public void arithmeticResolutionDoesNotAddAnUnvisitedError() throws Exception {
    session.begin();
    withTranslationSettings(() -> {
      for (var path : KnownEndpointExistsStep.Path.values()) {
        try (var forced = KnownEndpointExistsStep.forcePath(path)) {
          var plan = plan("MATCH {class:KnownSource,as:s} RETURN s.n as n",
              "MATCH {as:s}.out('MissingLink'){as:t,where:(@rid = 1 / :zero)} RETURN s",
              Map.of("zero", 0));
          assertThat(drain(plan)).isEmpty();
          assertThat(((KnownEndpointExistsStep) plan.getSteps().getFirst()).counters().path)
              .isEqualTo("source");
          var visited = plan("MATCH {class:KnownSource,as:s} RETURN s.n as n",
              "MATCH {as:s}.out('KnownLink'){as:t,where:(@rid = 1 / :zero)} RETURN s",
              Map.of("zero", 0));
          org.assertj.core.api.Assertions.assertThatThrownBy(() -> drain(visited))
              .isInstanceOf(ArithmeticException.class);
        }
      }
    });
    session.rollback();
  }

  /** An unvisited edge label cannot add an error, but a visited forward label still raises it. */
  @Test
  public void edgeLabelResolutionDoesNotAddAnUnvisitedError() throws Exception {
    session.begin();
    try {
      var check = "MATCH {as:s}.out(1 / :zero){as:t,where:(@rid=" + target + ")} RETURN s";
      withTranslationSettings(() -> {
        for (var path : KnownEndpointExistsStep.Path.values()) {
          try (var forced = KnownEndpointExistsStep.forcePath(path)) {
            var plan = plan("MATCH {class:KnownSource,as:s,where:(n < 0)} RETURN s.n as n",
                check, Map.of("zero", 0));
            assertThat(drain(plan)).isEmpty();
            var counters = ((KnownEndpointExistsStep) plan.getSteps().getFirst()).counters();
            assertThat(counters.path).isEqualTo("source");
            assertThat(counters.reason).isEqualTo(path == KnownEndpointExistsStep.Path.SOURCE
                ? "test selector" : "target attempt failed: ArithmeticException");
            assertThat(counters.targetLoads).isZero();
            assertThat(counters.edgeReads).isZero();
            var visited = plan("MATCH {class:KnownSource,as:s} RETURN s.n as n",
                check, Map.of("zero", 0));
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> drain(visited))
                .isInstanceOf(ArithmeticException.class);
          }
        }
      });
    } finally {
      session.rollback();
    }
  }

  /** The real plan cache does not freeze target eligibility from its first parameter binding. */
  @Test
  public void cachedBindingsCanSwitchBothWays() throws Exception {
    session.begin();
    String key = "known endpoint binding cache";
    var check = "MATCH {as:s}.out('KnownLink'){as:t,where:(@rid IN :rids)} RETURN s";
    var longList = java.util.Collections.nCopies(9, target);
    for (var initial : List.of(longList, List.of(target))) {
      var template = plan("MATCH {class:KnownSource,as:s} RETURN s.n as n", check,
          Map.of("rids", initial));
      YqlExecutionPlanCache.put(key, template, session, null);
      template.close();
      for (var binding : List.of(longList, List.of(target), longList)) {
        var cached = (SelectExecutionPlan) YqlExecutionPlanCache.get(key,
            context(Map.of("rids", binding)), session);
        assertThat(cached).isNotNull();
        assertThat(drain(cached)).containsExactly(7, 200);
        assertThat(((KnownEndpointExistsStep) cached.getSteps().getFirst()).counters().path)
            .isEqualTo("target");
      }
    }
    session.rollback();
  }

  /** Unindexed filter fields have no measured selectivity and use the neutral share of one. */
  @Test
  public void unmeasuredFiltersDoNotInventASelectivityBenefit() {
    session.begin();
    var plan = plan("MATCH {class:KnownSource,as:s,where:(n >= 0)} RETURN s.n as n",
        check(), Map.of());
    assertThat(
        ((KnownEndpointExistsStep) plan.getSteps().getFirst()).filterShare(plan.getContext()))
        .isEqualTo(1);
    assertThat(drain(plan)).containsExactly(7, 200);
    session.rollback();
  }

  /** Index statistics supply measured selectivity when they are available to a retained scan. */
  @Test
  public void availableIndexStatisticsSupplyFilterSelectivity() {
    session.begin();
    var plan =
        plan("MATCH {class:KnownSource,as:s,where:(n=7)} RETURN s.n as n", check(), Map.of());
    var step = (KnownEndpointExistsStep) plan.getSteps().getFirst();
    session.rollback();
    session.getMetadata().getSchema().getClass("KnownSource")
        .createProperty("n",
            com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType.INTEGER);
    session.execute("CREATE INDEX KnownSource.n ON KnownSource(n) NOTUNIQUE").close();
    session.begin();
    assertThat(step.filterShare(context(Map.of()))).isBetween(0.0, 0.01);
    plan.close();
    session.rollback();
  }

  /** An indexed RHS failure falls back even when an empty target would skip every source filter. */
  @Test
  public void filterEstimationFailureRunsTheCurrentPathForAZeroDegreeTarget() throws Exception {
    session.begin();
    // Add the index after planning so the cached class-scan branch retains its source filter.
    var plan = plan("MATCH {class:KnownSource,as:s,where:(n = 1 / :zero)} RETURN s.n as n",
        "MATCH {as:s}.out('KnownLink'){as:t,where:(@rid=" + otherTarget + ")} RETURN s",
        Map.of("zero", 1));
    var step = (KnownEndpointExistsStep) plan.getSteps().getFirst();
    session.rollback();
    session.getMetadata().getSchema().getClass("KnownSource")
        .createProperty("n",
            com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType.INTEGER);
    session.execute("CREATE INDEX KnownSource.n ON KnownSource(n) NOTUNIQUE").close();
    session.begin();
    try {
      withTranslationSettings(() -> {
        // The successful binding proves that estimation reaches nonempty index statistics.
        assertThat(step.filterShare(context(Map.of("zero", 1)))).isBetween(0.0, 0.01);
        assertThatThrownBy(() -> step.filterShare(context(Map.of("zero", 0))))
            .isInstanceOf(ArithmeticException.class);
        for (var path : KnownEndpointExistsStep.Path.values()) {
          try (var forced = KnownEndpointExistsStep.forcePath(path)) {
            var valid = (SelectExecutionPlan) plan.copy(context(Map.of("zero", 1)));
            assertThat(drain(valid)).isEmpty();
            var validCounters = ((KnownEndpointExistsStep) valid.getSteps().getFirst()).counters();
            assertThat(validCounters.path)
                .isEqualTo(path == KnownEndpointExistsStep.Path.SOURCE ? "source" : "target");
            assertThat(validCounters.reason).isEqualTo(path == KnownEndpointExistsStep.Path.AUTO
                ? "cost and first-row margin" : "test selector");
            var visited = (SelectExecutionPlan) plan.copy(context(Map.of("zero", 0)));
            assertThatThrownBy(() -> drain(visited)).isInstanceOf(ArithmeticException.class);
            var counters = ((KnownEndpointExistsStep) visited.getSteps().getFirst()).counters();
            assertThat(counters.path).isEqualTo("source");
            assertThat(counters.reason).isEqualTo(path == KnownEndpointExistsStep.Path.SOURCE
                ? "test selector" : "target attempt failed: ArithmeticException");
            assertThat(counters.candidates).isZero();
          }
        }
      });
    } finally {
      plan.close();
      session.rollback();
    }
  }

  /** Every guarded stage discards recoverable failures and retains the complete current result. */
  @Test
  public void targetOnlyFailuresKeepTheCurrentRowsAndReportTheReason() throws Exception {
    session.begin();
    try {
      for (var stage : List.of("plan", "plan preparation", "decision", "reverse walk",
          "target copy")) {
        for (var path : KnownEndpointExistsStep.Path.values()) {
          try (var forced = KnownEndpointExistsStep.forcePath(path);
              var injected = KnownEndpointExistsStep.injectFailure(stage,
                  new IllegalStateException("private policy data"))) {
            var plan = plan("MATCH {class:KnownSource,as:s} RETURN s.n as n", check(), Map.of());
            assertThat(drain(plan)).as(stage + " " + path).containsExactly(7, 200);
            if (stage.startsWith("plan")) {
              assertThat(plan.getSteps()).noneMatch(KnownEndpointExistsStep.class::isInstance);
            } else {
              var counters = ((KnownEndpointExistsStep) plan.getSteps().getFirst()).counters();
              assertThat(counters.path).isEqualTo("source");
              assertThat(counters.reason).isEqualTo(path == KnownEndpointExistsStep.Path.SOURCE
                  ? "test selector" : "target attempt failed: IllegalStateException");
              assertThat(counters.candidates).isZero();
              assertThat(counters.sourceRecordsRead).isEqualTo(512);
              if (stage.equals("target copy") && path != KnownEndpointExistsStep.Path.SOURCE) {
                assertThat(counters.edgeReads).isEqualTo(3);
                assertThat(counters.targetLoads).isEqualTo(1);
              }
            }
          }
        }
      }
    } finally {
      session.rollback();
    }
  }

  /** Cancellation, timeout and inactive-session failures must escape each guarded stage unchanged. */
  @Test
  public void nonRecoverableFailuresNeverStartTheFallbackBranch() throws Exception {
    session.begin();
    try {
      for (var stage : List.of("plan", "plan preparation", "decision", "reverse walk",
          "target copy")) {
        for (var path : KnownEndpointExistsStep.Path.values()) {
          for (var failure : List.of(new CommandInterruptedException(session, "cancelled"),
              new IllegalStateException(new CommandInterruptedException(session, "cancelled")),
              new LiveQueryInterruptedException(session, "cancelled"),
              new IllegalStateException(new LiveQueryInterruptedException(session, "cancelled")),
              new TimeoutException("timeout"), new SessionNotActivatedException(databaseName))) {
            try (var forced = KnownEndpointExistsStep.forcePath(path);
                var injected = KnownEndpointExistsStep.injectFailure(stage, failure)) {
              if (stage.startsWith("plan")) {
                assertThatThrownBy(() -> plan("MATCH {class:KnownSource,as:s} RETURN s.n as n",
                    check(), Map.of())).isSameAs(failure);
              } else {
                var plan =
                    plan("MATCH {class:KnownSource,as:s} RETURN s.n as n", check(), Map.of());
                if (path == KnownEndpointExistsStep.Path.SOURCE) {
                  assertThat(drain(plan)).containsExactly(7, 200);
                  var counters = ((KnownEndpointExistsStep) plan.getSteps().getFirst()).counters();
                  assertThat(counters.reason).isEqualTo("test selector");
                } else {
                  assertThatThrownBy(() -> drain(plan)).isSameAs(failure);
                  var counters = ((KnownEndpointExistsStep) plan.getSteps().getFirst()).counters();
                  assertThat(counters.sourceRecordsRead).isZero();
                  assertThat(counters.reason).doesNotStartWith("target attempt failed:");
                }
              }
              assertThat(Thread.currentThread().isInterrupted()).as(stage + " " + path).isFalse();
            }
          }
        }
      }
    } finally {
      session.rollback();
    }
  }

  /** Storage and lock interrupt wrappers escape every stage before source startup with flag set. */
  @Test
  public void threadInterruptFailuresRestoreTheFlagAndNeverStartFallback() throws Exception {
    session.begin();
    try {
      for (var stage : List.of("plan", "plan preparation", "decision", "reverse walk",
          "target copy")) {
        for (var path : List.of(KnownEndpointExistsStep.Path.AUTO,
            KnownEndpointExistsStep.Path.TARGET)) {
          for (var failure : threadInterruptFailures()) {
            try (var forced = KnownEndpointExistsStep.forcePath(path);
                var injected = KnownEndpointExistsStep.injectFailure(stage, failure)) {
              assertThat(Thread.currentThread().isInterrupted()).isFalse();
              if (stage.startsWith("plan")) {
                assertThatThrownBy(() -> plan("MATCH {class:KnownSource,as:s} RETURN s.n as n",
                    check(), Map.of())).as(stage + " " + path).isSameAs(failure);
              } else {
                var plan =
                    plan("MATCH {class:KnownSource,as:s} RETURN s.n as n", check(), Map.of());
                assertThatThrownBy(() -> drain(plan)).as(stage + " " + path).isSameAs(failure);
                var counters = ((KnownEndpointExistsStep) plan.getSteps().getFirst()).counters();
                assertThat(counters.sourceRecordsRead).isZero();
                assertThat(counters.path).isEqualTo("not started");
                assertThat(counters.reason).doesNotStartWith("target attempt failed:");
              }
              assertThat(Thread.currentThread().isInterrupted()).as(stage + " " + path).isTrue();
            } finally {
              // The engine must retain the flag for the caller. Only this test consumes it.
              Thread.interrupted();
            }
          }
        }
      }
    } finally {
      session.rollback();
    }
  }

  /** Cause traversal terminates on cycles and still finds an interrupt inside a cyclic chain. */
  @Test
  public void cyclicFailureCausesDoNotHideInterruptsOrPreventRecoverableFallback() {
    var first = new IllegalStateException("first");
    var second = new IllegalStateException("second", first);
    first.initCause(second);
    KnownEndpointExistsStep.rethrowIfNotRecoverable(first, session, false);
    assertThat(Thread.currentThread().isInterrupted()).isFalse();
    var interrupt = new ThreadInterruptedException("interrupt");
    var wrapped = new IllegalStateException("wrapped", interrupt);
    interrupt.initCause(wrapped);
    try {
      assertThatThrownBy(
          () -> KnownEndpointExistsStep.rethrowIfNotRecoverable(wrapped, session, false))
          .isSameAs(wrapped);
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }
  }

  private List<RuntimeException> threadInterruptFailures() {
    var cacheInterrupt = new ThreadInterruptedException("cache wait interrupted");
    cacheInterrupt.initCause(new InterruptedException("await"));
    var storageInterrupt = new StorageException(databaseName, "page load interrupted");
    storageInterrupt.initCause(new InterruptedException("acquire"));
    var lockInterrupt = new LockException("lock wait interrupted");
    lockInterrupt.initCause(new InterruptedException("tryLock"));
    return List.of(new ThreadInterruptedException("interrupt"), cacheInterrupt,
        new IllegalStateException(new ThreadInterruptedException("wrapped interrupt")),
        storageInterrupt, new IllegalStateException(new IllegalStateException(storageInterrupt)),
        lockInterrupt);
  }

  /** A closed session or lost active transaction forbids fallback, but no initial tx is allowed. */
  @Test
  public void unusableSessionStateRethrowsTheOriginalFailure() {
    var db = mock(DatabaseSessionEmbedded.class);
    var failure = new IllegalStateException("unusable");
    KnownEndpointExistsStep.rethrowIfNotRecoverable(failure, db, false);
    when(db.isTxActive()).thenReturn(true);
    KnownEndpointExistsStep.rethrowIfNotRecoverable(failure, db, true);
    when(db.isTxActive()).thenReturn(false);
    assertThatThrownBy(() -> KnownEndpointExistsStep.rethrowIfNotRecoverable(failure, db, true))
        .isSameAs(failure);
    when(db.isClosed()).thenReturn(true);
    assertThatThrownBy(() -> KnownEndpointExistsStep.rethrowIfNotRecoverable(failure, db, false))
        .isSameAs(failure);
  }

  /** Cleanup preserves recoverable fallback but propagates timeout and restores interrupt flags. */
  @Test
  public void partialAttemptCleanupUsesTheSameNonRecoverableRule() {
    var partial = mock(SelectExecutionPlan.class);
    var failure = new IllegalStateException("attempt");
    org.mockito.Mockito.doThrow(new IllegalStateException("cleanup")).when(partial).close();
    KnownEndpointExistsStep.discardAttempt(partial, failure, session, false);
    var timeout = new TimeoutException("cleanup timeout");
    org.mockito.Mockito.doThrow(timeout).when(partial).close();
    assertThatThrownBy(
        () -> KnownEndpointExistsStep.discardAttempt(partial, failure, session, false))
        .isSameAs(timeout);
    for (var interrupted : threadInterruptFailures()) {
      org.mockito.Mockito.doThrow(interrupted).when(partial).close();
      try {
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
        assertThatThrownBy(
            () -> KnownEndpointExistsStep.discardAttempt(partial, failure, session, false))
            .isSameAs(interrupted);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
      } finally {
        Thread.interrupted();
      }
    }
  }

  /** Fixed-target proof accepts only record-independent AST forms without evaluating them. */
  @Test
  public void fixedExpressionProofExcludesRecordAccessAndFunctions() throws Exception {
    var ctx = context(Map.of());
    for (var text : List.of("'x'", ":rid", "['a', :rid]", "#23:4", "(1 + :zero)",
        "1 / :zero", "true", "null")) {
      var expression = new YouTrackDBSql(new ByteArrayInputStream(
          text.getBytes(StandardCharsets.UTF_8))).Expression();
      assertThat(KnownEndpointExistsAccess.fixedExpression(expression, ctx)).as(text).isTrue();
    }
    for (var text : List.of("eval('x = 1')", "$current", "name", ":rid.foo",
        "{'x': :rid}", "['x', eval('x=1')]", "(SELECT FROM KnownSource)")) {
      var expression = new YouTrackDBSql(new ByteArrayInputStream(
          text.getBytes(StandardCharsets.UTF_8))).Expression();
      assertThat(KnownEndpointExistsAccess.fixedExpression(expression, ctx)).as(text).isFalse();
    }
  }

  /** PROFILE exposes executed inner steps and their costs on whichever branch actually ran. */
  @Test
  public void profileReportsTheExecutedBranchRatherThanItsTemplate() throws Exception {
    session.begin();
    for (var path : List.of(KnownEndpointExistsStep.Path.SOURCE,
        KnownEndpointExistsStep.Path.TARGET)) {
      try (var forced = KnownEndpointExistsStep.forcePath(path)) {
        var plan = plan("MATCH {class:KnownSource,as:s} RETURN s.n as n", check(), Map.of());
        var boundary = (KnownEndpointExistsStep) plan.getSteps().getFirst();
        int selected = path == KnownEndpointExistsStep.Path.SOURCE ? 0 : 1;
        var template = boundary.getSubExecutionPlans().get(selected);
        assertThat(drain(plan)).containsExactly(7, 200);
        var running = boundary.getSubExecutionPlans().get(selected);
        assertThat(running).isNotSameAs(template);
        assertThat(running.getSteps()).anyMatch(step -> step.getCost() > 0);
        List<com.jetbrains.youtrackdb.internal.core.query.Result> serialized =
            boundary.toResult(session).getProperty("subExecutionPlans");
        List<com.jetbrains.youtrackdb.internal.core.query.Result> steps =
            serialized.get(selected).getProperty("steps");
        assertThat(steps).anyMatch(step -> step.<Long>getProperty("cost") > 0);
        assertThat(boundary.prettyPrint(0, 2)).contains(running.prettyPrint(2, 2));
      }
    }
    session.rollback();
  }

  /** Every counter has a publication class, and structured/text keys agree in each state. */
  @Test
  public void profileOutputClassifiesEveryCounterInEveryOutcome() {
    var always = Set.of("path", "reason", "candidates", "sourceRecordsRead", "budgetSwitch");
    var untilSwitch = Set.of("edgeReads", "targetLoads");
    var internal = Set.of("workBudget", "discoveryWork");
    var classified = new LinkedHashSet<>(always);
    assertThat(classified.addAll(untilSwitch)).isTrue();
    assertThat(classified.addAll(internal)).isTrue();
    assertThat(Arrays.stream(KnownEndpointExistsStep.Counters.class.getDeclaredFields())
        .filter(field -> !Modifier.isStatic(field.getModifiers())).map(field -> field.getName()))
        .containsExactlyInAnyOrderElementsOf(classified);
    session.begin();
    var plan = plan("MATCH {class:KnownSource,as:s} RETURN s.n as n", check(), Map.of());
    try {
      var choice = (KnownEndpointExistsStep) plan.getSteps().getFirst();
      for (var state : List.of("not started", "target", "source")) {
        for (boolean switched : List.of(false, true)) {
          var counters = choice.counters();
          counters.path = state;
          counters.reason = switched ? "work budget exceeded" : "test selector";
          counters.budgetSwitch = switched;
          counters.edgeReads = 19;
          counters.targetLoads = 7;
          counters.workBudget = 9000;
          counters.discoveryWork = 8000;
          var expected = new LinkedHashSet<>(always);
          if (!switched) {
            expected.addAll(untilSwitch);
          }
          var result = choice.toResult(session);
          var framework = Set.of("name", "type", "javaType", "cost", "subSteps",
              "description", "subExecutionPlans");
          var published = new LinkedHashMap<String, Object>();
          result.getPropertyNames().stream().filter(name -> !framework.contains(name))
              .forEach(name -> published.put(name, result.getProperty(name)));
          assertThat(published.keySet()).as(state + " switched=" + switched)
              .containsExactlyInAnyOrderElementsOf(expected);
          var text = choice.prettyPrint(0, 2).lines().findFirst().orElseThrow();
          var bracket = text.substring(text.indexOf(" [") + 2, text.lastIndexOf(']'));
          var textValues = new LinkedHashMap<String, String>();
          for (var entry : bracket.split(", ")) {
            var pair = entry.split("=", 2);
            textValues.put(pair[0], pair[1]);
          }
          // Result property names are unordered. Check key equality and text order separately.
          assertThat(textValues.keySet()).containsExactlyInAnyOrderElementsOf(published.keySet());
          assertThat(textValues.keySet()).containsExactlyElementsOf(switched
              ? List.of("path", "reason", "candidates", "sourceRecordsRead", "budgetSwitch")
              : List.of("path", "reason", "candidates", "edgeReads", "targetLoads",
                  "sourceRecordsRead", "budgetSwitch"));
          published.forEach((name, value) -> assertThat(textValues.get(name))
              .as(name).isEqualTo(value.toString()));
          choice.setProfilingEnabled(false);
          assertThat(choice.prettyPrint(0, 2).lines().findFirst().orElseThrow())
              .doesNotContain(" [");
          choice.setProfilingEnabled(true);
        }
      }
    } finally {
      plan.close();
      session.rollback();
    }
  }

  /** Reverse-only legacy or dropped-collection entries must not bypass source filtering. */
  @Test
  public void unsupportedReverseEntriesFallBackBeforeAnyRow() throws Exception {
    var dropped = session.createVertexClass("DroppedCandidate");
    int collection = dropped.getCollectionIds()[0];
    session.execute("DROP CLASS DroppedCandidate").close();
    session.begin();
    var vertex =
        (com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl) session.loadVertex(target);
    var bag = (com.jetbrains.youtrackdb.internal.core.db.record.ridbag.LinkBag) vertex
        .getPropertyInternal("in_KnownLink");
    var dangling = RecordIdInternal.fromString("#" + collection + ":0", false);
    for (boolean legacy : List.of(false, true)) {
      var primary = legacy ? sources.getFirst() : dangling;
      var secondary = legacy ? sources.getFirst() : dangling;
      if (!legacy) {
        primary = target;
      }
      bag.add(primary, secondary);
      try (var forced = KnownEndpointExistsStep.forcePath(KnownEndpointExistsStep.Path.TARGET)) {
        var plan = plan("MATCH {class:KnownSource,as:s,where:(n < 0)} RETURN s.n as n",
            check(), Map.of());
        assertThat(drain(plan)).isEmpty();
        var counters = ((KnownEndpointExistsStep) plan.getSteps().getFirst()).counters();
        assertThat(counters.path).isEqualTo("source");
        assertThat(counters.reason).isEqualTo(legacy ? "legacy edge entry"
            : "unknown candidate collection");
      }
      bag.remove(primary);
    }
    session.rollback();
  }

  @FunctionalInterface
  private interface CheckedAction {
    void run() throws Exception;
  }

  // These expression regressions exercise the shared MATCH executor under either setting.
  private void withTranslationSettings(CheckedAction action) throws Exception {
    var configuration = session.getConfiguration();
    boolean previous = configuration.getValueAsBoolean(
        GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED);
    try {
      for (boolean translated : List.of(false, true)) {
        configuration.setValue(GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED,
            translated);
        action.run();
      }
    } finally {
      configuration.setValue(GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED,
          previous);
    }
  }

  private String check() {
    return "MATCH {as:s}.out('KnownLink'){as:t,where:(@rid=" + target + ")} RETURN s";
  }

  private record Run(List<Integer> rows, KnownEndpointExistsStep.Counters counters) {
  }

  private Run execute(KnownEndpointExistsStep.Path path, String suffix, Map<String, Object> params)
      throws Exception {
    try (var forced = KnownEndpointExistsStep.forcePath(path)) {
      var plan = plan("MATCH {class:KnownSource,as:s} RETURN s.n as n " + suffix, check(), params);
      var rows = drain(plan);
      return new Run(rows, ((KnownEndpointExistsStep) plan.getSteps().getFirst()).counters());
    }
  }

  private List<Integer> drain(SelectExecutionPlan plan) {
    ExecutionStream stream = null;
    try {
      stream = plan.start();
      return stream.stream(plan.getContext()).map(r -> r.<Integer>getProperty("n")).toList();
    } finally {
      if (stream != null) {
        stream.close(plan.getContext());
      }
      plan.close();
    }
  }

  private SelectExecutionPlan plan(String positive, String check, Map<String, Object> params) {
    return plan(positive, List.of(check), params);
  }

  private SelectExecutionPlan plan(String positive, List<String> checks,
      Map<String, Object> params) {
    var statement = parse(positive);
    var pattern = new Pattern();
    statement.getMatchExpressions().forEach(pattern::addExpression);
    var classes = new HashMap<String, String>();
    var filters = new HashMap<String, SQLWhereClause>();
    for (var expression : statement.getMatchExpressions()) {
      var nodes = new ArrayList<SQLMatchFilter>();
      nodes.add(expression.getOrigin());
      expression.getItems().forEach(i -> nodes.add(i.getFilter()));
      for (var node : nodes) {
        if (node.getClassName(context(params)) != null) {
          classes.put(node.getAlias(), node.getClassName(context(params)));
        }
        if (node.getFilter() != null) {
          filters.put(node.getAlias(), node.getFilter());
        }
      }
    }
    var inputs = MatchPlanInputs.builder(pattern).aliasClasses(classes).aliasFilters(filters)
        .existsMatchExpressions(checks.stream().map(KnownEndpointExistsPlannerTest::parse)
            .map(s -> s.getMatchExpressions().getFirst()).toList())
        .notMatchExpressions(statement.getNotMatchExpressions())
        .returnItems(statement.getReturnItems()).returnAliases(statement.getReturnAliases())
        .returnNestedProjections(statement.getReturnNestedProjections())
        .limit(statement.getLimit()).skip(statement.getSkip()).orderBy(statement.getOrderBy())
        .build();
    return (SelectExecutionPlan) new MatchExecutionPlanner(inputs)
        .createExecutionPlan(context(params), true, false);
  }

  private BasicCommandContext context(Map<String, Object> params) {
    var context = new BasicCommandContext();
    context.setDatabaseSession(session);
    context.setInputParameters(new HashMap<>(params));
    return context;
  }

  private static SQLMatchStatement parse(String sql) {
    try {
      return (SQLMatchStatement) new YouTrackDBSql(new ByteArrayInputStream(
          sql.getBytes(StandardCharsets.UTF_8))).parse();
    } catch (Exception error) {
      throw new AssertionError(sql, error);
    }
  }
}
