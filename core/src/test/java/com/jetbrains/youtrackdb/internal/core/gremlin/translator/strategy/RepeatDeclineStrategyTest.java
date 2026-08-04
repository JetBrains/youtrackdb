package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.AbstractMatchPlanStep;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.TraversalStrategies;
import org.apache.tinkerpop.gremlin.process.traversal.TraversalStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.DefaultGraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.process.traversal.step.branch.RepeatStep;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.optimization.AdjacentToIncidentStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.optimization.RepeatUnrollStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.traverser.TraverserRequirement;
import org.apache.tinkerpop.gremlin.process.traversal.util.TraversalHelper;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.junit.Test;

/**
 * Pins the decline of {@code repeat(...)}-bearing traversals. Variable-depth repetition is out of
 * scope for the translator, so every such traversal must reach the native pipeline untouched.
 *
 * <p>The defect these tests exist for: {@code RepeatUnrollStrategy} rewrites {@code
 * repeat(__.out()).times(n)} into n chained {@code VertexStep}s separated by {@code
 * NoOpBarrierStep}s, and the walker treats {@code NoOpBarrierStep} as transparent, so what reaches
 * the walker is indistinguishable from a hand-written n-hop chain. The translator folded it into a
 * single MATCH pattern and the planner then materialized every path — on the TinkerPop grateful-dead
 * fixture {@code times(8)} has 2,505,037,961,767,380 of them, so the query never returned. Native
 * Gremlin answers it in milliseconds because the barriers merge identical traversers into bulks.
 *
 * <p>Six cases go through {@link #assertDeclinedAndEquals}, which asserts four things per case:
 *
 * <ul>
 *   <li><b>A control on the exact shape</b>. The same traversal is compiled once with {@link
 *       RepeatDeclineStrategy} removed from its strategy list. A case whose control translates
 *       proves the decline came from the veto; a case whose control also declines is a guard on the
 *       recursive scan rather than a witness for the fix, and says so in its own javadoc.
 *   <li><b>Zero boundary steps</b> with the translator on, and {@link
 *       RepeatDeclineStrategy#isVetoed} answering true for the traversal — the veto itself, not only
 *       its outcome.
 *   <li><b>{@code RepeatUnrollStrategy} still registered</b> on the on arm. The decline works by
 *       marking the traversal, never by removing the unroll — dropping the unroll would strip the
 *       barriers that make the native fallback fast and would move the non-termination from MATCH
 *       into the Gremlin pipeline.
 *   <li><b>The same result as the translator-off run</b>, both compared against an explicit expected
 *       value so the comparison cannot hold vacuously over two empty results.
 * </ul>
 *
 * <p>Both the {@code .count()} form and the {@code .values(...)} form are covered, because the value
 * form materializes the same path space and a count-only test would pass while the catastrophic
 * shape stayed translated. Both polymorphism modes are exercised because the class constraint a
 * recogniser emits differs between them, and a decline that only held under one mode would leave the
 * other translating.
 *
 * <p>Nine further cases stand alone: {@link #repeatInsideAChildStartingAtV_vetoesThatChildToo} (a
 * repeat one nesting level down, in a child the translator would otherwise fold on its own), {@link
 * #childWithoutARepeat_stillTranslates} (the other side of that boundary), {@link
 * #theVeto_doesNotLeakToASiblingOrToARepeatFreeChild} (the marker is per-traversal, not per-tree),
 * {@link #afterLock_theVetoReachesARepeatFreeChildThatAlreadyTranslated} (what the framework does to
 * that reference once compilation finishes, and why the production read is timed the way it is),
 * {@link #translatorAlreadyRemovedFromTheSource_needsNoVeto} (a source that dropped the translator
 * itself), {@link #veto_leavesTheProcessWideStrategyCacheIntact} (the veto never edits the
 * JVM-global strategy set), {@link #translatorOff_leavesTranslatorAndUnrollRegistered} (the
 * measurement control arm), {@link #translatorOff_leavesTheStrategyListAndRequirementsUntouched}
 * (that same control arm compiles exactly as an unmarked traversal does), and {@link
 * #theVetoCarrier_forwardsEveryOperationToTheListItWraps} (the carrier is transparent apart from its
 * own type).
 */
public class RepeatDeclineStrategyTest extends GraphBaseTest {

  /**
   * A case whose shape translates once the veto is out of the way: the decline the case asserts is
   * the veto's work.
   */
  private static final int TRANSLATES_WITHOUT_THE_VETO = 1;

  /**
   * A case whose shape declines with the veto removed as well, because a recogniser refuses it. Such
   * a case guards the reach of the recursive scan and cannot witness the veto's effect on the step
   * list; the marker assertion in the helper is what makes it a witness for the veto at all.
   */
  private static final int DECLINES_WITHOUT_THE_VETO = 0;

