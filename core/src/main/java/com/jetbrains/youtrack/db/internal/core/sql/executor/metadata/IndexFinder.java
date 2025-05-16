package com.jetbrains.youtrack.db.internal.core.sql.executor.metadata;

import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import javax.annotation.Nullable;

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

  @Nullable
  IndexCandidate findExactIndex(IndexMetadataPath fieldName, Object value, CommandContext ctx);

  @Nullable
  IndexCandidate findByKeyIndex(IndexMetadataPath fieldName, Object value,
      CommandContext ctx);

  @Nullable
  IndexCandidate findAllowRangeIndex(
      IndexMetadataPath fieldName, Operation operation, Object value, CommandContext ctx);

  @Nullable
  IndexCandidate findByValueIndex(IndexMetadataPath fieldName, Object value,
      CommandContext ctx);
}
