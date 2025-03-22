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
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLBetweenCondition;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLBinaryCompareOperator;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLBinaryCondition;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLBooleanExpression;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLCollection;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLContainsAnyCondition;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLContainsKeyOperator;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLContainsTextCondition;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLContainsValueCondition;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLContainsValueOperator;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLEqualsCompareOperator;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLExpression;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLGeOperator;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLGtOperator;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLInCondition;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLLeOperator;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLLtOperator;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLValueExpression;
import com.jetbrains.youtrack.db.internal.core.tx.FrontendTransactionImpl;
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

/**
 *
 */
public class FetchFromIndexStep extends AbstractExecutionStep {

  protected IndexSearchDescriptor desc;

  private boolean orderAsc;

  private long count = 0;

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

    var streams = init(desc, orderAsc, ctx);

    var res =
        new ExecutionStreamProducer() {
          private final Iterator<Stream<RawPair<Object, RID>>> iter = streams.iterator();

          @Override
          public ExecutionStream next(CommandContext ctx) {
            var s = iter.next();
            return ExecutionStream.resultIterator(
                s.map((nextEntry) -> {
                  var session = ctx.getDatabaseSession();
                  var tx = session.getTransactionInternal();

                  if (tx instanceof FrontendTransactionImpl frontendTransactionOptimistic) {
                    frontendTransactionOptimistic.preProcessRecordsAndExecuteCallCallbacks();
                  }

                  return readResult(ctx, nextEntry);
                }).iterator());
          }

          @Override
          public boolean hasNext(CommandContext ctx) {
            return iter.hasNext();
          }

          @Override
          public void close(CommandContext ctx) {
            while (iter.hasNext()) {
              iter.next().close();
            }
          }
        };
    return new MultipleExecutionStream(res).onClose(this::close);
  }

  private void close(CommandContext context) {
    updateIndexStats();
  }

  private Result readResult(CommandContext ctx, RawPair<Object, RID> nextEntry) {
    if (ExecutionThreadLocal.isInterruptCurrentOperation()) {
      throw new CommandInterruptedException(ctx.getDatabaseSession(),
          "The command has been interrupted");
    }
    count++;
    var key = nextEntry.first;
    Identifiable value = nextEntry.second;

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

  private void updateIndexStats() {
    // stats
    var stats = QueryStats.get(ctx.getDatabaseSession());
    var index = desc.getIndex();
    var condition = desc.getKeyCondition();
    var additionalRangeCondition = desc.getAdditionalRangeCondition();
    if (index == null) {
      return; // this could happen, if not inited yet
    }
    var indexName = index.getName();
    var range = false;
    var size = 0;

    if (condition != null) {
      if (condition instanceof SQLBinaryCondition) {
        size = 1;
      } else if (condition instanceof SQLBetweenCondition) {
        size = 1;
        range = true;
      } else if (condition instanceof SQLAndBlock andBlock) {
        size = andBlock.getSubBlocks().size();
        var lastOp = andBlock.getSubBlocks()
            .get(andBlock.getSubBlocks().size() - 1);
        if (lastOp instanceof SQLBinaryCondition) {
          var op = ((SQLBinaryCondition) lastOp).getOperator();
          range = op.isRangeOperator();
        }
      } else if (condition instanceof SQLInCondition) {
        size = 1;
      }
    }
    stats.pushIndexStats(indexName, size, range, additionalRangeCondition != null, count);
  }

  private static List<Stream<RawPair<Object, RID>>> init(
      IndexSearchDescriptor desc, boolean isOrderAsc, CommandContext ctx) {

    var index = desc.getIndex();
    var condition = desc.getKeyCondition();
    var additionalRangeCondition = desc.getAdditionalRangeCondition();

    if (index.getDefinition() == null) {
      return Collections.emptyList();
    }
    return switch (condition) {
      case null -> processFlatIteration(ctx.getDatabaseSession(), index, isOrderAsc);
      case SQLBinaryCondition sqlBinaryCondition ->
          processBinaryCondition(ctx.getDatabaseSession(), index, condition, isOrderAsc, ctx);
      case SQLBetweenCondition sqlBetweenCondition ->
          processBetweenCondition(index, condition, isOrderAsc, ctx);
      case SQLAndBlock sqlAndBlock ->
          processAndBlock(index, condition, additionalRangeCondition, isOrderAsc, ctx);
      case SQLInCondition sqlInCondition -> processInCondition(index, condition, ctx, isOrderAsc);
      default -> throw new CommandExecutionException(ctx.getDatabaseSession(),
          "search for index for " + condition + " is not supported yet");
    };
  }

  private static List<Stream<RawPair<Object, RID>>> processInCondition(
      Index index, SQLBooleanExpression condition, CommandContext ctx, boolean orderAsc) {
    List<Stream<RawPair<Object, RID>>> streams = new ArrayList<>();
    Set<Stream<RawPair<Object, RID>>> acquiredStreams =
        Collections.newSetFromMap(new IdentityHashMap<>());
    var definition = index.getDefinition();
    var inCondition = (SQLInCondition) condition;

    var left = inCondition.getLeft();
    if (!left.toString().equalsIgnoreCase("key")) {
      throw new CommandExecutionException(ctx.getDatabaseSession(),
          "search for index for " + condition + " is not supported yet");
    }
    var rightValue = inCondition.evaluateRight((Result) null, ctx);
    var equals = new SQLEqualsCompareOperator(-1);
    if (MultiValue.isMultiValue(rightValue)) {
      for (var item : MultiValue.getMultiValueIterable(rightValue)) {
        if (item instanceof Result) {
          if (((Result) item).isEntity()) {
            item = ((Result) item).asEntity();
          } else if (((Result) item).getPropertyNames().size() == 1) {
            item =
                ((Result) item).getProperty(
                    ((Result) item).getPropertyNames().iterator().next());
          }
        }

        var localCursor =
            createCursor(ctx.getDatabaseSession(), index, equals, definition, item, orderAsc,
                condition);

        if (acquiredStreams.add(localCursor)) {
          streams.add(localCursor);
        }
      }
    } else {
      var stream =
          createCursor(
              ctx.getDatabaseSession(), index, equals, definition, rightValue, orderAsc, condition);
      if (acquiredStreams.add(stream)) {
        streams.add(stream);
      }
    }

    return streams;
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
      DatabaseSessionInternal session, Index index, boolean isOrderAsc) {
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

  private static Stream<RawPair<Object, RID>> fetchNullKeys(DatabaseSessionInternal session,
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
    var db = ctx.getDatabaseSession();
    List<Stream<RawPair<Object, RID>>> streams = new ArrayList<>();
    Set<Stream<RawPair<Object, RID>>> acquiredStreams =
        Collections.newSetFromMap(new IdentityHashMap<>());
    var secondValueCombinations = cartesianProduct(fromKey, ctx);
    var thirdValueCombinations = cartesianProduct(toKey, ctx);

    var indexDef = index.getDefinition();

    for (var i = 0; i < secondValueCombinations.size(); i++) {

      var secondValue = secondValueCombinations.get(i).execute((Result) null, ctx);
      if (secondValue instanceof List
          && ((List<?>) secondValue).size() == 1
          && indexDef.getFields().size() == 1
          && !(indexDef instanceof IndexDefinitionMultiValue)) {
        secondValue = ((List<?>) secondValue).get(0);
      }
      secondValue = unboxOResult(secondValue);
      // TODO unwind collections!
      var thirdValue = thirdValueCombinations.get(i).execute((Result) null, ctx);
      if (thirdValue instanceof List
          && ((List<?>) thirdValue).size() == 1
          && indexDef.getFields().size() == 1
          && !(indexDef instanceof IndexDefinitionMultiValue)) {
        thirdValue = ((List<?>) thirdValue).get(0);
      }
      thirdValue = unboxOResult(thirdValue);

      try {
        secondValue = convertToIndexDefinitionTypes(db, condition, secondValue,
            indexDef.getTypes());
        thirdValue = convertToIndexDefinitionTypes(db, condition, thirdValue, indexDef.getTypes());
      } catch (Exception e) {
        // manage subquery that returns a single collection
        if (secondValue instanceof Collection && secondValue.equals(thirdValue)) {
          ((Collection) secondValue)
              .forEach(
                  item -> {
                    Stream<RawPair<Object, RID>> stream;
                    var itemVal =
                        convertToIndexDefinitionTypes(db, condition, item, indexDef.getTypes());
                    if (index.supportsOrderedIterations()) {

                      var from = toBetweenIndexKey(db, indexDef, itemVal);
                      var to = toBetweenIndexKey(db, indexDef, itemVal);
                      if (from == null && to == null) {
                        // manage null value explicitly, as the index API does not seem to work
                        // correctly in this
                        // case
                        stream = getStreamForNullKey(db, index);
                        if (acquiredStreams.add(stream)) {
                          streams.add(stream);
                        }
                      } else {
                        stream =
                            index.streamEntriesBetween(db,
                                from, fromKeyIncluded, to, toKeyIncluded, isOrderAsc);
                        if (acquiredStreams.add(stream)) {
                          streams.add(stream);
                        }
                      }

                    } else if (additionalRangeCondition == null
                        && allEqualities((SQLAndBlock) condition)) {
                      stream =
                          index.streamEntries(db,
                              toIndexKey(db, indexDef, itemVal), isOrderAsc);

                      if (acquiredStreams.add(stream)) {
                        streams.add(stream);
                      }

                    } else if (isFullTextIndex(index)) {
                      stream =
                          index.streamEntries(db,
                              toIndexKey(db, indexDef, itemVal), isOrderAsc);
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

        var from = toBetweenIndexKey(db, indexDef, secondValue);
        var to = toBetweenIndexKey(db, indexDef, thirdValue);

        if (from == null && to == null) {
          // manage null value explicitly, as the index API does not seem to work correctly in this
          // case
          stream = getStreamForNullKey(db, index);
          if (acquiredStreams.add(stream)) {
            streams.add(stream);
          }
        } else {
          stream = index.streamEntriesBetween(db, from, fromKeyIncluded, to, toKeyIncluded,
              isOrderAsc);
          if (acquiredStreams.add(stream)) {
            streams.add(stream);
          }
        }

      } else if (additionalRangeCondition == null && allEqualities((SQLAndBlock) condition)) {
        stream =
            index.streamEntries(db, toIndexKey(ctx.getDatabaseSession(), indexDef, secondValue),
                isOrderAsc);
        if (acquiredStreams.add(stream)) {
          streams.add(stream);
        }
      } else if (isFullTextIndex(index)) {
        stream =
            index.streamEntries(db, toIndexKey(ctx.getDatabaseSession(), indexDef, secondValue),
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
      DatabaseSessionInternal session, Index index) {
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
  private static Object unboxOResult(Object value) {
    if (value instanceof List) {
      try (var stream = ((List<?>) value).stream()) {
        return stream.map(FetchFromIndexStep::unboxOResult).collect(Collectors.toList());
      }
    }
    if (value instanceof Result) {
      if (((Result) value).isEntity()) {
        return ((Result) value).getIdentity();
      }

      var props = ((Result) value).getPropertyNames();
      if (props.size() == 1) {
        return ((Result) value).getProperty(props.iterator().next());
      }
    }
    return value;
  }

  private static List<SQLCollection> cartesianProduct(SQLCollection key, CommandContext ctx) {
    return cartesianProduct(new SQLCollection(-1), key, ctx); // TODO
  }

  private static List<SQLCollection> cartesianProduct(
      SQLCollection head, SQLCollection key, CommandContext ctx) {
    if (key.getExpressions().isEmpty()) {
      return Collections.singletonList(head);
    }

    var db = ctx.getDatabaseSession();
    var nextElementInKey = key.getExpressions().get(0);
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
        tail.getExpressions().remove(0);
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
      tail.getExpressions().remove(0);
      return cartesianProduct(newHead, tail, ctx);
    }
  }

  private static SQLExpression toExpression(Object value) {
    return new SQLValueExpression(value);
  }

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
        if (!(((SQLBinaryCondition) exp).getOperator() instanceof SQLEqualsCompareOperator)
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

  private static List<Stream<RawPair<Object, RID>>> processBetweenCondition(
      Index index, SQLBooleanExpression condition, boolean isOrderAsc,
      CommandContext ctx) {
    List<Stream<RawPair<Object, RID>>> streams = new ArrayList<>();

    var definition = index.getDefinition();
    var key = ((SQLBetweenCondition) condition).getFirst();
    if (!key.toString().equalsIgnoreCase("key")) {
      throw new CommandExecutionException(ctx.getDatabaseSession(),
          "search for index for " + condition + " is not supported yet");
    }
    var second = ((SQLBetweenCondition) condition).getSecond();
    var third = ((SQLBetweenCondition) condition).getThird();

    var secondValue = second.execute((Result) null, ctx);
    secondValue = unboxOResult(secondValue);
    var thirdValue = third.execute((Result) null, ctx);
    thirdValue = unboxOResult(thirdValue);
    var db = ctx.getDatabaseSession();
    var stream =
        index.streamEntriesBetween(db,
            toBetweenIndexKey(db, definition, secondValue),
            true,
            toBetweenIndexKey(db, definition, thirdValue),
            true, isOrderAsc);
    streams.add(stream);
    return streams;
  }

  private static List<Stream<RawPair<Object, RID>>> processBinaryCondition(
      DatabaseSessionInternal session,
      Index index,
      SQLBooleanExpression condition,
      boolean isOrderAsc,
      CommandContext ctx) {
    List<Stream<RawPair<Object, RID>>> streams = new ArrayList<>();
    Set<Stream<RawPair<Object, RID>>> acquiredStreams =
        Collections.newSetFromMap(new IdentityHashMap<>());

    var definition = index.getDefinition();
    var operator = ((SQLBinaryCondition) condition).getOperator();
    var left = ((SQLBinaryCondition) condition).getLeft();
    if (!left.toString().equalsIgnoreCase("key")) {
      throw new CommandExecutionException(ctx.getDatabaseSession(),
          "search for index for " + condition + " is not supported yet");
    }
    var rightValue = ((SQLBinaryCondition) condition).getRight().execute((Result) null, ctx);
    var stream =
        createCursor(session, index, operator, definition, rightValue, isOrderAsc, condition);
    if (acquiredStreams.add(stream)) {
      streams.add(stream);
    }

    return streams;
  }

  private static Collection<?> toIndexKey(
      DatabaseSessionInternal session, IndexDefinition definition, Object rightValue) {
    if (definition.getFields().size() == 1 && rightValue instanceof Collection) {
      rightValue = ((Collection<?>) rightValue).iterator().next();
    }
    if (rightValue instanceof List) {
      rightValue = definition.createValue(session, (List<?>) rightValue);
    } else if (!(rightValue instanceof CompositeKey)) {
      rightValue = definition.createValue(session, rightValue);
    }
    if (!(rightValue instanceof Collection)) {
      rightValue = Collections.singleton(rightValue);
    }
    return (Collection<?>) rightValue;
  }

  private static Object toBetweenIndexKey(
      DatabaseSessionInternal session, IndexDefinition definition, Object rightValue) {
    if (definition.getFields().size() == 1 && rightValue instanceof Collection) {
      if (!((Collection<?>) rightValue).isEmpty()) {
        rightValue = ((Collection<?>) rightValue).iterator().next();
      } else {
        rightValue = null;
      }
    }

    if (rightValue instanceof Collection) {
      rightValue = definition.createValue(session, ((Collection<?>) rightValue).toArray());
    } else {
      rightValue = definition.createValue(session, rightValue);
    }

    return rightValue;
  }

  private static Stream<RawPair<Object, RID>> createCursor(
      DatabaseSessionInternal session,
      Index index,
      SQLBinaryCompareOperator operator,
      IndexDefinition definition,
      Object value,
      boolean orderAsc,
      SQLBooleanExpression condition) {
    if (operator instanceof SQLEqualsCompareOperator
        || operator instanceof SQLContainsKeyOperator
        || operator instanceof SQLContainsValueOperator) {
      return index.streamEntries(session, toIndexKey(session, definition, value), orderAsc);
    } else if (operator instanceof SQLGeOperator) {
      return index.streamEntriesMajor(session, value, true, orderAsc);
    } else if (operator instanceof SQLGtOperator) {
      return index.streamEntriesMajor(session, value, false, orderAsc);
    } else if (operator instanceof SQLLeOperator) {
      return index.streamEntriesMinor(session, value, true, orderAsc);
    } else if (operator instanceof SQLLtOperator) {
      return index.streamEntriesMinor(session, value, false, orderAsc);
    } else {
      throw new CommandExecutionException(session,
          "search for index for " + condition + " is not supported yet");
    }
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
        keyCondition.getSubBlocks().get(keyCondition.getSubBlocks().size() - 1);
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
    } else if (exp instanceof SQLInCondition || exp instanceof SQLContainsAnyCondition) {
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
        keyCondition.getSubBlocks().get(keyCondition.getSubBlocks().size() - 1);
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
    } else if (exp instanceof SQLInCondition || exp instanceof SQLContainsAnyCondition) {
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
  public Result serialize(DatabaseSessionInternal session) {
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
      SQLBooleanExpression condition = null;
      if (fromResult.getProperty("condition") != null) {
        condition = SQLBooleanExpression.deserializeFromOResult(
            fromResult.getProperty("condition"));
      }
      SQLBinaryCondition additionalRangeCondition = null;
      if (fromResult.getProperty("additionalRangeCondition") != null) {
        additionalRangeCondition = new SQLBinaryCondition(-1);
        additionalRangeCondition.deserialize(fromResult.getProperty("additionalRangeCondition"));
      }
      var index = session.getMetadata().getIndexManagerInternal().getIndex(session, indexName);
      desc = new IndexSearchDescriptor(index, condition, additionalRangeCondition, null);
      orderAsc = fromResult.getProperty("orderAsc");
    } catch (Exception e) {
      throw BaseException.wrapException(new CommandExecutionException(session, ""), e, session);
    }
  }

  @Override
  public void reset() {
    desc = null;
    count = 0;
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
}
