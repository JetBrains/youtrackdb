package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.YTDBMatchPlanStep;
import org.apache.tinkerpop.gremlin.process.traversal.Order;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.junit.Test;

/**
 * Covers the edge-schema class inference for a BARE GREMLIN HOP, recorded as finding TQ100.
 *
 * <p>A Gremlin hop written without {@code hasLabel} registers its target alias under the generic
 * {@code V} class, so the index-ordered planner cannot read a concrete class from the alias. It
 * infers one from the edge LINK schema instead: {@code in('REPLY_OF')} resolves through the
 * {@code REPLY_OF.out} link to {@code Comment}, whose {@code creationDate} index can then serve
 * the ORDER BY.
 *
 * <p>THE FINDING WAS THAT NO TEST FAILED ON REVERT. Existing reply-order tests pin the class
 * with {@code hasLabel("Comment")}, which skips the inference entirely, and the one shape that
 * omits the label asserts rows and boundary presence only. Rows cannot see the difference:
 * without the inference the planner falls back to an ordinary MATCH plan and returns the same
 * records in the same order. Only the plan shows it, which is what the tests below read.
 */
public class BareHopClassInferenceTest extends GraphBaseTest {

  /**
   * One message with sixty replies. {@code REPLY_OF} declares both endpoint LINKs, which is what
   * makes the inference possible, and {@code Comment.creationDate} carries the ordered index.
   *
   * <p>Sixty replies rather than a handful: the plan-time check refuses an ordered scan whose row
   * count reaches the whole index, so a small fixture would decline for a reason unrelated to the
   * inference under test.
   */
  private void seedRepliesReachableOnlyByABareHop() {
    session.execute("CREATE CLASS Message EXTENDS V").close();
    session.execute("CREATE PROPERTY Message.id LONG").close();
    session.execute("CREATE CLASS Comment EXTENDS V").close();
    session.execute("CREATE PROPERTY Comment.id LONG").close();
    session.execute("CREATE PROPERTY Comment.creationDate DATETIME").close();
    session.execute(
        "CREATE INDEX Comment.creationDate ON Comment (creationDate) NOTUNIQUE").close();
    session.execute("CREATE CLASS REPLY_OF EXTENDS E").close();
    // The endpoint LINK declarations are the schema the inference reads.
    session.execute("CREATE PROPERTY REPLY_OF.out LINK Comment").close();
    session.execute("CREATE PROPERTY REPLY_OF.in LINK Message").close();

    session.begin();
    session.execute("CREATE VERTEX Message SET id = 100").close();
    for (var i = 0; i < 60; i++) {
      // Distinct dates, so the ordered result is a single sequence and the row comparison
      // below is an invariant rather than a tie-break accident.
      session.execute(
          "CREATE VERTEX Comment SET id = " + (200 + i) + ", creationDate = date('2026-01-"
              + (i < 9 ? "0" : "") + (i % 28 + 1) + " 00:" + (i < 10 ? "0" : "") + i
              + ":00', 'yyyy-MM-dd HH:mm:ss')")
          .close();
    }
    session.execute(
        "CREATE EDGE REPLY_OF FROM (SELECT FROM Comment) TO (SELECT FROM Message WHERE id = 100)")
        .close();
    session.commit();
  }

  /** The MATCH plan behind the translated boundary step, or a failure when the shape declined. */
  private static String translatedPlan(Traversal.Admin<?, ?> admin) {
    admin.applyStrategies();
    for (var step : admin.getSteps()) {
      if (step instanceof YTDBMatchPlanStep<?, ?> boundary) {
        return boundary.getPlan().prettyPrint(0, 2);
      }
    }
    throw new AssertionError("the shape did not translate, so there is no plan to read");
  }

  /**
   * The bare hop {@code in("REPLY_OF")} carries no label, so the ordered plan exists only if the
   * planner infers {@code Comment} from the edge schema. Deleting the generic-class arm of that
   * inference makes this plan fall back to an ordinary MATCH.
   */
  @Test
  public void bareHopOrderedPlanNeedsTheEdgeSchemaInference() {
    seedRepliesReachableOnlyByABareHop();

    var plan = translatedPlan(
        graph.traversal().V().hasLabel("Message").has("id", 100L)
            .in("REPLY_OF")
            .order().by("creationDate", Order.desc)
            .limit(20)
            .asAdmin());

    assertThat(plan)
        .as("the inferred Comment class is what lets the creationDate index serve the order")
        .contains("INDEX ORDERED MATCH");
    assertThat(plan)
        .as("and the index it picks must be the one on the inferred class")
        .contains("Comment.creationDate");
  }

  /**
   * CONTROL. The same shape with the label written explicitly takes the ordinary alias-class path
   * and never reaches the inference. Without this pair a reader cannot tell whether the test above
   * exercises the inference or merely the ordered planner.
   */
  @Test
  public void labelledHopReachesTheSamePlanWithoutTheInference() {
    seedRepliesReachableOnlyByABareHop();

    var plan = translatedPlan(
        graph.traversal().V().hasLabel("Message").has("id", 100L)
            .in("REPLY_OF").hasLabel("Comment")
            .order().by("creationDate", Order.desc)
            .limit(20)
            .asAdmin());

    assertThat(plan)
        .as("an explicit label supplies the class directly")
        .contains("INDEX ORDERED MATCH");
  }

  /**
   * The rows are the same either way, which is why the finding could go unnoticed: the fallback
   * plan returns the same twenty replies in the same order. The assertion records that, so a
   * future reader does not mistake the plan assertions above for row coverage.
   */
  @Test
  public void bareHopAndLabelledHopReturnTheSameRows() {
    seedRepliesReachableOnlyByABareHop();

    var bare = graph.traversal().V().hasLabel("Message").has("id", 100L)
        .in("REPLY_OF").order().by("creationDate", Order.desc).limit(20)
        .values("id").toList();
    var labelled = graph.traversal().V().hasLabel("Message").has("id", 100L)
        .in("REPLY_OF").hasLabel("Comment").order().by("creationDate", Order.desc).limit(20)
        .values("id").toList();

    assertThat(bare).as("twenty of the sixty replies").hasSize(20);
    assertThat(bare).isEqualTo(labelled);
  }
}
