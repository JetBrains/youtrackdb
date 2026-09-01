package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.core.sql.parser.ParentChainAstAccess;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLArraySelector;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBaseExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBaseIdentifier;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLModifier;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLSuffixIdentifier;
import javax.annotation.Nullable;

/**
 * Recognises the closed set of expression shapes whose value provably does not depend on the record
 * the expression is evaluated against — a chain rooted at the {@code $parent} context variable.
 *
 * <h2>Why a closed whitelist and not {@code SQLExpression.refersToParent()}</h2>
 *
 * <p>{@code refersToParent()} is <i>existential</i>: it answers "does a {@code $parent} reference
 * occur anywhere in this expression". That is the right question for its ten existing callers and
 * it is deliberately left alone. It is the wrong question for the correlated RID fetch, which
 * evaluates the expression exactly once per parent row with a {@code null} current record (see
 * {@link FetchFromCorrelatedRidStep}). Any sub-expression that reads the current record then reads
 * {@code null} — or worse, a stale record — and rows are silently lost relative to the
 * scan-plus-filter plan the fetch replaces.
 *
 * <p>So this predicate is <i>universal</i>: every node of the expression must be provably
 * independent of the current record, and any node shape not explicitly proven safe is rejected.
 * Rejection is always safe: the caller falls through to the indexed-function handler, then the
 * index handler, then the class scan plus filter, which is the behaviour that existed before the
 * fetch step.
 *
 * <h2>Accepted</h2>
 *
 * <p>A single base expression with no arithmetic operator and no boolean condition, whose root
 * identifier is the plain identifier {@code $parent}, followed by zero or more links, where each
 * link is either
 *
 * <ul>
 *   <li>a plain suffix identifier ({@code .name}, {@code .$current}, {@code .$parent},
 *       {@code .anyProperty}), or
 *   <li>a single-index bracket modifier ({@code [0]}, {@code ['key']}, {@code [:param]}) whose
 *       index is a literal number, a literal string, a bind parameter, or itself a
 *       {@code $parent}-rooted chain.
 * </ul>
 *
 * <p>The root and every link resolve through {@code SQLSuffixIdentifier}. A link reads the value
 * the previous link handed down, and it may <i>also</i> read the current-record context variable.
 * A link whose name starts with a dollar sign is resolved by
 * {@code BasicCommandContext.getVariable}, whose {@code current} case returns
 * {@code VAR_CURRENT} (see {@code BasicCommandContext.java:236}), and a property miss falls back
 * the same way. So {@code $parent.$current.ref.$current} really does read that slot: a chain is
 * not, on its own, independent of the current-record variable.
 *
 * <p>The chain is nonetheless safe here, for a different reason. {@link LetQueryStep} installs an
 * intermediate context that owns {@code VAR_CURRENT} for the correlated subquery (see
 * {@code LetQueryStep.java:104-107}), so the slot holds the same parent row whichever inner plan
 * runs. The correlated fetch and the class scan plus filter therefore read the same value and
 * return the same rows.
 *
 * <p>That is an invariant of the surrounding step, not of the chain itself. Any future widening of
 * this gate, and any new caller that evaluates an admitted chain outside a {@link LetQueryStep},
 * must re-examine this point before relying on it.
 *
 * <h2>Rejected, with no exception</h2>
 *
 * <ul>
 *   <li><b>Every function call and every method call, anywhere.</b> {@code SQLFunctionCall} (see
 *       {@code SQLFunctionCall.java:119-127}) and {@code SQLMethodCall} fall back to the
 *       current-record system variable when the record argument is {@code null}, so they read the
 *       inner row with no signal in the syntax tree. The asymmetry is what breaks parity: under
 *       the scan plus filter the record argument is the inner row, so no fallback fires, while
 *       under the fetch it is {@code null}, so the call silently changes subject. An admitted
 *       chain link has no such asymmetry, because it reads the same slot under both plans.
 *       Verified counterexample:
 *       {@code @rid = ifnull($parent.$current.missing, first(out('GE')))} returns three rows
 *       through scan plus filter and zero rows through the fetch step. Determinism of the function
 *       is irrelevant, because the silent change of subject is the defect.
 *   <li><b>Any subquery</b>, which parses into a parenthesis expression or a collection literal
 *       under the level-zero identifier, and is evaluated against the current record.
 *   <li><b>A bracket modifier carrying a condition, a range, or a right binary condition</b>
 *       ({@code [name = 'x']}, {@code [0..2]}, {@code [= 3]}). A condition body is a full boolean
 *       expression evaluated per element and can read anything. A range selector and a right
 *       binary condition are simply not proven safe here.
 *   <li><b>Any identifier not reachable from the {@code $parent} root</b> — a bare property name,
 *       {@code @this}, a record attribute, {@code *}, or a {@code $current} that is not preceded by
 *       {@code $parent}. These read the current record by definition.
 *   <li><b>Arithmetic and boolean composition</b> ({@code $parent.a + 1},
 *       {@code $parent.a || $parent.b}), a {@code CASE} expression, a JSON literal, a RID literal,
 *       and the {@code null} / {@code true} / {@code false} literals: none of them is a chain, so
 *       none is admitted.
 * </ul>
 *
 * <p>The class lives here, next to {@link SelectExecutionPlanner}, rather than in the parse-node
 * package it walks. The build excludes {@code internal/core/sql/parser} from Spotless, from
 * ErrorProne and NullAway, and from the JaCoCo report, so decision logic placed there would escape
 * all three gates. The few parse-node fields that have no public getter are read through
 * {@link ParentChainAstAccess}, a branch-free same-package accessor.
 */
