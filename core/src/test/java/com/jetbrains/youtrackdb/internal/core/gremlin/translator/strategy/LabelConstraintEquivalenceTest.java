package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBTransaction;
import java.util.List;
import java.util.function.Supplier;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.structure.T;
import org.junit.Before;
import org.junit.Test;

/** Label conjunctions decline to native without losing a condition in either polymorphism mode. */
public class LabelConstraintEquivalenceTest extends GraphBaseTest {

  private final TranslatorEquivalenceSupport support =
      new TranslatorEquivalenceSupport(() -> ((YTDBTransaction) graph.tx()).getDatabaseSession());

  @Before
  public void seedHierarchyAndDecoys() {
    var user = session.createVertexClass("User");
    var employee = session.getSchema().createClass("Employee", user);
    session.getSchema().createClass("Contractor", user);
    session.getSchema().createClass("Manager", employee);
    session.createVertexClass("Hub");
    session.createEdgeClass("follows");
    var hub = graph.addVertex(T.label, "Hub", "name", "h");
    var plain = graph.addVertex(T.label, "User", "name", "u");
    var worker = graph.addVertex(T.label, "Employee", "name", "e");
    var contractor = graph.addVertex(T.label, "Contractor", "name", "c");
    var manager = graph.addVertex(T.label, "Manager", "name", "m");
    for (var target : List.of(plain, worker, contractor, manager)) {
      hub.addEdge("follows", target, "name", target.value("name"));
    }
    worker.addEdge("follows", plain);
    graph.tx().commit();
  }

  /** D4 keeps both the single-label condition and the later alternatives at the source. */
  @Test
  public void singleThenMultipleAtSource_declinesAndMatchesNative() {
    assertDeclined(() -> graph.traversal().V().hasLabel("User")
        .hasLabel("Employee", "Contractor").values("name"), List.of("c", "e", "m"), List.of());
  }

  /** D4 also keeps both conditions after navigation, including the plain User decoy. */
  @Test
  public void singleThenMultipleAfterOut_declinesAndMatchesNative() {
    assertDeclined(() -> graph.traversal().V().hasLabel("Hub").out("follows")
        .hasLabel("User").hasLabel("Employee", "Contractor").values("name"),
        List.of("c", "e", "m"), List.of());
  }

  /** D5 must not widen an Employee scan to User across a transparent barrier. */
  @Test
  public void wideningDirectChain_declinesAndMatchesNative() {
    assertDeclined(() -> graph.traversal().V().hasLabel("Employee").barrier()
        .hasLabel("User").values("name"), List.of("e", "m"), List.of());
  }

  /** D5 reads the class already captured inside where() and filter() children. */
  @Test
  public void wideningChildQueries_declineAndMatchNative() {
    assertDeclined(() -> graph.traversal().V().where(
        __.hasLabel("Employee").barrier().hasLabel("User")).values("name"),
        List.of("e", "m"), List.of());
    assertDeclined(() -> graph.traversal().V().filter(
        __.hasLabel("Employee").barrier().hasLabel("User")).values("name"),
        List.of("e", "m"), List.of());
  }

  /** D5 preflights sibling retypes before any arm can overwrite an earlier arm's class. */
  @Test
  public void wideningAndSiblings_declineAndMatchNative() {
    assertDeclined(() -> graph.traversal().V().and(__.hasLabel("Employee").barrier(),
        __.hasLabel("User").barrier()).values("name"), List.of("e", "m"), List.of());
    assertDeclined(() -> graph.traversal().V().and(__.hasLabel("Employee").barrier(),
        __.and(__.hasLabel("User").barrier(), __.has("name", "c"))).values("name"),
        List.of(), List.of());
  }

  /** A hop check and a narrowing sibling keep the source labels in both query modes. */
  @Test
  public void andHopWithNarrowingLabelSibling_translatesAndMatchesNative() {
    assertResult(() -> graph.traversal().V().hasLabel("User").barrier().and(
        __.out("follows").barrier(), __.hasLabel("Employee").barrier()).values("name"),
        List.of("e"), List.of(), TranslatorEquivalenceSupport.Recognition.RECOGNIZED);
  }

