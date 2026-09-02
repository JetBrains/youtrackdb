package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy.TranslatorEquivalenceSupport.countBoundarySteps;
import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.api.gremlin.tokens.YTDBQueryConfigParam;
import com.jetbrains.youtrackdb.internal.core.config.ContextConfiguration;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBTransaction;
import java.util.List;
import java.util.function.Supplier;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.lambda.ValueTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.OrderGlobalStep;
import org.apache.tinkerpop.gremlin.process.traversal.util.TraversalHelper;
import org.apache.tinkerpop.gremlin.structure.T;
import org.javatuples.Pair;
import org.junit.Test;

/**
 * Absolute-value pins for the TRANSLATED half of the productive order semantics: under the shipped
 * default the translated plan stops emitting the order-key {@code IS DEFINED} conjunct, so a record
 * missing the ordered property survives the pattern and sorts as a null key; under the portable
 * opt-out the conjunct is emitted and the record is dropped as before.
 *
 * <p><b>Rows are named, not compared arm to arm alone.</b> Arm-to-arm equality cannot detect a
 * change that moves both arms, and this change moves both. Each case therefore pins the absolute
 * row set and the absolute ordering FIRST, and only then pins that the translated arm and the
 * native arm agree. Null placement is the one value read back from the dialect, through an
 * equivalent YQL {@code ORDER BY} over the same fixture, because the sibling placement work makes
 * placement configurable.
 *
 * <p>The suite writes the translator switch and the order setting on the session's storage-scoped
 * {@code ContextConfiguration} and restores both, so it never touches process-wide
 * {@link GlobalConfiguration} state.
 */
public class TranslatedProductiveOrderTest extends GraphBaseTest {

  /**
   * Two people carrying {@code age} and exactly ONE without it. One ageless record is deliberate:
   * two would tie on the null key and no absolute ordering could be pinned.
   */
  private void seedAgedAndAgeless() {
    graph.addVertex(T.label, "Person", "name", "Alice", "age", 30);
    graph.addVertex(T.label, "Person", "name", "Bob", "age", 25);
    graph.addVertex(T.label, "Person", "name", "Nobody");
    graph.tx().commit();
  }

  /**
   * The translated plan keeps the ageless record under the shipped default and places it exactly
   * where YQL {@code ORDER BY age} places a null key. Before this change the plan carried an
   * {@code age IS DEFINED} conjunct and the record never reached the sort.
   */
  @Test
  public void translatedOrderByMissingKey_underDefault_keepsRecordAndPlacesItAsYqlDoes() {
    seedAgedAndAgeless();

    var names = withOrderIncludesMissingKey(true, () -> namesFromArm(true,
        () -> graph.traversal().V().hasLabel("Person").order().by("age").values("name")));

    assertThat(names)
        .as("the translated plan orders the ageless record as YQL orders a null key")
        .isEqualTo(yqlOrderedNames("age"));
    assertThat(names)
        .as("no record is dropped and the aged records keep ascending order")
        .containsExactlyInAnyOrder("Alice", "Bob", "Nobody")
        .containsSubsequence("Bob", "Alice");
  }

  /**
   * The descending spelling agrees with YQL too, which moves null placement with the direction.
   */
  @Test
  public void translatedOrderByMissingKeyDescending_underDefault_placesNullAsYqlDoes() {
    seedAgedAndAgeless();

    var names = withOrderIncludesMissingKey(true, () -> namesFromArm(true,
        () -> graph.traversal().V().hasLabel("Person")
            .order().by("age", org.apache.tinkerpop.gremlin.process.traversal.Order.desc)
            .values("name")));

    assertThat(names)
        .as("the translated descending plan agrees with YQL ORDER BY age DESC")
        .isEqualTo(yqlOrderedNames("age DESC"));
  }

