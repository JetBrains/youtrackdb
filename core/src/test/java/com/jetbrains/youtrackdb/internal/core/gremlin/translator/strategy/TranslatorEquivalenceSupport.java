package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.core.config.ContextConfiguration;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.AbstractMatchPlanStep;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.MultiPlanMatchStep;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.structure.Vertex;

/**
 * The translator-on / translator-off equivalence harness shared by the recogniser and equivalence
 * suites in this package — the kill-switch toggle, the boundary-step counters, the row renderers,
 * and the two-arm {@code assertEquivalent} driver with its anti-vacuity pins.
 *
 * <p>Every member below previously stood as a near-verbatim private copy in each suite. Counted over
 * this package: the boundary-step counter in eleven classes as a named method, plus a twelfth that
 * spelled it inline as a stream; a kill-switch toggle helper in twelve classes under seven different
 * names; the two-arm driver in five classes as {@code assertEquivalent} (nine declarations once
 * overloads are counted) and in five more under case-specific names; and the recognition enum in
 * five. Retiring the copies removed 84 to 116 lines from each of the five equivalence suites.
 *
 * <p><b>The copies had already drifted, which is the failure this class exists to stop.</b> Two
 * suites made the anti-vacuity pins opt-out-able and three left them unconditional; two wrote the
 * flag on {@code DbTestBase.session} while their siblings resolved it out of {@code graph.tx()}; and
 * the counter appeared with two return types over two parameter types. A pin added to one copy
 * reached the others only when somebody remembered to carry it by hand.
 *
 * <p><b>The session handle arrives as a constructor parameter because the suites disagree about
 * it.</b> Some write the flag on {@code DbTestBase.session}; others resolve the session out of
 * {@code graph.tx()}, which is the handle the graph's own traversals read. Those two are not always
 * the same object, so the harness takes a supplier and each suite keeps whichever handle it was
 * verified against rather than being migrated onto one of them blind.
 *
 * @see ModernGraphFixture the sibling extraction pattern this class follows — a package-private
 *     collaborator holding the shared body, with each suite keeping a thin local adapter that names
 *     the contract in the suite's own vocabulary
 */
final class TranslatorEquivalenceSupport {

  /**
   * What the translator must do with a shape. {@code RECOGNIZED_MULTI_PLAN} additionally pins that
   * the spliced boundary is a {@link MultiPlanMatchStep} — a shape can be recognised into the
   * single-plan boundary instead, which is a different contract and must not silently satisfy a
   * union test.
   */
  enum Recognition {
    RECOGNIZED, RECOGNIZED_MULTI_PLAN, DECLINED
  }

  /**
   * Whether the shape's fixture is expected to produce rows. {@code NON_EMPTY} arms the anti-vacuity
   * pin and is what almost every case wants; {@code MAY_BE_EMPTY} is the deliberate opt-out for a
   * shape that is empty by design — an empty-input {@code sum} / {@code min} / {@code max} /
   * {@code mean} that drops the null aggregate row, a slice that drops the single map a grouping
   * terminator emits, a {@code hasLabel} over a class nothing was seeded into, or a comparison whose
   * comparator legitimately rejects every stored value. An opt-out wants a translating control on
   * the same fixture beside it, because "both arms empty" is otherwise indistinguishable from "the
   * seed persisted nothing".
   */
  enum Cardinality {
    NON_EMPTY, MAY_BE_EMPTY
  }

  private final Supplier<DatabaseSessionEmbedded> sessionSupplier;

  /**
   * @param sessionSupplier resolves the session whose {@code ContextConfiguration} carries the
   *     kill-switch. Called afresh on every read and write rather than captured once, because the
   *     suites that resolve through {@code graph.tx()} need the transaction opened at call time.
   */
  TranslatorEquivalenceSupport(Supplier<DatabaseSessionEmbedded> sessionSupplier) {
    assert sessionSupplier != null;
    this.sessionSupplier = sessionSupplier;
  }

  boolean translatorEnabled() {
    return configuration()
        .getValueAsBoolean(GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED);
  }

  void setTranslatorEnabled(boolean enabled) {
    configuration().setValue(GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED,
        enabled);
  }

  /**
   * Runs {@code body} with the translator forced to {@code enabled}, restoring whatever the flag held
   * on the way in whether {@code body} returns or throws. Restoring the previous value rather than a
   * hardcoded default matters: the flag defaults to {@code true}, so a helper that restored
   * {@code false} would leave every later assertion in the same method running translator-off and
   * passing without exercising the translator.
   */
  void withTranslator(boolean enabled, Runnable body) {
    var original = translatorEnabled();
    setTranslatorEnabled(enabled);
    try {
      body.run();
    } finally {
      setTranslatorEnabled(original);
    }
  }

  /**
   * Runs {@code body} with the translator switch restored afterwards, leaving {@code body} itself
   * free to toggle it. For the two-arm comparisons, where {@link #withTranslator} would fight the
   * body over the initial value.
   */
  void withTranslatorRestored(Runnable body) {
    var original = translatorEnabled();
    try {
      body.run();
    } finally {
      setTranslatorEnabled(original);
    }
  }

