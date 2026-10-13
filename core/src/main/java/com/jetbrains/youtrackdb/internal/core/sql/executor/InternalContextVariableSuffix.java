package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.common.util.Resettable;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext.InternalVariable;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIdentifier;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLSuffixIdentifier;

/**
 * A builder-proven internal context reference. Only Result evaluation uses the direct route.
 * Other input forms retain SQLSuffixIdentifier behavior. Copies carry the role, never its value.
 */
public final class InternalContextVariableSuffix extends SQLSuffixIdentifier {

  private final InternalVariable variable;

  public InternalContextVariableSuffix(InternalVariable variable) {
    super(new SQLIdentifier("$" + variable.getVariableName()));
    this.variable = variable;
  }

  @Override
  public Object execute(Result record, CommandContext ctx) {
    if (ctx == null) {
      return super.execute(record, null);
    }
    // Keep both reads: a DynamicVariable can return a different value on each resolution.
    if (ctx.getInternalVariable(variable) != null) {
      var result = ctx.getInternalVariable(variable);
      if (result instanceof Resettable resettable && resettable.isResetable()) {
        resettable.reset();
      }
      if (result instanceof InternalResultSet resultSet) {
        result = resultSet.copy(ctx.getDatabaseSession());
      }
      return result;
    }
    // A missing/null context value retains the ordinary $-prefixed Result-column fallback.
    var name = identifier.getStringValue();
    if (record != null) {
      if (record.isProjection() && record.hasProperty(name)) {
        return record.getProperty(name);
      }
      if (record instanceof ResultInternal internal) {
        if (internal.getMetadataKeys().contains(name)) {
          return internal.getMetadata(name);
        }
        if (internal.getTemporaryProperties().contains(name)) {
          return internal.getTemporaryProperty(name);
        }
      }
    }
    return null;
  }

  @Override
  public InternalContextVariableSuffix copy() {
    return new InternalContextVariableSuffix(variable);
  }
}
