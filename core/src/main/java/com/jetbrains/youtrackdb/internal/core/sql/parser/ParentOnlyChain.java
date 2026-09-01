package com.jetbrains.youtrackdb.internal.core.sql.parser;

import javax.annotation.Nullable;

/// Recognises the closed set of expression shapes whose value provably does not depend on the
/// record the expression is evaluated against — a chain rooted at the `$parent` context variable.
///
/// ## Why a closed whitelist and not [SQLExpression#refersToParent()]
///
/// `refersToParent()` is *existential*: it answers "does a `$parent` reference occur anywhere in
/// this expression". That is the right question for its ten existing callers and it is deliberately
/// left alone. It is the wrong question for the correlated RID fetch, which evaluates the
/// expression exactly once per parent row with a `null` current record
/// (`FetchFromCorrelatedRidStep`). Any sub-expression that reads the current record then reads
/// `null` — or worse, a stale record — and rows are silently lost relative to the scan-plus-filter
/// plan the fetch replaces.
///
/// So this predicate is *universal*: every node of the expression must be provably independent of
/// the current record, and any node shape not explicitly proven safe is rejected. Rejection is
/// always safe: the caller falls through to the indexed-function handler, then the index handler,
/// then the class scan plus filter, which is the behaviour that existed before the fetch step.
///
/// ## Accepted
///
/// A single base expression with no arithmetic operator and no boolean condition, whose root
/// identifier is the plain identifier `$parent`, followed by zero or more links, where each link is
/// either
///
///  * a plain suffix identifier (`.name`, `.$current`, `.$parent`, `.anyProperty`), or
///  * a single-index bracket modifier (`[0]`, `['key']`, `[:param]`) whose index is a literal
///    number, a literal string, a bind parameter, or itself a `$parent`-rooted chain.
///
/// The root and every link resolve through [SQLSuffixIdentifier], which reads only the value handed
/// to it by the previous link (the parent context for the root). It never consults the
/// current-record system variable, so the whole chain is current-record independent.
///
/// ## Rejected, with no exception
///
///  * **Every function call and every method call, anywhere.** [SQLFunctionCall],
///    [SQLMethodCall] and `SQLFunctionEval` fall back to
///    [com.jetbrains.youtrackdb.internal.core.command.CommandContext#VAR_CURRENT] when the record
///    argument is `null`, so they read the inner row with no signal in the syntax tree. Verified
///    counterexample: `@rid = ifnull($parent.$current.missing, first(out('GE')))` returns three
///    rows through scan plus filter and zero rows through the fetch step. Determinism of the
///    function is irrelevant — the hidden read is what breaks parity.
///  * **Any subquery**, which parses into a parenthesis expression or a collection literal under
///    the level-zero identifier, and is evaluated against the current record.
///  * **A bracket modifier carrying a condition, a range, or a right binary condition**
///    (`[name = 'x']`, `[0..2]`, `[= 3]`). A condition body is a full boolean expression evaluated
///    per element and can read anything; a range selector and a right binary condition are simply
///    not proven safe here.
///  * **Any identifier not reachable from the `$parent` root** — a bare property name, `@this`, a
///    record attribute, `*`, or a `$current` that is not preceded by `$parent`. These read the
///    current record by definition.
///  * **Arithmetic and boolean composition** (`$parent.a + 1`, `$parent.a || $parent.b`), a `CASE`
///    expression, a JSON literal, a RID literal, and the `null` / `true` / `false` literals: none
///    of them is a chain, so none is admitted.
///
/// The class is a same-package accessor because [SQLModifier], [SQLArraySelector] and
/// [SQLBaseIdentifier] keep the fields this walk needs `protected` with no public getter. It is
/// final, has a private constructor, and only reads the tree.
public final class ParentOnlyChain {

  /// The one root identifier this whitelist admits.
  private static final String PARENT_VARIABLE = "$parent";

  private ParentOnlyChain() {
  }

  /// Whether `expression` is a `$parent`-rooted chain as defined by this class, and therefore may
  /// be evaluated once with a `null` current record without changing the rows a scan plus filter
  /// would return.
  public static boolean isParentOnlyChain(@Nullable SQLExpression expression) {
    if (expression == null) {
      return false;
    }
    // `execute` consults, in order: isNull, rid, mathExpression, and only then the
    // arrayConcat / boolean / json / booleanValue / literalValue payloads. Requiring the first
    // three to select the mathExpression branch makes the later payloads unreachable, so the walk
    // below decides the whole expression.
    if (expression.isNull || expression.rid != null) {
      return false;
    }
    // A math expression with an operator keeps its wrapper node; MathExpression() unwraps a
    // single operand to the operand itself. So "is exactly a base expression" is precisely
    // "one operand, no arithmetic operator".
    if (!(expression.mathExpression instanceof SQLBaseExpression base)) {
      return false;
    }
    return isParentOnlyChain(base);
  }

