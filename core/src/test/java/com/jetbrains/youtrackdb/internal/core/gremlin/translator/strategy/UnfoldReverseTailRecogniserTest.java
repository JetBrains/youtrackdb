package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy.TranslatorEquivalenceSupport.countBoundarySteps;
import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.BoundaryOutputType;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.FoldListShapingOp;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.ReverseListShapingOp;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.TailListShapingOp;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.UnfoldListShapingOp;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.apache.tinkerpop.gremlin.process.traversal.Step;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.step.GValue;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.TailGlobalStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.TailGlobalStepPlaceholder;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.ReverseStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.UnfoldStep;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.junit.Test;

/**
 * Unit tests for the three list-shaping terminators that are not the fold — {@link
 * UnfoldStepRecogniser}, {@link ReverseStepRecogniser}, {@link TailGlobalStepRecogniser} — and the
 * stages they register, at the two levels that hide different defects.
 *
 * <ul>
 *   <li><b>Walker level</b> — each step translates through the production registry and registers one
 *       stage, the composition rule admits a drain behind a per-payload stage and refuses anything
 *       behind a drain, and the shapes that must not translate decline: a negative {@code tail}
 *       window, a terminator inside a combinator child, a head of the wrong class. Every decline is
 *       paired with a translating control on the same registry, because a decline assertion's expected
 *       value is "nothing happened" and a mis-built fixture produces that too.
 *   <li><b>Result level</b> — the translated arm is compared against a hand-computed answer rather
 *       than against the native arm, and every case asserts how many boundary steps the traversal
 *       engaged. A shape that quietly declines runs the native pipeline on both arms, so an on/off
 *       comparison over it cannot fail; the boundary-step count is what makes a silent decline visible
 *       and the hand-computed value is what makes a wrong translation visible.
 * </ul>
 *
 * <p>Two result-level defects are worth naming because they are the plausible ones. Reading {@code
 * reverse()} as a stream reverse rather than a per-payload value reverse keeps the row count and the
 * payload types intact while returning a different multiset — {@code [Alice, Bob, Carol]} where the
 * answer is {@code [ecilA, boB, loraC]}. And a {@code tail(n)} window that keeps the <em>first</em> n
 * rather than the last returns a set of the right size with the wrong members, which no size assertion
 * catches; the ordered fixture is what makes the two disjoint.
 *
 * <p>Composition order is asserted on the registered stage list rather than on results, deliberately.
 * No production shape distinguishes {@code reverse().unfold()} from {@code unfold().reverse()}: a
 * string payload is atomic to {@code unfold} and a map entry is unreversible, so both spellings answer
 * the same on every payload type the boundary emits today. The carrier's ordering is exercised over
 * synthetic stages in the boundary-step tests instead.
 */
public class UnfoldReverseTailRecogniserTest extends GraphBaseTest {

  private final TranslatorEquivalenceSupport support =
      new TranslatorEquivalenceSupport(() -> session);

  // ---------------------------------------------------------------------------
  // Each terminator on its own: one stage registered, output type untouched.
  // ---------------------------------------------------------------------------

  /**
   * {@code g.V().unfold()} translates through the production registry and contributes exactly one
   * flat-map stage, leaving the boundary's output type where the vertex source pinned it. The
   * output-type assertion pins the mechanism rather than the outcome: the stage expands whatever the
   * preceding projection emits, so re-pinning the type would break {@code valueMap().unfold()} and
   * {@code groupCount().unfold()} while leaving this shape looking right.
   */
  @Test
  public void unfoldOnTheLastStep_registersOneFlatMapStage_andLeavesTheOutputTypeAlone() {
    var result = GremlinStepWalker.production().walk(graph.traversal().V().unfold().asAdmin());

    assertThat(result).as("unfold() has a registry entry, so the walk is not declined").isNotNull();
    assertThat(result.shaping().listShapingOps())
        .as("one flat-map stage, contributed by an append rather than by replacing the shaping")
        .hasSize(1)
        .allMatch(op -> op instanceof UnfoldListShapingOp);
    assertThat(result.outputType())
        .as("the expansion leaves the projection contract alone; only the payload stream is reshaped")
        .isEqualTo(BoundaryOutputType.ELEMENT);
  }