final class ParentOnlyChain {

  /** The one root identifier this whitelist admits. */
  private static final String PARENT_VARIABLE = "$parent";

  private ParentOnlyChain() {
  }

  /**
   * Returns whether {@code expression} is a {@code $parent}-rooted chain as defined by this class.
   * Such an expression may be evaluated once with a {@code null} current record without changing
   * the rows a scan plus filter would return.
   *
   * @param expression the right-hand side of the RID equality, possibly {@code null}
   * @return {@code true} when the expression is admitted by the whitelist
   */
  static boolean isParentOnlyChain(@Nullable SQLExpression expression) {
    if (expression == null) {
      return false;
    }
    // SQLExpression.execute consults, in order: isNull, rid, mathExpression, and only then the
    // arrayConcat / boolean / json / booleanValue / literalValue payloads. Requiring the first
    // three to select the mathExpression branch makes the later payloads unreachable, so the walk
    // below decides the whole expression.
    if (ParentChainAstAccess.isNull(expression) || expression.getRid() != null) {
      return false;
    }
    // A math expression with an operator keeps its wrapper node; the grammar's MathExpression()
    // unwraps a single operand to the operand itself. So "is exactly a base expression" is
    // precisely "one operand, no arithmetic operator".
    if (!(expression.getMathExpression() instanceof SQLBaseExpression base)) {
      return false;
    }
    return isParentOnlyChain(base);
  }

  /**
   * Returns whether {@code base} is a {@code $parent}-rooted identifier followed by an accepted
   * modifier chain.
   *
   * @param base the sole operand of the expression
   * @return {@code true} when the base expression is admitted by the whitelist
   */
  private static boolean isParentOnlyChain(SQLBaseExpression base) {
    // A number, a string literal or a bind parameter is not rooted at $parent, so it is not a
    // chain. Those shapes are plan-time resolvable and never reach this gate anyway.
    if (ParentChainAstAccess.number(base) != null
        || ParentChainAstAccess.stringLiteral(base) != null
        || ParentChainAstAccess.inputParam(base) != null) {
      return false;
    }
    var identifier = base.getIdentifier();
    if (identifier == null || !isParentRoot(identifier)) {
      return false;
    }
    return isAcceptedModifierChain(base.getModifier());
  }

  /**
   * Returns whether the base identifier is the bare {@code $parent} context variable.
   *
   * <p>A level-zero identifier is a function call, {@code @this}, or a collection literal — all
   * rejected. The suffix must be the plain identifier {@code $parent}: a record attribute or
   * {@code *} is rejected.
   *
   * @param identifier the root identifier of the base expression
   * @return {@code true} when the root is the parent context variable
   */
  private static boolean isParentRoot(SQLBaseIdentifier identifier) {
    if (identifier.getLevelZero() != null) {
      return false;
    }
    var suffix = identifier.getSuffix();
    return suffix != null && isPlainIdentifier(suffix)
        && PARENT_VARIABLE.equalsIgnoreCase(suffix.getIdentifier().getStringValue());
  }

