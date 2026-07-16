package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBTransaction;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.YTDBMatchPlanStep;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import java.util.List;
import java.util.function.Supplier;
import org.apache.tinkerpop.gremlin.process.traversal.TextP;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.junit.Test;

/**
 * Translator-on / translator-off equivalence fixture for the predicate surface: {@code
 * has(key, value)}, {@code hasLabel(L)}, {@code hasId(...)}, the {@code has(key)} presence form, and
 * the same-alias AND-composition. Each case runs the same traversal shape twice — translator on, then
 * off — and asserts (a) boundary-step engagement (a RECOGNIZED shape has exactly one {@link
 * YTDBMatchPlanStep} on and none off; a DECLINED shape has none either way) and (b) result-multiset
 * equality between the two runs. Multiset equality is on sorted RID strings, preserving multiplicity.
 *
 * <p>The {@code hasLabel} cases additionally pin the polymorphism contract on a {@code Person} /
 * {@code Employee} hierarchy: native membership is pinned first with the translator off (leaf-exact
 * under non-polymorphic, hierarchy-aware under polymorphic — the two modes {@code YTDBLabelMatcher}
 * produces), then the translated plan is shown to match under both modes and to narrow the scan by
 * re-typing the boundary node (its plan fetches from the labelled class, not the generic {@code V}).
 */
public class PredicateTraversalEquivalenceTest extends GraphBaseTest {

  private enum Recognition {
    RECOGNIZED, DECLINED
  }

  // ---------------------------------------------------------------------------
  // Native membership pin: with the translator OFF, hasLabel is leaf-exact
  // under non-polymorphic and hierarchy-aware under polymorphic. This pins the
  // behaviour the translated path must reproduce, so the equivalence tests below
  // are not vacuously comparing two wrong results.
  // ---------------------------------------------------------------------------

  /**
   * Native (translator-off) {@code hasLabel} membership on a {@code Person} / {@code Employee}
   * hierarchy: under polymorphic mode {@code hasLabel("Person")} is hierarchy-aware (matches the
   * {@code Person} and the {@code Employee}), under non-polymorphic mode it is leaf-exact (matches
   * only the {@code Person}). {@code hasLabel("Employee")} matches the {@code Employee} in both modes.
   * This is the native contract the translated path reproduces.
   */
  @Test
  public void hasLabelNativeMembership_polymorphicIsHierarchyAware_nonPolymorphicIsLeafExact() {
    seedPersonEmployeeHierarchy();
    withTranslator(false, () -> {
      withPolymorphicDefault(true, () -> {
        assertThat(labelsOf(graph.traversal().V().hasLabel("Person").toList()))
            .as("native polymorphic hasLabel(Person) is hierarchy-aware")
            .containsExactlyInAnyOrder("Person", "Employee");
        assertThat(labelsOf(graph.traversal().V().hasLabel("Employee").toList()))
            .containsExactlyInAnyOrder("Employee");
      });
      withPolymorphicDefault(false, () -> {
        assertThat(labelsOf(graph.traversal().V().hasLabel("Person").toList()))
            .as("native non-polymorphic hasLabel(Person) is leaf-exact")
            .containsExactlyInAnyOrder("Person");
        assertThat(labelsOf(graph.traversal().V().hasLabel("Employee").toList()))
            .containsExactlyInAnyOrder("Employee");
      });
    });
  }

  // ---------------------------------------------------------------------------
  // hasLabel — polymorphic and non-polymorphic equivalence + scan narrowing.
  // ---------------------------------------------------------------------------

  /**
   * Polymorphic {@code g.V().hasLabel("Person")} translates to the same hierarchy-aware multiset as
   * native (the {@code Person} and the {@code Employee}) and narrows the scan by re-typing the
   * boundary node to {@code Person}: the translated plan fetches from class {@code Person}, not the
   * generic {@code V}. Re-typing to {@code Person} is a polymorphic {@code SELECT FROM Person} scan,
   * so it includes {@code Employee} subclass rows — mirroring native polymorphic {@code hasLabel}.
   */
  @Test
  public void hasLabelPolymorphic_translatesHierarchyAware_andNarrowsScanToClass() {
    seedPersonEmployeeHierarchy();
    withPolymorphicDefault(true, () -> {
      assertEquivalent(
          "polymorphic g.V().hasLabel(Person)",
          Recognition.RECOGNIZED,
          () -> graph.traversal().V().hasLabel("Person"));
      // Scan-shape: the boundary node was re-typed to Person, so the plan fetches from Person.
      assertThat(boundaryPlanText(() -> graph.traversal().V().hasLabel("Person")))
          .as("polymorphic hasLabel re-types the boundary node — the plan fetches from Person")
          .contains("FETCH FROM CLASS Person")
          .doesNotContain("FETCH FROM CLASS V ");
    });
  }