  /** A widening sibling must decline without replacing the Employee source scan. */
  @Test
  public void andHopWithWideningLabelSibling_declinesAndMatchesNative() {
    assertDeclined(() -> graph.traversal().V().hasLabel("Employee").barrier().and(
        __.out("follows").barrier(), __.hasLabel("User").barrier()).values("name"),
        List.of("e"), List.of());
  }

  /** Exists validation declines an origin re-type even when the label before the hop narrows. */
  @Test
  public void andNarrowingBoundaryLabelBeforeHop_declinesAndMatchesNative() {
    assertDeclined(() -> graph.traversal().V().hasLabel("User").barrier().and(
        __.hasLabel("Employee").barrier().out("follows"),
        __.has("name", P.neq("never")).barrier()).values("name"),
        List.of("e"), List.of());
  }

  /** D5 declines a widening origin label before a hop without weakening either label. */
  @Test
  public void andWideningBoundaryLabelBeforeHop_declinesAndMatchesNative() {
    assertDeclined(() -> graph.traversal().V().hasLabel("Employee").barrier().and(
        __.hasLabel("User").barrier().out("follows"),
        __.has("name", P.neq("never")).barrier()).values("name"),
        List.of("e"), List.of());
  }

  /** A positive where() forwards the hop check and narrowing label from its nested AND. */
  @Test
  public void whereHopWithNarrowingLabelSibling_translatesAndMatchesNative() {
    assertResult(() -> graph.traversal().V().hasLabel("User").barrier().where(__.and(
        __.out("follows").barrier(), __.hasLabel("Employee").barrier())).values("name"),
        List.of("e"), List.of(), TranslatorEquivalenceSupport.Recognition.RECOGNIZED);
  }

  /** The label after a where() hop belongs to its User target, not the Employee source. */
  @Test
  public void whereHopTargetLabel_translatesAndMatchesNative() {
    assertResult(() -> graph.traversal().V().hasLabel("Employee").barrier().where(
        __.out("follows").hasLabel("User")).values("name"),
        List.of("e"), List.of("e"), TranslatorEquivalenceSupport.Recognition.RECOGNIZED);
  }

  /** D5 applies when a union fork replays its constrained prefix. */
  @Test
  public void wideningUnionArm_declinesAndMatchesNative() {
    assertDeclined(() -> graph.traversal().V().hasLabel("Employee").barrier().union(
        __.hasLabel("User").values("name"), __.has("name", "never").values("name")),
        List.of("e", "m"), List.of());
  }

  /** D5 checks the earlier alias's class after select() repins the traversal. */
  @Test
  public void wideningSelectedAlias_declinesAndMatchesNative() {
    assertDeclined(() -> graph.traversal().V().hasLabel("Employee").as("worker")
        .out("follows").select("worker").hasLabel("User").values("name"),
        List.of("e"), List.of());
  }

  /** A vertex label on a selected edge reaches the edge-alias guard after the schema check. */
  @Test
  public void vertexLabelOnSelectedEdge_declinesAndMatchesNative() {
    assertResult(() -> graph.traversal().V().hasLabel("Hub").outE("follows").as("edge")
        .inV().select("edge").values("name"), List.of("c", "e", "m", "u"),
        List.of("c", "e", "m", "u"), TranslatorEquivalenceSupport.Recognition.RECOGNIZED);
    assertDeclined(() -> graph.traversal().V().hasLabel("Hub").outE("follows").as("edge")
        .inV().select("edge").hasLabel("V").values("name"), List.of(), List.of());
  }

  /** NL-5 checks widening in OR, NOT and a nested AND arm without losing a label condition. */
  @Test
  public void wideningBooleanArms_declineAndMatchNative() {
    assertDeclined(() -> graph.traversal().V().or(
        __.hasLabel("Employee").barrier().hasLabel("User").has("name", "c"),
        __.has("name", "never")).values("name"), List.of(), List.of());
    assertDeclined(() -> graph.traversal().V().not(
        __.hasLabel("Employee").barrier().hasLabel("User").has("name", "c")).values("name"),
        List.of("c", "e", "h", "m", "u"), List.of("c", "e", "h", "m", "u"));
    assertDeclined(() -> graph.traversal().V().or(
        __.and(__.hasLabel("Employee").barrier(), __.hasLabel("User").barrier())
            .has("name", "c"),
        __.has("name", "never")).values("name"), List.of(), List.of());
  }

