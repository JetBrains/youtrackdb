package com.jetbrains.youtrack.db.internal.core.sql.executor;

import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.api.exception.CommandExecutionException;
import com.jetbrains.youtrack.db.api.query.ExecutionStep;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.common.collection.MultiValue;
import com.jetbrains.youtrack.db.internal.common.concur.TimeoutException;
import com.jetbrains.youtrack.db.internal.common.util.RawPair;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.ExecutionThreadLocal;
import com.jetbrains.youtrack.db.internal.core.exception.CommandInterruptedException;
import com.jetbrains.youtrack.db.internal.core.index.CompositeKey;
import com.jetbrains.youtrack.db.internal.core.index.Index;
import com.jetbrains.youtrack.db.internal.core.index.IndexDefinition;
import com.jetbrains.youtrack.db.internal.core.index.IndexDefinitionMultiValue;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrack.db.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrack.db.internal.core.sql.executor.resultset.ExecutionStreamProducer;
import com.jetbrains.youtrack.db.internal.core.sql.executor.resultset.MultipleExecutionStream;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLAndBlock;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLBinaryCompareOperator;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLBinaryCondition;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLBooleanExpression;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLCollection;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLContainsAnyCondition;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLContainsCondition;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLContainsKeyOperator;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLContainsTextCondition;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLContainsValueCondition;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLContainsValueOperator;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLEqualsOperator;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLExpression;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLGeOperator;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLGtOperator;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLInCondition;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLLeOperator;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLLtOperator;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLValueExpression;
import com.jetbrains.youtrack.db.internal.core.tx.FrontendTransaction;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nullable;

public class FetchFromIndexStep extends AbstractExecutionStep {
  protected IndexSearchDescriptor desc;

  private boolean orderAsc;

  public FetchFromIndexStep(
      IndexSearchDescriptor desc, boolean orderAsc, CommandContext ctx, boolean profilingEnabled) {
    super(ctx, profilingEnabled);
    this.desc = desc;
    this.orderAsc = orderAsc;
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    var prev = this.prev;
    if (prev != null) {
      prev.start(ctx).close(ctx);
    }

    var session = ctx.getDatabaseSession();
    var tx = session.getTransactionInternal();
    tx.preProcessRecordsAndExecuteCallCallbacks();

    var streams = init(desc, orderAsc, ctx);
    var res =
        new ExecutionStreamProducer() {
          private final Iterator<Stream<RawPair<Object, RID>>> iter = streams.iterator();

          @Override
          public ExecutionStream next(CommandContext ctx) {
            var s = iter.next();
            return ExecutionStream.resultIterator(
                s.map((nextEntry) -> {
                  tx.preProcessRecordsAndExecuteCallCallbacks();
                  return readResult(ctx, nextEntry);
                }).iterator());
          }

          @Override
          public boolean hasNext(CommandContext ctx) {
            tx.preProcessRecordsAndExecuteCallCallbacks();
            return iter.hasNext();
          }

          @Override
          public void close(CommandContext ctx) {
            while (iter.hasNext()) {
              iter.next().close();
            }
          }
        };
    return new MultipleExecutionStream(res);
  }


  private static Result readResult(CommandContext ctx, RawPair<Object, RID> nextEntry) {
    if (ExecutionThreadLocal.isInterruptCurrentOperation()) {
      throw new CommandInterruptedException(ctx.getDatabaseSession(),
          "The command has been interrupted");
    }
    var key = nextEntry.first();
    Identifiable value = nextEntry.second();

    var result = new ResultInternal(ctx.getDatabaseSession());
    result.setProperty("key", convertKey(key));
    result.setProperty("rid", value);
    ctx.setVariable("$current", result);
    return result;
  }

  private static Object convertKey(Object key) {
    if (key instanceof CompositeKey) {
      return new ArrayList<>(((CompositeKey) key).getKeys());
    }
    return key;
  }

  private static List<Stream<RawPair<Object, RID>>> init(
      IndexSearchDescriptor desc, boolean isOrderAsc, CommandContext ctx) {

    var index = desc.getIndex();
    var condition = desc.getKeyCondition();
    var additionalRangeCondition = desc.getAdditionalRangeCondition();

    if (index.getDefinition() == null) {
      return Collections.emptyList();
    }
    if (condition == null) {
      return processFlatIteration(ctx.getDatabaseSession(), index, isOrderAsc);
    }

    return processAndBlock(index, condition, additionalRangeCondition, isOrderAsc, ctx);
  }

