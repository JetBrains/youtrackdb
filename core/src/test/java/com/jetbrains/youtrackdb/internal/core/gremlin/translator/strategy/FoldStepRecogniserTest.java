package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy.TranslatorEquivalenceSupport.countBoundarySteps;
import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.BoundaryOutputType;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.FoldListShapingOp;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.apache.tinkerpop.gremlin.process.traversal.Operator;
import org.apache.tinkerpop.gremlin.process.traversal.Step;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.FoldStep;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.junit.Test;

/**
 * Unit tests for {@link FoldStepRecogniser} and the {@link FoldListShapingOp} drain it registers, at
 * the two levels that each hide a different defect.
 *
 * <ul>
 *   <li><b>Walker level</b> — {@code g.V().fold()} translates through the production registry, and the
 *       shapes that must not translate decline: the seeded reduce {@code fold(seed, operator)}, a fold
 *       inside a combinator child, a step behind the captured stage, and a fold behind a captured
 *       slice. Each decline is paired with a translating control on the same registry, because a
 *       decline assertion's expected value is "nothing happened" and a mis-built fixture produces that
 *       too.
 *   <li><b>Result level</b> — the translated arm is compared against a hand-computed answer rather
 *       than against the native arm, and every case asserts how many boundary steps the traversal
 *       engaged. A shape that quietly declines runs the native pipeline on both arms, so an on/off
 *       comparison over it cannot fail; the boundary-step count is what makes a silent decline visible
 *       and the hand-computed value is what makes a wrong translation visible.
 * </ul>
 *
 * <p>The result-level cases carry the defects worth catching. A translated {@code fold().count()}
 * counts the rows the fold was meant to consume (3 instead of 1), and a translated
 * {@code values("name").fold().limit(2)} folds a sliced row set (a 2-element list instead of a
 * 3-element one). Both are plausible outcomes of a missing position gate and neither is visible to a
 * result-count assertion.
 */
public class FoldStepRecogniserTest extends GraphBaseTest {

  private final TranslatorEquivalenceSupport support =
      new TranslatorEquivalenceSupport(() -> session);

  /**
   * {@code g.V().fold()} translates through the production registry and contributes exactly one drain
   * stage, leaving the boundary's output type where the vertex source pinned it. The output-type
   * assertion pins the mechanism rather than the outcome: the drain builds its list out of whatever the
   * preceding projection emits, so re-pinning the type to a list constant would erase what tells the
   * boundary how to project each element — breaking {@code values().fold()} and
   * {@code valueMap().fold()} while leaving this shape looking right.
   */
  @Test
  public void foldOnTheLastStep_registersOneDrainStage_andLeavesTheOutputTypeAlone() {
    var result = GremlinStepWalker.production().walk(graph.traversal().V().fold().asAdmin());

    assertThat(result)
        .as("fold() has a registry entry, so the walk is not declined for a missing one")
        .isNotNull();
    assertThat(result.shaping().listShapingOps())
        .as("one drain stage, contributed by an append rather than by replacing the shaping")
        .hasSize(1)
        .allMatch(op -> op instanceof FoldListShapingOp);
    assertThat(result.outputType())
        .as("the fold leaves the projection contract alone; only the payload stream is reshaped")
        .isEqualTo(BoundaryOutputType.ELEMENT);
  }

  /**
   * Two ops built for two {@code fold()} steps compare unequal. That inequality is what declines
   * {@code union(__.out().fold(), __.in().fold())} today: the union recogniser requires every arm to
   * agree on its result shaping and compares the records element-wise, so a shared constant or a
   * value-equal record would make the arms agree and ship one list over the concatenation where native
   * produces one list per arm. The assertion is on the ops rather than on the union shape because that
   * shape's decline is over-determined — a result comparison over it could not attribute the decline to
   * op identity.
   */
  @Test
  public void twoFoldSteps_registerOpsThatCompareUnequal() {
    var first = GremlinStepWalker.production().walk(graph.traversal().V().fold().asAdmin());
    var second = GremlinStepWalker.production().walk(graph.traversal().V().fold().asAdmin());

    assertThat(first.shaping().listShapingOps().getFirst())
        .as("a fresh op per recognition, so two arms carrying a fold each never agree")
        .isNotEqualTo(second.shaping().listShapingOps().getFirst());
    assertThat(first.shaping())
        .as("so the whole shaping records compare unequal, which is what the union recogniser's "
            + "agreement check reads")
        .isNotEqualTo(second.shaping());
  }

