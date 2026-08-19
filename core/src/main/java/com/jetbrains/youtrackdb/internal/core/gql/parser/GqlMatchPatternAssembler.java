package com.jetbrains.youtrackdb.internal.core.gql.parser;

import com.jetbrains.youtrackdb.internal.core.sql.executor.match.MatchExecutionPlanner;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder.MatchPatternBuilder;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchFilter;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Converts GQL visitor output ({@link SQLMatchFilter} list) into the shared MATCH IR
 * ({@link MatchPatternBuilder.PatternIR}) that {@link MatchExecutionPlanner} consumes.
 *
 * <p>Owns GQL-specific defaults that the shared {@link MatchPatternBuilder} deliberately
 * does not apply: anonymous aliases minted as {@code $c<N>} and missing labels
 * resolved to {@code "V"}.
 */
final class GqlMatchPatternAssembler {

  private static final String DEFAULT_TYPE = "V";

  private final MatchPatternBuilder builder = new MatchPatternBuilder();
  private int anonymousCounter = 0;

  /** Registers every filter on a fresh assembler and returns the built IR. */
  static MatchPatternBuilder.PatternIR fromFilters(List<SQLMatchFilter> matchFilters) {
    var assembler = new GqlMatchPatternAssembler();
    for (var filter : matchFilters) {
      assembler.add(filter);
    }
    return assembler.build();
  }

  void add(SQLMatchFilter filter) {
    var rawAlias = filter.getAlias();
    var alias = effectiveAlias(rawAlias, anonymousCounter);
    if (rawAlias == null || rawAlias.isBlank()) {
      anonymousCounter++;
    }
    builder.addNode(
        alias, effectiveType(filter.getClassName(null)), filter.getFilter(), /*optional=*/ false);
  }

  MatchPatternBuilder.PatternIR build() {
    return builder.build();
  }

  private static String effectiveAlias(@Nullable String alias, int anonymousCounter) {
    return (alias != null && !alias.isBlank()) ? alias : ("$c" + anonymousCounter);
  }

  private static String effectiveType(@Nullable String label) {
    return (label == null || label.isBlank()) ? DEFAULT_TYPE : label;
  }
}