  /**
   * it's not key = [...] but a real condition on field names, already ordered (field names will be
   * ignored)
   */
  private static List<Stream<RawPair<Object, RID>>> processAndBlock(
      Index index,
      SQLBooleanExpression condition,
      SQLBinaryCondition additionalRangeCondition,
      boolean isOrderAsc,
      CommandContext ctx) {
    var fromKey = indexKeyFrom((SQLAndBlock) condition, additionalRangeCondition);
    var toKey = indexKeyTo((SQLAndBlock) condition, additionalRangeCondition);
    var fromKeyIncluded = indexKeyFromIncluded((SQLAndBlock) condition,
        additionalRangeCondition);
    var toKeyIncluded = indexKeyToIncluded((SQLAndBlock) condition, additionalRangeCondition);
    return multipleRange(
        index,
        fromKey,
        fromKeyIncluded,
        toKey,
        toKeyIncluded,
        condition,
        isOrderAsc,
        additionalRangeCondition,
        ctx);
  }

  private static List<Stream<RawPair<Object, RID>>> processFlatIteration(
      DatabaseSessionEmbedded session, Index index, boolean isOrderAsc) {
    List<Stream<RawPair<Object, RID>>> streams = new ArrayList<>();
    Set<Stream<RawPair<Object, RID>>> acquiredStreams =
        Collections.newSetFromMap(new IdentityHashMap<>());

    var stream = fetchNullKeys(session, index);
    if (stream != null) {
      acquiredStreams.add(stream);
      streams.add(stream);
    }

    stream = isOrderAsc ? index.stream(session) : index.descStream(session);
    if (acquiredStreams.add(stream)) {
      streams.add(stream);
    }
    return streams;
  }

  @Nullable
  private static Stream<RawPair<Object, RID>> fetchNullKeys(DatabaseSessionEmbedded session,
      Index index) {
    if (index.getDefinition().isNullValuesIgnored()) {
      return null;
    }

    return getStreamForNullKey(session, index);
  }

