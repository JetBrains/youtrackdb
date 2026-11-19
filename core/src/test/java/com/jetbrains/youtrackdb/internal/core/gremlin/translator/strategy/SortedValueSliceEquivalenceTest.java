package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.api.config.OrderByNullsPlacement;
import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBElementImpl;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.YTDBMatchPlanStep;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy.TranslatorEquivalenceSupport.Cardinality;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy.TranslatorEquivalenceSupport.Recognition;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;
import org.apache.tinkerpop.gremlin.process.traversal.Order;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.structure.T;
import org.junit.Test;

/** End-to-end sorted slices pin engagement, sequence order, null positions and duplicate visits. */
public class SortedValueSliceEquivalenceTest extends GraphBaseTest {

  private final TranslatorEquivalenceSupport support =
      new TranslatorEquivalenceSupport(() -> session);

  /** Every order and slice spelling drops missing keys before counting under all settings. */
  @Test
  public void rootAndSharedHopSlices_preserveOrderedSurvivorsUnderAllSettings() {
    var source = session.createVertexClass("Source");
    var named = session.createVertexClass("Named");
    named.createProperty("name", PropertyType.STRING).setCollate("ci");
    var link = session.createEdgeClass("Link");
    link.getProperty("out").setLinkedClass(source);
    link.getProperty("in").setLinkedClass(named);
    var first = graph.addVertex(T.label, "Source", "selected", true);
    var second = graph.addVertex(T.label, "Source", "selected", true);
    // Missing keys bracket present nulls and ties in record order, not value order.
    for (var value : Arrays.asList("missing", null, "Zebra", "alice", "Bob", null,
        "Bob", "missing")) {
      var vertex = graph.addVertex(T.label, "Named", "selected", true);
      if (!"missing".equals(value)) {
        ((YTDBElementImpl) vertex).getRawEntity().setProperty("name", value);
      }
      first.addEdge("Link", vertex);
      second.addEdge("Link", vertex);
    }
    graph.tx().commit();
    var config = session.getConfiguration();
    var missingKey = GlobalConfiguration.QUERY_GREMLIN_ORDER_INCLUDES_MISSING_KEY;
    var ascKey = GlobalConfiguration.QUERY_ORDER_BY_NULLS_PLACEMENT_ASC;
    var descKey = GlobalConfiguration.QUERY_ORDER_BY_NULLS_PLACEMENT_DESC;
    var oldMissing = config.getValue(missingKey);
    var oldAsc = config.getValue(ascKey);
    var oldDesc = config.getValue(descKey);
    try {
      for (var missing : List.of(false, true)) {
        config.setValue(missingKey, missing);
        for (var placement : OrderByNullsPlacement.values()) {
          config.setValue(ascKey, placement);
          config.setValue(descKey, placement);
          for (var hop : List.of(false, true)) {
            for (int order = 0; order < 4; order++) {
              for (int slice = 0; slice < 5; slice++) {
                int orderForm = order;
                int sliceForm = slice;
                var expected = new ArrayList<Object>(Arrays.asList("alice", "Bob", "Bob",
                    "Zebra"));
                if (order == 2) {
                  java.util.Collections.reverse(expected);
                }
                if (placement == OrderByNullsPlacement.FIRST) {
                  expected.addAll(0, Arrays.asList(null, null));
                } else {
                  expected.addAll(Arrays.asList(null, null));
                }
                if (hop) {
                  var repeated = new ArrayList<Object>();
                  expected.forEach(value -> {
                    repeated.add(value);
                    repeated.add(value);
                  });
                  expected = repeated;
                }
                var complete = expected;
                expected = switch (slice) {
                  case 0 -> new ArrayList<>(complete.subList(0, 3));
                  case 1 -> new ArrayList<>(complete.subList(1, 5));
                  case 2 -> new ArrayList<>(complete.subList(2, complete.size()));
                  case 3 -> new ArrayList<>();
                  default -> new ArrayList<>(complete.subList(0, complete.size()));
                };
                Supplier<GraphTraversal<?, ?>> query = () -> {
                  var values = hop
                      ? graph.traversal().V().hasLabel("Source").has("selected", true)
                          .out("Link").values("name")
                      : graph.traversal().V().hasLabel("Named").has("selected", true)
                          .values("name");
                  var sorted = values.order();
                  switch (orderForm) {
                    case 1 -> sorted.by(Order.asc);
                    case 2 -> sorted.by(Order.desc);
                    case 3 -> sorted.by(__.identity(), Order.asc);
                    default -> {
                    }
                  }
                  return switch (sliceForm) {
                    case 0 -> sorted.limit(3);
                    case 1 -> sorted.range(1, 5);
                    case 2 -> sorted.skip(2);
                    case 3 -> sorted.limit(0);
                    default -> sorted.limit(100);
                  };
                };
                var scenario = "missing=" + missing + " nulls=" + placement + " hop=" + hop
                    + " order=" + order + " slice=" + slice;
                support.assertEquivalent(scenario, Recognition.RECOGNIZED,
                    slice == 3 ? Cardinality.MAY_BE_EMPTY : Cardinality.NON_EMPTY,
                    SortedValueSliceEquivalenceTest::orderedRows, query);
                assertThat(query.get().toList()).as(scenario).isEqualTo(expected);
              }
            }
          }
        }
      }
    } finally {
      config.setValue(missingKey, oldMissing);
      config.setValue(ascKey, oldAsc);
      config.setValue(descKey, oldDesc);
    }
  }