  /**
   * Seeds a four-vertex {@code knows} chain a→b→c→d. Two hops from every start vertex reaches
   * exactly {c, d} (a→b→c and b→c→d), which is the expected value every case below pins.
   */
  private void seedKnowsChain() {
    var a = graph.addVertex(T.label, "Person", "name", "a");
    var b = graph.addVertex(T.label, "Person", "name", "b");
    var c = graph.addVertex(T.label, "Person", "name", "c");
    var d = graph.addVertex(T.label, "Person", "name", "d");
    a.addEdge("knows", b);
    b.addEdge("knows", c);
    c.addEdge("knows", d);
    graph.tx().commit();
  }

  /**
   * {@code g.V().repeat(__.out()).times(2).count()} under the default polymorphic mode must run
   * natively and return 2 — the two vertices reachable in exactly two hops.
   */
  @Test
  public void repeatTimesCount_declinesAndCountsNatively_polymorphic() {
    seedKnowsChain();
    assertDeclinedAndEquals(
        "g.V().repeat(out()).times(2).count()",
        () -> graph.traversal().V().repeat(__.out()).times(2).count(),
        List.of(2L),
        TRANSLATES_WITHOUT_THE_VETO);
  }

  /**
   * The same {@code .count()} shape under non-polymorphic mode. The mode changes how a recogniser
   * would constrain the class, so the decline is pinned separately rather than assumed to carry.
   */
  @Test
  public void repeatTimesCount_declinesAndCountsNatively_nonPolymorphic() {
    seedKnowsChain();
    setPolymorphicByDefault(false);
    assertDeclinedAndEquals(
        "g.V().repeat(out()).times(2).count() (non-polymorphic)",
        () -> graph.traversal().V().repeat(__.out()).times(2).count(),
        List.of(2L),
        TRANSLATES_WITHOUT_THE_VETO);
  }

  /**
   * {@code g.V().repeat(__.out()).times(2).values("name")} under the default polymorphic mode. It
   * materializes the same path space as the count form, so it is pinned in its own right: a fix
   * verified only through {@code .count()} would leave this shape translated.
   */
  @Test
  public void repeatTimesValues_declinesAndMatchesNative_polymorphic() {
    seedKnowsChain();
    assertDeclinedAndEquals(
        "g.V().repeat(out()).times(2).values(name)",
        () -> graph.traversal().V().repeat(__.out()).times(2).values("name"),
        List.of("c", "d"),
        TRANSLATES_WITHOUT_THE_VETO);
  }

  /** The value form under non-polymorphic mode; same reasoning as the count form's second case. */
  @Test
  public void repeatTimesValues_declinesAndMatchesNative_nonPolymorphic() {
    seedKnowsChain();
    setPolymorphicByDefault(false);
    assertDeclinedAndEquals(
        "g.V().repeat(out()).times(2).values(name) (non-polymorphic)",
        () -> graph.traversal().V().repeat(__.out()).times(2).values("name"),
        List.of("c", "d"),
        TRANSLATES_WITHOUT_THE_VETO);
  }

  /**
   * {@code until(...)} is the other out-of-scope loop terminator, and it reaches the walker through
   * the same {@code RepeatStep}. Walking {@code knows} until a vertex has no outgoing edge reaches
   * {@code d} from every start vertex that can move at all.
   *
   * <p>This case is a guard, not a witness for the stall this strategy cures. {@code
   * RepeatUnrollStrategy} unrolls only a {@code LoopTraversal} terminator — that is, only {@code
   * times(n)} — so an {@code until}-terminated {@code RepeatStep} survives into the
   * provider-optimization pass, where the walker has no recogniser for it and declines on its own
   * account. What the case pins is that a decline keyed on {@code times(n)} alone would not cover
   * {@code until}, and (through the marker assertion in the helper) that the veto does fire here
   * even though the unroll does not.
   */
  @Test
  public void repeatUntil_declinesAndMatchesNative() {
    seedKnowsChain();
    assertDeclinedAndEquals(
        "g.V().repeat(out()).until(__.not(__.out()))",
        () -> graph
            .traversal()
            .V()
            .repeat(__.out("knows"))
            .until(__.not(__.out("knows")))
            .values("name"),
        List.of("d", "d", "d"),
        DECLINES_WITHOUT_THE_VETO);
  }

