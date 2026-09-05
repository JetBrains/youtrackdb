package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder.MatchLiteralBuilder;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder.MatchWhereBuilder;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBinaryCompareOperator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBooleanExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLEqualsOperator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLGeOperator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLGtOperator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLLeOperator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLLtOperator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLNeqOperator;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.apache.tinkerpop.gremlin.process.traversal.Compare;
import org.apache.tinkerpop.gremlin.process.traversal.Contains;
import org.apache.tinkerpop.gremlin.process.traversal.NotP;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.Text;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.HasContainer;
import org.apache.tinkerpop.gremlin.process.traversal.util.AndP;
import org.apache.tinkerpop.gremlin.process.traversal.util.OrP;

/**
 * Translates a TinkerPop {@link HasContainer} (one {@code has(key, predicate)} clause) into a MATCH
 * {@code WHERE} {@link SQLBooleanExpression}, or declines by returning {@code null}.
 *
 * <h2>Chokepoint role</h2>
 *
 * This is the seam every {@code has(...)}-bearing recogniser routes through, so predicate coverage
 * lives in one reviewable place. It maps the whole Phase-1 predicate surface:
 *
 * <ul>
 *   <li>the six scalar {@link Compare} comparisons ({@code eq} / {@code neq} / {@code lt} / {@code
 *       lte} / {@code gt} / {@code gte}) over a rendered literal;
 *   <li>{@link Contains} membership ({@code within} → {@code IN}, {@code without} → {@code NOT IN});
 *   <li>the {@link Text} / {@code TextP} string predicates ({@code containing} / {@code
 *       startingWith} / {@code endingWith} / {@code regex} and their {@code not*} forms) via the
 *       string-predicate AST nodes ({@code SQLContainsTextCondition} collate transform, {@code
 *       SQLEndsWithCondition}, {@code SQLStartsWithCondition}, find-mode {@code SQLMatchesCondition}).
 *       These translate in <em>strict</em> mode so a present non-String operand throws at execution
 *       exactly as native {@code Text} (String-only) does, rather than diverging by returning rows;
 *   <li>the connectives {@code P.and} / {@code P.or} ({@link AndP} / {@link OrP}) and negation
 *       {@code P.not} ({@link NotP}), recursing into each child. {@code between} / {@code inside} /
 *       {@code outside} need no special case: TinkerPop already decomposes them into an {@code
 *       AndP} / {@code OrP} of scalar comparisons — {@code between(lo, hi)} arrives as {@code
 *       AndP[gte lo, lt hi]}, the right-exclusive {@code [lo, hi)} range, which is why this adapter
 *       never emits an {@code SQLBetweenCondition} (that node is the closed {@code [lo, hi]} range).
 * </ul>
 *
 * <h2>Absent-property guard</h2>
 *
 * Native {@code HasContainer.test} excludes an element that lacks the key: it iterates {@code
 * element.properties(key)}, gets an empty iterator, and returns false without ever consulting the
 * predicate. A translated WHERE clause must reproduce that exclusion. Most YTDB operators already
 * agree — {@code =} / {@code <} / {@code <=} / {@code >} / {@code >=} / {@code IN} / {@code
 * CONTAINSTEXT} all evaluate false on an absent (null) operand — but every predicate whose SQL
 * evaluates <em>true</em> on an absent property needs an explicit {@code key IS DEFINED} guard.
 * That covers {@code neq} ({@code <>} negates equality, which is false on null, so {@code <>} is
 * true on absent) and every negated form ({@code without}, the {@code not*} text predicates, and
 * any {@link NotP}): {@code NOT(false-on-absent)} is true on absent. {@link #guarded} wraps those
 * translations in {@code key IS DEFINED AND (…)}.
 *
 * <h2>NULL comparands</h2>
 *
 * {@code eq(null)} rewrites to a bare {@code key IS NULL}. YTDB {@code IS NULL} is true for both
 * an absent property and a present-null value, which matches native Gremlin membership (pinned in
 * {@code PredicateTraversalEquivalenceTest.nullComparand_nativeMembership_pinnedBeforeEquivalence}).
 * {@code neq(null)} rewrites to {@code NOT(key IS NULL)} ({@code IS NOT NULL}), which is false on
 * absent and present-null and needs no guard. A null comparand on the four range comparisons has no
 * defined membership meaning and declines.
 *
 * <h2>Decline (return {@code null}) — never throw</h2>
 *
 * A recogniser turns a {@code null} here into a whole-traversal decline, so this adapter never
 * throws on an unrecognised predicate. It declines when:
 *
 * <ul>
 *   <li>the key is null or blank, or lands in a reserved namespace (the {@code ~} hidden-key space
 *       from {@code hasLabel} / {@code hasId}, the translator's minted-alias {@code $} space, or
 *       YouTrackDB's record-attribute {@code @} space) — such a key would reach a WHERE identifier
 *       the executor resolves as a reserved token, a context variable, or record metadata rather
 *       than a plain property. {@code ~label} / {@code ~id} narrowing is the recogniser's job before
 *       the adapter runs;
 *   <li>the predicate's bi-predicate is a custom {@code BiPredicate} (not {@link Compare} / {@link
 *       Contains} / {@link Text} / {@link Text.RegexPredicate}) — the translator cannot reproduce
 *       arbitrary user logic;
 *   <li>the comparand is a size-1 collection under {@code eq} / {@code neq}: {@code
 *       QueryOperatorEquals} auto-unboxes a singleton against a scalar, and field cardinality is
 *       unknown at translation time, so the two pipelines could disagree. Size 0 and size ≥2
 *       collections translate normally;
 *   <li>a {@code within} / {@code without} member or a scalar comparand is null, or the comparand
 *       is a type {@link MatchLiteralBuilder} cannot render (e.g. a deferred {@code GValue}
 *       parameter).
 * </ul>
 *
 * <p>A {@link Text} / regex string predicate on a non-String property no longer declines: it
 * translates in strict mode and throws at execution just as native {@code Text} does. The {@link
 * PropertyTypeGate} is now only a routing hint for {@code startingWith} — a declared-String
 * property uses the index-aware prefix range, everything else uses the strict full-scan {@code
 * STARTSWITH} node — never a decline.
 *
 * <h2>Parameter harvest</h2>
 *
 * {@link #bindParams} walks the same dispatch as {@link #toFilter} but only calls {@code
 * bindParam}. Shape extraction uses it so a translation-cache hit does not build a WHERE tree it
 * would throw away. Slot order is therefore one code path: {@code eq(null)} still allocates none,
 * declared-String {@code startingWith} still allocates two.
 */
