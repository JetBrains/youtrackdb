package com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy.optimization;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.traversal.lambda.RecordIdSortKeyTraversal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.apache.tinkerpop.gremlin.process.traversal.Order;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.Scope;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.process.traversal.lambda.ColumnTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.lambda.IdentityTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.lambda.ValueTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.step.TraversalParent;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.OrderGlobalStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.OrderLocalStep;
import org.apache.tinkerpop.gremlin.structure.Column;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.javatuples.Pair;
import org.junit.Test;

/**
 * {@link YTDBOrderRidTieBreakStrategy} appends a stream-typed secondary key: the record identifier
 * sort key on elements and on element group keys, {@code Column.keys} on scalar group entries,
 * identity on a global stream of anything else, and nothing at all on a local order whose member
 * type is unproven.
 */
public class YTDBOrderRidTieBreakStrategyTest extends GraphBaseTest {

  /** Element property sorts gain a trailing record identifier key, ascending. */
  @Test
  public void apply_appendsRecordIdKeyAfterPropertySort() {
    var admin = graph.traversal().V().order().by("name").asAdmin();
    YTDBOrderRidTieBreakStrategy.instance().apply(admin);

    var orderStep = orderStep(admin);
    assertThat(comparators(orderStep)).hasSize(2);
    var tieBreak = comparators(orderStep).get(1);
    assertRecordIdKey(tieBreak.getValue0());
    assertThat(tieBreak.getValue1()).isEqualTo(Order.asc);
  }

  /**
   * Scenario: a descending element property sort. Expected: the appended record identifier key is
   * descending too, mirroring the item whose ties it breaks. Both arms receive the same appended
   * comparator, so the sequence does not depend on the direction — a descending index scan hands
   * back its equal keys in descending identifier order, and only a descending appended item lets
   * the planner stream that scan rather than buffer it.
   */
  @Test
  public void apply_mirrorsDescendingDirectionOntoTheRecordIdKey() {
    var admin = graph.traversal().V().order().by("name", Order.desc).asAdmin();
    YTDBOrderRidTieBreakStrategy.instance().apply(admin);

    var comparators = comparators(orderStep(admin));
    assertThat(comparators).hasSize(2);
    assertThat(comparators.get(0).getValue1()).isEqualTo(Order.desc);
    assertRecordIdKey(comparators.get(1).getValue0());
    assertThat(comparators.get(1).getValue1())
        .as("the appended key must mirror the direction of the item it breaks the ties of")
        .isEqualTo(Order.desc);
  }

  /**
   * Scenario: a descending local order over folded elements. Expected: the appended key mirrors that
   * direction as well, so the local shape follows the same rule as the global one.
   */
  @Test
  public void apply_mirrorsDescendingDirectionOnLocalOrder() {
    var admin =
        graph.traversal().V().fold().order(Scope.local).by("name", Order.desc).asAdmin();
    YTDBOrderRidTieBreakStrategy.instance().apply(admin);

    var localComparators = localComparators(localOrderStep(admin));
    assertThat(localComparators).hasSize(2);
    assertRecordIdKey(localComparators.get(1).getValue0());
    assertThat(localComparators.get(1).getValue1()).isEqualTo(Order.desc);
  }

  /**
   * Bare {@code order()} over elements stores one synthetic identity slot. Identity over elements is
   * element orderability, which is the record identifier order by class name rather than by number,
   * so the slot is replaced instead of followed.
   */
  @Test
  public void apply_replacesBareOrderIdentityWithRecordIdKey() {
    var admin = graph.traversal().V().order().asAdmin();
    YTDBOrderRidTieBreakStrategy.instance().apply(admin);

    var comparators = comparators(orderStep(admin));
    assertThat(comparators).hasSize(1);
    assertRecordIdKey(comparators.get(0).getValue0());
  }

  /**
   * A user-written {@code by(T.id)} over elements is replaced by the record identifier key, and the
   * slot count stays at one. The token sorts two identifier classes by class name; the key sorts
   * them numerically, which is what the translated arm does.
   */
  @Test
  public void apply_replacesExplicitTokenIdSortWithRecordIdKey() {
    var admin = graph.traversal().V().order().by(T.id).asAdmin();
    YTDBOrderRidTieBreakStrategy.instance().apply(admin);

    var comparators = comparators(orderStep(admin));
    assertThat(comparators).hasSize(1);
    assertRecordIdKey(comparators.get(0).getValue0());
  }

  /**
   * A {@code by(T.id)} in a non-final position is replaced on that same slot, and no further key is
   * appended. The token decides the whole sequence from the first slot, so leaving it there kept the
   * class-name comparison of two identifier classes exactly where it mattered most. The record
   * identifier key is total over an element stream by itself, which is why the property slot behind
   * it needs no tie-break of its own.
   */
  @Test
  public void apply_replacesTokenIdSortInANonFinalSlot() {
    var admin = graph.traversal().V().order().by(T.id).by("name").asAdmin();
    YTDBOrderRidTieBreakStrategy.instance().apply(admin);

    var comparators = comparators(orderStep(admin));
    assertThat(comparators).hasSize(2);
    assertRecordIdKey(comparators.get(0).getValue0());
    // The property slot behind the token has to survive the replacement.
    assertThat(comparators.get(1).getValue0()).isInstanceOf(ValueTraversal.class);
    assertThat(((ValueTraversal) comparators.get(1).getValue0()).getPropertyKey())
        .isEqualTo("name");
  }

  /** Replacing a descending {@code by(T.id)} keeps the descending comparator on that slot. */
  @Test
  public void apply_replacingTokenIdSortKeepsItsDirection() {
    var admin = graph.traversal().V().order().by(T.id, Order.desc).asAdmin();
    YTDBOrderRidTieBreakStrategy.instance().apply(admin);

    var comparators = comparators(orderStep(admin));
    assertThat(comparators).hasSize(1);
    assertRecordIdKey(comparators.get(0).getValue0());
    assertThat(comparators.get(0).getValue1()).isEqualTo(Order.desc);
  }