  /**
   * A {@code repeat(...)} nested inside a combinator child must veto the whole traversal, not just
   * that child. The translator is all-or-nothing per traversal, so a partial decline would leave the
   * parent translating a pattern assembled from a child it could not read. Over a→b→c→d the union of
   * "two hops" {c, d} and "one hop" {b, c, d} is the five-element multiset below.
   *
   * <p>This shape already declined before the veto existed — the union recogniser rejects it for its
   * own reasons — so the case is a guard on the recursive scan rather than a witness for the fix. It
   * is here so the decline stops depending on which recogniser happens to say no first.
   */
  @Test
  public void repeatNestedInAUnionChild_declinesTheWholeTraversal() {
    seedKnowsChain();
    assertDeclinedAndEquals(
        "g.V().union(repeat(out()).times(2), out())",
        () -> graph
            .traversal()
            .V()
            .union(__.repeat(__.out()).times(2), __.out())
            .values("name"),
        List.of("b", "c", "c", "d", "d"),
        DECLINES_WITHOUT_THE_VETO);
  }

  /**
   * A {@code repeat(...)} inside a sub-traversal must be vetoed in that sub-traversal's own right,
   * not only through its root. {@code g.V().map(__.V().repeat(__.out()).times(n)...)} is the shape:
   * the child's session resolves through its parent, a mid-traversal {@code V()} is a
   * vertex-emitting {@code GraphStep}, and once the unroll has flattened the repeat the child is an
   * ordinary hop chain that the translator folds into a MATCH pattern of its own — the same
   * non-termination, one nesting level down. The control run pins that: with the veto strategy
   * removed, the child does translate.
   *
   * <p>{@code map} rather than {@code union} because a {@code union} child carries an {@code EndStep}
   * the walker has no recogniser for, so a union child declines whatever the veto does and could not
   * witness this. A child built from the traversal source instead of from {@code __} would be the
   * sharper version of the shape, since it would carry the graph's own strategy list, but {@code
   * Bytecode.convertArgument} throws for any child argument carrying source instructions, so the
   * fluent API cannot construct one.
   */
  @Test
  public void repeatInsideAChildStartingAtV_vetoesThatChildToo() {
    seedKnowsChain();
    setTranslatorEnabled(true);

    GraphTraversal<Object, String> controlChild =
        __.<Object>V().repeat(__.out()).times(2).values("name");
    var controlRoot = graph.traversal().V().map(controlChild).asAdmin();
    controlRoot.setStrategies(
        controlRoot.getStrategies().clone().removeStrategies(RepeatDeclineStrategy.class));
    controlRoot.applyStrategies();
    assertThat(countBoundarySteps(controlChild.asAdmin()))
        .as("control: with the veto removed, the child translates the flattened repeat")
        .isEqualTo(1);

    GraphTraversal<Object, String> child =
        __.<Object>V().repeat(__.out()).times(2).values("name");
    var root = graph.traversal().V().map(child).asAdmin();
    root.applyStrategies();

    assertThat(RepeatDeclineStrategy.isVetoed(child.asAdmin()))
        .as("the veto must mark the child that carries the repeat, not the root alone")
        .isTrue();
    assertThat(countBoundarySteps(child.asAdmin()))
        .as("so the child declines to native as well")
        .isZero();
    assertThat(countBoundarySteps(root))
        .as("and the root declines too — the decline is all-or-nothing per traversal")
        .isZero();
  }

  /**
   * The veto must not cost translation anywhere else. A sub-traversal with no {@code repeat} in it
   * still translates on its own account, which is what keeps the decline mechanism from doubling as
   * a blanket "root-only translator" switch.
   *
   * <p>The mechanism is what makes this hold. A child's own strategy list comes from {@code
   * EmptyGraph} and never carries a provider strategy during the strategy pass, so a veto expressed
   * as "the translator is missing from this list" would decline every sub-traversal in the process.
   * Expressed as a marker the veto adds, it declines exactly the traversals it marked.
   */
  @Test
  public void childWithoutARepeat_stillTranslates() {
    seedKnowsChain();
    setTranslatorEnabled(true);

    GraphTraversal<Object, String> child = __.<Object>V().out("knows").values("name");
    var root = graph.traversal().V().map(child).asAdmin();
    root.applyStrategies();

    assertThat(RepeatDeclineStrategy.isVetoed(child.asAdmin()))
        .as("a repeat-free child carries no veto marker")
        .isFalse();
    assertThat(countBoundarySteps(child.asAdmin()))
        .as("and is still translated in its own right")
        .isEqualTo(1);
  }