final class GremlinPredicateAdapter {

  /** Singleton — the adapter is stateless and cheap to share across recogniser calls. */
  static final GremlinPredicateAdapter INSTANCE = new GremlinPredicateAdapter();

  /** Stateless builder for the WHERE AST; construction is trivial so a shared instance is fine. */
  private static final MatchWhereBuilder WHERE = new MatchWhereBuilder();

  /**
   * Success marker for a bind-only walk. Harvest never inspects it as SQL; {@code translate} reuses
   * {@code null} as decline, so success still needs a non-null return.
   */
  private static final SQLBooleanExpression BIND_OK = WHERE.isNull("$");

  /**
   * Decides whether a property key is a declared {@code STRING} schema type, which selects the
   * {@code startingWith} translation form: a declared String uses the index-aware prefix range,
   * everything else uses the strict full-scan {@code STARTSWITH} node (see the class Javadoc). A
   * recogniser builds this from the element's class and the resolved schema — the adapter itself
   * has no schema input, so the gate is passed in per call. Callers with no schema context (unit
   * tests, a generic {@code V} boundary whose leaf class is unknown) pass {@link #NO_TYPE_INFO},
   * which reports every key as not-a-declared-String, so {@code startingWith} routes to strict.
   *
   * <p>{@link #declaredTypeIn} additionally answers whether a declared property's schema type sits
   * in a Gremlin comparability block. When that is true for an order comparison, the adapter skips
   * the per-row {@code key.type() IN [...]} guard — the schema already guarantees the same answer.
   */
  @FunctionalInterface
  interface PropertyTypeGate {
    boolean isDeclaredString(String key);

    /**
     * Whether {@code key} is a declared property whose schema type name is in {@code typeNames}.
     * Default {@code false} — unknown schema keeps the runtime type guard.
     */
    default boolean declaredTypeIn(String key, List<String> typeNames) {
      return false;
    }
  }

  /** Type gate for callers with no schema context: reports no key as a declared String, so a
   *  {@code startingWith} routes to the strict full-scan form (the value type is unknown). */
  static final PropertyTypeGate NO_TYPE_INFO = key -> false;

  /**
   * Schema-backed gate for a known element class: {@link #isDeclaredString} for {@code startingWith}
   * routing, {@link #declaredTypeIn} for dropping order-comparison type guards.
   */
  static PropertyTypeGate schemaGate(RecognitionContext ctx, @Nullable String className) {
    return new PropertyTypeGate() {
      @Override
      public boolean isDeclaredString(String key) {
        return ctx.isDeclaredStringProperty(className, key);
      }

      @Override
      public boolean declaredTypeIn(String key, List<String> typeNames) {
        return ctx.isDeclaredPropertyTypeIn(className, key, typeNames);
      }
    };
  }

  /**
   * Resolves a user-facing Gremlin {@code as(...)} label to the pattern alias the walker minted for
   * the step it labelled, or {@code null} when the label names no node in the pattern. The
   * {@code $matched} row the executor evaluates a label-reference accessor against is keyed on
   * pattern aliases, never on Gremlin labels, so an unresolved label has to decline rather than emit
   * an accessor that silently reads nothing. Used by
   * {@link #toMatchedLabelFilter(String, P, PropertyTypeGate, LabelResolver)}.
   */
  @FunctionalInterface
  interface LabelResolver {

    @Nullable String aliasFor(String userLabel);
  }

  /**
   * Per-call options for the shared predicate dispatch. {@code emitAst} is true for walker
   * {@link #toFilter} and false for harvest {@link #bindParams}, so slot order cannot drift between
   * the two callers.
   */
  private record Translation(
      PropertyTypeGate typeGate,
      @Nullable ParamSink paramSink,
      boolean rangeTypeGuard,
      boolean emitAst) {

    Translation withoutRangeGuard() {
      return rangeTypeGuard ? new Translation(typeGate, paramSink, false, emitAst) : this;
    }
  }