  /**
   * {@code g.V().values("name").reverse()} translates and contributes exactly one value-transform
   * stage behind the projection that pinned {@code SINGLE_VALUE}. The output-type assertion is the
   * discriminating half: a {@code reverse} that re-pinned the boundary would change what each row
   * projects to, where the whole contribution is meant to be one stage over the projected values.
   */
  @Test
  public void reverseOnTheLastStep_registersOneValueTransformStage_behindTheProjection() {
    var result =
        GremlinStepWalker.production()
            .walk(graph.traversal().V().values("name").reverse().asAdmin());

    assertThat(result).as("reverse() has a registry entry, so the walk is not declined")
        .isNotNull();
    assertThat(result.shaping().listShapingOps())
        .as("one value-transform stage, appended behind the projection")
        .hasSize(1)
        .allMatch(op -> op instanceof ReverseListShapingOp);
    assertThat(result.outputType())
        .as("the projection still decides what each row yields; the stage only maps it")
        .isEqualTo(BoundaryOutputType.SINGLE_VALUE);
  }

  /**
   * {@code g.V().tail(2)} translates and contributes one window stage carrying the limit the step
   * declared. The limit assertion is what a stage built from the wrong field — the deque capacity, a
   * hard-coded one, the {@code GValue} rather than its value — would fail; a size-only assertion over
   * the stage list would not notice.
   */
  @Test
  public void tailOnTheLastStep_registersOneWindowStage_carryingItsDeclaredLimit() {
    var result = GremlinStepWalker.production().walk(graph.traversal().V().tail(2).asAdmin());

    assertThat(result).as("tail(n) has a registry entry, so the walk is not declined").isNotNull();
    assertThat(result.shaping().listShapingOps())
        .as("one window stage")
        .hasSize(1)
        .allMatch(op -> op instanceof TailListShapingOp);
    assertThat(((TailListShapingOp) result.shaping().listShapingOps().getFirst()).limit())
        .as("and it retains the number of payloads the step asked for")
        .isEqualTo(2L);
  }

  /**
   * Both {@code tail} forms {@code TailGlobalStepContract.CONCRETE_STEPS} enumerates reach the
   * recogniser, which is what registering from that constant buys over two hand-written literals. The
   * class assertions are the fixture premise the registration claim rests on: {@code tail(long)}
   * compiles to the concrete step and {@code tail(GValue)} to the placeholder, so a registry missing
   * either entry declines a shape the other one translates.
   */
  @Test
  public void bothTailForms_reachTheRecogniser_soNeitherRegistryEntryIsMissing() {
    var concrete = graph.traversal().V().tail(2).asAdmin();
    var placeholder = graph.traversal().V().tail(GValue.ofLong("n", 2L)).asAdmin();

    Step<?, ?> concreteTail = concrete.getSteps().getLast();
    Step<?, ?> placeholderTail = placeholder.getSteps().getLast();
    assertThat(concreteTail)
        .as("fixture premise: the long overload compiles to the concrete step")
        .isInstanceOf(TailGlobalStep.class);
    assertThat(placeholderTail)
        .as("fixture premise: the GValue overload is the only path to the placeholder form")
        .isInstanceOf(TailGlobalStepPlaceholder.class);

    assertThat(GremlinStepWalker.production().walk(concrete))
        .as("the concrete form translates")
        .isNotNull();
    assertThat(GremlinStepWalker.production().walk(placeholder))
        .as("and so does the placeholder form, off the same registry")
        .isNotNull();
  }

  // ---------------------------------------------------------------------------
  // The composition rule, driven end to end for the first time: a drain may sit
  // behind a per-payload stage, and nothing may sit behind a drain.
  // ---------------------------------------------------------------------------