  /**
   * The marker belongs to one traversal, not to the tree it sits in. A repeat in one union child
   * must veto that child and the root — the root's scan is recursive — while leaving the sibling
   * child, which carries no repeat, free to be translated on its own account.
   *
   * <p>The assertion has to be taken mid-pass, which is why the strategy is driven directly rather
   * than through {@code applyStrategies}. {@code DefaultTraversal.lock()} overwrites every
   * descendant's strategies reference with its parent's as the last act of compilation, so after a
   * full {@code applyStrategies} every descendant of a vetoed root reads as vetoed and the case
   * could not discriminate. {@code TraversalHelper.applyTraversalRecursively} is the same walk
   * {@code applyStrategies} uses per strategy, so this reproduces the exact state {@code
   * GremlinToMatchStrategy} reads in.
   *
   * <p>The rejected carrier is what this case was written against. A side-effect key cannot pass it:
   * {@code g.V().union(a, b)} hands both children the root's own {@code TraversalSideEffects}
   * instance (measured — the three references are identical), so a boolean key written at the root
   * would report every sibling as vetoed and withdraw translation from correct shapes.
   */
  @Test
  public void theVeto_doesNotLeakToASiblingOrToARepeatFreeChild() {
    seedKnowsChain();
    setTranslatorEnabled(true);

    GraphTraversal<Vertex, String> repeatChild =
        __.<Vertex>repeat(__.out()).times(2).values("name");
    GraphTraversal<Vertex, String> plainChild = __.<Vertex>out().values("name");
    var root = graph.traversal().V().union(repeatChild, plainChild).asAdmin();

    assertThat(root.getSideEffects())
        .as("premise of the rejected carrier: root and children share one side-effects instance")
        .isSameAs(repeatChild.asAdmin().getSideEffects())
        .isSameAs(plainChild.asAdmin().getSideEffects());

    TraversalHelper.applyTraversalRecursively(RepeatDeclineStrategy.instance()::apply, root);

    assertThat(RepeatDeclineStrategy.isVetoed(repeatChild.asAdmin()))
        .as("the child holding the repeat is vetoed")
        .isTrue();
    assertThat(RepeatDeclineStrategy.isVetoed(root))
        .as("and so is the root, whose scan reaches into that child")
        .isTrue();
    assertThat(RepeatDeclineStrategy.isVetoed(plainChild.asAdmin()))
        .as("but the repeat-free sibling must not inherit the veto — declining it would withdraw "
            + "translation from a shape the translator handles correctly")
        .isFalse();
  }

  /**
   * The post-{@code lock()} state of the marker, measured rather than read off the production
   * Javadoc. {@code DefaultTraversal.lock()} copies the parent's strategies reference into every
   * non-root traversal as the last act of that traversal's compilation, so a repeat-free child of a
   * vetoed root reads as vetoed once compilation has finished — even though it translated during the
   * strategy pass, while it still held its own reference. Both halves are asserted: the propagation
   * is a fact about TinkerPop that this case notices if it ever changes, and the child's boundary
   * step is the evidence that the production read happened before the copy. A future reader placed
   * after {@code lock()} would flip the second assertion and cost translation on a shape the veto was
   * never meant to reach.
   */
  @Test
  public void afterLock_theVetoReachesARepeatFreeChildThatAlreadyTranslated() {
    seedKnowsChain();
    setTranslatorEnabled(true);

    GraphTraversal<Object, String> child = __.<Object>V().out("knows").values("name");
    var vetoedRoot = graph.traversal().V().repeat(__.out()).times(2).map(child).asAdmin();
    vetoedRoot.applyStrategies();

    assertThat(RepeatDeclineStrategy.isVetoed(vetoedRoot))
        .as("precondition: the root carries the repeat, so the veto fired on it")
        .isTrue();
    assertThat(RepeatDeclineStrategy.isVetoed(child.asAdmin()))
        .as("after lock() the repeat-free child reads as vetoed, because it now holds the root's "
            + "strategies reference rather than its own")
        .isTrue();
    assertThat(countBoundarySteps(child.asAdmin()))
        .as("yet the child was translated: the translator read the marker during the strategy pass, "
            + "before lock() copied the root's reference down")
        .isEqualTo(1);
  }