  private static List<Stream<RawPair<Object, RID>>> multipleRange(
      Index index,
      SQLCollection fromKey,
      boolean fromKeyIncluded,
      SQLCollection toKey,
      boolean toKeyIncluded,
      SQLBooleanExpression condition,
      boolean isOrderAsc,
      SQLBinaryCondition additionalRangeCondition,
      CommandContext ctx) {
    var session = ctx.getDatabaseSession();
    List<Stream<RawPair<Object, RID>>> streams = new ArrayList<>();
    Set<Stream<RawPair<Object, RID>>> acquiredStreams =
        Collections.newSetFromMap(new IdentityHashMap<>());
    var secondValueCombinations = cartesianProduct(fromKey, ctx);
    var thirdValueCombinations = cartesianProduct(toKey, ctx);

    var indexDef = index.getDefinition();

    var transaction = session.getActiveTransaction();
    for (var i = 0; i < secondValueCombinations.size(); i++) {

      var secondValue = secondValueCombinations.get(i).execute((Result) null, ctx);
      if (secondValue instanceof List
          && ((List<?>) secondValue).size() == 1
          && indexDef.getProperties().size() == 1
          && !(indexDef instanceof IndexDefinitionMultiValue)) {
        secondValue = ((List<?>) secondValue).getFirst();
      }
      secondValue = unboxResult(secondValue);
      var thirdValue = thirdValueCombinations.get(i).execute((Result) null, ctx);
      if (thirdValue instanceof List
          && ((List<?>) thirdValue).size() == 1
          && indexDef.getProperties().size() == 1
          && !(indexDef instanceof IndexDefinitionMultiValue)) {
        thirdValue = ((List<?>) thirdValue).getFirst();
      }
      thirdValue = unboxResult(thirdValue);

      try {
        secondValue = convertToIndexDefinitionTypes(session, condition, secondValue,
            indexDef.getTypes());
        thirdValue = convertToIndexDefinitionTypes(session, condition, thirdValue,
            indexDef.getTypes());
      } catch (Exception e) {
        // manage subquery that returns a single collection
        if (secondValue instanceof Collection && secondValue.equals(thirdValue)) {
          //noinspection rawtypes,unchecked
          ((Collection) secondValue)
              .forEach(
                  item -> {
                    Stream<RawPair<Object, RID>> stream;
                    var itemVal =
                        convertToIndexDefinitionTypes(session, condition, item,
                            indexDef.getTypes());
                    if (index.supportsOrderedIterations()) {

                      var from = toBetweenIndexKey(transaction, indexDef, itemVal);
                      var to = toBetweenIndexKey(transaction, indexDef, itemVal);
                      if (from == null && to == null) {
                        // manage null value explicitly, as the index API does not seem to work
                        // correctly in this
                        // case
                        stream = getStreamForNullKey(session, index);
                        if (acquiredStreams.add(stream)) {
                          streams.add(stream);
                        }
                      } else {
                        stream =
                            index.streamEntriesBetween(session,
                                from, fromKeyIncluded, to, toKeyIncluded, isOrderAsc);
                        if (acquiredStreams.add(stream)) {
                          streams.add(stream);
                        }
                      }

                    } else if (additionalRangeCondition == null
                        && allEqualities((SQLAndBlock) condition)) {
                      stream =
                          index.streamEntries(session,
                              toIndexKey(transaction, indexDef, itemVal), isOrderAsc);

                      if (acquiredStreams.add(stream)) {
                        streams.add(stream);
                      }

                    } else if (isFullTextIndex(index)) {
                      stream =
                          index.streamEntries(session,
                              toIndexKey(transaction, indexDef, itemVal), isOrderAsc);
                      if (acquiredStreams.add(stream)) {
                        streams.add(stream);
                      }
                    } else {
                      throw new UnsupportedOperationException(
                          "Cannot evaluate " + condition + " on index " + index);
                    }
                  });
        }

        // some problems in key conversion, so the params do not match the key types
        continue;
      }
      Stream<RawPair<Object, RID>> stream;
      if (index.supportsOrderedIterations()) {

        var from = toBetweenIndexKey(transaction, indexDef, secondValue);
        var to = toBetweenIndexKey(transaction, indexDef, thirdValue);

        if (from == null && to == null) {
          // manage null value explicitly, as the index API does not seem to work correctly in this
          // case
          stream = getStreamForNullKey(session, index);
          if (acquiredStreams.add(stream)) {
            streams.add(stream);
          }
        } else {
          stream = index.streamEntriesBetween(session, from, fromKeyIncluded, to, toKeyIncluded,
              isOrderAsc);
          if (acquiredStreams.add(stream)) {
            streams.add(stream);
          }
        }

      } else if (additionalRangeCondition == null && allEqualities((SQLAndBlock) condition)) {
        stream =
            index.streamEntries(session,
                toIndexKey(transaction, indexDef, secondValue),
                isOrderAsc);
        if (acquiredStreams.add(stream)) {
          streams.add(stream);
        }
      } else if (isFullTextIndex(index)) {
        stream =
            index.streamEntries(session,
                toIndexKey(transaction, indexDef, secondValue),
                isOrderAsc);
        if (acquiredStreams.add(stream)) {
          streams.add(stream);
        }
      } else {
        throw new UnsupportedOperationException(
            "Cannot evaluate " + condition + " on index " + index);
      }
    }
    return streams;
  }

  private static boolean isFullTextIndex(Index index) {
    return index.getType().equalsIgnoreCase("FULLTEXT")
        && !index.getAlgorithm().equalsIgnoreCase("LUCENE");
  }

  private static Stream<RawPair<Object, RID>> getStreamForNullKey(
      DatabaseSessionEmbedded session, Index index) {
    final var stream = index.getRids(session, null);
    return stream.map((rid) -> new RawPair<>(null, rid));
  }