  /**
   * A drain behind a per-payload stage translates, and the stages arrive in the order the traversal
   * declared them. This is the shape the walker's two-set composition rule exists for — one allow-list
   * read by both the gate and the drain latch would either refuse this or admit {@code
   * fold().unfold()} — and it is the first traversal that reaches the gate's admit branch, since the
   * per-payload set was empty until these recognisers landed.
   *
   * <p>The result assertion is what makes the acceptance meaningful rather than structural: the folded
   * list holds the reversed names, so a stage order the boundary applied the other way round (folding
   * first, then trying to reverse one list) would fail here.
   */
  @Test
  public void drainBehindAPerPayloadStage_translates_withBothStagesInDeclaredOrder() {
    seedThreePeople();

    var result =
        GremlinStepWalker.production()
            .walk(graph.traversal().V().values("name").reverse().fold().asAdmin());

    assertThat(result).as("a drain is admitted behind a per-payload stage").isNotNull();
    assertThat(result.shaping().listShapingOps())
        .as("and the carrier holds both stages in the order the traversal declared them")
        .hasSize(2);
    assertThat(result.shaping().listShapingOps().get(0)).isInstanceOf(ReverseListShapingOp.class);
    assertThat(result.shaping().listShapingOps().get(1)).isInstanceOf(FoldListShapingOp.class);

    withTranslatorOn(
        () -> {
          var payloads =
              translated(() -> graph.traversal().V().values("name").reverse().fold());
          assertThat(payloads).as("the drain emits exactly one payload").hasSize(1);
          assertThat(payloads.getFirst()).isInstanceOf(List.class);
          assertThat(((List<?>) payloads.getFirst()).stream().map(String::valueOf).toList())
              .as("one folded list, holding each name reversed rather than the names reordered")
              .containsExactlyInAnyOrder("ecilA", "boB", "loraC");
        });
  }

  /**
   * Nothing may claim a step behind a drain, not even another list-shaping stage. {@code
   * fold().unfold()} would expand the list the fold just built, and {@code fold().tail(1)} would
   * window it, where native applies both to the one list the fold made — so the loop's drain latch
   * refuses them and both traversals run natively. The controls walk the same suffixes without the
   * fold, so the refusals are attributable to the drain rather than to a suffix recogniser that is
   * simply not registered.
   */
  @Test
  public void stageBehindADrain_declines_soTheSuffixKeepsNativeSemantics() {
    seedThreePeople();

    assertThat(GremlinStepWalker.production().walk(graph.traversal().V().unfold().asAdmin()))
        .as("control: a bare unfold translates on the production registry")
        .isNotNull();
    assertThat(GremlinStepWalker.production().walk(graph.traversal().V().tail(1).asAdmin()))
        .as("control: a bare tail translates too")
        .isNotNull();

    assertThat(GremlinStepWalker.production().walk(graph.traversal().V().fold().unfold().asAdmin()))
        .as("an expansion behind a drain reshapes an output nobody wrote a stage for")
        .isNull();
    assertThat(GremlinStepWalker.production().walk(graph.traversal().V().fold().tail(1).asAdmin()))
        .as("and so does a window behind a drain")
        .isNull();

    withTranslatorOn(
        () -> {
          var unfolded = graph.traversal().V().fold().unfold().asAdmin();
          unfolded.applyStrategies();
          assertThat(countBoundarySteps(unfolded.getSteps()))
              .as("the declined shape engages no boundary step and runs natively")
              .isZero();
          assertThat(unfolded.toList())
              .as("native expands the one folded list back into its three vertices")
              .hasSize(3);
        });
  }

