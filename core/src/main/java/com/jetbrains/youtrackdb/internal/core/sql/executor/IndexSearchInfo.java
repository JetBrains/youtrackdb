package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import javax.annotation.Nonnull;

/**
 * Immutable metadata about a single indexed property, passed to
 * {@link com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBooleanExpression#isIndexAware}
 * to determine whether a WHERE condition can leverage an index on this field.
 *
 * @param fieldName         the property name in the schema (e.g. "city")
 * @param allowsRangeQueries true if the index supports ordered iteration (B-tree)
 * @param isMap             true if the property type is EMBEDDEDMAP
 * @param indexedByKey      true if the index covers the map's keys
 * @param indexedByValue    true if the index covers the map's values
 * @param schemaClass       the schema class owning this property
 * @param ctx               the command context (for resolving parameters)
 *
 * @see SelectExecutionPlanner#buildIndexSearchDescriptor
 */
public record IndexSearchInfo(
    String fieldName,
    boolean allowsRangeQueries,
    boolean isMap,
    boolean indexedByKey,
    boolean indexedByValue,
    @Nonnull SchemaClass schemaClass,
    CommandContext ctx) {

}
