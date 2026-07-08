package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBaseExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBinaryCondition;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBooleanExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLEqualsOperator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLRecordAttribute;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;

/**
 * Shared factory for the {@code @class} narrowing AST that recognisers attach to a pattern node
 * when a Gremlin step names an <em>explicit</em> schema class — the folded {@code hasLabel(L)},
 * added later. It is the one place {@code @class} narrowing is produced, so the "explicit classes
 * only" rule lives in a single reviewable seam.
 *
 * <h2>Explicit classes only — never a bare chain hop</h2>
 *
 * A bare chain hop ({@code out(L)} / {@code in(L)} / {@code both(L)}) and the start step do
 * <em>not</em> narrow by class: their target roots at the generic {@code V} class polymorphically
 * so subclass instances are kept, matching native Gremlin, which never class-filters a hop target
 * (the no-narrowing rule — see {@link VertexStepRecogniser} and {@link StartStepRecogniser}).
 * Narrowing is
 * correct only when the user named a concrete class ({@code hasLabel('Person')}), where the exact
 * class is the intended filter. This helper is therefore called only from explicit-class
 * recognisers, never from the bare-hop path.
 *
 * <h2>Exact class, via {@code @class}</h2>
 *
 * The narrowing is an exact-class predicate {@code @class = 'ClassName'}, not the polymorphic MATCH
 * {@code class:} node type: {@code @class} reads the record's own leaf class, so the predicate
 * selects exactly the named class. {@code @class} is a record attribute (like {@code @rid}), so the
 * left side is an {@link SQLRecordAttribute}, not a plain property identifier — the runtime
 * evaluator dispatches the two shapes through different paths.
 */
final class MatchClassFilters {

  private MatchClassFilters() {
    // Static factory — no instances.
  }

  /**
   * Builds the boolean expression {@code @class = 'className'} for an explicit, non-blank class
   * name. Throws {@link IllegalArgumentException} on a null or blank name: an explicit-class
   * recogniser only reaches this helper once it has a concrete user-named class, so a blank name is
   * a caller bug, not a runtime shape — failing loud beats emitting a silently-wrong {@code @class =
   * ''} predicate.
   */
  static SQLBooleanExpression classEquals(String className) {
    if (className == null || className.isBlank()) {
      throw new IllegalArgumentException("class name for @class narrowing must be non-blank");
    }
    var classAttr = new SQLRecordAttribute(-1);
    classAttr.setName("@class");
    var leftExpr = new SQLExpression(classAttr, null);

    var condition = new SQLBinaryCondition(-1);
    condition.setLeft(leftExpr);
    condition.setOperator(SQLEqualsOperator.INSTANCE);
    condition.setRight(stringLiteral(className));
    return condition;
  }

  /**
   * Wraps {@link #classEquals(String)} in an {@link SQLWhereClause} so it can drop straight into
   * the MATCH IR's {@code aliasFilters} map for the narrowed alias.
   */
  static SQLWhereClause classEqualsWhere(String className) {
    var clause = new SQLWhereClause(-1);
    clause.setBaseExpression(classEquals(className));
    return clause;
  }

  /**
   * Builds a string-literal {@link SQLExpression} for the right side of the {@code @class}
   * comparison, mirroring the shape {@code MatchWhereBuilder} uses for its own string literals so
   * the condition is interchangeable with a parsed one.
   */
  private static SQLExpression stringLiteral(String value) {
    var expr = new SQLExpression(-1);
    expr.setMathExpression(new SQLBaseExpression(value));
    return expr;
  }
}
