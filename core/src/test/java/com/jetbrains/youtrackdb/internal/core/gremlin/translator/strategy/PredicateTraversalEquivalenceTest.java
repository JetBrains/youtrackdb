package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBTransaction;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.YTDBMatchPlanStep;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass.INDEX_TYPE;
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
  // Non-String Text native parity — a Text predicate on a non-String property
  // now TRANSLATES in strict mode and throws at execution exactly as native
  // does, instead of declining. Both pipelines error.
  // ---------------------------------------------------------------------------

  /**
   * A {@code Text} predicate on a declared non-String property now translates in strict mode and
   * throws at execution, matching native. {@code age} is declared {@code INTEGER} on {@code Person},
   * so native {@code hasLabel("Person").has("age", TextP.containing("3"))} errors (a {@code Text}
   * predicate tests String operands). With the translator on the shape now carries a boundary step
   * (the adapter emits a strict {@code CONTAINSTEXT}), and both runs throw — the strict node throws
   * on the {@code Integer} {@code age} exactly where native throws, so the pipelines agree on the
   * error rather than one returning rows.
   */
  @Test
  public void nonStringTextPredicate_translatesStrict_andBothThrow() {
    var person = session.createVertexClass("Person");
    person.createProperty("age", PropertyType.INTEGER);
    graph.addVertex(T.label, "Person", "name", "Alice", "age", 30);
    graph.tx().commit();

    assertTranslatedAndNativeThrow(
        "g.V().hasLabel(Person).has(age, containing(3)) on an int property",
        () -> graph.traversal().V().hasLabel("Person").has("age", TextP.containing("3")));
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
  public void polymorphicNonStringTextOnSubclassOnlyProperty_translatesStrict_andBothThrow() {
    var person = session.createVertexClass("Person");
    var employee = session.getSchema().createClass("Employee", person);
    employee.createProperty("age", PropertyType.INTEGER); // non-String, on the subclass only
    graph.addVertex(T.label, "Employee", "name", "Eve", "age", 30);
    graph.tx().commit();

    // In polymorphic mode hasLabel(Person) is hierarchy-aware, so the Employee row (Integer age)
    // reaches the predicate. The adapter emits a strict CONTAINSTEXT regardless of the property's
    // declared type (the type gate no longer gates Text), so the strict node throws on the Integer
    // age exactly where native throws — no subclass type sweep is needed to stay in parity.
    withPolymorphicDefault(true, () -> assertTranslatedAndNativeThrow(
        "polymorphic g.V().hasLabel(Person).has(age, containing(3)) — subclass-only int age",
        () -> graph.traversal().V().hasLabel("Person").has("age", TextP.containing("3"))));
  }

  // ---------------------------------------------------------------------------
  // startingWith routing — declared-String uses the index-aware range and
  // matches native; every other case uses the strict full-scan node, which
  // throws on a non-String value exactly as native does.
  // ---------------------------------------------------------------------------

  /**
   * {@code startingWith} on a declared, indexed String property translates to the index-aware
   * half-open prefix range and matches native. {@code name} is {@code STRING} with a NOTUNIQUE
   * index on {@code Person}, so the declared-String routing picks the range form (a B-tree prefix
   * scan) and returns the prefix-matching vertices.
   */
  @Test
  public void startingWithDeclaredStringIndexed_matchesNative() {
    var person = session.createVertexClass("Person");
    person.createProperty("name", PropertyType.STRING).createIndex(INDEX_TYPE.NOTUNIQUE);
    graph.addVertex(T.label, "Person", "name", "Alice");
    graph.addVertex(T.label, "Person", "name", "Albert");
    graph.addVertex(T.label, "Person", "name", "Bob");
    graph.tx().commit();
    assertEquivalent(
        "g.V().hasLabel(Person).has(name, startingWith(Al)) on an indexed String property",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().hasLabel("Person").has("name", TextP.startingWith("Al")));
  }

  /**
   * {@code startingWith} on a declared non-String property translates to the strict full-scan {@code
   * STARTSWITH} node and throws at execution like native. {@code age} is {@code INTEGER} on {@code
   * Person}, so the declared-non-String routing avoids the range (which cannot throw) and uses the
   * strict node; native {@code Text.startingWith} errors on the {@code Integer}, so both throw.
   */
  @Test
  public void startingWithDeclaredNonString_translatesStrict_andBothThrow() {
    var person = session.createVertexClass("Person");
    person.createProperty("age", PropertyType.INTEGER);
    graph.addVertex(T.label, "Person", "name", "Alice", "age", 30);
    graph.tx().commit();
    assertTranslatedAndNativeThrow(
        "g.V().hasLabel(Person).has(age, startingWith(3)) on an int property",
        () -> graph.traversal().V().hasLabel("Person").has("age", TextP.startingWith("3")));
  }

  /**
   * {@code startingWith} on a schema-less property holding a non-String value throws in both
   * pipelines. With no {@code hasLabel} the boundary is the generic {@code V}, so the property type
   * is unknown and the routing picks the strict full-scan node; the {@code code} value is an {@code
   * Integer}, so the strict node throws exactly where native {@code Text.startingWith} throws.
   */
  @Test
  public void startingWithSchemalessNonStringValue_translatesStrict_andBothThrow() {
    graph.addVertex(T.label, "Thing", "code", 1); // undeclared property, Integer value
    graph.tx().commit();
    assertTranslatedAndNativeThrow(
        "g.V().has(code, startingWith(1)) on a schema-less int value",
        () -> graph.traversal().V().has("code", TextP.startingWith("1")));
  }

  /**
   * {@code startingWith} on a schema-less property holding a String value matches native. The
   * routing picks the strict full-scan node (unknown type), which on a String value behaves like a
   * normal prefix match and returns the prefix-matching vertices — no throw, same multiset as
   * native.
   */
  @Test
  public void startingWithSchemalessStringValue_matchesNative() {
    graph.addVertex(T.label, "Thing", "code", "Alpha");
    graph.addVertex(T.label, "Thing", "code", "Beta");
    graph.tx().commit();
    assertEquivalent(
        "g.V().has(code, startingWith(Al)) on a schema-less String value",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().has("code", TextP.startingWith("Al")));
  }

  /**
   * {@code endingWith} on a declared non-String property translates strict and throws in both
   * pipelines — the suffix twin of the {@code startingWith} / {@code containing} non-String parity.
   */
  @Test
  public void endingWithDeclaredNonString_translatesStrict_andBothThrow() {
    var person = session.createVertexClass("Person");
    person.createProperty("age", PropertyType.INTEGER);
    graph.addVertex(T.label, "Person", "name", "Alice", "age", 30);
    graph.tx().commit();
    assertTranslatedAndNativeThrow(
        "g.V().hasLabel(Person).has(age, endingWith(0)) on an int property",
        () -> graph.traversal().V().hasLabel("Person").has("age", TextP.endingWith("0")));
  }

  /**
   * {@code regex} on a declared non-String property translates strict and throws in both pipelines —
   * the find-mode {@code MATCHES} twin of the non-String parity.
   */
  @Test
  public void regexDeclaredNonString_translatesStrict_andBothThrow() {
    var person = session.createVertexClass("Person");
    person.createProperty("age", PropertyType.INTEGER);
    graph.addVertex(T.label, "Person", "name", "Alice", "age", 30);
    graph.tx().commit();
    assertTranslatedAndNativeThrow(
        "g.V().hasLabel(Person).has(age, regex(3)) on an int property",
        () -> graph.traversal().V().hasLabel("Person").has("age", TextP.regex("3")));
  }

  // ---------------------------------------------------------------------------
  // Edge-property Text parity — the EdgeHopRecogniser type path. A Text
  // predicate on a non-String edge property translates strict and throws like
  // native; on a String edge property it matches native.
  // ---------------------------------------------------------------------------

  /**
   * {@code outE("knows").has(<int edge prop>, containing(...)).inV()} translates the edge filter
   * strict and throws in both pipelines. {@code weight} is {@code INTEGER} on the {@code knows} edge
   * class, so native {@code Text.containing} errors on it and the translated strict {@code
   * CONTAINSTEXT} edge filter throws at the same point — closing the previously untested
   * EdgeHopRecogniser type path end-to-end.
   */
  @Test
  public void edgeContainingNonStringProperty_translatesStrict_andBothThrow() {
    session.createVertexClass("Person");
    var knows = session.createEdgeClass("knows");
    knows.createProperty("weight", PropertyType.INTEGER);
    var a = graph.addVertex(T.label, "Person", "name", "A");
    var b = graph.addVertex(T.label, "Person", "name", "B");
    a.addEdge("knows", b, "weight", 1);
    graph.tx().commit();
    assertTranslatedAndNativeThrow(
        "g.V().outE(knows).has(weight, containing(1)).inV() on an int edge property",
        () -> graph.traversal().V().outE("knows").has("weight", TextP.containing("1")).inV());
  }

  /**
   * {@code outE("knows").has(<String edge prop>, containing(...)).inV()} matches native. {@code note}
   * is {@code STRING} on the {@code knows} edge class, so the strict {@code CONTAINSTEXT} edge filter
   * never throws and returns the same target vertices as native.
   */
  @Test
  public void edgeContainingStringProperty_matchesNative() {
    session.createVertexClass("Person");
    var knows = session.createEdgeClass("knows");
    knows.createProperty("note", PropertyType.STRING);
    var a = graph.addVertex(T.label, "Person", "name", "A");
    var b = graph.addVertex(T.label, "Person", "name", "B");
    a.addEdge("knows", b, "note", "hexnut");
    graph.tx().commit();
    assertEquivalent(
        "g.V().outE(knows).has(note, containing(ex)).inV() on a String edge property",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().outE("knows").has("note", TextP.containing("ex")).inV());
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

  /**
   * Asserts a shape that must error in both pipelines but, unlike a decline, still TRANSLATES: with
   * the translator on it engages exactly one boundary step (the strict node was emitted, not
   * declined) and throws at execution; with the translator off native throws. This is the
   * translate-strict-and-throw contract for a {@code Text} predicate over a non-String value — the
   * two pipelines agree on the error rather than one returning rows.
   */
  private void assertTranslatedAndNativeThrow(
      String scenario, Supplier<GraphTraversal<?, ?>> traversalSupplier) {
    withTranslator(true, () -> {
      var onAdmin = traversalSupplier.get().asAdmin();
      onAdmin.applyStrategies();
      assertThat(countBoundarySteps(onAdmin.getSteps()))
          .as(scenario + " (translator on) must translate to a boundary step, not decline")
          .isEqualTo(1);
      assertThatThrownBy(onAdmin::toList)
          .as(scenario + " (translator on) must throw at execution like native")
          .isInstanceOf(RuntimeException.class);
    });
    withTranslator(false, () -> assertThatThrownBy(() -> traversalSupplier.get().toList())
        .as(scenario + " (native) must throw on a Text predicate over a non-String value")
        .isInstanceOf(RuntimeException.class));
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