  /** NL-9 keeps overlapping captured refinements outside the supported OR/NOT fold. */
  @Test
  public void capturedMultipleLabelChains_declineAndMatchNativeInOrAndNot() {
    assertDeclined(() -> graph.traversal().V().or(
        __.hasLabel("Employee", "Contractor").barrier().hasLabel("Employee", "Manager"),
        __.has("name", "never")).values("name"), List.of("e", "m"), List.of("e"));
    assertDeclined(() -> graph.traversal().V().not(
        __.hasLabel("Employee", "Contractor").barrier().hasLabel("Employee", "Manager"))
        .values("name"), List.of("c", "h", "u"), List.of("c", "h", "m", "u"));
  }

  /** An AND grandchild must not hide an enclosing OR arm's concrete capture from the guard. */
  @Test
  public void grandchildMultipleLabelsInOrAnd_declineAndMatchNative() {
    assertDeclined(() -> graph.traversal().V().or(
        __.hasLabel("Employee", "Contractor").barrier().and(
            __.hasLabel("Employee", "Manager").barrier(), __.has("name", "e").barrier()),
        __.has("name", "never")).values("name"), List.of("e"), List.of("e"));
  }

  /** A where() grandchild sees the OR arm's capture even before its own first class write. */
  @Test
  public void grandchildMultipleLabelsInOrWhere_declineAndMatchNative() {
    assertDeclined(() -> graph.traversal().V().or(
        __.hasLabel("Employee", "Contractor").barrier().where(
            __.hasLabel("Employee", "Manager").barrier()).has("name", "e"),
        __.has("name", "never")).values("name"), List.of("e"), List.of("e"));
  }

  /** An AND grandchild under NOT preserves the unsupported captured refinement by declining. */
  @Test
  public void grandchildMultipleLabelsInNotAnd_declineAndMatchNative() {
    assertDeclined(() -> graph.traversal().V().not(
        __.hasLabel("Employee", "Contractor").barrier().and(
            __.hasLabel("Employee", "Manager").barrier(), __.has("name", "e").barrier()))
        .values("name"), List.of("c", "h", "m", "u"), List.of("c", "h", "m", "u"));
  }

  /** A where() grandchild under NOT preserves the named-label intersection by declining. */
  @Test
  public void grandchildMultipleLabelsInNotWhere_declineAndMatchNative() {
    assertDeclined(() -> graph.traversal().V().not(
        __.hasLabel("Employee", "Contractor").barrier().where(
            __.hasLabel("Employee", "Manager").barrier()).has("name", "e"))
        .values("name"), List.of("c", "h", "m", "u"), List.of("c", "h", "m", "u"));
  }

  /** Captured narrowing stays native in boolean folds, including a nested AND. */
  @Test
  public void capturedNarrowing_declinesInBooleanFolds() {
    assertDeclined(() -> graph.traversal().V().or(
        __.hasLabel("Employee").barrier().hasLabel("Manager").has("name", "m"),
        __.has("name", "never")).values("name"), List.of("m"), List.of());
    assertDeclined(() -> graph.traversal().V().not(
        __.hasLabel("Employee").barrier().hasLabel("Manager").has("name", "m"))
        .values("name"), List.of("c", "e", "h", "u"), List.of("c", "e", "h", "m", "u"));
    assertDeclined(() -> graph.traversal().V().or(
        __.and(__.hasLabel("User").barrier(), __.hasLabel("Employee").barrier())
            .has("name", "e"),
        __.has("name", "never")).values("name"), List.of("e"), List.of());
    assertDeclined(() -> graph.traversal().V().or(
        __.where(__.hasLabel("Employee").barrier().hasLabel("Manager")).has("name", "m"),
        __.has("name", "never")).values("name"), List.of("m"), List.of());
  }