  /** Declared default, absent declarations and conflicting subclasses all use plain comparison. */
  @Test
  public void defaultAbsentAndConflictingCollations_preservePlainOrder() {
    var declared = session.createVertexClass("Declared");
    declared.createProperty("name", PropertyType.STRING);
    session.createVertexClass("Absent");
    var parent = session.createVertexClass("Parent");
    session.getMetadata().getSchema().createClass("CollatedChild", parent)
        .createProperty("name", PropertyType.STRING).setCollate("ci");
    session.getMetadata().getSchema().createClass("Child", parent)
        .createProperty("name", PropertyType.STRING);
    for (var label : List.of("Declared", "Absent", "Parent", "Child", "CollatedChild")) {
      for (var value : List.of("alice", "Zebra", "Bob", "Bob")) {
        graph.addVertex(T.label, label, "name", value);
      }
    }
    graph.tx().commit();
    for (var label : List.of("Declared", "Absent", "Parent")) {
      support.assertEquivalent(label, Recognition.RECOGNIZED, Cardinality.NON_EMPTY,
          SortedValueSliceEquivalenceTest::orderedRows,
          () -> graph.traversal().V().hasLabel(label).values("name").order().range(1, 3));
    }
  }

  /** Root index order and a LINK-narrowed indexed hop must execute rather than silently decline. */
  @Test
  public void indexedRootAndHop_keepIndexOrderedExecution() {
    var source = session.createVertexClass("Source");
    var named = session.createVertexClass("Named");
    var name = named.createProperty("name", PropertyType.INTEGER);
    name.setNotNull(true);
    name.createIndex(SchemaClass.INDEX_TYPE.NOTUNIQUE);
    var link = session.createEdgeClass("Link");
    link.getProperty("out").setLinkedClass(source);
    link.getProperty("in").setLinkedClass(named);
    var start = graph.addVertex(T.label, "Source");
    for (int i = 999; i >= 0; i--) {
      start.addEdge("Link", graph.addVertex(T.label, "Named", "name", i / 2));
    }
    graph.tx().commit();
    for (var hop : List.of(false, true)) {
      Supplier<GraphTraversal<?, ?>> query = () -> hop
          ? graph.traversal().V().hasLabel("Source").out("Link")
              .values("name").order().limit(10)
          : graph.traversal().V().hasLabel("Named").values("name").order().limit(10);
      support.assertEquivalent("indexed hop=" + hop, Recognition.RECOGNIZED,
          Cardinality.NON_EMPTY, SortedValueSliceEquivalenceTest::orderedRows, query);
      support.withTranslator(true, () -> {
        var traversal = query.get().asAdmin();
        traversal.applyStrategies();
        var step = (YTDBMatchPlanStep<?, ?>) traversal.getStartStep();
        assertThat(step.getPlan().prettyPrint(0, 2))
            .contains(hop ? "INDEX ORDERED MATCH" : "FETCH FROM INDEX VALUES ASC Named.name");
        assertThat(traversal.toList()).isEqualTo(List.of(0, 0, 1, 1, 2, 2, 3, 3, 4, 4));
      });
    }
  }

