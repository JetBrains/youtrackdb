package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.index.Index;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLAndBlock;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBaseExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBinaryCondition;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBooleanExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLEqualsOperator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLNotBlock;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLOrBlock;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Invocation-local equality-source proof. Only the exact four-step indexed element pipeline is
 * supported. The same index object fixes definition, collation, null and duplicate semantics.
 * Evidence belongs to the cold invocation only. Cache the resulting decision, never this object.
 */
public final class NativeStartEvidence {

  private final String className;
  private final String property;
  private final Object value;
  private final Index index;
  private final int[] collections;
  private final boolean ascending;

  private NativeStartEvidence(String className, String property, Object value,
      Index index, int[] collections, boolean ascending) {
    this.className = className;
    this.property = property;
    this.value = value;
    this.index = index;
    this.collections = collections;
    this.ascending = ascending;
  }

  public static NativeStartEvidence from(InternalExecutionPlan nativePlan, String className,
      String property, Object value) {
    if (!(value instanceof Long || value instanceof Integer || value instanceof String)) {
      return null;
    }
    var steps = nativePlan.getSteps();
    if (!pipeline(steps)) {
      return null;
    }
    var fetch = (FetchFromIndexStep) steps.getFirst();
    var evidence = new NativeStartEvidence(className, property, value, fetch.getDesc().getIndex(),
        ((GetValueFromIndexEntryStep) steps.get(1)).getFilterCollectionIds(), fetch.isOrderAsc());
    return evidence.matches(nativePlan, nativePlan.getContext(), className) ? evidence : null;
  }

  public boolean matches(InternalExecutionPlan plan, CommandContext context, String expectedClass) {
    var steps = plan.getSteps();
    if (!className.equals(expectedClass) || !pipeline(steps)) {
      return false;
    }
    var fetch = (FetchFromIndexStep) steps.getFirst();
    var desc = fetch.getDesc();
    // Reject composite, range, residual, alternate-index and collection-scope contracts.
    return desc.getIndex() == index
        && fetch.isOrderAsc() == ascending
        && index.getDefinition().getProperties().equals(List.of(property))
        && desc.getAdditionalRangeCondition() == null
        && (desc.getRemainingCondition() == null
            || equality(desc.getRemainingCondition(), context))
        && equality(desc.getKeyCondition(), context)
        && equality(((FilterStep) steps.get(2)).getWhereClause().getBaseExpression(), context)
        && className.equals(((FilterByClassStep) steps.get(3)).getClassName())
        && Arrays.equals(collections,
            ((GetValueFromIndexEntryStep) steps.get(1)).getFilterCollectionIds());
  }

  private static boolean pipeline(List<? extends ExecutionStep> steps) {
    return steps.size() == 4
        && steps.get(0).getClass() == FetchFromIndexStep.class
        && steps.get(1).getClass() == GetValueFromIndexEntryStep.class
        && steps.get(2).getClass() == FilterStep.class
        && steps.get(3).getClass() == FilterByClassStep.class;
  }

  private boolean equality(SQLBooleanExpression expression, CommandContext context) {
    if (expression != null && expression.getClass() == SQLAndBlock.class) {
      var children = ((SQLAndBlock) expression).getSubBlocks();
      return children.size() == 1 && equality(children.getFirst(), context);
    }
    if (expression != null && expression.getClass() == SQLOrBlock.class) {
      var children = ((SQLOrBlock) expression).getSubBlocks();
      return children.size() == 1 && equality(children.getFirst(), context);
    }
    if (expression != null && expression.getClass() == SQLNotBlock.class) {
      var not = (SQLNotBlock) expression;
      return !not.isNegate() && equality(not.getSub(), context);
    }
    if (expression == null || expression.getClass() != SQLBinaryCondition.class) {
      return false;
    }
    var binary = (SQLBinaryCondition) expression;
    return binary.getOperator().getClass() == SQLEqualsOperator.class
        && binary.getLeft().isBaseIdentifier()
        && property.equals(binary.getLeft().getDefaultAlias().getStringValue())
        && binary.getRight().getMathExpression() instanceof SQLBaseExpression base
        && base.getClass() == SQLBaseExpression.class
        && base.getIdentifier() == null && base.getModifier() == null
        && binary.getRight().getArrayConcatExpression() == null
        && binary.getRight().getRid() == null
        && binary.getRight().isEarlyCalculated(context)
        && Objects.equals(value, binary.getRight().execute((Result) null, context));
  }
}
