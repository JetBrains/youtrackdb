package com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy.optimization;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.apache.tinkerpop.gremlin.process.traversal.Order;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.Scope;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.process.traversal.lambda.ColumnTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.lambda.IdentityTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.lambda.TokenTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.OrderGlobalStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.OrderLocalStep;
import org.apache.tinkerpop.gremlin.structure.Column;
import org.apache.tinkerpop.gremlin.structure.T;
import org.javatuples.Pair;
import org.junit.Test;

/**
 * {@link YTDBOrderRidTieBreakStrategy} appends a stream-typed secondary key: {@code T.id} on
 * elements, {@code Column.keys} or {@code select(keys).id()} on group entries, identity elsewhere.
 */
public class YTDBOrderRidTieBreakStrategyTest extends GraphBaseTest {

  /** Element property sorts gain a trailing {@code T.id ASC} modulator. */
  @Test
  public void apply_appendsIdComparatorAfterPropertySort() {
    var admin = graph.traversal().V().order().by("name").asAdmin();
    YTDBOrderRidTieBreakStrategy.instance().apply(admin);

    var orderStep = orderStep(admin);
    assertThat(comparators(orderStep)).hasSize(2);
    var tieBreak = comparators(orderStep).get(1);
    assertThat(tieBreak.getValue0()).isInstanceOf(TokenTraversal.class);
    assertThat(((TokenTraversal) tieBreak.getValue0()).getToken()).isEqualTo(T.id);
    assertThat(tieBreak.getValue1()).isEqualTo(Order.asc);
  }

  /** Bare {@code order()} keeps its default identity comparator and gains no duplicate. */
  @Test
  public void apply_leavesBareOrderUntouched() {
    var admin = graph.traversal().V().order().asAdmin();
    YTDBOrderRidTieBreakStrategy.instance().apply(admin);
    assertThat(comparators(orderStep(admin))).hasSize(1);
  }

  /** {@code order().by(T.id)} must not gain a duplicate id modulator. */
  @Test
  public void apply_leavesExplicitIdSortUntouched() {
    var admin = graph.traversal().V().order().by(T.id).asAdmin();
    YTDBOrderRidTieBreakStrategy.instance().apply(admin);
    assertThat(comparators(orderStep(admin))).hasSize(1);
  }

