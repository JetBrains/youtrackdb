package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;

/**
 *
 */
public class FetchFromIndexValuesStep extends FetchFromIndexStep {

  public FetchFromIndexValuesStep(
      IndexSearchDescriptor desc, boolean orderAsc, CommandContext ctx, boolean profilingEnabled) {
    super(desc, orderAsc, ctx, profilingEnabled);
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    if (isOrderAsc()) {
      return ExecutionStepInternal.getIndent(depth, indent)
          + "+ FETCH FROM INDEX VAUES ASC "
          + desc.getIndex().getName();
    } else {
      return ExecutionStepInternal.getIndent(depth, indent)
          + "+ FETCH FROM INDEX VAUES DESC "
          + desc.getIndex().getName();
    }
  }

  @Override
  public boolean canBeCached() {
    return false;
  }
}