  /**
   * Only the trailing slot is replaced. With a property key first and a bare {@code by(Order.asc)}
   * second, the property slot must survive, which an {@code equals}-matched replacement loses
   * because every identity traversal is equal to every other one.
   */
  @Test
  public void apply_replacesTrailingIdentityAndKeepsEarlierSlots() {
    var admin = graph.traversal().V().order().by("name", Order.desc).by(Order.asc).asAdmin();
    YTDBOrderRidTieBreakStrategy.instance().apply(admin);

    var comparators = comparators(orderStep(admin));
    assertThat(comparators).hasSize(2);
    assertThat(comparators.get(0).getValue0()).isInstanceOf(ValueTraversal.class);
    assertThat(((ValueTraversal) comparators.get(0).getValue0()).getPropertyKey())
        .isEqualTo("name");
    assertThat(comparators.get(0).getValue1()).isEqualTo(Order.desc);
    assertRecordIdKey(comparators.get(1).getValue0());
  }

  /**
   * Two identity slots over elements: both become the record identifier key, and each keeps the
   * comparator of the slot it replaced. An identity slot compares elements through TinkerPop
   * orderability wherever it sits, so leaving the earlier one in place kept the class-name
   * comparison of two identifier classes as the primary key of the sort.
   *
   * <p>The directions are what pin the positional rebuild here: a replacement that rewrote one slot
   * twice would lose the descending comparator of the first.
   */
  @Test
  public void apply_replacesBothIdentitySlotsAndKeepsTheirDirections() {
    var admin = graph.traversal().V().order().by(Order.desc).by(Order.asc).asAdmin();
    YTDBOrderRidTieBreakStrategy.instance().apply(admin);

    var comparators = comparators(orderStep(admin));
    assertThat(comparators).hasSize(2);
    assertRecordIdKey(comparators.get(0).getValue0());
    assertThat(comparators.get(0).getValue1()).isEqualTo(Order.desc);
    assertRecordIdKey(comparators.get(1).getValue0());
    assertThat(comparators.get(1).getValue1()).isEqualTo(Order.asc);
  }

  /** A step label on the replaced order step survives the rebuild. */
  @Test
  public void apply_replacementKeepsStepLabels() {
    var admin = graph.traversal().V().order().by(T.id).as("sorted").asAdmin();
    YTDBOrderRidTieBreakStrategy.instance().apply(admin);
    assertThat(orderStep(admin).getLabels()).containsExactly("sorted");
  }

  /**
   * A multi-key element sort whose last key is a property named {@code id} gains the record
   * identifier key like any other. The strategy used to skip this shape, treating the property as a
   * unique surrogate. Nothing in the engine makes it unique, so its duplicate values tie and the
   * sequence needs the appended key.
   */
  @Test
  public void apply_appendsRecordIdKeyAfterPropertyIdSort() {
    var admin = graph.traversal().V().order().by("creationDate", Order.desc).by("id", Order.asc)
        .asAdmin();
    YTDBOrderRidTieBreakStrategy.instance().apply(admin);

    var comparators = comparators(orderStep(admin));
    assertThat(comparators).hasSize(3);
    assertRecordIdKey(comparators.get(2).getValue0());
    assertThat(comparators.get(2).getValue1()).isEqualTo(Order.asc);
  }

  /**
   * A single map from {@code groupCount()} is not an entry stream — identity tie-break, not
   * {@code T.id}.
   */
  @Test
  public void apply_appendsIdentityAfterOrderOnGroupCountMap() {
    var admin = graph.traversal().V().groupCount().by("name").order().by("age").asAdmin();
    YTDBOrderRidTieBreakStrategy.instance().apply(admin);

    var comparators = comparators(orderStep(admin));
    assertThat(comparators).hasSize(2);
    assertThat(comparators.get(1).getValue0()).isInstanceOf(IdentityTraversal.class);
  }

  /**
   * List-ordering steps gain an identity tie-break so {@code by(sum(local))} ties sort by list
   * contents.
   */
  @Test
  public void apply_appendsIdentityAfterOrderOnFoldMap() {
    var admin = graph.traversal().V()
        .map(__.bothE().values("weight").fold())
        .order()
        .by(__.sum(Scope.local), Order.desc)
        .asAdmin();
    YTDBOrderRidTieBreakStrategy.instance().apply(admin);

    var comparators = comparators(orderStep(admin));
    assertThat(comparators).hasSize(2);
    assertThat(comparators.get(1).getValue0()).isInstanceOf(IdentityTraversal.class);
  }

  /** {@code group().unfold().order()} sees map entries — append {@code Column.keys}, not {@code T.id}. */
  @Test
  public void apply_appendsKeysAfterUnfoldedGroup() {
    var admin = graph.traversal().V()
        .group().by("name").by(__.outE().values("weight").sum())
        .unfold()
        .order()
        .by(__.select(Column.values), Order.desc)
        .asAdmin();
    YTDBOrderRidTieBreakStrategy.instance().apply(admin);

    var comparators = comparators(orderStep(admin));
    assertThat(comparators).hasSize(3);
    assertThat(comparators.get(1).getValue0()).isInstanceOf(ColumnTraversal.class);
    assertThat(((ColumnTraversal) comparators.get(1).getValue0()).getColumn())
        .isEqualTo(Column.keys);
    assertThat(comparators.get(2).getValue0()).isInstanceOf(IdentityTraversal.class);
  }

  /**
   * Default {@code group().unfold().order()} with element keys gains the record identifier key, not
   * raw {@code Column.keys}, because a vertex is not {@code Comparable}.
   */
  @Test
  public void apply_appendsRecordIdKeyAfterUnfoldedDefaultGroup() {
    var admin = graph.traversal().V()
        .group().by().by(__.count())
        .unfold()
        .order()
        .by(__.select(Column.values), Order.desc)
        .asAdmin();
    YTDBOrderRidTieBreakStrategy.instance().apply(admin);

    var comparators = comparators(orderStep(admin));
    assertThat(comparators).hasSize(3);
    assertRecordIdKey(comparators.get(1).getValue0());
    assertThat(comparators.get(2).getValue0()).isInstanceOf(IdentityTraversal.class);
  }