  private GremlinPredicateAdapter() {
    // Singleton — instantiate via INSTANCE.
  }

  /**
   * Translates one {@link HasContainer} into a {@code WHERE} boolean expression with no schema
   * context. A {@code startingWith} then routes to the strict full-scan form. Prefer {@link
   * #toFilter(HasContainer, PropertyTypeGate)} from a recogniser that can resolve the element's
   * class, so a {@code startingWith} on a declared-String property uses the index-aware prefix
   * range.
   */
  @Nullable SQLBooleanExpression toFilter(HasContainer container) {
    return toFilter(container, NO_TYPE_INFO, null, /* rangeTypeGuard= */ false);
  }

  /**
   * Translates one {@link HasContainer} into a {@code WHERE} boolean expression, or returns {@code
   * null} to decline (see the class Javadoc for the decline cases). Never throws. The {@code
   * typeGate} routes {@code startingWith} between the index-aware prefix range (declared String)
   * and the strict full-scan {@code STARTSWITH} node (everything else). When {@code paramSink} is
   * non-null, comparison values bind as positional-parameter slots; when {@code null} (unit tests),
   * values render as inline literals.
   */
  @Nullable SQLBooleanExpression toFilter(HasContainer container, PropertyTypeGate typeGate) {
    return toFilter(container, typeGate, null, /* rangeTypeGuard= */ false);
  }

  /**
   * Translates one {@link HasContainer}, optionally emitting the per-record type guard beside every
   * order comparison ({@code gt} / {@code gte} / {@code lt} / {@code lte}).
   *
   * <p>{@code rangeTypeGuard} must be {@code true} exactly where the container is <em>not</em> folded
   * into {@code YTDBGraphStep} — see {@link RecognitionContext#atTraversalStart()} for why the two
   * positions need different translations, and the guard's own description on
   * {@link #translateCompare} for what it emits.
   *
   * <p>This is the only overload that takes a {@code paramSink}, and therefore the only one a
   * recogniser can call. There is deliberately no shorter form that binds parameters and defaults
   * the guard: a recogniser added later has to state its fold position or fail to compile, rather
   * than inherit a fail-open {@code false} and diverge from native in an unfolded position. The
   * shorter overloads above pass {@code null} for the sink, which makes them unit-test-only —
   * production always binds.
   */
  @Nullable SQLBooleanExpression toFilter(
      HasContainer container,
      PropertyTypeGate typeGate,
      @Nullable ParamSink paramSink,
      boolean rangeTypeGuard) {
    return translateContainer(container, typeGate, paramSink, rangeTypeGuard, /* emitAst= */ true);
  }

  /**
   * Pushes this container's comparison values into {@code paramSink} in the same slot order
   * {@link #toFilter} would, without building a WHERE AST. Shape harvest uses this so a cache hit
   * does not compile SQL it would throw away. A decline binds nothing extra (same as {@code toFilter}
   * returning {@code null}): {@code eq(null)} still allocates no slot, {@code startingWith} on a
   * declared String still allocates two.
   *
   * <p>Uses {@code rangeTypeGuard=true}, matching {@link HasStepRecogniser#contributeShape}: the
   * type-guard AST is skipped, but an order comparison whose literal names no comparability block
   * still declines before binding, as {@code toFilter(..., true)} does.
   */
  void bindParams(HasContainer container, PropertyTypeGate typeGate, ParamSink paramSink) {
    Objects.requireNonNull(paramSink, "paramSink");
    translateContainer(container, typeGate, paramSink, /* rangeTypeGuard= */ true,
        /* emitAst= */ false);
  }

  private @Nullable SQLBooleanExpression translateContainer(
      HasContainer container,
      PropertyTypeGate typeGate,
      @Nullable ParamSink paramSink,
      boolean rangeTypeGuard,
      boolean emitAst) {
    if (container == null) {
      return null;
    }
    var key = container.getKey();
    if (key == null || key.isBlank() || WalkerContext.isReservedHasKey(key)) {
      // Blank or reserved-namespace keys are out of scope. A reserved key (the minted-alias $
      // space, the ~label/~id hidden-key space, or the @-record-attribute space; see
      // WalkerContext.isReservedHasKey) would reach a WHERE identifier the executor resolves as a
      // context variable, a reserved token, or record metadata rather than a plain property, so it
      // declines to keep native behaviour. ~label/~id narrowing is the recogniser's job.
      return null;
    }
    var predicate = container.getPredicate();
    if (predicate == null) {
      return null;
    }
    return translate(
        key, predicate, new Translation(typeGate, paramSink, rangeTypeGuard, emitAst));
  }