  /**
   * A per-payload stage may follow another one, and the carrier keeps the declared order both ways
   * round. Asserted on the registered stages rather than on results because no payload type the
   * boundary emits today tells the two spellings apart — a string is atomic to {@code unfold} and a
   * map entry is unreversible — so a result comparison would pass under a carrier that ignored order.
   */
  @Test
  public void perPayloadStagesCompose_andTheCarrierKeepsTheDeclaredOrder() {
    var reverseFirst =
        GremlinStepWalker.production()
            .walk(graph.traversal().V().values("name").reverse().unfold().asAdmin());
    var unfoldFirst =
        GremlinStepWalker.production()
            .walk(graph.traversal().V().values("name").unfold().reverse().asAdmin());

    assertThat(reverseFirst).as("reverse().unfold() translates").isNotNull();
    assertThat(unfoldFirst).as("and so does unfold().reverse()").isNotNull();
    assertThat(reverseFirst.shaping().listShapingOps())
        .as("the carrier holds the stages in the order the traversal declared them")
        .hasSize(2);
    assertThat(reverseFirst.shaping().listShapingOps().get(0))
        .isInstanceOf(ReverseListShapingOp.class);
    assertThat(reverseFirst.shaping().listShapingOps().get(1))
        .isInstanceOf(UnfoldListShapingOp.class);
    assertThat(unfoldFirst.shaping().listShapingOps().get(0))
        .as("and the other spelling registers them the other way round")
        .isInstanceOf(UnfoldListShapingOp.class);
    assertThat(unfoldFirst.shaping().listShapingOps().get(1))
        .isInstanceOf(ReverseListShapingOp.class);
  }

  /**
   * A step whose contribution rides the assembled statement still declines behind a per-payload stage,
   * the same way it declines behind a drain. {@code unfold().dedup()} would emit {@code RETURN
   * DISTINCT} over the rows the expansion was meant to consume — MATCH applies the statement as the
   * plan runs, strictly before the boundary applies the stage — so admitting the two list-shaping
   * kinds must not have widened the gate to everything. The control is the same {@code dedup} with no
   * stage captured.
   */
  @Test
  public void clauseWritingStepBehindAPerPayloadStage_stillDeclines() {
    assertThat(GremlinStepWalker.production().walk(graph.traversal().V().dedup().asAdmin()))
        .as("control: a bare dedup translates on the production registry")
        .isNotNull();

    assertThat(
        GremlinStepWalker.production().walk(graph.traversal().V().unfold().dedup().asAdmin()))
        .as("RETURN DISTINCT rides the statement, which MATCH applies before the stage runs")
        .isNull();
  }

  // ---------------------------------------------------------------------------
  // The tail window's limit: zero, negative, and the GValue-bearing form.
  // ---------------------------------------------------------------------------

  /**
   * A zero window translates and emits nothing, which is native's answer too — {@code TailGlobalStep}
   * trims its deque back to the limit after each add, so a limit of zero trims everything away. Both
   * arms therefore return nothing, which makes the boundary-step count the only thing distinguishing a
   * correct translation from a silent decline; the {@code tail(3)} control on the same fixture is what
   * shows the fixture was alive and returning rows in the first place.
   */
  @Test
  public void tailWithAZeroWindow_translatesAndEmitsNothing() {
    seedThreePeople();

    assertThat(GremlinStepWalker.production().walk(graph.traversal().V().tail(0).asAdmin()))
        .as("a zero window is a translatable shape, not a declined one")
        .isNotNull();

    withTranslatorOn(
        () -> {
          var admin = graph.traversal().V().tail(0).asAdmin();
          admin.applyStrategies();
          assertThat(countBoundarySteps(admin.getSteps()))
              .as("the shape translated, so the empty result below is the translated answer")
              .isEqualTo(1);
          assertThat(admin.toList()).as("a zero window retains nothing").isEmpty();

          var control = graph.traversal().V().tail(3).asAdmin();
          control.applyStrategies();
          assertThat(countBoundarySteps(control.getSteps())).isEqualTo(1);
          assertThat(control.toList())
              .as("control: the same fixture through a non-zero window returns its rows")
              .hasSize(3);
        });
  }

