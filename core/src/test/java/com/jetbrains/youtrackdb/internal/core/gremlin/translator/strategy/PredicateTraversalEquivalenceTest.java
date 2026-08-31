package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy.TranslatorEquivalenceSupport.countBoundarySteps;
import static com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy.TranslatorEquivalenceSupport.sortedIds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBTransaction;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.AbstractMatchPlanStep;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.YTDBMatchPlanStep;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy.TranslatorEquivalenceSupport.Cardinality;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy.TranslatorEquivalenceSupport.Recognition;
import com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy.optimization.YTDBGraphStepStrategy;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass.INDEX_TYPE;
import java.util.List;
import java.util.function.Supplier;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.TextP;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.AndStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.HasStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.TraversalFilterStep;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.junit.Test;

/**
 * Translator-on / translator-off equivalence fixture for the predicate surface: {@code
 * has(key, value)}, {@code hasLabel(L)}, {@code hasId(...)}, the {@code has(key)} presence form, and
 * the same-alias AND-composition. Each case runs the same traversal shape twice — translator on, then
 * off — and asserts (a) boundary-step engagement (a RECOGNIZED shape has exactly one {@link
 * AbstractMatchPlanStep} on — normally a {@link YTDBMatchPlanStep} — and none off; a DECLINED shape
 * has none of either kind either way) and (b) result-multiset
 * equality between the two runs. Multiset equality is on sorted RID strings, preserving multiplicity.
 *
 * <p>The {@code hasLabel} cases additionally pin the polymorphism contract on a {@code Person} /
 * {@code Employee} hierarchy: native membership is pinned first with the translator off (leaf-exact
 * under non-polymorphic, hierarchy-aware under polymorphic — the two modes {@code YTDBLabelMatcher}
 * produces), then the translated plan is shown to match under both modes and to narrow the scan by
 * re-typing the boundary node (its plan fetches from the labelled class, not the generic {@code V}).
 */
public class PredicateTraversalEquivalenceTest extends GraphBaseTest {

  private final TranslatorEquivalenceSupport support =
      new TranslatorEquivalenceSupport(this::graphSession);

  /** The alias the walker mints for the root {@code V()} scan — the origin of every hop below it. */
  private static final String ORIGIN_ALIAS = "$g2m_v0";

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
   * A multi-label {@code g.V().hasLabel("Person", "Employee")} in polymorphic mode declines to
   * native: {@code @class IN [Person, Employee]} is leaf-exact and would drop polymorphic subclasses
   * of the listed classes, under-matching native hierarchy-aware {@code hasLabel}. Native still
   * returns a non-empty multiset (the Person and Employee rows), so on==off is not vacuous.
   */
  @Test
  public void hasLabelMultiLabel_declinesPolymorphic() {
    seedPersonEmployeeHierarchy();
    withPolymorphicDefault(true, () -> assertEquivalent(
        "polymorphic g.V().hasLabel(Person, Employee) (multi-label)",
        Recognition.DECLINED,
        () -> graph.traversal().V().hasLabel("Person", "Employee")));
  }

