package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.common.collection.MultiValue;
import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIdentifier;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLUnwind;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Intermediate step that "unwinds" (flattens) one or more collection-valued fields,
 * producing one output row per element in each collection.
 *
 * <pre>
 *  SQL:   SELECT name, tags FROM Article UNWIND tags
 *
 *  Input:  { name: "Intro",  tags: ["java", "db"] }
 *  Output: { name: "Intro",  tags: "java" }
 *          { name: "Intro",  tags: "db"   }
 * </pre>
 *
 * <p>Multiple fields can be unwound simultaneously; the step recursively unwinds each
 * field in order. If a field is not a collection, the row is emitted unchanged.
 *
 * <p>Empty collections produce a single row with the field set to {@code null}
 * (the row is never dropped). When multiple fields are unwound, the result is
 * a Cartesian product across all collection fields.
 *
 * @see SelectExecutionPlanner#handleUnwind
 */
public class UnwindStep extends AbstractExecutionStep {

  /** The UNWIND clause from the SQL statement. */
  private final SQLUnwind unwind;

  /** Pre-resolved field names to unwind. */
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
          "Cannot unwind without an upstream data source");
    }

    var resultSet = prev.start(ctx);
    var db = ctx.getDatabaseSession();
    return resultSet.flatMap((res, res2) -> fetchNextResults(db, res));
  }

  private ExecutionStream fetchNextResults(DatabaseSessionEmbedded db, Result res) {
    return ExecutionStream.resultIterator(unwind(db, res, unwindFields).iterator());
  }

  /**
   * Recursively unwinds the given fields. For each field, if the value is a collection
   * or array, produces one copy of the record per element (replacing the field value).
   * Multiple unwind fields are handled by recursing on the remaining fields for each copy.
   */
  private static Collection<Result> unwind(DatabaseSessionEmbedded db, final Result entity,
      final List<String> unwindFields) {
    final List<Result> result = new ArrayList<>();

    if (unwindFields.isEmpty()) {
      result.add(entity);
    } else {
      var firstField = unwindFields.get(0);
      final var nextFields = unwindFields.subList(1, unwindFields.size());

      var fieldValue = entity.getProperty(firstField);
      // EntityImpl values are treated as scalars (unwinding a document is not meaningful).
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
      // Empty collection: emit one row with the field set to null (preserving the row).
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

  /** Cacheable: the UNWIND clause is a structural AST node deep-copied per execution. */
  @Override
  public boolean canBeCached() {
    return true;
  }

  @Override
  public ExecutionStep copy(CommandContext ctx) {
    return new UnwindStep(unwind.copy(), ctx, profilingEnabled);
  }
}