  /**
   * The veto tolerates a source which has already dropped the translator: the two mechanisms
   * compose, and the traversal runs natively and returns the native two-hop result. This is the one
   * path in the class that reaches TinkerPop's in-place {@code removeStrategies} through {@code
   * withoutStrategies}.
   *
   * <p>The zero boundary count and the native rows hold under either mechanism alone, so they cannot
   * tell the two apart — drop the {@code withoutStrategies} call and the veto still declines the
   * traversal; unregister the veto and the source still has no translator to engage. The two
   * assertions above them are what separate them: the strategy list genuinely no longer carries the
   * translator, and the marker is on the list anyway. Without the first, a {@code VetoedStrategies}
   * wrapper that swallowed the removal would look the same; without the second, a veto that gave up
   * on an unfamiliar strategy list would.
   */
  @Test
  public void translatorAlreadyRemovedFromTheSource_needsNoVeto() {
    seedKnowsChain();
    setTranslatorEnabled(true);
    var admin =
        graph
            .traversal()
            .withoutStrategies(GremlinToMatchStrategy.class)
            .V()
            .repeat(__.out())
            .times(2)
            .values("name")
            .asAdmin();
    admin.applyStrategies();

    assertThat(admin.getStrategies().getStrategy(GremlinToMatchStrategy.class))
        .as("withoutStrategies must really have removed the translator from this traversal's list, "
            + "and the veto carrier must forward the read rather than mask it")
        .isEmpty();
    assertThat(RepeatDeclineStrategy.isVetoed(admin))
        .as("and the veto still fires on top of that removal, so the two mechanisms compose")
        .isTrue();
    assertThat(countBoundarySteps(admin))
        .as("a source without the translator must produce no boundary step")
        .isZero();
    assertThat(sortedByStringForm(admin.toList()))
        .as("and must still return the native two-hop result")
        .isEqualTo(List.of("c", "d"));
  }

  /**
   * The veto edits a copy of the strategy list, never the process-wide one. {@code
   * graph.traversal()} hands every traversal the exact {@code TraversalStrategies} instance that
   * {@code TraversalStrategies.GlobalCache} holds for the graph class, shared by every graph and
   * every thread in the JVM, so a veto that lost its {@code clone()} would mark — and thereby
   * silence the translator for — the rest of the process, including the three sibling test classes
   * surefire runs beside this one. That damage would outlive the test, because the cache entry is
   * keyed by graph class while each test's database is not, and it would surface as a
   * translator-coverage regression with no failing test pointing at it. Reading the cache directly,
   * and then compiling a second repeat-free traversal on the same graph, pins it independently of
   * method order.
   */
  @Test
  public void veto_leavesTheProcessWideStrategyCacheIntact() {
    seedKnowsChain();
    setTranslatorEnabled(true);

    var vetoed = graph.traversal().V().repeat(__.out()).times(2).count().asAdmin();
    vetoed.applyStrategies();
    assertThat(countBoundarySteps(vetoed))
        .as("precondition: the repeat-bearing traversal declines, so the veto did fire")
        .isZero();
    assertThat(RepeatDeclineStrategy.isVetoed(vetoed))
        .as("precondition: the veto marked this traversal's own strategy list")
        .isTrue();

    var cached = TraversalStrategies.GlobalCache.getStrategies(graph.getClass());
    assertThat(RepeatDeclineStrategy.isVetoed(vetoed))
        .as("precondition restated: the traversal under test is the vetoed one")
        .isTrue();
    assertThat(cached)
        .as("the veto must not mark the JVM-global strategy cache itself")
        .isNotInstanceOf(RepeatDeclineStrategy.VetoedStrategies.class);
    assertThat(cached.getStrategy(GremlinToMatchStrategy.class))
        .as("and must leave the translator registered in that cache")
        .isPresent();
    assertSameStrategiesInOrder(
        "the vetoed traversal reads through to the global list, so that list must be untouched",
        vetoed.getStrategies().toList(),
        cached.toList());

    var later = graph.traversal().V().out("knows").out("knows").asAdmin();
    later.applyStrategies();
    assertThat(countBoundarySteps(later))
        .as("a later traversal on the same graph must still translate")
        .isEqualTo(1);
  }

  /**
   * With the translator off, nothing is removed from the traversal's strategy list: the translator
   * is still registered, the unroll is still registered, and the unroll still rewrote the repeat
   * into chained hops. This is the control arm every measurement on this branch compares against, so
   * a decline mechanism that disabled a strategy here would change a shipped path as well as
   * invalidate the comparison.
   *
   * <p>The veto does mark the traversal on this arm — it does not read the kill-switch, because two
   * reads of a flag another thread can flip can disagree. The marker changes no behaviour with the
   * translator off, since the translator declines at its own session gate either way, which is what
   * the assertions below pin.
   */
  @Test
  public void translatorOff_leavesTranslatorAndUnrollRegistered() {
    seedKnowsChain();
    setTranslatorEnabled(false);
    var admin = graph.traversal().V().repeat(__.out()).times(2).count().asAdmin();
    admin.applyStrategies();

    assertThat(admin.getStrategies().getStrategy(GremlinToMatchStrategy.class))
        .as("translator off: the translator strategy stays in the traversal's own strategy list")
        .isPresent();
    assertThat(admin.getStrategies().getStrategy(RepeatUnrollStrategy.class))
        .as("translator off: the unroll strategy stays in the traversal's own strategy list")
        .isPresent();
    assertThat(admin.getSteps())
        .as("translator off: the unroll still rewrote the repeat into chained hops")
        .noneMatch(step -> step instanceof RepeatStep<?>);
    assertThat(countBoundarySteps(admin))
        .as("translator off: the traversal runs natively, marked or not")
        .isZero();
    assertThat(RepeatDeclineStrategy.isVetoed(admin))
        .as("translator off: the veto still marks the traversal, because it never reads the "
            + "kill-switch — no flip between two reads of that flag can skip it")
        .isTrue();
  }

