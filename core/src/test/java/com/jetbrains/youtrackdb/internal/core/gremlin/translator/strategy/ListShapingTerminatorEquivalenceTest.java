package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBTransaction;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy.TranslatorEquivalenceSupport.Cardinality;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy.TranslatorEquivalenceSupport.Recognition;
import java.util.List;
import java.util.function.Supplier;
import org.apache.tinkerpop.gremlin.process.traversal.Operator;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.junit.Test;

/**
 * Translator-on / translator-off equivalence for the four list-shaping terminators — {@code fold} /
 * {@code unfold} / {@code reverse} / {@code tail} — on the shared harness. The recogniser suites
 * ({@link FoldStepRecogniserTest}, {@link UnfoldReverseTailRecogniserTest}) pin what each terminator
 * registers and compare the translated arm against a hand-computed answer; this suite asks the other
 * question, which no structural assertion reaches: does the translated arm answer what the native
 * Gremlin pipeline answers, for the shapes a user actually writes.
 *
 * <p>Every case runs the same shape twice through
 * {@link TranslatorEquivalenceSupport#assertEquivalent}, so each carries the harness's two
 * anti-vacuity pins as well as the comparison: boundary-step engagement (one step on the on-arm for a
 * {@code RECOGNIZED} shape, none for a {@code DECLINED} one, never any on the off-arm) and a non-empty
 * result behind the equality. Both matter here more than usual, because the two arms agree by
 * construction wherever a shape silently declines.
 *
 * <p><b>Order handling is two-tier rather than one comparison for everything.</b> The translator's
 * equivalence standard is multiset equality, since MATCH's planner reorders freely — but {@code
 * fold()}'s list order and {@code tail(n)}'s window are positional, so a multiset comparison over them
 * would pass under a window that kept the wrong rows in the right quantity. Shapes carrying an
 * explicit {@code order().by(...)} are therefore compared element for element (MATCH compiles that into
 * an {@code ORDER BY}, and the modern graph's names are distinct, so the sort is total on both arms);
 * shapes with no ordered input are compared as multisets, with a folded list canonicalised by sorting
 * inside it.
 *
 * <p><b>Present-null payloads are deliberately absent from every fixture here.</b> Native {@code
 * UnfoldStep.flatMap} reaches {@code value.getClass()} unguarded and throws where the translated stage
 * emits the value, a divergence recorded as bounded and named rather than fixed, so a fixture carrying
 * one would compare an exception against a row.
 */
public class ListShapingTerminatorEquivalenceTest extends GraphBaseTest {

  private final TranslatorEquivalenceSupport support =
      new TranslatorEquivalenceSupport(this::graphSession);

  // ---------------------------------------------------------------------------
  // fold — the drain.
  // ---------------------------------------------------------------------------

  /**
   * {@code g.V().order().by(name).values(name).fold()} folds an ordered projection into one list, and
   * the translated list holds the same values in the same positions as native's. Compared element for
   * element because that is the discriminating comparison for a drain: the payload is a single list, so
   * a multiset comparison over the result would only ever compare two one-element lists and could not
   * see the contents drift.
   */
  @Test
  public void foldOverAnOrderedProjection_returnsTheSameListAsNative() {
    ModernGraphFixture.seed(graph, session);

    assertOrdered(
        "g.V().order().by(name).values(name).fold()",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().order().by("name").values("name").fold());
  }

  /**
   * {@code g.V().fold()} over element payloads drains every vertex into one list on both arms. The
   * comparison is a multiset one — nothing pins the arrival order of an unordered scan, and MATCH's
   * planner is free to choose its own — so the renderer sorts inside the folded list. What survives
   * that canonicalisation is the property worth pinning: the same vertices, the same number of times,
   * in one list rather than one list per row.
   */
  @Test
  public void foldOverUnorderedElements_returnsTheSameOneListMultisetAsNative() {
    ModernGraphFixture.seed(graph, session);

    assertMultiset(
        "g.V().fold()",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().fold());
  }