  /** Property-key modulators and dedup do not belong to projected-value slice admission. */
  @Test
  public void unsupportedValueTransforms_keepNativeExecution() {
    graph.addVertex(T.label, "Named", "name", "Bob");
    graph.tx().commit();
    support.withTranslatorRestored(() -> {
      for (var enabled : List.of(true, false)) {
        support.setTranslatorEnabled(enabled);
        var keyed = graph.traversal().V().hasLabel("Named").values("name")
            .order().by("name").limit(1).asAdmin();
        keyed.applyStrategies();
        assertThat(TranslatorEquivalenceSupport.countBoundarySteps(keyed)).isZero();
        org.assertj.core.api.Assertions.assertThatThrownBy(keyed::toList)
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("modulator");
      }
    });
    support.assertEquivalent("dedup value order", Recognition.DECLINED,
        Cardinality.NON_EMPTY, SortedValueSliceEquivalenceTest::orderedRows,
        () -> graph.traversal().V().hasLabel("Named").values("name")
            .dedup().order().limit(1));
  }

  /** Element identity before extraction must not promote the later missing-property drop. */
  @Test
  public void elementIdentityOrderBeforeValues_declinesWithoutRidStrategy() {
    graph.addVertex(T.label, "Named");
    graph.addVertex(T.label, "Named", "name", "Bob");
    graph.addVertex(T.label, "Named", "name", "Alice");
    graph.tx().commit();
    support.assertEquivalent("element identity is not projected identity", Recognition.DECLINED,
        Cardinality.NON_EMPTY, SortedValueSliceEquivalenceTest::orderedRows,
        () -> graph.traversal().withoutStrategies(
            com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy.optimization.YTDBOrderRidTieBreakStrategy.class)
            .V().hasLabel("Named").order().values("name").limit(2));
  }

  /** Repeated identity comparators still refer to the extracted value and preserve its slice. */
  @Test
  public void multipleIdentityComparators_preserveProjectedOrder() {
    for (var value : List.of("Bob", "Alice", "Carol")) {
      graph.addVertex(T.label, "Named", "name", value);
    }
    graph.tx().commit();
    support.assertEquivalent("multiple projected identities", Recognition.RECOGNIZED,
        Cardinality.NON_EMPTY, SortedValueSliceEquivalenceTest::orderedRows,
        () -> graph.traversal().V().hasLabel("Named").values("name")
            .order().by(__.identity(), Order.asc).by(__.identity(), Order.desc).limit(2));
    assertThat(graph.traversal().V().hasLabel("Named").values("name")
        .order().by(__.identity(), Order.asc).by(__.identity(), Order.desc).limit(2).toList())
        .containsExactly("Alice", "Bob");
  }

  /** Union barriers sort each arm's complete input, dropping missing keys but retaining nulls. */
  @Test
  public void sortedUnionArms_preserveCompleteOrderedSequenceWithMissingAndNullNames() {
    var start = graph.addVertex(T.label, "Source");
    for (var value : Arrays.asList("missing", "Bob", null, "Alice", "Carol")) {
      var vertex = graph.addVertex(T.label, "Named");
      if (!"missing".equals(value)) {
        ((YTDBElementImpl) vertex).getRawEntity().setProperty("name", value);
      }
      start.addEdge("Link", vertex);
    }
    graph.tx().commit();
    support.assertEquivalent("sorted union arms", Recognition.RECOGNIZED,
        Cardinality.NON_EMPTY, SortedValueSliceEquivalenceTest::orderedRows,
        () -> graph.traversal().V().hasLabel("Source").out("Link")
            .union(__.values("name").order().limit(2),
                __.values("name").order().limit(1)));
  }