  /**
   * The drain collects every projected payload into one list, over both an element projection and a
   * value projection — the two output types that make "the output type stays where the preceding step
   * pinned it" observable. Three seeded vertices give one 3-element list on each shape, so a drain that
   * emitted per row (three 1-element lists) or dropped rows fails on size, and a plan that folded the
   * wrong column fails on contents.
   */
  @Test
  public void fold_collectsEveryProjectedPayloadIntoOneList_overElementsAndValues() {
    var seededIds = seedThreePeople();

    withTranslatorOn(
        () -> {
          var elements = translatedSingleList(() -> graph.traversal().V().fold());
          assertThat(elements.stream().map(v -> ((Vertex) v).id().toString()).sorted().toList())
              .as("every vertex row lands in the one list the fold emits")
              .isEqualTo(seededIds);

          var names = translatedSingleList(() -> graph.traversal().V().values("name").fold());
          assertThat(names)
              .as("the projected value is folded, not the element it was projected from")
              .containsExactlyInAnyOrder("Alice", "Bob", "Carol");
        });
  }

  /**
   * A projection that drops every row still yields one empty list, which is what native {@code fold()}
   * gives over a dry stream. No vertex carries {@code nickname}, so the {@code dropOnAbsent} half of
   * {@code values(key)} removes all three rows before the drain runs. The failure this pins is a drain
   * that emits nothing on an empty stream: the traversal would return zero results where native returns
   * one, which no assertion over a non-empty fixture would notice.
   */
  @Test
  public void fold_overAProjectionThatDropsEveryRow_stillEmitsOneEmptyList() {
    seedThreePeople();

    withTranslatorOn(
        () -> assertThat(
            translatedSingleList(() -> graph.traversal().V().values("nickname").fold()))
            .as("a dry upstream still produces the one empty list native produces")
            .isEmpty());
  }

  /**
   * The seeded reduce {@code fold(seed, operator)} declines. It rides the same {@link FoldStep} class
   * as the list form and is told apart only by {@link FoldStep#isListFold()}, so a recogniser that
   * skipped that check would claim it and register a drain, turning one summed scalar into a list of the
   * summands. The control is the list form of the same class on the same shape. The result assertion
   * pins the native scalar (0 + 29 + 27 + 35), which a wrongly-claimed reduce would replace with a
   * 3-element list.
   */
  @Test
  public void foldSeededReduce_declines_andStillProducesTheNativeSummedScalar() {
    seedThreePeople();

    assertThat(
        GremlinStepWalker.production()
            .walk(graph.traversal().V().values("age").fold(0, Operator.sum).asAdmin()))
        .as("the seeded reduce has no drain expression, so the whole walk declines")
        .isNull();
    assertThat(
        GremlinStepWalker.production()
            .walk(graph.traversal().V().values("age").fold().asAdmin()))
        .as("control: the list form of the same class on the same shape translates")
        .isNotNull();

    withTranslatorOn(
        () -> {
          var admin = graph.traversal().V().values("age").fold(0, Operator.sum).asAdmin();
          admin.applyStrategies();
          assertThat(countBoundarySteps(admin.getSteps()))
              .as("the declined shape engages no boundary step and runs natively")
              .isZero();
          assertThat(graph.traversal().V().values("age").fold(0, Operator.sum).next())
              .as("native reduces the ages to one scalar rather than collecting them")
              .isEqualTo(91);
        });
  }

  /**
   * A fold inside a combinator child declines, while the same step on the walk that wraps that child is
   * accepted. The pair has to be white-box. A child's payloads never reach a boundary, so the correct
   * answer (decline the walk) and both wrong ones (swallow the append, or throw out of it into the
   * strategy's exception net) all end with the traversal on the native pipeline — a result comparison
   * over {@code g.V().not(__.out().fold())} therefore passes under the bug it would be cited for. The
   * parent is a real {@link WalkerContext} rather than a mock: Mockito answers {@code false} to any
   * unstubbed boolean, so a mocked parent would make both arms of this pair agree for the wrong reason.
   */
  @Test
  public void foldInACombinatorChild_declines_whileTheParentWalkAccepts() {
    var parent = new WalkerContext(true, false);
    var child = new SubTraversalPredicateAdapter(parent, Map.of());

    assertThat(FoldStepRecogniser.INSTANCE.recognize(cursorOverAFoldStep(), child))
        .as("a sub-walk cannot carry the stage, so the recogniser declines the whole walk")
        .isEqualTo(Outcome.DECLINE);
    assertThat(FoldStepRecogniser.INSTANCE.recognize(cursorOverAFoldStep(), parent))
        .as("control: the same step on a context whose own boundary reads the shaping is accepted")
        .isEqualTo(Outcome.ACCEPTED);
    assertThat(parent.listShapingOps())
        .as("and the accepted contribution really landed, so the decline above discriminates")
        .hasSize(1);
  }