  /** Multi-key sort ending on property {@code id} must not gain a {@code T.id} modulator. */
  @Test
  public void apply_leavesExplicitIdPropertySortUntouched() {
    var admin = graph.traversal().V().order().by("creationDate", Order.desc).by("id", Order.asc)
        .asAdmin();
    YTDBOrderRidTieBreakStrategy.instance().apply(admin);
    assertThat(comparators(orderStep(admin))).hasSize(2);
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
   * Default {@code group().unfold().order()} with element keys gains {@code select(keys).id()},
   * not raw {@code Column.keys} (Vertex is not {@code Comparable}).
   */
  @Test
  public void apply_appendsSelectKeysIdAfterUnfoldedDefaultGroup() {
    var admin = graph.traversal().V()
        .group().by().by(__.count())
        .unfold()
        .order()
        .by(__.select(Column.values), Order.desc)
        .asAdmin();
    YTDBOrderRidTieBreakStrategy.instance().apply(admin);

    var comparators = comparators(orderStep(admin));
    assertThat(comparators).hasSize(3);
    var tieBreak = comparators.get(1).getValue0();
    assertThat(tieBreak).isNotInstanceOf(ColumnTraversal.class);
    assertThat(tieBreak.getEndStep()).isInstanceOf(
        org.apache.tinkerpop.gremlin.process.traversal.step.map.IdStep.class);
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

  /** {@code fold().unfold()} restores the element stream — append {@code T.id}, not identity. */
  @Test
  public void apply_appendsIdAfterFoldUnfold() {
    var admin = graph.traversal().V().fold().unfold().order().by("name").asAdmin();
    YTDBOrderRidTieBreakStrategy.instance().apply(admin);

    var tieBreak = comparators(orderStep(admin)).get(1);
    assertThat(tieBreak.getValue0()).isInstanceOf(TokenTraversal.class);
    assertThat(((TokenTraversal) tieBreak.getValue0()).getToken()).isEqualTo(T.id);
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

  /** Local {@code order(local)} over folded vertices gains {@code T.id} (not identity). */
  @Test
  public void apply_appendsIdToOrderLocalOnFoldedElements() {
    var admin = graph.traversal().V().fold().order(Scope.local).by("name").asAdmin();
    YTDBOrderRidTieBreakStrategy.instance().apply(admin);

    var local = admin.getSteps().stream()
        .filter(OrderLocalStep.class::isInstance)
        .map(OrderLocalStep.class::cast)
        .findFirst()
        .orElseThrow();
    @SuppressWarnings({"unchecked", "rawtypes"})
    List<Pair<org.apache.tinkerpop.gremlin.process.traversal.Traversal.Admin,
        Comparator>> localComparators = local.getComparators();
    assertThat(localComparators).hasSize(2);
    assertThat(localComparators.get(1).getValue0()).isInstanceOf(TokenTraversal.class);
    assertThat(((TokenTraversal) localComparators.get(1).getValue0()).getToken()).isEqualTo(T.id);
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

    var local = admin.getSteps().stream()
        .filter(OrderLocalStep.class::isInstance)
        .map(OrderLocalStep.class::cast)
        .findFirst()
        .orElseThrow();
    @SuppressWarnings({"unchecked", "rawtypes"})
    List<Pair<org.apache.tinkerpop.gremlin.process.traversal.Traversal.Admin,
        Comparator>> localComparators = local.getComparators();
    assertThat(localComparators).hasSize(2);
    assertThat(localComparators.get(1).getValue0()).isInstanceOf(ColumnTraversal.class);
    assertThat(((ColumnTraversal) localComparators.get(1).getValue0()).getColumn())
        .isEqualTo(Column.keys);
  }

  /**
   * Default {@code group()} keys are elements — local order gains {@code select(keys).id()}, not
   * raw keys (Vertex is not {@code Comparable}).
   */
  @Test
  public void apply_appendsSelectKeysIdToOrderLocalOnDefaultGroup() {
    var admin = graph.traversal().V()
        .group().by().by(__.count())
        .order(Scope.local)
        .by(Column.values)
        .asAdmin();
    YTDBOrderRidTieBreakStrategy.instance().apply(admin);

    var local = admin.getSteps().stream()
        .filter(OrderLocalStep.class::isInstance)
        .map(OrderLocalStep.class::cast)
        .findFirst()
        .orElseThrow();
    @SuppressWarnings({"unchecked", "rawtypes"})
    List<Pair<org.apache.tinkerpop.gremlin.process.traversal.Traversal.Admin,
        Comparator>> localComparators = local.getComparators();
    assertThat(localComparators).hasSize(2);
    var tieBreak = localComparators.get(1).getValue0();
    assertThat(tieBreak).isNotInstanceOf(ColumnTraversal.class);
    assertThat(tieBreak.getEndStep()).isInstanceOf(
        org.apache.tinkerpop.gremlin.process.traversal.step.map.IdStep.class);
  }

  /** Bare local {@code order(local)} over folded elements becomes {@code T.id} (replaces identity). */
  @Test
  public void apply_replacesBareOrderLocalIdentityWithIdOnFoldedElements() {
    var admin = graph.traversal().V().fold().order(Scope.local).asAdmin();
    YTDBOrderRidTieBreakStrategy.instance().apply(admin);

    var local = admin.getSteps().stream()
        .filter(OrderLocalStep.class::isInstance)
        .map(OrderLocalStep.class::cast)
        .findFirst()
        .orElseThrow();
    @SuppressWarnings({"unchecked", "rawtypes"})
    List<Pair<org.apache.tinkerpop.gremlin.process.traversal.Traversal.Admin,
        Comparator>> localComparators = local.getComparators();
    assertThat(localComparators).hasSize(1);
    assertThat(localComparators.get(0).getValue0()).isInstanceOf(TokenTraversal.class);
    assertThat(((TokenTraversal) localComparators.get(0).getValue0()).getToken()).isEqualTo(T.id);
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

  private static OrderGlobalStep<?, ?>
      orderStep(org.apache.tinkerpop.gremlin.process.traversal.Traversal.Admin<?, ?> admin) {
    return admin.getSteps().stream()
        .filter(OrderGlobalStep.class::isInstance)
        .map(OrderGlobalStep.class::cast)
        .findFirst()
        .orElseThrow();
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static
      List<Pair<org.apache.tinkerpop.gremlin.process.traversal.Traversal.Admin, Comparator>>
      comparators(OrderGlobalStep step) {
    return step.getComparators();
  }
}
