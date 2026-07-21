package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLPositionalParameter;

/**
 * Allocates positional-parameter slots ({@code ?}) during predicate AST emission. {@link
 * RecognitionContext} extends this so recognisers can bind values while walking; {@link
 * GremlinPredicateAdapter} accepts only this narrow sink — not the full walk context — so predicate
 * translation cannot reach boundary state, pattern contributions, or sub-walk seams.
 *
 * <p>Slot numbering is shape-pure: each call allocates the next slot in bind order, independent of
 * the bound value. Structural tokens (class names, {@code ~label}, RIDs) stay inline and must not
 * call this.
 */
@FunctionalInterface
interface ParamSink {

  SQLPositionalParameter bindParam(Object value);
}