  /**
   * With the translator off, a repeat-bearing traversal must compile exactly as an unmarked one
   * does. This is the arm every measurement on this branch compares against, so the marker has to
   * be inert here in a stronger sense than "the answer is the same": the strategy list must be the
   * same objects in the same order, and the traverser requirements must be the same set.
   *
   * <p>Both halves name a carrier that fails them. The list assertion fails against a marker added
   * to a cloned strategy list — the clone carries one entry more, and {@code
   * TraversalStrategies.sortStrategies} re-runs over it and may seat the unconstrained
   * optimizations, {@code RepeatUnrollStrategy} and {@code AdjacentToIncidentStrategy} among them,
   * in a different order. The requirements assertion fails against a side-effect key: {@code
   * DefaultTraversal.getTraverserRequirements} adds {@code SIDE_EFFECTS} whenever {@code
   * getSideEffects().keys()} is non-empty, which swaps the traverser generator from {@code
   * B_O_TraverserGenerator} to {@code B_O_S_SE_SL_TraverserGenerator} (measured).
   *
   * <p>The control is a hand-written two-hop count rather than a second repeat form, so it is a
   * traversal the veto never touches. Both shapes reduce to the same chained hops once {@code
   * RepeatUnrollStrategy} has run.
   */
  @Test
  public void translatorOff_leavesTheStrategyListAndRequirementsUntouched() {
    seedKnowsChain();
    setTranslatorEnabled(false);

    var control = graph.traversal().V().out("knows").out("knows").count().asAdmin();
    control.applyStrategies();

    var vetoed = graph.traversal().V().repeat(__.out()).times(2).count().asAdmin();
    vetoed.applyStrategies();

    assertThat(RepeatDeclineStrategy.isVetoed(vetoed))
        .as("precondition: the veto did fire on the off arm, so the case measures the marked state")
        .isTrue();
    assertThat(RepeatDeclineStrategy.isVetoed(control))
        .as("precondition: the control is unmarked")
        .isFalse();

    assertSameStrategiesInOrder(
        "translator off: a marked traversal must see the same strategy list an unmarked one sees",
        vetoed.getStrategies().toList(),
        control.getStrategies().toList());

    assertThat(vetoed.getTraverserRequirements())
        .as("translator off: the marker must not add a traverser requirement — SIDE_EFFECTS would "
            + "change which traverser generator the native pipeline picks")
        .doesNotContain(TraverserRequirement.SIDE_EFFECTS)
        .isEqualTo(control.getTraverserRequirements());
  }

  /**
   * The carrier is transparent for everything except its own type. Every {@code
   * TraversalStrategies} operation must reach the wrapped list and report what the wrapped list
   * reports, because a traversal that has been vetoed is still an ordinary traversal to every other
   * strategy, to {@code applyStrategies}, and to {@code lock()}. The one deliberate exception is
   * {@code clone()}, which keeps the veto: a copy describes the same repeat-bearing query, and
   * carrying the decline forward is the safe direction.
   *
   * <p>The wrapper is driven directly, over a detached copy of the graph's strategy list. Its
   * mutators forward verbatim, so exercising them through a live traversal would edit the JVM-global
   * {@code GlobalCache} instance every other test in this fork compiles against.
   */
  @Test
  public void theVetoCarrier_forwardsEveryOperationToTheListItWraps() {
    var delegate = graph.traversal().V().asAdmin().getStrategies().clone();
    var wrapper = new RepeatDeclineStrategy.VetoedStrategies(delegate);

    assertSameStrategiesInOrder(
        "a fresh wrapper reads the wrapped list through unchanged", wrapper.toList(),
        delegate.toList());
    assertThat(wrapper.iterator())
        .toIterable()
        .as("and iterates it in the same order, which is what applyStrategies consumes")
        .containsExactlyElementsOf(delegate.toList());
    assertThat(wrapper.getStrategy(GremlinToMatchStrategy.class))
        .as("lookups resolve against the wrapped list, not against the wrapper")
        .isPresent();
    assertThat(wrapper.toString())
        .as("and the rendering is the wrapped list's, so a log line reads as it did before")
        .isEqualTo(delegate.toString());

    assertThat(wrapper.removeStrategies(GremlinToMatchStrategy.class))
        .as("removeStrategies returns the wrapper, so the veto outlives a removal")
        .isSameAs(wrapper);
    assertThat(delegate.getStrategy(GremlinToMatchStrategy.class))
        .as("and the removal reached the wrapped list")
        .isEmpty();

    assertThat(wrapper.addStrategies(GremlinToMatchStrategy.instance()))
        .as("addStrategies likewise returns the wrapper")
        .isSameAs(wrapper);
    assertThat(delegate.getStrategy(GremlinToMatchStrategy.class))
        .as("and reached the wrapped list")
        .isPresent();

    var copy = wrapper.clone();
    assertThat(copy)
        .as("a clone of a vetoed list stays vetoed")
        .isInstanceOf(RepeatDeclineStrategy.VetoedStrategies.class)
        .isNotSameAs(wrapper);
    assertThat(copy.toList())
        .as("and carries the same strategies")
        .containsExactlyElementsOf(delegate.toList());
  }

