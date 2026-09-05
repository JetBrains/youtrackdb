package com.jetbrains.youtrackdb.internal.core.gremlin.translator.step;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import java.util.List;
import org.apache.tinkerpop.gremlin.process.traversal.Step;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.structure.T;
import org.junit.Test;

/**
 * Tests the per-row entity-column memoization of {@link AbstractMatchPlanStep}, recorded as PF8.
 *
 * <p>A {@code select(...).by(key)} projection carries one {@link AliasPropertyPresence} per
 * emitted column. The row-level presence check walked that list and resolved an entity per entry,
 * and the map projection then resolved the same entity again per emitted column, so a six-column
 * select over two aliases performed twelve resolutions per row where two suffice.
 *
 * <p>The memoization has NO OTHER OBSERVABLE EFFECT: the rows, their order and their values are
 * unchanged. The resolution counter on the step is therefore the only thing a test can read, and
 * these tests read it. The row assertions beside it are the behaviour half.
 */
public class PresenceEntityResolutionCountTest extends GraphBaseTest {

  /** Two persons who each know one other person, so every row binds two distinct aliases. */
  private void seedTwoPairs() {
    var ann = graph.addVertex(T.label, "Person", "name", "Ann", "city", "Rome", "age", 30);
    var bob = graph.addVertex(T.label, "Person", "name", "Bob", "city", "Oslo", "age", 40);
    var cid = graph.addVertex(T.label, "Person", "name", "Cid", "city", "Kiev", "age", 50);
    ann.addEdge("knows", bob);
    bob.addEdge("knows", cid);
    graph.tx().commit();
  }

  /** Finds the translated boundary step, or fails when the shape declined. */
  private static AbstractMatchPlanStep<?, ?> boundaryStep(Traversal.Admin<?, ?> admin) {
    for (Step<?, ?> step : admin.getSteps()) {
      if (step instanceof AbstractMatchPlanStep<?, ?> boundary) {
        return boundary;
      }
    }
    throw new AssertionError("the shape did not translate, so there is no step to measure");
  }

  /**
   * A three-column {@code select("a","b").by(...)} over two aliases must resolve two entity
   * columns per emitted row, not one per presence entry and again per column. Two rows therefore
   * cost four resolutions. Before the memoization the same shape cost twelve.
   */
  @Test
  public void multiColumnSelectResolvesEachAliasOncePerRow() {
    seedTwoPairs();

    var admin = graph.traversal().V().hasLabel("Person").as("a")
        .out("knows").as("b")
        .select("a", "b").by("name").by("city")
        .asAdmin();
    admin.applyStrategies();
    var step = boundaryStep(admin);

    var rows = admin.toList();
    assertThat(rows).as("two knows edges, so two emitted rows").hasSize(2);
    assertThat(step.entityColumnResolutions())
        .as("two distinct presence aliases times two rows")
        .isEqualTo(4L);
  }

  /**
   * The count must scale with the ROW COUNT and the DISTINCT ALIAS COUNT, never with the column
   * count. This shape emits the same two aliases under three columns, so it must cost exactly
   * what the two-column shape above costs.
   */
  @Test
  public void addingAColumnOnTheSameAliasesAddsNoResolution() {
    seedTwoPairs();

    var twoColumns = graph.traversal().V().hasLabel("Person").as("a")
        .out("knows").as("b")
        .select("a", "b").by("name").by("city")
        .asAdmin();
    twoColumns.applyStrategies();
    var twoColumnStep = boundaryStep(twoColumns);
    twoColumns.toList();

    var threeColumns = graph.traversal().V().hasLabel("Person").as("a")
        .out("knows").as("b").as("c")
        .select("a", "b", "c").by("name").by("city").by("age")
        .asAdmin();
    threeColumns.applyStrategies();
    var threeColumnStep = boundaryStep(threeColumns);
    var rows = threeColumns.toList();

    assertThat(rows).hasSize(2);
    assertThat(threeColumnStep.entityColumnResolutions())
        .as("a third column on an already-resolved alias costs no extra resolution")
        .isEqualTo(twoColumnStep.entityColumnResolutions());
  }

  /**
   * The count must scale with the ROW COUNT and nothing else, which is the case PF8 cares about:
   * a shape with a cardinality clause and no limit emits an unbounded number of rows, so the
   * per-row constant is the whole cost. Ten rows over two aliases must cost twenty resolutions.
   *
   * <p>PF8 names a {@code dedup()}-only select as that shape. A translated {@code dedup()} plus
   * {@code select(...).by(...)} could not be built here in either order, so the unbounded-row
   * case is measured through row count alone rather than through that spelling.
   */
  @Test
  public void resolutionCountScalesWithRowsAndNotWithColumns() {
    var hub = graph.addVertex(T.label, "Person", "name", "Hub", "city", "Rome");
    for (var i = 0; i < 10; i++) {
      var friend = graph.addVertex(T.label, "Person", "name", "F" + i, "city", "C" + i);
      hub.addEdge("knows", friend);
    }
    graph.tx().commit();

    var admin = graph.traversal().V().hasLabel("Person").as("a")
        .out("knows").as("b")
        .select("a", "b").by("name").by("city")
        .asAdmin();
    admin.applyStrategies();
    var step = boundaryStep(admin);

    var rows = admin.toList();
    assertThat(rows).as("the hub knows ten people").hasSize(10);
    assertThat(step.entityColumnResolutions())
        .as("two aliases per row, whatever the column count")
        .isEqualTo(20L);
  }

  /**
   * BEHAVIOUR HALF. The memoized read must return the same values as a fresh read, including for
   * a row where one alias carries the key and the other does not. Bob has no {@code nickname},
   * so the presence drop must still remove the row it removed before.
   */
  @Test
  public void memoizedResolutionKeepsTheValuesAndTheDrop() {
    var ann = graph.addVertex(T.label, "Person", "name", "Ann", "nickname", "Annie");
    var bob = graph.addVertex(T.label, "Person", "name", "Bob");
    var cid = graph.addVertex(T.label, "Person", "name", "Cid", "nickname", "Ciddy");
    ann.addEdge("knows", cid);
    ann.addEdge("knows", bob);
    graph.tx().commit();

    var admin = graph.traversal().V().hasLabel("Person").as("a")
        .out("knows").as("b")
        .select("a", "b").by("nickname").by("nickname")
        .asAdmin();
    admin.applyStrategies();
    var rows = admin.toList();

    assertThat(rows)
        .as("only the pair whose both ends carry a nickname survives the presence drop")
        .isEqualTo(List.of(java.util.Map.of("a", "Annie", "b", "Ciddy")));
  }
}