  /** Explicit {@code by(Column.keys)} already ties on the entry key — no duplicate keys modulator. */
  @Test
  public void apply_leavesExplicitKeysSortUntouched() {
    var admin = graph.traversal().V()
        .groupCount().by("name")
        .unfold()
        .order()
        .by(Column.keys, Order.asc)
        .asAdmin();
    YTDBOrderRidTieBreakStrategy.instance().apply(admin);
    assertThat(comparators(orderStep(admin))).hasSize(1);
  }

  /** {@code by(__.select(Column.keys))} is the same tie-break spelling — no duplicate append. */
  @Test
  public void apply_leavesSelectColumnKeysSortUntouched() {
    var admin = graph.traversal().V()
        .groupCount().by("name")
        .unfold()
        .order()
        .by(__.select(Column.keys), Order.asc)
        .asAdmin();
    YTDBOrderRidTieBreakStrategy.instance().apply(admin);
    assertThat(comparators(orderStep(admin))).hasSize(1);
  }

  /** {@code fold().unfold()} restores the element stream — append the key, not identity. */
  @Test
  public void apply_appendsRecordIdKeyAfterFoldUnfold() {
    var admin = graph.traversal().V().fold().unfold().order().by("name").asAdmin();
    YTDBOrderRidTieBreakStrategy.instance().apply(admin);

    assertRecordIdKey(comparators(orderStep(admin)).get(1).getValue0());
  }

  /** {@code project().order()} orders maps — identity tie-break, never {@code T.id}. */
  @Test
  public void apply_appendsIdentityAfterProjectOrder() {
    var admin = graph.traversal().V()
        .project("n", "a")
        .by("name")
        .by("age")
        .order()
        .by(__.select("a"), Order.desc)
        .asAdmin();
    YTDBOrderRidTieBreakStrategy.instance().apply(admin);

    var comparators = comparators(orderStep(admin));
    assertThat(comparators).hasSize(2);
    assertThat(comparators.get(1).getValue0()).isInstanceOf(IdentityTraversal.class);
  }

  /** Local {@code order(local)} over folded vertices gains the record identifier key. */
  @Test
  public void apply_appendsRecordIdKeyToOrderLocalOnFoldedElements() {
    var admin = graph.traversal().V().fold().order(Scope.local).by("name").asAdmin();
    YTDBOrderRidTieBreakStrategy.instance().apply(admin);

    var localComparators = localComparators(localOrderStep(admin));
    assertThat(localComparators).hasSize(2);
    assertRecordIdKey(localComparators.get(1).getValue0());
  }

  /** Local {@code order(local)} over a {@code group()} map gains {@code Column.keys}. */
  @Test
  public void apply_appendsKeysToOrderLocalOnGroupMap() {
    var admin = graph.traversal().V()
        .group().by("name").by(__.outE().values("weight").sum())
        .order(Scope.local)
        .by(Column.values)
        .asAdmin();
    YTDBOrderRidTieBreakStrategy.instance().apply(admin);

    var localComparators = localComparators(localOrderStep(admin));
    assertThat(localComparators).hasSize(2);
    assertThat(localComparators.get(1).getValue0()).isInstanceOf(ColumnTraversal.class);
    assertThat(((ColumnTraversal) localComparators.get(1).getValue0()).getColumn())
        .isEqualTo(Column.keys);
  }

  /**
   * Default {@code group()} keys are elements — local order gains the record identifier key, not
   * raw keys, because a vertex is not {@code Comparable}.
   */
  @Test
  public void apply_appendsRecordIdKeyToOrderLocalOnDefaultGroup() {
    var admin = graph.traversal().V()
        .group().by().by(__.count())
        .order(Scope.local)
        .by(Column.values)
        .asAdmin();
    YTDBOrderRidTieBreakStrategy.instance().apply(admin);

    var localComparators = localComparators(localOrderStep(admin));
    assertThat(localComparators).hasSize(2);
    assertRecordIdKey(localComparators.get(1).getValue0());
  }

  /** Bare local {@code order(local)} over folded elements becomes the key, replacing identity. */
  @Test
  public void apply_replacesBareOrderLocalIdentityWithRecordIdKeyOnFoldedElements() {
    var admin = graph.traversal().V().fold().order(Scope.local).asAdmin();
    YTDBOrderRidTieBreakStrategy.instance().apply(admin);

    var localComparators = localComparators(localOrderStep(admin));
    assertThat(localComparators).hasSize(1);
    assertRecordIdKey(localComparators.get(0).getValue0());
  }

  /**
   * Explicit {@code by(Order.asc)} on folded elements stores {@code IdentityTraversal} — replace
   * in place with the record identifier key.
   */
  @Test
  public void apply_replacesExplicitIdentityOrderLocalWithRecordIdKeyOnFoldedElements() {
    var admin = graph.traversal().V().fold().order(Scope.local).by(Order.asc).asAdmin();
    YTDBOrderRidTieBreakStrategy.instance().apply(admin);
    var localComparators = localComparators(localOrderStep(admin));
    assertThat(localComparators).hasSize(1);
    assertRecordIdKey(localComparators.get(0).getValue0());
  }

  /** {@code select} is not assumed to be an element stream — identity, not {@code T.id}. */
  @Test
  public void apply_appendsIdentityAfterSelectOrder() {
    var admin = graph.traversal().V().as("a").select("a").order().by("name").asAdmin();
    YTDBOrderRidTieBreakStrategy.instance().apply(admin);

    var comparators = comparators(orderStep(admin));
    assertThat(comparators).hasSize(2);
    assertThat(comparators.get(1).getValue0()).isInstanceOf(IdentityTraversal.class);
  }

  /** {@code Order.shuffle} must not gain a tie-break modulator. */
  @Test
  public void apply_leavesShuffleUntouched() {
    var admin = graph.traversal().V().order().by(Order.shuffle).asAdmin();
    YTDBOrderRidTieBreakStrategy.instance().apply(admin);
    assertThat(comparators(orderStep(admin))).hasSize(1);
  }