  /** M1 keeps both label filters when a positive grandchild refines a captured User scan. */
  @Test
  public void positiveUserThenMultipleLabels_translatesAndMatchesNative() {
    assertResult(() -> graph.traversal().V().where(__.hasLabel("User").barrier().where(
        __.hasLabel("Employee", "Contractor").barrier())).values("name"),
        List.of("c", "e", "m"), List.of(),
        TranslatorEquivalenceSupport.Recognition.RECOGNIZED);
  }

  /** M8 intersects alternatives inside a positive AND without folding its LCA as exact class. */
  @Test
  public void positiveMultipleLabelIntersection_translatesAndMatchesNative() {
    assertResult(() -> graph.traversal().V().where(
        __.hasLabel("Employee", "Contractor").barrier().and(
            __.hasLabel("Employee", "Manager").barrier()))
        .values("name"),
        List.of("e", "m"), List.of("e"),
        TranslatorEquivalenceSupport.Recognition.RECOGNIZED);
  }

  /** M10 retains the Employee scan and a positive grandchild's explicit alternative filter. */
  @Test
  public void positiveEmployeeThenMultipleLabels_translatesAndMatchesNative() {
    assertResult(() -> graph.traversal().V().where(__.hasLabel("Employee").barrier().where(
        __.hasLabel("Employee", "Manager").barrier())).values("name"),
        List.of("e", "m"), List.of("e"),
        TranslatorEquivalenceSupport.Recognition.RECOGNIZED);
  }

  /** Direct narrowing and equal repeats still translate while keeping exact-mode filters. */
  @Test
  public void narrowingAndEqualRepeats_keepEveryCondition() {
    assertResult(() -> graph.traversal().V().hasLabel("User").barrier()
        .hasLabel("Employee").values("name"), List.of("e", "m"), List.of(),
        TranslatorEquivalenceSupport.Recognition.RECOGNIZED);
    assertResult(() -> graph.traversal().V().hasLabel("Employee").barrier()
        .hasLabel("Employee").values("name"), List.of("e", "m"), List.of("e"),
        TranslatorEquivalenceSupport.Recognition.RECOGNIZED);
    assertDeclined(() -> graph.traversal().V().hasLabel("Employee").barrier()
        .hasLabel("Contractor").values("name"), List.of(), List.of());
  }

  /** Equal scan classes retain the earlier IN filter instead of admitting the plain User decoy. */
  @Test
  public void earlierAlternatives_keepTheirExplicitClassFilter() {
    assertResult(() -> graph.traversal().V().hasLabel("Employee", "Contractor").barrier()
        .hasLabel("User").values("name"), List.of("c", "e", "m"), List.of(),
        TranslatorEquivalenceSupport.Recognition.RECOGNIZED);
  }

  /** M5 tests the named alternatives rather than the User scan class in an OR operand. */
  @Test
  public void multipleLabelsInOrArm_matchNativeInsteadOfExactLca() {
    assertResult(() -> graph.traversal().V().or(
        __.hasLabel("Employee", "Contractor").has("name", "e"),
        __.has("name", "never")).values("name"), List.of("e"), List.of("e"),
        TranslatorEquivalenceSupport.Recognition.RECOGNIZED);
  }

  /** M6 includes Manager because it is named explicitly, even with polymorphism disabled. */
  @Test
  public void nestedOrNamedParentAndSubclass_matchNative() {
    assertResult(() -> graph.traversal().V().where(__.or(
        __.hasLabel("Employee", "Manager").barrier(), __.has("name", "c")))
        .values("name"), List.of("c", "e", "m"), List.of("c", "e", "m"),
        TranslatorEquivalenceSupport.Recognition.RECOGNIZED);
  }

  /** N1 carries named alternatives through a positive child into its enclosing OR operand. */
  @Test
  public void multipleLabelsInWhereInsideOr_matchNative() {
    assertResult(() -> graph.traversal().V().or(
        __.where(__.hasLabel("Employee", "Contractor").barrier()).has("name", "e"),
        __.has("name", "never")).values("name"), List.of("e"), List.of("e"),
        TranslatorEquivalenceSupport.Recognition.RECOGNIZED);
  }