  /**
   * Returns whether every link of the modifier chain is accepted, walking the {@code next} pointers
   * from {@code modifier} to the end.
   *
   * @param modifier the first link, or {@code null} when the chain is empty
   * @return {@code true} when every link is accepted, including the empty-chain case
   */
  private static boolean isAcceptedModifierChain(@Nullable SQLModifier modifier) {
    for (var link = modifier; link != null; link = link.getNext()) {
      if (!isAcceptedLink(link)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Returns whether one link of the chain is accepted, ignoring its {@code next} pointer.
   *
   * @param link the chain link to classify
   * @return {@code true} when the link is a plain dotted step or a single constant index
   */
  private static boolean isAcceptedLink(SQLModifier link) {
    // Rejected outright: a method call hides a current-record read, and a condition, a range or a
    // right binary condition is an evaluated body this whitelist does not prove safe.
    if (link.getMethodCall() != null
        || ParentChainAstAccess.condition(link) != null
        || ParentChainAstAccess.arrayRange(link) != null
        || ParentChainAstAccess.rightBinaryCondition(link) != null) {
      return false;
    }
    var suffix = link.getSuffix();
    if (suffix != null) {
      // `.name` — a dotted property step, never bracketed.
      return !ParentChainAstAccess.hasSquareBrackets(link) && isPlainIdentifier(suffix);
    }
    var indexes = ParentChainAstAccess.arraySingleValues(link);
    if (indexes != null) {
      // `[index]` — exactly one index. A multi-index selector such as `[0,1]` builds a list and is
      // not the single-index shape this whitelist describes, so it is rejected.
      var items = ParentChainAstAccess.items(indexes);
      return ParentChainAstAccess.hasSquareBrackets(link)
          && items.size() == 1
          && isAcceptedIndex(items.getFirst());
    }
    // No payload matched: an unknown or empty link is rejected rather than assumed safe.
    return false;
  }

  /**
   * Returns whether a bracket index is current-record independent, which means a literal, a bind
   * parameter, or a nested {@code $parent}-rooted chain.
   *
   * @param selector the single index selector of a bracket step
   * @return {@code true} when the index cannot read the current record
   */
  private static boolean isAcceptedIndex(SQLArraySelector selector) {
    // A RID selector is dead weight here: SQLArraySelector.getValue ignores the rid field and
    // yields null, so it is rejected rather than silently treated as a constant.
    if (ParentChainAstAccess.selectorRid(selector) != null) {
      return false;
    }
    if (ParentChainAstAccess.selectorInteger(selector) != null
        || ParentChainAstAccess.selectorInputParam(selector) != null) {
      return true;
    }
    var expression = ParentChainAstAccess.selectorExpression(selector);
    return expression != null
        && (isConstantIndex(expression) || isParentOnlyChain(expression));
  }

  /**
   * Returns whether the index expression is a bare literal number, literal string, or bind
   * parameter, which are the forms {@code [0]}, {@code ['key']} and {@code [:param]} parse into.
   *
   * @param expression the expression payload of an index selector
   * @return {@code true} when the index is a naked literal or bind parameter
   */
  private static boolean isConstantIndex(SQLExpression expression) {
    if (ParentChainAstAccess.isNull(expression) || expression.getRid() != null) {
      return false;
    }
    if (!(expression.getMathExpression() instanceof SQLBaseExpression base)) {
      return false;
    }
    // A modifier on the index would be a chain of its own, so only a naked literal counts as
    // constant. An identifier root is not constant either, so it must be absent here.
    if (base.getModifier() != null || base.getIdentifier() != null) {
      return false;
    }
    return ParentChainAstAccess.number(base) != null
        || ParentChainAstAccess.stringLiteral(base) != null
        || ParentChainAstAccess.inputParam(base) != null;
  }

  /**
   * Returns whether a suffix is a plain named identifier, as opposed to a record attribute or
   * {@code *}.
   *
   * @param suffix the suffix to classify
   * @return {@code true} when the suffix carries a plain identifier
   */
  private static boolean isPlainIdentifier(SQLSuffixIdentifier suffix) {
    return suffix.getIdentifier() != null
        && suffix.getRecordAttribute() == null
        && !suffix.isStar();
  }
}