  /**
   * A head that is not a {@link FoldStep} declines rather than contributing. Dispatch keys on the exact
   * runtime class, so this can only be reached by a registry entry pointing the wrong step class here —
   * and the fail-safe is what turns that wiring mistake into a decline instead of a drain registered
   * over an unrelated step. The context is checked afterwards to show nothing was contributed on the
   * way out.
   */
  @Test
  public void headThatIsNotAFoldStep_declinesWithoutContributing() {
    var ctx = new WalkerContext(true, false);
    var count = graph.traversal().V().count().asAdmin().getSteps().getLast();

    assertThat(FoldStepRecogniser.INSTANCE.recognize(cursorOver(count), ctx))
        .as("a head of the wrong class is a wiring mistake, and the recogniser fails safe")
        .isEqualTo(Outcome.DECLINE);
    assertThat(ctx.listShapingOps())
        .as("and nothing was appended before the decline")
        .isEmpty();
  }

  /**
   * A step dispatched behind the captured stage declines, and the shapes here are the ones whose wrong
   * answers are ordinary. {@code fold().count()} would compile {@code count(*)} into the statement and
   * count the three rows the fold was meant to drain, where native counts the one list it made;
   * {@code values("name").fold().limit(2)} would compile {@code LIMIT 2} and fold two rows, where native
   * folds all three and keeps the list it made. {@code fold().fold()} is the third and reaches a
   * different arm of the same gate: the second drain is refused by the loop's drain latch rather than by
   * an allow-list, and it is the only production shape that exercises the latch while the per-payload
   * set is still empty. The walker's in-loop gate refuses all three, so the control walks the same
   * suffix without the fold to show that suffix recogniser is otherwise live on this registry.
   */
  @Test
  public void stepBehindTheCapturedStage_declines_soTheSuffixKeepsNativeSemantics() {
    seedThreePeople();

    assertThat(GremlinStepWalker.production().walk(graph.traversal().V().count().asAdmin()))
        .as("control: a bare count translates on the production registry")
        .isNotNull();
    assertThat(GremlinStepWalker.production().walk(graph.traversal().V().fold().count().asAdmin()))
        .as("count(*) rides the statement, which MATCH applies before the drain runs")
        .isNull();
    assertThat(
        GremlinStepWalker.production()
            .walk(graph.traversal().V().values("name").fold().limit(2).asAdmin()))
        .as("LIMIT rides the statement too, so it would slice the rows the drain consumes")
        .isNull();
    assertThat(GremlinStepWalker.production().walk(graph.traversal().V().fold().fold().asAdmin()))
        .as("and a second drain behind the first is refused by the loop's drain latch")
        .isNull();

    withTranslatorOn(
        () -> {
          assertThat(graph.traversal().V().fold().count().next())
              .as("the count sees the one list the fold made, never the three rows behind it")
              .isEqualTo(1L);
          assertThat(
              declinedSingleList(() -> graph.traversal().V().values("name").fold().limit(2)))
              .as("the slice keeps the one list the fold made, whole")
              .containsExactlyInAnyOrder("Alice", "Bob", "Carol");
          var nested = declinedSingleList(() -> graph.traversal().V().fold().fold());
          assertThat(nested)
              .as("the second fold wraps the first one's list rather than re-folding the rows")
              .hasSize(1);
          assertThat(nested.getFirst()).isInstanceOf(List.class);
          assertThat((List<?>) nested.getFirst())
              .as("and the inner list is the one the first fold made, all three rows of it")
              .hasSize(3);
        });
  }