  /** N2 negates the named alternatives forwarded by where(), not their internal User scan. */
  @Test
  public void multipleLabelsInWhereInsideNot_matchNative() {
    assertResult(() -> graph.traversal().V().not(
        __.where(__.hasLabel("Employee", "Contractor").barrier())).values("name"),
        List.of("h", "u"), List.of("h", "m", "u"),
        TranslatorEquivalenceSupport.Recognition.RECOGNIZED);
  }

  /** N3 excludes the Employee closure only in polymorphic mode and always excludes Contractor. */
  @Test
  public void multipleLabelsInsideNot_matchNative() {
    assertResult(() -> graph.traversal().V().not(
        __.hasLabel("Employee", "Contractor")).values("name"),
        List.of("h", "u"), List.of("h", "m", "u"),
        TranslatorEquivalenceSupport.Recognition.RECOGNIZED);
  }

  /** N13 keeps both equal sibling label sets without adding exact equality on their LCA. */
  @Test
  public void equalMultipleLabelAndSiblingsInsideOr_matchNative() {
    assertResult(() -> graph.traversal().V().or(
        __.and(__.hasLabel("Employee", "Contractor").barrier(),
            __.hasLabel("Employee", "Contractor").barrier()).has("name", "e"),
        __.has("name", "never")).values("name"), List.of("e"), List.of("e"),
        TranslatorEquivalenceSupport.Recognition.RECOGNIZED);
  }

  /** A single named label inside OR includes its subclasses only in polymorphic mode. */
  @Test
  public void singleLabelInOr_includesNativeSubclassClosure() {
    assertResult(() -> graph.traversal().V().or(
        __.hasLabel("Employee").has("name", "m"), __.has("name", "never"))
        .values("name"), List.of("m"), List.of(),
        TranslatorEquivalenceSupport.Recognition.RECOGNIZED);
  }

  /** A positive grandchild preserves the single named label used by an enclosing NOT. */
  @Test
  public void singleLabelInWhereInsideNot_includesNativeSubclassClosure() {
    assertResult(() -> graph.traversal().V().not(
        __.where(__.hasLabel("Employee").has("name", P.neq("never")).barrier()))
        .values("name"),
        List.of("c", "h", "u"), List.of("c", "h", "m", "u"),
        TranslatorEquivalenceSupport.Recognition.RECOGNIZED);
  }

  private void assertDeclined(Supplier<GraphTraversal<?, ?>> query,
      List<String> polymorphicNames, List<String> exactNames) {
    assertResult(query, polymorphicNames, exactNames,
        TranslatorEquivalenceSupport.Recognition.DECLINED);
  }

  private void assertResult(Supplier<GraphTraversal<?, ?>> query,
      List<String> polymorphicNames, List<String> exactNames,
      TranslatorEquivalenceSupport.Recognition recognition) {
    graph.tx().readWrite();
    var config = ((YTDBTransaction) graph.tx()).getDatabaseSession().getConfiguration();
    var key = GlobalConfiguration.QUERY_GREMLIN_POLYMORPHIC_BY_DEFAULT;
    var previous = config.getValueAsBoolean(key);
    try {
      for (var polymorphic : List.of(true, false)) {
        config.setValue(key, polymorphic);
        support.withTranslator(false, () -> assertThat(
            TranslatorEquivalenceSupport.sortedStrings(query.get().toList()))
            .isEqualTo(polymorphic ? polymorphicNames : exactNames));
        support.assertEquivalent(query.get().toString(), recognition,
            TranslatorEquivalenceSupport.Cardinality.MAY_BE_EMPTY,
            TranslatorEquivalenceSupport::sortedStrings, query);
        // A translating control proves that the fixture and the translator switch both work.
        support.assertEquivalent("Employee control",
            TranslatorEquivalenceSupport.Recognition.RECOGNIZED,
            TranslatorEquivalenceSupport.Cardinality.NON_EMPTY,
            TranslatorEquivalenceSupport::sortedStrings,
            () -> graph.traversal().V().hasLabel("Employee").values("name"));
      }
    } finally {
      config.setValue(key, previous);
    }
  }
}