  /** Bare local {@code order(local)} over folded scalars already has identity — leave untouched. */
  @Test
  public void apply_leavesBareOrderLocalOnFoldedScalarsUntouched() {
    var admin = graph.traversal().V().values("name").fold().order(Scope.local).asAdmin();
    YTDBOrderRidTieBreakStrategy.instance().apply(admin);

    var local = admin.getSteps().stream()
        .filter(OrderLocalStep.class::isInstance)
        .map(OrderLocalStep.class::cast)
        .findFirst()
        .orElseThrow();
    assertThat(local.getComparators()).hasSize(1);
  }

  /**
   * Smoke: formerly-failing TinkerPop shapes that order maps/lists/entries must still execute after
   * strategies run.
   */
  @Test
  public void apply_nonElementOrderShapesStillExecute() {
    var a = graph.addVertex(T.label, "person", "name", "marko");
    var b = graph.addVertex(T.label, "person", "name", "josh");
    a.addEdge("knows", b, "weight", 0.5d);
    graph.tx().commit();

    assertThat(graph.traversal().V().groupCount().by("name").order().by("age").toList())
        .hasSize(1);
    assertThat(
        graph.traversal().V()
            .map(__.bothE().values("weight").fold())
            .order()
            .by(__.sum(Scope.local), Order.desc)
            .toList())
        .hasSize(2);
    assertThat(
        graph.traversal().V().hasLabel("person")
            .group().by("name").by(__.outE().values("weight").sum())
            .unfold()
            .order()
            .by(__.select(Column.values), Order.desc)
            .toList())
        .isNotNull();
    assertThat(graph.traversal().V().fold().order(Scope.local).by("name").next()).isNotNull();
    assertThat(
        graph.traversal().V().hasLabel("person")
            .group().by("name").by(__.outE().values("weight").sum())
            .order(Scope.local)
            .by(Column.values)
            .next())
        .isNotNull();
    assertThat(
        graph.traversal().V().hasLabel("person")
            .group().by().by(__.count())
            .order(Scope.local)
            .by(Column.values)
            .next())
        .isNotNull();
  }

  /**
   * Regression, folded element stream sorted locally by a property. {@code out()} is a flat-map
   * step, so before the classification fix this stream was unproven, gained a bare identity, and
   * failed the {@code Comparable} cast on a vertex. Expected: two names in ascending order inside
   * one folded list.
   */
  @Test
  public void execute_foldedHopSortedLocallyByProperty_returnsSortedMembers() {
    seedTwoKnownPeople();

    var folded = graph.traversal().V().out().fold().order(Scope.local).by("name").toList();

    assertThat(folded).as("fold() is a global barrier, so it emits one list").hasSize(1);
    assertThat(names(folded.get(0))).containsExactly("josh", "vadas");
  }

  /**
   * Regression, grouped element stream sorted locally by values. The default {@code by()} keys the
   * group on the hop's vertices, and before the fix the key was believed scalar, so a vertex met the
   * {@code Comparable} cast. Expected: one map holding both hop targets, each counted once.
   */
  @Test
  public void execute_groupedHopSortedLocallyByValues_returnsOneMapPerRow() {
    seedTwoKnownPeople();

    var maps = graph.traversal().V().out()
        .group().by().by(__.count())
        .order(Scope.local).by(Column.values)
        .toList();

    assertThat(maps).hasSize(1);
    assertThat((Map<?, ?>) maps.get(0)).hasSize(2);
  }

  /**
   * Regression, element map stream mapped through a filter and sorted by a selected key. The filter
   * child made the map stream look like an element stream, and the appended element-only modulator
   * then rejected a {@code LinkedHashMap}. Expected: both maps, ordered by the selected name.
   */
  @Test
  public void execute_elementMapThroughFilterSortedBySelectedKey_returnsSortedMaps() {
    seedTwoKnownPeople();

    var maps = graph.traversal().V()
        .elementMap()
        .map(__.filter(__.select("name")))
        .order().by(__.select("name"))
        .toList();

    assertThat(maps.stream().map(row -> String.valueOf(((Map<?, ?>) row).get("name"))).toList())
        .containsExactly("josh", "marko", "vadas");
  }

  /**
   * Regression, branching step mixing elements with a constant. {@code coalesce} exposes its
   * branches as local children, and reading the first branch alone called the whole stream elements,
   * so the constant met an element-only modulator. Expected: every row survives, two of them hop
   * targets and two the constant, because only marko has an outgoing edge.
   */
  @Test
  public void execute_coalesceMixingElementsAndConstant_returnsEveryRow() {
    seedTwoKnownPeople();

    var rows = graph.traversal().V()
        .coalesce(arm(__.out()), arm(__.constant("none")))
        .order().by(__.constant(1))
        .toList();

    assertThat(rows).hasSize(4);
    assertThat(rows.stream().filter("none"::equals).toList()).hasSize(2);
  }

  /**
   * A second application of the strategy must change nothing, including for an order step nested
   * inside a child traversal, which is where the recursive step scan reaches it a second time.
   */
  @Test
  public void apply_isIdempotentForNestedOrderStep() {
    var admin = graph.traversal().V()
        .local(__.out().order().by("name"))
        .order().by("name")
        .asAdmin();

    YTDBOrderRidTieBreakStrategy.instance().apply(admin);
    var firstOuter = renderComparators(orderStep(admin));
    var firstNested = renderComparators(nestedOrderStep(admin));

    YTDBOrderRidTieBreakStrategy.instance().apply(admin);

    assertThat(renderComparators(orderStep(admin))).isEqualTo(firstOuter);
    assertThat(renderComparators(nestedOrderStep(admin))).isEqualTo(firstNested);
    assertThat(firstNested).hasSize(2);
  }