  /**
   * Translates one predicate against {@code key}. Dispatches the connectives ({@link NotP} / {@link
   * AndP} / {@link OrP}) before inspecting the bi-predicate — a connective's own bi-predicate is not
   * one of the leaf types, so checking it first would misroute the whole predicate to a decline.
   * Returns {@code null} to decline (propagated to a whole-traversal decline by the caller).
   */
  private @Nullable SQLBooleanExpression translate(
      String key, P<?> predicate, Translation translation) {
    if (predicate instanceof NotP<?> notP) {
      // NotP has no public getter for its wrapped predicate, but negate() returns it (a NotP is
      // built by P.negate(), and negating it back yields the original). Translate the inner
      // predicate positively, negate the SQL, and guard for absent: native NotP excludes an absent
      // property (HasContainer.test's empty iterator is false whatever the inner predicate), so
      // without IS DEFINED the NOT of a false-on-absent inner would wrongly include absent rows.
      var inner = translate(key, notP.negate(), translation);
      if (inner == null) {
        return null;
      }
      return translation.emitAst() ? guarded(key, WHERE.not(inner)) : BIND_OK;
    }
    if (predicate instanceof AndP<?> andP) {
      return combine(key, andP.getPredicates(), /* and= */ true, translation);
    }
    if (predicate instanceof OrP<?> orP) {
      return combine(key, orP.getPredicates(), /* and= */ false, translation);
    }
    // Leaf predicate — dispatch on the concrete bi-predicate type.
    var biPredicate = predicate.getBiPredicate();
    var value = predicate.getValue();
    if (biPredicate instanceof Compare compare) {
      return translateCompare(key, compare, value, translation);
    }
    if (biPredicate instanceof Contains contains) {
      return translateContains(key, contains, value, translation);
    }
    if (biPredicate instanceof Text text) {
      return translateText(key, text, value, translation);
    }
    if (biPredicate instanceof Text.RegexPredicate regex) {
      // Text.regex / Text.notRegex do not use a Text enum constant; their bi-predicate is a
      // RegexPredicate carrying the pattern and a negate flag.
      return translateRegex(key, regex, translation);
    }
    // Custom BiPredicate (a user lambda or a predicate type the translator does not model) —
    // decline rather than guess at its semantics.
    return null;
  }

  /**
   * Translates the children of an {@link AndP} / {@link OrP}, combining them with {@code AND} /
   * {@code OR}. Any child that declines fails the whole connective (all-or-nothing). Each child
   * carries its own absent-property guard where needed, so the combined block reproduces native
   * membership without a connective-level guard.
   *
   * <p>An {@code AndP} whose children are all order comparisons over the same comparability block
   * takes the shared-guard path instead — see {@link #andWithSingleGuard}.
   */
  private @Nullable SQLBooleanExpression combine(
      String key, List<? extends P<?>> children, boolean and, Translation translation) {
    if (children == null || children.isEmpty()) {
      // A connective with no children is degenerate; decline rather than emit an empty block.
      return null;
    }
    // The shared type-guard path only changes the AST (one typeIn, not one per bound). Bind order
    // is the same as the per-child path, so harvest skips the hoist.
    if (and && translation.rangeTypeGuard() && translation.emitAst()) {
      // between / inside decompose into an AndP of two order comparisons over the same block, and
      // per-child guarding would emit that block's type test twice per record. The eligibility test
      // reads the predicates only — no literal is bound before it decides — so an ineligible
      // connective falls through to the per-child path below having pushed nothing into the sink.
      var sharedBlock = sharedOrderComparisonBlock(children);
      if (sharedBlock != null) {
        return andWithSingleGuard(key, children, sharedBlock, translation);
      }
    }
    if (!translation.emitAst()) {
      for (var child : children) {
        if (translate(key, child, translation) == null) {
          return null;
        }
      }
      return BIND_OK;
    }
    var translated = new ArrayList<SQLBooleanExpression>(children.size());
    for (var child : children) {
      var expr = translate(key, child, translation);
      if (expr == null) {
        return null;
      }
      translated.add(expr);
    }
    var operands = translated.toArray(new SQLBooleanExpression[0]);
    return and ? WHERE.and(operands) : WHERE.or(operands);
  }

  /**
   * The comparability block every child of a conjunction shares, or {@code null} when they do not
   * share one — a child that is itself a connective, is not an order comparison, or names a
   * different block (or no block at all) makes the conjunction ineligible for one shared guard.
   *
   * <p>Reads the predicates only: it binds no literal and mutates no sink, so a caller may fall
   * through to the per-child path on {@code null} without having emitted anything.
   */
  private static @Nullable List<String> sharedOrderComparisonBlock(List<? extends P<?>> children) {
    List<String> shared = null;
    for (var child : children) {
      var block = orderComparisonBlock(child);
      if (block == null || (shared != null && !shared.equals(block))) {
        return null;
      }
      shared = block;
    }
    return shared;
  }

  /**
   * The comparability block a leaf order comparison's literal names, or {@code null} when {@code
   * predicate} is not one. The connectives are excluded first for the same reason {@link #translate}
   * dispatches them first: a connective's own bi-predicate is not one of the leaf types, so reading
   * it would misclassify the whole predicate.
   */
  private static @Nullable List<String> orderComparisonBlock(P<?> predicate) {
    if (predicate instanceof NotP<?> || predicate instanceof AndP<?>
        || predicate instanceof OrP<?>) {
      return null;
    }
    if (!(predicate.getBiPredicate() instanceof Compare compare) || !isOrderComparison(compare)) {
      return null;
    }
    var value = predicate.getValue();
    return value == null ? null : comparabilityBlock(value);
  }