  /**
   * A projection that drops every row still folds to one empty list on both arms. No vertex in the
   * fixture carries {@code nickname}, so the projection's absent-property handling removes all six rows
   * before the drain runs, and a drain that emitted nothing on a dry stream would return zero results
   * where native returns one. The result is genuinely empty of values, so the fixture's cardinality
   * opt-out is not in play: the payload list is non-empty (it holds the one empty list), which is
   * exactly the distinction this case exists to keep.
   */
  @Test
  public void foldOverAProjectionThatDropsEveryRow_stillReturnsNativesOneEmptyList() {
    ModernGraphFixture.seed(graph, session);

    assertMultiset(
        "g.V().values(nickname).fold() — every row dropped before the drain",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().values("nickname").fold());
  }

  // ---------------------------------------------------------------------------
  // tail — the window.
  // ---------------------------------------------------------------------------

  /**
   * {@code tail(2)} over an ordered projection keeps the same last two rows as native. The ordered
   * prefix is what makes this discriminating: a window that kept the first two rather than the last two
   * returns a result of the right size, and only a total order on both arms turns the two readings into
   * disjoint answers rather than two orderings of one.
   */
  @Test
  public void tailOverAnOrderedProjection_keepsTheSameLastRowsAsNative() {
    ModernGraphFixture.seed(graph, session);

    assertOrdered(
        "g.V().order().by(name).values(name).tail(2)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().order().by("name").values("name").tail(2));
  }

  /**
   * A window wider than the stream returns every row on both arms rather than padding, throwing, or
   * emitting out of a fixed-size ring. This is the branch where the window's eviction arithmetic never
   * runs, so it is the one an implementation built around a full ring gets wrong.
   */
  @Test
  public void tailWindowWiderThanTheProjection_returnsEveryRowAsNative() {
    ModernGraphFixture.seed(graph, session);

    assertOrdered(
        "g.V().order().by(name).values(name).tail(20)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().order().by("name").values("name").tail(20));
  }

  /**
   * A zero window returns nothing on both arms, which is native's answer — {@code TailGlobalStep} trims
   * its deque back to the limit after every add, so a limit of zero trims everything away. Both arms
   * being empty is what makes the comparison unable to fail on its own, so this case takes the
   * harness's cardinality opt-out and pays for it twice: the boundary-step pins still establish that the
   * on-arm translated and the off-arm did not, and the {@code tail(6)} control on the same fixture
   * establishes that the fixture returns rows at all.
   */
  @Test
  public void zeroTailWindow_returnsNothingOnBothArms_withATranslatingControlBesideIt() {
    ModernGraphFixture.seed(graph, session);

    support.assertEquivalent(
        "g.V().order().by(name).values(name).tail(0)",
        Recognition.RECOGNIZED,
        Cardinality.MAY_BE_EMPTY,
        ListShapingTerminatorEquivalenceTest::shapedInArrivalOrder,
        () -> graph.traversal().V().order().by("name").values("name").tail(0));

    assertOrdered(
        "control: the same fixture through a non-zero window returns its rows",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().order().by("name").values("name").tail(6));
  }

  // ---------------------------------------------------------------------------
  // reverse — the per-payload value transform.
  // ---------------------------------------------------------------------------

  /**
   * {@code reverse()} reverses each projected value and leaves the stream where it was, matching native
   * on both counts. Reading it as a stream reverse is the plausible misreading, and over an ordered
   * projection the two readings disagree on every row: native answers the names spelled backwards in
   * ascending order of the original, a stream reverse answers the names unchanged in descending order.
   */
  @Test
  public void reverseOverAnOrderedProjection_returnsTheSameReversedValuesAsNative() {
    ModernGraphFixture.seed(graph, session);

    assertOrdered(
        "g.V().order().by(name).values(name).reverse()",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().order().by("name").values("name").reverse());
  }

  // ---------------------------------------------------------------------------
  // unfold — the flat-map, over the payload shapes production actually emits.
  // ---------------------------------------------------------------------------

  /**
   * {@code groupCount().by(name).unfold()} expands one accumulated map into its entries, matching
   * native entry for entry. This is the map arm over the 1→N direction — one payload in, one per group
   * out — and it is an ordinary suite idiom rather than a synthetic shape, which is why it is compared
   * against native here as well as against a hand-computed answer in the recogniser suite.
   */
  @Test
  public void unfoldOverAGroupCountMap_returnsTheSameEntriesAsNative() {
    ModernGraphFixture.seed(graph, session);

    assertMultiset(
        "g.V().groupCount().by(name).unfold()",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().groupCount().by("name").unfold());
  }

  /**
   * {@code valueMap(name).unfold()} runs the same map arm the other way round — one map per row, so the
   * stage runs once per row over a single entry each — and the entries match native's, values wrapped
   * the way {@code valueMap} wraps them. The pair with the case above is deliberate: a stage that
   * expanded only the first payload, or only a map it found in a single-payload stream, passes one of
   * the two and fails the other.
   */
  @Test
  public void unfoldOverAValueMapPerRow_returnsTheSameEntriesAsNative() {
    ModernGraphFixture.seed(graph, session);

    assertMultiset(
        "g.V().valueMap(name).unfold()",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().valueMap("name").unfold());
  }

  /**
   * {@code g.V().unfold()} over element payloads is an identity on both arms. A vertex is not an
   * iterator, an iterable, a map or an array, so it lands on the arm that emits the payload unchanged —
   * and a stage that expanded only collection-shaped payloads would turn this shape from an identity
   * into an empty result, which the harness's non-empty pin catches even before the comparison does.
   */
  @Test
  public void unfoldOverElementPayloads_returnsTheSameVerticesAsNative() {
    ModernGraphFixture.seed(graph, session);

    assertMultiset(
        "g.V().unfold()",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().unfold());
  }

  // ---------------------------------------------------------------------------
  // Composition: a drain behind a per-payload stage, and two per-payload stages
  // either way round.
  // ---------------------------------------------------------------------------

  /**
   * {@code reverse().fold()} — a drain behind a per-payload stage — folds the reversed values into one
   * list, matching native element for element over an ordered projection. This is the composition the
   * walker's two-set may-follow rule exists to admit, and the ordered comparison is what makes it
   * meaningful: a carrier that applied the two stages the other way round would fold first and then try
   * to reverse one list, answering the names in reverse order rather than each name reversed.
   */
  @Test
  public void reverseThenFold_returnsTheSameFoldedListAsNative() {
    ModernGraphFixture.seed(graph, session);

    assertOrdered(
        "g.V().order().by(name).values(name).reverse().fold()",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().order().by("name").values("name").reverse().fold());
  }

  /**
   * Two per-payload stages compose in either declared order and both spellings match native. No
   * production payload distinguishes them by result — a string is atomic to {@code unfold} and a map
   * entry is unreversible — so this pair does not witness declared order; the registered stage list
   * carries that claim in {@link UnfoldReverseTailRecogniserTest}. What it does witness is that
   * admitting a second per-payload stage did not change either spelling's answer, which a composition
   * that dropped one of the two stages would break.
   */
  @Test
  public void perPayloadStagesInEitherOrder_returnTheSameValuesAsNative() {
    ModernGraphFixture.seed(graph, session);

    assertOrdered(
        "g.V().order().by(name).values(name).reverse().unfold()",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().order().by("name").values("name").reverse().unfold());
    assertOrdered(
        "g.V().order().by(name).values(name).unfold().reverse()",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().order().by("name").values("name").unfold().reverse());
  }

  // ---------------------------------------------------------------------------
  // Declines. Each one is a silent wrong answer if the shape translates, and
  // each is paired with a control that translates on the same fixture.
  // ---------------------------------------------------------------------------

  /**
   * The seeded reduce {@code fold(seed, operator)} declines and keeps native's summed scalar. It rides
   * the same step class as the list form and is told apart only by a flag on it, so a recogniser
   * skipping that check would register a drain and turn one scalar into a list of the summands. The
   * control is the list form of the same class over the same projection, which translates.
   */
  @Test
  public void seededReduceFold_declines_andKeepsNativesSummedScalar() {
    ModernGraphFixture.seed(graph, session);

    assertMultiset(
        "g.V().values(age).fold(0, sum) — the seeded reduce",
        Recognition.DECLINED,
        () -> graph.traversal().V().values("age").fold(0, Operator.sum));
    assertMultiset(
        "control: the list form of the same class over the same projection translates",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().values("age").fold());
  }

  /**
   * A slice behind a drain declines and keeps native's whole list. {@code LIMIT} rides the assembled
   * statement, which MATCH applies as the plan runs and therefore strictly before the boundary applies
   * the drain, so a translation would fold two rows into a two-element list where native folds every row
   * and then keeps the one list it made. The control is the same prefix without the slice.
   */
  @Test
  public void sliceBehindADrain_declines_andKeepsNativesWholeList() {
    ModernGraphFixture.seed(graph, session);

    assertMultiset(
        "g.V().values(name).fold().limit(2) — a row-level slice behind a drain",
        Recognition.DECLINED,
        () -> graph.traversal().V().values("name").fold().limit(2));
    assertMultiset(
        "control: the same prefix without the slice translates",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().values("name").fold());
  }

  /**
   * A count behind a drain declines and keeps native's count of one. {@code count(*)} rides the
   * statement the same way the slice above does, so a translation would count the rows the drain was
   * meant to consume — six here — where native counts the one list the drain made. The control is the
   * bare count on the same fixture.
   */
  @Test
  public void countBehindADrain_declines_andKeepsNativesCountOfTheOneList() {
    ModernGraphFixture.seed(graph, session);

    assertMultiset(
        "g.V().fold().count() — a count behind a drain",
        Recognition.DECLINED,
        () -> graph.traversal().V().fold().count());
    assertMultiset(
        "control: the bare count on the same fixture translates",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().count());
  }

  /**
   * A {@code dedup()} behind an expansion declines and keeps native's entries. {@code RETURN DISTINCT}
   * rides the statement, so it would deduplicate the rows the expansion was meant to consume rather
   * than the entries it produced — the row-level and payload-level answers differ as soon as one row
   * expands into several. This is the shape that shows admitting two kinds of list-shaping stage did not
   * widen the walker's gate to every following step; the control is the same prefix without the
   * {@code dedup}.
   */
  @Test
  public void dedupBehindAnExpansion_declines_andKeepsNativesEntries() {
    ModernGraphFixture.seed(graph, session);

    assertMultiset(
        "g.V().valueMap(name).unfold().dedup() — a distinct behind an expansion",
        Recognition.DECLINED,
        () -> graph.traversal().V().valueMap("name").unfold().dedup());
    assertMultiset(
        "control: the same prefix without the dedup translates",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().valueMap("name").unfold());
  }

  /**
   * A combinator child carrying a drain declines, and the whole traversal keeps native's answer instead
   * of throwing out of strategy application. {@code not(__.out(knows).fold())} is the one combinator
   * spelling whose correct and swallowed answers differ: the child always emits one list, empty or not,
   * so native's filter is true for every vertex and the {@code not} keeps nothing — while a context that
   * silently swallowed the child's stage would translate the shape into a detached anti-join and return
   * every vertex with no incoming {@code knows} edge.
   *
   * <p>Native answering nothing is why this case takes the cardinality opt-out and why the control
   * matters: {@code not(__.out(knows))} translates on the same fixture and returns rows, so the empty
   * answer above is the shape's own and not a fixture that seeded nothing. The boundary-step pin is
   * what discriminates — a swallow makes the on-arm engage a boundary step.
   */
  @Test
  public void combinatorChildCarryingADrain_declines_withATranslatingControlBesideIt() {
    ModernGraphFixture.seed(graph, session);

    support.assertEquivalent(
        "g.V().not(out(knows).fold()) — a drain inside a combinator child",
        Recognition.DECLINED,
        Cardinality.MAY_BE_EMPTY,
        ListShapingTerminatorEquivalenceTest::shapedAsMultiset,
        () -> graph.traversal().V().not(__.out("knows").fold()));

    // Pin the native oracle the opt-out rests on: the child's drain always emits one list, so the NOT
    // filters out every vertex. A swallowed append would answer the sink-free vertices instead.
    support.withTranslator(
        false,
        () -> assertThat(graph.traversal().V().not(__.out("knows").fold()).toList())
            .as("native keeps nothing — a dry upstream still emits one empty list, so the child's "
                + "filter is true for every vertex")
            .isEmpty());

    assertMultiset(
        "control: the same combinator over a child with no drain translates and returns rows",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().not(__.out("knows")));
  }

  // ---------------------------------------------------------------------------
  // Harness adapters.
  // ---------------------------------------------------------------------------

  /**
   * The multiset comparison: payload order is not pinned, and a folded list's own element order is
   * canonicalised away with it. For every shape with no {@code order().by(...)} ahead of the terminator,
   * where MATCH's planner is free to choose an order native has no reason to match.
   */
  private void assertMultiset(
      String scenario, Recognition expected, Supplier<GraphTraversal<?, ?>> traversalSupplier) {
    support.assertEquivalent(
        scenario,
        expected,
        Cardinality.NON_EMPTY,
        ListShapingTerminatorEquivalenceTest::shapedAsMultiset,
        traversalSupplier);
  }

  /**
   * The element-for-element comparison, for shapes whose input is ordered by an {@code order().by(...)}
   * the translator compiles into an {@code ORDER BY}. Positional terminators need it: a window compared
   * as a multiset passes while keeping the wrong rows in the right quantity.
   */
  private void assertOrdered(
      String scenario, Recognition expected, Supplier<GraphTraversal<?, ?>> traversalSupplier) {
    support.assertEquivalent(
        scenario,
        expected,
        Cardinality.NON_EMPTY,
        ListShapingTerminatorEquivalenceTest::shapedInArrivalOrder,
        traversalSupplier);
  }

  /** Rows rendered with both the payload stream and any folded list's contents sorted. */
  private static List<String> shapedAsMultiset(List<?> results) {
    return results.stream().map(payload -> render(payload, true)).sorted().toList();
  }

  /** Rows rendered in arrival order, with any folded list's contents left in theirs. */
  private static List<String> shapedInArrivalOrder(List<?> results) {
    return results.stream().map(payload -> render(payload, false)).toList();
  }

  /**
   * One payload as a comparable string. Elements render as their RID so two runs of the same shape
   * compare by identity rather than by whatever {@code toString} a wrapper carries; a list payload — a
   * drain's output, or a reversed collection — renders through its own elements, optionally sorted, so
   * the caller decides whether the list's order is part of the comparison; everything else goes through
   * {@code String.valueOf}.
   */
  private static String render(Object payload, boolean sortListContents) {
    if (payload instanceof Vertex vertex) {
      return vertex.id().toString();
    }
    if (payload instanceof List<?> list) {
      var rendered = list.stream().map(element -> render(element, sortListContents));
      return (sortListContents ? rendered.sorted() : rendered).toList().toString();
    }
    return String.valueOf(payload);
  }

  /**
   * The session whose configuration carries the kill-switch: the one the graph's own traversals read,
   * resolved out of {@code graph.tx()} rather than the base class's handle.
   */
  private DatabaseSessionEmbedded graphSession() {
    var tx = (YTDBTransaction) graph.tx();
    tx.readWrite();
    return tx.getDatabaseSession();
  }
}
