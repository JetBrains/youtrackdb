package com.jetbrains.youtrack.db.internal.core.sql.executor;

import com.jetbrains.youtrack.db.api.exception.CommandExecutionException;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.internal.common.collection.MultiValue;
import com.jetbrains.youtrack.db.internal.common.concur.TimeoutException;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLIdentifier;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLUnwind;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * unwinds a result-set.
 */
public class UnwindStep extends AbstractExecutionStep {

  private final SQLUnwind unwind;
  private final List<String> unwindFields;

  public UnwindStep(SQLUnwind unwind, CommandContext ctx, boolean profilingEnabled) {
    super(ctx, profilingEnabled);
    this.unwind = unwind;
    unwindFields =
        unwind.getItems().stream().map(SQLIdentifier::getStringValue).collect(Collectors.toList());
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    if (prev == null) {
      throw new CommandExecutionException(ctx.getDatabaseSession(),
          "Cannot expand without a target");
    }

    var resultSet = prev.start(ctx);
    var db = ctx.getDatabaseSession();
    return resultSet.flatMap((res, res2) -> fetchNextResults(db, res));
  }

  private ExecutionStream fetchNextResults(DatabaseSessionInternal db, Result res) {
    return ExecutionStream.resultIterator(unwind(db, res, unwindFields).iterator());
  }

  private static Collection<Result> unwind(DatabaseSessionInternal db, final Result entity,
      final List<String> unwindFields) {
    final List<Result> result = new ArrayList<>();

    if (unwindFields.isEmpty()) {
      result.add(entity);
    } else {
      var firstField = unwindFields.get(0);
      final var nextFields = unwindFields.subList(1, unwindFields.size());

      var fieldValue = entity.getProperty(firstField);
      if (fieldValue == null || fieldValue instanceof EntityImpl) {
        result.addAll(unwind(db, entity, nextFields));
        return result;
      }

      if (!(fieldValue instanceof Iterable) && !fieldValue.getClass().isArray()) {
        result.addAll(unwind(db, entity, nextFields));
        return result;
      }

      Iterator<?> iterator;
      if (fieldValue.getClass().isArray()) {
        iterator = MultiValue.getMultiValueIterator(fieldValue);
      } else {
        iterator = ((Iterable<?>) fieldValue).iterator();
      }
      if (!iterator.hasNext()) {
        var unwindedDoc = new ResultInternal(db);
        copy(entity, unwindedDoc);

        unwindedDoc.setProperty(firstField, null);
        result.addAll(unwind(db, unwindedDoc, nextFields));
      } else {
        do {
          var o = iterator.next();
          var unwindedDoc = new ResultInternal(db);
          copy(entity, unwindedDoc);
          unwindedDoc.setProperty(firstField, o);
          result.addAll(unwind(db, unwindedDoc, nextFields));
        } while (iterator.hasNext());
      }
    }

    return result;
  }

  private static void copy(Result from, ResultInternal to) {
    for (var prop : from.getPropertyNames()) {
      to.setProperty(prop, from.getProperty(prop));
    }
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    var spaces = ExecutionStepInternal.getIndent(depth, indent);
    return spaces + "+ " + unwind;
  }
}