  /**
   * Emits {@code key.type() IN [block] AND cmp1 AND cmp2 …} — one type guard for a whole
   * conjunction of order comparisons rather than one per bound. {@code P.between(a, b)} arrives as
   * an {@code AndP} of {@code gte(a)} and {@code lt(b)}; guarding each bound separately made every
   * candidate row re-evaluate the same list of type-name literals twice, and the guard is the
   * expensive half of the conjunct (an {@code IN} over seven string literals for a numeric block,
   * against one property read and one operator call for the comparison it protects).
   *
   * <p>Hoisting is sound only over {@code AND}: {@code g AND (c1 AND c2)} is exactly
   * {@code (g AND c1) AND (g AND c2)}. The children are translated with the guard suppressed, which
   * changes no decline decision — with the block already resolved, the only remaining way a leaf
   * order comparison declines is an unsupported literal class, and that check does not read the
   * guard flag.
   *
   * <p>What this does not reach: two separately spelled containers on the same key
   * ({@code has("age", gt(x)).has("age", lt(y))}) arrive as two independent {@code toFilter} calls
   * and still emit a guard each — deduping across containers belongs to whoever merges an alias's
   * filters. Nor does it remove the surviving guard's per-record cost: the type-name collection is
   * re-derived from the AST on every row, and a declared property on a resolvable class could drop
   * the guard outright at compile time. Both need work outside this class (an executor-side
   * early-calculable {@code IN} right side, and a schema accessor wider than
   * {@link PropertyTypeGate}).
   */
  private @Nullable SQLBooleanExpression andWithSingleGuard(
      String key,
      List<? extends P<?>> children,
      List<String> sharedBlock,
      Translation translation) {
    var operands = new ArrayList<SQLBooleanExpression>(children.size() + 1);
    // Schema already pins the property to this block — one type() IN would only restate that.
    if (!translation.typeGate().declaredTypeIn(key, sharedBlock)) {
      operands.add(WHERE.typeIn(key, sharedBlock));
    }
    var unguarded = translation.withoutRangeGuard();
    for (var child : children) {
      var expr = translate(key, child, unguarded);
      if (expr == null) {
        return null;
      }
      operands.add(expr);
    }
    return WHERE.and(operands.toArray(new SQLBooleanExpression[0]));
  }

  /**
   * Translates a scalar {@link Compare}. Handles the {@code eq(null)} / {@code neq(null)} rewrites
   *, the singleton-collection decline, the {@code neq} absent-property guard, and — when {@code
   * rangeTypeGuard} is set — the per-record type guard on the four order comparisons.
   *
   * <h2>The type guard</h2>
   *
   * In an unfolded position the native arm compares through TinkerPop's {@code
   * GremlinValueComparator}, which types both operands and returns {@code false} — never throwing —
   * whenever they fall in different comparability blocks. Plain SQL has no such rule: it orders a
   * String above an Integer and answers rows native excludes. Emitting
   *
   * <pre>{@code key.type() IN [<the literal's block>] AND key <cmp> literal}</pre>
   *
   * reproduces the comparator's partition per record, without needing the property's declared type
   * (which is unavailable for a schema-less class, an undeclared key, or any position past a hop).
   * The blocks are read off the comparator's own type enum: every {@code java.lang.Number} subtype
   * is one block (so Integer against Long, Double or BigDecimal all compare), String, Boolean and
   * {@code java.util.Date} are each their own, and everything else is either value-dependent
   * (element ids) or unrecognised.
   *
   * <p>Only the four order comparisons are guarded. {@code eq} does not go through the comparator,
   * and {@code neq} is defined as {@code !eq}, so it returns <em>true</em> for operands the order
   * predicates reject — guarding it would invert the answer.
   *
   * <p>A conjunction of order comparisons over one block ({@code between} / {@code inside}) carries
   * a single hoisted guard rather than one per bound; this method emits the per-comparison form only
   * where {@link #andWithSingleGuard} did not already take the conjunction.
   *
   * <p>An order comparison whose literal names no block declines: an element operand's
   * comparability depends on the id <em>value</em> rather than its type, and a class {@code
   * PropertyTypeInternal.getTypeByValue} does not recognise ({@code java.time.*}, {@code UUID}) has
   * no type name to test against. No static conjunct expresses either, so those shapes stay on the
   * native pipeline.
   */
  private @Nullable SQLBooleanExpression translateCompare(
      String key, Compare compare, @Nullable Object value, Translation translation) {
    if (value == null) {
      // Only eq/neq have a defined absent-safe null rewrite; a range comparison against null has no
      // membership meaning and declines.
      return switch (compare) {
        case eq -> translation.emitAst() ? WHERE.isNull(key) : BIND_OK;
        case neq -> translation.emitAst() ? WHERE.not(WHERE.isNull(key)) : BIND_OK;
        default -> null;
      };
    }
    // Singleton-collection equality declines: QueryOperatorEquals auto-unboxes a size-1
    // collection against a scalar, and field cardinality is unknown at translation time, so the
    // translated and native pipelines could disagree. Size 0 and ≥2 fall through and translate.
    if ((compare == Compare.eq || compare == Compare.neq)
        && value instanceof Collection<?> collection && collection.size() == 1) {
      return null;
    }
    // Resolve the comparability block before binding the literal, so a shape that must decline does
    // not first push a positional parameter into the sink. A declared property whose schema type
    // already sits in that block needs no per-row type() guard — drop it at compile time.
    List<String> guardTypeNames = null;
    if (isOrderComparison(compare) && translation.rangeTypeGuard()) {
      guardTypeNames = comparabilityBlock(value);
      if (guardTypeNames == null) {
        return null;
      }
      if (translation.typeGate().declaredTypeIn(key, guardTypeNames)) {
        guardTypeNames = null;
      }
    }
    if (!translation.emitAst()) {
      translation.paramSink().bindParam(value);
      return BIND_OK;
    }
    SQLExpression literal;
    try {
      literal = valueExpression(value, translation.paramSink());
    } catch (IllegalArgumentException unsupportedType) {
      return null;
    }
    var comparison = WHERE.op(key, toOperator(compare), literal);
    if (guardTypeNames != null) {
      return WHERE.and(WHERE.typeIn(key, guardTypeNames), comparison);
    }
    // neq (<>) is true on an absent property (SQLNeqOperator negates QueryOperatorEquals.equals,
    // which is false on a null operand → true), so guard it. The other five comparisons are false
    // on absent already and need no guard.
    return compare == Compare.neq ? guarded(key, comparison) : comparison;
  }