  /**
   * Injected map entries that tie on {@code values} sort by identity — same key sequence every
   * run.
   */
  @Test
  public void apply_tiedInjectedEntries_sortDeterministicallyByIdentity() {
    var first = drainTiedInjectedEntryKeys();
    var second = drainTiedInjectedEntryKeys();
    assertThat(first).containsExactly("alice", "bob", "carol");
    assertThat(second).isEqualTo(first);
  }

  /**
   * Unfolded group entries that tie on {@code values} sort by {@code keys} — same key sequence
   * every run.
   */
  @Test
  public void apply_tiedGroupEntries_sortDeterministicallyByKeys() {
    graph.addVertex(T.label, "person", "name", "carol");
    graph.addVertex(T.label, "person", "name", "alice");
    graph.addVertex(T.label, "person", "name", "bob");
    graph.tx().commit();

    var first = drainTiedGroupEntryKeys();
    var second = drainTiedGroupEntryKeys();
    assertThat(first).containsExactly("alice", "bob", "carol");
    assertThat(second).isEqualTo(first);
  }

  /**
   * The same key may appear in entries from different maps after {@code inject(m1,m2).unfold()}.
   * Values and keys both tie — entry identity must still yield a stable sequence.
   */
  @Test
  public void apply_tiedUnfoldedMapsDuplicateKeys_sortDeterministicallyByIdentity() {
    var first = drainDuplicateKeyAcrossInjectedMaps();
    var second = drainDuplicateKeyAcrossInjectedMaps();
    assertThat(first).containsExactly("alice", "alice");
    assertThat(second).isEqualTo(first);
  }

  /**
   * Two projected group maps unfolded to entries — same key {@code alice} in both maps, values tie.
   * Uses identity (not {@code group().unfold()} MAP_ENTRY path) but exercises cross-map duplicates.
   */
  @Test
  public void apply_tiedProjectedMapsUnfoldDuplicateKeys_sortDeterministically() {
    seedKnowsGraphForLocalGroupDuplicateKeys();
    graph.tx().commit();

    var first = drainDuplicateKeyAcrossProjectedMapsUnfold();
    var second = drainDuplicateKeyAcrossProjectedMapsUnfold();
    assertThat(first).containsExactly("alice", "alice", "bob");
    assertThat(second).isEqualTo(first);
  }

  /**
   * Local order with no fold or map producer upstream cannot prove its member type, so it gains no
   * modulator at all. Appending one would meet the {@code Comparable} cast with an unknown member.
   */
  @Test
  public void apply_appendsNothingToOrderLocalWithUnprovenMembers() {
    var admin = graph.traversal().V().order(Scope.local).by("name").asAdmin();
    YTDBOrderRidTieBreakStrategy.instance().apply(admin);
    assertThat(localComparators(localOrderStep(admin))).hasSize(1);
  }

  /**
   * Local element order ending on {@code T.id} becomes the record identifier key on that same slot,
   * for the same reason the global case does.
   */
  @Test
  public void apply_replacesExplicitTokenIdOrderLocalOnFoldedElements() {
    var admin = graph.traversal().V().fold().order(Scope.local).by(T.id).asAdmin();
    YTDBOrderRidTieBreakStrategy.instance().apply(admin);
    var localComparators = localComparators(localOrderStep(admin));
    assertThat(localComparators).hasSize(1);
    assertRecordIdKey(localComparators.get(0).getValue0());
  }

  /**
   * Local element order whose last key is a property named {@code id} gains the record identifier
   * key too, because a folded element list ties on duplicate values exactly as a global stream does.
   */
  @Test
  public void apply_appendsRecordIdKeyAfterPropertyIdOrderLocalOnFoldedElements() {
    var admin = graph.traversal().V().fold().order(Scope.local).by("id").asAdmin();
    YTDBOrderRidTieBreakStrategy.instance().apply(admin);

    var localComparators = localComparators(localOrderStep(admin));
    assertThat(localComparators).hasSize(2);
    assertRecordIdKey(localComparators.get(1).getValue0());
  }

  /**
   * Bare local order over a group map replaces synthetic identity with {@code Column.keys}.
   */
  @Test
  public void apply_replacesBareOrderLocalIdentityWithKeysOnGroupMap() {
    var admin = graph.traversal().V().group().by("name").order(Scope.local).asAdmin();
    YTDBOrderRidTieBreakStrategy.instance().apply(admin);
    var localComparators = localComparators(localOrderStep(admin));
    assertThat(localComparators).hasSize(1);
    assertThat(localComparators.get(0).getValue0()).isInstanceOf(ColumnTraversal.class);
    assertThat(((ColumnTraversal) localComparators.get(0).getValue0()).getColumn())
        .isEqualTo(Column.keys);
  }

  /**
   * Explicit {@code by(Order.asc)} stores {@code IdentityTraversal} as a local child — replace in
   * place with {@code Column.keys} ({@code replaceLocalChild} path).
   */
  @Test
  public void apply_replacesExplicitIdentityOrderLocalWithKeysOnGroupMap() {
    var admin = graph.traversal().V()
        .group().by("name")
        .order(Scope.local)
        .by(Order.asc)
        .asAdmin();
    YTDBOrderRidTieBreakStrategy.instance().apply(admin);
    var localComparators = localComparators(localOrderStep(admin));
    assertThat(localComparators).hasSize(1);
    assertThat(localComparators.get(0).getValue0()).isInstanceOf(ColumnTraversal.class);
  }

  /** Local order over {@code project()} maps gains {@code Column.keys}, not element id. */
  @Test
  public void apply_appendsKeysToOrderLocalOnProjectMap() {
    var admin = graph.traversal().V()
        .project("n")
        .by("name")
        .order(Scope.local)
        .by(__.select("n"))
        .asAdmin();
    YTDBOrderRidTieBreakStrategy.instance().apply(admin);
    var localComparators = localComparators(localOrderStep(admin));
    assertThat(localComparators).hasSize(2);
    assertThat(localComparators.get(1).getValue0()).isInstanceOf(ColumnTraversal.class);
  }