  /**
   * this is for subqueries, when a Result is found
   *
   * <ul>
   *   <li>if it's a projection with a single column, the value is returned
   *   <li>if it's a document, the RID is returned
   * </ul>
   */
  private static Object unboxResult(Object value) {
    if (value instanceof List) {
      try (var stream = ((List<?>) value).stream()) {
        return stream.map(FetchFromIndexStep::unboxResult).collect(Collectors.toList());
      }
    }
    if (value instanceof Result) {
      if (((Result) value).isEntity()) {
        return ((Result) value).getIdentity();
      }

      var props = ((Result) value).getPropertyNames();
      if (props.size() == 1) {
        return ((Result) value).getProperty(props.getFirst());
      }
    }
    return value;
  }

  private static List<SQLCollection> cartesianProduct(SQLCollection key, CommandContext ctx) {
    return cartesianProduct(new SQLCollection(-1), key, ctx);
  }

  private static List<SQLCollection> cartesianProduct(
      SQLCollection head, SQLCollection key, CommandContext ctx) {
    if (key.getExpressions().isEmpty()) {
      return Collections.singletonList(head);
    }

    var db = ctx.getDatabaseSession();
    var nextElementInKey = key.getExpressions().getFirst();
    var value = nextElementInKey.execute(new ResultInternal(db), ctx);
    if (value instanceof Iterable && !(value instanceof Identifiable)) {
      List<SQLCollection> result = new ArrayList<>();
      for (var elemInKey : (Collection<?>) value) {
        var newHead = new SQLCollection(-1);
        for (var exp : head.getExpressions()) {
          newHead.add(exp.copy());
        }
        newHead.add(toExpression(elemInKey));
        var tail = key.copy();
        tail.getExpressions().removeFirst();
        result.addAll(cartesianProduct(newHead, tail, ctx));
      }
      return result;
    } else {
      var newHead = new SQLCollection(-1);
      for (var exp : head.getExpressions()) {
        newHead.add(exp.copy());
      }
      newHead.add(nextElementInKey);
      var tail = key.copy();
      tail.getExpressions().removeFirst();
      return cartesianProduct(newHead, tail, ctx);
    }
  }

  private static SQLExpression toExpression(Object value) {
    return new SQLValueExpression(value);
  }

  @Nullable
  private static Object convertToIndexDefinitionTypes(
      DatabaseSessionInternal session, SQLBooleanExpression condition, Object val,
      PropertyTypeInternal[] types) {
    if (val == null) {
      return null;
    }

    if (MultiValue.isMultiValue(val)) {
      List<Object> result = new ArrayList<>();

      var i = 0;
      for (var o : MultiValue.getMultiValueIterable(val)) {
        result.add(types[i++].convert(o, null, null, session));
      }

      if (condition instanceof SQLAndBlock) {
        for (var j = 0; j < ((SQLAndBlock) condition).getSubBlocks().size(); j++) {
          var subExp = ((SQLAndBlock) condition).getSubBlocks().get(j);
          if (subExp instanceof SQLBinaryCondition) {
            if (((SQLBinaryCondition) subExp).getOperator() instanceof SQLContainsKeyOperator) {
              Map<Object, Object> newValue = new HashMap<>();
              newValue.put(result.get(j), "");
              result.set(j, newValue);
            } else if (((SQLBinaryCondition) subExp).getOperator()
                instanceof SQLContainsValueOperator) {
              Map<Object, Object> newValue = new HashMap<>();
              newValue.put("", result.get(j));
              result.set(j, newValue);
            }
          } else if (subExp instanceof SQLContainsValueCondition) {
            Map<Object, Object> newValue = new HashMap<>();
            newValue.put("", result.get(j));
            result.set(j, newValue);
          }
        }
      }
      return result;
    }
    return types[0].convert(val, null, null, session);
  }

  private static boolean allEqualities(SQLAndBlock condition) {
    if (condition == null) {
      return false;
    }
    for (var exp : condition.getSubBlocks()) {
      if (exp instanceof SQLBinaryCondition) {
        if (!(((SQLBinaryCondition) exp).getOperator() instanceof SQLEqualsOperator)
            && !(((SQLBinaryCondition) exp).getOperator() instanceof SQLContainsKeyOperator)
            && !(((SQLBinaryCondition) exp).getOperator() instanceof SQLContainsValueOperator)) {
          return false;
        }
      } else if (!(exp instanceof SQLInCondition)) {
        return false;
      } // OK
    }
    return true;
  }