  /**
   * Under the portable opt-out the conjunct comes back and the translated plan drops the ageless
   * record, which is the pre-change contract expressed as absolute rows.
   */
  @Test
  public void translatedOrderByMissingKey_underPortableOptOut_dropsRecord() {
    seedAgedAndAgeless();

    var names = withOrderIncludesMissingKey(false, () -> namesFromArm(true,
        () -> graph.traversal().V().hasLabel("Person").order().by("age").values("name")));

    assertThat(names)
        .as("the opt-out restores the order-key IS DEFINED conjunct, so the ageless record drops")
        .containsExactly("Bob", "Alice");
  }

  /**
   * The kept record reaches a following {@code count()}, which reads the pattern rather than the
   * projected stream: three under the default, two under the opt-out.
   */
  @Test
  public void translatedCountAfterOrderByMissingKey_countsPerSetting() {
    seedAgedAndAgeless();

    var underDefault = withOrderIncludesMissingKey(true, () -> namesFromArm(true,
        () -> graph.traversal().V().hasLabel("Person").order().by("age").count()));
    var underOptOut = withOrderIncludesMissingKey(false, () -> namesFromArm(true,
        () -> graph.traversal().V().hasLabel("Person").order().by("age").count()));

    assertThat(underDefault).as("the default counts every record").containsExactly("3");
    assertThat(underOptOut).as("the opt-out counts the key bearers only").containsExactly("2");
  }

  /**
   * The translated arm and the native arm return the same rows in the same order under BOTH
   * settings. Absolute values are pinned first so the agreement cannot be satisfied by two arms
   * that moved together in the wrong direction.
   */
  @Test
  public void bothArmsAgree_underBothSettings() {
    seedAgedAndAgeless();

    Supplier<GraphTraversal<?, ?>> shape =
        () -> graph.traversal().V().hasLabel("Person").order().by("age").values("name");

    withOrderIncludesMissingKey(true, () -> {
      var translated = namesFromArm(true, shape);
      var native0 = namesFromArm(false, shape);
      assertThat(translated).isEqualTo(yqlOrderedNames("age"));
      assertThat(native0)
          .as("under the default both arms keep the ageless record in the same place")
          .isEqualTo(translated);
      return null;
    });

    withOrderIncludesMissingKey(false, () -> {
      var translated = namesFromArm(true, shape);
      var native0 = namesFromArm(false, shape);
      assertThat(translated).containsExactly("Bob", "Alice");
      assertThat(native0)
          .as("under the opt-out both arms drop the ageless record")
          .isEqualTo(translated);
      return null;
    });
  }

  /**
   * The per-traversal override reaches the translated arm as well: one traversal opts out and its
   * plan carries the conjunct again, while the deployment-wide setting stays on.
   */
  @Test
  public void perTraversalOptOut_reachesTranslatedArm() {
    seedAgedAndAgeless();

    var names = withOrderIncludesMissingKey(true, () -> namesFromArm(true,
        () -> graph.traversal()
            .with(YTDBQueryConfigParam.orderIncludesMissingKey, false)
            .V().hasLabel("Person").order().by("age").values("name")));

    assertThat(names)
        .as("the option is read before the session default on the translated path too")
        .containsExactly("Bob", "Alice");
  }

