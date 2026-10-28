package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.AbstractMatchPlanStep;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.MultiPlanMatchStep;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.YTDBMatchPlanStep;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;

/** Untimed observation of the live plan, after opening but before exhaustion closes hash state. */
record DetachedChecksBenchmarkWitness(String engine, List<String> paths, int builtKeys,
    Map<Integer, Long> rows) {

  /** OR and NOT over OR use scoped checks. */
  static boolean expectTranslatedOr(String kind) {
    return kind.equals("OR") || kind.equals("NOT_OR");
  }

  static DetachedChecksBenchmarkWitness gremlin(Traversal.Admin<?, ?> traversal,
      Map<Integer, Long> rows) throws Exception {
    var boundaries = traversal.getSteps().stream().filter(AbstractMatchPlanStep.class::isInstance)
        .toList();
    assertThat(boundaries).hasSizeLessThanOrEqualTo(1);
    if (boundaries.isEmpty()) {
      return new DetachedChecksBenchmarkWitness("native", List.of("native"), 0, rows);
    }
    if (boundaries.getFirst() instanceof YTDBMatchPlanStep<?, ?> single) {
      return inspect("translated", single.getPlan(), rows);
    }
    var multi = (MultiPlanMatchStep<?, ?>) boundaries.getFirst();
    var paths = new ArrayList<String>();
    int keys = 0;
    for (var plan : multi.getPlans()) {
      keys += inspectSteps(plan.getSteps(), paths);
    }
    return new DetachedChecksBenchmarkWitness("translated", List.copyOf(paths), keys, rows);
  }

  static DetachedChecksBenchmarkWitness sql(ExecutionPlan plan, Map<Integer, Long> rows)
      throws Exception {
    return inspect("SQL", plan, rows);
  }

  private static DetachedChecksBenchmarkWitness inspect(String engine, ExecutionPlan plan,
      Map<Integer, Long> rows) throws Exception {
    var paths = new ArrayList<String>();
    int keys = inspectSteps(plan.getSteps(), paths);
    return new DetachedChecksBenchmarkWitness(engine, List.copyOf(paths), keys, rows);
  }

  private static int inspectSteps(List<? extends ExecutionStep> steps, List<String> paths)
      throws Exception {
    int keys = 0;
    for (var step : steps) {
      if (step instanceof FilterBooleanMatchStep) {
        var kind = FilterBooleanMatchStep.class.getDeclaredField("kind");
        kind.setAccessible(true);
        if (kind.get(step) == FilterBooleanMatchStep.Kind.PROBE) {
          var state = FilterBooleanMatchStep.class.getDeclaredField("keys");
          state.setAccessible(true);
          var built = (Set<?>) state.get(step);
          paths.add(built == null ? "nested-BOOLEAN" : "hash-BOOLEAN");
          keys += built == null ? 0 : built.size();
          // The tree owns membership keys. Its build helper does not own an open join hashSet.
          continue;
        }
      }
      if (step instanceof HashJoinMatchStep hash) {
        var mode = HashJoinMatchStep.class.getDeclaredField("joinMode");
        mode.setAccessible(true);
        paths.add("hash-" + mode.get(hash));
        // Presence of a hash step alone is insufficient: overflow can execute nested probes.
        var state = HashJoinMatchStep.class.getDeclaredField("hashSet");
        state.setAccessible(true);
        var built = (Set<?>) state.get(hash);
        assertThat(built).as("live hash build must not overflow or remain unopened").isNotNull();
        keys += built.size();
      } else if (step instanceof FilterNotMatchPatternStep) {
        paths.add("nested-NOT");
      } else if (step instanceof FilterExistsMatchPatternStep) {
        paths.add("nested-EXISTS");
      }
      keys += inspectSteps(step.getSubSteps(), paths);
    }
    return keys;
  }

  void verify(String kind, String scenario, Map<Integer, Long> expected, boolean limited) {
    boolean or = kind.equals("OR") || kind.equals("NOT_OR");
    assertThat(engine).isEqualTo(kind.equals("SQL_NOT") ? "SQL"
        : or && !expectTranslatedOr(kind) ? "native" : "translated");
    String check = kind.equals("EXISTS") ? "EXISTS" : "NOT";
    if (!or) {
      boolean hash = scenario.endsWith("hash") || scenario.equals("low-match");
      assertThat(paths).containsExactly(hash
          ? "hash-" + (kind.equals("EXISTS") ? "SEMI_JOIN" : "ANTI_JOIN")
          : "nested-" + check);
      if (hash) {
        assertThat(builtKeys).isPositive()
            .isLessThanOrEqualTo((int) DetachedChecksBenchmark.KEY_LIMIT);
      }
    } else if (!expectTranslatedOr(kind)) {
      assertThat(paths).containsExactly("native");
    } else {
      assertThat(paths).containsAnyOf("nested-BOOLEAN", "hash-BOOLEAN");
    }
    if (limited) {
      assertThat(rows.values().stream().mapToLong(Long::longValue).sum()).isEqualTo(1);
      assertThat(expected.keySet()).containsAll(rows.keySet());
    } else {
      assertThat(rows).containsExactlyInAnyOrderEntriesOf(expected);
    }
    assertThat(rows).isNotEmpty();
  }
}