  private static Collection<?> toIndexKey(
      FrontendTransaction transaction, IndexDefinition definition, Object rightValue) {
    if (definition.getProperties().size() == 1 && rightValue instanceof Collection) {
      rightValue = ((Collection<?>) rightValue).iterator().next();
    }
    if (rightValue instanceof List) {
      rightValue = definition.createValue(transaction, (List<?>) rightValue);
    } else if (!(rightValue instanceof CompositeKey)) {
      rightValue = definition.createValue(transaction, rightValue);
    }
    if (!(rightValue instanceof Collection)) {
      rightValue = Collections.singleton(rightValue);
    }
    return (Collection<?>) rightValue;
  }

  private static Object toBetweenIndexKey(
      FrontendTransaction transaction, IndexDefinition definition, Object rightValue) {
    if (definition.getProperties().size() == 1 && rightValue instanceof Collection) {
      if (!((Collection<?>) rightValue).isEmpty()) {
        rightValue = ((Collection<?>) rightValue).iterator().next();
      } else {
        rightValue = null;
      }
    }

    if (rightValue instanceof Collection) {
      rightValue = definition.createValue(transaction, ((Collection<?>) rightValue).toArray());
    } else {
      rightValue = definition.createValue(transaction, rightValue);
    }

    return rightValue;
  }

  protected boolean isOrderAsc() {
    return orderAsc;
  }

  private static SQLCollection indexKeyFrom(SQLAndBlock keyCondition,
      SQLBinaryCondition additional) {
    var result = new SQLCollection(-1);
    for (var exp : keyCondition.getSubBlocks()) {
      var res = exp.resolveKeyFrom(additional);
      if (res != null) {
        result.add(res);
      }
    }
    return result;
  }

  private static SQLCollection indexKeyTo(SQLAndBlock keyCondition, SQLBinaryCondition additional) {
    var result = new SQLCollection(-1);
    for (var exp : keyCondition.getSubBlocks()) {
      var res = exp.resolveKeyTo(additional);
      if (res != null) {
        result.add(res);
      }
    }
    return result;
  }

  private static boolean indexKeyFromIncluded(
      SQLAndBlock keyCondition, SQLBinaryCondition additional) {
    var exp =
        keyCondition.getSubBlocks().getLast();
    var additionalOperator =
        Optional.ofNullable(additional).map(SQLBinaryCondition::getOperator).orElse(null);
    if (exp instanceof SQLBinaryCondition) {
      var operator = ((SQLBinaryCondition) exp).getOperator();
      if (isGreaterOperator(operator)) {
        return isIncludeOperator(operator);
      } else {
        return additionalOperator == null
            || (isIncludeOperator(additionalOperator) && isGreaterOperator(additionalOperator));
      }
    } else if (exp instanceof SQLInCondition || exp instanceof SQLContainsAnyCondition
        || exp instanceof SQLContainsCondition) {
      return additional == null
          || (isIncludeOperator(additionalOperator) && isGreaterOperator(additionalOperator));
    } else if (exp instanceof SQLContainsTextCondition) {
      return true;
    } else if (exp instanceof SQLContainsValueCondition) {
      SQLBinaryCompareOperator operator = ((SQLContainsValueCondition) exp).getOperator();
      if (isGreaterOperator(operator)) {
        return isIncludeOperator(operator);
      } else {
        return additionalOperator == null
            || (isIncludeOperator(additionalOperator) && isGreaterOperator(additionalOperator));
      }
    } else {
      throw new UnsupportedOperationException("Cannot execute index query with " + exp);
    }
  }

  private static boolean isGreaterOperator(SQLBinaryCompareOperator operator) {
    if (operator == null) {
      return false;
    }
    return operator instanceof SQLGeOperator || operator instanceof SQLGtOperator;
  }

  private static boolean isLessOperator(SQLBinaryCompareOperator operator) {
    if (operator == null) {
      return false;
    }
    return operator instanceof SQLLeOperator || operator instanceof SQLLtOperator;
  }

  private static boolean isIncludeOperator(SQLBinaryCompareOperator operator) {
    if (operator == null) {
      return false;
    }
    return operator instanceof SQLGeOperator || operator instanceof SQLLeOperator;
  }