  /**
   * A cut over an EQUAL-KEY TIE GROUP is the one place the two arms may legitimately differ. Two
   * records carry no {@code age}, so both sort under the same null key, and {@code limit(2)} keeps
   * two of them: WHICH two is a tie-break the two engines are free to answer differently.
   *
   * <p>The pin is therefore the tie group itself: both arms return two rows, both drawn from the
   * ageless pair, and both arms return the same NUMBER of rows. Identity is deliberately not
   * pinned.
   */
  @Test
  public void cutOverTieGroup_bothArmsKeepTheSameTieGroup() {
    graph.addVertex(T.label, "Person", "name", "Alice", "age", 30);
    graph.addVertex(T.label, "Person", "name", "Bob", "age", 25);
    graph.addVertex(T.label, "Person", "name", "Nobody");
    graph.addVertex(T.label, "Person", "name", "Nemo");
    graph.tx().commit();

    // Ascending with the configured placement putting nulls first, the two ageless records are the
    // first two rows. The placement is read from the dialect rather than assumed: the assertion
    // below compares against the YQL prefix of the same length.
    var expectedPrefix = yqlOrderedNames("age").subList(0, 2);

    withOrderIncludesMissingKey(true, () -> {
      Supplier<GraphTraversal<?, ?>> shape =
          () -> graph.traversal().V().hasLabel("Person").order().by("age").limit(2)
              .values("name");
      var translated = namesFromArm(true, shape);
      var native0 = namesFromArm(false, shape);

      assertThat(translated)
          .as("the cut keeps two rows from the null-key tie group, whichever two")
          .hasSize(2)
          .isSubsetOf(expectedPrefix);
      assertThat(native0)
          .as("the native arm keeps the same tie group and the same row count")
          .hasSize(2)
          .isSubsetOf(expectedPrefix);
      return null;
    });

    withOrderIncludesMissingKey(false, () -> {
      var translated = namesFromArm(true,
          () -> graph.traversal().V().hasLabel("Person").order().by("age").limit(2)
              .values("name"));
      assertThat(translated)
          .as("under the opt-out no tie group exists: the two key bearers are the whole result")
          .containsExactly("Bob", "Alice");
      return null;
    });
  }

  /**
   * Exactly ONE mechanism serves any shape, and never zero.
   *
   * <p>The native {@code YTDBProductiveOrderByStrategy} names the translator as a prior strategy, so
   * the two cannot both act: a RECOGNISED shape has its whole step list replaced by the boundary
   * step, leaving no {@code OrderGlobalStep} for the native rewrite to touch, while a DECLINED shape
   * keeps its order step and gets the native rewrite. This case pins both halves of that split, and
   * pins that each half produces the semantics the setting asks for, which rules out a shape served
   * by neither.
   */
  @Test
  public void recognisedShapeUsesTranslatorOnly_declinedShapeUsesNativeRewriteOnly() {
    seedAgedAndAgeless();

    withOrderIncludesMissingKey(true, () -> {
      var recognised = translatedSteps(
          () -> graph.traversal().V().hasLabel("Person").order().by("age").values("name"));
      assertThat(countBoundarySteps(recognised))
          .as("the recognised shape is spliced to a boundary step")
          .isEqualTo(1);
      assertThat(orderSteps(recognised))
          .as("no OrderGlobalStep survives translation, so the native rewrite cannot also apply")
          .isEmpty();
      assertThat(rows(recognised))
          .as("the translated plan alone delivers the including semantics")
          .hasSize(3);

      var declined = translatedSteps(
          () -> graph.traversal().V().hasLabel("Person").order().by("age")
              .filter(traverser -> true).values("name"));
      assertThat(countBoundarySteps(declined))
          .as("the lambda filter declines the whole traversal")
          .isZero();
      assertThat(modulatorBypass(declined))
          .as("the declined shape is served by the native rewrite instead")
          .isNotNull();
      assertThat(rows(declined))
          .as("and the native rewrite delivers the same including semantics")
          .hasSize(3);
      return null;
    });

    withOrderIncludesMissingKey(false, () -> {
      var recognised = translatedSteps(
          () -> graph.traversal().V().hasLabel("Person").order().by("age").values("name"));
      assertThat(orderSteps(recognised)).isEmpty();
      assertThat(rows(recognised))
          .as("under the opt-out the translated plan drops the ageless record")
          .hasSize(2);

      var declined = translatedSteps(
          () -> graph.traversal().V().hasLabel("Person").order().by("age")
              .filter(traverser -> true).values("name"));
      assertThat(modulatorBypass(declined))
          .as("under the opt-out the native rewrite leaves the modulator filtering")
          .isNull();
      assertThat(rows(declined))
          .as("so the declined shape drops the ageless record too")
          .hasSize(2);
      return null;
    });
  }

  // --- helpers ----------------------------------------------------------------------------------