  /** The four comparisons TinkerPop routes through {@code GremlinValueComparator}'s ordering. */
  private static boolean isOrderComparison(Compare compare) {
    return compare == Compare.gt
        || compare == Compare.gte
        || compare == Compare.lt
        || compare == Compare.lte;
  }

  /** Every numeric {@code PropertyType} name — one comparability block, because the comparator
   *  types a numeric operand as a bare {@code java.lang.Number} with no per-subtype whitelist. */
  private static final List<String> NUMERIC_TYPE_NAMES =
      List.of("BYTE", "SHORT", "INTEGER", "LONG", "FLOAT", "DOUBLE", "DECIMAL");

  /**
   * The {@code PropertyType} names a stored value must report for it to be comparable with {@code
   * literal} under {@code GremlinValueComparator}, or {@code null} when the literal's own class
   * names no block (see {@link #translateCompare}'s decline clause).
   *
   * <p>{@code DATE} rides along with {@code DATETIME} because the comparator's Date block is
   * {@code java.util.Date} and its subclasses, which the type accessor may report under either
   * name depending on the stored value.
   */
  private static @Nullable List<String> comparabilityBlock(Object literal) {
    if (literal instanceof Number) {
      return NUMERIC_TYPE_NAMES;
    }
    if (literal instanceof String) {
      return List.of("STRING");
    }
    if (literal instanceof Boolean) {
      return List.of("BOOLEAN");
    }
    if (literal instanceof java.util.Date) {
      return List.of("DATE", "DATETIME");
    }
    return null;
  }

  /**
   * Translates {@link Contains} membership. {@code within} → {@code key IN [..]}; {@code without} →
   * {@code key IS DEFINED AND NOT(key IN [..])} — {@code NOT IN} is true on an absent property, so
   * it takes the absent-property guard.
   */
  private @Nullable SQLBooleanExpression translateContains(
      String key, Contains contains, @Nullable Object value, Translation translation) {
    if (!(value instanceof Collection<?> elements)) {
      return null;
    }
    if (!translation.emitAst()) {
      for (var element : elements) {
        if (element == null) {
          return null;
        }
        translation.paramSink().bindParam(element);
      }
      return BIND_OK;
    }
    var literals = new ArrayList<SQLExpression>(elements.size());
    for (var element : elements) {
      if (element == null) {
        // A null member is not renderable as a literal (toLiteral rejects null); decline whole.
        return null;
      }
      try {
        literals.add(valueExpression(element, translation.paramSink()));
      } catch (IllegalArgumentException unsupportedType) {
        return null;
      }
    }
    return switch (contains) {
      case within -> WHERE.in(key, literals);
      case without -> guarded(key, WHERE.notIn(key, literals));
    };
  }

  /**
   * Translates the {@link Text} string predicates onto the string-predicate AST nodes, in strict
   * mode so a present non-String operand throws at execution exactly as native {@code Text} does
   * (String-only) rather than silently returning rows. The {@code not*} forms are the negation of
   * their positive counterpart and are true on an absent property, so they take the absent-property
   * guard. {@code startingWith} / {@code notStartingWith} route through {@link #startsWithFilter},
   * which picks the index-aware prefix range for a declared-String property and the strict
   * full-scan {@code STARTSWITH} node otherwise; neither declines, so no pathological prefix falls
   * back to native.
   */
  private @Nullable SQLBooleanExpression translateText(
      String key, Text text, @Nullable Object value, Translation translation) {
    if (!(value instanceof String string)) {
      // The predicate's comparand (the search string) is not a String — not a translatable Text
      // predicate. This is the argument, not the property value, so it is a decline, not a throw.
      return null;
    }
    if (!translation.emitAst()) {
      return switch (text) {
        case startingWith, notStartingWith -> bindStartsWith(key, string, translation);
        default -> {
          translation.paramSink().bindParam(string);
          yield BIND_OK;
        }
      };
    }
    return switch (text) {
      case containing -> WHERE.containsText(key, valueExpression(string, translation.paramSink()),
          true);
      case notContaining ->
          guarded(key,
              WHERE.not(WHERE.containsText(key, valueExpression(string, translation.paramSink()),
                  true)));
      case startingWith -> startsWithFilter(key, string, translation);
      case notStartingWith ->
          guarded(key, WHERE.not(startsWithFilter(key, string, translation)));
      case endingWith -> WHERE.endsWith(key, valueExpression(string, translation.paramSink()),
          true);
      case notEndingWith ->
          guarded(key,
              WHERE.not(WHERE.endsWith(key, valueExpression(string, translation.paramSink()),
                  true)));
    };
  }