  private static boolean indexKeyToIncluded(SQLAndBlock keyCondition,
      SQLBinaryCondition additional) {
    var exp =
        keyCondition.getSubBlocks().getLast();
    var additionalOperator =
        Optional.ofNullable(additional).map(SQLBinaryCondition::getOperator).orElse(null);
    if (exp instanceof SQLBinaryCondition) {
      var operator = ((SQLBinaryCondition) exp).getOperator();
      if (isLessOperator(operator)) {
        return isIncludeOperator(operator);
      } else {
        return additionalOperator == null
            || (isIncludeOperator(additionalOperator) && isLessOperator(additionalOperator));
      }
    } else if (exp instanceof SQLInCondition || exp instanceof SQLContainsAnyCondition
        || exp instanceof SQLContainsCondition) {
      return additionalOperator == null
          || (isIncludeOperator(additionalOperator) && isLessOperator(additionalOperator));
    } else if (exp instanceof SQLContainsTextCondition) {
      return true;
    } else if (exp instanceof SQLContainsValueCondition) {
      SQLBinaryCompareOperator operator = ((SQLContainsValueCondition) exp).getOperator();
      if (isLessOperator(operator)) {
        return isIncludeOperator(operator);
      } else {
        return additionalOperator == null
            || (isIncludeOperator(additionalOperator) && isLessOperator(additionalOperator));
      }
    } else {
      throw new UnsupportedOperationException("Cannot execute index query with " + exp);
    }
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    var result =
        ExecutionStepInternal.getIndent(depth, indent)
            + "+ FETCH FROM INDEX "
            + desc.getIndex().getName();
    if (profilingEnabled) {
      result += " (" + getCostFormatted() + ")";
    }
    if (desc.getKeyCondition() != null) {
      var additional =
          Optional.ofNullable(desc.getAdditionalRangeCondition())
              .map(rangeCondition -> " and " + rangeCondition)
              .orElse("");
      result +=
          ("\n"
              + ExecutionStepInternal.getIndent(depth, indent)
              + "  "
              + desc.getKeyCondition()
              + additional);
    }

    return result;
  }

  @Override
  public Result serialize(DatabaseSessionEmbedded session) {
    var result = ExecutionStepInternal.basicSerialize(session, this);
    result.setProperty("indexName", desc.getIndex().getName());
    if (desc.getKeyCondition() != null) {
      result.setProperty("condition", desc.getKeyCondition().serialize(session));
    }
    if (desc.getAdditionalRangeCondition() != null) {
      result.setProperty(
          "additionalRangeCondition", desc.getAdditionalRangeCondition().serialize(session));
    }
    result.setProperty("orderAsc", orderAsc);
    return result;
  }

  @Override
  public void deserialize(Result fromResult, DatabaseSessionInternal session) {
    try {
      ExecutionStepInternal.basicDeserialize(fromResult, this, session);
      String indexName = fromResult.getProperty("indexName");
      SQLAndBlock condition = null;
      if (fromResult.getProperty("condition") != null) {
        condition = (SQLAndBlock) SQLAndBlock.deserializeFromOResult(
            fromResult.getProperty("condition"));
      }
      SQLBinaryCondition additionalRangeCondition = null;
      if (fromResult.getProperty("additionalRangeCondition") != null) {
        additionalRangeCondition = new SQLBinaryCondition(-1);
        additionalRangeCondition.deserialize(fromResult.getProperty("additionalRangeCondition"));
      }
      var index = session.getSharedContext().getIndexManager().getIndex(indexName);
      desc = new IndexSearchDescriptor(index, condition, additionalRangeCondition, null);
      orderAsc = fromResult.getProperty("orderAsc");
    } catch (Exception e) {
      throw BaseException.wrapException(new CommandExecutionException(session, ""), e, session);
    }
  }

  @Override
  public void reset() {
    desc = null;
  }

  @Override
  public boolean canBeCached() {
    return true;
  }

  @Override
  public ExecutionStep copy(CommandContext ctx) {
    return new FetchFromIndexStep(desc, this.orderAsc, ctx, this.profilingEnabled);
  }

  @Override
  public void close() {
    super.close();
  }

  public String getIndexName() {
    return desc.getIndex().getName();
  }

  public IndexSearchDescriptor getDesc() {
    return desc;
  }
}