  /** Local order over {@code valueMap()} gains {@code Column.keys}. */
  @Test
  public void apply_appendsKeysToOrderLocalOnValueMap() {
    var admin = graph.traversal().V()
        .valueMap()
        .order(Scope.local)
        .by(Column.values)
        .asAdmin();
    YTDBOrderRidTieBreakStrategy.instance().apply(admin);
    var localComparators = localComparators(localOrderStep(admin));
    assertThat(localComparators).hasSize(2);
    assertThat(localComparators.get(1).getValue0()).isInstanceOf(ColumnTraversal.class);
  }

  /** Local order over {@code elementMap()} gains {@code Column.keys}. */
  @Test
  public void apply_appendsKeysToOrderLocalOnElementMap() {
    var admin = graph.traversal().V()
        .elementMap()
        .order(Scope.local)
        .by(Column.values)
        .asAdmin();
    YTDBOrderRidTieBreakStrategy.instance().apply(admin);
    var localComparators = localComparators(localOrderStep(admin));
    assertThat(localComparators).hasSize(2);
    assertThat(localComparators.get(1).getValue0()).isInstanceOf(ColumnTraversal.class);
  }

  /** Transparent steps between group and local order still see map entries. */
  @Test
  public void apply_appendsKeysToOrderLocalAfterTransparentStepsOnGroupMap() {
    var admin = graph.traversal().V()
        .group().by("name")
        .identity()
        .order(Scope.local)
        .by(Column.values)
        .asAdmin();
    YTDBOrderRidTieBreakStrategy.instance().apply(admin);
    var localComparators = localComparators(localOrderStep(admin));
    assertThat(localComparators).hasSize(2);
    assertThat(localComparators.get(1).getValue0()).isInstanceOf(ColumnTraversal.class);
  }

  /**
   * Folding unfolded group entries then ordering locally still classifies as map-entry members —
   * key modulator falls back to {@code Column.keys} when the immediate predecessor is Fold.
   */
  @Test
  public void apply_appendsKeysToOrderLocalOnFoldedUnfoldedGroupEntries() {
    var admin = graph.traversal().V()
        .group().by("name")
        .unfold()
        .fold()
        .order(Scope.local)
        .by(__.select(Column.values))
        .asAdmin();
    YTDBOrderRidTieBreakStrategy.instance().apply(admin);
    var localComparators = localComparators(localOrderStep(admin));
    assertThat(localComparators).hasSize(2);
    assertThat(localComparators.get(1).getValue0()).isInstanceOf(ColumnTraversal.class);
  }

  /** Transparent steps between unfold and global order still append keys + identity. */
  @Test
  public void apply_appendsKeysAfterUnfoldedGroupWithTransparentSteps() {
    var admin = graph.traversal().V()
        .group().by("name")
        .unfold()
        .identity()
        .order()
        .by(__.select(Column.values), Order.desc)
        .asAdmin();
    YTDBOrderRidTieBreakStrategy.instance().apply(admin);
    assertThat(comparators(orderStep(admin))).hasSize(3);
    assertThat(comparators(orderStep(admin)).get(1).getValue0())
        .isInstanceOf(ColumnTraversal.class);
  }

  /**
   * Default {@code groupCount().unfold().order()} keys are elements — the record identifier key plus
   * identity.
   */
  @Test
  public void apply_appendsRecordIdKeyAfterUnfoldedDefaultGroupCount() {
    var admin = graph.traversal().V()
        .groupCount()
        .unfold()
        .order()
        .by(__.select(Column.values), Order.desc)
        .asAdmin();
    YTDBOrderRidTieBreakStrategy.instance().apply(admin);
    var comparators = comparators(orderStep(admin));
    assertThat(comparators).hasSize(3);
    assertRecordIdKey(comparators.get(1).getValue0());
  }

  /**
   * Explicit {@code group().by(identity)} still projects element keys — same record identifier key
   * modulator as default {@code by()}.
   */
  @Test
  public void apply_appendsRecordIdKeyAfterUnfoldedGroupByIdentity() {
    var admin = graph.traversal().V()
        .group().by(__.identity()).by(__.count())
        .unfold()
        .order()
        .by(__.select(Column.values), Order.desc)
        .asAdmin();
    YTDBOrderRidTieBreakStrategy.instance().apply(admin);
    var comparators = comparators(orderStep(admin));
    assertThat(comparators).hasSize(3);
    assertRecordIdKey(comparators.get(1).getValue0());
  }

  /**
   * Group key traversal that emits elements ({@code outE}) needs the record identifier key, not raw
   * keys.
   */
  @Test
  public void apply_appendsRecordIdKeyAfterUnfoldedGroupByOutEdges() {
    var admin = graph.traversal().V()
        .group().by(__.outE()).by(__.count())
        .unfold()
        .order()
        .by(__.select(Column.values), Order.desc)
        .asAdmin();
    YTDBOrderRidTieBreakStrategy.instance().apply(admin);
    var comparators = comparators(orderStep(admin));
    assertThat(comparators).hasSize(3);
    assertRecordIdKey(comparators.get(1).getValue0());
  }

  /** {@code index().unfold()} is not a group-entry stream — identity tie-break. */
  @Test
  public void apply_appendsIdentityAfterIndexUnfoldOrder() {
    var admin = graph.traversal().V()
        .index()
        .unfold()
        .order()
        .by(__.select(Column.values), Order.asc)
        .asAdmin();
    YTDBOrderRidTieBreakStrategy.instance().apply(admin);
    var comparators = comparators(orderStep(admin));
    assertThat(comparators).hasSize(2);
    assertThat(comparators.get(1).getValue0()).isInstanceOf(IdentityTraversal.class);
  }

  /** Local shuffle must not gain a tie-break modulator. */
  @Test
  public void apply_leavesLocalShuffleUntouched() {
    var admin = graph.traversal().V().fold().order(Scope.local).by(Order.shuffle).asAdmin();
    YTDBOrderRidTieBreakStrategy.instance().apply(admin);
    assertThat(localComparators(localOrderStep(admin))).hasSize(1);
  }