  /**
   * Chooses the {@code startingWith} translation form. A declared-String property can only hold
   * String values, so it uses the index-aware half-open prefix range ({@link
   * MatchWhereBuilder#startsWith}, a B-tree prefix scan) when a finite range exists. Every other
   * case — an unknown / undeclared type, a declared non-String type, or a declared String whose
   * prefix has no finite range (empty or all-max-code-point) — uses the strict full-scan {@code
   * STARTSWITH} node ({@link MatchWhereBuilder#startsWithStrict}), which throws on a present
   * non-String value like native and matches on a String. An empty prefix under the strict node is
   * {@code startsWith("")}, which matches every present value — native {@code startingWith("")}
   * parity — so nothing declines.
   */
  private SQLBooleanExpression startsWithFilter(
      String key, String prefix, Translation translation) {
    var upperBound = indexAwareUpperBound(key, prefix, translation.typeGate());
    if (upperBound != null) {
      var paramSink = translation.paramSink();
      if (paramSink == null) {
        return WHERE.startsWith(key, prefix);
      }
      var lower = WHERE.op(key, SQLGeOperator.INSTANCE, valueExpression(prefix, paramSink));
      var upper = WHERE.op(key, SQLLtOperator.INSTANCE, valueExpression(upperBound, paramSink));
      return WHERE.and(lower, upper);
    }
    return WHERE.startsWithStrict(key, valueExpression(prefix, translation.paramSink()));
  }

  /**
   * Bind-only counterpart of {@link #startsWithFilter}: same 1-vs-2 slot choice from
   * {@link #indexAwareUpperBound}, no AST.
   */
  private SQLBooleanExpression bindStartsWith(
      String key, String prefix, Translation translation) {
    var upperBound = indexAwareUpperBound(key, prefix, translation.typeGate());
    translation.paramSink().bindParam(prefix);
    if (upperBound != null) {
      translation.paramSink().bindParam(upperBound);
    }
    return BIND_OK;
  }

  /**
   * Exclusive upper bound of the index-aware prefix range, or {@code null} when the walk must use
   * the strict one-slot {@code STARTSWITH} form. Both {@link #startsWithFilter} and
   * {@link #bindStartsWith} read this so a declared-String {@code startingWith} cannot bind two
   * slots in harvest and one in the walker.
   *
   * <p>Two prefixes have no finite range: empty (exclusive upper bound is undefined) and a prefix
   * whose code points are all {@link Character#MAX_CODE_POINT} ({@link
   * MatchWhereBuilder#incrementLastCodePoint} throws).
   */
  private static @Nullable String indexAwareUpperBound(
      String key, String prefix, PropertyTypeGate typeGate) {
    if (!typeGate.isDeclaredString(key) || prefix.isEmpty()) {
      return null;
    }
    try {
      return MatchWhereBuilder.incrementLastCodePoint(prefix);
    } catch (IllegalArgumentException noFiniteUpperBound) {
      return null;
    }
  }

  /**
   * Translates a regex {@link Text.RegexPredicate} onto a find-mode {@code SQLMatchesCondition} in
   * strict mode, so a present non-String value throws at execution as native regex does rather than
   * returning rows. {@code notRegex} (the negate flag) is the negation of the positive match and is
   * true on an absent property, so it takes the absent-property guard. Regex stays case-sensitive
   * regardless of collation (collate-transforming a pattern would change its meaning).
   */
  private @Nullable SQLBooleanExpression translateRegex(
      String key, Text.RegexPredicate regex, Translation translation) {
    var pattern = regex.getPattern();
    if (pattern == null) {
      return null;
    }
    if (!translation.emitAst()) {
      translation.paramSink().bindParam(pattern);
      return BIND_OK;
    }
    var matches = WHERE.matchesRegex(key, valueExpression(pattern, translation.paramSink()), true);
    return regex.isNegate() ? guarded(key, WHERE.not(matches)) : matches;
  }

  /**
   * Renders a comparison value as an inline literal (unit tests) or binds it to the next positional
   * slot (production walks).
   */
  private static SQLExpression valueExpression(Object value, @Nullable ParamSink paramSink) {
    if (paramSink != null) {
      return MatchLiteralBuilder.toInputParameter(paramSink.bindParam(value));
    }
    return MatchLiteralBuilder.toLiteral(value);
  }