  /**
   * Runs {@code traversalSupplier}'s shape with the translator enabled and again disabled, then
   * asserts boundary-step engagement (per {@code expected}) and row equality between the two runs.
   * The flag is restored afterwards.
   *
   * <p>Two anti-vacuity pins are the reason this body is worth sharing rather than re-typing. On the
   * recognised branch, a fixture that persisted nothing makes both arms return {@code []} and the
   * equality below holds over two empty lists. On the <b>declined</b> branch it is worse: a decline
   * makes both arms the native pipeline by construction, so the equality cannot fail whatever the
   * fixture holds, leaving the boundary counts as the only live assertions. Both pins are armed
   * unless the caller passes {@link Cardinality#MAY_BE_EMPTY}.
   *
   * <p>The off arm's boundary count is pinned at zero as well, and that pin is not redundant: the
   * flag defaults on, so a write that never reached the handle the traversal reads would leave both
   * arms translated and turn the equality into the translated engine compared against itself.
   *
   * @param renderer converts a drained result list into the comparable form this suite uses —
   *     {@link #sortedIds} for element shapes, {@link #sortedStrings} for values,
   *     {@link #sortedIdsOrValues} for shapes that may return either, or a suite-local canonicaliser
   *     for maps and order-sensitive comparisons
   */
  void assertEquivalent(
      String scenario,
      Recognition expected,
      Cardinality cardinality,
      Function<List<?>, List<String>> renderer,
      Supplier<GraphTraversal<?, ?>> traversalSupplier) {
    var original = translatorEnabled();
    try {
      // Translator ON: apply strategies (which read the flag fresh), count boundary steps, drain.
      setTranslatorEnabled(true);
      var onAdmin = traversalSupplier.get().asAdmin();
      onAdmin.applyStrategies();
      var boundaryOn = countBoundarySteps(onAdmin.getSteps());
      var multiPlanOn = countMultiPlanSteps(onAdmin.getSteps());
      var onRows = renderer.apply(onAdmin.toList());

      // Translator OFF: the native pipeline, never a boundary step.
      setTranslatorEnabled(false);
      var offAdmin = traversalSupplier.get().asAdmin();
      offAdmin.applyStrategies();
      var boundaryOff = countBoundarySteps(offAdmin.getSteps());
      var offRows = renderer.apply(offAdmin.toList());

      if (expected == Recognition.DECLINED) {
        // The counter reads the boundary supertype, so a zero here also rules out a spliced
        // multi-plan step — a decline expectation cannot be satisfied by the other boundary shape.
        assertThat(boundaryOn)
            .as(scenario + " (translator on) must decline to native — no boundary step")
            .isZero();
        if (cardinality == Cardinality.NON_EMPTY) {
          assertThat(offRows)
              .as(scenario + ": a declined shape must still return a non-empty native result, else "
                  + "the equality below is vacuous")
              .isNotEmpty();
        }
      } else {
        assertThat(boundaryOn)
            .as(scenario + " (translator on) must engage exactly one boundary step")
            .isEqualTo(1);
        if (expected == Recognition.RECOGNIZED_MULTI_PLAN) {
          assertThat(multiPlanOn)
              .as(scenario + " (translator on) must splice MultiPlanMatchStep")
              .isEqualTo(1);
        }
        if (cardinality == Cardinality.NON_EMPTY) {
          assertThat(onRows)
              .as(scenario + ": a RECOGNIZED fixture must return a non-empty result (else the "
                  + "equality below is vacuous)")
              .isNotEmpty();
        }
      }
      assertThat(boundaryOff)
          .as(scenario + " (translator off) must never engage a boundary step")
          .isZero();
      assertThat(onRows)
          .as(scenario + ": translator-on and translator-off results must match")
          .isEqualTo(offRows);
    } finally {
      setTranslatorEnabled(original);
    }
  }

  /**
   * Counts translated boundary steps of <em>any</em> kind across a step list (raw {@code
   * List<Step>}). The supertype is deliberate: a shape that splices a {@link MultiPlanMatchStep}
   * instead of a single-plan step is still a translation, and counting only the single-plan subtype
   * would let such a shape satisfy a decline expectation while the translator in fact accepted it.
   */
  static int countBoundarySteps(List<?> steps) {
    var count = 0;
    for (var step : steps) {
      if (step instanceof AbstractMatchPlanStep<?, ?>) {
        count++;
      }
    }
    return count;
  }

  /** The {@link #countBoundarySteps(List)} overload for callers holding a traversal. */
  static int countBoundarySteps(Traversal.Admin<?, ?> admin) {
    return countBoundarySteps(admin.getSteps());
  }

  /** Counts spliced multi-plan boundary steps — the union shapes' stricter engagement pin. */
  static int countMultiPlanSteps(List<?> steps) {
    var count = 0;
    for (var step : steps) {
      if (step instanceof MultiPlanMatchStep<?, ?>) {
        count++;
      }
    }
    return count;
  }

  /**
   * Sorted RID strings of the returned elements. Sorting preserves multiplicity, so the comparison
   * is a multiset one: a vertex reached N times appears N times.
   */
  static List<String> sortedIds(List<?> results) {
    return results.stream().map(v -> ((Vertex) v).id().toString()).sorted().toList();
  }

  /** The {@link #sortedIds} sibling for rows that are values or maps rather than elements. */
  static List<String> sortedStrings(List<?> results) {
    return results.stream().map(String::valueOf).sorted().toList();
  }

  /**
   * The lenient renderer for a shape whose rows may be either. Elements render as their RID, and
   * everything else — a count terminator's {@code Long}, a projected value — through {@code
   * String.valueOf}.
   */
  static List<String> sortedIdsOrValues(List<?> results) {
    return results.stream()
        .map(v -> v instanceof Vertex vertex ? vertex.id().toString() : String.valueOf(v))
        .sorted()
        .toList();
  }

  /** getConfiguration() is declared nullable but is non-null on a live session. */
  @SuppressWarnings("DataFlowIssue")
  private ContextConfiguration configuration() {
    var session = sessionSupplier.get();
    assert session != null : "the equivalence harness needs a live session to carry the flag";
    var configuration = session.getConfiguration();
    assert configuration != null : "a live session always carries a ContextConfiguration";
    return configuration;
  }
}