  /** Compiles a traversal with the translator enabled, so both mechanisms have had their chance. */
  private Traversal.Admin<?, ?> translatedSteps(Supplier<GraphTraversal<?, ?>> supplier) {
    var configuration = graphConfiguration();
    var previous = configuration.getValueAsBoolean(
        GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED);
    configuration.setValue(GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED, true);
    try {
      var admin = supplier.get().asAdmin();
      admin.applyStrategies();
      return admin;
    } finally {
      configuration.setValue(
          GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED, previous);
    }
  }

  private static List<OrderGlobalStep> orderSteps(Traversal.Admin<?, ?> admin) {
    return TraversalHelper.getStepsOfAssignableClassRecursively(OrderGlobalStep.class, admin);
  }

  /**
   * The bypass traversal the native rewrite installs on a {@code by(key)} modulator, or
   * {@code null} when the modulator was left filtering. Reading the bypass is what distinguishes a
   * rewritten modulator from an untouched one.
   */
  @SuppressWarnings("rawtypes")
  private static Object modulatorBypass(Traversal.Admin<?, ?> admin) {
    var steps = orderSteps(admin);
    assertThat(steps).as("a declined shape keeps exactly one order step").hasSize(1);
    OrderGlobalStep<?, ?> orderStep = steps.getFirst();
    var pair = (Pair) orderStep.getComparators().getFirst();
    var modulator = pair.getValue0();
    assertThat(modulator).isInstanceOf(ValueTraversal.class);
    return ((ValueTraversal<?, ?>) modulator).getBypassTraversal();
  }

  private static List<String> rows(Traversal.Admin<?, ?> admin) {
    return admin.toList().stream().map(String::valueOf).toList();
  }

  /**
   * Drains one arm and pins its engagement: the translated arm must splice exactly one boundary
   * step, the native arm none. Rows are rendered through {@code String.valueOf} so a {@code count}
   * terminator and a {@code values} projection share one renderer, and ORDER IS PRESERVED.
   */
  private List<String> namesFromArm(boolean translated, Supplier<GraphTraversal<?, ?>> supplier) {
    var configuration = graphConfiguration();
    var previous = configuration.getValueAsBoolean(
        GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED);
    configuration.setValue(
        GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED, translated);
    try {
      var admin = supplier.get().asAdmin();
      admin.applyStrategies();
      assertThat(countBoundarySteps(admin))
          .as(translated
              ? "the translator-on arm must engage exactly one boundary step"
              : "the translator-off arm must engage no boundary step")
          .isEqualTo(translated ? 1 : 0);
      return admin.toList().stream().map(String::valueOf).toList();
    } finally {
      configuration.setValue(
          GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED, previous);
    }
  }

  /** Runs {@code body} with the productive-order setting forced, restoring the previous value. */
  private <T> T withOrderIncludesMissingKey(boolean value, Supplier<T> body) {
    var configuration = graphConfiguration();
    var previous = configuration.getValueAsBoolean(
        GlobalConfiguration.QUERY_GREMLIN_ORDER_INCLUDES_MISSING_KEY);
    configuration.setValue(GlobalConfiguration.QUERY_GREMLIN_ORDER_INCLUDES_MISSING_KEY, value);
    try {
      return body.get();
    } finally {
      configuration.setValue(
          GlobalConfiguration.QUERY_GREMLIN_ORDER_INCLUDES_MISSING_KEY, previous);
    }
  }

  /**
   * The names of the seeded people in the order YQL {@code ORDER BY orderBy} returns them. Every
   * placement assertion compares against this rather than a hardcoded position.
   */
  private List<String> yqlOrderedNames(String orderBy) {
    session.begin();
    try (var result = session.query("SELECT name FROM Person ORDER BY " + orderBy)) {
      return result.stream().map(row -> row.<String>getProperty("name")).toList();
    } finally {
      session.commit();
    }
  }

  /** The storage-scoped configuration the graph's own traversals read. */
  private ContextConfiguration graphConfiguration() {
    return graphSession().getConfiguration();
  }

  private DatabaseSessionEmbedded graphSession() {
    var tx = (YTDBTransaction) graph.tx();
    tx.readWrite();
    return tx.getDatabaseSession();
  }
}