  /**
   * A negative window declines. TinkerPop does not reject one at construction — {@code tail(-1)}
   * builds a step whose deque capacity is negative — so the shape reaches the recogniser and there is
   * no window semantics to reproduce. The assertions are white-box on purpose: whatever native makes
   * of a negative window, both arms run the native pipeline once the walk declines, so a result
   * comparison could not tell a decline from an accidentally-correct translation. The {@code tail(1)}
   * control is the same step class on the same registry.
   */
  @Test
  public void tailWithANegativeWindow_declines() {
    seedThreePeople();

    assertThat(GremlinStepWalker.production().walk(graph.traversal().V().tail(1).asAdmin()))
        .as("control: the same step class with a valid window translates")
        .isNotNull();
    assertThat(GremlinStepWalker.production().walk(graph.traversal().V().tail(-1).asAdmin()))
        .as("a negative window has no meaning to reproduce, so the whole walk declines")
        .isNull();

    withTranslatorOn(
        () -> {
          var admin = graph.traversal().V().tail(-1).asAdmin();
          admin.applyStrategies();
          assertThat(countBoundarySteps(admin.getSteps()))
              .as("the declined shape engages no boundary step and runs natively")
              .isZero();
        });
  }

  /**
   * A {@code tail} whose limit is a GValue variable translates, and the variable is pinned exactly
   * when the value is baked into the stage. Pinning is TinkerPop's own signal that a consumer has
   * consumed the variable's value; the stage holds the window size as plain state and is rebuilt on
   * every strategy application, so a variable left unpinned could be re-bound underneath it.
   *
   * <p>Declining instead would cost the whole traversal's plan rather than the window alone, the walk
   * being all-or-nothing, which is the price the slice recogniser refuses to pay for a parameterised
   * {@code limit(n)} as well.
   */
  @Test
  public void tailWithAVariableWindow_translates_andPinsTheVariableItBakedIn() {
    var admin = graph.traversal().V().tail(GValue.ofLong("n", 2L)).asAdmin();
    assertThat(admin.getGValueManager().getPinnedVariableNames())
        .as("fixture premise: the variable starts unpinned, so the pin below is this walk's doing")
        .isEmpty();

    var result = GremlinStepWalker.production().walk(admin);

    assertThat(result).as("a parameterised window translates rather than costing the plan")
        .isNotNull();
    assertThat(((TailListShapingOp) result.shaping().listShapingOps().getFirst()).limit())
        .as("and the stage carries the variable's concrete value, not a default")
        .isEqualTo(2L);
    assertThat(admin.getGValueManager().getPinnedVariableNames())
        .as("the value is baked into the stage, so the variable is pinned rather than left re-bindable")
        .contains("n");
  }

  /**
   * A declined {@code tail} leaves the GValue manager exactly as it found it. Every decline branch
   * reads the limit through {@code getLimitAsGValue()}, a pure field read on the placeholder form,
   * where {@code getLimit()} would pin the variable on the way out — so a negative parameterised
   * window declines without stripping the variable of its variable status for the native pipeline that
   * then runs. The accept case above is the control: the same fixture shape with a valid window does
   * pin, so this assertion is about the decline path rather than about pinning being broken.
   */
  @Test
  public void declinedTailWithAVariableWindow_leavesTheVariableUnpinned() {
    var admin = graph.traversal().V().tail(GValue.ofLong("n", -1L)).asAdmin();

    assertThat(GremlinStepWalker.production().walk(admin))
        .as("a negative window declines whether or not it arrived as a variable")
        .isNull();
    // A GValue may hold null, and neither GValue.ofLong nor the placeholder's constructor rejects it,
    // so a null window reaches the recogniser and declines on the same pure read.
    var nullWindow = graph.traversal().V().tail(GValue.ofLong("n", null)).asAdmin();
    assertThat(GremlinStepWalker.production().walk(nullWindow))
        .as("a null window has no size to retain, so it declines too")
        .isNull();
    assertThat(nullWindow.getGValueManager().getPinnedVariableNames())
        .as("and that decline pinned nothing either")
        .isEmpty();
    assertThat(admin.getGValueManager().getPinnedVariableNames())
        .as("and the decline pinned nothing, so the native pipeline still sees a free variable")
        .isEmpty();
    assertThat(admin.getGValueManager().getUnpinnedVariableNames())
        .as("fixture premise: the traversal really carried a variable to leave alone")
        .contains("n");
  }

  // ---------------------------------------------------------------------------
  // Result-level semantics: the two mappings whose plausible misreadings are
  // invisible to a row-count assertion.
  // ---------------------------------------------------------------------------

