package com.jetbrains.youtrackdb.internal.core.gremlin.traversal.step.filter;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.core.collate.CaseInsensitiveCollate;
import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBTransaction;
import com.jetbrains.youtrackdb.internal.core.gremlin.traversal.step.sideeffect.YTDBGraphStep;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.TextP;
import org.apache.tinkerpop.gremlin.process.traversal.step.GValue;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.HasContainer;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.junit.Test;

/** Exercises native property filtering because translated MATCH already applies schema collation. */
public class YTDBCollatedHasContainerTest extends GraphBaseTest {

  /** Scalar comparison, ranges, membership, text predicates, and boolean trees use {@code ci}. */
  @Test
  public void postHopSupportedPredicatesUseDeclaredCollation() {
    var vertices = seedPeople();
    var source = vertices.getFirst();

    assertNames(source, P.eq("BOB"), "bob");
    assertNames(source, P.neq("BOB"), "Alice");
    assertNames(source, P.gte("BOB").and(P.lt("CAROL")), "bob");
    assertNames(source, P.within("BOB", "CHARLIE"), "bob");
    assertNames(source, P.without("BOB", "CHARLIE"), "Alice");
    assertNames(source, TextP.containing("O"), "bob");
    assertNames(source, TextP.notContaining("O"), "Alice");
    assertNames(source, TextP.startingWith("B"), "bob");
    assertNames(source, TextP.notStartingWith("B"), "Alice");
    assertNames(source, TextP.endingWith("B"), "bob");
    assertNames(source, TextP.notEndingWith("B"), "Alice");
    assertNames(source, P.eq("BOB").or(P.eq("MISSING")), "bob");
    assertNames(source, P.not(P.eq("BOB")), "Alice");
  }

  /** By-ID filtering reaches the same wrapped container without changing graph-start planning. */
  @Test
  public void byIdPropertyFilterUsesDeclaredCollation() {
    var vertices = seedPeople();
    var bob = vertices.get(1);

    withTranslator(false,
        () -> assertThat(graph.traversal().V(bob.id()).has("name", "BOB").toList())
            .containsExactly(bob));
  }

  /** Edge properties use their concrete edge class declaration and owner metadata. */
  @Test
  public void edgePropertyFilterUsesDeclaredCollation() {
    var person = session.createVertexClass("Person");
    person.createProperty("name", PropertyType.STRING);
    var knows = session.createEdgeClass("Knows");
    knows.createProperty("role", PropertyType.STRING).setCollate("ci");
    var source = graph.addVertex(T.label, "Person", "name", "source");
    var target = graph.addVertex(T.label, "Person", "name", "target");
    source.addEdge("Knows", target, "role", "Supervisor");
    graph.tx().commit();

    withTranslator(false, () -> assertThat(
        graph.traversal().V(source.id()).outE("Knows").has("role", "SUPERVISOR").count().next())
        .isOne());
  }

  /** Effective inherited declarations apply while conflicting concrete declarations stay local. */
  @Test
  public void polymorphicOwnersResolveTheirEffectiveDeclarations() {
    var base = session.createVertexClass("BasePerson");
    base.createProperty("name", PropertyType.STRING).setCollate("ci");
    var schema = session.getMetadata().getSchema();
    var inherited = schema.createClass("InheritedPerson", base);
    var strict = schema.createClass("StrictPerson", base);
    strict.createProperty("name", PropertyType.STRING);
    var sourceClass = session.createVertexClass("Source");
    var source = graph.addVertex(T.label, sourceClass.getName());
    var collated = graph.addVertex(T.label, inherited.getName(), "name", "bob");
    var caseSensitive = graph.addVertex(T.label, strict.getName(), "name", "bob");
    source.addEdge("sees", collated);
    source.addEdge("sees", caseSensitive);
    graph.tx().commit();

    withTranslator(false, () -> assertThat(
        graph.traversal().V(source.id()).out("sees").has("name", "BOB").toList())
        .containsExactly(collated));
  }

  /** List equality and retained-set equality apply collation without changing collection shape. */
  @Test
  public void collectionEqualityUsesDeclaredCollation() {
    var person = session.createVertexClass("Person");
    person.createProperty("aliases", PropertyType.EMBEDDEDLIST)
        .setLinkedType(PropertyType.STRING)
        .setCollate("ci");
    person.createProperty("tags", PropertyType.EMBEDDEDSET)
        .setLinkedType(PropertyType.STRING)
        .setCollate("ci");
    var source = graph.addVertex(T.label, "Person");
    var target = graph.addVertex(
        T.label,
        "Person",
        "aliases",
        List.of("Bob", "Builder"),
        "tags",
        new LinkedHashSet<>(Set.of("Blue", "Green")));
    source.addEdge("sees", target);
    graph.tx().commit();

    withTranslator(false, () -> {
      assertThat(target.<List<String>>value("aliases"))
          .containsExactly("Bob", "Builder");
      assertThat(new YTDBCollatedHasContainer(
          "aliases", P.eq(List.of("BOB", "BUILDER"))).test(target)).isTrue();
      assertThat(graph.traversal().V(source.id())
          .out("sees")
          .has("aliases", P.eq(List.of("BOB", "BUILDER")))
          .toList()).containsExactly(target);
      assertThat(graph.traversal().V(source.id())
          .out("sees")
          .has("tags", P.eq(GValue.of("expectedTags", Set.of("BLUE", "GREEN"))))
          .toList()).containsExactly(target);
    });
  }

