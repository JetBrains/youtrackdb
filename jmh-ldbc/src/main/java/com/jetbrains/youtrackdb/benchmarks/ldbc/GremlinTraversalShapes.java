package com.jetbrains.youtrackdb.benchmarks.ldbc;

import com.jetbrains.youtrackdb.api.gremlin.YTDBGraphTraversal;
import com.jetbrains.youtrackdb.api.gremlin.YTDBGraphTraversalSource;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.AbstractMatchPlanStep;
import java.util.List;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.structure.Vertex;

/**
 * The four Gremlin traversal shapes the on/off translator benchmark measures, plus the two
 * engagement checks that decide whether a measurement is an arm of that A/B or a mislabelled
 * repeat of the same path.
 *
 * <p>The shapes are named methods here rather than inline expressions in the {@code @Benchmark}
 * bodies so the JMH harness and the in-track JUnit test measure and assert over byte-identical
 * traversals. A benchmark whose shape drifted from the shape the test verified would report an
 * A/B over something nobody checked engages the translator.
 *
 * <p><b>These numbers are not comparable to the IC / IS figures.</b> The twenty LDBC queries are
 * SQL MATCH text with {@code LET} and correlated subqueries that no recognised Gremlin shape
 * reproduces, so this class measures its own shapes and its only meaningful comparison is
 * translator-on against translator-off over the same shape.
 *
 * <p>The load-bearing shape is {@link #personByRid}. A RID-bearing walk sets
 * {@code cacheEligible=false} in the translator, so translator-on compiles an uncached MATCH plan
 * where translator-off ran no query at all — the one shape where the translator can be strictly
 * slower than native.
 */
public final class GremlinTraversalShapes {

  /** Vertex class the LDBC schema gives the {@code id} and {@code firstName} properties. */
  public static final String PERSON_LABEL = "Person";

  /** Edge label the LDBC schema uses for the friendship graph. */
  public static final String KNOWS_LABEL = "KNOWS";

  private GremlinTraversalShapes() {
  }

  /**
   * Shape 1 — {@code g.V(rid)}: a by-id lookup with nothing after it.
   *
   * <p>Held apart from the other three because it is the only shape where the native path issues
   * no query: TinkerPop resolves the id straight to a record, while the translator compiles a
   * MATCH plan for it. The RID has to be resolved from an LDBC {@code id} long before the call,
   * which is why the benchmark state builds a RID pool at trial setup.
   */
  public static YTDBGraphTraversal<Vertex, Vertex> personByRid(
      YTDBGraphTraversalSource g, Object rid) {
    return g.V(rid);
  }

  /**
   * Shape 2 — the {@code KNOWS} walk under {@code values}: one property value per friend.
   *
   * <p>This is the witness shape for the kill-switch installation check, because every step in it
   * ({@code V}, {@code hasLabel}, {@code has}, {@code out}, {@code values}) has been in the
   * recognised set since well before the terminators, so a missing boundary step on the on-arm
   * means the flag flip did not reach the traversal rather than that the shape declined.
   */
  public static YTDBGraphTraversal<Vertex, String> knowsFirstNames(
      YTDBGraphTraversalSource g, long personId) {
    return g.V()
        .hasLabel(PERSON_LABEL)
        .has("id", personId)
        .out(KNOWS_LABEL)
        .values("firstName");
  }

  /** Shape 3 — the same walk under {@code count()}: one scalar per traversal. */
  public static YTDBGraphTraversal<Vertex, Long> knowsFirstNameCount(
      YTDBGraphTraversalSource g, long personId) {
    return knowsFirstNames(g, personId).count();
  }

  /**
   * Shape 4 — the same walk under {@code fold()}: one list per traversal.
   *
   * <p>The reason the harness lands in Track 11 rather than earlier. {@code fold()} is registered
   * by this track's own {@code FoldStep} recogniser, so until that lands the shape declines and
   * both arms measure the native path — the two numbers coincide, which is itself the signal that
   * the recogniser is not in yet.
   */
  public static YTDBGraphTraversal<Vertex, List<String>> knowsFirstNamesFolded(
      YTDBGraphTraversalSource g, long personId) {
    return knowsFirstNames(g, personId).fold();
  }

  /**
   * Counts boundary steps in a strategy-applied traversal.
   *
   * <p>Keys on {@link AbstractMatchPlanStep} rather than a concrete boundary class so every
   * boundary form counts — the single-plan step and the multi-plan step share the base. {@code
   * core}'s test-side {@code countBoundarySteps} helper is unreachable from here because this
   * module declares no {@code core} test-jar dependency, so the check is restated rather than
   * imported.
   *
   * @param strategyApplied a traversal on which {@code applyStrategies()} has already run;
   *     counting before that always returns zero and would make every caller vacuous
   */
  public static int countBoundarySteps(Traversal.Admin<?, ?> strategyApplied) {
    var count = 0;
    for (var step : strategyApplied.getSteps()) {
      if (step instanceof AbstractMatchPlanStep<?, ?>) {
        count++;
      }
    }
    return count;
  }

  /**
   * Throws unless the traversal translated to exactly one boundary step.
   *
   * <p>Throws rather than asserting. The JMH launcher in {@code jmh-ldbc/pom.xml} runs {@code java}
   * with no {@code -ea} and no {@code @Fork(jvmArgsAppend)} adds one, while surefire's
   * {@code argLine} does carry it — so a Java {@code assert} here would hold in-track and become a
   * no-op under measurement, which is the one place the check has to hold.
   *
   * @param shape human-readable shape name, so a failure names which of the four broke
   * @param strategyApplied a traversal on which {@code applyStrategies()} has already run
   */
  public static void requireTranslated(String shape, Traversal.Admin<?, ?> strategyApplied) {
    var boundaries = countBoundarySteps(strategyApplied);
    if (boundaries != 1) {
      throw new IllegalStateException(
          "translator-on arm: shape '" + shape + "' must carry exactly one AbstractMatchPlanStep"
              + " after applyStrategies(), found " + boundaries
              + ". Either the kill-switch flip did not reach this traversal or the shape declined."
              + " Step list: " + strategyApplied.getSteps());
    }
  }

  /**
   * Throws unless the traversal carries no boundary step over a non-empty native pipeline.
   *
   * <p>The empty-step-list guard is not defensive padding. "No boundary step" is also what a
   * traversal that never built, a closed session, or a degenerate fixture produces, so the absence
   * check alone would pass for the wrong reason. Requiring a non-empty step list plus a successful
   * {@link #requireTranslated} on the same shape leaves absence as the only reading.
   *
   * @param shape human-readable shape name, so a failure names which of the four broke
   * @param strategyApplied a traversal on which {@code applyStrategies()} has already run
   */
  public static void requireNotTranslated(String shape, Traversal.Admin<?, ?> strategyApplied) {
    var steps = strategyApplied.getSteps();
    if (steps.isEmpty()) {
      throw new IllegalStateException(
          "translator-off arm: shape '" + shape + "' produced an empty step list, so the absence"
              + " of a boundary step says nothing about the kill-switch. The traversal never"
              + " built.");
    }
    var boundaries = countBoundarySteps(strategyApplied);
    if (boundaries != 0) {
      throw new IllegalStateException(
          "translator-off arm: shape '" + shape + "' must carry no AbstractMatchPlanStep after"
              + " applyStrategies(), found " + boundaries
              + ". The kill-switch flip did not reach this traversal — a session-local override"
              + " shadowing the global flag is the usual cause. Step list: " + steps);
    }
  }
}