  /**
   * {@code reverse()} reverses each payload's own value and leaves the stream alone. Reading it as a
   * stream reverse is the plausible misreading and it keeps the row count and the payload types
   * intact, so the discriminating assertion is on the contents: the answer is the three names spelled
   * backwards, where a stream reverse would return the three names unchanged. Compared as a multiset
   * because nothing pins the projected rows' arrival order.
   */
  @Test
  public void reverse_reversesEachValue_ratherThanTheStream() {
    seedThreePeople();

    withTranslatorOn(
        () -> assertThat(translated(() -> graph.traversal().V().values("name").reverse()))
            .as("each name spelled backwards, in whatever order the rows arrived")
            .containsExactlyInAnyOrder("ecilA", "boB", "loraC"));
  }

  /**
   * {@code unfold()} over a {@code MAP} payload expands it into its <em>entries</em>, which is the arm
   * carrying the ordinary suite idioms: {@code groupCount()} emits one accumulated map and {@code
   * valueMap()} emits one map per row, and both are expanded by the same stage. Expanding into keys or
   * into values instead would return three payloads here too, so the assertions read the entries'
   * keys and values rather than counting them.
   */
  @Test
  public void unfold_overAMapPayload_expandsItIntoEntries() {
    seedThreePeople();

    withTranslatorOn(
        () -> {
          // groupCount() emits ONE map payload, so this case also pins the 1→N direction: one payload
          // in, three out.
          var counted = translated(() -> graph.traversal().V().groupCount().by("name").unfold());
          assertThat(counted)
              .as("one accumulated map expands into one entry per group")
              .hasSize(3)
              .allMatch(payload -> payload instanceof Map.Entry<?, ?>);
          assertThat(entryKeys(counted))
              .as("the entries' keys are the group keys")
              .containsExactlyInAnyOrder("Alice", "Bob", "Carol");
          assertThat(entryValues(counted))
              .as("and their values are the per-group counts, so neither key nor value was dropped")
              .containsExactly("1", "1", "1");

          // valueMap() emits one map PER ROW, so the same stage runs three times over one entry each.
          var valueMapped = translated(() -> graph.traversal().V().valueMap("name").unfold());
          assertThat(entryKeys(valueMapped))
              .as("one entry per row, keyed on the projected property")
              .containsExactly("name", "name", "name");
        });
  }

  /**
   * {@code unfold()} over an atomic payload passes it through rather than dropping it. The vertices
   * {@code g.V()} projects are not iterators, iterables, maps or arrays, so they land on the arm that
   * emits one payload — and a stage that expanded only collection-shaped payloads would return nothing
   * here, turning an identity into an empty result.
   */
  @Test
  public void unfold_overAnAtomicPayload_passesItThrough() {
    var seededIds = seedThreePeople();

    withTranslatorOn(
        () -> assertThat(
            translated(() -> graph.traversal().V().unfold()).stream()
                .map(payload -> ((Vertex) payload).id().toString())
                .sorted()
                .toList())
            .as("every vertex survives the expansion unchanged")
            .isEqualTo(seededIds));
  }

  /**
   * {@code tail(n)} keeps the <em>last</em> n payloads in arrival order. Keeping the first n is the
   * plausible misreading and it returns a window of exactly the right size, so the fixture pins
   * arrival order with an {@code order().by("name")} ahead of the window — the names are distinct, so
   * MATCH's sort is total here and the two readings become disjoint answers rather than two orderings
   * of one answer. The unsliced control shows the sort itself agrees with the oracle before the window
   * selects out of it.
   */
  @Test
  public void tail_keepsTheLastPayloadsOfAnOrderedStream() {
    seedThreePeople();

    withTranslatorOn(
        () -> {
          assertThat(translated(() -> graph.traversal().V().order().by("name").values("name")))
              .as("control: the sorted projection the window selects out of")
              .containsExactly("Alice", "Bob", "Carol");
          assertThat(
              translated(() -> graph.traversal().V().order().by("name").values("name").tail(2)))
              .as("the last two of the sorted rows, not the first two")
              .containsExactly("Bob", "Carol");
        });
  }