  /**
   * Explicit {@code by(select(keys).id())} already ties on the entry key — leave untouched.
   */
  @Test
  public void apply_leavesSelectKeysIdSortUntouched() {
    var admin = graph.traversal().V()
        .group().by().by(__.count())
        .unfold()
        .order()
        .by(__.select(Column.keys).id(), Order.asc)
        .asAdmin();
    YTDBOrderRidTieBreakStrategy.instance().apply(admin);
    assertThat(comparators(orderStep(admin))).hasSize(1);
  }

  /**
   * Local map order whose last key is a property named {@code id} gains the entry key modulator, for
   * the same reason: the group key is what separates two entries carrying one duplicate value.
   */
  @Test
  public void apply_appendsKeysAfterPropertyIdOrderLocalOnGroupMap() {
    var admin = graph.traversal().V()
        .group().by("name")
        .order(Scope.local)
        .by("id")
        .asAdmin();
    YTDBOrderRidTieBreakStrategy.instance().apply(admin);

    var localComparators = localComparators(localOrderStep(admin));
    assertThat(localComparators).hasSize(2);
    assertThat(localComparators.get(1).getValue0()).isInstanceOf(ColumnTraversal.class);
    assertThat(((ColumnTraversal) localComparators.get(1).getValue0()).getColumn())
        .isEqualTo(Column.keys);
  }

  /**
   * {@code map} whose child emits elements recovers the element stream — append the key.
   */
  @Test
  public void apply_appendsRecordIdKeyAfterMapEmittingElements() {
    var admin = graph.traversal().V()
        .map(__.identity())
        .order()
        .by("name")
        .asAdmin();
    YTDBOrderRidTieBreakStrategy.instance().apply(admin);
    assertRecordIdKey(comparators(orderStep(admin)).get(1).getValue0());
  }

  /**
   * {@code flatMap} ending in a filter still projects elements — append the key. The filter is
   * transparent, so the child's own {@code out()} decides.
   */
  @Test
  public void apply_appendsRecordIdKeyAfterFlatMapEndingInFilter() {
    var admin = graph.traversal().V()
        .flatMap(__.out().hasLabel("person"))
        .order()
        .by("name")
        .asAdmin();
    YTDBOrderRidTieBreakStrategy.instance().apply(admin);
    assertRecordIdKey(comparators(orderStep(admin)).get(1).getValue0());
  }

  /**
   * A {@code map} child that is only a filter re-emits the parent's input, so a map stream stays a
   * map stream. Reading the child's last step class alone called this an element stream and then
   * met a {@code LinkedHashMap} with an element-only modulator.
   */
  @Test
  public void apply_appendsIdentityAfterMapFilterOverMapStream() {
    var admin = graph.traversal().V()
        .elementMap()
        .map(__.filter(__.select("name")))
        .order()
        .by(__.select("name"))
        .asAdmin();
    YTDBOrderRidTieBreakStrategy.instance().apply(admin);
    assertThat(comparators(orderStep(admin)).get(1).getValue0())
        .isInstanceOf(IdentityTraversal.class);
  }

  /**
   * A branching step whose branches disagree is not an element stream. {@code coalesce} keeps its
   * branches as local children, and the first one alone said element while the second emits a
   * constant.
   */
  @Test
  public void apply_appendsIdentityAfterCoalesceMixingElementsAndConstant() {
    var admin = graph.traversal().V()
        .coalesce(arm(__.out()), arm(__.constant("none")))
        .order()
        .by(__.constant(1))
        .asAdmin();
    YTDBOrderRidTieBreakStrategy.instance().apply(admin);
    assertThat(comparators(orderStep(admin)).get(1).getValue0())
        .isInstanceOf(IdentityTraversal.class);
  }

  /** Transparent filter/barrier/dedup/range do not hide an element stream. */
  @Test
  public void apply_appendsRecordIdKeyAfterTransparentStepsOnElements() {
    var admin = graph.traversal().V()
        .identity()
        .dedup()
        .range(0, 100)
        .order()
        .by("name")
        .asAdmin();
    YTDBOrderRidTieBreakStrategy.instance().apply(admin);
    assertRecordIdKey(comparators(orderStep(admin)).get(1).getValue0());
  }

  /**
   * {@code outE()} is an edge stream. {@code VertexStep} extends {@code FlatMapStep}, so the map
   * test running first hid every edge and vertex hop behind the unproven case.
   */
  @Test
  public void apply_appendsRecordIdKeyAfterOutEdgesOrder() {
    var admin = graph.traversal().V().outE().order().by("weight").asAdmin();
    YTDBOrderRidTieBreakStrategy.instance().apply(admin);
    assertRecordIdKey(comparators(orderStep(admin)).get(1).getValue0());
  }

  /** {@code otherV()} is a vertex stream, for the same reason {@code outE()} is an edge stream. */
  @Test
  public void apply_appendsRecordIdKeyAfterOtherVOrder() {
    var admin = graph.traversal().V().outE().otherV().order().by("name").asAdmin();
    YTDBOrderRidTieBreakStrategy.instance().apply(admin);
    assertRecordIdKey(comparators(orderStep(admin)).get(1).getValue0());
  }

  /**
   * Steps outside the element / non-element catalogues fall through to OTHER — identity tie-break
   * (covers the final {@code classifyFrom} fallback).
   */
  @Test
  public void apply_appendsIdentityAfterUnionOrder() {
    var admin = graph.traversal().V()
        .union(__.identity())
        .order()
        .by("name")
        .asAdmin();
    YTDBOrderRidTieBreakStrategy.instance().apply(admin);
    assertThat(comparators(orderStep(admin)).get(1).getValue0())
        .isInstanceOf(IdentityTraversal.class);
  }

  /** Local order over {@code groupCount()} map gains {@code Column.keys}. */
  @Test
  public void apply_appendsKeysToOrderLocalOnGroupCountMap() {
    var admin = graph.traversal().V()
        .groupCount().by("name")
        .order(Scope.local)
        .by(Column.values)
        .asAdmin();
    YTDBOrderRidTieBreakStrategy.instance().apply(admin);
    var localComparators = localComparators(localOrderStep(admin));
    assertThat(localComparators).hasSize(2);
    assertThat(localComparators.get(1).getValue0()).isInstanceOf(ColumnTraversal.class);
  }

