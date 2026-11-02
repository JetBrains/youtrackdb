package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.sql.executor.InternalExecutionPlan;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;

/** Test-only access to the production translator and the pre-detection planner observation seam. */
public final class PresencePlanTestSupport {

  private PresencePlanTestSupport() {
  }

  public static Observed plan(Traversal.Admin<?, ?> traversal, DatabaseSessionEmbedded db) {
    return plan(traversal, db, false);
  }

  static Observed plan(Traversal.Admin<?, ?> traversal, DatabaseSessionEmbedded db,
      boolean ordered) {
    try {
      var translator = Class.forName("com.jetbrains.youtrackdb.internal.core.gremlin.translator"
          + ".strategy.GremlinToMatchTranslator");
      var translate = translator.getDeclaredMethod("translate", Traversal.Admin.class);
      translate.setAccessible(true);
      var translation = translate.invoke(null, traversal);
      assertThat(translation).as("real Gremlin translation").isNotNull();
      var inputsMethod = translation.getClass().getDeclaredMethod("inputs");
      inputsMethod.setAccessible(true);
      var parametersMethod = translation.getClass().getDeclaredMethod("inputParameters");
      parametersMethod.setAccessible(true);
      var inputs = (MatchPlanInputs) inputsMethod.invoke(translation);
      var planner = new Observed(inputs);
      if (ordered) {
        // The translator supports values().limit(). Supply an ordered planner tail on its real
        // inputs to exercise LINK inference without changing traversal recognition.
        var alias = inputs.promotedPresenceKeys().keySet().iterator().next();
        var statement =
            (com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchStatement) com.jetbrains.youtrackdb.internal.core.sql.SQLEngine
                .parse(
                    "MATCH {as:s} RETURN s ORDER BY `" + alias + "`.k", db);
        var order = MatchExecutionPlanner.class.getDeclaredField("orderBy");
        order.setAccessible(true);
        order.set(planner, statement.getOrderBy());
      }
      var context = new BasicCommandContext(db);
      @SuppressWarnings("unchecked")
      var parameters = (Map<Object, Object>) parametersMethod.invoke(translation);
      context.setInputParameters(parameters);
      planner.plan = planner.createExecutionPlan(context, false, false);
      return planner;
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError("translator inspection failed", failure);
    }
  }

  public static final class Observed extends MatchExecutionPlanner implements AutoCloseable {
    public final MatchPlanInputs inputs;
    public Map<String, Long> estimates;
    public List<EdgeTraversal> edges;
    public InternalExecutionPlan plan;

    private Observed(MatchPlanInputs inputs) {
      super(inputs);
      this.inputs = inputs;
    }

    @Override
    void observeRootPlanning(Map<String, Long> estimates, Set<String> prefetch,
        List<EdgeTraversal> edges) {
      this.estimates = Map.copyOf(estimates);
      this.edges = List.copyOf(edges);
    }

    public void assertFillState(boolean rebuild, boolean complete) {
      var alias = inputs.promotedPresenceKeys().keySet().iterator().next();
      var density = presenceDensities().get(alias);
      assertThat(density.candidateClass()).isEqualTo("Filled");
      assertThat(density.candidates()).isEqualTo(100);
      if (rebuild && !complete) {
        assertThat(density.source()).isEqualTo(PresenceDensity.Source.EMPTY_STATISTICS);
        assertThat(density.applied()).isFalse();
      } else {
        assertThat(density.source()).isEqualTo(PresenceDensity.Source.INDEX);
        assertThat(density.total()).isPositive();
        assertThat(density.total()).isLessThanOrEqualTo(100);
        assertThat(density.density()).isEqualTo(
            (double) (density.total() - density.nulls()) / density.candidates());
        if (complete) {
          assertThat(density.density()).isEqualTo(1);
        } else {
          assertThat(density.density()).isStrictlyBetween(0.0, 1.0);
        }
        assertThat(density.applied()).isTrue();
      }
      assertThat(edges).hasSize(1);
      assertThat(edges.getFirst().out).isEqualTo(complete || rebuild);
      assertThat(plan.prettyPrint(0, 2)).contains("FETCH FROM CLASS")
          .doesNotContain("FETCH FROM INDEX");
      if (!complete && !rebuild) {
        assertThat(plan.prettyPrint(0, 2)).contains("FETCH FROM CLASS Filled");
      }
    }

    @Override
    public void close() {
      plan.close();
    }
  }
}