  /**
   * A fold behind a captured {@code LIMIT} declines too, through the walker's cardinality gate rather
   * than through anything this recogniser does. The decline is stricter than correctness requires — the
   * statement applies {@code LIMIT 2} and the drain runs afterwards, which is the order Gremlin gives
   * {@code limit(2).fold()} as well — so the shape costs coverage, not correctness. The control is the
   * same prefix without the fold, so the decline is attributable to the fold sitting behind the clause
   * rather than to the slice itself.
   */
  @Test
  public void foldBehindACapturedSlice_declines_asCoverageLostRatherThanCorrectness() {
    assertThat(GremlinStepWalker.production().walk(graph.traversal().V().limit(2).asAdmin()))
        .as("control: the slice on its own translates")
        .isNotNull();

    assertThat(GremlinStepWalker.production().walk(graph.traversal().V().limit(2).fold().asAdmin()))
        .as("the cardinality gate refuses every step behind a captured clause, this one included")
        .isNull();
  }

  // ---------------------------------------------------------------------------
  // Helpers.
  // ---------------------------------------------------------------------------

  /**
   * Seeds three {@code Person} vertices with distinct names and ages summing to 91, and returns their
   * ids as a sorted list of strings — the oracle the element-fold case compares against, captured at
   * seed time so it does not depend on a second traversal.
   */
  private List<String> seedThreePeople() {
    var alice = graph.addVertex(T.label, "Person", "name", "Alice", "age", 29);
    var bob = graph.addVertex(T.label, "Person", "name", "Bob", "age", 27);
    var carol = graph.addVertex(T.label, "Person", "name", "Carol", "age", 35);
    graph.tx().commit();
    return List.of(alice, bob, carol).stream().map(v -> v.id().toString()).sorted().toList();
  }

  /**
   * Applies strategies, asserts the shape engaged exactly one boundary step, and returns the single
   * emitted payload as a list. The boundary-step assertion is the anti-vacuity guard: without it a shape
   * that silently declined would run natively and satisfy every assertion below it, since native is the
   * answer those assertions are written against.
   */
  private List<Object> translatedSingleList(Supplier<GraphTraversal<?, ?>> traversalSupplier) {
    return singleList(traversalSupplier, 1);
  }

  /**
   * The counterpart for a shape that must <em>not</em> translate: asserts no boundary step was engaged
   * and returns the single emitted list. Used where the expected answer is native's and the claim under
   * test is that the translator stayed out of it.
   */
  private List<Object> declinedSingleList(Supplier<GraphTraversal<?, ?>> traversalSupplier) {
    return singleList(traversalSupplier, 0);
  }

  @SuppressWarnings("unchecked")
  private List<Object> singleList(
      Supplier<GraphTraversal<?, ?>> traversalSupplier, int expectedBoundarySteps) {
    var admin = traversalSupplier.get().asAdmin();
    admin.applyStrategies();
    assertThat(countBoundarySteps(admin.getSteps()))
        .as("the shape's recognition must be the expected one, or the assertions on its result are "
            + "read off the wrong pipeline")
        .isEqualTo(expectedBoundarySteps);
    List<?> results = admin.toList();
    assertThat(results).as("a drain emits exactly one payload").hasSize(1);
    Object payload = results.getFirst();
    assertThat(payload).as("and that payload is the folded list").isInstanceOf(List.class);
    return (List<Object>) payload;
  }

  /**
   * A cursor whose only step is a real list-form {@link FoldStep}, built through the fluent API so the
   * recogniser sees the step TinkerPop actually compiles rather than a hand-instantiated one. A fresh
   * cursor per call, because {@code recognize} consumes its head.
   */
  private StepCursor cursorOverAFoldStep() {
    Step<?, ?> fold = graph.traversal().V().fold().asAdmin().getSteps().getLast();
    assertThat(fold)
        .as("fixture premise: the fluent fold() compiles to the class the recogniser is keyed on")
        .isInstanceOf(FoldStep.class);
    return cursorOver(fold);
  }

  /** A cursor whose only step is {@code step}, with no transparent classes to skip. */
  private static StepCursor cursorOver(Step<?, ?> step) {
    return new StepStreamCursor(List.of(step), Set.of());
  }

  /** Runs {@code body} with the translator on, restoring the previous setting afterwards. */
  private void withTranslatorOn(Runnable body) {
    support.withTranslator(true, body);
  }
}