  /**
   * Bare local order on a non-fold/non-map stream already ends with identity — OTHER path leaves
   * it untouched.
   */
  @Test
  public void apply_leavesBareOrderLocalOnElementStreamUntouched() {
    var admin = graph.traversal().V().order(Scope.local).asAdmin();
    YTDBOrderRidTieBreakStrategy.instance().apply(admin);
    assertThat(localComparators(localOrderStep(admin))).hasSize(1);
  }

  /** Three people, marko knowing both of the others, so {@code out()} yields two vertices. */
  private void seedTwoKnownPeople() {
    var marko = graph.addVertex(T.label, "person", "name", "marko");
    var josh = graph.addVertex(T.label, "person", "name", "josh");
    var vadas = graph.addVertex(T.label, "person", "name", "vadas");
    marko.addEdge("knows", josh);
    marko.addEdge("knows", vadas);
    graph.tx().commit();
  }

  private static List<String> names(Object foldedRow) {
    return ((List<?>) foldedRow).stream()
        .map(member -> ((Vertex) member).<String>value("name"))
        .toList();
  }

  /** Asserts that {@code modulator} is the appended record identifier sort key. */
  private static void assertRecordIdKey(Object modulator) {
    assertThat(modulator).isInstanceOf(RecordIdSortKeyTraversal.class);
  }

  /** Comparator slots as text, so two applications of the strategy can be compared as lists. */
  @SuppressWarnings("rawtypes")
  private static List<String> renderComparators(OrderGlobalStep step) {
    return comparators(step).stream()
        .map(pair -> pair.getValue0().getClass().getSimpleName() + "/" + pair.getValue1())
        .toList();
  }

  /** The single {@code order()} step inside the first child traversal of {@code admin}. */
  private static OrderGlobalStep<?, ?> nestedOrderStep(Traversal.Admin<?, ?> admin) {
    return admin.getSteps().stream()
        .filter(TraversalParent.class::isInstance)
        .map(TraversalParent.class::cast)
        .flatMap(parent -> parent.getLocalChildren().stream())
        .flatMap(child -> child.getSteps().stream())
        .filter(OrderGlobalStep.class::isInstance)
        .map(step -> (OrderGlobalStep<?, ?>) step)
        .findFirst()
        .orElseThrow();
  }

  /** Widens a branch traversal so mixed-type {@code coalesce} arms compile. */
  @SuppressWarnings("unchecked")
  private static Traversal<?, Object> arm(Traversal<?, ?> traversal) {
    return (Traversal<?, Object>) traversal;
  }

  private void seedKnowsGraphForLocalGroupDuplicateKeys() {
    var marko = graph.addVertex(T.label, "person", "name", "marko");
    var josh = graph.addVertex(T.label, "person", "name", "josh");
    var aliceOne = graph.addVertex(T.label, "person", "name", "alice");
    var aliceTwo = graph.addVertex(T.label, "person", "name", "alice");
    var bob = graph.addVertex(T.label, "person", "name", "bob");
    marko.addEdge("knows", aliceOne);
    marko.addEdge("knows", bob);
    josh.addEdge("knows", aliceTwo);
  }

  @SuppressWarnings("unchecked")
  private List<String> drainTiedInjectedEntryKeys() {
    var entries = (List<Map.Entry<String, Integer>>) (List<?>) graph.traversal()
        .inject(Map.entry("carol", 1), Map.entry("alice", 1), Map.entry("bob", 1))
        .order()
        .by(__.select(Column.values), Order.asc)
        .toList();
    return entries.stream().map(Map.Entry::getKey).toList();
  }

  @SuppressWarnings("unchecked")
  private List<String> drainTiedGroupEntryKeys() {
    var entries = (List<Map.Entry<String, Integer>>) (List<?>) graph.traversal().V()
        .hasLabel("person")
        .group().by("name").by(__.constant(1))
        .unfold()
        .order()
        .by(__.select(Column.values), Order.asc)
        .toList();
    return entries.stream().map(Map.Entry::getKey).toList();
  }

  @SuppressWarnings("unchecked")
  private List<String> drainDuplicateKeyAcrossInjectedMaps() {
    var entries = (List<Map.Entry<String, Integer>>) (List<?>) graph.traversal()
        .inject(Map.of("alice", 1), Map.of("alice", 1))
        .unfold()
        .order()
        .by(__.select(Column.values), Order.asc)
        .toList();
    return entries.stream().map(Map.Entry::getKey).toList();
  }

  @SuppressWarnings("unchecked")
  private List<String> drainDuplicateKeyAcrossProjectedMapsUnfold() {
    var entries = (List<Map.Entry<String, Integer>>) (List<?>) graph.traversal().V()
        .has("name", P.within("marko", "josh"))
        .project("map")
        .by(__.out("knows").group().by("name").by(__.constant(1)))
        .select("map")
        .unfold()
        .unfold()
        .order()
        .by(__.select(Column.values), Order.asc)
        .toList();
    return entries.stream().map(Map.Entry::getKey).toList();
  }

  private static OrderGlobalStep<?, ?> orderStep(Traversal.Admin<?, ?> admin) {
    return admin.getSteps().stream()
        .filter(OrderGlobalStep.class::isInstance)
        .map(OrderGlobalStep.class::cast)
        .findFirst()
        .orElseThrow();
  }

  private static OrderLocalStep<?, ?> localOrderStep(Traversal.Admin<?, ?> admin) {
    return admin.getSteps().stream()
        .filter(OrderLocalStep.class::isInstance)
        .map(OrderLocalStep.class::cast)
        .findFirst()
        .orElseThrow();
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static List<Pair<Traversal.Admin, Comparator>> comparators(OrderGlobalStep step) {
    return step.getComparators();
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static List<Pair<Traversal.Admin, Comparator>> localComparators(
      OrderLocalStep<?, ?> step) {
    return (List) step.getComparators();
  }
}