  /**
   * Non-polymorphic {@code g.V().hasLabel("Person")} translates to the leaf-exact native multiset
   * (only the {@code Person}, not the {@code Employee}) and narrows the scan by re-typing the
   * boundary node to {@code Person}. Non-polymorphic mode adds an exact {@code @class = 'Person'}
   * filter on top of the re-typed scan so an {@code Employee} subclass row is excluded, mirroring
   * native non-polymorphic {@code hasLabel}.
   */
  @Test
  public void hasLabelNonPolymorphic_translatesLeafExact_andNarrowsScanToClass() {
    seedPersonEmployeeHierarchy();
    withPolymorphicDefault(false, () -> {
      assertEquivalent(
          "non-polymorphic g.V().hasLabel(Person)",
          Recognition.RECOGNIZED,
          () -> graph.traversal().V().hasLabel("Person"));
      assertThat(boundaryPlanText(() -> graph.traversal().V().hasLabel("Person")))
          .as("non-polymorphic hasLabel also re-types the boundary node — the plan fetches from "
              + "Person, then filters @class")
          .contains("FETCH FROM CLASS Person")
          .doesNotContain("FETCH FROM CLASS V ");
    });
  }

  /**
   * {@code g.V().hasLabel("Employee")} (a leaf subclass) returns the {@code Employee} in both modes
   * and matches native — the subclass label narrows to exactly the {@code Employee}.
   */
  @Test
  public void hasLabelSubclass_matchesNativeInBothModes() {
    seedPersonEmployeeHierarchy();
    withPolymorphicDefault(true, () -> assertEquivalent(
        "polymorphic g.V().hasLabel(Employee)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().hasLabel("Employee")));
    withPolymorphicDefault(false, () -> assertEquivalent(
        "non-polymorphic g.V().hasLabel(Employee)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().hasLabel("Employee")));
  }

  /**
   * A multi-label {@code g.V().hasLabel("Person", "Employee")} declines to native: it arrives as a
   * single {@code within(...)} label container, which a single-class MATCH node cannot express.
   * Native polymorphic {@code hasLabel(Person, Employee)} matches an element of either class (both
   * vertices), so the declined native run returns that multiset.
   */
  @Test
  public void hasLabelMultiLabel_declinesToNative() {
    seedPersonEmployeeHierarchy();
    assertEquivalent(
        "g.V().hasLabel(Person, Employee) (multi-label)",
        Recognition.DECLINED,
        () -> graph.traversal().V().hasLabel("Person", "Employee"));
  }

  /**
   * {@code g.V().hasLabel("Missing")} on a never-used label declines to native rather than re-typing
   * to a non-existent class (which would make {@code SELECT FROM Missing} error). Native matches no
   * vertex, so the declined run returns empty — the two pipelines agree on emptiness.
   */
  @Test
  public void hasLabelNonExistentClass_declinesToNative() {
    seedPersonEmployeeHierarchy();
    assertEquivalent(
        "g.V().hasLabel(Missing) (never-used label)",
        Recognition.DECLINED,
        () -> graph.traversal().V().hasLabel("Missing"));
  }

  // ---------------------------------------------------------------------------
  // hasId — single, multi, and the set-membership duplicate case.
  // ---------------------------------------------------------------------------

  /** {@code g.V().hasId(id)} translates to an @rid IN filter and returns exactly that vertex. */
  @Test
  public void hasIdSingle_matchesNative() {
    var alice = graph.addVertex(T.label, "Person", "name", "Alice");
    graph.addVertex(T.label, "Person", "name", "Bob");
    graph.tx().commit();
    var aliceId = alice.id();
    assertEquivalent(
        "g.V().hasId(alice)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().hasId(aliceId));
  }

  /** {@code g.V().hasId(id1, id2)} translates to an @rid IN over both and returns both vertices. */
  @Test
  public void hasIdMulti_matchesNative() {
    var alice = graph.addVertex(T.label, "Person", "name", "Alice");
    var bob = graph.addVertex(T.label, "Person", "name", "Bob");
    graph.addVertex(T.label, "Person", "name", "Carol");
    graph.tx().commit();
    var aliceId = alice.id();
    var bobId = bob.id();
    assertEquivalent(
        "g.V().hasId(alice, bob)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().hasId(aliceId, bobId));
  }

  /**
   * {@code g.V().hasId(id, id)} with a repeated id is set membership — it matches the one vertex
   * once, matching native. Unlike {@code g.V(id, id)} (seek semantics, which the start step declines
   * for a duplicate), the {@code hasId} branch must NOT decline a duplicate: it maps to the same
   * {@code @rid IN [id]} filter.
   */
  @Test
  public void hasIdDuplicate_isSetMembership_matchesNative() {
    var alice = graph.addVertex(T.label, "Person", "name", "Alice");
    graph.addVertex(T.label, "Person", "name", "Bob");
    graph.tx().commit();
    var aliceId = alice.id();
    assertEquivalent(
        "g.V().hasId(alice, alice) (duplicate id, set membership)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().hasId(aliceId, aliceId));
  }

  // ---------------------------------------------------------------------------
  // Property has() and same-alias AND-composition.
  // ---------------------------------------------------------------------------

  /** {@code g.V().has("name", "Alice")} translates and returns only the Alice vertex. */
  @Test
  public void propertyHas_matchesNative() {
    graph.addVertex(T.label, "Person", "name", "Alice");
    graph.addVertex(T.label, "Person", "name", "Bob");
    graph.tx().commit();
    assertEquivalent(
        "g.V().has(name, Alice)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().has("name", "Alice"));
  }

  /**
   * Two filters on one alias AND-compose: {@code g.V(id1, id2).has("age", 30)} returns only the
   * age-30 vertices among the two addressed ids, not every age-30 vertex. Carol (age 30, not
   * addressed) must be excluded — an overwrite that dropped the @rid IN would wrongly include her.
   */
  @Test
  public void ridInAndHas_andCompose_onSameAlias() {
    var alice = graph.addVertex(T.label, "Person", "name", "Alice", "age", 30);
    var bob = graph.addVertex(T.label, "Person", "name", "Bob", "age", 40);
    graph.addVertex(T.label, "Person", "name", "Carol", "age", 30); // age 30 but not addressed
    graph.tx().commit();
    var aliceId = alice.id();
    var bobId = bob.id();
    assertEquivalent(
        "g.V(alice, bob).has(age, 30) — only Alice, not every age-30 vertex",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V(aliceId, bobId).has("age", 30));
  }

  /**
   * {@code hasLabel(L)} and {@code has(k, v)} on the same alias AND-compose: {@code
   * g.V().hasLabel("Employee").has("name", "Eve")} returns only Employees named Eve, intersecting
   * the class narrowing and the property filter.
   */
  @Test
  public void hasLabelAndHas_andCompose_onSameAlias() {
    seedPersonEmployeeHierarchy();
    graph.addVertex(T.label, "Employee", "name", "Eve");
    graph.addVertex(T.label, "Employee", "name", "Frank");
    graph.addVertex(T.label, "Person", "name", "Eve"); // a Person named Eve, must be excluded
    graph.tx().commit();
    assertEquivalent(
        "g.V().hasLabel(Employee).has(name, Eve)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().hasLabel("Employee").has("name", "Eve"));
  }

  // ---------------------------------------------------------------------------
  // has(key) presence form → IS DEFINED.
  // ---------------------------------------------------------------------------

  /**
   * {@code g.V().has("nickname")} translates to {@code nickname IS DEFINED} and matches native: the
   * vertices that carry the property (present with a value), excluding the vertex that lacks it. This
   * is the presence form, distinct from a value filter.
   */
  @Test
  public void hasKeyPresence_matchesNative() {
    graph.addVertex(T.label, "Person", "name", "Alice", "nickname", "Al");
    graph.addVertex(T.label, "Person", "name", "Bob", "nickname", "Bobby");
    graph.addVertex(T.label, "Person", "name", "Carol"); // no nickname — excluded
    graph.tx().commit();
    assertEquivalent(
        "g.V().has(nickname) presence",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().has("nickname"));
  }

  // ---------------------------------------------------------------------------
  // Non-String Text decline — a Text predicate on a declared non-String
  // property errors natively, so the translator declines and both pipelines error.
  // ---------------------------------------------------------------------------

  /**
   * A {@code Text} predicate on a declared non-String property declines to native. {@code age} is
   * declared {@code INTEGER} on {@code Person}, so native {@code hasLabel("Person").has("age",
   * TextP.containing("3"))} errors (a {@code Text} predicate tests String operands). The {@code
   * hasLabel} gives the recogniser the class context to resolve the property type; with the
   * translator on the shape must carry no boundary step (the adapter declined the non-String {@code
   * Text}), and both runs must throw — proving the translator fell back to native rather than
   * emitting a {@code CONTAINSTEXT} that returns rows.
   */
  @Test
  public void nonStringTextPredicate_declinesToNative_andBothError() {
    var person = session.createVertexClass("Person");
    person.createProperty("age", PropertyType.INTEGER);
    graph.addVertex(T.label, "Person", "name", "Alice", "age", 30);
    graph.tx().commit();

    withTranslator(true, () -> {
      var onAdmin =
          graph.traversal().V().hasLabel("Person").has("age", TextP.containing("3")).asAdmin();
      onAdmin.applyStrategies();
      assertThat(countBoundarySteps(onAdmin.getSteps()))
          .as("a Text predicate on a declared non-String property must decline — no boundary step")
          .isEqualTo(0);
      assertThatThrownBy(onAdmin::toList)
          .as("the declined shape runs native, which errors on a Text predicate over an int")
          .isInstanceOf(RuntimeException.class);
    });
    withTranslator(false, () -> assertThatThrownBy(
        () -> graph.traversal().V().hasLabel("Person").has("age", TextP.containing("3")).toList())
        .as("native errors on a Text predicate over an int")
        .isInstanceOf(RuntimeException.class));
  }

  /**
   * A {@code Text} predicate on a declared String property translates and matches native: {@code
   * name} is {@code STRING} on {@code Person}, so {@code hasLabel("Person").has("name",
   * TextP.containing("li"))} maps to {@code CONTAINSTEXT} and returns the matching vertices. This is
   * the companion to the non-String decline — with the same class context available, the type gate
   * declines only genuinely non-String properties and lets a String {@code Text} translate.
   */
  @Test
  public void stringTextPredicate_translates_matchesNative() {
    var person = session.createVertexClass("Person");
    person.createProperty("name", PropertyType.STRING);
    graph.addVertex(T.label, "Person", "name", "Alice");
    graph.addVertex(T.label, "Person", "name", "Bob");
    graph.tx().commit();
    assertEquivalent(
        "g.V().hasLabel(Person).has(name, containing(li)) on a String property",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().hasLabel("Person").has("name", TextP.containing("li")));
  }

  /**
   * A polymorphic {@code hasLabel(parent).has(subclassOnlyProp, Text...)} declines to native when
   * the non-String property is declared only on the subclass — the type gate must sweep subclasses,
   * not just the named class. Here {@code age} is {@code INTEGER} on {@code Employee} only (absent
   * from its {@code Person} parent). In polymorphic mode {@code hasLabel("Person")} is
   * hierarchy-aware, so an {@code Employee} row (with an {@code Integer} {@code age}) reaches the
   * {@code Text} predicate and native errors. A gate resolving the type only against {@code Person}
   * would miss the subclass declaration, translate a {@code CONTAINSTEXT} that silently returns no
   * rows, and diverge; the gate must decline instead, so both runs throw. This is the
   * polymorphic-subclass companion to the named-class non-String decline test above.
   */
  @Test
  public void polymorphicNonStringTextOnSubclassOnlyProperty_declinesToNative_andBothError() {
    var person = session.createVertexClass("Person");
    var employee = session.getSchema().createClass("Employee", person);
    employee.createProperty("age", PropertyType.INTEGER); // non-String, on the subclass only
    graph.addVertex(T.label, "Employee", "name", "Eve", "age", 30);
    graph.tx().commit();

    withPolymorphicDefault(true, () -> {
      withTranslator(true, () -> {
        var onAdmin =
            graph.traversal().V().hasLabel("Person").has("age", TextP.containing("3")).asAdmin();
        onAdmin.applyStrategies();
        assertThat(countBoundarySteps(onAdmin.getSteps()))
            .as("a Text predicate on a subclass-only non-String property must decline in "
                + "polymorphic mode — no boundary step")
            .isEqualTo(0);
        assertThatThrownBy(onAdmin::toList)
            .as("the declined shape runs native, which errors on a Text predicate over the "
                + "Employee's int age")
            .isInstanceOf(RuntimeException.class);
      });
      withTranslator(false, () -> assertThatThrownBy(
          () -> graph.traversal().V().hasLabel("Person").has("age", TextP.containing("3")).toList())
          .as("native polymorphic hasLabel(Person) matches the Employee, so its int age errors on "
              + "a Text predicate")
          .isInstanceOf(RuntimeException.class));
    });
  }

  // ---------------------------------------------------------------------------
  // Fixture + assertion helpers.
  // ---------------------------------------------------------------------------

  /** Seeds one {@code Person} and one {@code Employee} (a subclass of {@code Person}). */
  private void seedPersonEmployeeHierarchy() {
    var person = session.createVertexClass("Person");
    session.getSchema().createClass("Employee", person);
    graph.addVertex(T.label, "Person", "name", "Alice");
    graph.addVertex(T.label, "Employee", "name", "Eve");
    graph.tx().commit();
  }

  /**
   * Runs {@code traversalSupplier}'s shape with the translator enabled and again disabled, asserting
   * boundary-step engagement (per {@code expected}) and result-multiset equality between the runs.
   */
  private void assertEquivalent(
      String scenario, Recognition expected, Supplier<GraphTraversal<?, ?>> traversalSupplier) {
    var original = translatorEnabled();
    try {
      setTranslatorEnabled(true);
      var onAdmin = traversalSupplier.get().asAdmin();
      onAdmin.applyStrategies();
      var boundaryOn = countBoundarySteps(onAdmin.getSteps());
      var onIds = sortedIds(onAdmin.toList());

      setTranslatorEnabled(false);
      var offAdmin = traversalSupplier.get().asAdmin();
      offAdmin.applyStrategies();
      var boundaryOff = countBoundarySteps(offAdmin.getSteps());
      var offIds = sortedIds(offAdmin.toList());

      if (expected == Recognition.RECOGNIZED) {
        assertThat(boundaryOn)
            .as(scenario + " (translator on) must engage exactly one boundary step").isEqualTo(1);
        assertThat(onIds)
            .as(scenario + ": a RECOGNIZED fixture must return a non-empty result (else the "
                + "multiset equality below is vacuous)")
            .isNotEmpty();
      } else {
        assertThat(boundaryOn)
            .as(scenario + " (translator on) must decline to native — no boundary step")
            .isEqualTo(0);
      }
      assertThat(boundaryOff)
          .as(scenario + " (translator off) must never engage a boundary step").isEqualTo(0);
      assertThat(onIds)
          .as(scenario + ": translator-on and translator-off result multisets must match")
          .isEqualTo(offIds);
    } finally {
      setTranslatorEnabled(original);
    }
  }

  /** Applies strategies to the supplied traversal (translator on) and returns the boundary step's
   *  compiled plan rendered as text, for scan-shape assertions. */
  private String boundaryPlanText(Supplier<GraphTraversal<?, ?>> traversalSupplier) {
    var original = translatorEnabled();
    try {
      setTranslatorEnabled(true);
      var admin = traversalSupplier.get().asAdmin();
      admin.applyStrategies();
      var boundary = admin.getSteps().stream()
          .filter(YTDBMatchPlanStep.class::isInstance)
          .map(s -> (YTDBMatchPlanStep<?, ?>) s)
          .findFirst()
          .orElseThrow(() -> new AssertionError("expected a translated boundary step"));
      return boundary.getPlan().prettyPrint(0, 2);
    } finally {
      setTranslatorEnabled(original);
    }
  }

  private void withTranslator(boolean enabled, Runnable body) {
    var original = translatorEnabled();
    setTranslatorEnabled(enabled);
    try {
      body.run();
    } finally {
      setTranslatorEnabled(original);
    }
  }

  private void withPolymorphicDefault(boolean value, Runnable body) {
    var config = graphSession().getConfiguration();
    var previous =
        config.getValueAsBoolean(GlobalConfiguration.QUERY_GREMLIN_POLYMORPHIC_BY_DEFAULT);
    config.setValue(GlobalConfiguration.QUERY_GREMLIN_POLYMORPHIC_BY_DEFAULT, value);
    try {
      body.run();
    } finally {
      config.setValue(GlobalConfiguration.QUERY_GREMLIN_POLYMORPHIC_BY_DEFAULT, previous);
    }
  }

  private boolean translatorEnabled() {
    return graphSession()
        .getConfiguration()
        .getValueAsBoolean(GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED);
  }

  private void setTranslatorEnabled(boolean enabled) {
    graphSession()
        .getConfiguration()
        .setValue(GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED, enabled);
  }

  /** The database session backing the graph traversals (its config controls the translator flag). */
  private com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded graphSession() {
    var tx = (YTDBTransaction) graph.tx();
    tx.readWrite();
    return tx.getDatabaseSession();
  }

  private static List<String> sortedIds(List<?> results) {
    return results.stream().map(v -> ((Vertex) v).id().toString()).sorted().toList();
  }

  private static List<String> labelsOf(List<?> results) {
    return results.stream().map(v -> ((Vertex) v).label()).toList();
  }

  private static int countBoundarySteps(List<?> steps) {
    var count = 0;
    for (var step : steps) {
      if (step instanceof YTDBMatchPlanStep<?, ?>) {
        count++;
      }
    }
    return count;
  }
}
