package com.jetbrains.youtrack.db.internal.core.sql.executor.metadata;

import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import java.util.Optional;

public interface IndexFinder {

  enum Operation {
    Eq,
    Gt,
    Lt,
    Ge,
    Le,
    FuzzyEq,
    Range;

    public boolean isRange() {
      return this == Gt || this == Lt || this == Ge || this == Le;
    }
  }

  Optional<IndexCandidate> findExactIndex(MetadataPath fieldName, Object value, CommandContext ctx);

  Optional<IndexCandidate> findByKeyIndex(MetadataPath fieldName, Object value, CommandContext ctx);

  Optional<IndexCandidate> findAllowRangeIndex(
      MetadataPath fieldName, Operation operation, Object value, CommandContext ctx);

  Optional<IndexCandidate> findByValueIndex(MetadataPath fieldName, Object value,
      CommandContext ctx);
}
