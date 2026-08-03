package com.jetbrains.youtrackdb.shade;

import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.api.gremlin.YTDBGraphTraversalSource;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.AbstractMatchPlanStep;
import java.util.List;
import org.junit.Test;

/**
 * Witnesses which arm of the Gremlin-to-MATCH kill-switch this module's surefire fork is really
 * running, by reading the plan of a recognised traversal instead of trusting the command line.
 *
 * <p>The module declares {@code <argLine>} inline in the surefire {@code <configuration>}, where
 * plugin configuration beats the {@code argLine} user property. A kill-switch smuggled into a CLI
 * {@code -DargLine=} therefore never reaches the fork, and the run silently measures the
 * translator-on arm while its log claims otherwise. The Cucumber suite in this module has no
 * known-good scenario count that would expose that mistake, so the arm is asserted here: the
 * fork's own view of {@code youtrackdb.query.gremlin.toMatchTranslator.enabled} must agree with
 * whether a bare {@code g.V()} carries a boundary step after {@code applyStrategies()}.
 *
 * <p>Both directions assert something, so neither can pass on a broken fixture. With the switch
 * unset or true — the default build and CI — the traversal must carry exactly one boundary step,
 * which proves the fixture can see a boundary step at all. With the switch set to {@code false}
 * the traversal must carry none, which is the signal that an off-arm measurement was taken with
 * the translator genuinely off.
 */
public class EmbeddedTranslatorKillSwitchWitnessTest {

  private static final String DB_NAME = "killswitchwitness";

  /**
   * Asserts that the boundary-step count of a bare {@code g.V()} matches the kill-switch value the
   * fork received: exactly one boundary step when the switch is unset or true, and zero when it is
   * explicitly {@code false}. A mis-set switch — passed inside {@code -DargLine=}, misspelled, or
   * swallowed before the fork — leaves the property unset while the assertion still demands the
   * on-arm shape, so an off-arm run that never turned the translator off fails here rather than
   * publishing the on-arm number twice. The observed pair is printed so the run log carries the
   * evidence a published measurement cites.
   */
  @Test
  public void boundaryStepPresenceMatchesTheKillSwitchTheForkReceived() {
    var key = GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED.getKey();
    var forkSetting = System.getProperty(key);
    // The kill-switch defaults to true, so anything other than an explicit "false" is the on-arm.
    var expectedBoundarySteps = "false".equalsIgnoreCase(forkSetting) ? 0 : 1;

    try (YouTrackDB db = YourTracks.instance(".")) {
      db.createIfNotExists(DB_NAME, DatabaseType.MEMORY, "admin", "admin", "admin");

      try (YTDBGraphTraversalSource g = db.openTraversal(DB_NAME, "admin", "admin")) {
        g.executeInTx(tx -> tx.addV("Person").property("name", "Alice").next());

        int boundarySteps = g.computeInTx(tx -> {
          var admin = tx.V().asAdmin();
          admin.applyStrategies();
          return countBoundarySteps(admin.getSteps());
        });

        System.out.println("[kill-switch witness] " + key + "=" + forkSetting
            + " boundarySteps=" + boundarySteps);

        assertEquals(
            "the kill-switch the fork received (" + key + "=" + forkSetting
                + ") disagrees with the plan of a bare g.V()",
            expectedBoundarySteps, boundarySteps);
      }

      db.drop(DB_NAME);
    }
  }

  /**
   * Counts boundary steps across the whole step list rather than inspecting only the start step,
   * because the design invariant is "exactly one boundary step after {@code applyStrategies()}".
   */
  private static int countBoundarySteps(List<?> steps) {
    var count = 0;
    for (var step : steps) {
      if (step instanceof AbstractMatchPlanStep<?, ?>) {
        count++;
      }
    }
    return count;
  }
}