  /// Whether `base` is a `$parent`-rooted identifier followed by an accepted modifier chain.
  private static boolean isParentOnlyChain(SQLBaseExpression base) {
    // A number, a string literal or a bind parameter is not rooted at $parent, so it is not a
    // chain. Those shapes are plan-time resolvable and never reach this gate anyway.
    if (base.number != null || base.string != null || base.inputParam != null) {
      return false;
    }
    var identifier = base.getIdentifier();
    if (identifier == null || !isParentRoot(identifier)) {
      return false;
    }
    return isAcceptedModifierChain(base.modifier);
  }

  /// Whether the base identifier is the bare `$parent` context variable.
  ///
  /// A level-zero identifier is a function call, `@this`, or a collection literal — all rejected.
  /// The suffix must be the plain identifier `$parent`: a record attribute or `*` is rejected.
  private static boolean isParentRoot(SQLBaseIdentifier identifier) {
    if (identifier.levelZero != null) {
      return false;
    }
    var suffix = identifier.suffix;
    return suffix != null && isPlainIdentifier(suffix)
        && PARENT_VARIABLE.equalsIgnoreCase(suffix.identifier.getStringValue());
  }

  /// Walks the `next` chain of modifiers, requiring every link to be accepted.
  private static boolean isAcceptedModifierChain(@Nullable SQLModifier modifier) {
    for (var link = modifier; link != null; link = link.next) {
      if (!isAcceptedLink(link)) {
        return false;
      }
    }
    return true;
  }

  /// Whether one link of the chain is accepted, ignoring its `next`.
  private static boolean isAcceptedLink(SQLModifier link) {
    // Rejected outright: a method call hides a current-record read, and a condition, a range or a
    // right binary condition is an evaluated body this whitelist does not prove safe.
    if (link.methodCall != null
        || link.condition != null
        || link.arrayRange != null
        || link.rightBinaryCondition != null) {
      return false;
    }
    if (link.suffix != null) {
      // `.name` — a dotted property step, never bracketed.
      return !link.squareBrackets && isPlainIdentifier(link.suffix);
    }
    if (link.arraySingleValues != null) {
      // `[index]` — exactly one index. A multi-index selector such as `[0,1]` builds a list and is
      // not the single-index shape this whitelist describes, so it is rejected.
      return link.squareBrackets
          && link.arraySingleValues.items.size() == 1
          && isAcceptedIndex(link.arraySingleValues.items.getFirst());
    }
    // No payload matched: an unknown or empty link is rejected rather than assumed safe.
    return false;
  }

  /// Whether a bracket index is current-record independent: a literal, a bind parameter, or a
  /// nested `$parent`-rooted chain.
  private static boolean isAcceptedIndex(SQLArraySelector selector) {
    // A RID selector is dead weight here: SQLArraySelector.getValue ignores the rid field and
    // yields null, so it is rejected rather than silently treated as a constant.
    if (selector.rid != null) {
      return false;
    }
    if (selector.integer != null || selector.inputParam != null) {
      return true;
    }
    var expression = selector.expression;
    return expression != null
        && (isConstantIndex(expression) || isParentOnlyChain(expression));
  }

  /// Whether the index expression is a bare literal number, literal string, or bind parameter —
  /// the forms `[0]`, `['key']` and `[:param]` parse into.
  private static boolean isConstantIndex(SQLExpression expression) {
    if (expression.isNull || expression.rid != null) {
      return false;
    }
    if (!(expression.mathExpression instanceof SQLBaseExpression base)) {
      return false;
    }
    // A modifier on the index would be a chain of its own; only a naked literal counts as
    // constant. An identifier root is not constant, so it must be absent here.
    if (base.modifier != null || base.getIdentifier() != null) {
      return false;
    }
    return base.number != null || base.string != null || base.inputParam != null;
  }

  /// Whether a suffix is a plain named identifier, as opposed to a record attribute or `*`.
  private static boolean isPlainIdentifier(SQLSuffixIdentifier suffix) {
    return suffix.identifier != null && suffix.recordAttribute == null && !suffix.star;
  }
}