  /** Ordinary set literals retain the existing native Set-to-List mismatch approved as a non-goal. */
  @Test
  public void ordinarySetLiteralKeepsNativeShapeMismatch() {
    var person = session.createVertexClass("Person");
    person.createProperty("tags", PropertyType.EMBEDDEDSET)
        .setLinkedType(PropertyType.STRING)
        .setCollate("ci");
    var source = graph.addVertex(T.label, "Person");
    var target = graph.addVertex(
        T.label, "Person", "tags", new LinkedHashSet<>(Set.of("Blue", "Green")));
    source.addEdge("sees", target);
    graph.tx().commit();

    withTranslator(false, () -> {
      assertThat(graph.traversal().V(source.id())
          .out("sees")
          .has("tags", P.eq(Set.of("Blue", "Green")))
          .toList()).isEmpty();
      assertThat(graph.traversal().V(source.id())
          .out("sees")
          .has("tags", P.eq(Set.of("BLUE", "GREEN")))
          .toList()).isEmpty();
    });
  }

  /** Default, schemaless, and regular-expression predicates retain case-sensitive behavior. */
  @Test
  public void unsupportedOrMetadataFreePredicatesKeepNativeBehavior() {
    var declared = session.createVertexClass("Declared");
    declared.createProperty("name", PropertyType.STRING);
    var collated = session.createVertexClass("Collated");
    collated.createProperty("name", PropertyType.STRING).setCollate("ci");
    var source = graph.addVertex(T.label, "Declared");
    var strict = graph.addVertex(T.label, "Declared", "name", "bob");
    var schemaless = graph.addVertex(T.label, "V", "name", "bob");
    var regex = graph.addVertex(T.label, "Collated", "name", "bob");
    source.addEdge("sees", strict);
    source.addEdge("sees", schemaless);
    source.addEdge("sees", regex);
    graph.tx().commit();

    withTranslator(false, () -> {
      assertThat(graph.traversal().V(source.id()).out("sees").has("name", "BOB").toList())
          .containsExactly(regex);
      assertThat(graph.traversal().V(source.id())
          .out("sees")
          .has("name", TextP.regex("BOB"))
          .toList()).isEmpty();
    });
  }

  /** Strategy replacement preserves order, predicate identity, clones, and repeat application. */
  @Test
  public void strategyWrappingPreservesContainerContracts() {
    var first = P.eq("BOB");
    var second = P.gt(10);
    var traversal = graph.traversal().V().has("name", first).has("age", second).asAdmin();

    withTranslator(false, () -> {
      traversal.applyStrategies();
      var graphStep = (YTDBGraphStep<?, ?>) traversal.getStartStep();
      assertThat(graphStep.getHasContainers()).extracting(HasContainer::getKey)
          .containsExactly("name", "age");
      assertThat(graphStep.getHasContainers().getFirst())
          .isInstanceOf(YTDBCollatedHasContainer.class);
      assertThat(graphStep.getHasContainers().getFirst().getPredicate()).isSameAs(first);
      assertThat(graphStep.getHasContainers().get(1).getPredicate()).isSameAs(second);
      assertThat(graphStep.getHasContainers().getFirst().clone())
          .isInstanceOf(YTDBCollatedHasContainer.class);

      assertThat(YTDBCollatedHasContainer.wrap(graphStep.getHasContainers().getFirst()))
          .isSameAs(graphStep.getHasContainers().getFirst());
      assertThat(graphStep.getHasContainers()).hasSize(2);
    });
  }

  /** Lazy list transformation reads only the prefix consumed by a short-circuiting comparison. */
  @Test
  public void caseInsensitiveListTransformationDoesNotCopyWholeInput() {
    var values = new CountingList(10_000);
    var transformed = YTDBCollatedHasContainer.transform(values, new CaseInsensitiveCollate());

    assertThat(YTDBCollatedHasContainer.valuesEqual(
        transformed, differentValues(values.size()))).isFalse();
    assertThat(values.reads).isLessThan(values.size());
  }

  private List<Vertex> seedPeople() {
    var sourceClass = session.createVertexClass("Source");
    var person = session.createVertexClass("Person");
    person.createProperty("name", PropertyType.STRING).setCollate("ci");
    var source = graph.addVertex(T.label, sourceClass.getName());
    var bob = graph.addVertex(T.label, person.getName(), "name", "bob");
    var alice = graph.addVertex(T.label, person.getName(), "name", "Alice");
    source.addEdge("knows", bob);
    source.addEdge("knows", alice);
    graph.tx().commit();
    return List.of(source, bob, alice);
  }

  private void assertNames(Vertex source, P<?> predicate, String... names) {
    withTranslator(false, () -> assertThat(graph.traversal().V(source.id())
        .out("knows")
        .has("name", predicate)
        .values("name")
        .toList()).containsExactlyInAnyOrder(names));
  }

  private static List<String> differentValues(int size) {
    var values = new ArrayList<String>(size);
    values.add("different");
    for (var index = 1; index < size; index++) {
      values.add("value-" + index);
    }
    return values;
  }

  private void withTranslator(boolean enabled, Runnable body) {
    graph.tx().readWrite();
    var configuration = ((YTDBTransaction) graph.tx()).getDatabaseSession().getConfiguration();
    var original = configuration.getValueAsBoolean(
        GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED);
    configuration.setValue(GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED, enabled);
    try {
      body.run();
    } finally {
      configuration.setValue(GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED,
          original);
    }
  }

  private static final class CountingList extends AbstractList<String> {

    private final List<String> values;
    private int reads;

    private CountingList(int size) {
      values = new ArrayList<>(size);
      for (var index = 0; index < size; index++) {
        values.add("value-" + index);
      }
    }

    @Override
    public String get(int index) {
      reads++;
      return values.get(index);
    }

    @Override
    public int size() {
      return values.size();
    }
  }
}