  /**
   * A window wider than the stream returns the whole stream rather than padding or throwing. The ring
   * never fills in that case, so this is the branch where the eviction arithmetic is not exercised —
   * and an implementation that emitted from a fixed-size ring regardless would return nulls or an
   * empty result here.
   */
  @Test
  public void tail_withAWindowWiderThanTheStream_returnsEveryPayload() {
    seedThreePeople();

    withTranslatorOn(
        () -> assertThat(
            translated(
                () -> graph.traversal().V().order().by("name").values("name").tail(10)))
            .as("three rows through a window of ten is the three rows, in order")
            .containsExactly("Alice", "Bob", "Carol"));
  }

  // ---------------------------------------------------------------------------
  // The declines each recogniser owns.
  // ---------------------------------------------------------------------------

  /**
   * Each of the three terminators declines inside a combinator child while the same step on the walk
   * that wraps that child is accepted. The pair has to be white-box: a child's payloads never reach a
   * boundary, so the correct answer (decline the walk) and both wrong ones (swallow the append, or
   * throw out of it into the strategy's exception net) all end with the traversal on the native
   * pipeline, and a result comparison over {@code g.V().not(__.out().unfold())} therefore passes under
   * the bug it would be cited for. The parent is a real {@link WalkerContext} rather than a mock,
   * because Mockito answers {@code false} to any unstubbed boolean and a mocked parent would make both
   * arms of each pair agree for the wrong reason.
   */
  @Test
  public void terminatorInACombinatorChild_declines_whileTheParentWalkAccepts() {
    var parent = new WalkerContext(true, false);
    var child = new SubTraversalPredicateAdapter(parent, Map.of());

    for (var each : List.of(
        Map.entry("unfold", UnfoldStepRecogniser.INSTANCE),
        Map.entry("reverse", ReverseStepRecogniser.INSTANCE),
        Map.entry("tail", TailGlobalStepRecogniser.INSTANCE))) {
      var name = each.getKey();
      StepRecogniser recogniser = each.getValue();

      assertThat(recogniser.recognize(cursorOverTerminator(name), child))
          .as(name + " cannot carry the stage in a sub-walk, so the recogniser declines")
          .isEqualTo(Outcome.DECLINE);
      assertThat(recogniser.recognize(cursorOverTerminator(name), parent))
          .as("control: " + name + " on a context whose own boundary reads the shaping is accepted")
          .isEqualTo(Outcome.ACCEPTED);
    }

    assertThat(parent.listShapingOps())
        .as("and all three accepted contributions really landed, so the declines discriminate")
        .hasSize(3);
  }

  /**
   * A head that is not the step class the recogniser owns declines rather than contributing. Dispatch
   * keys on the exact runtime class, so this can only be reached by a registry entry pointing the
   * wrong step class at a recogniser — and the fail-safe is what turns that wiring mistake into a
   * decline instead of a stage registered over an unrelated step. The context is checked afterwards to
   * show nothing was contributed on the way out.
   */
  @Test
  public void headOfTheWrongClass_declinesWithoutContributing() {
    var ctx = new WalkerContext(true, false);
    var count = graph.traversal().V().count().asAdmin().getSteps().getLast();

    for (StepRecogniser recogniser : List.of(
        UnfoldStepRecogniser.INSTANCE,
        ReverseStepRecogniser.INSTANCE,
        TailGlobalStepRecogniser.INSTANCE)) {
      assertThat(recogniser.recognize(cursorOver(count), ctx))
          .as(recogniser.getClass().getSimpleName() + " fails safe on a head of the wrong class")
          .isEqualTo(Outcome.DECLINE);
    }

    assertThat(ctx.listShapingOps()).as("and nothing was appended before the declines").isEmpty();
  }