  /**
   * The veto is idempotent: a second pass over an already-marked traversal returns without wrapping
   * the strategies reference a second time. Both calls go through {@code apply} directly, because
   * {@code applyStrategies} locks a traversal after its first pass and so cannot drive this.
   *
   * <p>Neither call sets the kill-switch, which is the second thing the case pins: the veto marks
   * regardless of the flag, so no interleaving of two reads of it can leave a repeat-bearing
   * traversal unmarked.
   */
  @Test
  public void applyingTheVetoTwice_wrapsTheStrategiesReferenceOnce() {
    seedKnowsChain();
    var admin = graph.traversal().V().repeat(__.out()).times(2).count().asAdmin();

    RepeatDeclineStrategy.instance().apply(admin);
    var afterFirstPass = admin.getStrategies();
    assertThat(RepeatDeclineStrategy.isVetoed(admin))
        .as("the first pass marks the traversal without reading the kill-switch")
        .isTrue();

    RepeatDeclineStrategy.instance().apply(admin);
    assertThat(admin.getStrategies())
        .as("the second pass must reuse the existing wrapper rather than nest another one")
        .isSameAs(afterFirstPass);
  }

  /**
   * A failure inside the veto declines the veto and not the query. The strategy is registered for
   * every YouTrackDB graph and runs on every compilation, so an exception escaping it would abort
   * traversals that have nothing to do with the translator. Here {@code getStrategies()} throws once
   * the traversal is built; {@code apply} must swallow it and leave the traversal unmarked, which
   * hands the decision back to the translator's own gates.
   */
  @Test
  public void aThrowInsideTheVeto_declinesTheVetoInsteadOfAbortingCompilation() {
    var armed = new AtomicBoolean(false);
    var traversal =
        new DefaultGraphTraversal<Object, Object>() {
          @Override
          public TraversalStrategies getStrategies() {
            if (armed.get()) {
              throw new IllegalStateException("strategy list unavailable");
            }
            return super.getStrategies();
          }
        };
    traversal.repeat(__.identity()).times(2);
    armed.set(true);
    try {
      RepeatDeclineStrategy.instance().apply(traversal.asAdmin());
    } finally {
      armed.set(false);
    }

    assertThat(RepeatDeclineStrategy.isVetoed(traversal.asAdmin()))
        .as("the veto declined, so the traversal carries no marker and compiles as it did before")
        .isFalse();
  }