  /**
   * Wraps {@code expr} in {@code key IS DEFINED AND (expr)} — the absent-property guard. Used
   * for every translation whose SQL evaluates true on an absent property (the negated forms), so
   * the translated WHERE reproduces native's exclusion of elements that lack the key.
   */
  private static SQLBooleanExpression guarded(String key, SQLBooleanExpression expr) {
    return WHERE.and(WHERE.isDefined(key), expr);
  }

  /**
   * Translates a {@code where(P)} label-reference predicate into a {@code WHERE} boolean comparing
   * {@code $matched.<alias>} accessors — the <em>pattern alias</em> each Gremlin label resolves to,
   * since the {@code $matched} row the executor evaluates the accessors against is keyed on aliases
   * and never on labels. {@code startLabel} is the optional Gremlin scope label from
   * {@code where(startLabel, P)}; when {@code null}, the left-hand side is the boundary alias's
   * {@code @rid}. Returns {@code null} to decline (propagated as a whole-traversal decline).
   *
   * @param labelResolver resolves each label the predicate names to its pattern alias. A label it
   *     cannot resolve declines the whole translation rather than emitting an accessor that would
   *     silently read nothing.
   */
  @Nullable SQLBooleanExpression toMatchedLabelFilter(
      @Nullable String startLabel,
      P<?> predicate,
      PropertyTypeGate typeGate,
      LabelResolver labelResolver) {
    if (predicate == null) {
      return null;
    }
    var left = leftMatchedOperand(startLabel, labelResolver);
    if (left == null) {
      return null;
    }
    return translateMatchedLabelPredicate(left, predicate, typeGate, labelResolver);
  }

  private @Nullable SQLExpression leftMatchedOperand(
      @Nullable String startLabel, LabelResolver labelResolver) {
    if (startLabel == null) {
      return WHERE.boundaryRidExpression();
    }
    var alias = resolvedAlias(startLabel, labelResolver);
    return alias == null ? null : WHERE.matchedAccess(alias, "@rid");
  }

  /**
   * The pattern alias {@code userLabel} names, or {@code null} when the label is unusable — blank,
   * inside the translator's reserved {@code $} namespace, or bound to no pattern node.
   */
  private static @Nullable String resolvedAlias(String userLabel, LabelResolver labelResolver) {
    if (userLabel.isBlank() || userLabel.startsWith("$")) {
      return null;
    }
    return labelResolver.aliasFor(userLabel);
  }

  private @Nullable SQLBooleanExpression translateMatchedLabelPredicate(
      SQLExpression left, P<?> predicate, PropertyTypeGate typeGate, LabelResolver labelResolver) {
    if (predicate instanceof NotP<?> notP) {
      var inner = translateMatchedLabelPredicate(left, notP.negate(), typeGate, labelResolver);
      return inner == null ? null : WHERE.not(inner);
    }
    if (predicate instanceof AndP<?> andP) {
      return combineMatchedLabelOperands(
          left, andP.getPredicates(), /* and= */ true, typeGate, labelResolver);
    }
    if (predicate instanceof OrP<?> orP) {
      return combineMatchedLabelOperands(
          left, orP.getPredicates(), /* and= */ false, typeGate, labelResolver);
    }
    var biPredicate = predicate.getBiPredicate();
    var value = predicate.getValue();
    if (biPredicate instanceof Compare compare && value instanceof String refLabel) {
      var alias = resolvedAlias(refLabel, labelResolver);
      if (alias == null) {
        return null;
      }
      var right = WHERE.matchedAccess(alias, "@rid");
      return WHERE.compareExpressions(left, toOperator(compare), right);
    }
    return null;
  }

  private @Nullable SQLBooleanExpression combineMatchedLabelOperands(
      SQLExpression left,
      List<? extends P<?>> children,
      boolean and,
      PropertyTypeGate typeGate,
      LabelResolver labelResolver) {
    if (children == null || children.isEmpty()) {
      return null;
    }
    var translated = new ArrayList<SQLBooleanExpression>(children.size());
    for (var child : children) {
      var expr = translateMatchedLabelPredicate(left, child, typeGate, labelResolver);
      if (expr == null) {
        return null;
      }
      translated.add(expr);
    }
    var operands = translated.toArray(new SQLBooleanExpression[0]);
    return and ? WHERE.and(operands) : WHERE.or(operands);
  }

  /**
   * Maps a TinkerPop {@link Compare} onto the matching SQL comparison operator. The {@code switch}
   * is exhaustive over the six scalar comparisons; a bi-predicate that is not one of them was
   * already ruled out by the {@code instanceof Compare} gate in {@link #translate}.
   */
  private static @Nonnull SQLBinaryCompareOperator toOperator(Compare compare) {
    return switch (compare) {
      case eq -> SQLEqualsOperator.INSTANCE;
      case neq -> SQLNeqOperator.INSTANCE;
      case lt -> SQLLtOperator.INSTANCE;
      case lte -> SQLLeOperator.INSTANCE;
      case gt -> SQLGtOperator.INSTANCE;
      case gte -> SQLGeOperator.INSTANCE;
    };
  }
}