  /**
   * Multi-label {@code hasLabel(Person, Employee)} in non-polymorphic mode matches native leaf-exact
   * semantics: both the {@code Person} and the {@code Employee} vertex, via {@code @class IN}.
   */
  @Test
  public void hasLabelMultiLabel_matchesNativeNonPolymorphic() {
    seedPersonEmployeeHierarchy();
    withPolymorphicDefault(false, () -> assertEquivalent(
        "non-polymorphic g.V().hasLabel(Person, Employee) (multi-label)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().hasLabel("Person", "Employee")));
  }

  /**
   * {@code g.V().hasLabel("Missing")} on a never-used label declines to native and returns empty on
   * both arms. Native resolves the class at execution time (it may be created later in the same
   * transaction), so a translated plan compiled against a schema without the class would diverge;
   * declining keeps on==off. The existing {@code Person} class stays a translated control.
   */
  @Test
  public void hasLabelNonExistentClass_declinesEmpty() {
    seedPersonEmployeeHierarchy();

    assertEquivalent(
        "g.V().hasLabel(Person) (fixture control)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().hasLabel("Person"));

    assertEquivalent(
        "g.V().hasLabel(Missing) (never-used label)",
        Recognition.DECLINED,
        Cardinality.MAY_BE_EMPTY,
        () -> graph.traversal().V().hasLabel("Missing"));
  }

  // ---------------------------------------------------------------------------
  // hasId — single, multi, and the set-membership duplicate case.
  // ---------------------------------------------------------------------------

  /**
   * A BARE {@code g.V().hasId(id)} point-lookup declines to native: a single pinned node with no
   * hop, which native resolves by RID directly with no query, so the translator would compile an
   * uncached MATCH plan for nothing. Declining is trivially on==off; native still returns exactly
   * that vertex. A RID lookup FOLLOWED by a hop still translates.
   */
  @Test
  public void hasIdSingle_declinesBarePointLookup() {
    var alice = graph.addVertex(T.label, "Person", "name", "Alice");
    graph.addVertex(T.label, "Person", "name", "Bob");
    graph.tx().commit();
    var aliceId = alice.id();
    assertEquivalent(
        "g.V().hasId(alice)",
        Recognition.DECLINED,
        () -> graph.traversal().V().hasId(aliceId));
  }

  /** A bare {@code g.V().hasId(id1, id2)} point-lookup declines to native; native returns both. */
  @Test
  public void hasIdMulti_declinesBarePointLookup() {
    var alice = graph.addVertex(T.label, "Person", "name", "Alice");
    var bob = graph.addVertex(T.label, "Person", "name", "Bob");
    graph.addVertex(T.label, "Person", "name", "Carol");
    graph.tx().commit();
    var aliceId = alice.id();
    var bobId = bob.id();
    assertEquivalent(
        "g.V().hasId(alice, bob)",
        Recognition.DECLINED,
        () -> graph.traversal().V().hasId(aliceId, bobId));
  }

  /**
   * A bare {@code g.V().hasId(id, id)} with a repeated id declines to native like every other bare
   * RID point-lookup. Native still applies set membership — it matches the one vertex once — so the
   * decline is on==off.
   */
  @Test
  public void hasIdDuplicate_declinesBarePointLookup() {
    var alice = graph.addVertex(T.label, "Person", "name", "Alice");
    graph.addVertex(T.label, "Person", "name", "Bob");
    graph.tx().commit();
    var aliceId = alice.id();
    assertEquivalent(
        "g.V().hasId(alice, alice) (duplicate id, set membership)",
        Recognition.DECLINED,
        () -> graph.traversal().V().hasId(aliceId, aliceId));
  }

  /**
   * {@code g.V().hasId(<edge rid>)} returns nothing on both pipelines, and its {@code count()}
   * returns zero. A vertex source is scoped to the vertex class, so a RID naming an edge is not a
   * member of it however well-formed the RID is.
   *
   * <p>The scoping is at risk because the planner has a fast path that fetches a pinned RID
   * directly, and the fetch it builds carries either the RID list or the class, never both. A
   * translated {@code g.V()} boundary keeps its whole type constraint in that class, so a
   * promotion the planner has not proved against the class turns the plan into a bare fetch of
   * whatever record the RID names, and the edge is emitted.
   *
   * <p>The {@code count()} half is the sharper of the two. The list half raises when the boundary
   * turns the row into a vertex, which is loud; {@code count()} is answered from the plan without
   * materialising an element, so a widened plan returns one with nothing to notice. This case
   * therefore asserts both.
   *
   * <p>Since a bare {@code g.V().hasId(rid)} point-lookup now DECLINES to native (a single pinned
   * node with no hop — native resolves the RID directly), the vertex-source scoping is enforced by
   * the native pipeline on both arms rather than by a translated plan. This asserts the bare shape
   * declines (zero boundary steps with the translator on) and that both arms return nothing and
   * count zero, so the empty result is the honest answer. The translated pinned-RID fetch's
   * class-scoping (for RID starts that DO translate, i.e. followed by a hop) stays covered by the
   * SQL-layer {@code PromoteStaticRidsFromFiltersTest}.
   */
  @Test
  public void hasIdOverEdgeRid_declinesAndReturnsNothingAndCountsZero() {
    var alice = graph.addVertex(T.label, "Person", "name", "Alice");
    var bob = graph.addVertex(T.label, "Person", "name", "Bob");
    var edge = alice.addEdge("knows", bob);
    graph.tx().commit();
    var edgeId = edge.id();

    withTranslator(true, () -> {
      var admin = graph.traversal().V().hasId(edgeId).asAdmin();
      admin.applyStrategies();
      assertThat(countBoundarySteps(admin.getSteps()))
          .as("a bare g.V().hasId(<edge rid>) point-lookup must decline to native")
          .isZero();
      assertThat(admin.toList())
          .as("a vertex source must not emit a record that is an edge")
          .isEmpty();
      assertThat(graph.traversal().V().hasId(edgeId).count().next())
          .as("the vertex-source count must be scoped to vertices too")
          .isZero();
    });
    withTranslator(false, () -> {
      assertThat(graph.traversal().V().hasId(edgeId).toList())
          .as("native pins the expected answer")
          .isEmpty();
      assertThat(graph.traversal().V().hasId(edgeId).count().next()).isZero();
    });
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
    // A RID-bearing single-node lookup (V(ids).has(...) with no hop) is a bare point-lookup and
    // declines to native, which still AND-composes the id membership with the has filter — only
    // Alice, never Carol. on==off holds; the merge stays covered for translating shapes by
    // hasLabelAndHas_andCompose_onSameAlias below.
    assertEquivalent(
        "g.V(alice, bob).has(age, 30) — only Alice, not every age-30 vertex",
        Recognition.DECLINED,
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

  /** {@code g.V().has("nickname")} translates to {@code nickname IS DEFINED} and matches native: the
   * vertices that carry the property (present with a value or present-null), excluding the vertex
   * that lacks the key entirely. Distinct from {@code IS NULL}, which would also match absent keys.
   */
  @Test
  public void hasKeyPresence_matchesNative() {
    var alice = graph.addVertex(T.label, "Person", "name", "Alice", "nickname", "Al");
    graph.addVertex(T.label, "Person", "name", "Bob", "nickname", "Bobby");
    var carol = graph.addVertex(T.label, "Person", "name", "Carol"); // no nickname key
    var dave = graph.addVertex(T.label, "Person", "name", "Dave");
    dave.property("nickname", null); // present-null — must match has(key), not absent
    graph.tx().commit();
    assertEquivalent(
        "g.V().has(nickname) presence",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().has("nickname"));
    // Sanity: the fixture distinguishes present-null from absent.
    assertThat(alice.id()).isNotEqualTo(carol.id());
  }

  /**
   * {@code g.V().hasNot("nickname")} translates to {@code nickname IS NOT DEFINED} and matches
   * native: vertices that lack the property entirely, excluding present-null and present-value rows.
   */
  @Test
  public void hasNotKeyPresence_matchesNative() {
    graph.addVertex(T.label, "Person", "name", "Alice", "nickname", "Al");
    graph.addVertex(T.label, "Person", "name", "Bob", "nickname", "Bobby");
    var absent = graph.addVertex(T.label, "Person", "name", "Carol");
    var presentNull = graph.addVertex(T.label, "Person", "name", "Dave");
    presentNull.property("nickname", null);
    graph.tx().commit();

    assertEquivalent(
        "g.V().hasNot(nickname) presence",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().hasNot("nickname"));
    assertThat(absent.id()).isNotEqualTo(presentNull.id());
  }

  /**
   * {@code g.V().where(__.has("age", 30))} is equivalent to {@code g.V().has("age", 30)} for the
   * pure-filter sub-traversal shape.
   */
  @Test
  public void wherePureFilterHasAge_matchesNative() {
    graph.addVertex(T.label, "Person", "name", "Alice", "age", 30);
    graph.addVertex(T.label, "Person", "name", "Bob", "age", 25);
    graph.tx().commit();

    assertEquivalent(
        "g.V().where(has(age,30))",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().where(__.has("age", P.eq(30))));
  }

  // ---------------------------------------------------------------------------
  // NULL semantics (A1) and negated absent-property exclusion (A2).
  // ---------------------------------------------------------------------------

  /**
   * Pins native membership for the three {@code nickname} states before asserting translator parity.
   * Fixture: absent key, present-null, present non-null value. Runs translator-off only first so
   * the contract is anchored to native Gremlin, not to a SQL translation guess.
   */
  @Test
  public void nullComparand_nativeMembership_pinnedBeforeEquivalence() {
    var absent = graph.addVertex(T.label, "Person", "name", "Carol");
    var presentNull = graph.addVertex(T.label, "Person", "name", "Dave");
    presentNull.property("nickname", null);
    var presentValue = graph.addVertex(T.label, "Person", "name", "Alice", "nickname", "Al");
    graph.tx().commit();

    // Storage-layer sanity: absent vs present-null must be distinguishable in the fixture.
    assertThat(absent.keys()).doesNotContain("nickname");
    assertThat(presentNull.keys()).contains("nickname");
    assertThat(presentValue.keys()).contains("nickname");

    var hasKeyNative =
        nativeSortedIds(() -> graph.traversal().V().has("nickname"));
    var eqNullNative =
        nativeSortedIds(() -> graph.traversal().V().has("nickname", P.eq(null)));
    var neqNullNative =
        nativeSortedIds(() -> graph.traversal().V().has("nickname", P.neq(null)));

    // Pin table — native Gremlin (translator off). Update only after deliberate semantic change.
    assertThat(hasKeyNative)
        .as("native has(nickname): present-null + present-value, not absent")
        .containsExactlyInAnyOrder(presentNull.id().toString(), presentValue.id().toString());
    assertThat(eqNullNative)
        .as("native has(nickname, eq(null)): pinned contract — run once to record, then lock")
        .containsExactlyInAnyOrder(presentNull.id().toString(), absent.id().toString());
    assertThat(neqNullNative)
        .as("native has(nickname, neq(null)): present non-null only")
        .containsExactly(presentValue.id().toString());

    assertEquivalent(
        "g.V().has(nickname) presence",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().has("nickname"));
    assertEquivalent(
        "g.V().has(nickname, neq(null))",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().has("nickname", P.neq(null)));
    assertEquivalent(
        "g.V().has(nickname, eq(null))",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().has("nickname", P.eq(null)));
  }

  /**
   * {@code has("nickname", neq(null))} matches vertices with a non-null value; absent and
   * present-null rows are excluded by both pipelines (A1).
   */
  @Test
  public void neqNull_excludesAbsentAndNull_matchesNative() {
    var alice = graph.addVertex(T.label, "Person", "name", "Alice");
    alice.property("nickname", null);
    graph.addVertex(T.label, "Person", "name", "Bob"); // absent
    graph.addVertex(T.label, "Person", "name", "Carol", "nickname", "C");
    graph.tx().commit();
    assertEquivalent(
        "g.V().has(nickname, neq(null))",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().has("nickname", P.neq(null)));
  }

  /**
   * {@code has("since", without(...))} excludes vertices lacking the key — a negated membership
   * predicate whose SQL form would be true on absent without the {@code IS DEFINED} guard (A2).
   */
  @Test
  public void without_excludesAbsentProperty_matchesNative() {
    graph.addVertex(T.label, "Person", "name", "Alice", "since", 2000);
    graph.addVertex(T.label, "Person", "name", "Bob"); // absent since
    graph.addVertex(T.label, "Person", "name", "Carol", "since", 1990);
    graph.tx().commit();
    assertEquivalent(
        "g.V().has(since, without(1990))",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().has("since", P.without(1990)));
  }

  /**
   * {@code notContaining} excludes vertices lacking the key — a negated Text form (A2).
   */
  @Test
  public void notContaining_excludesAbsentProperty_matchesNative() {
    graph.addVertex(T.label, "Person", "name", "Alice", "tag", "alpha");
    graph.addVertex(T.label, "Person", "name", "Bob"); // absent tag
    graph.addVertex(T.label, "Person", "name", "Carol", "tag", "beta");
    graph.tx().commit();
    assertEquivalent(
        "g.V().has(tag, notContaining(z))",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().has("tag", TextP.notContaining("z")));
  }

  // ---------------------------------------------------------------------------
  // Range decompositions and singleton-collection decline (D3).
  // ---------------------------------------------------------------------------

  /**
   * {@code between(lo, hi)} is right-exclusive {@code [lo, hi)} — the high bound is excluded.
   */
  @Test
  public void between_isRightExclusive_matchesNative() {
    graph.addVertex(T.label, "Person", "name", "A", "age", 20);
    graph.addVertex(T.label, "Person", "name", "B", "age", 25);
    graph.addVertex(T.label, "Person", "name", "C", "age", 30);
    graph.tx().commit();
    assertEquivalent(
        "g.V().has(age, between(20, 30))",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().has("age", P.between(20, 30)));
  }

  /** {@code inside(lo, hi)} is open on both ends. */
  @Test
  public void inside_openInterval_matchesNative() {
    graph.addVertex(T.label, "Person", "name", "A", "age", 20);
    graph.addVertex(T.label, "Person", "name", "B", "age", 25);
    graph.addVertex(T.label, "Person", "name", "C", "age", 30);
    graph.tx().commit();
    assertEquivalent(
        "g.V().has(age, inside(20, 30))",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().has("age", P.inside(20, 30)));
  }

  /** {@code outside(lo, hi)} matches values below or above the open interval. */
  @Test
  public void outside_complementInterval_matchesNative() {
    graph.addVertex(T.label, "Person", "name", "A", "age", 10);
    graph.addVertex(T.label, "Person", "name", "B", "age", 25);
    graph.addVertex(T.label, "Person", "name", "C", "age", 40);
    graph.tx().commit();
    assertEquivalent(
        "g.V().has(age, outside(20, 30))",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().has("age", P.outside(20, 30)));
  }

  /** Size-1 collection equality normalizes to scalar eq and matches native singleton auto-unbox. */
  @Test
  public void singletonCollectionEq_matchesNative() {
    graph.addVertex(T.label, "Person", "name", "Alice");
    graph.addVertex(T.label, "Person", "name", "Bob");
    graph.tx().commit();
    assertEquivalent(
        "g.V().has(name, eq([Alice])) singleton collection",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().has("name", P.eq(List.of("Alice"))));
  }

  /** Size-2 collection membership via {@code within} translates and matches native. */
  @Test
  public void multiValueWithin_matchesNative() {
    graph.addVertex(T.label, "Person", "name", "A", "age", 30);
    graph.addVertex(T.label, "Person", "name", "B", "age", 40);
    graph.addVertex(T.label, "Person", "name", "C", "age", 50);
    graph.tx().commit();
    assertEquivalent(
        "g.V().has(age, within(30, 40))",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().has("age", P.within(30, 40)));
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
   * Polymorphic {@code hasLabel(Person).has(age, containing(...))} on a subclass-only {@code INTEGER}
   * property: {@code age} is declared on {@code Employee} only. In polymorphic mode {@code
   * hasLabel("Person")} is hierarchy-aware, so the {@code Employee} row reaches the predicate. The
   * adapter emits strict {@code CONTAINSTEXT}; the strict node throws on the {@code Integer} {@code
   * age} exactly where native throws — no subclass type sweep or whole-traversal decline is
   * involved because Text predicates no longer gate on declared type.
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
   * {@code endingWith} on a declared String property translates and matches native.
   */
  @Test
  public void endingWithDeclaredString_matchesNative() {
    var person = session.createVertexClass("Person");
    person.createProperty("name", PropertyType.STRING);
    graph.addVertex(T.label, "Person", "name", "Alice");
    graph.addVertex(T.label, "Person", "name", "Bob");
    graph.tx().commit();
    assertEquivalent(
        "g.V().hasLabel(Person).has(name, endingWith(ce))",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().hasLabel("Person").has("name", TextP.endingWith("ce")));
  }

  /**
   * {@code regex} on a declared String property translates (find-mode) and matches native.
   */
  @Test
  public void regexDeclaredString_matchesNative() {
    var person = session.createVertexClass("Person");
    person.createProperty("name", PropertyType.STRING);
    graph.addVertex(T.label, "Person", "name", "Alice");
    graph.addVertex(T.label, "Person", "name", "Bob");
    graph.tx().commit();
    assertEquivalent(
        "g.V().hasLabel(Person).has(name, regex(li.*))",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().hasLabel("Person").has("name", TextP.regex("li.*")));
  }

  /**
   * {@code notStartingWith} on a declared String property translates and matches native.
   */
  @Test
  public void notStartingWithDeclaredString_matchesNative() {
    var person = session.createVertexClass("Person");
    person.createProperty("name", PropertyType.STRING);
    graph.addVertex(T.label, "Person", "name", "Alice");
    graph.addVertex(T.label, "Person", "name", "Bob");
    graph.tx().commit();
    assertEquivalent(
        "g.V().hasLabel(Person).has(name, notStartingWith(Al))",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().hasLabel("Person").has("name", TextP.notStartingWith("Al")));
  }

  /**
   * {@code notEndingWith} on a declared String property translates and matches native.
   */
  @Test
  public void notEndingWithDeclaredString_matchesNative() {
    var person = session.createVertexClass("Person");
    person.createProperty("name", PropertyType.STRING);
    graph.addVertex(T.label, "Person", "name", "Alice");
    graph.addVertex(T.label, "Person", "name", "Bob");
    graph.tx().commit();
    assertEquivalent(
        "g.V().hasLabel(Person).has(name, notEndingWith(ce))",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().hasLabel("Person").has("name", TextP.notEndingWith("ce")));
  }

  /**
   * {@code notRegex} on a declared String property translates and matches native.
   */
  @Test
  public void notRegexDeclaredString_matchesNative() {
    var person = session.createVertexClass("Person");
    person.createProperty("name", PropertyType.STRING);
    graph.addVertex(T.label, "Person", "name", "Alice");
    graph.addVertex(T.label, "Person", "name", "Bob");
    graph.tx().commit();
    assertEquivalent(
        "g.V().hasLabel(Person).has(name, notRegex(li.*))",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().hasLabel("Person").has("name", TextP.notRegex("li.*")));
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
  // Logical OR over hasLabel+has — polymorphic re-type fold.
  // ---------------------------------------------------------------------------

  /**
   * Polymorphic {@code or(hasLabel(Person).has(age,30), hasLabel(Company).has(age,40))} must match
   * native: each OR arm keeps its label discrimination. Without folding the child's {@code hasLabel}
   * re-type into the OR operand as {@code classEquals}, the translated WHERE would be roughly
   * {@code (age=30) OR (age=40)} and wrongly admit cross-label rows.
   */
  @Test
  public void polymorphicOrHasLabelPlusHas_matchesNative() {
    session.createVertexClass("Person");
    session.createVertexClass("Company");
    graph.addVertex(T.label, "Person", "name", "Alice", "age", 30);
    graph.addVertex(T.label, "Person", "name", "Bob", "age", 40); // age matches Company arm — must exclude
    graph.addVertex(T.label, "Company", "name", "Acme", "age", 40);
    graph.addVertex(T.label, "Company", "name", "Beta", "age", 30); // age matches Person arm — must exclude
    graph.tx().commit();

    withPolymorphicDefault(true, () -> assertEquivalent(
        "polymorphic or(hasLabel(Person).has(age,30), hasLabel(Company).has(age,40))",
        Recognition.RECOGNIZED,
        () -> graph
            .traversal()
            .V()
            .or(
                __.hasLabel("Person").has("age", P.eq(30)),
                __.hasLabel("Company").has("age", P.eq(40)))));
  }

  // ---------------------------------------------------------------------------
  // not(hop) inside another connective. A not(hop) becomes a detached anti-join
  // that the planner applies conjunctively over the whole match, so the shape is
  // translatable exactly where its surroundings are a conjunction. These cases pin
  // both directions: the disjunction must decline, and the conjunctive spellings
  // must keep translating. The failure mode on this surface is UNDER-emission —
  // a leaked anti-join drops rows that passed a sibling arm — so each case names
  // the row the fixture supplies for the leak to lose.
  // ---------------------------------------------------------------------------

  /**
   * {@code g.V().or(__.not(__.out("a")).has("name", "x"), __.has("age", 30))} declines and returns
   * native's {@code [x, y]}.
   *
   * <p>The arm's anti-join has only one destination — the plan's top-level {@code
   * notMatchExpressions} sink, which the planner applies over the whole match. Composing the arm's
   * remaining operand into the OR and letting the anti-join travel there reads the query as
   * {@code (no out-a) AND (name = x OR age = 30)}, which drops {@code y}: {@code y} has an
   * {@code a} edge and passes only on the second arm. The arm has no hop of its own — the hop is
   * inside the {@code not} — so the OR cannot see it through the edge-bearing check and has to read
   * the captured anti-join.
   */
  @Test
  public void orArmWithEdgeBearingNot_declinesAndMatchesNative() {
    seedAntiJoinDisjunction();
    Supplier<GraphTraversal<?, ?>> traversal =
        () -> graph.traversal().V()
            .or(__.not(__.out("a")).has("name", P.eq("x")), __.has("age", P.eq(30)));

    assertThat(nativeSortedIds(() -> graph.traversal().V().has("age", P.eq(30)).out("a")))
        .as("the fixture must hold a vertex that passes only the second arm and has an out-a edge, "
            + "or a leaked anti-join loses nothing and this case witnesses nothing")
        .hasSize(1);
    assertThat(namesOf(traversal, false))
        .as("native reads the disjunction arm-wise: x passes the first arm, y the second")
        .containsExactly("x", "y");
    assertEquivalent(
        "g.V().or(not(out(a)).has(name, x), has(age, 30))", Recognition.DECLINED, traversal);
  }

  /**
   * The same {@code not(__.out("a"))} arm inside an AND keeps translating and matches native's
   * {@code [t, x]} with one boundary step. The arm's anti-join reaches the plan sink through the
   * connective's commit path, which is conjunctive and therefore agrees with how the planner reads
   * the sink.
   *
   * <p>Paired with the OR case above on purpose. The decline there is scoped to the disjunction, and
   * a gate that declined every {@code not(hop)} inside a connective would take this shape — plus the
   * one below — down with it while fixing nothing.
   *
   * <p>The barrier keeps the case honest. {@code InlineFilterStrategy} unwraps a plain
   * {@code and(not(out(a)), has(age, 1))} into a bare {@code NotStep} plus a {@code HasStep}, which
   * the walker handles at top level and never routes through a captured child at all; the barrier
   * blocks the unwrap and is transparent to the walker, so the arm really is a captured child. That
   * is a claim about the traversal, so {@link #assertConnectiveReachesTheTranslator} observes it
   * before the equivalence runs — without it the case would stay green on the top-level path and
   * witness nothing about the commit path it exists for. The {@code xa} vertex is what the two
   * readings disagree on: it is aged 1 but has an {@code a} edge, so a commit path that dropped the
   * captured anti-join would admit it.
   */
  @Test
  public void andArmWithEdgeBearingNot_matchesNative() {
    seedAntiJoinDisjunction();
    Supplier<GraphTraversal<?, ?>> traversal =
        () -> graph.traversal().V()
            .and(__.not(__.out("a")).barrier(), __.has("age", P.eq(1)));

    assertConnectiveReachesTheTranslator(
        "g.V().and(not(out(a)).barrier(), has(age, 1))", AndStep.class, traversal);
    assertEquivalent(
        "g.V().and(not(out(a)).barrier(), has(age, 1))", Recognition.RECOGNIZED, traversal);
  }

  /**
   * {@code g.V().where(__.not(__.out("a")).barrier())} keeps translating and matches native's
   * {@code [t, x]}. A positive {@code where} is conjunctive with the rest of the match, the same
   * reading the plan-level anti-join sink gives, so the captured expression is forwarded rather than
   * declined. The barrier is there for the reason given on the AND case above — without it the
   * {@code where} collapses to a bare {@code NotStep} and the captured-child path is never taken —
   * and the wrapper assertion is what observes that rather than asserting it in prose. The wrapper
   * here is a {@code TraversalFilterStep}, the class {@code where(traversal)} produces when the
   * child carries no scope label.
   */
  @Test
  public void whereWithEdgeBearingNot_matchesNative() {
    seedAntiJoinDisjunction();
    Supplier<GraphTraversal<?, ?>> traversal =
        () -> graph.traversal().V().where(__.not(__.out("a")).barrier());

    assertConnectiveReachesTheTranslator(
        "g.V().where(not(out(a)).barrier())", TraversalFilterStep.class, traversal);
    assertEquivalent(
        "g.V().where(not(out(a)).barrier())", Recognition.RECOGNIZED, traversal);
  }

  /**
   * {@code g.V().not(__.not(__.out("a")).has("name", "x"))} declines and matches native's
   * {@code [t, x, y]} — every vertex except the one that has no {@code a} edge and is named
   * {@code x}.
   *
   * <p>A detached anti-join cannot be negated again. The outer {@code not} either wraps a single
   * boundary WHERE or builds one anti-join from the child's captured pattern, and the inner
   * expression fits neither, so accepting would negate the {@code name = x} half and drop the
   * anti-join. That reading answers {@code NOT(name = x)}, which loses {@code xa} — same name, but
   * it has an {@code a} edge, so native's inner filter never selects it and the outer {@code not}
   * keeps it.
   */
  @Test
  public void nestedNotOverEdgeBearingChild_declinesAndMatchesNative() {
    seedAntiJoinDisjunction();
    Supplier<GraphTraversal<?, ?>> traversal =
        () -> graph.traversal().V().not(__.not(__.out("a")).has("name", P.eq("x")));

    assertThat(namesOf(traversal, false))
        .as("native drops only the a-edgeless x; the a-bearing namesake survives, and it is the "
            + "row a NOT(name = x) reading would lose")
        .containsExactly("t", "x", "y");
    assertEquivalent(
        "g.V().not(not(out(a)).has(name, x))", Recognition.DECLINED, traversal);
  }

  // ---------------------------------------------------------------------------
  // Predicates on a non-root alias. A predicate after a hop constrains the hop's
  // target, which is a second pattern alias; only the alias the planner picks as
  // root has its filter read from the plan inputs. Two arms of that rule still
  // translate and are pinned here: the predicate applied directly after the hop,
  // and the one on a not(...) sub-traversal. Each returns an over-large multiset
  // when the constraint is dropped, never an error, which is why they are
  // equivalence cases rather than structural assertions. The union-child arm lives
  // in
  // UnionTraversalEquivalenceTest#unionChildPostHopFilter_returnsSameMultisetAsNative,
  // which is where the fork's own boundary-step accounting already is.
  //
  // The where(...)-fragment cases share the section for shape reasons only. All of
  // them now decline on the edge-bearing filter gate and build no plan, so what
  // they witness is the decline and the native multiset, not the binding rule.
  // ---------------------------------------------------------------------------

  /**
   * {@code g.V(marko).out().has("name", "vadas")} returns the one named target, matching native. The
   * pinned single-RID origin wins root selection, so the predicate lands on the hop's target alias.
   * Marko has three out-neighbours, so dropping the target's predicate returns all three.
   */
  @Test
  public void postHopHas_pinnedOrigin_matchesNative() {
    var modern = ModernGraphFixture.seed(graph, session);
    var markoId = modern.marko().id();

    Supplier<GraphTraversal<?, ?>> traversal =
        () -> graph.traversal().V(markoId).out().has("name", "vadas");

    assertRootsAtOrigin("g.V(marko).out().has(name, vadas)", traversal);
    assertEquivalent("g.V(marko).out().has(name, vadas)", Recognition.RECOGNIZED, traversal);
  }

  /**
   * {@code g.V().has("name", "marko").out().has("name", "vadas")} returns the one named target,
   * matching native. Same defect on a property-filtered rather than RID-pinned origin: the origin's
   * own predicate is honoured (it roots the plan), the target's is the one at risk.
   */
  @Test
  public void postHopHas_filteredOrigin_matchesNative() {
    ModernGraphFixture.seed(graph, session);

    assertEquivalent(
        "g.V().has(name, marko).out().has(name, vadas)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().has("name", "marko").out().has("name", "vadas"));
  }

  /**
   * {@code g.V(marko, josh).where(__.out().has("name", "vadas"))} declines to the native pipeline
   * and returns native's {@code [marko]}: marko has an out-neighbour named vadas and josh does not.
   *
   * <p>The shape used to translate, with the fragment's hop appended to the positive pattern. That
   * reading is a join, so it agreed with native only while at most one target matched the
   * fragment's predicate — exactly one of marko's three out-neighbours is named vadas, which is why
   * the case was green. The sibling case below drives the same fragment with a predicate that
   * matches two, and it is that one that would have failed.
   *
   * <p>The result assertion is what keeps this case honest. For a declined expectation {@link
   * #assertEquivalent} compares two runs that both execute natively, so its multiset equality holds
   * however the fixture drifts; pinning {@code [marko]} is the only assertion here that a seed
   * change can break. The name no longer says {@code pinnedOrigin} because root selection does not
   * run for a shape that builds no plan.
   */
  @Test
  public void whereFragmentPostHopFilter_singleMatchingTarget_declinesAndMatchesNative() {
    var modern = ModernGraphFixture.seed(graph, session);
    var markoId = modern.marko().id();
    var joshId = modern.josh().id();

    Supplier<GraphTraversal<?, ?>> traversal =
        () -> graph.traversal().V(markoId, joshId).where(__.out().has("name", "vadas"));

    assertThat(nativeSortedIds(traversal))
        .as("marko has an out-neighbour named vadas and josh does not")
        .containsExactly(markoId.toString());
    assertEquivalent(
        "g.V(marko, josh).where(out().has(name, vadas))", Recognition.DECLINED, traversal);
  }

  /**
   * The same {@code where(...)} fragment with a predicate matching <em>two</em> of marko's
   * out-neighbours returns marko once, from native and from the translator-on run alike, because the
   * edge-bearing filter declines.
   *
   * <p>This is the case that pins the decline's purpose. Appending the fragment's hop makes the
   * translation a join that emits one row per matching path, so before the decline this returned
   * marko twice while native returned it once — the same element set, a wrong multiset. Both
   * candidate repairs were unsound: {@code RETURN DISTINCT} also collapses the path multiplicity a
   * prefix hop legitimately produces, and a captured sub-walk cannot express result shaping at all.
   */
  @Test
  public void whereFragmentWithSeveralMatchingTargets_declinesAndMatchesNative() {
    var modern = ModernGraphFixture.seed(graph, session);
    var markoId = modern.marko().id();
    Supplier<GraphTraversal<?, ?>> traversal =
        () -> graph.traversal().V(markoId).where(__.out().hasLabel("Person"));

    var translated = translatedSortedIds(traversal);
    var nativeIds = nativeSortedIds(traversal);

    assertThat(nativeSortedIds(() -> graph.traversal().V(markoId).out().hasLabel("Person")))
        .as("the fixture must fan out — with one matching out-neighbour the filter reading and "
            + "the join reading agree and this case witnesses nothing")
        .hasSize(2);
    assertThat(nativeIds)
        .as("native where(...) is a filter — marko passes once")
        .containsExactly(markoId.toString());
    assertThat(translated)
        .as("marko has two Person out-neighbours (vadas and josh), so a join reading would emit "
            + "him twice; the decline keeps the native multiset")
        .containsExactly(markoId.toString());
    assertEquivalent(
        "g.V(marko).where(out().hasLabel(Person))", Recognition.DECLINED, traversal);
  }

  /**
   * {@code g.V(marko).out("knows").where(__.out("created"))} declines and returns native's
   * {@code [josh]}. The {@code where} sits on a hop target rather than on the scan origin, which is
   * the shape that rules out the {@code RETURN DISTINCT} repair: the RETURN column is the hop's
   * target, and deduplicating it would also collapse the duplicates a prefix hop legitimately
   * produces when several sources reach the same target. Josh has two {@code created} edges, so a
   * join reading emits him twice where native emits him once.
   */
  @Test
  public void wherePostHop_edgeBearingChild_declinesAndMatchesNative() {
    var modern = ModernGraphFixture.seed(graph, session);
    var markoId = modern.marko().id();
    var joshId = modern.josh().id();

    Supplier<GraphTraversal<?, ?>> traversal =
        () -> graph.traversal().V(markoId).out("knows").where(__.out("created"));

    assertThat(nativeSortedIds(() -> graph.traversal().V(joshId).out("created")))
        .as("the fixture must fan out — josh created two things, so a join reading emits him twice")
        .hasSize(2);
    assertThat(nativeSortedIds(traversal))
        .as("native: josh is marko's only knows-neighbour that created something, and he passes once")
        .containsExactly(joshId.toString());
    assertEquivalent(
        "g.V(marko).out(knows).where(out(created))", Recognition.DECLINED, traversal);
  }

  /**
   * The same shape with a {@code values("name")} tail — the spelling that first surfaced the
   * over-emission outside this suite — must yield josh's name once, not once per {@code created}
   * edge. Split from the element-returning case above rather than appended to it: the two differ in
   * return type (the projection needs a string drain, because the id helpers cast to {@code
   * Vertex}), and bundled they would report a projection failure under a method name that mentions
   * no projection, and would not run at all if the first half failed.
   */
  @Test
  public void wherePostHop_edgeBearingChild_valuesTail_matchesNative() {
    var modern = ModernGraphFixture.seed(graph, session);
    var markoId = modern.marko().id();
    var joshId = modern.josh().id();

    assertThat(nativeSortedIds(() -> graph.traversal().V(joshId).out("created")))
        .as("the fixture must fan out — josh created two things, so a join reading emits him twice")
        .hasSize(2);

    Supplier<GraphTraversal<?, ?>> projected =
        () -> graph.traversal().V(markoId).out("knows").where(__.out("created")).values("name");
    assertThat(drainAsStrings(projected, true))
        .as("g.V(marko).out(knows).where(out(created)).values(name): translator-on must agree "
            + "with native, which yields josh's name once and not once per created-edge")
        .isEqualTo(drainAsStrings(projected, false))
        .containsExactly("josh");
  }

  /**
   * {@code g.V().as("a").out("knows").where(__.as("a").has("age", 30))} declines and returns
   * native's {@code [Bob]}. The {@code as("a")} names the scan origin, and the {@code where} child
   * runs from that binding rather than from the hop target the cursor is sitting on. Translating it
   * would key the {@code age} predicate on Bob (40) instead of Alice (30) and return nothing, so
   * the shape has to stay native until the walker resolves a scope label to its alias.
   *
   * <p>The result assertion is the discriminating one: a decline expectation alone would also hold
   * if the shape started translating <em>correctly</em>, while {@code [Bob]} is exactly what the
   * wrong-alias reading loses.
   */
  @Test
  public void pathScopedWhereAfterHop_declinesAndMatchesNative() {
    var alice = graph.addVertex(T.label, "Person", "name", "Alice", "age", 30);
    var bob = graph.addVertex(T.label, "Person", "name", "Bob", "age", 40);
    alice.addEdge("knows", bob);
    graph.tx().commit();

    Supplier<GraphTraversal<?, ?>> traversal =
        () -> graph.traversal().V().as("a").out("knows").where(__.as("a").has("age", P.eq(30)));

    assertThat(namesOf(traversal, false))
        .as("native applies the scoped predicate to Alice, so her knows-target passes")
        .containsExactly("Bob");
    assertEquivalent(
        "g.V().as(a).out(knows).where(as(a).has(age, 30))", Recognition.DECLINED, traversal);
  }

  /**
   * {@code g.V().as("a").out().where(P.neq("a"))} returns every out-neighbour that is not its own
   * source, matching native. The back-reference is the interesting part: {@code where(P)} emits a
   * {@code $matched.<x>} accessor, and the {@code $matched} row the executor builds is keyed on
   * pattern aliases, never on the user's Gremlin {@code as(...)} label. The label therefore has to
   * be resolved through the walker's label-to-alias map before the accessor is built.
   *
   * <p>The shape reaches the executor only because the {@code where} lands on the hop's target,
   * which is not the plan root — an accessor that resolved to nothing would keep or drop every
   * candidate depending on how the comparison treats a missing operand, and either way the multiset
   * would move. The modern graph has no self-loops, so a correct translation returns all six
   * out-edge targets and an accessor that silently matched everything would return none.
   */
  @Test
  public void postHopBackReferenceToOriginLabel_matchesNative() {
    ModernGraphFixture.seed(graph, session);

    assertEquivalent(
        "g.V().as(a).out().where(neq(a))",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().as("a").out().where(P.neq("a")));
  }

  /**
   * {@code g.V().not(__.out().hasLabel("Person"))} returns the five vertices that have no outgoing
   * edge to a {@code Person}, matching native. A NOT sub-traversal is emitted as a detached anti-join
   * expression whose path items are copied, and the class constraint has to be bound onto the copy —
   * under polymorphic mode {@code hasLabel} puts the class nowhere else, so a class-dropped
   * translation degenerates to {@code not(out())} and returns only the three vertices with no
   * out-edges at all.
   *
   * <p>The discriminating fixture property is that josh and peter have out-edges but none to a
   * {@code Person}. In a graph whose only edge-bearing vertex points at a {@code Person} the two
   * forms return the same rows and this case would pass without witnessing anything.
   */
  @Test
  public void notChildTargetLabel_matchesNative() {
    ModernGraphFixture.seed(graph, session);

    assertEquivalent(
        "g.V().not(out().hasLabel(Person))",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().not(__.out().hasLabel("Person")));
  }

  // ---------------------------------------------------------------------------
  // as(...) labels sitting on the filter step.
  //
  // TinkerPop's FilterRankingStrategy moves a user as(...) label forward off the
  // step it was written on and onto the following filter, because a filter does
  // not transform the traverser — the labelled element is the same one either
  // side of it. It is an OptimizationStrategy, so it has already run by the time
  // the translator walks the list, and HasStepRecogniser therefore sees labels
  // the user wrote somewhere else. Where the label ends up, not where it was
  // written, decides whether the walk can resolve it.
  // ---------------------------------------------------------------------------

  /**
   * The premise the whole section rests on: with the YouTrackDB strategies stood down, applying the
   * standard strategy list to {@code g.V().as("a").has("name", "Alice")} leaves the {@code a} label
   * on the {@code HasStep} and the {@code GraphStep} bare. Pinning it against the post-strategy step
   * list rather than the authored traversal is what separates the two spellings below — read as
   * written they differ, and by the time any recogniser runs they are the same list.
   */
  @Test
  public void filterRankingStrategy_movesTheStartStepLabelOntoTheHasStep() {
    seedAliceAndBob();

    var admin = graph.traversal()
        .withoutStrategies(GremlinToMatchStrategy.class, YTDBGraphStepStrategy.class)
        .V().as("a").has("name", "Alice").select("a").asAdmin();
    admin.applyStrategies();

    var hasSteps = admin.getSteps().stream().filter(HasStep.class::isInstance).toList();
    assertThat(hasSteps)
        .as("the has(name, Alice) filter must survive as its own step for the relocation to land on")
        .hasSize(1);
    assertThat(hasSteps.getFirst().getLabels())
        .as("FilterRankingStrategy relocates the user label forward onto the filter step")
        .containsExactly("a");
    assertThat(admin.getStartStep().getLabels())
        .as("and takes it off the step the user wrote it on")
        .isEmpty();
  }

  /**
   * {@code g.V().has("name", "Alice").as("a").select("a")} — the label written on the filter step
   * itself — translates and returns native's multiset. Before {@code HasStepRecogniser} bound step
   * labels the walk declined here, because {@code SelectOneStepRecogniser} could not resolve
   * {@code a} to any pattern alias.
   */
  @Test
  public void labelOnPropertyHas_translatesAndMatchesNative() {
    seedAliceAndBob();

    assertEquivalent(
        "g.V().has(name, Alice).as(a).select(a)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().has("name", "Alice").as("a").select("a"));
  }

  /**
   * The same shape with the label written on the start step instead —
   * {@code g.V().as("a").has("name", "Alice").select("a")}. The case above and this one are the same
   * post-strategy list (see {@link #filterRankingStrategy_movesTheStartStepLabelOntoTheHasStep}), so
   * both are fixed by the one bind and neither is fixed by anything on the start step:
   * {@code StartStepRecogniser} already bound its own labels and the label is no longer there.
   */
  @Test
  public void labelWrittenOnStartStepThenRelocated_translatesAndMatchesNative() {
    seedAliceAndBob();

    assertEquivalent(
        "g.V().as(a).has(name, Alice).select(a)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().as("a").has("name", "Alice").select("a"));
  }

  /**
   * {@code hasLabel} carries a relocated label the same way, and the re-typed boundary node is still
   * the node the label binds to: {@code g.V().hasLabel("Person").as("a").select("a")} returns the
   * three {@code Person} vertices on both arms, not the {@code Software} one.
   */
  @Test
  public void labelOnHasLabel_translatesAndMatchesNative() {
    ModernGraphFixture.seed(graph, session);

    assertEquivalent(
        "g.V().hasLabel(Person).as(a).select(a)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().hasLabel("Person").as("a").select("a"));
  }

  /**
   * Bind-or-decline: the same user label reaching two different pattern aliases declines the whole
   * walk rather than silently overwriting the first binding.
   * {@code g.V().as("a").out("knows").has("name", "bob").as("a")} binds {@code a} to the origin at
   * the start step and then asks the filter step to rebind it to the hop target, which
   * {@code RecognitionContext#bindStepLabels} refuses.
   *
   * <p><b>The decline closes a measured wrong answer rather than guarding a hypothetical one.</b>
   * Before the bind existed the second {@code as("a")} was dropped, so {@code select("a")} resolved
   * to the origin alias the start step had bound and the shape translated: measured on this fixture
   * it returned {@code [alice]} with the translator on against native's {@code [bob]}, because
   * {@code select} reads {@code Pop.last}. The name assertion below pins the answer the decline
   * preserves, and would have failed on the translated arm before the change.
   *
   * <p>The control beside it is the same shape with the second label renamed, and it is what makes
   * the decline attributable: a declined shape runs the native pipeline on both arms, so the row
   * comparison inside {@code assertEquivalent} cannot fail whatever the fixture holds and the
   * engagement count is the only live assertion. The control shows the fixture translates when the
   * one thing under test — the label collision — is removed.
   */
  @Test
  public void sameLabelOnTwoAliases_declines_whileDistinctLabelsTranslate() {
    seedAliceKnowsBobAndCarol();
    Supplier<GraphTraversal<?, ?>> colliding =
        () -> graph.traversal().V().as("a").out("knows").has("name", "bob").as("a").select("a");

    assertEquivalent(
        "g.V().as(a).out(knows).has(name, bob).as(a).select(a)",
        Recognition.DECLINED,
        colliding);
    assertThat(namesOf(colliding, true))
        .as("select reads Pop.last, so the surviving answer is the hop target and not the origin")
        .containsExactly("bob");

    assertEquivalent(
        "g.V().as(a).out(knows).has(name, bob).as(b).select(b)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().as("a").out("knows").has("name", "bob").as("b").select("b"));
  }

  /**
   * <b>The one shape in this suite where the two arms must disagree, and the translated arm is the
   * right one.</b> {@code g.V().out("knows").hasLabel("Person").as("a").select("a")} returns
   * {@code []} natively: {@code YTDBGraphStepStrategy.rebuildTraversal}'s {@code else} branch — a
   * {@code HasStep} that does not directly follow the {@code GraphStep} — replaces it with a
   * {@code YTDBHasLabelStep} carrying the extracted {@code ~label} predicates and copies no labels
   * onto the replacement, so {@code select("a")} finds no {@code a} in the path and drops every
   * traverser. That defect is pre-existing on {@code develop} and has nothing to do with the
   * translator, which is why this case is asserted against a hand-computed oracle instead of against
   * the native arm, and why it is not routed through {@code assertEquivalent} — that driver treats
   * any disagreement as a failure.
   *
   * <p>Three assertions, one claim each. The translated arm engages a boundary step and returns the
   * two {@code Person} sinks the oracle names. The native arm returns nothing, which records the
   * defect rather than asserting it is acceptable. The label-free control run natively returns the
   * same two sinks, which is what proves the empty native answer comes from the dropped label and
   * not from a fixture that seeded nothing.
   */
  @Test
  public void labelBehindAHop_translatedArmIsCorrectWhereTheNativeArmDropsIt() {
    var seeded = seedAliceKnowsBobAndCarol();
    var oracle = sortedIds(List.of(seeded.bob(), seeded.carol()));

    withTranslator(true, () -> {
      var admin = graph.traversal()
          .V().out("knows").hasLabel("Person").as("a").select("a").asAdmin();
      admin.applyStrategies();
      assertThat(countBoundarySteps(admin.getSteps()))
          .as("the relocated label binds, so the walk translates rather than declining")
          .isEqualTo(1);
      assertThat(sortedIds(admin.toList()))
          .as("the translated arm answers the hand-computed oracle — the two Person sinks")
          .isEqualTo(oracle);
    });

    withTranslator(false, () -> {
      assertThat(sortedIds(
          graph.traversal().V().out("knows").hasLabel("Person").as("a").select("a").toList()))
          .as("native drops the label in rebuildTraversal's else branch, so select(a) empties the "
              + "stream — the develop defect this divergence is bounded to")
          .isEmpty();
      assertThat(sortedIds(graph.traversal().V().out("knows").hasLabel("Person").toList()))
          .as("control: the same native filter without the label returns the two sinks, so the "
              + "empty answer above is the label drop and not an empty fixture")
          .isEqualTo(oracle);
    });
  }

  // ---------------------------------------------------------------------------
  // Fixture + assertion helpers.
  // ---------------------------------------------------------------------------

  /** Two {@code Person} vertices, one of them named Alice, for the single-filter label cases. */
  private void seedAliceAndBob() {
    graph.addVertex(T.label, "Person", "name", "Alice");
    graph.addVertex(T.label, "Person", "name", "Bob");
    graph.tx().commit();
  }

  /** The three vertices and one non-{@code Person} sink the behind-a-hop label cases share. */
  private record KnowsFixture(Vertex alice, Vertex bob, Vertex carol) {
  }

  /**
   * Alice knows Bob and Carol (both {@code Person}) and knows one {@code Software} vertex. The
   * {@code Software} sink is what keeps {@code hasLabel("Person")} discriminating — without it a
   * translation that dropped the class constraint would return the same rows.
   */
  private KnowsFixture seedAliceKnowsBobAndCarol() {
    session.createVertexClass("Person");
    session.createVertexClass("Software");
    var alice = graph.addVertex(T.label, "Person", "name", "alice");
    var bob = graph.addVertex(T.label, "Person", "name", "bob");
    var carol = graph.addVertex(T.label, "Person", "name", "carol");
    var widget = graph.addVertex(T.label, "Software", "name", "widget");
    alice.addEdge("knows", bob);
    alice.addEdge("knows", carol);
    alice.addEdge("knows", widget);
    graph.tx().commit();
    return new KnowsFixture(alice, bob, carol);
  }

  /**
   * Seeds the fixture the {@code not(hop)}-in-a-connective cases share. Each vertex is there to
   * separate one reading from another:
   *
   * <ul>
   *   <li>{@code y} has an {@code a} edge and is the only one aged 30, so it passes the OR's second
   *       arm alone — it is the row a leaked anti-join drops.
   *   <li>{@code x} has no {@code a} edge and is the row the first arm selects.
   *   <li>{@code t} passes neither arm.
   *   <li>{@code xa} shares {@code x}'s name but has an {@code a} edge, so a translation that kept
   *       the {@code name = x} half of a nested {@code not} and lost its anti-join answers
   *       differently from native on it.
   * </ul>
   */
  private void seedAntiJoinDisjunction() {
    var y = graph.addVertex(T.label, "Person", "name", "y", "age", 30);
    var t = graph.addVertex(T.label, "Person", "name", "t", "age", 1);
    graph.addVertex(T.label, "Person", "name", "x", "age", 1);
    var xa = graph.addVertex(T.label, "Person", "name", "x", "age", 1);
    y.addEdge("a", t);
    xa.addEdge("a", t);
    graph.tx().commit();
  }

  /** Sorted {@code name} values of the traversal's vertices, run with the translator forced to
   *  {@code enabled}. Names read better than RIDs in the {@code not(hop)} cases, whose fixture
   *  names each vertex after the arm it passes. */
  private List<String> namesOf(Supplier<GraphTraversal<?, ?>> traversalSupplier, boolean enabled) {
    var original = translatorEnabled();
    try {
      setTranslatorEnabled(enabled);
      return traversalSupplier.get().toList().stream()
          .map(v -> ((Vertex) v).<Object>value("name").toString())
          .sorted()
          .toList();
    } finally {
      setTranslatorEnabled(original);
    }
  }

  /** Seeds one {@code Person} and one {@code Employee} (a subclass of {@code Person}). */
  private void seedPersonEmployeeHierarchy() {
    var person = session.createVertexClass("Person");
    session.getSchema().createClass("Employee", person);
    graph.addVertex(T.label, "Person", "name", "Alice");
    graph.addVertex(T.label, "Employee", "name", "Eve");
    graph.tx().commit();
  }

  /** Runs {@code traversalSupplier} with the translator on and returns sorted vertex id strings. */
  private List<String> translatedSortedIds(Supplier<GraphTraversal<?, ?>> traversalSupplier) {
    var original = translatorEnabled();
    try {
      setTranslatorEnabled(true);
      return sortedIds(traversalSupplier.get().toList());
    } finally {
      setTranslatorEnabled(original);
    }
  }

  /** Runs {@code traversalSupplier} with the translator off and returns sorted vertex id strings. */
  private List<String> nativeSortedIds(Supplier<GraphTraversal<?, ?>> traversalSupplier) {
    var original = translatorEnabled();
    try {
      setTranslatorEnabled(false);
      return sortedIds(traversalSupplier.get().toList());
    } finally {
      setTranslatorEnabled(original);
    }
  }

  /**
   * Runs {@code traversalSupplier}'s shape with the translator enabled and again disabled, asserting
   * boundary-step engagement (per {@code expected}) and result-multiset equality between the runs.
   */
  private void assertEquivalent(
      String scenario, Recognition expected, Supplier<GraphTraversal<?, ?>> traversalSupplier) {
    assertEquivalent(scenario, expected, Cardinality.NON_EMPTY, traversalSupplier);
  }

  /**
   * As above, with the anti-vacuity guard's cardinality expectation stated explicitly. See {@link
   * Cardinality}.
   */
  private void assertEquivalent(
      String scenario,
      Recognition expected,
      Cardinality cardinality,
      Supplier<GraphTraversal<?, ?>> traversalSupplier) {
    support.assertEquivalent(
        scenario,
        expected,
        cardinality,
        TranslatorEquivalenceSupport::sortedIds,
        traversalSupplier);
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

  /**
   * Asserts the shape still carries {@code wrapper} at the top level once TinkerPop's optimisation
   * strategies have run, which is the step list the translator is handed.
   *
   * <p>{@code InlineFilterStrategy} unwraps a connective whose arms are single filter steps, and a
   * case that lost its wrapper still translates and still returns native's rows — it merely
   * exercises the top-level path instead of the captured-child one it was written for. A barrier in
   * one arm blocks the unwrap; this is the assertion that observes the barrier did its job.
   *
   * <p>Driven with the translator off. Those strategies run identically either way (they precede the
   * provider stage the translator occupies), and an accepted translation replaces the whole step
   * list with the boundary step, so the translator-on run has nothing left to inspect.
   */
  private void assertConnectiveReachesTheTranslator(
      String scenario, Class<?> wrapper, Supplier<GraphTraversal<?, ?>> supplier) {
    withTranslator(false, () -> {
      var admin = supplier.get().asAdmin();
      admin.applyStrategies();
      assertThat(admin.getSteps().stream().anyMatch(wrapper::isInstance))
          .as(scenario + ": the " + wrapper.getSimpleName() + " must survive optimisation, or the "
              + "case exercises the top-level path instead of the captured-child one")
          .isTrue();
    });
  }

  /**
   * Asserts the plan roots at the traversal's origin rather than at the hop's target, so the
   * target's constraint travels on the path item. Without this pin a root-selection change silently
   * routes the constraint through the plan inputs and the multiset comparison still passes.
   */
  private void assertRootsAtOrigin(String scenario, Supplier<GraphTraversal<?, ?>> traversal) {
    assertThat(planRootAlias(traversal))
        .as(scenario + " must root at the origin — otherwise the case no longer witnesses the "
            + "path-item binding it exists for")
        .isEqualTo(ORIGIN_ALIAS);
  }

  /**
   * The alias the compiled plan roots at, read off the {@code SET <alias>} line {@code prettyPrint}
   * emits for the scan the planner starts from.
   */
  private String planRootAlias(Supplier<GraphTraversal<?, ?>> traversalSupplier) {
    var text = boundaryPlanText(traversalSupplier);
    var lines = text.lines().toList();
    for (var i = 0; i < lines.size() - 1; i++) {
      if ("+ SET".equals(lines.get(i).strip())) {
        return lines.get(i + 1).strip();
      }
    }
    throw new AssertionError("plan names no root alias on a SET line:\n" + text);
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
    support.withTranslator(enabled, body);
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
    return support.translatorEnabled();
  }

  private void setTranslatorEnabled(boolean enabled) {
    support.setTranslatorEnabled(enabled);
  }

  /** The database session backing the graph traversals (its config controls the translator flag). */
  private com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded graphSession() {
    var tx = (YTDBTransaction) graph.tx();
    tx.readWrite();
    return tx.getDatabaseSession();
  }

  /**
   * Runs {@code traversalSupplier} with the translator forced to {@code enabled} and returns the
   * results rendered as sorted strings. The {@link #sortedIds} pair casts to {@code Vertex}, so a
   * shape ending in a {@code values(...)} projection needs this looser rendering.
   */
  private List<String> drainAsStrings(
      Supplier<GraphTraversal<?, ?>> traversalSupplier, boolean enabled) {
    var original = translatorEnabled();
    try {
      setTranslatorEnabled(enabled);
      return traversalSupplier.get().toList().stream().map(String::valueOf).sorted().toList();
    } finally {
      setTranslatorEnabled(original);
    }
  }

  private static List<String> labelsOf(List<?> results) {
    return results.stream().map(v -> ((Vertex) v).label()).toList();
  }

}