  /**
   * Runs {@code scenario}'s shape three times: once with {@link RepeatDeclineStrategy} removed as a
   * control, once with the translator on, once with it off. Asserts the control's boundary-step
   * count is {@code boundaryStepsWithoutTheVeto}, that the on-run engages no boundary step and
   * carries the veto marker, that the unroll survives on the on arm, and that both real runs produce
   * {@code expected}. The expected value is passed in rather than derived from the off-run alone, so
   * a seeding regression that emptied the graph would fail the case instead of making it pass
   * vacuously.
   *
   * @param boundaryStepsWithoutTheVeto {@link #TRANSLATES_WITHOUT_THE_VETO} when the shape is a
   *     translation candidate the veto is what stops, {@link #DECLINES_WITHOUT_THE_VETO} when a
   *     recogniser declines it too. The control run is compiled but never iterated, so a case that
   *     translates here costs one plan build and no path enumeration.
   */
  private void assertDeclinedAndEquals(
      String scenario,
      Supplier<GraphTraversal<?, ?>> traversalSupplier,
      List<?> expected,
      int boundaryStepsWithoutTheVeto) {
    setTranslatorEnabled(true);

    var control = traversalSupplier.get().asAdmin();
    // AdjacentToIncidentStrategy comes out with the veto because it rewrites the last vertex hop
    // before a count() into an edge hop, which no recogniser accepts. Whether it fires on an
    // unrolled repeat depends on where TinkerPop's sort puts it relative to RepeatUnrollStrategy,
    // and RepeatUnrollStrategy declares no ordering constraints at all, so that position varies
    // between JVMs. Removing it keeps this control asserting one thing — that the chain the unroll
    // produces is a translation candidate — instead of also sampling a coin flip.
    control.setStrategies(
        control
            .getStrategies()
            .clone()
            .removeStrategies(RepeatDeclineStrategy.class, AdjacentToIncidentStrategy.class));
    control.applyStrategies();
    assertThat(countBoundarySteps(control))
        .as(scenario + " (veto removed): control on the exact shape the veto declines — without it,"
            + " a zero-boundary-step assertion below would hold for any reason at all; compiled to "
            + control.getSteps())
        .isEqualTo(boundaryStepsWithoutTheVeto);

    var onAdmin = traversalSupplier.get().asAdmin();
    onAdmin.applyStrategies();
    assertThat(countBoundarySteps(onAdmin))
        .as(scenario + " (translator on) must decline to native — no boundary step")
        .isZero();
    assertThat(RepeatDeclineStrategy.isVetoed(onAdmin))
        .as(scenario + " (translator on): the veto must mark this traversal, so the decline is this"
            + " strategy's work and not a recogniser's")
        .isTrue();
    assertThat(onAdmin.getStrategies().getStrategy(RepeatUnrollStrategy.class))
        .as(scenario + " (translator on) must keep the unroll strategy, which supplies the "
            + "barriers the native fallback needs")
        .isPresent();
    var onValues = sortedByStringForm(onAdmin.toList());

    setTranslatorEnabled(false);
    var offAdmin = traversalSupplier.get().asAdmin();
    offAdmin.applyStrategies();
    var offValues = sortedByStringForm(offAdmin.toList());

    assertThat(onValues).as(scenario + " (translator on) result").isEqualTo(expected);
    assertThat(offValues).as(scenario + " (translator off) result").isEqualTo(expected);
  }

  /**
   * Writes the kill-switch on the session's {@code ContextConfiguration}. No case restores it: the
   * configuration belongs to the storage {@code DbTestBase} creates for this test method and drops
   * in its {@code @After}, so the write cannot reach {@code GlobalConfiguration} or any later test
   * in the same fork. The graph opens the same database, so the flag applies to its traversals too.
   */
  private void setTranslatorEnabled(boolean enabled) {
    session
        .getConfiguration()
        .setValue(GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED, enabled);
  }

  /**
   * Writes {@code QUERY_GREMLIN_POLYMORPHIC_BY_DEFAULT} through the same per-test handle as {@link
   * #setTranslatorEnabled}, so the non-polymorphic cases differ from their twins in one flag and
   * nothing else. Not restored, for the reason given there.
   */
  private void setPolymorphicByDefault(boolean polymorphic) {
    session
        .getConfiguration()
        .setValue(GlobalConfiguration.QUERY_GREMLIN_POLYMORPHIC_BY_DEFAULT, polymorphic);
  }

  /**
   * Results ordered by their string form, which preserves multiplicity for a multiset comparison
   * while leaving the values themselves unconverted — a {@code .count()} case therefore compares
   * against {@code 2L} rather than {@code "2"}.
   */
  private static List<Object> sortedByStringForm(List<?> results) {
    var sorted = new ArrayList<Object>(results);
    sorted.sort(Comparator.comparing(String::valueOf));
    return sorted;
  }

  /**
   * Asserts two strategy lists hold the same instances at the same positions. Identity rather than
   * equality, because {@code AbstractTraversalStrategy.equals} compares only the runtime class and
   * would pass over two distinct copies; position by position rather than as a set, because the
   * order is the thing a re-sort moves.
   */
  private static void assertSameStrategiesInOrder(
      String description,
      List<? extends TraversalStrategy<?>> actual,
      List<? extends TraversalStrategy<?>> expected) {
    assertThat(actual).as(description + " — list length").hasSameSizeAs(expected);
    for (var i = 0; i < expected.size(); i++) {
      assertThat(actual.get(i))
          .as(description + " — strategy at position " + i)
          .isSameAs(expected.get(i));
    }
  }

  /** Counts boundary steps of every form, keyed on the shared base rather than one concrete step. */
  private static int countBoundarySteps(Traversal.Admin<?, ?> admin) {
    var count = 0;
    for (var step : admin.getSteps()) {
      if (step instanceof AbstractMatchPlanStep<?, ?>) {
        count++;
      }
    }
    return count;
  }
}
