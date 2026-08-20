package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBTransaction;
import com.jetbrains.youtrackdb.internal.core.sql.executor.InternalExecutionPlan;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.process.traversal.util.TraversalHelper;
import org.junit.Test;

/**
 * End-to-end wiring of {@link GremlinTranslationMetrics} through {@link GremlinToMatchStrategy}:
 * success, walker decline, edge start, repeat veto, throw-safety error, kill-switch skip, and
 * idempotent re-apply.
 */
public class GremlinTranslationTelemetryTest extends GraphBaseTest {

  private GremlinTranslationMetrics metrics() {
    return GremlinTranslationMetrics.of(graphSession());
  }

  private com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded graphSession() {
    var tx = (YTDBTransaction) graph.tx();
    tx.readWrite();
    return tx.getDatabaseSession();
  }

  /** A bare {@code g.V()} that translates increments the success counter once. */
  @Test
  public void apply_translatedShape_recordsSuccess() {
    var before = metrics().getSuccesses();
    var admin = graph.traversal().V().asAdmin();

    GremlinToMatchStrategy.instance().apply(admin);

    assertThat(metrics().getSuccesses()).isEqualTo(before + 1);
  }

  /**
   * An unrecognized trailing step declines whole and records a decline with the step-class shape,
   * without counting as a success or error.
   */
  @Test
  public void apply_unsupportedShape_recordsDecline() {
    var beforeDeclines = metrics().getDeclines();
    var beforeSuccesses = metrics().getSuccesses();
    // Lambda map is permanently out of scope — forces a walker decline.
    var admin = graph.traversal().V().map(t -> t.get()).asAdmin();

    GremlinToMatchStrategy.instance().apply(admin);

    assertThat(metrics().getDeclines()).isEqualTo(beforeDeclines + 1);
    assertThat(metrics().getSuccesses()).isEqualTo(beforeSuccesses);
    assertThat(metrics().topDeclinedShapes(5))
        .anyMatch(s -> s.shape().contains("GraphStep") && s.count() >= 1);
  }

  /**
   * A translator that throws is caught by the throw-safety net and recorded as an error, not a
   * plain decline.
   */
  @Test
  public void apply_throwingTranslator_recordsError() {
    var beforeErrors = metrics().getErrors();
    var beforeDeclines = metrics().getDeclines();
    var admin = graph.traversal().V().asAdmin();
    var strategy =
        new GremlinToMatchStrategy(
            t -> {
              throw new IllegalStateException("simulated translator bug");
            },
            (s, tr, planningStart) -> mock(InternalExecutionPlan.class));

    strategy.apply(admin);

    assertThat(metrics().getErrors()).isEqualTo(beforeErrors + 1);
    assertThat(metrics().getDeclines()).isEqualTo(beforeDeclines);
  }

  /** Kill-switch off declines before a translation attempt, so counters stay unchanged. */
  @Test
  public void apply_killSwitchOff_doesNotCountAsAttempt() {
    var beforeAttempts = metrics().getAttempts();
    graphSession()
        .getConfiguration()
        .setValue(
            com.jetbrains.youtrackdb.api.config.GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED,
            false);
    try {
      var admin = graph.traversal().V().asAdmin();
      GremlinToMatchStrategy.instance().apply(admin);
      assertThat(metrics().getAttempts()).isEqualTo(beforeAttempts);
    } finally {
      graphSession()
          .getConfiguration()
          .setValue(
              com.jetbrains.youtrackdb.api.config.GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED,
              true);
    }
  }

  /**
   * Edge starts ({@code g.E()}) are an unsupported product surface (vertex-only milestone) and
   * count as declines, not silent gate exits.
   */
  @Test
  public void apply_edgeStart_recordsDecline() {
    var beforeDeclines = metrics().getDeclines();
    var beforeSuccesses = metrics().getSuccesses();
    var admin = graph.traversal().E().asAdmin();

    GremlinToMatchStrategy.instance().apply(admin);

    assertThat(metrics().getDeclines()).isEqualTo(beforeDeclines + 1);
    assertThat(metrics().getSuccesses()).isEqualTo(beforeSuccesses);
    assertThat(metrics().topDeclinedShapes(5))
        .anyMatch(s -> s.shape().contains("GraphStep") && s.count() >= 1);
  }

  /**
   * Re-applying the strategy to an already-translated traversal is idempotency, not a new attempt —
   * counters must not move.
   */
  @Test
  public void apply_alreadyTranslated_reapply_doesNotCountAsAttempt() {
    var admin = graph.traversal().V().asAdmin();
    GremlinToMatchStrategy.instance().apply(admin);
    var afterFirst = metrics().getAttempts();

    GremlinToMatchStrategy.instance().apply(admin);

    assertThat(metrics().getAttempts()).isEqualTo(afterFirst);
  }

  /**
   * A {@code repeat(...)}-bearing traversal marked by {@link RepeatDeclineStrategy} counts as a
   * decline (out of scope), not a silent gate exit.
   */
  @Test
  public void apply_repeatVeto_recordsDecline() {
    var beforeDeclines = metrics().getDeclines();
    var admin = graph.traversal().V().repeat(__.out()).times(2).asAdmin();
    TraversalHelper.applyTraversalRecursively(RepeatDeclineStrategy.instance()::apply, admin);
    assertThat(RepeatDeclineStrategy.isVetoed(admin)).isTrue();

    GremlinToMatchStrategy.instance().apply(admin);

    assertThat(metrics().getDeclines()).isEqualTo(beforeDeclines + 1);
  }
}