  /** Accepted representation ties are observed without inventing a secondary comparison rule. */
  @Test
  public void numericAndCaseVariantTies_recordRetainedRepresentations() {
    var named = session.createVertexClass("Named");
    named.createProperty("name", PropertyType.STRING).setCollate("ci");
    for (var value : List.of("ada", "Ada", "ADA", "Bob")) {
      graph.addVertex(T.label, "Named", "name", value);
    }
    for (var value : List.of(1, 1L, 1.0, 2)) {
      graph.addVertex(T.label, "Numeric", "number", value);
    }
    graph.tx().commit();
    support.withTranslatorRestored(() -> {
      for (var ci : List.of(false, true)) {
        var paths = new ArrayList<List<String>>();
        for (var translated : List.of(true, false)) {
          support.setTranslatorEnabled(translated);
          var query = ci
              ? graph.traversal().V().hasLabel("Named").values("name").order().range(1, 2)
              : graph.traversal().V().hasLabel("Numeric").values("number").order().range(1, 2);
          query.asAdmin().applyStrategies();
          assertThat(TranslatorEquivalenceSupport.countBoundarySteps(query.asAdmin()))
              .isEqualTo(translated ? 1 : 0);
          var rows = query.toList();
          assertThat(rows).hasSize(1);
          if (ci) {
            assertThat(rows.getFirst()).isIn("ada", "Ada", "ADA");
          } else {
            assertThat(((Number) rows.getFirst()).doubleValue()).isEqualTo(1);
          }
          paths.add(rows.stream().map(value -> value.getClass().getSimpleName() + ":" + value)
              .toList());
        }
        System.out.println("SORTED TIE ci=" + ci + " translated=" + paths.get(0)
            + " native=" + paths.get(1));
      }
    });
  }

  /** Both cache layers rebind a selective start and metadata changes invalidate sorted templates. */
  @Test
  public void sortedSlices_rebindOnBothCachePathsAndInvalidateOnMetadata() throws Exception {
    var source = session.createVertexClass("Source");
    source.createProperty("bucket", PropertyType.INTEGER)
        .createIndex(SchemaClass.INDEX_TYPE.NOTUNIQUE);
    var named = session.createVertexClass("Named");
    named.createProperty("name", PropertyType.INTEGER).setMandatory(true);
    var link = session.createEdgeClass("Link");
    link.getProperty("out").setLinkedClass(source);
    link.getProperty("in").setLinkedClass(named);
    for (int bucket = 1; bucket <= 2; bucket++) {
      var start = graph.addVertex(T.label, "Source", "bucket", bucket);
      for (int value = 5; value >= 1; value--) {
        start.addEdge("Link", graph.addVertex(T.label, "Named", "name", bucket * 10 + value));
      }
    }
    graph.tx().commit();
    graph.tx().readWrite();
    var cache = GremlinPlanCache.instance(session);
    support.withTranslator(true, () -> {
      cache.invalidate();
      assertCachedBinding(1, List.of(12, 13, 14));
      var hits = cache.getTranslationHits();
      assertCachedBinding(2, List.of(22, 23, 24));
      assertThat(cache.getTranslationHits()).isEqualTo(hits + 1);
    });
    var field = GremlinPlanCache.class.getDeclaredField("translationCache");
    field.setAccessible(true);
    var translationCache = field.get(cache);
    var clear = translationCache.getClass().getMethod("invalidateAll");
    clear.setAccessible(true);
    clear.invoke(translationCache);
    support.withTranslator(true, () -> {
      var hits = cache.getHits();
      assertCachedBinding(2, List.of(22, 23, 24));
      assertThat(cache.getHits()).isEqualTo(hits + 1);
    });
    graph.tx().rollback();
    named.createProperty("metadataChange", PropertyType.STRING);
    support.withTranslator(true, () -> assertCachedBinding(1, List.of(12, 13, 14)));
  }

  private void assertCachedBinding(int bucket, List<Integer> expected) {
    var query = graph.traversal().V().hasLabel("Source").has("bucket", bucket)
        .out("Link").hasLabel("Named").values("name").order().range(1, 4).asAdmin();
    query.applyStrategies();
    assertThat(TranslatorEquivalenceSupport.countBoundarySteps(query)).isEqualTo(1);
    assertThat(query.toList()).isEqualTo(expected);
  }

  private static List<String> orderedRows(List<?> rows) {
    return rows.stream().map(String::valueOf).toList();
  }
}
