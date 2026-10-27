package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import static org.assertj.core.api.Assertions.assertThat;

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

  /** Flip this expectation when the translator supports both benchmark OR shapes. */
  static boolean expectTranslatedOr() {
    return false;
  }

  static DetachedChecksBenchmarkWitness gremlin(Traversal.Admin<?, ?> traversal,
      Map<Integer, Long> rows) throws Exception {
    var boundaries = traversal.getSteps().stream().filter(YTDBMatchPlanStep.class::isInstance)
        .map(step -> (YTDBMatchPlanStep<?, ?>) step).toList();
    assertThat(boundaries).hasSizeLessThanOrEqualTo(1);
    if (boundaries.isEmpty()) {
      return new DetachedChecksBenchmarkWitness("native", List.of("native"), 0, rows);
    }
    return inspect("translated", boundaries.getFirst().getPlan(), rows);
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
        : or && !expectTranslatedOr() ? "native" : "translated");
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
    } else if (!expectTranslatedOr()) {
      assertThat(paths).containsExactly("native");
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