  /**
   * Two stages built for two {@code tail(1)} steps compare unequal even though their limits match.
   * That inequality is what declines {@code union(__.out().tail(1), __.in().tail(1))} today: the union
   * recogniser requires every arm to agree on its result shaping and compares the records
   * element-wise, so a record carrying the limit would make the arms agree and ship one window over
   * the concatenation where native takes one per arm. The assertion is on the stages rather than on
   * the union shape because that shape's decline is over-determined — the post-union suffix gate
   * refuses a window too — so a result comparison could not attribute the decline to identity.
   */
  @Test
  public void twoTailStepsWithTheSameLimit_registerStagesThatCompareUnequal() {
    var first = GremlinStepWalker.production().walk(graph.traversal().V().tail(1).asAdmin());
    var second = GremlinStepWalker.production().walk(graph.traversal().V().tail(1).asAdmin());

    assertThat(first.shaping().listShapingOps().getFirst())
        .as("a fresh stage per recognition, so two arms carrying an identical tail never agree")
        .isNotEqualTo(second.shaping().listShapingOps().getFirst());
    assertThat(first.shaping())
        .as("so the whole shaping records compare unequal, which is what the union recogniser reads")
        .isNotEqualTo(second.shaping());
  }

  // ---------------------------------------------------------------------------
  // Helpers.
  // ---------------------------------------------------------------------------

  /**
   * Seeds three {@code Person} vertices whose names are distinct, sort into a known order, and reverse
   * into three distinct strings. Returns their ids as a sorted list of strings — the oracle the
   * element-expansion case compares against, captured at seed time so it does not depend on a second
   * traversal.
   */
  private List<String> seedThreePeople() {
    var alice = graph.addVertex(T.label, "Person", "name", "Alice", "age", 29);
    var bob = graph.addVertex(T.label, "Person", "name", "Bob", "age", 27);
    var carol = graph.addVertex(T.label, "Person", "name", "Carol", "age", 35);
    graph.tx().commit();
    return List.of(alice, bob, carol).stream().map(v -> v.id().toString()).sorted().toList();
  }

  /**
   * Applies strategies, asserts the shape engaged exactly one boundary step, and returns the payloads
   * it emitted. The boundary-step assertion is the anti-vacuity guard: without it a shape that
   * silently declined would run natively and satisfy every assertion below it, since native is the
   * answer those assertions are written against.
   */
  private List<Object> translated(Supplier<GraphTraversal<?, ?>> traversalSupplier) {
    var admin = traversalSupplier.get().asAdmin();
    admin.applyStrategies();
    assertThat(countBoundarySteps(admin.getSteps()))
        .as("the shape must really translate, or the assertions on its result read the native answer")
        .isEqualTo(1);
    return List.copyOf(admin.toList());
  }

  /**
   * The keys of {@code payloads}, every one of which must be a {@link Map.Entry}, rendered as strings
   * so the assertion compares values rather than wildcard-captured element types.
   */
  private static List<String> entryKeys(List<Object> payloads) {
    return payloads.stream().map(p -> String.valueOf(((Map.Entry<?, ?>) p).getKey())).toList();
  }

  /** The values of {@code payloads}, rendered as strings for the reason {@link #entryKeys} is. */
  private static List<String> entryValues(List<Object> payloads) {
    return payloads.stream().map(p -> String.valueOf(((Map.Entry<?, ?>) p).getValue())).toList();
  }

  /**
   * A cursor whose only step is the last step of {@code g.V().<terminator>()}, built through the
   * fluent API so the recogniser sees the step TinkerPop actually compiles rather than a
   * hand-instantiated one. A fresh cursor per call, because {@code recognize} consumes its head.
   */
  private StepCursor cursorOverTerminator(String terminator) {
    Traversal.Admin<?, ?> admin =
        switch (terminator) {
          case "unfold" -> graph.traversal().V().unfold().asAdmin();
          case "reverse" -> graph.traversal().V().reverse().asAdmin();
          case "tail" -> graph.traversal().V().tail(1).asAdmin();
          default -> throw new IllegalArgumentException("unknown terminator: " + terminator);
        };
    Step<?, ?> step = admin.getSteps().getLast();
    assertThat(step)
        .as("fixture premise: the fluent " + terminator + "() compiles to a registered step class")
        .isInstanceOfAny(UnfoldStep.class, ReverseStep.class, TailGlobalStep.class);
    return cursorOver(step);
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
